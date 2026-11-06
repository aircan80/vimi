package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldLowRes;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A map's low-resolution horizon heights from its WDL, read by its recorded path {@code
 * world/maps/<directory>/<directory>.wdl}. Reads are bounded and cached per map and content key;
 * file services remain borrowed.
 */
public final class WorldHorizon {
    public enum State {
        /** The WDL was read; some declared tiles may still be refused (see the problems). */
        READY,
        /** The map has no directory, or its WDL is not stored or not resolvable by name. */
        ABSENT,
        /** The WDL exists but is incomplete, protected, too large or malformed. */
        UNAVAILABLE
    }

    /** {@code lowRes} is non-null exactly when READY. */
    public record Result(
            State state, long mapId, long fileId, WorldLowRes.LowRes lowRes, String reason) {
        public Result {
            Objects.requireNonNull(state);
            Objects.requireNonNull(reason);
            if ((state == State.READY) != (lowRes != null) || fileId < 0) {
                throw new IllegalArgumentException("Invalid horizon result");
            }
        }

        public static Result absent(long mapId, String reason) {
            return new Result(State.ABSENT, mapId, 0, null, reason);
        }

        static Result unavailable(long mapId, long fileId, String reason) {
            return new Result(State.UNAVAILABLE, mapId, fileId, null, reason);
        }
    }

    private static final int CACHE = 2;
    private final BuildFiles files;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(2),
                    Thread.ofPlatform().daemon().name("world-horizon").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String, Result> cache = new LinkedHashMap<>(4, .75f, true);
    private CompletableFuture<Void> closing;

    WorldHorizon(BuildFiles files) {
        this.files = files;
    }

    /** The recorded WDL path for a map directory, or null when the directory is unusable. */
    static String path(String directory) {
        if (directory == null
                || directory.isBlank()
                || directory.length() > 64
                || !directory.matches("[A-Za-z0-9_' -]+")) {
            return null;
        }
        String name = directory.toLowerCase(Locale.ROOT);
        return "world/maps/" + name + "/" + name + ".wdl";
    }

    synchronized CompletableFuture<Result> load(WorldService.MapInfo map, Cancellation token) {
        Objects.requireNonNull(map);
        Objects.requireNonNull(token);
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World workspace closed"));
        }
        var result = new CompletableFuture<Result>();
        try {
            worker.execute(() -> run(map, token, result));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Horizon request queue is full", full));
        }
        return result.copy();
    }

    @SuppressWarnings("try")
    private void run(WorldService.MapInfo map, Cancellation caller, CompletableFuture<Result> out) {
        var token = new Cancellation();
        try (var owner = lifetime.onCancel(token::cancel);
                var request = caller.onCancel(token::cancel)) {
            token.check();
            out.complete(read(map, token));
        } catch (java.util.concurrent.CancellationException canceled) {
            out.completeExceptionally(canceled);
        } catch (RuntimeException failure) {
            if (token.isCanceled()) {
                out.completeExceptionally(new java.util.concurrent.CancellationException());
            } else {
                out.complete(Result.unavailable(map.id(), 0, "Horizon source failed to read"));
            }
        }
    }

    private Result read(WorldService.MapInfo map, Cancellation token) {
        String path = path(map.directory());
        if (files == null || path == null) {
            return Result.absent(map.id(), "Map has no usable directory for a WDL");
        }
        var ref = FileTypes.FileRef.forPath(path);
        var stat = files.stat(ref, token).join();
        token.check();
        var file = stat.file();
        if (stat.state() != FileTypes.FileLookupState.FOUND || file == null) {
            return Result.absent(map.id(), "Map has no stored WDL");
        }
        long id = file.fileDataId();
        String contentKey = file.contentKey();
        if (contentKey == null || !contentKey.matches("[0-9a-fA-F]{32}")) {
            return Result.unavailable(map.id(), id, "WDL has no verified content key");
        }
        if (file.size() != null
                && (file.size() < 12 || file.size() > WorldLowRes.MAX_SOURCE_BYTES)) {
            return Result.unavailable(map.id(), id, "WDL exceeds its size bound");
        }
        String key = map.id() + ":" + id + ":" + contentKey.toLowerCase(Locale.ROOT);
        synchronized (this) {
            var saved = cache.get(key);
            if (saved != null) {
                return saved;
            }
        }
        var opened = files.openRead(FileTypes.FileRef.id(id), token).join();
        byte[] bytes;
        try (var stream = opened.stream()) {
            if (stream == null
                    || opened.state() != FileTypes.FileReadState.COMPLETE
                    || !stream.holes().isEmpty()) {
                return Result.unavailable(map.id(), id, "WDL is incomplete or protected");
            }
            if (stream.length() < 12 || stream.length() > WorldLowRes.MAX_SOURCE_BYTES) {
                return Result.unavailable(map.id(), id, "WDL exceeds its size bound");
            }
            var target = ByteBuffer.allocate((int) stream.length());
            while (target.hasRemaining()) {
                token.check();
                if (stream.read(target, token).join() <= 0) {
                    return Result.unavailable(map.id(), id, "WDL read ended early");
                }
            }
            bytes = target.array();
        }
        WorldLowRes.LowRes lowRes;
        try {
            lowRes = WorldLowRes.read(bytes, token::isCanceled);
        } catch (IllegalArgumentException malformed) {
            return Result.unavailable(map.id(), id, "WDL is malformed: " + malformed.getMessage());
        }
        var current = files.stat(FileTypes.FileRef.id(id), token).join();
        token.check();
        if (current.file() == null || !contentKey.equalsIgnoreCase(current.file().contentKey())) {
            return Result.unavailable(map.id(), id, "WDL changed during read");
        }
        var result =
                new Result(
                        State.READY,
                        map.id(),
                        id,
                        lowRes,
                        lowRes.tiles().size()
                                + " tiles"
                                + (lowRes.problems().isEmpty()
                                        ? ""
                                        : ", " + lowRes.problems().size() + " refused"));
        synchronized (this) {
            cache.put(key, result);
            while (cache.size() > CACHE) {
                cache.remove(cache.keySet().iterator().next());
            }
        }
        return result;
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        lifetime.cancel();
        worker.shutdown();
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {}
                        synchronized (this) {
                            cache.clear();
                        }
                        closing.complete(null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }
}
