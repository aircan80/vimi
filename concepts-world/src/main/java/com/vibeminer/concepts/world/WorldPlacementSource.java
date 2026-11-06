package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Build-scoped recorded obj0 metadata; source services remain borrowed. */
public final class WorldPlacementSource {
    public enum State {
        READ,
        UNAVAILABLE
    }

    public record Receipt(
            BuildRef build,
            String locale,
            long mapId,
            WorldFormats.Tile tile,
            long obj0Id,
            String contentKey,
            String parserRevision) {
        public Receipt {
            Objects.requireNonNull(build);
            Objects.requireNonNull(locale);
            Objects.requireNonNull(tile);
            Objects.requireNonNull(parserRevision);
            if (obj0Id <= 0
                    || obj0Id != tile.obj0Id()
                    || contentKey == null
                    || !contentKey.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Invalid placement source receipt");
            }
        }
    }

    public record Result(
            State state, Receipt receipt, WorldPlacements.Metadata facts, String issue) {
        public Result {
            Objects.requireNonNull(state);
            if (state == State.READ
                    ? receipt == null || facts == null || issue != null
                    : receipt != null || facts != null || issue == null || issue.isBlank()) {
                throw new IllegalArgumentException("Inconsistent placement source result");
            }
        }

        public static Result unavailable(String issue) {
            return new Result(State.UNAVAILABLE, null, null, issue);
        }
    }

    private record Key(
            BuildRef build, String locale, long id, String contentKey, String revision) {}

    private static final class Request {
        final WorldService.MapInfo map;
        final WorldFormats.Tile tile;
        final Cancellation caller;
        final Cancellation token = new Cancellation();
        final CompletableFuture<Result> result = new CompletableFuture<>();
        Cancellation.Registration registration;
        boolean canceled, cancelRequested;

        Request(WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation caller) {
            this.map = map;
            this.tile = tile;
            this.caller = caller;
        }
    }

    private final BuildFiles files;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().daemon().name("world-placements").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Thread cancellation;
    private final LinkedHashMap<Key, WorldPlacements.Metadata> cache =
            new LinkedHashMap<>(16, .75f, true);
    private int cachedRecords;
    private Request active, pendingCancellation;
    private boolean stoppingCancellation;
    private Throwable cancellationFailure;
    private CompletableFuture<Void> closing;

    public WorldPlacementSource(BuildFiles files) {
        this.files = files;
        cancellation =
                Thread.ofPlatform().daemon().name("world-placement-cancel").start(this::cancelLoop);
    }

    public synchronized CompletableFuture<Result> read(
            WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(tile);
        Objects.requireNonNull(token);
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World placement source closed"));
        }
        var request = new Request(map, tile, token);
        request.registration = token.onCancel(() -> cancel(request));
        try {
            worker.execute(() -> run(request));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            request.registration.close();
            request.result.completeExceptionally(
                    new IllegalStateException("World placement request queue is full", full));
        }
        return request.result.copy();
    }

    private synchronized void cancel(Request request) {
        request.canceled = true;
        if (active != request || request.cancelRequested) {
            return;
        }
        request.cancelRequested = true;
        // Only this identity can own source callbacks. A previous pending target must already
        // have completed; never discard the current active source's cancellation.
        if (pendingCancellation != null && pendingCancellation == active) {
            return;
        }
        pendingCancellation = request;
        notifyAll();
    }

    private void cancelLoop() {
        while (true) {
            Request target;
            synchronized (this) {
                while (pendingCancellation == null && !stoppingCancellation) {
                    try {
                        wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancellationFailure = interrupted;
                        return;
                    }
                }
                if (pendingCancellation == null) {
                    return;
                }
                target = pendingCancellation;
                pendingCancellation = null;
            }
            try {
                target.token.cancel();
            } catch (RuntimeException | Error failure) {
                synchronized (this) {
                    if (cancellationFailure == null) {
                        cancellationFailure = failure;
                    }
                }
            }
        }
    }

    private void run(Request request) {
        try {
            synchronized (this) {
                active = request;
            }
            check(request);
            Result result;
            try {
                result = load(request);
            } catch (RuntimeException unavailable) {
                check(request);
                // Only a pure cancellation chain stops; a real failure caused by one stays itself.
                var stop = WorldSourceRefusals.cleanCancellation(unavailable);
                if (stop != null) {
                    throw stop;
                }
                result = Result.unavailable("World object metadata unavailable");
            }
            check(request);
            request.result.complete(result);
        } catch (Throwable failure) {
            request.result.completeExceptionally(failure);
        } finally {
            request.registration.close();
            synchronized (this) {
                if (active == request) {
                    active = null;
                }
                if (pendingCancellation == request) {
                    pendingCancellation = null;
                }
            }
        }
    }

    private synchronized void check(Request request) {
        if (closing != null || request.canceled || request.caller.isCanceled()) {
            throw new java.util.concurrent.CancellationException("World placement request retired");
        }
        request.token.check();
    }

    private Result load(Request request) {
        if (files == null || request.tile.obj0Id() == 0) {
            return Result.unavailable("No recorded obj0 source");
        }
        var ref = FileTypes.FileRef.id(request.tile.obj0Id());
        var before = files.statContent(ref, request.token).join();
        check(request);
        if (before.state() != FileTypes.FileLookupState.FOUND
                || before.file() == null
                || before.file().fileDataId() != request.tile.obj0Id()
                || before.file().size() != null
                        && (before.file().size() < 12
                                || before.file().size() > WorldPlacements.MAX_SOURCE_BYTES)
                || before.file().contentKey() == null
                || !before.file().contentKey().matches("[0-9a-fA-F]{32}")) {
            return Result.unavailable("Object source absent, unbound or outside size limits");
        }
        var key =
                new Key(
                        Objects.requireNonNull(files.build()),
                        Objects.requireNonNull(files.locale()),
                        request.tile.obj0Id(),
                        before.file().contentKey(),
                        WorldPlacements.REVISION);
        var facts = cache.get(key);
        if (facts == null) {
            var opened = files.openRead(ref, request.token).join();
            if (opened.stream() == null) {
                return Result.unavailable("Object source unavailable");
            }
            try (var stream = opened.stream()) {
                check(request);
                if (opened.state() != FileTypes.FileReadState.COMPLETE
                        || !stream.holes().isEmpty()
                        || stream.length() < 12
                        || stream.length() > WorldPlacements.MAX_SOURCE_BYTES
                        || before.file().size() != null
                                && before.file().size() != stream.length()) {
                    return Result.unavailable(
                            "Object source incomplete, protected or outside size limits");
                }
                var bytes = ByteBuffer.allocate((int) stream.length());
                while (bytes.hasRemaining()) {
                    check(request);
                    int remaining = bytes.remaining();
                    int read = stream.read(bytes, request.token).join();
                    if (read <= 0 || remaining - bytes.remaining() != read) {
                        return Result.unavailable("Object source truncated or nonadvancing");
                    }
                }
                facts = WorldPlacements.readObj0(bytes.array(), () -> canceled(request));
            }
            var after = files.statContent(ref, request.token).join();
            check(request);
            if (after.state() != FileTypes.FileLookupState.FOUND
                    || after.file() == null
                    || after.file().fileDataId() != request.tile.obj0Id()
                    || !key.contentKey().equals(after.file().contentKey())) {
                return Result.unavailable("Object source changed during read");
            }
            int count = records(facts);
            while (!cache.isEmpty() && (cache.size() >= 9 || cachedRecords + count > 16384)) {
                cachedRecords -= records(cache.remove(cache.keySet().iterator().next()));
            }
            cache.put(key, facts);
            cachedRecords += count;
        }
        check(request);
        return new Result(
                State.READ,
                new Receipt(
                        key.build(),
                        key.locale(),
                        request.map.id(),
                        request.tile,
                        key.id(),
                        key.contentKey(),
                        key.revision()),
                facts,
                null);
    }

    private synchronized boolean canceled(Request request) {
        return closing != null
                || request.canceled
                || request.caller.isCanceled()
                || request.token.isCanceled();
    }

    private static int records(WorldPlacements.Metadata facts) {
        return facts.doodads().size() + facts.worldObjects().size();
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        if (active != null) {
            cancel(active);
        }
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        synchronized (this) {
                            stoppingCancellation = true;
                            notifyAll();
                        }
                        cancellation.join();
                        cache.clear();
                        cachedRecords = 0;
                        if (cancellationFailure == null) {
                            closing.complete(null);
                        } else {
                            closing.completeExceptionally(cancellationFailure);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }
}
