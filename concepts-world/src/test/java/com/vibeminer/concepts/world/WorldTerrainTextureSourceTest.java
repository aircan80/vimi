package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.world.WorldFormats;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldTerrainTextureSourceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);
    private static final long UNUSED = 999;

    private static byte[] ints(int... values) {
        var bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            bytes.putInt(value);
        }
        return bytes.array();
    }

    private static byte[] part(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(ints(tag, bytes.length));
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static byte[] wdt(int flags) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        var mphd = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(flags);
        out.writeBytes(part("MPHD", mphd.array()));
        var main = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN);
        main.putInt((TILE.x() * 64 + TILE.y()) * 8, 1);
        out.writeBytes(part("MAIN", main.array()));
        var maid = ByteBuffer.allocate(4096 * 32).order(ByteOrder.LITTLE_ENDIAN);
        int at = (TILE.x() * 64 + TILE.y()) * 32;
        maid.putInt(at, 2).putInt(at + 12, 3);
        out.writeBytes(part("MAID", maid.array()));
        return out.toByteArray();
    }

    private static byte[] manyWdt(List<WorldFormats.Tile> tiles) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(
                part(
                        "MPHD",
                        ByteBuffer.allocate(32)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(0x3ca)
                                .array()));
        var main = ByteBuffer.allocate(4096 * 8).order(ByteOrder.LITTLE_ENDIAN);
        var maid = ByteBuffer.allocate(4096 * 32).order(ByteOrder.LITTLE_ENDIAN);
        for (var tile : tiles) {
            int ordinal = tile.x() * 64 + tile.y();
            main.putInt(ordinal * 8, 1);
            maid.putInt(ordinal * 32, (int) tile.rootId())
                    .putInt(ordinal * 32 + 12, (int) tile.tex0Id());
        }
        out.writeBytes(part("MAIN", main.array()));
        out.writeBytes(part("MAID", maid.array()));
        return out.toByteArray();
    }

    private static byte[] eightLayers() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        out.writeBytes(part("MDID", ints(10, 11, 12, 13, 14, 15, 16, 17)));
        out.writeBytes(part("MHID", new byte[32]));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var layers = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 8; i++) {
                layers.putInt(i)
                        .putInt(i == 0 ? 0 : 0x100)
                        .putInt(i == 0 ? 0 : (i - 1) * 4096)
                        .putInt(0);
            }
            byte[] masks = new byte[7 * 4096];
            java.util.Arrays.fill(masks, (byte) 32);
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(part("MCLY", layers.array()));
            chunk.writeBytes(part("MCAL", masks));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] root() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
            int x = ordinal % 16, y = ordinal / 16;
            header.putInt(4, x)
                    .putInt(8, y)
                    .putFloat(104, (float) (-y * WorldFormats.TILE_SIZE / 16))
                    .putFloat(108, (float) (-x * WorldFormats.TILE_SIZE / 16))
                    .putFloat(112, 10);
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(header.array());
            chunk.writeBytes(part("MCVT", new byte[580]));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] texture() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        out.writeBytes(part("MDID", ints(10, 11, (int) UNUSED)));
        out.writeBytes(part("MHID", ints(0, 0, 0)));
        for (int i = 0; i < 256; i++) {
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(
                    part("MCLY", i < 128 ? ints(0, 0, 0, 0, 1, 0x100, 0, 0) : ints(0, 0, 0, 0)));
            byte[] alpha = new byte[i < 128 ? 4096 : 0];
            java.util.Arrays.fill(alpha, (byte) 128);
            chunk.writeBytes(part("MCAL", alpha));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    private static byte[] blp(int color) {
        // Exact BC2 handmade8->4->2->1 chain, all block selectors0 and sourcealpha15.
        var data = ByteBuffer.allocate(148 + 7 * 16).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 8)
                .put((byte) 1)
                .put((byte) 2)
                .putInt(8)
                .putInt(8);
        int at = 148;
        for (int level = 0; level < 4; level++) {
            int length = level == 0 ? 64 : 16;
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            for (int block = 0; block < length / 16; block++) {
                data.position(at + block * 16);
                data.putLong(-1).putShort((short) color).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return data.array();
    }

    private static class Files {
        final Map<Long, byte[]> sources = new ConcurrentHashMap<>();
        final Map<Long, AtomicInteger> stats = new ConcurrentHashMap<>(),
                opens = new ConcurrentHashMap<>();
        final AtomicInteger closed = new AtomicInteger(), borrowedClosed = new AtomicInteger();
        volatile long protectedId, changedId, wrongId;
        volatile boolean changeAfterRead;

        Files() {
            sources.put(1L, wdt(0x3ca));
            sources.put(2L, root());
            sources.put(3L, texture());
            sources.put(10L, blp(0xf800));
            sources.put(11L, blp(0x001f));
        }

        FileTypes.FileStat stat(long id, Cancellation token) {
            token.check();
            if (id == UNUSED) {
                throw new AssertionError("Unused palette may not be fetched");
            }
            stats.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = sources.get(id);
            if (bytes == null) {
                return new FileTypes.FileStat(FileTypes.FileLookupState.UNKNOWN_FILE, null);
            }
            String key =
                    changeAfterRead
                                    && id == changedId
                                    && opens.getOrDefault(id, new AtomicInteger()).get() > 0
                            ? "d".repeat(32)
                            : String.format("%032x", id);
            return new FileTypes.FileStat(
                    FileTypes.FileLookupState.FOUND,
                    new FileTypes.BuildFile(
                            id == wrongId ? id + 1 : id,
                            null,
                            FileTypes.FilePathSource.NONE,
                            (long) bytes.length,
                            key,
                            FileTypes.FileFormat.UNKNOWN,
                            FileTypes.FileFormatSource.NONE,
                            FileTypes.FileStorage.STORED,
                            false));
        }

        FileReadStream open(long id, Cancellation token) {
            token.check();
            opens.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = sources.get(id);
            return new FileReadStream() {
                int position;
                boolean finished;

                public long length() {
                    return bytes.length;
                }

                public long position() {
                    return position;
                }

                public void seek(long value) {
                    position = Math.toIntExact(value);
                }

                public List<FileTypes.FileHole> holes() {
                    return id == protectedId
                            ? List.of(new FileTypes.FileHole(0, 4, "authored"))
                            : List.of();
                }

                public CompletableFuture<Integer> read(ByteBuffer target, Cancellation cancel) {
                    cancel.check();
                    int count = Math.min(target.remaining(), bytes.length - position);
                    target.put(bytes, position, count);
                    position += count;
                    return CompletableFuture.completedFuture(count);
                }

                public void close() {
                    if (!finished) {
                        finished = true;
                        closed.incrementAndGet();
                    }
                }
            };
        }

        BuildFiles files() {
            return (BuildFiles)
                    Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "build" -> BUILD;
                                        case "locale" -> "enUS";
                                        case "stat" ->
                                                CompletableFuture.completedFuture(
                                                        stat(
                                                                ((FileTypes.FileRef) args[0])
                                                                        .fileDataId(),
                                                                (Cancellation) args[1]));
                                        case "openRead" -> {
                                            long id = ((FileTypes.FileRef) args[0]).fileDataId();
                                            yield CompletableFuture.completedFuture(
                                                    new FileTypes.FileOpen(
                                                            FileTypes.FileReadState.COMPLETE,
                                                            open(id, (Cancellation) args[1]),
                                                            null,
                                                            null,
                                                            null));
                                        }
                                        case "close" -> {
                                            borrowedClosed.incrementAndGet();
                                            yield null;
                                        }
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected borrowed API: "
                                                                + method.getName());
                                    });
        }

        int opens(long id) {
            return opens.getOrDefault(id, new AtomicInteger()).get();
        }

        int totalOpens() {
            return opens.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static WorldTerrainTextureSource.BatchResult read(WorldTerrainTextureSource source)
            throws Exception {
        return source.read(MAP, List.of(TILE), Cancellation.NONE, progress -> {})
                .get(5, TimeUnit.SECONDS);
    }

    private static int identity(WorldTerrainTextureSource.BatchResult result) {
        var identity = new AtomicInteger();
        try (var lease = result.retainAtlas(TILE.x(), TILE.y()).orElseThrow()) {
            lease.withAtlas(atlas -> identity.set(System.identityHashCode(atlas)));
        }
        return identity.get();
    }

    @Test
    void coldWarmSharesOneAtlasAndFetchesOnlyConsumedPaletteEntries() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            int original;
            try (var cold = read(source)) {
                var row = cold.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.READY, row.state());
                assertEquals(256, row.supportedChunks());
                assertEquals(2, row.receipt().diffuse().size());
                assertEquals(BUILD, row.receipt().build());
                assertEquals("enUS", row.receipt().locale());
                assertEquals(1, row.receipt().diffuse().getFirst().level());
                assertEquals(2, row.receipt().diffuse().getFirst().mipFlags());
                original = identity(cold);
                try (var lease = cold.retainAtlas(7, 19).orElseThrow()) {
                    lease.withAtlas(
                            atlas -> {
                                assertEquals(127, atlas.pixels().get(0) & 255);
                                assertEquals(128, atlas.pixels().get(2) & 255);
                            });
                }
            }
            assertEquals(0, pool.stats().leaseClaims());
            assertEquals(1, pool.stats().cachedEntries());
            try (var warm = read(source)) {
                assertEquals(original, identity(warm));
            }
            assertEquals(1, files.opens(10));
            assertEquals(1, files.opens(11));
            assertFalse(files.stats.containsKey(UNUSED));
            source.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(1, pool.stats().cachedEntries());
            var replacement = new WorldTerrainTextureSource(files.files(), pool);
            try (var shared = read(replacement)) {
                assertEquals(original, identity(shared));
            }
            replacement.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(files.totalOpens(), files.closed.get());
            assertEquals(0, files.borrowedClosed.get());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void missingDiffuseIsPartialUncachedAndASecondReadCanImprove() throws Exception {
        var files = new Files();
        files.sources.remove(11L);
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            var partial = read(source);
            try {
                assertEquals(
                        WorldTerrainTextureSource.State.PARTIAL,
                        partial.tiles().getFirst().state());
                assertEquals(128, partial.tiles().getFirst().supportedChunks());
                assertEquals(1, partial.tiles().getFirst().receipt().diffuse().size());
                assertEquals(0, pool.stats().cachedEntries());
                try (var lease = partial.retainAtlas(7, 19).orElseThrow()) {
                    partial.close();
                    lease.withAtlas(
                            atlas -> {
                                assertEquals(0, atlas.pixels().get(3));
                                assertTrue(atlas.covered(128));
                            });
                }
            } finally {
                partial.close();
            }
            assertEquals(0, pool.stats().entries());
            files.sources.put(11L, blp(0x001f));
            try (var complete = read(source)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, complete.tiles().getFirst().state());
            }
            assertEquals(1, pool.stats().cachedEntries());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void wrongProfilesTupleProtectedAndChangedSourcesRemainUnavailable() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            var files = new Files();
            if (mode == 0) {
                files.sources.put(1L, wdt(0x3ca ^ 0x80));
            }
            if (mode == 1) {
                files.protectedId = 2;
            }
            if (mode == 2) {
                files.changedId = 3;
                files.changeAfterRead = true;
            }
            if (mode == 3) {
                files.wrongId = 2;
            }
            if (mode == 4) {
                var bytes = files.sources.get(2L);
                ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(24, 1)
                        .putInt(12 + 724 + 8 + 4, 0);
            }
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try {
                var tile = mode == 5 ? new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 4, 0, 0) : TILE;
                try (var result =
                        source.read(MAP, List.of(tile), Cancellation.NONE, progress -> {})
                                .get(5, TimeUnit.SECONDS)) {
                    assertEquals(
                            WorldTerrainTextureSource.State.UNAVAILABLE,
                            result.tiles().getFirst().state());
                    assertNull(result.tiles().getFirst().receipt());
                    assertTrue(result.retainAtlas(7, 19).isEmpty());
                }
                assertEquals(0, pool.stats().entries());
                assertEquals(files.totalOpens(), files.closed.get());
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void globalWorkerDrainsCanceledCallbacksBeforeLatestSourceAndCloseIsLocal() throws Exception {
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var callback = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var callerThread = Thread.currentThread();
        var firstFiles =
                new Files() {
                    @SuppressWarnings("try")
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1) {
                            try (var registration =
                                    token.onCancel(
                                            () -> {
                                                assertFalse(Thread.currentThread() == callerThread);
                                                canceled.complete(null);
                                                callback.complete(null);
                                                release.join();
                                            })) {
                                entered.complete(null);
                                canceled.join();
                                token.check();
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var secondFiles = new Files();
        var pool = new TerrainAtlasPool();
        var first = new WorldTerrainTextureSource(firstFiles.files(), pool);
        var second = new WorldTerrainTextureSource(secondFiles.files(), pool);
        try {
            var token = new Cancellation();
            var active = first.read(MAP, List.of(TILE), token, p -> {});
            entered.get(5, TimeUnit.SECONDS);
            token.cancel();
            callback.get(5, TimeUnit.SECONDS);
            var retired = second.read(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            var latest = second.read(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            assertThrows(Exception.class, () -> retired.get(5, TimeUnit.SECONDS));
            assertEquals(0, secondFiles.totalOpens());
            var firstClose = first.closeAsync();
            assertFalse(firstClose.isDone());
            release.complete(null);
            assertThrows(Exception.class, () -> active.get(5, TimeUnit.SECONDS));
            firstClose.get(5, TimeUnit.SECONDS);
            try (var result = latest.get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            assertEquals(1, pool.stats().cachedEntries());
            try (var other = read(second)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, other.tiles().getFirst().state());
            }
        } finally {
            release.complete(null);
            first.closeAsync().get(5, TimeUnit.SECONDS);
            second.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void alreadyCanceledLatestRequestCannotRetireUncanceledActiveSource() throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1 && !entered.isDone()) {
                            entered.complete(null);
                            release.join();
                            token.check();
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var first = new WorldTerrainTextureSource(files.files(), pool);
        var second = new WorldTerrainTextureSource(new Files().files(), pool);
        try {
            var active = first.read(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            entered.get(5, TimeUnit.SECONDS);
            var canceled = new Cancellation();
            canceled.cancel();
            var rejected = second.read(MAP, List.of(TILE), canceled, p -> {});
            assertThrows(Exception.class, () -> rejected.get(5, TimeUnit.SECONDS));
            release.complete(null);
            try (var result = active.get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
        } finally {
            release.complete(null);
            first.closeAsync().get(5, TimeUnit.SECONDS);
            second.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void futureCancellationAndSupersededPublicationReleaseUnclaimedLeases() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        try {
            var future =
                    source.read(
                            MAP,
                            List.of(TILE),
                            Cancellation.NONE,
                            p -> {
                                if (p.stage() == WorldTerrainTextureSource.Stage.REVALIDATING) {
                                    entered.complete(null);
                                    release.join();
                                }
                            });
            entered.get(5, TimeUnit.SECONDS);
            assertEquals(1, pool.stats().leaseClaims());
            assertTrue(future.cancel(false));
            release.complete(null);
            source.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(0, pool.stats().leaseClaims());
            assertEquals(files.totalOpens(), files.closed.get());
        } finally {
            release.complete(null);
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void nineCurrentAndNinePendingShareGlobalBudgetAndNineteenthIsPoolBusy() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var tiles = new ArrayList<WorldFormats.Tile>();
        for (int i = 0; i < 19; i++) {
            tiles.add(new WorldFormats.Tile(7 + i % 7, 19 + i / 7, 2, 0, 0, 0, 0, 3, 0, 0));
        }
        files.sources.put(1L, manyWdt(tiles));
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var current =
                        source.read(MAP, tiles.subList(0, 9), Cancellation.NONE, p -> {})
                                .get(10, TimeUnit.SECONDS);
                var pending =
                        source.read(MAP, tiles.subList(9, 18), Cancellation.NONE, p -> {})
                                .get(10, TimeUnit.SECONDS)) {
            assertEquals(9, current.tiles().size());
            assertEquals(9, pending.tiles().size());
            assertEquals(18, pool.stats().entries());
            assertEquals(18, pool.stats().leaseClaims());
            assertEquals(18, pool.stats().cachedEntries());
            try (var refused =
                    source.read(MAP, tiles.subList(18, 19), Cancellation.NONE, p -> {})
                            .get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.POOL_BUSY,
                        refused.tiles().getFirst().state());
                assertTrue(refused.retainAtlas(tiles.getLast().x(), tiles.getLast().y()).isEmpty());
            }
            assertEquals(18, pool.stats().entries());
            assertEquals(18, pool.stats().cachedEntries());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void allEightLayersUseBoundedSingleTileSourceAndReservedOutput() throws Exception {
        var files = new Files();
        files.sources.put(3L, eightLayers());
        for (long id = 10; id <= 17; id++) {
            files.sources.put(id, blp(0xf800));
        }
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result =
                source.read(
                                MAP,
                                List.of(TILE),
                                Cancellation.NONE,
                                p -> {
                                    if (p.stage() == WorldTerrainTextureSource.Stage.COMPOSING) {
                                        assertEquals(1, pool.stats().reservedEntries());
                                    }
                                })
                        .get(10, TimeUnit.SECONDS)) {
            assertEquals(WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            assertEquals(8, result.tiles().getFirst().receipt().diffuse().size());
            assertEquals(7_379_048, files.sources.get(3L).length);
            assertEquals(TerrainAtlasPool.ENTRY_BYTES, pool.stats().chargedBytes());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void finalBatchRevalidationRejectsAnEarlierPublishedTileAfterSourceChange() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result =
                source.read(
                                MAP,
                                List.of(TILE),
                                Cancellation.NONE,
                                p -> {
                                    if (p.stage() == WorldTerrainTextureSource.Stage.REVALIDATING) {
                                        files.changedId = 2;
                                        files.changeAfterRead = true;
                                    }
                                })
                        .get(5, TimeUnit.SECONDS)) {
            assertEquals(
                    WorldTerrainTextureSource.State.UNAVAILABLE, result.tiles().getFirst().state());
            assertEquals(
                    WorldTerrainTextureSource.Reason.CHANGED,
                    result.tiles().getFirst().issues().getFirst().reason());
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void sourceCountGuardRejectsOversizedCallerListBeforeElementAccess() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        var oversized =
                new AbstractList<WorldFormats.Tile>() {
                    @Override
                    public int size() {
                        return 10;
                    }

                    @Override
                    public WorldFormats.Tile get(int index) {
                        throw new AssertionError("No copy before count guard");
                    }
                };
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source.read(MAP, oversized, Cancellation.NONE, p -> {}));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldTerrainTextureSource.BatchResult.unsupported(oversized));
            assertEquals(0, files.totalOpens());
            assertEquals(0, pool.stats().entries());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void throwingCancellationCallbackIsReportedOnlyAfterOwnedWorkDrains() throws Exception {
        var entered = new CompletableFuture<Void>();
        var canceled = new CompletableFuture<Void>();
        var files =
                new Files() {
                    @SuppressWarnings("try")
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        try (var registration =
                                token.onCancel(
                                        () -> {
                                            canceled.complete(null);
                                            throw new IllegalStateException(
                                                    "Authored texture cancel failure");
                                        })) {
                            entered.complete(null);
                            canceled.join();
                            token.check();
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        var result = source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {});
        entered.get(5, TimeUnit.SECONDS);
        var close = source.closeAsync();
        var failure =
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> close.get(5, TimeUnit.SECONDS));
        assertTrue(failure.toString().contains("Authored texture cancel failure"));
        assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals(0, pool.stats().entries());
        assertEquals(0, files.totalOpens());
        assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> pool.closeAsync().get(5, TimeUnit.SECONDS));
        assertEquals(0, files.borrowedClosed.get());
    }

    @Test
    void worldConstructorKeepsOldProviderUnsupportedAndDrainsTextureWorkWithoutClosingPool()
            throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var tables =
                (BuildTables)
                        Proxy.newProxyInstance(
                                BuildTables.class.getClassLoader(),
                                new Class<?>[] {BuildTables.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("Borrowed tables unused");
                                });
        var old = new WorldService(tables, files.files());
        try (var unsupported =
                old.terrainTextures(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                        .get(5, TimeUnit.SECONDS)) {
            assertEquals(
                    WorldTerrainTextureSource.Reason.UNSUPPORTED,
                    unsupported.tiles().getFirst().issues().getFirst().reason());
        } finally {
            old.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, files.totalOpens());
        var world = new WorldService(tables, files.files(), pool);
        try (var result =
                world.terrainTextures(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                        .get(5, TimeUnit.SECONDS)) {
            assertEquals(WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
        } finally {
            world.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, pool.stats().cachedEntries());
        assertEquals(0, files.borrowedClosed.get());
        pool.closeAsync().get(5, TimeUnit.SECONDS);
    }
}
