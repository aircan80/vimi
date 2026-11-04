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
        boolean changedTable;
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
}
