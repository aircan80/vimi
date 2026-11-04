package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class WorldStreamScopesTest {
    private static final WorldService.MapInfo REQUEST =
            new WorldService.MapInfo(7, "Forged", "Other", 0, 999L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    @Test
    void derivesNumericWdtOnlyFromQualifiedOwnedMapRowAndRevalidatesEveryRecordedReference() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var scope = source.create(REQUEST, Cancellation.NONE);
            assertEquals(1, scope.wdtId(), "Caller-supplied WDT999 is never opened");
            assertEquals(0, files.opens(999));
            assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
            long[] refs = {2, 0, 0, 0, 0, 3, 0, 0};
            for (int i = 0; i < refs.length; i++) {
                var wrong = refs.clone();
                wrong[i]++;
                var forged =
                        new WorldFormats.Tile(
                                7, 19, wrong[0], wrong[1], wrong[2], wrong[3], wrong[4], wrong[5],
                                wrong[6], wrong[7]);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> source.recorded(scope, forged, Cancellation.NONE));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            source.recorded(
                                    scope,
                                    new WorldFormats.Tile(8, 19, 2, 0, 0, 0, 0, 3, 0, 0),
                                    Cancellation.NONE));
            assertEquals(
                    1,
                    files.totalOpens(),
                    "Reference verification never reads caller root/tex0/objects");
        } finally {
            source.closeAsync().join();
            tables.close();
        }
        assertEquals(0, files.borrowedClosed.get(), "Source handles remain borrowed");
    }

    @Test
    void rejectsUnknownDuplicateMissingUnqualifiedAndPartialRowsBeforeOpeningFiles() {
        for (int mode = 0; mode < 6; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            switch (mode) {
                case 0 -> tables.mapId = 8;
                case 1 -> tables.duplicate = true;
                case 2 -> tables.wdt = "0";
                case 3 -> tables.qualified = false;
                case 4 -> tables.state = TableTypes.State.PARTIAL;
                case 5 -> tables.pageState = TableTypes.PageState.STALE;
                default -> throw new AssertionError();
            }
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void changedMapRowOrWdtKeyInvalidatesEvenAnExternallyRetainedScope() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        try {
            var first = source.create(REQUEST, Cancellation.NONE);
            source.activate(first);
            tables.identity = "changed-row-identity";
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(first, TILE, Cancellation.NONE));
            assertEquals(0, source.retainedProofs());
            int reads = tables.pages.get();
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(first, TILE, Cancellation.NONE));
            assertEquals(reads, tables.pages.get(), "Stale token fails before source access");
            var second = source.create(REQUEST, Cancellation.NONE);
            assertNotEquals(first.digest(), second.digest());
            files.changedId = 1;
            files.changeAfterRead = true;
            assertThrows(
                    IllegalStateException.class,
                    () -> source.recorded(second, TILE, Cancellation.NONE));
            assertEquals(0, source.retainedProofs());
        } finally {
            source.closeAsync().join();
            tables.close();
        }
    }

    @Test
    void stalePublicTokensCannotRetainHeavyProofsAndOnlyCurrentPlusReplacementSurvive() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var old = new ArrayList<WorldStreamScopes.Scope>();
        try {
            var current = source.create(REQUEST, Cancellation.NONE);
            source.activate(current);
            for (int i = 0; i < 40; i++) {
                old.add(source.create(REQUEST, Cancellation.NONE));
                assertEquals(2, source.retainedProofs());
                assertEquals(263_168, source.retainedMaidBytes());
            }
            for (var scope : old.subList(0, old.size() - 1)) {
                assertThrows(
                        IllegalStateException.class,
                        () -> source.recorded(scope, TILE, Cancellation.NONE));
            }
            for (var field : WorldStreamScopes.Scope.class.getDeclaredFields()) {
                assertTrue(
                        field.getType().isPrimitive() || field.getType() == String.class,
                        "A retained token has neither heavy arrays nor source/service closures");
            }
            for (var constructor : WorldStreamScopes.Scope.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
        } finally {
            source.closeAsync().join();
            tables.close();
        }
        assertEquals(0, source.retainedProofs());
        assertThrows(
                IllegalStateException.class,
                () -> source.recorded(old.getLast(), TILE, Cancellation.NONE));
    }

    @Test
    void heldSourceReadDoesNotHoldLifecycleMonitorAndCloseDrainsBeforeCompleting()
            throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new Tables(files);
        tables.held = new CompletableFuture<>();
        var source = new WorldStreamScopes(tables.handle(), files.files());
        var pending =
                CompletableFuture.supplyAsync(() -> source.create(REQUEST, Cancellation.NONE));
        assertTrue(tables.entered.await(2, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> source.create(REQUEST, Cancellation.NONE));
        long start = System.nanoTime();
        var closed = source.closeAsync();
        for (int i = 0; i < 32; i++) {
            assertFalse(source.closeAsync().isDone(), "Repeated close shares pending owner drain");
        }
        assertTrue(
                System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1),
                "Close does not wait for held I/O");
        assertFalse(closed.isDone());
        assertEquals(0, source.retainedProofs());
        tables.held.complete(tables.page());
        assertThrows(java.util.concurrent.CompletionException.class, pending::join);
        closed.get(2, TimeUnit.SECONDS);
        assertEquals(0, files.totalOpens());
        assertEquals(0, files.borrowedClosed.get());
        tables.close();
    }

    @Test
    void unsupportedOrUnknownNativeCaptureNeverFallsBackToGenericPages() {
        for (boolean unsupported : new boolean[] {true, false}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.unsupported = unsupported;
            tables.unknownEvidence = !unsupported;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        RuntimeException.class, () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void invalidWdtIdentityProtectedBytesAndCancellationDoNotLeaveProofs() {
        for (int mode = 0; mode < 4; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            var token = new Cancellation();
            switch (mode) {
                case 0 -> files.protectedId = 1;
                case 1 -> files.wrongId = 1;
                case 2 -> {
                    files.changedId = 1;
                    files.changeAfterRead = true;
                }
                case 3 -> token.cancel();
                default -> throw new AssertionError();
            }
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(RuntimeException.class, () -> source.create(REQUEST, token));
                assertEquals(0, source.retainedProofs());
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void boundedCapabilityRefusalDoesNotFallbackOrTurnIntoMissingTerrain() {
        for (boolean absent : new boolean[] {true, false}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var tables = new Tables(files);
            tables.boundedUnsupported = absent;
            tables.forcedLimit = !absent;
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                assertThrows(
                        RuntimeException.class, () -> source.create(REQUEST, Cancellation.NONE));
                assertEquals(0, files.totalOpens());
                assertEquals(0, source.retainedProofs());
                tables.boundedUnsupported = false;
                tables.forcedLimit = false;
                assertEquals(
                        1,
                        source.create(REQUEST, Cancellation.NONE).wdtId(),
                        "A policy refusal does not poison the native graph or source engine");
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    @Test
    void sourceDownloadMayEnrichStoragePathFormatAndUnknownLengthWithoutChangingIdentity() {
        for (boolean wrongLength : new boolean[] {false, true}) {
            var files =
                    new WorldTerrainTextureSourceTest.Files() {
                        @Override
                        FileTypes.FileStat stat(long id, Cancellation token) {
                            var original = super.stat(id, token);
                            if (original.file() == null) {
                                return original;
                            }
                            var file = original.file();
                            boolean downloaded = opens(id) > 0;
                            return new FileTypes.FileStat(
                                    original.state(),
                                    new FileTypes.BuildFile(
                                            file.fileDataId(),
                                            downloaded ? "world/maps/authored/authored.wdt" : null,
                                            downloaded
                                                    ? FileTypes.FilePathSource.LISTFILE
                                                    : FileTypes.FilePathSource.NONE,
                                            downloaded ? file.size() + (wrongLength ? 1 : 0) : null,
                                            file.contentKey(),
                                            downloaded
                                                    ? FileTypes.FileFormat.WDT
                                                    : FileTypes.FileFormat.UNKNOWN,
                                            downloaded
                                                    ? FileTypes.FileFormatSource.EXTENSION
                                                    : FileTypes.FileFormatSource.NONE,
                                            downloaded
                                                    ? FileTypes.FileStorage.STORED
                                                    : FileTypes.FileStorage.NOT_STORED,
                                            false));
                        }
                    };
            var tables = new Tables(files);
            var source = new WorldStreamScopes(tables.handle(), files.files());
            try {
                if (wrongLength) {
                    assertThrows(
                            IllegalStateException.class,
                            () -> source.create(REQUEST, Cancellation.NONE));
                    assertEquals(0, source.retainedProofs());
                } else {
                    var scope = source.create(REQUEST, Cancellation.NONE);
                    assertEquals(1, scope.wdtId());
                    assertEquals(TILE, source.recorded(scope, TILE, Cancellation.NONE));
                }
            } finally {
                source.closeAsync().join();
                tables.close();
            }
        }
    }

    private static final class Tables {
        final WorldTerrainTextureSourceTest.Files files;
        final AtomicInteger pages = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile long mapId = 7;
        volatile String wdt = "1", identity = "owned-map-row";
        volatile boolean duplicate,
                qualified = true,
                unsupported,
                unknownEvidence,
                boundedUnsupported,
                forcedLimit;
        volatile TableTypes.State state = TableTypes.State.READY;
        volatile TableTypes.PageState pageState = TableTypes.PageState.OK;
        volatile CompletableFuture<CapturedRowsTypes.Page> held;
        CapturedRows last;
        final java.util.Map<String, AuthoredNativeMap> nativeMaps = new java.util.HashMap<>();

        Tables(WorldTerrainTextureSourceTest.Files files) {
            this.files = files;
        }

        CapturedRowsTypes.Page page() {
            var original =
                    last.readOrdinalPage(0, 1, WorldStreamScopes.READ_LIMITS, Cancellation.NONE)
                            .join();
            var row = original.rows().getFirst();
            if (mapId != 7 || !wdt.equals("1")) {
                row =
                        new TableTypes.Row(
                                row.ordinal(),
                                new Db2Types.Row(
                                        mapId,
                                        false,
                                        null,
                                        List.of(Long.toString(mapId), wdt),
                                        row.row().textIssues()));
            }
            return new CapturedRowsTypes.Page(
                    original.identity(),
                    original.firstOrdinal(),
                    pageState == TableTypes.PageState.OK
                            ? original.state()
                            : CapturedRowsTypes.ReadState.INVALIDATED,
                    pageState == TableTypes.PageState.OK
                            ? (duplicate ? List.of(row, row) : List.of(row))
                            : List.of(),
                    null,
                    null,
                    null);
        }

        CapturedRowsTypes.Capture capture(Cancellation token) {
            if (unsupported) {
                return CapturedRowsTypes.Capture.unsupported();
            }
            var fixture =
                    nativeMaps.computeIfAbsent(
                            identity,
                            key -> {
                                try {
                                    return new AuthoredNativeMap(
                                            files.files(),
                                            java.util.HexFormat.of()
                                                    .formatHex(
                                                            java.security.MessageDigest.getInstance(
                                                                            "MD5")
                                                                    .digest(
                                                                            key.getBytes(
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8))));
                                } catch (java.security.NoSuchAlgorithmException impossible) {
                                    throw new AssertionError(impossible);
                                }
                            });
            var actual =
                    fixture.tables()
                            .capturePreparedLocal(TableTypes.Ref.forName("Map"), token)
                            .join();
            assertEquals(CapturedRowsTypes.CaptureState.CAPTURED, actual.state(), actual.message());
            var delegate = actual.rows();
            last = delegate;
            CapturedRows wrapper =
                    new CapturedRows() {
                        public CapturedRowsTypes.Receipt receipt() {
                            var r = delegate.receipt();
                            if (qualified && state == TableTypes.State.READY && !unknownEvidence) {
                                return r;
                            }
                            var entry =
                                    new TableTypes.Entry(
                                            r.table().fileDataId(),
                                            "Map",
                                            state,
                                            true,
                                            null,
                                            r.table().counts(),
                                            r.table().contentKey());
                            var schema =
                                    qualified
                                            ? r.schema()
                                            : new TableSchema(
                                                    List.of(),
                                                    r.schema().layoutHashes(),
                                                    r.schema().match(),
                                                    r.schema().locale(),
                                                    null);
                            return new CapturedRowsTypes.Receipt(
                                    r.build(),
                                    r.locale(),
                                    entry,
                                    schema,
                                    r.columns(),
                                    r.definitionSha256(),
                                    r.blockIdentity(),
                                    r.graphIdentity(),
                                    r.sourceIdentity(),
                                    r.readerOptions(),
                                    r.readerRevision(),
                                    r.rowEncoding(),
                                    r.encodingScope(),
                                    r.rowCount(),
                                    r.header(),
                                    r.completion(),
                                    r.sections(),
                                    r.holeKeys(),
                                    r.effectiveKeys(),
                                    r.retention(),
                                    r.membership(),
                                    unknownEvidence
                                            ? CapturedRowsTypes.IssueChannelEvidence.unknown()
                                            : r.issueChannelEvidence());
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long first, int count, Cancellation cancel) {
                            throw new AssertionError("Scope proof may not use unbounded row API");
                        }

                        public CompletableFuture<CapturedRowsTypes.Page> readOrdinalPage(
                                long first,
                                int count,
                                CapturedRowsTypes.ReadLimits limits,
                                Cancellation cancel) {
                            assertEquals(WorldStreamScopes.READ_LIMITS, limits);
                            if (boundedUnsupported) {
                                return CompletableFuture.failedFuture(
                                        new UnsupportedOperationException(
                                                "No bounded row capability"));
                            }
                            if (forcedLimit) {
                                return CompletableFuture.completedFuture(
                                        new CapturedRowsTypes.Page(
                                                delegate.receipt().graphIdentity(),
                                                first,
                                                CapturedRowsTypes.ReadState.LIMIT_EXCEEDED,
                                                List.of(),
                                                null,
                                                "CAPTURE_READ_LIMIT",
                                                "Authored refusal"));
                            }
                            assertEquals(1, count, "Map proof never requests a wide generic page");
                            pages.incrementAndGet();
                            entered.countDown();
                            return held != null ? held : CompletableFuture.completedFuture(page());
                        }

                        public CompletableFuture<Void> closeAsync() {
                            last = null;
                            return delegate.closeAsync();
                        }
                    };
            return new CapturedRowsTypes.Capture(
                    CapturedRowsTypes.CaptureState.CAPTURED, wrapper, null, null);
        }

        void close() {
            for (var fixture : nativeMaps.values()) {
                fixture.close();
            }
            nativeMaps.clear();
        }

        BuildTables handle() {
            return (BuildTables)
                    Proxy.newProxyInstance(
                            BuildTables.class.getClassLoader(),
                            new Class<?>[] {BuildTables.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "build" -> files.files().build();
                                        case "locale" -> "enUS";
                                        case "capturePreparedLocal" ->
                                                CompletableFuture.completedFuture(
                                                        capture((Cancellation) args[1]));
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected borrowed table API "
                                                                + method.getName());
                                    });
        }
    }
}
