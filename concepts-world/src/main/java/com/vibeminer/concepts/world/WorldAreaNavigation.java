package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Explicit bounded exact-area discovery. Borrows files and retains no source payloads. */
public final class WorldAreaNavigation implements AutoCloseable {
    public enum State {
        COMPLETE,
        PARTIAL,
        CANCELED
    }

    public record Request(WorldService.MapInfo map, long areaId, List<WorldFormats.Tile> tiles) {
        public Request {
            Objects.requireNonNull(map);
            if (areaId <= 0 || areaId > 0xffff_ffffL || tiles.size() > 4096) {
                throw new IllegalArgumentException("Invalid area discovery scope");
            }
            tiles = List.copyOf(tiles);
            var keys = new HashSet<String>();
            for (var tile : tiles) {
                if (!keys.add(tile.key())) {
                    throw new IllegalArgumentException("Duplicate terrain tile");
                }
            }
        }
    }

    /** Entry chunk is null only when every matching chunk is fully holed. */
    public record Match(
            WorldFormats.Tile tile,
            String contentKey,
            int matchingChunks,
            WorldFormats.AreaChunk entryChunk) {}

    public record Progress(int checked, int total, int matches, int unavailable) {}

    public record Result(State state, Progress progress, List<Match> matches, List<String> issues) {
        public Result {
            matches = List.copyOf(matches);
            issues = List.copyOf(issues);
        }
    }

    private final BuildFiles files;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().daemon().name("world-area-discovery").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private Job latest;
    private Job active;
    private CompletableFuture<Void> closing;

    public WorldAreaNavigation(BuildFiles files) {
        this.files = files;
    }

    /** Call off FX: cancellation may notify lower-layer request listeners. */
    public CompletableFuture<Result> scan(
            Request request, Cancellation token, Consumer<Progress> progress) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(token);
        Objects.requireNonNull(progress);
        Job previous;
        boolean removed;
        var job = new Job(request, token, progress);
        synchronized (this) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Area discovery closed"));
            }
            previous = latest;
            removed = previous != null && worker.remove(previous);
            latest = job;
            worker.execute(job);
        }
        if (previous != null) {
            previous.cancel.cancel();
            if (removed) {
                previous.completeCanceled();
            }
        }
        return job.future.copy();
    }

    private final class Job implements Runnable {
        final Request request;
        final Cancellation external;
        final Consumer<Progress> observer;
        final Cancellation cancel = new Cancellation();
        final CompletableFuture<Result> future = new CompletableFuture<>();
        final ArrayList<Match> matches = new ArrayList<>();
        final ArrayList<String> issues = new ArrayList<>();
        int checked;
        int unavailable;
        long lastProgress;

        Job(Request request, Cancellation external, Consumer<Progress> observer) {
            this.request = request;
            this.external = external;
            this.observer = observer;
        }

        Progress progress() {
            return new Progress(checked, request.tiles().size(), matches.size(), unavailable);
        }

        void completeCanceled() {
            future.complete(new Result(State.CANCELED, progress(), matches, issues));
        }

        void publish(boolean force) {
            long now = System.nanoTime();
            if (force || now - lastProgress >= 100_000_000L) {
                lastProgress = now;
                observer.accept(progress());
            }
        }

        @Override
        @SuppressWarnings("try")
        public void run() {
            synchronized (WorldAreaNavigation.this) {
                active = this;
                if (closing != null) {
                    cancel.cancel();
                }
            }
            try (var registration = external.onCancel(cancel::cancel)) {
                cancel.check();
                publish(true);
                for (var tile : request.tiles()) {
                    cancel.check();
                    try {
                        inspect(tile);
                    } catch (RuntimeException failure) {
                        cancel.check();
                        Throwable cause = failure;
                        while (cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof CancellationException) {
                            throw new CancellationException();
                        }
                        unavailable++;
                        if (issues.size() < 32) {
                            issues.add("Tile " + tile.key() + ": " + reason(failure));
                        }
                    }
                    checked++;
                    publish(false);
                }
                cancel.check();
                publish(true);
                future.complete(
                        new Result(
                                unavailable == 0 ? State.COMPLETE : State.PARTIAL,
                                progress(),
                                matches,
                                issues));
            } catch (CancellationException canceled) {
                completeCanceled();
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                synchronized (WorldAreaNavigation.this) {
                    active = null;
                    if (latest == this) {
                        latest = null;
                    }
                }
            }
        }

        void inspect(WorldFormats.Tile tile) {
            FileTypes.FileRef ref;
            if (tile.rootId() > 0) {
                ref = FileTypes.FileRef.id(tile.rootId());
            } else if (request.map().directory() != null) {
                String directory = request.map().directory();
                ref =
                        FileTypes.FileRef.forPath(
                                "world/maps/"
                                        + directory
                                        + "/"
                                        + directory
                                        + "_"
                                        + tile.x()
                                        + "_"
                                        + tile.y()
                                        + ".adt");
            } else {
                throw new IllegalStateException("No terrain source reference");
            }
            var source = WorldFileBytes.read(files, ref, cancel, true);
            var metadata = WorldFormats.adtAreas(source.bytes(), cancel::isCanceled);
            int count = 0;
            WorldFormats.AreaChunk entry = null;
            for (var chunk : metadata.chunks()) {
                if (chunk.areaId() == request.areaId()) {
                    count++;
                    if (entry == null && chunk.entryReference() != null) {
                        entry = chunk;
                    }
                }
            }
            cancel.check();
            if (count > 0) {
                matches.add(new Match(tile, source.contentKey(), count, entry));
            }
        }
    }

    private static String reason(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        String message = failure.getMessage();
        if (message == null) {
            return "Terrain source unavailable";
        }
        return message.substring(0, Math.min(message.length(), 240));
    }

    public CompletableFuture<Void> closeAsync() {
        Job running;
        Job last;
        boolean removed;
        CompletableFuture<Void> drained;
        synchronized (this) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            drained = closing;
            running = active;
            last = latest;
            removed = last != null && worker.remove(last);
            worker.shutdown();
        }
        if (running != null) {
            running.cancel.cancel();
        }
        if (last != null) {
            last.cancel.cancel();
            if (removed) {
                last.completeCanceled();
            }
        }
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        drained.complete(null);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        drained.completeExceptionally(failure);
                    }
                });
        return drained.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
