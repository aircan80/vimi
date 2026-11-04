package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainScene;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** UI-owned latest scene loading; one active worker, one pending descriptor, no pixel copies. */
final class WorldTextureLoading implements AutoCloseable {
    record Status(boolean busy, String message) {}
    /** A terminal current request; ownership of a nonnull snapshot transfers to the consumer. */
    record Result(TerrainScene scene, WorldTextureSnapshot snapshot, String issue) {}
    private static final class View {
        final long generation;
        final BuildWorld source;
        final WorldService.MapInfo map;
        final List<WorldFormats.Tile> tiles;
        final java.lang.ref.WeakReference<TerrainScene> identity;
        volatile TerrainScene strongScene;
        View(long generation, BuildWorld source, WorldService.MapInfo map,
                List<WorldFormats.Tile> tiles, TerrainScene scene) {
            this.generation = generation;
            this.source = source;
            this.map = map;
            this.tiles = tiles;
            strongScene = scene;
            identity = new java.lang.ref.WeakReference<>(scene);
        }
        long generation() { return generation; }
        BuildWorld source() { return source; }
        WorldService.MapInfo map() { return map; }
        List<WorldFormats.Tile> tiles() { return tiles; }
        TerrainScene scene() { return strongScene == null ? identity.get() : strongScene; }
        void releaseScene() { strongScene = null; }
    }

    private static final class Job {
        final View view;
        final Cancellation token = new Cancellation();
        final AtomicReference<WorldTextureSnapshot> result = new AtomicReference<>();
        volatile boolean canceled;
        boolean workerDone, mailboxDone, retired;
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
    private final Consumer<Result> deliver;
    private final Runnable idle;
    private final ModelPreviewView.LatestDelivery<Runnable> progress;
    private View view;
    private Job active;
    private long generation, attempted;
    private volatile boolean closed;
    private final Object retirement = new Object();
    private int issued;
    private java.io.IOException retirementFailure;
    private CompletableFuture<Void> idleEpoch = CompletableFuture.completedFuture(null);

    WorldTextureLoading(Consumer<Runnable> schedule, Consumer<Status> status,
            Consumer<Result> deliver) {
        this(schedule, status, deliver, () -> {});
    }

    WorldTextureLoading(Consumer<Runnable> schedule, Consumer<Status> status,
            Consumer<Result> deliver, Runnable idle) {
        this.idle = Objects.requireNonNull(idle);
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
        synchronized (retirement) {
            if (issued++ == 0) idleEpoch = new CompletableFuture<>();
        }
        attempted = view.generation();
        try {
            worker.execute(() -> run(job));
        } catch (RuntimeException rejected) {
            active = null;
            String issue = "Terrain textures unavailable · previous preview retained";
            try {
                status.accept(new Status(false, issue));
                deliver.accept(new Result(job.view.scene(), null, issue));
            }
            finally {
                job.view.releaseScene();
                try { idle.run(); } finally { retired(job, true, true, rejected); }
            }
        } catch (Error fatal) {
            // No supplier/mailbox exists after failed worker creation. Keep the fatal outcome
            // sticky before returning the SAME Error; never fabricate a provider result.
            active = null;
            job.view.releaseScene();
            retired(job, true, true, fatal);
            throw fatal;
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
            job.result.set(WorldTextureSnapshot.prepare(job.view.scene(), batch,
                    () -> closed || job.canceled || job.token.isCanceled()));
            if (closed || job.canceled) job.releaseResult();
        } catch (RuntimeException error) {
            failure = "Terrain textures unavailable · previous preview retained";
            if (!(job.canceled || closed)
                    || error.getClass() != java.util.concurrent.CancellationException.class
                    || error.getCause() != null || error.getSuppressed().length != 0) {
                retired(job, false, false, error);
            }
        } catch (Error error) {
            retired(job, false, false, error);
            throw error;
        } finally {
            Throwable cleanupFailure = null;
            try {
                if (closed || job.canceled) {
                    job.releaseResult();
                    job.view.releaseScene();
                }
                final String message = failure;
                schedule.accept(() -> completed(job, message));
            } catch (Throwable rejected) {
                cleanupFailure = rejected;
                try { job.releaseResult(); }
                catch (Throwable cleanup) { cleanupFailure = cleanup; }
                finally { job.view.releaseScene(); }
                // There is no FX mailbox entry after failed scheduling. Fail the fence rather
                // than retaining a phantom active job or claiming successful GUI retirement.
                retired(job, false, true, cleanupFailure);
            } finally {
                retired(job, true, false, cleanupFailure);
            }
        }
    }

    private void completed(Job job, String failure) {
        Throwable failed = null;
        try {
            completeOnOwner(job, failure);
        } catch (Throwable error) {
            failed = error;
            throw error;
        } finally {
            retired(job, false, true, failed);
        }
    }

    private void completeOnOwner(Job job, String failure) {
        if (active != job) {
            job.releaseResult();
            job.view.releaseScene();
            return;
        }
        active = null;
        var result = job.result.getAndSet(null);
        try {
            if (closed || job.canceled || view != job.view) {
                if (result != null) result.close();
            } else {
                String issue = result == null && failure == null
                        ? "Terrain textures unavailable · previous preview retained" : failure;
                if (result == null) status.accept(new Status(false, issue));
                boolean transferred = false;
                try {
                    deliver.accept(new Result(job.view.scene(), result, issue));
                    transferred = true;
                } finally {
                    if (!transferred && result != null) result.close();
                }
            }
        } finally {
            // Remove strong scene aliases before idle can admit another full geometry candidate.
            result = null;
            job.view.releaseScene();
            start();
            idle.run();
        }
    }

    /**
     * GUI job fence only: supplier finally plus FX result/scene retirement. Stop producers first;
     * a later request starts a new epoch. Native source IO must be fenced separately afterward.
     */
    CompletableFuture<Void> whenIdle() {
        synchronized (retirement) { return idleEpoch.copy(); }
    }

    private void retired(Job job, boolean workerDone, boolean mailboxDone, Throwable error) {
        CompletableFuture<Void> completed = null;
        java.io.IOException failed;
        synchronized (retirement) {
            if (error != null && retirementFailure == null) {
                retirementFailure = new java.io.IOException(error.getClass().getName());
            }
            job.workerDone |= workerDone;
            job.mailboxDone |= mailboxDone;
            if (!job.retired && job.workerDone && job.mailboxDone) {
                job.retired = true;
                if (--issued == 0) completed = idleEpoch;
            }
            failed = retirementFailure;
        }
        if (completed != null) {
            if (failed == null) completed.complete(null);
            else completed.completeExceptionally(failed);
        }
    }

    boolean hasActiveWork() { return active != null; }

    private void retireActive() {
        progress.clear();
        if (active == null || active.canceled) return;
        var retiring = active;
        retiring.canceled = true;
        try { retiring.releaseResult(); }
        catch (Throwable error) { retired(retiring, false, false, error); }
        finally {
            retiring.view.releaseScene();
            try { cancellation.execute(retiring.token::cancel); }
            catch (Throwable rejected) { retired(retiring, false, false, rejected); }
        }
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
