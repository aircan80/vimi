package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldAreaNavigationTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(0, "Authored", "Test", 0, 5L);

    @Test
    void mixedAreasPreserveExactTileReferenceEntryChunkAndPartialCoverage() {
        byte[] one = adt(42, -1L, 0);
        byte[] two = adt(42, 0, 1);
        byte[] three = adt(43, 0, 2);
        byte[] mixed =
                ByteBuffer.allocate(one.length + two.length + three.length - 24)
                        .put(one)
                        .put(two, 12, two.length - 12)
                        .put(three, 12, three.length - 12)
                        .array();
        var fixture = new Files(Map.of(10L, mixed, 11L, adt(43, 0, 0)));
        var updates = new ArrayList<WorldAreaNavigation.Progress>();
        try (var helper = new WorldAreaNavigation(fixture.handle())) {
            var result = helper.scan(request(10, 11, 12), Cancellation.NONE, updates::add).join();
            assertEquals(WorldAreaNavigation.State.PARTIAL, result.state());
            assertEquals(3, result.progress().checked());
            assertEquals(1, result.progress().unavailable());
            assertEquals(1, result.matches().size());
            var match = result.matches().getFirst();
            assertEquals(10, match.tile().rootId());
            assertEquals(KEY, match.contentKey());
            assertEquals(2, match.matchingChunks());
            assertEquals(1, match.entryChunk().x());
            assertEquals(309, match.entryChunk().entryReference().z());
            assertEquals(1, fixture.maxOpen.get());
            assertEquals(0, fixture.open.get());
            assertEquals(1, result.issues().size());
            assertEquals(3, updates.getLast().checked());
        }
        assertEquals(0, fixture.borrowedCloses.get());
    }

    @Test
    void sourceChangeProtectedAndFullyHoledTilesRemainDistinctEvidence() {
        var fixture =
                new Files(Map.of(10L, adt(42, 0, 0), 11L, adt(42, 0, 0), 12L, adt(42, -1L, 0)));
        fixture.changed = 10;
        fixture.protectedId = 11;
        try (var helper = new WorldAreaNavigation(fixture.handle())) {
            var result = helper.scan(request(10, 11, 12), Cancellation.NONE, p -> {}).join();
            assertEquals(2, result.progress().unavailable());
            assertEquals(1, result.matches().size());
            assertEquals(12, result.matches().getFirst().tile().rootId());
            assertEquals(null, result.matches().getFirst().entryChunk());
            assertTrue(result.issues().stream().anyMatch(s -> s.contains("changed")));
            assertTrue(result.issues().stream().anyMatch(s -> s.contains("protected")));
            assertEquals(0, fixture.open.get());
        }
    }

    @Test
    void stalledReadKeepsOneWorkerAndOnlyLatestPendingRequest() throws Exception {
        var fixture = new Files(Map.of(10L, adt(42, 0, 0), 11L, adt(42, 0, 0), 12L, adt(42, 0, 0)));
        fixture.gated = 10;
        try (var helper = new WorldAreaNavigation(fixture.handle())) {
            var first = helper.scan(request(10), Cancellation.NONE, p -> {});
            assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
            var superseded = helper.scan(request(11), Cancellation.NONE, p -> {});
            var latest = helper.scan(request(12), Cancellation.NONE, p -> {});
            assertEquals(WorldAreaNavigation.State.CANCELED, superseded.join().state());
            assertFalse(first.isDone());
            assertFalse(latest.isDone());
            assertEquals(List.of(10L), fixture.readIds);
            fixture.gate.complete(null);
            assertEquals(
                    WorldAreaNavigation.State.CANCELED, first.get(5, TimeUnit.SECONDS).state());
            assertEquals(
                    WorldAreaNavigation.State.COMPLETE, latest.get(5, TimeUnit.SECONDS).state());
            assertEquals(List.of(10L, 12L), fixture.readIds);
            assertEquals(1, fixture.maxOpen.get());
        }
    }

    @Test
    void closeWaitsForAcceptedReadAndKeepsBorrowedFilesOpen() throws Exception {
        var fixture = new Files(Map.of(10L, adt(42, 0, 0)));
        fixture.gated = 10;
        var helper = new WorldAreaNavigation(fixture.handle());
        var scan = helper.scan(request(10), Cancellation.NONE, p -> {});
        assertTrue(fixture.entered.await(5, TimeUnit.SECONDS));
        var close = helper.closeAsync();
        assertFalse(close.isDone());
        assertTrue(helper.scan(request(10), Cancellation.NONE, p -> {}).isCompletedExceptionally());
        fixture.gate.complete(null);
        close.get(5, TimeUnit.SECONDS);
        assertEquals(WorldAreaNavigation.State.CANCELED, scan.join().state());
        assertEquals(0, fixture.open.get());
        assertEquals(0, fixture.borrowedCloses.get());
    }

    @Test
    void cancellationRetainsCompletedMatchesAndDiagnosticsAreBounded() {
        var fixture = new Files(Map.of(10L, adt(42, 0, 0)));
        var token = new Cancellation();
        fixture.afterClose = token::cancel;
        try (var helper = new WorldAreaNavigation(fixture.handle())) {
            var canceled = helper.scan(request(10, 11), token, p -> {}).join();
            assertEquals(WorldAreaNavigation.State.CANCELED, canceled.state());
            assertTrue(canceled.progress().checked() <= 1);
        }
        try (var helper = new WorldAreaNavigation(new Files(Map.of()).handle())) {
            var tiles = new ArrayList<WorldFormats.Tile>();
            for (int i = 0; i < 100; i++) {
                tiles.add(new WorldFormats.Tile(i / 64, i % 64, i + 1, 0, 0));
            }
            var result =
                    helper.scan(
                                    new WorldAreaNavigation.Request(MAP, 42, tiles),
                                    Cancellation.NONE,
                                    p -> {})
                            .join();
            assertEquals(100, result.progress().unavailable());
            assertEquals(32, result.issues().size());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldAreaNavigation.Request(MAP, 0, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldAreaNavigation.Request(MAP, 42, List.of(tile(1, 0), tile(2, 0))));
    }

    private static WorldAreaNavigation.Request request(long... ids) {
        var tiles = new ArrayList<WorldFormats.Tile>();
        for (int i = 0; i < ids.length; i++) {
            tiles.add(tile(ids[i], i));
        }
        return new WorldAreaNavigation.Request(MAP, 42, tiles);
    }

    private static WorldFormats.Tile tile(long id, int at) {
        return new WorldFormats.Tile(at / 64, at % 64, id, 0, 0);
    }

    private static byte[] adt(int area, long holes, int chunkX) {
        var b = ByteBuffer.allocate(12 + 8 + 128 + 8 + 145 * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x4d564552).putInt(4).putInt(18).putInt(0x4d434e4b).putInt(128 + 8 + 145 * 4);
        b.putInt(20, 0x10000).putInt(24, chunkX).putLong(40, holes).putInt(72, area);
        b.putFloat(124, 100).putFloat(128, 200).putFloat(132, 300);
        b.position(148);
        b.putInt(0x4d435654).putInt(145 * 4);
        for (int i = 0; i < 145; i++) {
            b.putFloat(i);
        }
        return b.array();
    }

    private static final class Files {
        final Map<Long, byte[]> payloads;
        final AtomicInteger open = new AtomicInteger();
        final AtomicInteger maxOpen = new AtomicInteger();
        final AtomicInteger borrowedCloses = new AtomicInteger();
        final List<Long> readIds = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<Long, AtomicInteger> stats = new java.util.concurrent.ConcurrentHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CompletableFuture<Void> gate = new CompletableFuture<>();
        long gated = -1;
        long changed = -1;
        long protectedId = -1;
        Runnable afterClose = () -> {};

        Files(Map<Long, byte[]> payloads) {
            this.payloads = payloads;
        }

        BuildFiles handle() {
            return (BuildFiles)
                    Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("close")
                                        || method.getName().equals("closeAsync")) {
                                    borrowedCloses.incrementAndGet();
                                    return CompletableFuture.completedFuture(null);
                                }
                                var ref = (FileTypes.FileRef) args[0];
                                long id = ref.fileDataId();
                                byte[] bytes = payloads.get(id);
                                if (method.getName().equals("stat")) {
                                    int calls =
                                            stats.computeIfAbsent(id, key -> new AtomicInteger())
                                                    .incrementAndGet();
                                    return CompletableFuture.completedFuture(
                                            new FileTypes.FileStat(
                                                    bytes == null
                                                            ? FileTypes.FileLookupState.UNKNOWN_FILE
                                                            : FileTypes.FileLookupState.FOUND,
                                                    bytes == null
                                                            ? null
                                                            : new FileTypes.BuildFile(
                                                                    id,
                                                                    null,
                                                                    FileTypes.FilePathSource.NONE,
                                                                    (long) bytes.length,
                                                                    id == changed && calls > 1
                                                                            ? "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                                                            : KEY,
                                                                    FileTypes.FileFormat.ADT_ROOT,
                                                                    FileTypes.FileFormatSource
                                                                            .MAGIC,
                                                                    FileTypes.FileStorage.STORED,
                                                                    false)));
                                }
                                if (!method.getName().equals("openRead")) {
                                    throw new AssertionError(method.getName());
                                }
                                open.incrementAndGet();
                                maxOpen.accumulateAndGet(open.get(), Math::max);
                                readIds.add(id);
                                var stream =
                                        new FileReadStream() {
                                            int at;
                                            boolean closed;

                                            public long length() {
                                                return bytes.length;
                                            }

                                            public long position() {
                                                return at;
                                            }

                                            public void seek(long value) {
                                                at = (int) value;
                                            }

                                            public List<FileTypes.FileHole> holes() {
                                                return id == protectedId
                                                        ? List.of(
                                                                new FileTypes.FileHole(
                                                                        1, 1, "authored"))
                                                        : List.of();
                                            }

                                            public CompletableFuture<Integer> read(
                                                    ByteBuffer target, Cancellation token) {
                                                entered.countDown();
                                                var ready =
                                                        id == gated
                                                                ? gate
                                                                : CompletableFuture
                                                                        .<Void>completedFuture(
                                                                                null);
                                                return ready.thenApply(
                                                        v -> {
                                                            token.check();
                                                            int count =
                                                                    Math.min(
                                                                            target.remaining(),
                                                                            bytes.length - at);
                                                            target.put(bytes, at, count);
                                                            at += count;
                                                            return count;
                                                        });
                                            }

                                            public void close() {
                                                if (!closed) {
                                                    closed = true;
                                                    open.decrementAndGet();
                                                    afterClose.run();
                                                }
                                            }
                                        };
                                return CompletableFuture.completedFuture(
                                        new FileTypes.FileOpen(
                                                FileTypes.FileReadState.COMPLETE,
                                                stream,
                                                null,
                                                null,
                                                null));
                            });
        }
    }
}
