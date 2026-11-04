package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** UI-owned latest scene loading; one active worker, one pending descriptor, no pixel copies. */
final class WorldTextureLoading implements AutoCloseable {
    record Status(boolean busy, String message) {}
    private record View(long generation, BuildWorld source, WorldService.MapInfo map,
            List<WorldFormats.Tile> tiles, TerrainScene scene) {}

    private static final class Job {
        final View view;
        final Cancellation token = new Cancellation();
        final AtomicReference<WorldTextureSnapshot> result = new AtomicReference<>();
        volatile boolean canceled;
        Job(View view) { this.view = view; }
        void releaseResult() {
            var old = result.getAndSet(null);
            if (old != null) old.close();
        }
    }

    private final ThreadPoolExecutor worker = executor("world-texture-loading");
    private final ThreadPoolExecutor cancellation = executor("world-texture-retirement");
    private final Consumer<Runnable> schedule;
    private final Consumer<Status> status;
    private final Consumer<WorldTextureSnapshot> deliver;
    private final ModelPreviewView.LatestDelivery<Runnable> progress;
    private View view;
    private Job active;
    private long generation, attempted;
    private volatile boolean closed;

    WorldTextureLoading(Consumer<Runnable> schedule, Consumer<Status> status,
            Consumer<WorldTextureSnapshot> deliver) {
        this.schedule = Objects.requireNonNull(schedule);
        this.status = Objects.requireNonNull(status);
        this.deliver = Objects.requireNonNull(deliver);
        progress = new ModelPreviewView.LatestDelivery<>(schedule, Runnable::run);
        // A discarded queued cancellation belongs to an already completed former job. A still
        // active job is canceled only once and cannot be replaced until completed() retires it.
        cancellation.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    private static ThreadPoolExecutor executor(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    void request(BuildWorld source, WorldService.MapInfo map, List<WorldFormats.Tile> tiles,
            TerrainScene scene) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(map);
        Objects.requireNonNull(scene);
        if (tiles.isEmpty() || tiles.size() > 9 || tiles.size() != scene.tiles().size()) {
            throw new IllegalArgumentException("Texture request requires matching 1..9 source tiles");
        }
        if (closed) return;
        if (view != null && view.source() == source && view.scene() == scene && view.map().equals(map)) return;
        view = new View(++generation, source, map, List.copyOf(tiles), scene);
        retireActive();
        status.accept(new Status(true, "Loading terrain textures…"));
        start();
    }

    private void start() {
        if (closed || active != null || view == null || attempted == view.generation()) return;
        var job = new Job(view);
        active = job;
        attempted = view.generation();
        try {
            worker.execute(() -> run(job));
        } catch (RuntimeException rejected) {
            active = null;
            status.accept(new Status(false, "Terrain textures unavailable · previous preview retained"));
        }
    }

    private void run(Job job) {
        String failure = null;
        try {
            job.token.check();
            var batch = job.view.source().terrainTextures(job.view.map(), job.view.tiles(), job.token,
                    update -> progress.submit(() -> {
                        if (!closed && active == job && !job.canceled && view == job.view) {
                            status.accept(new Status(true, switch (update.stage()) {
                                case INVENTORY -> "Checking terrain texture sources…";
                                case GRID -> "Reading terrain texture geometry…";
                                case TEXTURES -> "Reading terrain texture layers…";
                                case DIFFUSE -> "Loading terrain surface images…";
                                case COMPOSING -> "Preparing terrain surface textures…";
                                case REVALIDATING -> "Checking prepared terrain textures…";
                            }));
                        }
                    })).join();
            var snapshot = WorldTextureSnapshot.prepare(job.view.scene(), batch,
                    () -> closed || job.canceled || job.token.isCanceled());
            job.result.set(snapshot);
            if (closed || job.canceled) job.releaseResult();
        } catch (RuntimeException error) {
            failure = "Terrain textures unavailable · previous preview retained";
        } finally {
            if (closed || job.canceled) job.releaseResult();
            final String message = failure;
            try {
                schedule.accept(() -> completed(job, message));
            } catch (RuntimeException rejected) {
                job.releaseResult();
            }
        }
    }

    private void completed(Job job, String failure) {
        if (active != job) {
            job.releaseResult();
            return;
        }
        active = null;
        var result = job.result.getAndSet(null);
        if (closed || job.canceled || view != job.view) {
            if (result != null) result.close();
        } else if (result == null) {
            status.accept(new Status(false, failure == null
                    ? "Terrain textures unavailable · previous preview retained" : failure));
        } else {
            boolean transferred = false;
            try {
                deliver.accept(result);
                transferred = true;
            } finally {
                if (!transferred) result.close();
            }
        }
        start();
    }

    private void retireActive() {
        progress.clear();
        if (active == null || active.canceled) return;
        var retiring = active;
        retiring.canceled = true;
        retiring.releaseResult();
        cancellation.execute(retiring.token::cancel);
    }

    void hide() {
        view = null;
        ++generation;
        retireActive();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        hide();
        worker.shutdown();
        cancellation.shutdown();
    }
}
