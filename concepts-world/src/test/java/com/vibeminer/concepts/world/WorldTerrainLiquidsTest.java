package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileReadStream;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.AuthoredNativeLiquids;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
@SuppressWarnings("try")
class WorldTerrainLiquidsTest {
    private static final WorldService.MapInfo MAP =
            new WorldService.MapInfo(7, "Authored", null, 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    private static byte[] liquids(int count, int secondary, long mask, boolean nonplanar) {
        int tables = 3072 + count * 24, bitmaps = tables, vertices = bitmaps + count * 8;
        var b = ByteBuffer.allocate(vertices + count * 405).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int header = i / 4, instance = i % 4;
            if (instance == 0) {
                b.putInt(header * 12, 3072 + i * 24)
                        .putInt(header * 12 + 4, Math.min(4, count - i));
            }
            int at = 3072 + i * 24;
            b.putShort(at, (short) 1250)
                    .putShort(at + 2, (short) secondary)
                    .putFloat(at + 4, 5)
                    .putFloat(at + 8, nonplanar ? 6 : 5);
            b.put(at + 14, (byte) 8)
                    .put(at + 15, (byte) 8)
                    .putInt(at + 16, bitmaps + i * 8)
                    .putInt(at + 20, vertices + i * 405);
            b.putLong(bitmaps + i * 8, mask << instance);
            for (int v = 0; v < 81; v++) {
                b.putFloat(vertices + i * 405 + v * 4, nonplanar && v == 1 ? 6 : 5);
            }
            for (int v = 0; v < 81; v++) {
                b.put(vertices + i * 405 + 324 + v, (byte) (200 + v % 50));
            }
        }
        return b.array();
    }

    private static byte[] append(byte[] root, byte[] payload) {
        var b =
                ByteBuffer.allocate(root.length + 8 + payload.length)
                        .order(ByteOrder.LITTLE_ENDIAN);
        return b.put(root).putInt(0x4d48324f).putInt(payload.length).put(payload).array();
    }

    private static final class Fixture implements AutoCloseable {
        AuthoredNativeLiquids nativeRows;
        int liquidCaptures;
        boolean changedTable, changedRoot;
        int changeAtCapture;
        boolean failCaptureClose;
        final CompletableFuture<Void> entered = new CompletableFuture<>(),
                canceled = new CompletableFuture<>();
        final CompletableFuture<Integer> release = new CompletableFuture<>();
        boolean hold;
        final WorldTerrainTextureSourceTest.Files base =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    FileTypes.FileStat stat(long id, Cancellation token) {
                        if (id == 1371380 || id == 1132538) {
                            token.check();
                            return new FileTypes.FileStat(
                                    FileTypes.FileLookupState.FOUND,
                                    new FileTypes.BuildFile(
                                            id,
                                            null,
                                            FileTypes.FilePathSource.NONE,
                                            (long) nativeRows.sourceBytes(id),
                                            changedTable
                                                    ? "c".repeat(32)
                                                    : id == 1371380
                                                            ? AuthoredNativeLiquids.TYPE_KEY
                                                            : AuthoredNativeLiquids.MATERIAL_KEY,
                                            FileTypes.FileFormat.UNKNOWN,
                                            FileTypes.FileFormatSource.NONE,
                                            FileTypes.FileStorage.STORED,
                                            false));
                        }
                        if (id == 2 && changedRoot) {
                            var old = super.stat(id, token).file();
                            return new FileTypes.FileStat(
                                    FileTypes.FileLookupState.FOUND,
                                    new FileTypes.BuildFile(
                                            id,
                                            null,
                                            FileTypes.FilePathSource.NONE,
                                            old.size(),
                                            "b".repeat(32),
                                            FileTypes.FileFormat.UNKNOWN,
                                            FileTypes.FileFormatSource.NONE,
                                            FileTypes.FileStorage.STORED,
                                            false));
                        }
                        return super.stat(id, token);
                    }

                    @Override
                    FileReadStream open(long id, Cancellation token) {
                        var actual = super.open(id, token);
                        if (id != 2 || !hold) {
                            return actual;
                        }
                        return new FileReadStream() {
                            Cancellation.Registration registration;

                            public long length() {
                                return actual.length();
                            }

                            public long position() {
                                return actual.position();
                            }

                            public void seek(long p) {
                                actual.seek(p);
                            }

                            public List<FileTypes.FileHole> holes() {
                                return actual.holes();
                            }

                            public CompletableFuture<Integer> read(
                                    ByteBuffer target, Cancellation stop) {
                                registration = stop.onCancel(() -> canceled.complete(null));
                                entered.complete(null);
                                return release;
                            }

                            public void close() {
                                if (registration != null) {
                                    registration.close();
                                }
                                actual.close();
                            }
                        };
                    }
                };
        final BuildFiles files = base.files();
        final WorldStreamScopesTest.Tables maps = new WorldStreamScopesTest.Tables(base);
        final WorldStreamScopes scopes;
        final WorldStreamScopes.Scope scope;
        final BuildTables tables;
        final byte[] terrain = base.sources.get(2L);

        Fixture() {
            nativeRows = new AuthoredNativeLiquids(files);
            var mapTables = maps.handle();
            tables =
                    (BuildTables)
                            Proxy.newProxyInstance(
                                    BuildTables.class.getClassLoader(),
                                    new Class<?>[] {BuildTables.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("build")) {
                                            return files.build();
                                        }
                                        if (method.getName().equals("locale")) {
                                            return files.locale();
                                        }
                                        if (!method.getName().equals("capturePreparedLocal")) {
                                            throw new AssertionError("No other table API");
                                        }
                                        if (((TableTypes.Ref) args[0]).name().equals("Map")) {
                                            return method.invoke(mapTables, args);
                                        }
                                        liquidCaptures++;
                                        if (changeAtCapture == liquidCaptures) {
                                            changedTable = true;
                                        }
                                        return nativeRows
                                                .tables()
                                                .capturePreparedLocal(
                                                        (TableTypes.Ref) args[0],
                                                        (Cancellation) args[1])
                                                .thenApply(
                                                        captured -> {
                                                            if (!failCaptureClose) {
                                                                return captured;
                                                            }
                                                            var actual = captured.rows();
                                                            var wrapped =
                                                                    new com.vibeminer.formats.tables
                                                                            .CapturedRows() {
                                                                        public com.vibeminer.formats
                                                                                        .tables
                                                                                        .CapturedRowsTypes
                                                                                        .Receipt
                                                                                receipt() {
                                                                            return actual.receipt();
                                                                        }

                                                                        public CompletableFuture<
                                                                                        com
                                                                                                .vibeminer
                                                                                                .formats
                                                                                                .tables
                                                                                                .CapturedRowsTypes
                                                                                                .Page>
                                                                                readOrdinalPage(
                                                                                        long first,
                                                                                        int count,
                                                                                        Cancellation
                                                                                                token) {
                                                                            return actual
                                                                                    .readOrdinalPage(
                                                                                            first,
                                                                                            count,
                                                                                            token);
                                                                        }

                                                                        public CompletableFuture<
                                                                                        com
                                                                                                .vibeminer
                                                                                                .formats
                                                                                                .tables
                                                                                                .CapturedRowsTypes
                                                                                                .Page>
                                                                                readOrdinalPage(
                                                                                        long first,
                                                                                        int count,
                                                                                        com
                                                                                                        .vibeminer
                                                                                                        .formats
                                                                                                        .tables
                                                                                                        .CapturedRowsTypes
                                                                                                        .ReadLimits
                                                                                                limits,
                                                                                        Cancellation
                                                                                                token) {
                                                                            return actual
                                                                                    .readOrdinalPage(
                                                                                            first,
                                                                                            count,
                                                                                            limits,
                                                                                            token);
                                                                        }

                                                                        public CompletableFuture<
                                                                                        Void>
                                                                                closeAsync() {
                                                                            return actual.closeAsync()
                                                                                    .thenApply(
                                                                                            ignored -> {
                                                                                                throw new IllegalStateException(
                                                                                                        "Actual capture close failed");
                                                                                            });
                                                                        }
                                                                    };
                                                            return new com.vibeminer.formats.tables
                                                                    .CapturedRowsTypes.Capture(
                                                                    captured.state(),
                                                                    wrapped,
                                                                    captured.code(),
                                                                    captured.message());
                                                        });
                                    });
            scopes = new WorldStreamScopes(tables, files);
            scope = scopes.create(MAP, Cancellation.NONE);
            scopes.activate(scope);
        }

        void payload(byte[] p) {
            base.sources.put(2L, append(terrain, p));
        }

        WorldTerrainLiquids.Owner inspect() {
            return scopes.liquids().inspect(scope, TILE, Cancellation.NONE);
        }

        public void close() throws Exception {
            hold = false;
            release.completeExceptionally(new java.util.concurrent.CancellationException());
            scopes.closeAsync().get(5, TimeUnit.SECONDS);
            nativeRows.close();
        }
    }

    @Test
    void absentAndPresentEmptyReadNoLiquidTables() {
        try (var f = new Fixture()) {
            try (var absent = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.ABSENT, absent.summary().state());
            }
            f.payload(new byte[3072]);
            try (var empty = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.EMPTY, empty.summary().state());
            }
            assertEquals(0, f.liquidCaptures);
            assertEquals(0, f.scopes.liquidCredits());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void indirectObject42AbsentStillQualifiesActualMaterialAndUsedHeight() throws Exception {
        try (var f = new Fixture()) {
            byte[] p = liquids(1, 42, 1, false);
            var b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            // Only vertices0,1,9,10 are used; finite unused zero padding stays unconstructed.
            b.putFloat(3072 + 24 + 8 + 80 * 4, 0);
            f.payload(p);
            try (var owner = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.READY, owner.summary().state());
                assertEquals(4, f.liquidCaptures);
                owner.withBorrow(
                        v -> {
                            assertEquals(1, v.layers());
                            assertEquals(42, v.secondary(0));
                            assertEquals(1250, v.liquidType(0));
                            assertEquals(5, v.actualUsedHeight(0));
                            assertEquals(1, v.mask(0));
                            assertEquals(200, v.depth(0, 0));
                            return null;
                        });
            }
            assertEquals(0, f.scopes.liquidCredits());
        }
    }

    @Test
    void allClearUnsupportedAndNonplanarAreNeverGuessedEmptyOrFlat() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 0, false));
            try (var owner = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.EMPTY, owner.summary().state());
            }
            f.payload(liquids(1, 1, 0, false));
            try (var owner = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.UNRESOLVED, owner.summary().state());
                assertEquals(
                        WorldTerrainLiquids.Reason.UNRESOLVED_FOOTPRINT, owner.summary().reason());
            }
            f.payload(liquids(1, 0, 1, true));
            try (var owner = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.UNRESOLVED, owner.summary().state());
                assertEquals(
                        WorldTerrainLiquids.Reason.NONPLANAR_PROFILE, owner.summary().reason());
            }
            assertEquals(0, f.liquidCaptures);
        }
    }

    @Test
    void unsupportedMaterialOrMissingCopyNeverTurnAnAllClearMaskIntoEmpty() throws Exception {
        for (boolean missingCopy : new boolean[] {false, true}) {
            try (var f = new Fixture()) {
                f.nativeRows.close();
                f.nativeRows =
                        new AuthoredNativeLiquids(f.files, missingCopy ? 130 : 2, !missingCopy);
                f.payload(liquids(1, 42, 0, false));
                try (var owner = f.inspect()) {
                    assertEquals(WorldTerrainLiquids.State.UNRESOLVED, owner.summary().state());
                    assertEquals(
                            missingCopy
                                    ? WorldTerrainLiquids.Reason.ROW_NOT_OBSERVED
                                    : WorldTerrainLiquids.Reason.UNRESOLVED_FOOTPRINT,
                            owner.summary().reason());
                    assertEquals(0, owner.summary().admitted());
                }
                assertEquals(0, f.scopes.liquidCredits());
            }
        }
    }

    @Test
    void twoCreditsIncludeConstructionThirdRefusesBeforeIoAndBorrowExpires() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            var saved = new AtomicReference<WorldTerrainLiquids.Borrow>();
            var other =
                    new WorldTerrainLiquids(
                            f.scopes, f.tables, f.files, f.files.build(), f.files.locale());
            try (var a = f.inspect();
                    var b = other.inspect(f.scope, TILE, Cancellation.NONE)) {
                int opens = f.base.opens.get(2L).get(), captures = f.maps.captures.get();
                assertThrows(WorldTerrainLiquids.Pressure.class, f::inspect);
                assertEquals(opens, f.base.opens.get(2L).get());
                assertEquals(captures, f.maps.captures.get());
                a.withBorrow(
                        v -> {
                            saved.set(v);
                            assertThrows(
                                    WorldTerrainLiquids.Pressure.class,
                                    () -> a.withBorrow(n -> n.layers()));
                            CompletableFuture.runAsync(
                                            () ->
                                                    assertThrows(
                                                            IllegalStateException.class,
                                                            () -> v.layers()))
                                    .join();
                            return null;
                        });
                assertThrows(IllegalStateException.class, () -> saved.get().layers());
                a.close();
                try (var next = f.inspect()) {
                    assertEquals(2, f.scopes.liquidCredits());
                }
            }
            assertEquals(0, f.scopes.liquidCredits());
        }
    }

    @Test
    void rootCapAndNoGeometryCacheOnlyAuthority() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(129, 0, 1, false));
            try (var owner = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.PARTIAL, owner.summary().state());
                assertEquals(128, owner.summary().admitted());
                assertEquals(WorldTerrainLiquids.Reason.CAPPED, owner.summary().reason());
            }
            int before = f.base.opens.get(2L).get();
            f.payload(liquids(1, 0, 1, true));
            try (var owner = f.inspect()) {
                assertEquals(
                        WorldTerrainLiquids.Reason.NONPLANAR_PROFILE, owner.summary().reason());
            }
            assertEquals(before + 1, f.base.opens.get(2L).get());
        }
    }

    @Test
    void scopeRetirementBlocksNewBorrowButKeepsDetachedSummaryHistorical() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            try (var owner = f.inspect()) {
                var next = f.scopes.create(MAP, Cancellation.NONE);
                f.scopes.activate(next);
                assertThrows(RuntimeException.class, () -> owner.withBorrow(v -> v.layers()));
                assertEquals(1, owner.summary().admitted());
            }
        }
    }

    @Test
    void knownNegativeTableObservationStillRechecksSourceBeforeTransfer() throws Exception {
        try (var f = new Fixture()) {
            byte[] p = liquids(1, 42, 1, false);
            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putShort(3072, (short) 999);
            f.payload(p);
            f.changeAtCapture = 2;
            assertThrows(WorldTerrainLiquids.Changed.class, f::inspect);
            assertEquals(0, f.scopes.liquidCredits());
            f.changeAtCapture = 0;
            f.changedTable = false;
            f.payload(liquids(1, 42, 1, false));
            try (var recovered = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.READY, recovered.summary().state());
            }
        }
    }

    @Test
    void malformedAndOverlappingOptionalDataPreserveTerrainAndRemainUnresolved() throws Exception {
        try (var f = new Fixture()) {
            f.payload(new byte[3]);
            try (var malformed = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.UNRESOLVED, malformed.summary().state());
                assertEquals(WorldTerrainLiquids.Reason.MALFORMED, malformed.summary().reason());
                assertEquals(-1, malformed.summary().declared());
                assertEquals(256, WorldFormats.adt(f.base.sources.get(2L), () -> false).chunks());
            }
            byte[] p = liquids(2, 0, 1, false);
            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putLong(3072 + 48 + 8, 1);
            f.payload(p);
            try (var overlap = f.inspect()) {
                assertEquals(WorldTerrainLiquids.Reason.MULTILAYER, overlap.summary().reason());
                assertEquals(0, overlap.summary().admitted());
            }
            p = liquids(1, 0, 1, false);
            ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(3072 + 20, 0);
            f.payload(p);
            try (var missing = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.UNRESOLVED, missing.summary().state());
                assertEquals(WorldTerrainLiquids.Reason.VERTEX_ABSENT, missing.summary().reason());
            }
            assertEquals(0, f.liquidCaptures);
        }
    }

    @Test
    void partialGridDoesNotMintAnAnchorMappingOrReadLiquidTables() throws Exception {
        try (var f = new Fixture()) {
            int firstEnd =
                    20 + ByteBuffer.wrap(f.terrain).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
            f.base.sources.put(
                    2L,
                    append(java.util.Arrays.copyOf(f.terrain, firstEnd), liquids(1, 42, 1, false)));
            try (var partial = f.inspect()) {
                assertEquals(WorldTerrainLiquids.Reason.GRID_PROFILE, partial.summary().reason());
                assertEquals(0, partial.summary().admitted());
                assertEquals(null, partial.summary().geometrySha());
            }
            assertEquals(0, f.liquidCaptures);
        }
    }

    @Test
    void captureCleanupFailureQuarantinesSourceAndCannotHideBehindOptionalCoverage()
            throws Exception {
        var f = new Fixture();
        WorldTerrainLiquids.Owner old = null;
        try {
            f.payload(liquids(1, 0, 1, false));
            old = f.inspect();
            f.payload(liquids(1, 42, 1, false));
            f.failCaptureClose = true;
            assertThrows(WorldLiquidTables.CleanupFailure.class, f::inspect);
            var held = old;
            assertThrows(IllegalStateException.class, () -> held.withBorrow(v -> v.layers()));
            assertEquals(1, f.scopes.liquidCredits());
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> f.scopes.closeAsync().get(3, TimeUnit.SECONDS));
            assertEquals(0, f.scopes.liquidCredits());
        } finally {
            if (old != null) {
                old.close();
            }
            try {
                f.scopes.closeAsync().get(3, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() != null);
            }
            f.nativeRows.close();
        }
    }

    @Test
    void externalCancellationWaitsActualReadAndLeavesHealthyScopeRetryable() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            f.hold = true;
            var stop = new Cancellation();
            var result = new CompletableFuture<Throwable>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    f.scopes.liquids().inspect(f.scope, TILE, stop);
                                    result.completeExceptionally(
                                            new AssertionError("Canceled inspection escaped"));
                                } catch (Throwable failure) {
                                    result.complete(failure);
                                }
                            });
            try {
                f.entered.get(3, TimeUnit.SECONDS);
                stop.cancel();
                f.canceled.get(3, TimeUnit.SECONDS);
                assertFalse(result.isDone());
                assertEquals(1, f.scopes.liquidCredits());
                var original = new java.util.concurrent.CancellationException();
                f.release.completeExceptionally(original);
                org.junit.jupiter.api.Assertions.assertSame(
                        original, result.get(3, TimeUnit.SECONDS));
                assertEquals(0, f.scopes.liquidCredits());
                assertTrue(f.scopes.isKnown(f.scope));
                f.hold = false;
                try (var fresh = f.inspect()) {
                    assertEquals(WorldTerrainLiquids.State.READY, fresh.summary().state());
                }
            } finally {
                f.release.completeExceptionally(new java.util.concurrent.CancellationException());
                result.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void terrainHolesDoNotEraseIndependentRawLiquidMask() throws Exception {
        try (var f = new Fixture()) {
            byte[] root = f.terrain.clone();
            ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putShort(20 + 60, (short) 0xffff);
            f.base.sources.put(2L, append(root, liquids(1, 0, 1, false)));
            try (var water = f.inspect()) {
                assertEquals(WorldTerrainLiquids.State.READY, water.summary().state());
                water.withBorrow(
                        v -> {
                            assertEquals(1, v.mask(0));
                            return null;
                        });
            }
        }
    }

    @Test
    void throwingScalarCallbackReleasesItsPinWithoutCertifyingCallerResources() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            try (var owner = f.inspect()) {
                var error = new AssertionError("Caller callback failure");
                var localClosed = new java.util.concurrent.atomic.AtomicInteger();
                org.junit.jupiter.api.Assertions.assertSame(
                        error,
                        assertThrows(
                                AssertionError.class,
                                () ->
                                        owner.withBorrow(
                                                v -> {
                                                    try (AutoCloseable local =
                                                            localClosed::incrementAndGet) {
                                                        throw error;
                                                    } catch (Exception impossible) {
                                                        throw new AssertionError(impossible);
                                                    }
                                                })));
                assertEquals(1, localClosed.get());
                assertEquals(1, f.scopes.liquidCredits());
                owner.withBorrow(
                        v -> {
                            assertEquals(1, v.layers());
                            return null;
                        });
            }
            assertEquals(0, f.scopes.liquidCredits());
        }
    }

    @Test
    void sourceCloseCancelsNewRootBeforeWaitingForOldBorrowAndCredits() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            var old = f.inspect();
            var readEntered = new CompletableFuture<Void>();
            var readRelease = new CompletableFuture<Void>();
            var read = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    old.withBorrow(
                                            v -> {
                                                readEntered.complete(null);
                                                readRelease.join();
                                                assertEquals(5, v.actualUsedHeight(0));
                                                return null;
                                            });
                                    read.complete(null);
                                } catch (Throwable e) {
                                    read.completeExceptionally(e);
                                }
                            });
            var building = new CompletableFuture<Throwable>();
            try {
                readEntered.get(3, TimeUnit.SECONDS);
                f.hold = true;
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        f.inspect();
                                        building.completeExceptionally(
                                                new AssertionError("Closed builder escaped"));
                                    } catch (Throwable e) {
                                        building.complete(e);
                                    }
                                });
                f.entered.get(3, TimeUnit.SECONDS);
                assertEquals(2, f.scopes.liquidCredits());
                var drain = f.scopes.closeAsync();
                f.canceled.get(3, TimeUnit.SECONDS);
                assertFalse(drain.isDone());
                assertFalse(building.isDone());
                var copied = old.closeAsync();
                copied.cancel(false);
                assertFalse(drain.isDone());
                f.release.completeExceptionally(new java.util.concurrent.CancellationException());
                assertEquals(
                        java.util.concurrent.CancellationException.class,
                        building.get(3, TimeUnit.SECONDS).getClass());
                assertEquals(1, f.scopes.liquidCredits());
                assertFalse(drain.isDone());
                readRelease.complete(null);
                read.get(3, TimeUnit.SECONDS);
                drain.get(3, TimeUnit.SECONDS);
                assertEquals(0, f.scopes.liquidCredits());
            } finally {
                f.release.completeExceptionally(new java.util.concurrent.CancellationException());
                readRelease.complete(null);
                read.get(3, TimeUnit.SECONDS);
                old.close();
            }
        }
    }

    @Test
    void rejectedCreateCannotCompleteDrainWithHeldLiquidBorrow() throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            var owner = f.inspect();
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var finished = new CompletableFuture<Void>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    owner.withBorrow(
                                            v -> {
                                                entered.complete(null);
                                                release.join();
                                                assertEquals(5, v.actualUsedHeight(0));
                                                return null;
                                            });
                                    finished.complete(null);
                                } catch (Throwable failure) {
                                    finished.completeExceptionally(failure);
                                }
                            });
            try {
                entered.get(3, TimeUnit.SECONDS);
                var drain = f.scopes.closeAsync();
                assertFalse(drain.isDone());
                assertEquals(1, f.scopes.liquidCredits());
                assertThrows(RuntimeException.class, () -> f.scopes.create(MAP, Cancellation.NONE));
                assertFalse(drain.isDone(), "Rejected work must not retire held liquid owner");
            } finally {
                release.complete(null);
                finished.get(3, TimeUnit.SECONDS);
                owner.close();
            }
        }
    }

    @Test
    void flatGeometryUsesActualRootAnchorsUnsignedDepthAndExplicitPositiveUpDiagonal()
            throws Exception {
        try (var f = new Fixture()) {
            f.payload(liquids(1, 0, 1, false));
            try (var owner = f.inspect()) {
                owner.withBorrow(
                        v -> {
                            assertEquals(4, v.vertexCount());
                            assertEquals(6, v.indexCount());
                            assertEquals(5, v.vertexUp(0));
                            assertEquals(200, v.vertexDepth(0));
                            assertEquals(201, v.vertexDepth(1));
                            assertEquals(209, v.vertexDepth(2));
                            assertEquals(210, v.vertexDepth(3));
                            assertEquals(v.vertexNorth(0), v.vertexNorth(1));
                            assertEquals(v.vertexWest(0), v.vertexWest(2));
                            assertTrue(v.vertexNorth(2) < v.vertexNorth(0));
                            assertTrue(v.vertexWest(1) < v.vertexWest(0));
                            int[] expected = {0, 2, 1, 1, 2, 3};
                            for (int n = 0; n < 6; n++) {
                                assertEquals(expected[n], v.index(n));
                            }
                            float ax = v.vertexNorth(2) - v.vertexNorth(0),
                                    ay = v.vertexWest(2) - v.vertexWest(0);
                            float bx = v.vertexNorth(1) - v.vertexNorth(0),
                                    by = v.vertexWest(1) - v.vertexWest(0);
                            assertTrue(ax * by - ay * bx > 0);
                            assertThrows(IndexOutOfBoundsException.class, () -> v.vertexDepth(4));
                            return null;
                        });
            }
        }
    }

    @Test
    void geometryPrecountAllocatesExactMaximumWithoutTrimAndEmptyAllocatesNothing()
            throws Exception {
        try (var f = new Fixture()) {
            var p = liquids(128, 0, -1L, false);
            var b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
            for (int header = 0; header < 256; header++) {
                b.putInt(header * 12, 0).putInt(header * 12 + 4, 0);
            }
            for (int i = 0; i < 128; i++) {
                b.putInt(i * 12, 3072 + i * 24).putInt(i * 12 + 4, 1);
                b.putLong(3072 + 128 * 24 + i * 8, -1L);
            }
            f.payload(p);
            try (var owner = f.inspect()) {
                assertEquals(8192, owner.summary().cells());
                owner.withBorrow(
                        v -> {
                            assertEquals(32768, v.vertexCount());
                            assertEquals(49152, v.indexCount());
                            assertEquals(32767, v.index(49151));
                            assertEquals(5, v.vertexUp(32767));
                            return null;
                        });
            }
            f.payload(liquids(1, 0, 0, false));
            try (var owner = f.inspect()) {
                owner.withBorrow(
                        v -> {
                            assertEquals(0, v.vertexCount());
                            assertEquals(0, v.indexCount());
                            return null;
                        });
            }
            assertEquals(0, f.scopes.liquidCredits());
        }
    }

    @Test
    void sourceGeometryUsesAsymmetricActualAnchorAndCellAxesWithoutTerrainBase() throws Exception {
        try (var f = new Fixture()) {
            var bytes = ByteBuffer.wrap(f.terrain).order(ByteOrder.LITTLE_ENDIAN);
            while (bytes.hasRemaining()) {
                int tag = bytes.getInt(), length = bytes.getInt(), start = bytes.position();
                if (tag == 0x4d434e4b) {
                    bytes.putFloat(start + 104, 1000)
                            .putFloat(start + 108, -2000)
                            .putFloat(start + 112, 300);
                    break;
                }
                bytes.position(start + length);
            }
            f.payload(liquids(1, 0, 1L << 17, false));
            try (var owner = f.inspect()) {
                owner.withBorrow(
                        v -> {
                            assertEquals(4, v.vertexCount());
                            assertEquals(
                                    Float.floatToRawIntBits(
                                            (float) (1000 - 4 * (WorldFormats.TILE_SIZE / 256))),
                                    Float.floatToRawIntBits(v.vertexNorth(0)));
                            assertEquals(
                                    Float.floatToRawIntBits(
                                            (float) (-2000 - 1 * (WorldFormats.TILE_SIZE / 128))),
                                    Float.floatToRawIntBits(v.vertexWest(0)));
                            assertEquals(5, v.vertexUp(0));
                            return null;
                        });
            }
        }
    }

    private static final class PublicFixture implements AutoCloseable {
        final Fixture authored = new Fixture();
        final TerrainAtlasPool pool = new TerrainAtlasPool();
        final WorldService world;
        final WorldTerrainStreaming terrain;
        final WorldWaterPreview water;
        final WorldTerrainStreaming.WrittenTile<AutoCloseable> tile;
        final AtomicReference<RuntimeException> terrainCleanup = new AtomicReference<>();
        boolean expectedCloseFailure;

        PublicFixture(int secondary) {
            authored.payload(liquids(1, secondary, 1, false));
            world = new WorldService(authored.tables, authored.files, pool);
            terrain = world.terrainStreaming().orElseThrow();
            water = world.waterPreview().orElseThrow();
            var scope = terrain.createScope(MAP, Cancellation.NONE);
            terrain.activate(scope);
            tile =
                    terrain.prepareAndWrite(
                            scope,
                            TILE,
                            Cancellation.NONE,
                            (b, t) ->
                                    (AutoCloseable)
                                            () -> {
                                                if (terrainCleanup.get() != null) {
                                                    throw terrainCleanup.get();
                                                }
                                            });
        }

        public void close() throws Exception {
            try {
                world.closeAsync().get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException fatalCleanup) {
                if (!expectedCloseFailure) {
                    throw fatalCleanup;
                }
            } finally {
                pool.closeAsync().get(5, TimeUnit.SECONDS);
                authored.close();
            }
        }
    }

    @Test
    void publicWaterRequiresActualOpaqueTerrainAndDirectLayoutKeepsTablesAbsent() throws Exception {
        try (var f = new PublicFixture(0);
                var foreign = new PublicFixture(0)) {
            int capturesBefore = f.authored.liquidCaptures;
            var saved = new AtomicReference<WorldWaterPreview.CheckedBorrow>();
            var closes = new java.util.concurrent.atomic.AtomicInteger();
            var result =
                    f.water.prepareAndWrite(
                            f.tile,
                            Cancellation.NONE,
                            (b, t) -> {
                                t.check();
                                saved.set(b);
                                assertEquals(4, b.vertices());
                                assertEquals(6, b.indices());
                                assertEquals(0, b.facts().tableObservations().size());
                                return (AutoCloseable) closes::incrementAndGet;
                            });
            var written = (WorldWaterPreview.WrittenWater<AutoCloseable>) result;
            assertEquals(capturesBefore, f.authored.liquidCaptures);
            assertThrows(IllegalStateException.class, () -> saved.get().vertices());
            assertEquals(4, written.withPayload(WorldWaterPreview.CheckedBorrow::vertices));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            f.water.prepareAndWrite(
                                    foreign.tile,
                                    Cancellation.NONE,
                                    (b, t) -> (AutoCloseable) () -> {}));
            written.withWrittenResource(r -> assertEquals(0, closes.get()));
            written.closeAsync().get(3, TimeUnit.SECONDS);
            assertEquals(1, closes.get());
            assertFalse(written.isOpen());
            f.world.closeAsync().get(3, TimeUnit.SECONDS);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            f.water.prepareAndWrite(
                                    foreign.tile,
                                    Cancellation.NONE,
                                    (b, t) -> (AutoCloseable) () -> {}));
            assertThrows(
                    WorldTerrainStreaming.Refusal.class,
                    () ->
                            f.water.prepareAndWrite(
                                    f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {}));
        }
    }

    @Test
    void waterWriterFinalNativeChangeClosesUnadvertisedOutputBeforeHandoff() throws Exception {
        try (var f = new PublicFixture(42)) {
            var closes = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            f.water.prepareAndWrite(
                                    f.tile,
                                    Cancellation.NONE,
                                    (b, t) -> {
                                        assertEquals(2, b.facts().tableObservations().size());
                                        for (var observation : b.facts().tableObservations()) {
                                            assertEquals(64, observation.receiptSha256().length());
                                        }
                                        f.authored.changedTable = true;
                                        return (AutoCloseable) closes::incrementAndGet;
                                    }));
            assertEquals(1, closes.get());
            f.authored.changedTable = false;
            try (var recovered =
                    f.water.prepareAndWrite(
                            f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {})) {
                assertEquals(WorldWaterPreview.State.READY, recovered.facts().state());
            }
        }
    }

    @Test
    void waterNoSurfaceCreatesNoWriterAndTwoCreditsIncludeActualWrittenOwners() throws Exception {
        try (var f = new PublicFixture(0)) {
            var a =
                    f.water.prepareAndWrite(
                            f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {});
            var b =
                    f.water.prepareAndWrite(
                            f.tile, Cancellation.NONE, (v, t) -> (AutoCloseable) () -> {});
            int before = f.authored.base.opens.get(2L).get();
            var pressure =
                    assertThrows(
                            WorldTerrainStreaming.Refusal.class,
                            () ->
                                    f.water.prepareAndWrite(
                                            f.tile,
                                            Cancellation.NONE,
                                            (v, t) -> (AutoCloseable) () -> {}));
            assertEquals(WorldTerrainStreaming.Refusal.Reason.RETRY_LATER, pressure.reason());
            assertEquals(before, f.authored.base.opens.get(2L).get());
            a.close();
            b.close();
            f.authored.payload(liquids(1, 0, 0, false));
            try (var noSurface =
                    f.water.prepareAndWrite(
                            f.tile,
                            Cancellation.NONE,
                            (v, t) -> {
                                throw new AssertionError("empty writer");
                            })) {
                assertTrue(noSurface instanceof WorldWaterPreview.NoSurface<?>);
                assertEquals(WorldWaterPreview.State.EMPTY, noSurface.facts().state());
            }
        }
    }

    @Test
    void actualWaterWriterCancellationAndHeldResourceRetirementKeepServiceDrainHonest()
            throws Exception {
        try (var f = new PublicFixture(0)) {
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var canceled = new CompletableFuture<Void>();
            var result = new CompletableFuture<Throwable>();
            var closes = new java.util.concurrent.atomic.AtomicInteger();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            f.water.prepareAndWrite(
                                                    f.tile,
                                                    Cancellation.NONE,
                                                    (b, t) -> {
                                                        try (var registration =
                                                                t.onCancel(
                                                                        () ->
                                                                                canceled.complete(
                                                                                        null))) {
                                                            entered.complete(null);
                                                            release.join();
                                                            return (AutoCloseable)
                                                                    closes::incrementAndGet;
                                                        }
                                                    });
                                            result.complete(
                                                    new AssertionError(
                                                            "closed Source returned water"));
                                        } catch (RuntimeException | Error failure) {
                                            result.complete(failure);
                                        }
                                    });
            try {
                entered.get(3, TimeUnit.SECONDS);
                var drain = f.world.closeAsync();
                canceled.get(3, TimeUnit.SECONDS);
                assertFalse(drain.isDone());
                assertFalse(result.isDone());
                release.complete(null);
                worker.join();
                drain.get(3, TimeUnit.SECONDS);
                assertEquals(1, closes.get());
                assertTrue(
                        result.get(3, TimeUnit.SECONDS)
                                instanceof java.util.concurrent.CancellationException);
            } finally {
                release.complete(null);
                worker.join();
            }
        }
    }

    @Test
    void waterCannotJoinOldOpaqueTerrainToChangedActualRoot() throws Exception {
        try (var f = new PublicFixture(0)) {
            f.authored.changedRoot = true;
            var refused =
                    assertThrows(
                            WorldTerrainStreaming.Refusal.class,
                            () ->
                                    f.water.prepareAndWrite(
                                            f.tile,
                                            Cancellation.NONE,
                                            (b, t) -> {
                                                throw new AssertionError("stale terrain joined");
                                            }));
            assertEquals(WorldTerrainStreaming.Refusal.Reason.SOURCE_INVALIDATED, refused.reason());
            f.authored.changedRoot = false;
            try (var recovered =
                    f.water.prepareAndWrite(
                            f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {})) {
                assertEquals(1, recovered.facts().cells());
            }
        }
    }

    @Test
    void waterCleanupFailureCannotCompleteServiceDrainWhileAnotherPayloadIsBorrowed()
            throws Exception {
        try (var f = new PublicFixture(0)) {
            f.expectedCloseFailure = true;
            var cleanupEntered = new CompletableFuture<Void>();
            f.water.prepareAndWrite(
                    f.tile,
                    Cancellation.NONE,
                    (b, t) ->
                            (AutoCloseable)
                                    () -> {
                                        cleanupEntered.complete(null);
                                        throw new IllegalStateException("actual water R cleanup");
                                    });
            var second =
                    (WorldWaterPreview.WrittenWater<AutoCloseable>)
                            f.water.prepareAndWrite(
                                    f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {});
            var entered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var borrowed =
                    CompletableFuture.runAsync(
                            () ->
                                    second.withPayload(
                                            b -> {
                                                entered.complete(null);
                                                release.join();
                                                assertEquals(4, b.vertices());
                                                return null;
                                            }));
            try {
                entered.get(3, TimeUnit.SECONDS);
                var drain = f.world.closeAsync();
                cleanupEntered.get(3, TimeUnit.SECONDS);
                // Wait causally for first cleanup failure; the held second pin still owns geometry.
                var until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean failed = false;
                while (!failed && System.nanoTime() < until) {
                    try {
                        f.water.prepareAndWrite(
                                f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {});
                    } catch (RuntimeException expected) {
                        failed = !(expected instanceof WorldTerrainStreaming.Refusal);
                    }
                    if (!failed) {
                        Thread.yield();
                    }
                }
                assertTrue(failed);
                assertFalse(drain.isDone());
                release.complete(null);
                borrowed.get(3, TimeUnit.SECONDS);
                assertThrows(
                        java.util.concurrent.ExecutionException.class,
                        () -> drain.get(3, TimeUnit.SECONDS));
            } finally {
                release.complete(null);
            }
        }
    }

    @Test
    void failedFinalWaterGateHoldsSourceAdmissionUntilReturnedResourceActuallyCloses()
            throws Exception {
        try (var f = new PublicFixture(42)) {
            var cleanupEntered = new CompletableFuture<Void>();
            var release = new CompletableFuture<Void>();
            var outcome = new CompletableFuture<Throwable>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            f.water.prepareAndWrite(
                                                    f.tile,
                                                    Cancellation.NONE,
                                                    (b, t) -> {
                                                        f.authored.changedTable = true;
                                                        return (AutoCloseable)
                                                                () -> {
                                                                    cleanupEntered.complete(null);
                                                                    release.join();
                                                                };
                                                    });
                                            outcome.complete(
                                                    new AssertionError("stale writer returned"));
                                        } catch (RuntimeException | Error error) {
                                            outcome.complete(error);
                                        }
                                    });
            try {
                cleanupEntered.get(3, TimeUnit.SECONDS);
                assertFalse(outcome.isDone());
                int before = f.authored.base.opens.get(2L).get();
                var busy =
                        assertThrows(
                                WorldTerrainStreaming.Refusal.class,
                                () -> f.terrain.createScope(MAP, Cancellation.NONE));
                assertEquals("SOURCE_WORKER", busy.site());
                assertEquals(before, f.authored.base.opens.get(2L).get());
                release.complete(null);
                worker.join();
                assertTrue(
                        outcome.get(3, TimeUnit.SECONDS) instanceof WorldTerrainStreaming.Refusal);
                f.authored.changedTable = false;
                try (var retry =
                        f.water.prepareAndWrite(
                                f.tile, Cancellation.NONE, (b, t) -> (AutoCloseable) () -> {})) {
                    assertEquals(1, retry.facts().cells());
                }
            } finally {
                release.complete(null);
                worker.join();
            }
        }
    }

    @Test
    void terrainRetirementAfterWaterCallbackCannotLeakUnreturnedWaterOwner() throws Exception {
        for (boolean cleanupFailure : new boolean[] {false, true}) {
            try (var f = new PublicFixture(0)) {
                if (cleanupFailure) {
                    f.expectedCloseFailure = true;
                    f.terrainCleanup.set(
                            new IllegalStateException("actual terrain cleanup failure"));
                }
                var closes = new java.util.concurrent.atomic.AtomicInteger();
                var failure =
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        f.water.prepareAndWrite(
                                                f.tile,
                                                Cancellation.NONE,
                                                (b, t) -> {
                                                    f.tile.close();
                                                    return (AutoCloseable) closes::incrementAndGet;
                                                }));
                assertEquals(1, closes.get());
                if (cleanupFailure) {
                    assertFalse(failure instanceof WorldTerrainStreaming.Refusal);
                } else {
                    assertTrue(failure instanceof WorldTerrainStreaming.Refusal);
                }
            }
        }
    }
}
