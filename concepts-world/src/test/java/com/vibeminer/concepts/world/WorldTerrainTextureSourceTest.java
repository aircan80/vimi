package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// Drain waits must fail, not hang, if a lease leaks.
@Timeout(60)
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

    /** A full handmade BC2 chain from side x side down to 1 x 1, every block one colour. */
    private static byte[] blpChain(int color, int side) {
        int levels = Integer.numberOfTrailingZeros(side) + 1;
        long total = 148;
        for (int level = 0; level < levels; level++) {
            int blocks = Math.max(1, (side >> level) / 4);
            total += (long) blocks * blocks * 16;
        }
        var data = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 8)
                .put((byte) 1)
                .put((byte) 2)
                .putInt(side)
                .putInt(side);
        int at = 148;
        for (int level = 0; level < levels; level++) {
            int blocks = Math.max(1, (side >> level) / 4);
            int length = blocks * blocks * 16;
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            data.position(at);
            for (int block = 0; block < blocks * blocks; block++) {
                data.putLong(-1).putShort((short) color).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return data.array();
    }

    /** The same authored BLP2 the coverage tests use, for the splatting detail decode. */
    static byte[] blpForDetail(int color) {
        return blp(color);
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

    static void useSixtyFourDiffuseSources(Files files) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        int[] ids = new int[64];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = 10 + i;
            files.sources.put((long) ids[i], blp(i % 2 == 0 ? 0xf800 : 0x001f));
        }
        out.writeBytes(part("MDID", ints(ids)));
        out.writeBytes(part("MHID", new byte[64 * 4]));
        for (int i = 0; i < 256; i++) {
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(part("MCLY", ints(i % 64, 0, 0, 0)));
            chunk.writeBytes(part("MCAL", new byte[0]));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        files.sources.put(3L, out.toByteArray());
    }

    static class Files {
        final Map<Long, byte[]> sources = new ConcurrentHashMap<>();
        final Map<Long, AtomicInteger> stats = new ConcurrentHashMap<>(),
                opens = new ConcurrentHashMap<>();
        final AtomicInteger closed = new AtomicInteger(), borrowedClosed = new AtomicInteger();

        /** Bytes served by read() per file, across every stream opened for it. */
        final Map<Long, java.util.concurrent.atomic.AtomicLong> bytesRead =
                new ConcurrentHashMap<>();

        volatile long protectedId, changedId, wrongId;

        /** Opens that fail with a state and code instead of returning a stream. */
        final java.util.Map<Long, FileTypes.FileOpen> failedOpens = new ConcurrentHashMap<>();

        volatile boolean changeAfterRead;

        /** Called with each file id as it is opened for reading, before its stream exists. */
        volatile java.util.function.LongConsumer onOpenRead = id -> {};

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
                    if (!target.hasRemaining()) {
                        return CompletableFuture.completedFuture(0);
                    }
                    if (position == bytes.length) {
                        return CompletableFuture.completedFuture(-1);
                    }
                    int count = Math.min(target.remaining(), bytes.length - position);
                    target.put(bytes, position, count);
                    position += count;
                    bytesRead
                            .computeIfAbsent(
                                    id, ignored -> new java.util.concurrent.atomic.AtomicLong())
                            .addAndGet(count);
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
                                        case "stat", "statContent" ->
                                                CompletableFuture.completedFuture(
                                                        stat(
                                                                ((FileTypes.FileRef) args[0])
                                                                        .fileDataId(),
                                                                (Cancellation) args[1]));
                                        case "openRead" -> {
                                            long id = ((FileTypes.FileRef) args[0]).fileDataId();
                                            onOpenRead.accept(id);
                                            var failed = failedOpens.get(id);
                                            if (failed != null) {
                                                yield CompletableFuture.completedFuture(failed);
                                            }
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
    @SuppressWarnings("try")
    void
            actualIdleWaitsCanceledRawResultWorkerAndLowerCancellationFinallyWithoutClosingSharedSource()
                    throws Exception {
        var entered = new CompletableFuture<Void>();
        var releaseWorker = new CompletableFuture<Void>();
        var workerReturned = new CompletableFuture<Void>();
        var lowerEntered = new CompletableFuture<Void>();
        var releaseLower = new CompletableFuture<Void>();
        var first = new AtomicInteger();
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1 && first.getAndIncrement() == 0) {
                            try (var registration =
                                    token.onCancel(
                                            () -> {
                                                lowerEntered.complete(null);
                                                releaseLower.join();
                                            })) {
                                entered.complete(null);
                                releaseWorker.join();
                                token.check();
                            } finally {
                                workerReturned.complete(null);
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        var other = new WorldTerrainTextureSource(new Files().files(), pool);
        try {
            var raw = source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            entered.get(3, TimeUnit.SECONDS);
            raw.cancel(false);
            lowerEntered.get(3, TimeUnit.SECONDS);
            var idle = source.activityDrained();
            assertTrue(raw.isDone());
            assertFalse(idle.isDone());
            other.activityDrained().get(1, TimeUnit.SECONDS);
            releaseWorker.complete(null);
            workerReturned.get(3, TimeUnit.SECONDS);
            assertFalse(idle.isDone()); // Input worker returning is not lower callback retirement.
            releaseLower.complete(null);
            idle.get(3, TimeUnit.SECONDS);
            assertFalse(source.closed);
            try (var fresh =
                    source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                            .get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, fresh.tiles().getFirst().state());
            }
            source.activityDrained().get(3, TimeUnit.SECONDS);
        } finally {
            releaseWorker.complete(null);
            releaseLower.complete(null);
            source.closeAsync().get(5, TimeUnit.SECONDS);
            other.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void idleAndSourceCloseWaitPostActiveFinalizationInsteadOfOnlyEmptyActivePointer()
            throws Exception {
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(new Files().files(), pool);
        var held = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var allowLoad = new CompletableFuture<Void>();
        try {
            var operation =
                    source.readOwned(
                            MAP,
                            List.of(TILE),
                            Cancellation.NONE,
                            p -> {
                                if (p.stage() == WorldTerrainTextureSource.Stage.INVENTORY) {
                                    allowLoad.join();
                                }
                            });
            var field = WorldTerrainTextureSource.Operation.class.getDeclaredField("drained");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var actual = (CompletableFuture<Void>) field.get(operation);
            actual.whenComplete(
                    (nothing, error) -> {
                        held.complete(null);
                        release.join();
                    });
            allowLoad.complete(null);
            try (var result = operation.result().get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            held.get(3, TimeUnit.SECONDS);
            assertTrue(operation.isDrained()); // CF terminal flag alone does not prove
            // callback-finally.
            var idle = source.activityDrained();
            var closed = source.closeAsync();
            assertFalse(idle.isDone());
            assertFalse(closed.isDone());
            release.complete(null);
            idle.get(3, TimeUnit.SECONDS);
            closed.get(3, TimeUnit.SECONDS);
        } finally {
            allowLoad.complete(null);
            release.complete(null);
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void observationFailureAfterOpenClosesActualInputBeforeOwnedDrain() throws Exception {
        var files =
                new Files() {
                    @Override
                    FileReadStream open(long id, Cancellation token) {
                        if (id == 3) {
                            sources.put(3L, new byte[16]);
                        }
                        return super.open(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            var operation = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> operation.result().get(5, TimeUnit.SECONDS));
            operation.awaitDrained();
            assertEquals(files.totalOpens(), files.closed.get());
            assertEquals(0, pool.stats().entries());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void ownedObservationKeepsUnknownDeclaredLengthAndAllowsConsistentMetadataEnrichment()
            throws Exception {
        var known = new java.util.concurrent.atomic.AtomicBoolean();
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        var result = super.stat(id, token);
                        if (id != 3 || known.get()) {
                            return result;
                        }
                        var f = result.file();
                        return new FileTypes.FileStat(
                                result.state(),
                                new FileTypes.BuildFile(
                                        f.fileDataId(),
                                        f.path(),
                                        f.pathSource(),
                                        null,
                                        f.contentKey(),
                                        f.format(),
                                        f.formatSource(),
                                        f.storage(),
                                        f.encrypted()));
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> source.readOwned(MAP, List.of(TILE, TILE), Cancellation.NONE, p -> {}));
            var operation = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            try (var result = operation.result().get(5, TimeUnit.SECONDS)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            operation.awaitDrained();
            var tex =
                    operation.observations().stream()
                            .filter(o -> o.fileId() == 3)
                            .findFirst()
                            .orElseThrow();
            assertNull(tex.initialDeclaredLength());
            assertEquals((long) files.sources.get(3L).length, tex.streamLength());
            known.set(true);
            assertTrue(tex.matches(files.stat(3, Cancellation.NONE)));
            files.sources.put(3L, new byte[16]);
            assertFalse(tex.matches(files.stat(3, Cancellation.NONE)));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void ownedObservationRetainsActualFailureAndNegativeFactsWithoutPositiveBindings()
            throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var files = new Files();
            if (mode == 0) {
                files.sources.remove(3L);
            } else if (mode == 1) {
                files.wrongId = 3;
            } else {
                files.sources.put(3L, new byte[16]);
            }
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try {
                var operation = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
                try (var result = operation.result().get(5, TimeUnit.SECONDS)) {
                    assertEquals(
                            WorldTerrainTextureSource.State.UNAVAILABLE,
                            result.tiles().getFirst().state());
                }
                operation.awaitDrained();
                var trace = operation.observations();
                var tex = trace.stream().filter(o -> o.fileId() == 3).findFirst().orElseThrow();
                assertEquals(
                        mode == 0
                                ? WorldTerrainTextureSource.ObservationKind.NOT_FOUND
                                : mode == 1
                                        ? WorldTerrainTextureSource.ObservationKind.UNBOUND
                                        : WorldTerrainTextureSource.ObservationKind.FOUND,
                        tex.kind());
                assertTrue(tex.matches(files.stat(3, Cancellation.NONE)));
                if (mode == 2) {
                    assertEquals(16L, tex.streamLength());
                    assertEquals(FileTypes.FileReadState.COMPLETE, tex.readState());
                    assertEquals(Boolean.FALSE, tex.hasHoles());
                } else {
                    assertNull(tex.streamLength());
                    assertNull(tex.readState());
                }
                assertThrows(UnsupportedOperationException.class, trace::clear);
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void ownedPendingSupersededAlreadyCanceledAndClosedRequestsDrainWithoutNewIo()
            throws Exception {
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1) {
                            entered.complete(null);
                            release.join();
                            token.check();
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            var active = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            entered.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, active::observations);
            var already = new Cancellation();
            already.cancel();
            var rejectedCanceled = source.readOwned(MAP, List.of(TILE), already, p -> {});
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> rejectedCanceled.result().join());
            rejectedCanceled.awaitDrained();
            assertFalse(active.result().isDone());
            var pending = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            var latest = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> pending.result().join());
            pending.awaitDrained();
            assertTrue(pending.observations().isEmpty());
            var close = source.closeAsync();
            assertFalse(close.isDone());
            assertThrows(
                    java.util.concurrent.CancellationException.class, () -> latest.result().join());
            latest.awaitDrained();
            var closed = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
            assertThrows(
                    java.util.concurrent.CompletionException.class, () -> closed.result().join());
            closed.awaitDrained();
            assertEquals(0, files.totalOpens());
            release.complete(null);
            assertThrows(
                    java.util.concurrent.CancellationException.class, () -> active.result().join());
            active.awaitDrained();
            close.get(5, TimeUnit.SECONDS);
            assertTrue(
                    active.isDrained()
                            && pending.isDrained()
                            && latest.isDrained()
                            && closed.isDrained());
        } finally {
            release.complete(null);
            source.closeAsync().join();
            pool.closeAsync().join();
        }
    }

    @Test
    void ownedReadsPropagateUnknownFailuresButRetainKnownSourceProfileOutcomes() throws Exception {
        for (int mode = 0; mode < 6; mode++) {
            int selected = mode;
            var finalPhase = new java.util.concurrent.atomic.AtomicBoolean();
            var files =
                    new Files() {
                        @Override
                        FileTypes.FileStat stat(long id, Cancellation token) {
                            if (id == 10 && selected == 5 && finalPhase.get()) {
                                throw new NullPointerException("Authored final validation bug");
                            }
                            if (id == 10 && selected == 0) {
                                throw new NullPointerException("Authored provider bug");
                            }
                            if (id == 10 && selected == 1) {
                                throw new IllegalArgumentException(
                                        "Authored unknown provider argument bug");
                            }
                            return super.stat(id, token);
                        }
                    };
            if (mode == 2) {
                files.sources.put(3L, new byte[16]);
            }
            if (mode == 3) {
                files.sources.put(10L, new byte[160]);
            }
            if (mode == 4) {
                files.changedId = 10;
                files.changeAfterRead = true;
            }
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try {
                var owned =
                        source.readOwned(
                                MAP,
                                List.of(TILE),
                                Cancellation.NONE,
                                p -> {
                                    if (p.stage() == WorldTerrainTextureSource.Stage.REVALIDATING) {
                                        finalPhase.set(true);
                                    }
                                });
                try {
                    if (mode < 2 || mode >= 4) {
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> owned.result().get(5, TimeUnit.SECONDS));
                    } else {
                        try (var result = owned.result().get(5, TimeUnit.SECONDS)) {
                            assertTrue(
                                    result.tiles().getFirst().state()
                                            != WorldTerrainTextureSource.State.READY);
                            assertTrue(
                                    result.tiles().getFirst().issues().stream()
                                            .anyMatch(
                                                    issue ->
                                                            issue.reason()
                                                                    == (selected == 2
                                                                            ? WorldTerrainTextureSource
                                                                                    .Reason
                                                                                    .TEXTURE_PROFILE
                                                                            : WorldTerrainTextureSource
                                                                                    .Reason
                                                                                    .DIFFUSE_PROFILE)));
                        }
                    }
                } finally {
                    owned.awaitDrained();
                }
                assertTrue(owned.isDrained());
                assertEquals(0, pool.stats().leaseClaims());
                assertEquals(0, pool.stats().borrowPins());
            } finally {
                source.closeAsync().join();
                pool.closeAsync().join();
            }
        }
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 10) {
                            throw new NullPointerException("Legacy provider behavior unchanged");
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var legacy =
                source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                        .get(5, TimeUnit.SECONDS)) {
            assertTrue(legacy.tiles().getFirst().state() != WorldTerrainTextureSource.State.READY);
        } finally {
            source.closeAsync().join();
            pool.closeAsync().join();
        }
    }

    @Test
    void realFailureCausedByACancellationStaysAFailureAndPureChainsStayCancellation()
            throws Exception {
        var joined = new java.util.concurrent.CancellationException("join");
        joined.initCause(new java.util.concurrent.CancellationException());
        var real = new IllegalStateException("cleanup failed", joined);
        var pure = new java.util.concurrent.CompletionException(joined);
        for (var thrown : List.<RuntimeException>of(real, pure)) {
            var files =
                    new Files() {
                        @Override
                        FileTypes.FileStat stat(long id, Cancellation token) {
                            if (id == 10) {
                                throw thrown;
                            }
                            return super.stat(id, token);
                        }
                    };
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try {
                var owned = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
                try {
                    Throwable failure =
                            assertThrows(
                                    Exception.class, () -> owned.result().get(5, TimeUnit.SECONDS));
                    if (failure instanceof java.util.concurrent.ExecutionException) {
                        failure = failure.getCause();
                    }
                    if (thrown == real) {
                        assertTrue(failure == real, "the real failure surfaces: " + failure);
                    } else {
                        assertTrue(
                                WorldSourceRefusals.cleanCancellation(failure) != null,
                                "a pure chain stays a clean stop: " + failure);
                    }
                } finally {
                    owned.awaitDrained();
                }
                assertEquals(0, pool.stats().leaseClaims());
            } finally {
                source.closeAsync().join();
                pool.closeAsync().join();
            }
        }
        // Without strict failures the real failure is a missing diffuse, not a stopped read.
        var files =
                new Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 10) {
                            throw real;
                        }
                        return super.stat(id, token);
                    }
                };
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var legacy =
                source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                        .get(5, TimeUnit.SECONDS)) {
            assertTrue(
                    legacy.tiles().getFirst().issues().stream()
                            .anyMatch(
                                    issue ->
                                            issue.reason()
                                                    == WorldTerrainTextureSource.Reason
                                                            .DIFFUSE_PROFILE));
        } finally {
            source.closeAsync().join();
            pool.closeAsync().join();
        }
    }

    @Test
    void handmadeBc3DiffuseReachesQualifiedTerrainAtlas() throws Exception {
        var files = new Files();
        for (long id : new long[] {10, 11}) {
            var bytes = files.sources.get(id).clone();
            bytes[10] = 7;
            // BC3 alpha endpoint255, other endpoint0, selectors0 for every existing block.
            for (int at = 148; at < bytes.length; at += 16) {
                java.util.Arrays.fill(bytes, at, at + 8, (byte) 0);
                bytes[at] = (byte) 255;
            }
            files.sources.put(id, bytes);
        }
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var loaded = read(source)) {
            var tile = loaded.tiles().getFirst();
            assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
            assertEquals(256, tile.supportedChunks());
            for (var diffuse : tile.receipt().diffuse()) {
                assertEquals("BC3", diffuse.encoding());
                assertEquals(8, diffuse.alphaDepth());
                assertEquals(7, diffuse.alphaEncoding());
                assertEquals(2, diffuse.mipFlags());
            }
        } finally {
            source.closeAsync().join();
            pool.closeAsync().join();
        }
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
                // A WDT without MAID names no tex0 companions.
                files.sources.put(1L, java.util.Arrays.copyOf(wdt(0x3ca), 12 + 40 + 8 + 4096 * 8));
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
        // Capacity beyond the legacy nine current plus nine pending is held by other borrowers.
        var others = new ArrayList<TerrainAtlasPool.Reservation>();
        for (int i = 18; i < TerrainAtlasPool.MAX_ENTRIES; i++) {
            others.add(pool.reserve().orElseThrow());
        }
        try (var current =
                        source.read(MAP, tiles.subList(0, 9), Cancellation.NONE, p -> {})
                                .get(10, TimeUnit.SECONDS);
                var pending =
                        source.read(MAP, tiles.subList(9, 18), Cancellation.NONE, p -> {})
                                .get(10, TimeUnit.SECONDS)) {
            assertEquals(9, current.tiles().size());
            assertEquals(9, pending.tiles().size());
            assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
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
            assertEquals(TerrainAtlasPool.MAX_ENTRIES, pool.stats().entries());
            assertEquals(18, pool.stats().cachedEntries());
        } finally {
            for (var other : others) {
                other.close();
            }
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void mapsWithoutBigAlphaFlagsReadFourBitAlphaMapsAndAnyOtherFlags() throws Exception {
        for (int flags : new int[] {0, 0x2, 0x3ca ^ 0x80}) {
            var files = new Files();
            files.sources.put(1L, wdt(flags));
            var out = new ByteArrayOutputStream();
            out.writeBytes(part("MVER", ints(18)));
            out.writeBytes(part("MDID", ints(10, 11)));
            for (int i = 0; i < 256; i++) {
                var chunk = new ByteArrayOutputStream();
                chunk.writeBytes(part("MCLY", ints(0, 0, 0, 0, 1, 0x100, 0, 0)));
                byte[] alpha = new byte[2048];
                java.util.Arrays.fill(alpha, (byte) 0x88);
                chunk.writeBytes(part("MCAL", alpha));
                chunk.writeBytes(part("MCSH", new byte[512]));
                out.writeBytes(part("MCNK", chunk.toByteArray()));
            }
            files.sources.put(3L, out.toByteArray());
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
                assertEquals(256, tile.supportedChunks());
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
            assertEquals(files.totalOpens(), files.closed.get());
        }
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
            // A published atlas without splatting inputs is charged as one, not at the maximum.
            assertEquals(
                    com.vibeminer.rendering.world.TerrainTileAtlas.maxRetainedBytes(0),
                    pool.stats().chargedBytes());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void splattingInputsRideTheAtlasOnlyWhenEnabledAndWideChunksKeepTheAtlas() throws Exception {
        for (boolean enabled : new boolean[] {false, true}) {
            var files = new Files();
            files.sources.put(3L, eightLayers());
            for (long id = 10; id <= 17; id++) {
                files.sources.put(id, blp(0xf800));
            }
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            WorldTerrainTextureSource.splat = enabled;
            try (var result =
                    source.read(MAP, List.of(TILE), Cancellation.NONE, p -> {})
                            .get(10, TimeUnit.SECONDS)) {
                var lease = result.retainAtlas(TILE.x(), TILE.y()).orElseThrow();
                try (lease) {
                    lease.withAtlas(
                            atlas -> {
                                assertEquals(enabled, atlas.splat().isPresent());
                                atlas.splat()
                                        .ifPresent(
                                                splat -> {
                                                    // Eight layers per chunk: no splat layer.
                                                    assertEquals(0, splat.diffuseCount());
                                                    assertEquals(
                                                            com.vibeminer.rendering.world
                                                                    .TerrainTileSplat.NONE,
                                                            splat.layer(3, 4, 0));
                                                });
                            });
                }
            } finally {
                WorldTerrainTextureSource.splat = false;
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
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
        var owned = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {});
        var result = owned.result();
        entered.get(5, TimeUnit.SECONDS);
        var close = source.closeAsync();
        var failure =
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> close.get(5, TimeUnit.SECONDS));
        assertTrue(failure.toString().contains("Authored texture cancel failure"));
        assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.CompletionException.class, owned::awaitDrained);
        assertTrue(owned.isDrained());
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

    private int stats(Files files, long id) {
        return files.stats.getOrDefault(id, new AtomicInteger()).get();
    }

    @Test
    void wdtInventoryIsReadOncePerContentKeyAndReadAgainAfterItChanges() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            for (int i = 0; i < 3; i++) {
                try (var result = read(source)) {
                    assertEquals(
                            WorldTerrainTextureSource.State.READY,
                            result.tiles().getFirst().state());
                }
            }
            assertEquals(1, files.opens(1));
            // A new WDT content key misses the cache and is read and parsed again.
            files.changedId = 1;
            files.changeAfterRead = true;
            try (var result = read(source)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            assertEquals(2, files.opens(1));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    @Test
    void eachSourceIsStatedForItsReadAndOnceAtPublicationAndBatchEnd() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            try (var result = read(source)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            // WDT: its read's stat, the check before it is cached, publication and batch end.
            // Others: their read's stat, publication and batch end. No per-read or per-decode
            // revalidation.
            assertEquals(4, stats(files, 1));
            for (long id : new long[] {2, 3, 10, 11}) {
                assertEquals(3, stats(files, id), "source " + id);
            }
            try (var result = read(source)) {
                assertEquals(
                        WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            }
            // Warm: cached inventory and atlas; the WDT is not re-read.
            assertEquals(7, stats(files, 1));
            assertEquals(1, files.opens(1));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void preparedRootIsUsedWithoutReadingTheRootAgainButStillRevalidated() throws Exception {
        var files = new Files();
        var grid = WorldFormats.adtGrid(files.sources.get(2L), () -> false);
        String fingerprint =
                com.vibeminer.rendering.world.TerrainGeometryFingerprint.sha256(
                        TILE.x(), TILE.y(), grid);
        var binding =
                new WorldTerrainTextureSource.FileBinding(
                        2,
                        String.format("%032x", 2),
                        files.sources.get(2L).length,
                        (long) files.sources.get(2L).length,
                        8 << 20);
        var root = new WorldTerrainTextureSource.PreparedRoot(binding, grid, fingerprint);
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.readOwned(
                                    MAP,
                                    List.of(new WorldFormats.Tile(7, 19, 5, 0, 0, 0, 0, 3, 0, 0)),
                                    Cancellation.NONE,
                                    p -> {},
                                    root));
            var owned = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {}, root);
            try (var result = owned.awaitResult()) {
                var tile = result.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
                assertEquals(fingerprint, tile.receipt().geometrySha256());
                assertEquals(binding, tile.receipt().root());
            } finally {
                owned.awaitDrained();
            }
            assertEquals(0, files.opens(2));
            // Not read, but checked with the tile's other sources (publication, batch end).
            assertEquals(2, stats(files, 2));
            // A changed root is still caught.
            files.changedId = 2;
            files.changeAfterRead = true;
            files.opens.computeIfAbsent(2L, ignored -> new AtomicInteger()).incrementAndGet();
            var changed = source.readOwned(MAP, List.of(TILE), Cancellation.NONE, p -> {}, root);
            try {
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> changed.result().get(5, TimeUnit.SECONDS));
            } finally {
                changed.awaitDrained();
            }
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unparseableBytesFromASourceThatMovedAreReportedAsChanged() throws Exception {
        for (long id : new long[] {1, 2, 3}) {
            var files = new Files();
            files.sources.put(id, new byte[64]);
            files.changedId = id;
            files.changeAfterRead = true;
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.UNAVAILABLE, tile.state());
                assertEquals(
                        WorldTerrainTextureSource.Reason.CHANGED,
                        tile.issues().getFirst().reason(),
                        "source " + id);
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
            // Unchanged but unparseable bytes keep their profile reason.
            var stable = new Files();
            stable.sources.put(id, new byte[64]);
            var stablePool = new TerrainAtlasPool();
            var stableSource = new WorldTerrainTextureSource(stable.files(), stablePool);
            try (var result = read(stableSource)) {
                var reason = result.tiles().getFirst().issues().getFirst().reason();
                assertEquals(
                        id == 1
                                ? WorldTerrainTextureSource.Reason.INVENTORY_PROFILE
                                : id == 2
                                        ? WorldTerrainTextureSource.Reason.ROOT_PROFILE
                                        : WorldTerrainTextureSource.Reason.TEXTURE_PROFILE,
                        reason);
            } finally {
                stableSource.closeAsync().get(5, TimeUnit.SECONDS);
                stablePool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void ownedReadRefusesWhenTex0OrWdtChangesBeforeTheBatchEndPass() throws Exception {
        for (long id : new long[] {1, 3}) {
            var files = new Files();
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try {
                var owned =
                        source.readOwned(
                                MAP,
                                List.of(TILE),
                                Cancellation.NONE,
                                p -> {
                                    // After publication, before the batch-end revalidation.
                                    if (p.stage() == WorldTerrainTextureSource.Stage.REVALIDATING) {
                                        files.changedId = id;
                                        files.changeAfterRead = true;
                                    }
                                });
                try {
                    var failure =
                            assertThrows(
                                    java.util.concurrent.ExecutionException.class,
                                    () -> owned.result().get(5, TimeUnit.SECONDS));
                    assertTrue(
                            WorldTerrainTextureSource.ownedSourceChanged(failure.getCause()),
                            "source " + id + ": " + failure.getCause());
                } finally {
                    owned.awaitDrained();
                }
                assertEquals(0, pool.stats().leaseClaims());
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rootsUpToEightMebibytesAreTexturedOnThePublicPathToo() throws Exception {
        var files = new Files();
        byte[] root = files.sources.get(2L);
        int padding = (3 << 20) - root.length - 8;
        var padded = ByteBuffer.allocate(root.length + 8 + padding).order(ByteOrder.LITTLE_ENDIAN);
        padded.put(root).putInt(0x50414444).putInt(padding);
        files.sources.put(2L, padded.array());
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            assertEquals(WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
            assertEquals(8 << 20, result.tiles().getFirst().receipt().root().sourceLimit());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void diffuseFilesUpToEightMebibytesAreDecodedFromTheirSmallMip() throws Exception {
        byte[] large = blpChain(0xf800, 2048);
        assertTrue(large.length > 5 << 20 && large.length < 6 << 20);
        for (byte[] diffuse :
                new byte[][] {
                    blpChain(0xf800, 1024), large, java.util.Arrays.copyOf(large, (8 << 20) + 1)
                }) {
            int size = diffuse.length;
            var files = new Files();
            // Only the header and the 4x4 mip of these chains are read.
            files.sources.put(10L, diffuse);
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                if (size <= WorldTerrainTextureSource.DIFFUSE_SOURCE_LIMIT) {
                    assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
                } else {
                    // Every authored chunk uses this diffuse, so none is drawn.
                    assertEquals(WorldTerrainTextureSource.State.UNAVAILABLE, tile.state());
                    assertTrue(
                            tile.issues().stream()
                                    .anyMatch(
                                            issue ->
                                                    issue.reason()
                                                                    == WorldTerrainTextureSource
                                                                            .Reason.LIMIT
                                                            && issue.fileId() == 10));
                }
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void aLargeDiffuseReadsOnlyItsHeaderAndSmallMipAndTheBoundIsInclusive() throws Exception {
        byte[] large = blpChain(0xf800, 2048);
        var files = new Files();
        files.sources.put(10L, large);
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            assertEquals(WorldTerrainTextureSource.State.READY, result.tiles().getFirst().state());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        // 148-byte header plus the 16-byte 4x4 block level, out of about 5.3 MiB. This authored
        // stream has no BLTE chunks; on real files the decoded chunk holding the mip is the cost.
        long served = files.bytesRead.get(10L).get();
        assertTrue(served <= 4096, "bytes read " + served + " of " + large.length);
        // Exactly 8 MiB passes the size bound and reaches the decoder, which refuses this
        // padded chain on its own profile (DIFFUSE_PROFILE), not LIMIT.
        var exact = new Files();
        exact.sources.put(10L, java.util.Arrays.copyOf(large, 8 << 20));
        var exactPool = new TerrainAtlasPool();
        var exactSource = new WorldTerrainTextureSource(exact.files(), exactPool);
        try (var result = read(exactSource)) {
            var reasons =
                    result.tiles().getFirst().issues().stream()
                            .filter(issue -> issue.fileId() == 10)
                            .map(WorldTerrainTextureSource.Issue::reason)
                            .toList();
            assertEquals(List.of(WorldTerrainTextureSource.Reason.DIFFUSE_PROFILE), reasons);
        } finally {
            exactSource.closeAsync().get(5, TimeUnit.SECONDS);
            exactPool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aLargeDiffuseWithATruncatedMipOffsetIsRefusedCleanly() throws Exception {
        byte[] large = blpChain(0xf800, 2048);
        // Point the 4x4 level (level 9) past the end of the file.
        ByteBuffer.wrap(large).order(ByteOrder.LITTLE_ENDIAN).putInt(20 + 9 * 4, large.length + 64);
        var files = new Files();
        files.sources.put(10L, large);
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            var tile = result.tiles().getFirst();
            assertTrue(tile.state() != WorldTerrainTextureSource.State.READY);
            assertTrue(
                    tile.issues().stream()
                            .anyMatch(
                                    issue ->
                                            issue.fileId() == 10
                                                    && issue.reason()
                                                            == WorldTerrainTextureSource.Reason
                                                                    .DIFFUSE_PROFILE));
            assertEquals(0, pool.stats().leaseClaims());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(files.totalOpens(), files.closed.get());
    }

    /** The authored tile with its last chunk column declaring no layers (an empty MCLY). */
    private static byte[] textureWithEmptyEdge() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MDID", ints(10, 11)));
        for (int i = 0; i < 256; i++) {
            var chunk = new ByteArrayOutputStream();
            if (i % 16 == 15) {
                chunk.writeBytes(part("MCLY", new byte[0]));
            } else {
                chunk.writeBytes(part("MCLY", ints(0, 0, 0, 0)));
            }
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return out.toByteArray();
    }

    @Test
    void chunksWithNoLayersLeaveATileReadyAndCached() throws Exception {
        var files = new Files();
        files.sources.put(3L, textureWithEmptyEdge());
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
                assertEquals(240, tile.supportedChunks());
                assertEquals(16, tile.emptyChunks());
                assertEquals(16, tile.issues().size());
                assertTrue(
                        tile.issues().stream()
                                .allMatch(
                                        issue ->
                                                issue.maskIssue()
                                                        == com.vibeminer.formats.world
                                                                .WorldTextureFormats.ChunkIssue
                                                                .NO_LAYERS));
                try (var lease = result.retainAtlas(TILE.x(), TILE.y()).orElseThrow()) {
                    lease.withAtlas(
                            atlas -> {
                                assertTrue(atlas.complete());
                                assertEquals(16, atlas.emptyChunks());
                                assertFalse(atlas.covered(15));
                                assertTrue(atlas.empty(15));
                            });
                }
            }
            assertEquals(1, pool.stats().cachedEntries());
            int decodes = files.opens(10);
            // The second read is an atlas cache hit: no diffuse decode, the same drawn and empty
            // counts, and the same NO_LAYERS issues for diagnostics.
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
                assertEquals(240, tile.supportedChunks());
                assertEquals(16, tile.emptyChunks());
                assertEquals(16, tile.issues().size());
                assertTrue(
                        tile.issues().stream()
                                .allMatch(
                                        issue ->
                                                issue.ordinal() % 16 == 15
                                                        && issue.maskIssue()
                                                                == com.vibeminer.formats.world
                                                                        .WorldTextureFormats
                                                                        .ChunkIssue.NO_LAYERS));
            }
            assertEquals(decodes, files.opens(10));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void readyMeansEveryChunkDrawnOrEmpty() throws Exception {
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            var receipt = result.tiles().getFirst().receipt();
            var ready = WorldTerrainTextureSource.State.READY;
            var partial = WorldTerrainTextureSource.State.PARTIAL;
            new WorldTerrainTextureSource.TileResult(TILE, ready, receipt, 256, 0, List.of());
            new WorldTerrainTextureSource.TileResult(TILE, ready, receipt, 240, 16, List.of());
            new WorldTerrainTextureSource.TileResult(TILE, partial, receipt, 240, 0, List.of());
            for (int[] bad : new int[][] {{240, 0}, {0, 256}, {200, 57}, {240, -1}}) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new WorldTerrainTextureSource.TileResult(
                                        TILE, ready, receipt, bad[0], bad[1], List.of()),
                        java.util.Arrays.toString(bad));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new WorldTerrainTextureSource.TileResult(
                                    TILE, partial, receipt, 240, 16, List.of()));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    /** A BC2 BLP of width x height with the first {@code levels} levels, one colour. */
    private static byte[] blpShape(int flags, int width, int height, int levels) {
        long total = 148;
        for (int level = 0; level < levels; level++) {
            int bw = Math.max(1, (width >> level) / 4), bh = Math.max(1, (height >> level) / 4);
            total += (long) bw * bh * 16;
        }
        var data = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 8)
                .put((byte) 1)
                .put((byte) flags)
                .putInt(width)
                .putInt(height);
        int at = 148;
        for (int level = 0; level < levels; level++) {
            int bw = Math.max(1, (width >> level) / 4), bh = Math.max(1, (height >> level) / 4);
            int length = bw * bh * 16;
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            data.position(at);
            for (int block = 0; block < bw * bh; block++) {
                data.putLong(-1).putShort((short) 0xf800).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return data.array();
    }

    /** An uncompressed BGRA 8 x 8 chain down to 1 x 1 with the given alpha depth and encoding. */
    private static byte[] blpBgra(int alphaDepth, int alphaEncoding) {
        int total = 148 + (64 + 16 + 4 + 1) * 4;
        var data = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 3)
                .put((byte) alphaDepth)
                .put((byte) alphaEncoding)
                .put((byte) 1)
                .putInt(8)
                .putInt(8);
        int at = 148;
        for (int level = 0; level < 4; level++) {
            int side = 8 >> level;
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, side * side * 4);
            data.position(at);
            for (int pixel = 0; pixel < side * side; pixel++) {
                data.put((byte) 0x20).put((byte) 0x60).put((byte) 0xa0).put((byte) 0x10);
            }
            at += side * side * 4;
        }
        return data.array();
    }

    /**
     * An uncompressed chain of {@code levels} from {@code width x height}, left half of each level
     * red and right half blue, like the real 768 x 128 diffuses that stop at 6 x 1.
     */
    static byte[] blpBgraHalves(int width, int height, int levels) {
        int total = 148;
        for (int level = 0; level < levels; level++) {
            total += Math.max(1, width >> level) * Math.max(1, height >> level) * 4;
        }
        var data = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 3)
                .put((byte) 8)
                .put((byte) 0)
                .put((byte) 1)
                .putInt(width)
                .putInt(height);
        int at = 148;
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, w * h * 4);
            data.position(at);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // BGRA: red on the left, blue on the right.
                    data.put(
                            x < w / 2
                                    ? new byte[] {0, 0, (byte) 255, (byte) 255}
                                    : new byte[] {(byte) 255, 0, 0, (byte) 255});
                }
            }
            at += w * h * 4;
        }
        return data.array();
    }

    private static WorldTerrainTextureSource.DiffuseRefusal refusalFor(byte[] diffuse)
            throws Exception {
        var files = new Files();
        files.sources.put(10L, diffuse);
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            var refused =
                    result.tiles().getFirst().issues().stream()
                            .filter(issue -> issue.fileId() == 10)
                            .toList();
            assertEquals(1, refused.size(), refused.toString());
            assertEquals(
                    WorldTerrainTextureSource.Reason.DIFFUSE_PROFILE, refused.getFirst().reason());
            return refused.getFirst().diffuseRefusal();
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void diffuseProfileRefusalsSayWhy() throws Exception {
        // 16 x 12: a short side that is not a power of two has no level of short side 4.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.NON_SQUARE,
                refusalFor(blpShape(0, 16, 12, 1)));
        // 16 x 8 alone: the level of short side 4 (8 x 4) is not declared.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.MIP_UNAVAILABLE,
                refusalFor(blpShape(0, 16, 8, 1)));
        // A single 16 x 16 level: no mip of 4 pixels or less.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.MIP_UNAVAILABLE,
                refusalFor(blpShape(0, 16, 16, 1)));
        // A square chain that stops at 8 x 8 is still MIP_UNAVAILABLE.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.MIP_UNAVAILABLE,
                refusalFor(blpShape(1, 16, 16, 2)));
        // Not a BLP2 container.
        byte[] blp1 = blpShape(2, 8, 8, 4);
        blp1[3] = '1';
        assertEquals(WorldTerrainTextureSource.DiffuseRefusal.UNSUPPORTED, refusalFor(blp1));
        // A mip offset past the end of the file.
        byte[] truncated = blpShape(2, 8, 8, 4);
        ByteBuffer.wrap(truncated)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(20 + 1 * 4, truncated.length + 64);
        assertEquals(WorldTerrainTextureSource.DiffuseRefusal.INVALID, refusalFor(truncated));
        // The detail is only ever attached to DIFFUSE_PROFILE.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldTerrainTextureSource.Issue(
                                WorldTerrainTextureSource.Reason.LIMIT,
                                -1,
                                10,
                                null,
                                com.vibeminer.formats.world.WorldTextureFormats.ChunkIssue.NONE,
                                WorldTerrainTextureSource.DiffuseRefusal.INVALID));
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.NONE,
                new WorldTerrainTextureSource.Issue(WorldTerrainTextureSource.Reason.LIMIT, -1, 10)
                        .diffuseRefusal());
    }

    @Test
    void uncompressedDiffusesWithAnyAlphaDepthAndEncodingByteAreDrawn() throws Exception {
        // Recorded on real terrain diffuses: BGRA with alpha depth 0 or 4 and alpha encoding 2.
        for (int[] profile : new int[][] {{0, 2}, {4, 2}, {8, 0}, {1, 8}}) {
            var files = new Files();
            files.sources.put(10L, blpBgra(profile[0], profile[1]));
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(
                        List.of(),
                        tile.issues().stream().filter(issue -> issue.fileId() == 10).toList(),
                        Arrays.toString(profile));
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state());
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
        // A BGRA alpha depth the format does not define stays a named refusal.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.UNSUPPORTED, refusalFor(blpBgra(2, 2)));
    }

    @Test
    void nonSquareDiffusesAreDrawnStretchedFromTheirLevelOfShortSideFour() throws Exception {
        // 32 x 8 down to 4 x 1 (no 4 x 4 level), and 16 x 8 down to 1 x 1 (which has 4 x 2).
        // Also tall (8 x 32) and a long side that is not a power of two (24 x 4, level 0 itself).
        // Each is bound at its short side and the level whose short side is 4.
        int[][] shapes = {{32, 8, 4, 8, 1}, {16, 8, 5, 8, 1}, {8, 32, 4, 8, 1}, {24, 4, 1, 4, 0}};
        for (int[] shape : shapes) {
            var files = new Files();
            files.sources.put(10L, blpBgraHalves(shape[0], shape[1], shape[2]));
            var pool = new TerrainAtlasPool();
            var source = new WorldTerrainTextureSource(files.files(), pool);
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                String name = shape[0] + "x" + shape[1];
                assertEquals(
                        List.of(),
                        tile.issues().stream().filter(issue -> issue.fileId() == 10).toList(),
                        name);
                assertEquals(WorldTerrainTextureSource.State.READY, tile.state(), name);
                var binding =
                        tile.receipt().diffuse().stream()
                                .filter(b -> b.file().fileId() == 10)
                                .findFirst()
                                .orElseThrow();
                assertEquals(shape[3], binding.originalSide(), name);
                assertEquals(shape[4], binding.level(), name);
                // Chunk 200 draws file 10 alone: its cell keeps the red and blue halves.
                try (var lease = result.retainAtlas(TILE.x(), TILE.y()).orElseThrow()) {
                    lease.withAtlas(
                            atlas -> {
                                var m =
                                        atlas.mappings().stream()
                                                .filter(c -> c.ordinal() == 200)
                                                .findFirst()
                                                .orElseThrow();
                                var px = atlas.pixels();
                                boolean red = false, blue = false;
                                for (int y = 0; y < 32; y++) {
                                    for (int x = 0; x < 32; x++) {
                                        int at = ((m.y() * 32 + y) * 512 + m.x() * 32 + x) * 4;
                                        int r = px.get(at) & 255, b = px.get(at + 2) & 255;
                                        red |= r > 200 && b < 50;
                                        blue |= b > 200 && r < 50;
                                    }
                                }
                                assertTrue(red && blue, name + " cell keeps both halves");
                            });
                }
            } finally {
                source.closeAsync().get(5, TimeUnit.SECONDS);
                pool.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void boxAverageShrinksByMeansAndEnlargesByRepeats() {
        // 8 x 2: left half red, right half blue, to 4 x 4.
        byte[] source = new byte[8 * 2 * 4];
        for (int i = 0; i < 16; i++) {
            source[i * 4 + ((i % 8) < 4 ? 0 : 2)] = (byte) 255;
            source[i * 4 + 3] = (byte) 255;
        }
        byte[] cell = WorldTerrainTextureSource.boxAverage(source, 8, 2, 4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                int at = (y * 4 + x) * 4;
                assertEquals(x < 2 ? 255 : 0, cell[at] & 255);
                assertEquals(x < 2 ? 0 : 255, cell[at + 2] & 255);
                assertEquals(255, cell[at + 3] & 255);
            }
        }
        // Rounded mean: 0 and 255 over one output pixel give 128.
        byte[] pair = {0, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255};
        assertArrayEquals(
                new byte[] {(byte) 128, (byte) 128, (byte) 128, (byte) 128},
                WorldTerrainTextureSource.boxAverage(pair, 2, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTerrainTextureSource.boxAverage(pair, 3, 1, 1, 1));
        // 6 -> 4 is not a whole ratio: each output pixel weighs 1.5 source pixels by the part it
        // covers (1 and 1/2, then 1/2 and 1).
        byte[] six = new byte[6 * 4];
        int[] values = {0, 30, 60, 90, 120, 150};
        for (int i = 0; i < 6; i++) {
            six[i * 4] = (byte) values[i];
        }
        byte[] four = WorldTerrainTextureSource.boxAverage(six, 6, 1, 4, 1);
        int[] expected = {10, 50, 100, 140};
        for (int i = 0; i < 4; i++) {
            assertEquals(expected[i], four[i * 4] & 255, "pixel " + i);
        }
    }

    @Test
    void aNonSquareLevelOverItsPixelBoundIsALimit() throws Exception {
        // 2048 x 4: the level of short side 4 is 8,192 pixels, past the 4,096 bound.
        assertEquals(
                WorldTerrainTextureSource.DiffuseRefusal.LIMIT,
                refusalFor(blpBgraHalves(2048, 4, 1)));
    }

    @Test
    void aTileComposesAtTheMaskSideItsReservationWasChargedFor() throws Exception {
        boolean splat = WorldTerrainTextureSource.splat;
        int mask = WorldTerrainTextureSource.splatMask;
        int standard = com.vibeminer.rendering.world.TerrainTileSplat.STANDARD_MASK;
        int high = com.vibeminer.rendering.world.TerrainTileSplat.HIGH_MASK;
        var files = new Files();
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try {
            WorldTerrainTextureSource.splat = true;
            WorldTerrainTextureSource.splatMask = standard;
            // The quality tier rises to HIGH after the tile reserved its atlas (diffuses are
            // opened between reservation and compose): it still composes at STANDARD, its charge.
            files.onOpenRead =
                    id -> {
                        if (id == 10) {
                            WorldTerrainTextureSource.splatMask = high;
                        }
                    };
            try (var result = read(source)) {
                var tile = result.tiles().getFirst();
                assertEquals(
                        WorldTerrainTextureSource.State.READY,
                        tile.state(),
                        tile.issues().toString());
                assertEquals(high, WorldTerrainTextureSource.splatMask, "the tier did change");
                try (var lease = result.retainAtlas(TILE.x(), TILE.y()).orElseThrow()) {
                    lease.withAtlas(
                            atlas ->
                                    assertEquals(standard, atlas.splat().orElseThrow().maskSide()));
                }
            }
        } finally {
            WorldTerrainTextureSource.splat = splat;
            WorldTerrainTextureSource.splatMask = mask;
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aTileWithAnEmptyTextureListIsReadAsChunksWithNoLayers() throws Exception {
        // An empty MDID and MHID with every MCLY empty: nothing to draw, but not a refusal.
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        out.writeBytes(part("MDID", new byte[0]));
        out.writeBytes(part("MHID", new byte[0]));
        for (int i = 0; i < 256; i++) {
            out.writeBytes(part("MCNK", part("MCLY", new byte[0])));
        }
        var files = new Files();
        files.sources.put(3L, out.toByteArray());
        var pool = new TerrainAtlasPool();
        var source = new WorldTerrainTextureSource(files.files(), pool);
        try (var result = read(source)) {
            var tile = result.tiles().getFirst();
            assertEquals(256, tile.issues().size());
            assertTrue(
                    tile.issues().stream()
                            .allMatch(
                                    issue ->
                                            issue.reason() == WorldTerrainTextureSource.Reason.CHUNK
                                                    && issue.maskIssue()
                                                            == com.vibeminer.formats.world
                                                                    .WorldTextureFormats.ChunkIssue
                                                                    .NO_LAYERS));
            // No chunk is drawn, so the tile keeps the untextured fallback, as before.
            assertEquals(WorldTerrainTextureSource.State.UNAVAILABLE, tile.state());
            assertEquals(0, tile.supportedChunks());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
            pool.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }
}
