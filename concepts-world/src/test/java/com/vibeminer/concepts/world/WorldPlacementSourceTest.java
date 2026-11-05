package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldPlacementSourceTest {
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", "Authored", 0, null);
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    @Test
    void recordedObj0OnlyAndEmptyReadDiffersFromUnavailable() throws Exception {
        var source = new Source();
        var reader = new WorldPlacementSource(source.files());
        try {
            assertEquals(
                    WorldPlacementSource.State.UNAVAILABLE,
                    reader.read(MAP, tile(0, 1), Cancellation.NONE)
                            .get(5, TimeUnit.SECONDS)
                            .state());
            assertEquals(0, source.stats.get());
            var read = reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(WorldPlacementSource.State.READ, read.state());
            assertTrue(read.facts().doodads().isEmpty());
            assertEquals(BUILD, read.receipt().build());
            assertEquals("enUS", read.receipt().locale());
            assertEquals(91, read.receipt().obj0Id());
            assertEquals(1, source.closed.get());
        } finally {
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void canceledAdmissionAndWorldServiceLifetimeNeverCloseBorrowedSources() throws Exception {
        var source = new Source();
        var tables =
                (com.vibeminer.formats.tables.BuildTables)
                        Proxy.newProxyInstance(
                                com.vibeminer.formats.tables.BuildTables.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.formats.tables.BuildTables.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Borrowed tables unused: " + method.getName());
                                });
        var world = new WorldService(tables, source.files());
        var canceled = new Cancellation();
        canceled.cancel();
        try {
            assertThrows(
                    Exception.class,
                    () -> world.placements(MAP, tile(91, 1), canceled).get(5, TimeUnit.SECONDS));
            assertEquals(0, source.stats.get());
            assertEquals(
                    WorldPlacementSource.State.READ,
                    world.placements(MAP, tile(91, 1), Cancellation.NONE)
                            .get(5, TimeUnit.SECONDS)
                            .state());
        } finally {
            world.closeAsync().get(5, TimeUnit.SECONDS);
        }
        assertEquals(1, source.closed.get());
        assertTrue(
                world.placements(MAP, tile(91, 1), Cancellation.NONE).isCompletedExceptionally());
        world.closeAsync().get(5, TimeUnit.SECONDS);
    }

    @Test
    void worldCloseWaitsEveryHelperThenReportsCancellationCallbackFailure() throws Exception {
        var placementEntered = new CompletableFuture<Void>();
        var placementCanceled = new CompletableFuture<Void>();
        var minimapClosing = new CompletableFuture<Void>();
        var releaseMinimap = new CompletableFuture<Void>();
        var minimapClosed = new AtomicInteger();
        var source =
                new Source() {
                    @SuppressWarnings("try")
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 91) {
                            try (var registration =
                                    token.onCancel(
                                            () -> {
                                                placementCanceled.complete(null);
                                                throw new IllegalStateException(
                                                        "Authored cancellation callback failure");
                                            })) {
                                placementEntered.complete(null);
                                placementCanceled.join();
                                token.check();
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var base = source.files();
        var files =
                (BuildFiles)
                        Proxy.newProxyInstance(
                                BuildFiles.class.getClassLoader(),
                                new Class<?>[] {BuildFiles.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("openRead")
                                            && ((FileTypes.FileRef) args[0]).fileDataId() == 92) {
                                        return CompletableFuture.completedFuture(
                                                new FileTypes.FileOpen(
                                                        FileTypes.FileReadState.COMPLETE,
                                                        new FileReadStream() {
                                                            int position;

                                                            public long length() {
                                                                return 148;
                                                            }

                                                            public long position() {
                                                                return position;
                                                            }

                                                            public void seek(long value) {
                                                                position = (int) value;
                                                            }

                                                            public List<FileTypes.FileHole>
                                                                    holes() {
                                                                return List.of();
                                                            }

                                                            public CompletableFuture<Integer> read(
                                                                    ByteBuffer target,
                                                                    Cancellation token) {
                                                                int count =
                                                                        Math.min(
                                                                                target.remaining(),
                                                                                148 - position);
                                                                target.put(new byte[count]);
                                                                position += count;
                                                                return CompletableFuture
                                                                        .completedFuture(count);
                                                            }

                                                            public void close() {
                                                                minimapClosing.complete(null);
                                                                releaseMinimap.join();
                                                                minimapClosed.incrementAndGet();
                                                            }
                                                        },
                                                        null,
                                                        null,
                                                        null));
                                    }
                                    try {
                                        return method.invoke(base, args);
                                    } catch (java.lang.reflect.InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
        var tables =
                (com.vibeminer.formats.tables.BuildTables)
                        Proxy.newProxyInstance(
                                com.vibeminer.formats.tables.BuildTables.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.formats.tables.BuildTables.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("Borrowed tables remain open");
                                });
        var world = new WorldService(tables, files);
        try {
            world.minimap(new WorldFormats.Tile(1, 2, 0, 0, 92), Cancellation.NONE);
            minimapClosing.get(5, TimeUnit.SECONDS);
            world.placements(MAP, tile(91, 1), Cancellation.NONE);
            placementEntered.get(5, TimeUnit.SECONDS);
            var close = world.closeAsync();
            var field = WorldService.class.getDeclaredField("placements");
            field.setAccessible(true);
            var helper = (WorldPlacementSource) field.get(world);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> helper.closeAsync().get(5, TimeUnit.SECONDS));
            assertFalse(
                    close.isDone(),
                    "failed placement helper must not skip outstanding minimap drain");
            assertEquals(0, minimapClosed.get());
            releaseMinimap.complete(null);
            var failure =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> close.get(5, TimeUnit.SECONDS));
            assertTrue(failure.toString().contains("Authored cancellation callback failure"));
            assertEquals(1, minimapClosed.get());
            assertTrue(world.closeAsync().isCompletedExceptionally());
        } finally {
            releaseMinimap.complete(null);
            try {
                world.closeAsync().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                /* authored cancellation failure */
            }
        }
    }

    @Test
    void cacheChecksIdentityAndAttachesFreshRequestNamespace() throws Exception {
        var source = new Source();
        var reader = new WorldPlacementSource(source.files());
        try {
            var first = reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            var other =
                    reader.read(
                                    new WorldService.MapInfo(8, "Other", null, 0, null),
                                    tile(91, 2),
                                    Cancellation.NONE)
                            .get(5, TimeUnit.SECONDS);
            assertEquals(1, source.opens.get());
            assertEquals(8, other.receipt().mapId());
            assertEquals(2, other.receipt().tile().x());
            assertEquals(first.facts(), other.facts());
            source.wrongId = true;
            var wrong = reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(WorldPlacementSource.State.UNAVAILABLE, wrong.state());
            assertNull(wrong.receipt());
            assertEquals(1, source.opens.get());
            source.wrongId = false;
            source.key = "c".repeat(32);
            assertEquals(
                    WorldPlacementSource.State.READ,
                    reader.read(MAP, tile(91, 1), Cancellation.NONE)
                            .get(5, TimeUnit.SECONDS)
                            .state());
            assertEquals(2, source.opens.get());
        } finally {
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void postReadIdOrContentChangeCannotEnterCache() throws Exception {
        for (boolean wrongId : List.of(false, true)) {
            var source = new Source();
            source.postReadChange = true;
            source.changeId = wrongId;
            var reader = new WorldPlacementSource(source.files());
            try {
                assertEquals(
                        WorldPlacementSource.State.UNAVAILABLE,
                        reader.read(MAP, tile(91, 1), Cancellation.NONE)
                                .get(5, TimeUnit.SECONDS)
                                .state());
                source.postReadChange = false;
                assertEquals(
                        WorldPlacementSource.State.READ,
                        reader.read(MAP, tile(91, 1), Cancellation.NONE)
                                .get(5, TimeUnit.SECONDS)
                                .state());
                assertEquals(2, source.opens.get());
                assertEquals(2, source.closed.get());
            } finally {
                reader.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void malformedProtectedAndOversizedSourcesNeverBecomeEmptyReads() throws Exception {
        for (int mode = 1; mode <= 4; mode++) {
            var source = new Source();
            source.mode = mode;
            var reader = new WorldPlacementSource(source.files());
            try {
                var result =
                        reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
                assertEquals(WorldPlacementSource.State.UNAVAILABLE, result.state());
                assertNotNull(result.issue());
                assertNull(result.facts());
                assertNull(result.receipt());
                assertEquals(source.opens.get(), source.closed.get());
                if (mode == 3) {
                    assertEquals(0, source.opens.get());
                }
            } finally {
                reader.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void realFailureCausedByACancellationIsUnavailableAndPureChainsStayCancellation()
            throws Exception {
        var joined = new java.util.concurrent.CancellationException("join");
        joined.initCause(new java.util.concurrent.CancellationException());
        var source = new Source();
        source.statFailure = new IllegalStateException("cleanup failed", joined);
        var reader = new WorldPlacementSource(source.files());
        try {
            var result = reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(WorldPlacementSource.State.UNAVAILABLE, result.state());
            source.statFailure = joined;
            var canceled =
                    assertThrows(
                            Exception.class,
                            () ->
                                    reader.read(MAP, tile(91, 1), Cancellation.NONE)
                                            .get(5, TimeUnit.SECONDS));
            assertTrue(
                    WorldSourceRefusals.cleanCancellation(canceled) != null,
                    "a pure chain stays a clean stop: " + canceled);
        } finally {
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void recordAndEntryCacheBudgetsEvictWholeFactsDeterministically() throws Exception {
        var source = new Source();
        source.records = 4096;
        var reader = new WorldPlacementSource(source.files());
        try {
            for (long id : new long[] {91, 92, 93}) {
                var result =
                        reader.read(MAP, tile(id, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
                assertEquals(4096, result.facts().doodads().size());
                assertEquals(4096, result.facts().worldObjects().size());
            }
            reader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(
                    4,
                    source.opens.get(),
                    "aggregate16krecords evicts oldest full8192-record result");
        } finally {
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
        var empty = new Source();
        var entryReader = new WorldPlacementSource(empty.files());
        try {
            for (long id = 91; id <= 100; id++) {
                entryReader.read(MAP, tile(id, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            }
            entryReader.read(MAP, tile(91, 1), Cancellation.NONE).get(5, TimeUnit.SECONDS);
            assertEquals(11, empty.opens.get(), "nine-entry budget applies even to empty metadata");
        } finally {
            entryReader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void queuedCancellationAndSaturationDoNotOpenExtraSources() throws Exception {
        var entered = new CompletableFuture<Void>();
        var released = new CompletableFuture<Void>();
        var source =
                new Source() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        entered.complete(null);
                        released.join();
                        return super.stat(id, token);
                    }
                };
        var reader = new WorldPlacementSource(source.files());
        try {
            var active = reader.read(MAP, tile(91, 1), Cancellation.NONE);
            entered.get(5, TimeUnit.SECONDS);
            var canceled = new Cancellation();
            var queued = reader.read(MAP, tile(92, 1), canceled);
            assertTrue(reader.read(MAP, tile(93, 1), Cancellation.NONE).isCompletedExceptionally());
            canceled.cancel();
            released.complete(null);
            assertEquals(WorldPlacementSource.State.READ, active.get(5, TimeUnit.SECONDS).state());
            assertThrows(Exception.class, () -> queued.get(5, TimeUnit.SECONDS));
            assertEquals(1, source.opens.get());
        } finally {
            released.complete(null);
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blockedOlderCancelCannotDiscardCurrentSourceRetirementOrBlockCloseCaller()
            throws Exception {
        var firstEntered = new CompletableFuture<Void>();
        var firstCanceled = new CompletableFuture<Void>();
        var callbackEntered = new CompletableFuture<Void>();
        var releaseCallback = new CompletableFuture<Void>();
        var secondEntered = new CompletableFuture<Void>();
        var secondCanceled = new CompletableFuture<Void>();
        var callerThread = Thread.currentThread();
        var source =
                new Source() {
                    @SuppressWarnings("try")
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 91) {
                            try (var registration =
                                    token.onCancel(
                                            () -> {
                                                assertFalse(Thread.currentThread() == callerThread);
                                                firstCanceled.complete(null);
                                                callbackEntered.complete(null);
                                                releaseCallback.join();
                                            })) {
                                firstEntered.complete(null);
                                firstCanceled.join();
                                token.check();
                            }
                        } else if (id == 92) {
                            try (var registration =
                                    token.onCancel(() -> secondCanceled.complete(null))) {
                                secondEntered.complete(null);
                                secondCanceled.join();
                                token.check();
                            }
                        }
                        return super.stat(id, token);
                    }
                };
        var reader = new WorldPlacementSource(source.files());
        try {
            var caller = new Cancellation();
            var first = reader.read(MAP, tile(91, 1), caller);
            firstEntered.get(5, TimeUnit.SECONDS);
            caller.cancel();
            callbackEntered.get(5, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> first.get(5, TimeUnit.SECONDS));
            var second = reader.read(MAP, tile(92, 1), Cancellation.NONE);
            secondEntered.get(5, TimeUnit.SECONDS);
            var pendingToken = new Cancellation();
            var pending = reader.read(MAP, tile(93, 1), pendingToken);
            pendingToken.cancel();
            var close = reader.closeAsync();
            assertFalse(close.isDone(), "blocked older callback still owned and accounted");
            assertTrue(reader.read(MAP, tile(94, 1), Cancellation.NONE).isCompletedExceptionally());
            releaseCallback.complete(null);
            secondCanceled.get(5, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> second.get(5, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> pending.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            reader.closeAsync().get(5, TimeUnit.SECONDS);
            assertEquals(0, source.opens.get());
        } finally {
            releaseCallback.complete(null);
            reader.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    private static WorldFormats.Tile tile(long id, int x) {
        return new WorldFormats.Tile(x, 2, 0, 0, 0, id, 999, 0, 0, 0);
    }

    private static class Source {
        final AtomicInteger stats = new AtomicInteger(),
                opens = new AtomicInteger(),
                closed = new AtomicInteger();
        volatile boolean wrongId, postReadChange, changeId;
        volatile String key = "a".repeat(32);
        volatile Throwable statFailure;
        int mode, records;

        FileTypes.FileStat stat(long id, Cancellation token) {
            token.check();
            int call = stats.incrementAndGet();
            boolean changed = postReadChange && call == 2;
            return new FileTypes.FileStat(
                    FileTypes.FileLookupState.FOUND,
                    new FileTypes.BuildFile(
                            wrongId || changed && changeId ? id + 1 : id,
                            null,
                            FileTypes.FilePathSource.NONE,
                            mode == 3 ? 9L << 20 : (long) bytes().length,
                            changed && !changeId ? "b".repeat(32) : key,
                            FileTypes.FileFormat.UNKNOWN,
                            FileTypes.FileFormatSource.NONE,
                            FileTypes.FileStorage.STORED,
                            false));
        }

        BuildFiles files() {
            return (BuildFiles)
                    Proxy.newProxyInstance(
                            BuildFiles.class.getClassLoader(),
                            new Class<?>[] {BuildFiles.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("build")) {
                                    return BUILD;
                                }
                                if (method.getName().equals("locale")) {
                                    return "enUS";
                                }
                                if (method.getName().equals("stat")
                                        || method.getName().equals("statContent")) {
                                    var ref = (FileTypes.FileRef) args[0];
                                    assertNull(ref.path());
                                    assertTrue(
                                            ref.fileDataId() >= 91 && ref.fileDataId() < 999,
                                            "onlyrecordedobj0IDs");
                                    if (statFailure != null) {
                                        return CompletableFuture.failedFuture(statFailure);
                                    }
                                    return CompletableFuture.completedFuture(
                                            stat(ref.fileDataId(), (Cancellation) args[1]));
                                }
                                if (method.getName().equals("openRead")) {
                                    opens.incrementAndGet();
                                    byte[] data = bytes();
                                    return CompletableFuture.completedFuture(
                                            new FileTypes.FileOpen(
                                                    FileTypes.FileReadState.COMPLETE,
                                                    new FileReadStream() {
                                                        int position;

                                                        public long length() {
                                                            return data.length;
                                                        }

                                                        public long position() {
                                                            return position;
                                                        }

                                                        public void seek(long value) {
                                                            position = (int) value;
                                                        }

                                                        public List<FileTypes.FileHole> holes() {
                                                            return mode == 2
                                                                    ? List.of(
                                                                            new FileTypes.FileHole(
                                                                                    0, 4, "key"))
                                                                    : List.of();
                                                        }

                                                        public CompletableFuture<Integer> read(
                                                                ByteBuffer target,
                                                                Cancellation token) {
                                                            token.check();
                                                            if (mode == 4) {
                                                                return CompletableFuture
                                                                        .completedFuture(0);
                                                            }
                                                            int n =
                                                                    Math.min(
                                                                            target.remaining(),
                                                                            data.length - position);
                                                            target.put(data, position, n);
                                                            position += n;
                                                            return CompletableFuture
                                                                    .completedFuture(n);
                                                        }

                                                        public void close() {
                                                            closed.incrementAndGet();
                                                        }
                                                    },
                                                    null,
                                                    null,
                                                    null));
                                }
                                throw new AssertionError(
                                        "Borrowed source may not close: " + method.getName());
                            });
        }

        byte[] bytes() {
            var b =
                    ByteBuffer.allocate(12 + (records == 0 ? 0 : 16 + records * 100))
                            .order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0x4d564552).putInt(4).putInt(mode == 1 ? 17 : 18);
            if (records > 0) {
                b.putInt(0x4d444446).putInt(records * 36);
                b.position(b.position() + records * 36);
                b.putInt(0x4d4f4446).putInt(records * 64);
            }
            return b.array();
        }
    }
}
