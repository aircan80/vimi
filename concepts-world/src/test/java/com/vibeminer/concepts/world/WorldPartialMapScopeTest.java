package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.AuthoredPartialNativeMap;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.CapturedRows;
import com.vibeminer.formats.tables.CapturedRowsTypes;
import com.vibeminer.formats.tables.NativeIdDomainTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.world.WorldFormats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class WorldPartialMapScopeTest {
    private static final WorldService.MapInfo REQUEST =
            new WorldService.MapInfo(7, "Forged", "Other", 0, 999L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);

    private static BuildTables wrapped(
            BuildTables actual, int mode, NativeIdDomainTypes.Witness supplied) {
        return (BuildTables)
                java.lang.reflect.Proxy.newProxyInstance(
                        BuildTables.class.getClassLoader(),
                        new Class<?>[] {BuildTables.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("capturePreparedLocal")) {
                                return actual.capturePreparedLocal(
                                                (TableTypes.Ref) args[0], (Cancellation) args[1])
                                        .thenApply(
                                                capture -> {
                                                    var original = capture.rows();
                                                    CapturedRows wrapper =
                                                            new CapturedRows() {
                                                                public CapturedRowsTypes.Receipt
                                                                        receipt() {
                                                                    return original.receipt();
                                                                }

                                                                public java.util.concurrent
                                                                                        .CompletableFuture<
                                                                                CapturedRowsTypes
                                                                                        .Page>
                                                                        readOrdinalPage(
                                                                                long first,
                                                                                int max,
                                                                                Cancellation
                                                                                        token) {
                                                                    return original.readOrdinalPage(
                                                                            first, max, token);
                                                                }

                                                                public java.util.concurrent
                                                                                        .CompletableFuture<
                                                                                CapturedRowsTypes
                                                                                        .Page>
                                                                        readOrdinalPage(
                                                                                long first,
                                                                                int max,
                                                                                CapturedRowsTypes
                                                                                                .ReadLimits
                                                                                        limits,
                                                                                Cancellation
                                                                                        token) {
                                                                    return original.readOrdinalPage(
                                                                                    first, max,
                                                                                    limits, token)
                                                                            .thenApply(
                                                                                    page -> {
                                                                                        if (mode
                                                                                                        != 3
                                                                                                || first
                                                                                                        != 1
                                                                                                || page.rows()
                                                                                                        .isEmpty()) {
                                                                                            return page;
                                                                                        }
                                                                                        var row =
                                                                                                page.rows()
                                                                                                        .getFirst();
                                                                                        var data =
                                                                                                row
                                                                                                        .row();
                                                                                        var
                                                                                                duplicated =
                                                                                                        new com
                                                                                                                .vibeminer
                                                                                                                .formats
                                                                                                                .db2
                                                                                                                .Db2Types
                                                                                                                .Row(
                                                                                                                7,
                                                                                                                data
                                                                                                                        .isCopy(),
                                                                                                                data
                                                                                                                        .copySourceId(),
                                                                                                                data
                                                                                                                        .values(),
                                                                                                                data
                                                                                                                        .textIssues());
                                                                                        return new CapturedRowsTypes
                                                                                                .Page(
                                                                                                page
                                                                                                        .identity(),
                                                                                                first,
                                                                                                page
                                                                                                        .state(),
                                                                                                java
                                                                                                        .util
                                                                                                        .List
                                                                                                        .of(
                                                                                                                new TableTypes
                                                                                                                        .Row(
                                                                                                                        row
                                                                                                                                .ordinal(),
                                                                                                                        duplicated)),
                                                                                                page
                                                                                                        .nextOrdinal(),
                                                                                                page
                                                                                                        .code(),
                                                                                                page
                                                                                                        .message(),
                                                                                                page
                                                                                                        .cellIssues());
                                                                                    });
                                                                }

                                                                public java.util.concurrent
                                                                                        .CompletableFuture<
                                                                                NativeIdDomainTypes
                                                                                        .Result>
                                                                        inspectNativeIdDomain(
                                                                                long id,
                                                                                NativeIdDomainTypes
                                                                                                .SourceAccess
                                                                                        access,
                                                                                Cancellation
                                                                                        token) {
                                                                    if (mode == 0) {
                                                                        return CapturedRows.super
                                                                                .inspectNativeIdDomain(
                                                                                        id, access,
                                                                                        token);
                                                                    }
                                                                    if (supplied != null) {
                                                                        return java.util.concurrent
                                                                                .CompletableFuture
                                                                                .completedFuture(
                                                                                        new NativeIdDomainTypes
                                                                                                .Result(
                                                                                                NativeIdDomainTypes
                                                                                                        .State
                                                                                                        .READABLE_UNIQUE,
                                                                                                supplied));
                                                                    }
                                                                    return original
                                                                            .inspectNativeIdDomain(
                                                                                    id, access,
                                                                                    token);
                                                                }

                                                                public java.util.concurrent
                                                                                        .CompletableFuture<
                                                                                Void>
                                                                        closeAsync() {
                                                                    return original.closeAsync();
                                                                }
                                                            };
                                                    return new CapturedRowsTypes.Capture(
                                                            capture.state(),
                                                            wrapper,
                                                            capture.code(),
                                                            capture.message());
                                                });
                            }
                            try {
                                return method.invoke(actual, args);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    @Test
    void missingCapabilityWrongOrdinalAndForeignWitnessCannotPromoteActualPartialRows()
            throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var files = new WorldTerrainTextureSourceTest.Files();
            try (var good = new AuthoredPartialNativeMap(files.files());
                    var selected = new AuthoredPartialNativeMap(files.files(), mode != 2, false)) {
                NativeIdDomainTypes.Witness witness = null;
                if (mode != 0) {
                    try (var rows =
                            good.tables()
                                    .capturePreparedLocal(
                                            TableTypes.Ref.forName("Map"), Cancellation.NONE)
                                    .join()
                                    .rows()) {
                        witness =
                                rows.inspectNativeIdDomain(
                                                7,
                                                NativeIdDomainTypes.SourceAccess.CONFIGURED_SOURCE,
                                                Cancellation.NONE)
                                        .join()
                                        .witness();
                    }
                    if (mode == 1) {
                        // Negative mutation only, after a genuine private native proof was
                        // produced.
                        var ordinal = witness.getClass().getDeclaredField("ordinal");
                        ordinal.setAccessible(true);
                        ordinal.setLong(witness, 1);
                    }
                }
                var scopes =
                        new WorldStreamScopes(
                                wrapped(selected.tables(), mode, witness), selected.files());
                try {
                    assertThrows(
                            UnsupportedOperationException.class,
                            () -> scopes.create(REQUEST, Cancellation.NONE));
                    assertEquals(0, files.totalOpens());
                    assertEquals(0, selected.opens.get());
                } finally {
                    scopes.closeAsync().join();
                }
            }
        }
    }

    @Test
    void duplicateVisibleSelectedIdRefusesBeforeAnyNativeInspectionOrWorldRead() {
        var files = new WorldTerrainTextureSourceTest.Files();
        try (var fixture = new AuthoredPartialNativeMap(files.files(), true, false, 2)) {
            var scopes = new WorldStreamScopes(wrapped(fixture.tables(), 3, null), fixture.files());
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> scopes.create(REQUEST, Cancellation.NONE));
                assertEquals(0, fixture.opens.get());
                assertEquals(0, files.totalOpens());
            } finally {
                scopes.closeAsync().join();
            }
        }
    }

    @Test
    void actualPartialNativeWitnessAdmitsOnlyObservedSelectedMapAndReusesStampedProof() {
        var files = new WorldTerrainTextureSourceTest.Files();
        try (var fixture = new AuthoredPartialNativeMap(files.files())) {
            var scopes = new WorldStreamScopes(fixture.tables(), fixture.files());
            var cache = new WorldGeometryCache();
            try {
                var scope = scopes.create(REQUEST, Cancellation.NONE);
                assertEquals(1, scope.wdtId());
                assertEquals(
                        1,
                        fixture.opens.get(),
                        "One actual ID-domain inspection establishes initial proof");
                assertEquals(TILE, scopes.recorded(scope, TILE, Cancellation.NONE));
                assertEquals(
                        1,
                        fixture.opens.get(),
                        "Fresh stamp+EOF+Map stat reuses unchanged native ID facts");
                var geometry =
                        scopes.withRecordedTile(
                                scope,
                                TILE,
                                WorldStreamScopes.UseProfile.TERRAIN_BASIC,
                                Cancellation.NONE,
                                cache::prepare);
                assertNotNull(geometry.fingerprint());
                geometry.close();
                fixture.contentKey.set("f".repeat(32));
                assertThrows(
                        IllegalStateException.class,
                        () -> scopes.recorded(scope, TILE, Cancellation.NONE));
            } finally {
                cache.closeAsync().join();
                scopes.closeAsync().join();
            }
        }
    }

    @Test
    void verifiedNativeScalarAndWdtCannotReplaceMissingExcludedIdInventoryOrCopyProfile() {
        for (boolean copy : new boolean[] {false, true}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            try (var fixture = new AuthoredPartialNativeMap(files.files(), copy, copy)) {
                var scopes = new WorldStreamScopes(fixture.tables(), fixture.files());
                try {
                    var capture =
                            fixture.tables()
                                    .capturePreparedLocal(
                                            TableTypes.Ref.forName("Map"), Cancellation.NONE)
                                    .join();
                    try (var rows = capture.rows()) {
                        assertEquals(TableTypes.State.PARTIAL, rows.receipt().table().state());
                    }
                    assertThrows(
                            UnsupportedOperationException.class,
                            () -> scopes.create(REQUEST, Cancellation.NONE));
                    assertEquals(
                            0,
                            files.totalOpens(),
                            "No WDT/root guessed from scalar-only partial data");
                } finally {
                    scopes.closeAsync().join();
                }
            }
        }
    }
}
