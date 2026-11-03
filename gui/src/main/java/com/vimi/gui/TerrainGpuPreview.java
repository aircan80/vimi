package com.vimi.gui;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.util.Objects;
import java.util.function.Consumer;

/** FX-owned terrain presentation state over the application-wide bounded renderer lifecycle. */
final class TerrainGpuPreview implements AutoCloseable {
    record Status(boolean busy, boolean ready, String message) {}

    @FunctionalInterface
    interface FrameSink {
        /** True only after the current viewport actually accepted its pixel upload. */
        boolean present(GpuPreviewService.Frame frame);
    }

    private record Pending(long generation, GpuPreviewService.Frame frame) {}

    private final Object owner = new Object();
    private final GpuPreviewService service;
    private final Consumer<Runnable> schedule;
    private final Consumer<Status> status;
    private final FrameSink sink;
    private final Runnable fallback;
    private final ModelPreviewView.LatestDelivery<Pending> frames;
    private TerrainScene scene, failed;
    private long generation, revision, barrier, presentedRevision, sequence;
    private int width, height;
    private boolean running, firstFrame, closed;
    private String backend = "";

    TerrainGpuPreview(GpuPreviewService service, Consumer<Runnable> schedule,
            Consumer<Status> status, FrameSink sink, Runnable fallback) {
        this.service = Objects.requireNonNull(service);
        this.schedule = Objects.requireNonNull(schedule);
        this.status = Objects.requireNonNull(status);
        this.sink = Objects.requireNonNull(sink);
        this.fallback = Objects.requireNonNull(fallback);
        frames = new ModelPreviewView.LatestDelivery<>(schedule, this::present);
    }

    /** Scene construction and normalization belong to the caller's bounded preparation worker. */
    boolean request(TerrainScene next, TerrainCamera camera, int requestedWidth, int requestedHeight) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(camera);
        if (closed) {
            return false;
        }
        // Validate dimensions before mutating generation or control revision.
        new GpuPreviewService.TerrainRequest(1, requestedWidth, requestedHeight, camera);
        if (scene != next) {
            scene = next;
            failed = null;
            running = false;
        }
        if (failed == scene) {
            return false;
        }
        long control = ++revision;
        if (!running || width != requestedWidth || height != requestedHeight) {
            barrier = control;
            if (running) {
                firstFrame = false;
                status.accept(new Status(true, false, "Updating GPU terrain size…"));
            }
        }
        width = requestedWidth;
        height = requestedHeight;
        if (!running) {
            running = true;
            firstFrame = false;
            sequence = 0;
            presentedRevision = 0;
            backend = "";
            long token = ++generation;
            status.accept(new Status(true, false, "Preparing GPU terrain…"));
            service.openTerrain(owner, scene,
                    update -> schedule.accept(() -> state(token, update)),
                    frame -> frames.submit(new Pending(token, frame)))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            schedule.accept(() -> fail(token,
                                    "GPU terrain unavailable; using software rendering."));
                        }
                    });
        }
        service.updateTerrain(owner,
                new GpuPreviewService.TerrainRequest(control, width, height, camera));
        return true;
    }

    private void state(long token, GpuPreviewService.Status update) {
        if (closed || token != generation || !running) {
            return;
        }
        switch (update.state()) {
            case FAILED -> fail(token, update.message());
            case CLOSED -> fail(token, "GPU terrain ended; using software rendering.");
            case READY -> {
                backend = update.message();
                status.accept(new Status(!firstFrame, firstFrame,
                        firstFrame ? backend : "Waiting for the first GPU terrain frame…"));
            }
            default -> status.accept(new Status(true, false, update.message()));
        }
    }

    private void present(Pending pending) {
        var frame = pending.frame();
        if (closed || !running || pending.generation() != generation
                || frame.sequence() <= sequence || frame.controlRevision() < barrier
                || frame.controlRevision() < presentedRevision || frame.controlRevision() > revision
                || frame.width() != width || frame.height() != height) {
            return;
        }
        try {
            if (!sink.present(frame)) {
                return;
            }
            sequence = frame.sequence();
            presentedRevision = frame.controlRevision();
            firstFrame = true;
            status.accept(new Status(false, true,
                    backend.isEmpty() ? "GPU terrain" : backend));
        } catch (RuntimeException failure) {
            fail(pending.generation(), "GPU terrain upload failed; using software rendering.");
        }
    }

    private void fail(long token, String message) {
        if (closed || token != generation || !running) {
            return;
        }
        failed = scene;
        frames.clear();
        running = false;
        ++generation;
        service.closeOwner(owner);
        status.accept(new Status(false, false, message));
        fallback.run();
    }

    boolean ready() {
        return running && firstFrame;
    }

    void retry() {
        failed = null;
    }

    void hide() {
        frames.clear();
        ++generation;
        running = false;
        service.closeOwner(owner);
    }

    @Override
    public void close() {
        if (!closed) {
            hide();
            closed = true;
        }
    }
}
