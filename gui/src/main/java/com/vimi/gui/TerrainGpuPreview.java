package com.vimi.gui;

import com.vibeminer.rendering.bgfx.TerrainTextureStatus;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;

import java.util.Objects;
import java.util.function.Consumer;

/** FX-owned terrain presentation state over the application-wide bounded renderer lifecycle. */
final class TerrainGpuPreview implements AutoCloseable {
    record Status(boolean busy, boolean ready, String message, TerrainTextureStatus textures) {
        Status(boolean busy, boolean ready, String message) {
            this(busy, ready, message, null);
        }
    }

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
    // Borrowed identities only: the current caller owns snapshots; the service retains its own
    // claim.
    private WorldTextureSnapshot textures, failedTextures;
    private TerrainTextureStatus textureStatus;
    private Pending awaitingAck;
    private long generation, revision, barrier, presentedRevision, sequence;
    private int width, height;
    private boolean running, firstFrame, closed;
    private String backend = "";

    TerrainGpuPreview(
            GpuPreviewService service,
            Consumer<Runnable> schedule,
            Consumer<Status> status,
            FrameSink sink,
            Runnable fallback) {
        this.service = Objects.requireNonNull(service);
        this.schedule = Objects.requireNonNull(schedule);
        this.status = Objects.requireNonNull(status);
        this.sink = Objects.requireNonNull(sink);
        this.fallback = Objects.requireNonNull(fallback);
        frames = new ModelPreviewView.LatestDelivery<>(schedule, this::present);
    }

    /** Scene construction and normalization belong to the caller's bounded preparation worker. */
    boolean request(
            TerrainScene next, TerrainCamera camera, int requestedWidth, int requestedHeight) {
        return request(next, null, camera, requestedWidth, requestedHeight);
    }

    boolean request(
            TerrainScene next,
            WorldTextureSnapshot nextTextures,
            TerrainCamera camera,
            int requestedWidth,
            int requestedHeight) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(camera);
        if (closed) {
            return false;
        }
        // Validate dimensions before mutating generation or control revision.
        new GpuPreviewService.TerrainRequest(1, requestedWidth, requestedHeight, camera);
        if (nextTextures != null && nextTextures.scene() != next) {
            throw new IllegalArgumentException("Terrain texture snapshot belongs to another scene");
        }
        // Empty batches use the unchanged height path and must not cause a pointless owner restart.
        var admitted = nextTextures != null && nextTextures.atlasCount() > 0 ? nextTextures : null;
        boolean deferTextures = scene == next && running && !firstFrame && textures != admitted;
        if (scene != next || textures != admitted && !deferTextures) {
            scene = next;
            textures = admitted;
            failed = null;
            failedTextures = null;
            running = false;
        }
        if (failed == scene && failedTextures == textures) {
            return false;
        }
        long control = ++revision;
        if (!running || width != requestedWidth || height != requestedHeight) {
            barrier = control;
            if (running) {
                firstFrame = false;
                status.accept(new Status(true, false, "Updating GPU terrain size…", textureStatus));
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
            textureStatus = null;
            awaitingAck = null;
            long token = ++generation;
            status.accept(new Status(true, false, "Preparing GPU terrain…"));
            try {
                service.openTerrain(
                                owner,
                                scene,
                                textures,
                                update -> schedule.accept(() -> state(token, update)),
                                frame -> frames.submit(new Pending(token, frame)))
                        .whenComplete(
                                (ignored, error) -> {
                                    if (error != null) {
                                        schedule.accept(
                                                () ->
                                                        fail(
                                                                token,
                                                                "GPU terrain unavailable; using"
                                                                        + " software rendering."));
                                    }
                                });
            } catch (RuntimeException unavailable) {
                fail(token, "GPU terrain unavailable; using software rendering.");
                return false;
            }
        }
        service.updateTerrain(
                owner, new GpuPreviewService.TerrainRequest(control, width, height, camera));
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
                textureStatus = update.textures();
                if (textures != null && textureStatus == null) {
                    fail(token, "GPU terrain texture status missing; using software rendering.");
                    return;
                }
                status.accept(
                        new Status(
                                !firstFrame,
                                firstFrame,
                                firstFrame
                                        ? presentationMessage()
                                        : "Waiting for the first GPU terrain frame…",
                                textureStatus));
                if (awaitingAck != null) {
                    var waiting = awaitingAck;
                    awaitingAck = null;
                    present(waiting);
                }
            }
            default -> status.accept(new Status(true, false, update.message()));
        }
    }

    private void present(Pending pending) {
        var frame = pending.frame();
        if (closed
                || !running
                || pending.generation() != generation
                || frame.sequence() <= sequence
                || frame.controlRevision() < barrier
                || frame.controlRevision() < presentedRevision
                || frame.controlRevision() > revision
                || frame.width() != width
                || frame.height() != height) {
            return;
        }
        if (textures != null && textureStatus == null) {
            if (awaitingAck == null
                    || frame.sequence() > awaitingAck.frame().sequence()
                            && frame.controlRevision() >= awaitingAck.frame().controlRevision()) {
                awaitingAck = pending;
            }
            return;
        }
        try {
            if (!sink.present(frame)) {
                return;
            }
            sequence = frame.sequence();
            presentedRevision = frame.controlRevision();
            firstFrame = true;
            status.accept(new Status(false, true, presentationMessage(), textureStatus));
        } catch (RuntimeException failure) {
            fail(pending.generation(), "GPU terrain upload failed; using software rendering.");
        }
    }

    private void fail(long token, String message) {
        if (closed || token != generation || !running) {
            return;
        }
        failed = scene;
        failedTextures = textures;
        awaitingAck = null;
        frames.clear();
        running = false;
        ++generation;
        service.closeOwner(owner);
        status.accept(new Status(false, false, message, textureStatus));
        fallback.run();
    }

    private String presentationMessage() {
        String message = backend.isEmpty() ? "GPU terrain" : backend;
        if (textureStatus == null) {
            return message;
        }
        return switch (textureStatus.state()) {
            case READY -> message + " · Basic terrain textures";
            case PARTIAL ->
                    message
                            + " · Partial basic terrain textures ("
                            + textureStatus.coveredRects()
                            + "/"
                            + textureStatus.totalRects()
                            + " regions)";
            case UNAVAILABLE -> message + " · Height shading; terrain textures unavailable";
        };
    }

    boolean usesTextures(WorldTextureSnapshot snapshot) {
        return running && textures == snapshot;
    }

    TerrainTextureStatus textureStatus() {
        return running ? textureStatus : null;
    }

    boolean canReplaceScene() {
        return !running || firstFrame;
    }

    boolean ready() {
        return running && firstFrame;
    }

    void retry() {
        failed = null;
        failedTextures = null;
    }

    void hide() {
        frames.clear();
        awaitingAck = null;
        textureStatus = null;
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
