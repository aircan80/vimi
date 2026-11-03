package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.AssetSources;
import com.vibeminer.formats.images.Blp2Decoder;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.world.WorldFormats;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded minimap artwork reads; file services remain borrowed. */
final class WorldMinimaps {
    private static final long SOURCE_LIMIT = 16L << 20;
    private final BuildFiles files;
    private final Cancellation lifetime = new Cancellation();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().daemon().name("world-minimap").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final LinkedHashMap<String, WorldService.Minimap> cache =
            new LinkedHashMap<>(16, .75f, true);
    private CompletableFuture<Void> closing;

    WorldMinimaps(BuildFiles files) {
        this.files = files;
    }

    synchronized CompletableFuture<WorldService.Minimap> image(
            WorldFormats.Tile tile, Cancellation token) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("World workspace closed"));
        }
        var result = new CompletableFuture<WorldService.Minimap>();
        try {
            worker.execute(() -> load(tile, token, result));
        } catch (java.util.concurrent.RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Minimap request queue is full", full));
        }
        return result.copy();
    }

    @SuppressWarnings("try")
    private void load(
            WorldFormats.Tile tile,
            Cancellation caller,
            CompletableFuture<WorldService.Minimap> result) {
        var token = new Cancellation();
        try (var owner = lifetime.onCancel(token::cancel);
                var request = caller.onCancel(token::cancel)) {
            token.check();
            result.complete(read(tile, token));
        } catch (java.util.concurrent.CancellationException canceled) {
            result.completeExceptionally(canceled);
        } catch (RuntimeException unavailable) {
            if (token.isCanceled()) {
                result.completeExceptionally(new java.util.concurrent.CancellationException());
            } else {
                result.complete(WorldService.Minimap.unavailable("Minimap artwork unavailable"));
            }
        }
    }

    private WorldService.Minimap read(WorldFormats.Tile tile, Cancellation token) {
        if (files == null || tile.minimapId() <= 0) {
            return WorldService.Minimap.unavailable("No recorded minimap artwork");
        }
        var ref = FileTypes.FileRef.id(tile.minimapId());
        var stat = files.stat(ref, token).join();
        token.check();
        if (stat.file() == null
                || stat.file().size() != null && stat.file().size() > SOURCE_LIMIT) {
            return WorldService.Minimap.unavailable("Minimap source absent or exceeds size limit");
        }
        String contentKey = stat.file().contentKey();
        if (contentKey == null || !contentKey.matches("[0-9a-fA-F]{32}")) {
            return WorldService.Minimap.unavailable("Minimap source has no verified content key");
        }
        String key = tile.minimapId() + ":" + contentKey;
        var saved = cache.get(key);
        if (saved != null) {
            return saved;
        }
        var opened = files.openRead(ref, token).join();
        if (opened.stream() == null) {
            return WorldService.Minimap.unavailable("Minimap source is unavailable");
        }
        try (var stream = opened.stream()) {
            if (opened.state() != FileTypes.FileReadState.COMPLETE || !stream.holes().isEmpty()) {
                return WorldService.Minimap.unavailable(
                        "Minimap source is incomplete or protected");
            }
            if (stream.length() < Blp2Decoder.HEADER_BYTES || stream.length() > SOURCE_LIMIT) {
                return WorldService.Minimap.unavailable("Minimap source exceeds size bounds");
            }
            var decoded =
                    Blp2Decoder.decodeLargestWithin(
                                    AssetSources.file(stream),
                                    512,
                                    new Blp2Types.Limits(
                                            SOURCE_LIMIT,
                                            4096,
                                            512L * 512,
                                            512L * 512 * 4,
                                            512L * 512 * 4),
                                    token)
                            .join();
            var rgba = decoded.rgba();
            double scale = Math.min(1, 256.0 / Math.max(decoded.width(), decoded.height()));
            int width = Math.max(1, (int) (decoded.width() * scale));
            int height = Math.max(1, (int) (decoded.height() * scale));
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                token.check();
                int sourceY = (2 * y + 1) * decoded.height() / (2 * height);
                for (int x = 0; x < width; x++) {
                    // Explicit nearest pixel-center resampling belongs to the minimap consumer;
                    // the generic BLP decoder still decodes only declared native mip levels.
                    int sourceX = (2 * x + 1) * decoded.width() / (2 * width);
                    int p = sourceY * decoded.rowStride() + sourceX * 4;
                    argb[y * width + x] =
                            (rgba[p + 3] & 255) << 24
                                    | (rgba[p] & 255) << 16
                                    | (rgba[p + 1] & 255) << 8
                                    | rgba[p + 2] & 255;
                }
            }
            var current = files.stat(ref, token).join();
            token.check();
            if (current.file() == null || !contentKey.equals(current.file().contentKey())) {
                return WorldService.Minimap.unavailable("Minimap source changed during read");
            }
            var image = new WorldService.Minimap(width, height, IntBuffer.wrap(argb), null);
            cache.put(key, image);
            while (cache.size() > 9) {
                cache.remove(cache.keySet().iterator().next());
            }
            return image;
        }
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
                        cache.clear();
                        closing.complete(null);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(interrupted);
                    }
                });
        return closing.copy();
    }
}
