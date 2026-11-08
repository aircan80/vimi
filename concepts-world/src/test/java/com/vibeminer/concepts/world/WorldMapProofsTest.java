package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldMapProofsTest {
    @Test
    void actualMapOnlyBindingAndRevalidationNeverOpenWdtOrRetainCaptureGraph() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var binding = source.bind(7, Cancellation.NONE);
            assertEquals(7, binding.mapId());
            assertEquals(1, binding.wdtId());
            assertEquals(WorldMapProofs.Authority.COMPLETE_READY, binding.authority());
            assertEquals(64, binding.identity().length());
            assertEquals(64, binding.sourceStamp().length());
            assertNull(binding.witness());
            assertNull(tables.last, "Actual captured rows retire before detached binding returns");
            assertTrue(source.revalidate(binding, Cancellation.NONE));
            assertNull(tables.last);
            assertTrue(tables.eofProbes.get() >= 2);
            assertEquals(0, files.totalOpens(), "Map-only qualifier never opens WDT/root/MAID");
            assertTrue(files.stats.isEmpty(), "READY Map-only path performs no asset stat");
            for (var constructor : WorldMapProofs.Binding.class.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()));
            }
            for (var field : WorldMapProofs.Binding.class.getDeclaredFields()) {
                assertFalse(field.getType().isArray());
                assertFalse(Collection.class.isAssignableFrom(field.getType()));
                assertFalse(
                        field.getType()
                                .getSimpleName()
                                .matches("CapturedRows|Receipt|SchemaResult|Proof|WorldMapProofs"));
            }
        } finally {
            tables.close();
        }
    }

    @Test
    void changedStampAndStrictEofRemainIndependentFromWdt() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var binding = source.bind(7, Cancellation.NONE);
            tables.stampMode = 1;
            assertFalse(source.revalidate(binding, Cancellation.NONE));
            assertNull(tables.last);
            tables.stampMode = 0;
            tables.eofMode = 1;
            var contradiction =
                    assertThrows(
                            IllegalStateException.class,
                            () -> source.revalidate(binding, Cancellation.NONE));
            assertFalse(contradiction instanceof WorldTerrainStreaming.Refusal);
            assertNull(tables.last);
            assertEquals(0, files.totalOpens());
        } finally {
            tables.close();
        }
    }

    @Test
    void cancellationAndRejectedRowsCloseAllCapturesWithoutCreatingTerrainProof() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var tables = new WorldStreamScopesTest.Tables(files);
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(CancellationException.class, () -> source.bind(7, canceled));
            assertEquals(0, tables.captures.get());
            assertThrows(IllegalStateException.class, () -> source.bind(99, Cancellation.NONE));
            assertNull(tables.last);
            tables.duplicate = true;
            assertThrows(IllegalStateException.class, () -> source.bind(7, Cancellation.NONE));
            assertNull(tables.last);
            assertEquals(0, files.totalOpens());
        } finally {
            tables.close();
        }
    }

    private static final String AUTHORED_WDT = "world/maps/Authored/Authored.wdt";

    private static WorldStreamScopesTest.Tables directoryTables(
            WorldTerrainTextureSourceTest.Files files, String layout) {
        var tables = new WorldStreamScopesTest.Tables(files);
        tables.directory = "Authored";
        tables.layout = layout;
        tables.identity = "directory-map-row-" + layout;
        return tables;
    }

    @Test
    void mapLayoutWithoutWdtFieldBindsTheDirectoryWdtPathAndRevalidatesItsResolution() {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.paths.put(AUTHORED_WDT, 1L);
        var tables = directoryTables(files, "B47C6F05");
        try {
            var source = new WorldMapProofs(tables.handle(), files.files());
            var binding = source.bind(7, Cancellation.NONE);
            assertEquals(7, binding.mapId());
            assertEquals(1, binding.wdtId());
            assertEquals(WorldMapProofs.WdtSource.DIRECTORY_PATH, binding.wdtSource());
            assertEquals(AUTHORED_WDT, binding.wdtPath());
            assertEquals(WorldMapProofs.Authority.COMPLETE_READY, binding.authority());
            assertNull(tables.last);
            assertTrue(source.revalidate(binding, Cancellation.NONE));
            assertEquals(2, files.pathLookups.get(), "Bind and revalidate each resolve the path");
            assertEquals(0, files.totalOpens(), "Binding never opens the WDT");

            // The build's names now give that path another file: the proof never follows it.
            files.sources.put(5L, files.sources.get(1L));
            files.paths.put(AUTHORED_WDT, 5L);
            assertFalse(source.revalidate(binding, Cancellation.NONE));
            files.paths.remove(AUTHORED_WDT);
            assertFalse(source.revalidate(binding, Cancellation.NONE));
            assertNull(tables.last);
        } finally {
            tables.close();
        }
    }

    @Test
    void numericAndDirectoryBindingsNeverShareAStampOrRevalidateAsEachOther() {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.paths.put(AUTHORED_WDT, 1L);
        var numericTables = new WorldStreamScopesTest.Tables(files);
        var directory = directoryTables(files, "B47C6F05");
        try {
            var numericSource = new WorldMapProofs(numericTables.handle(), files.files());
            var directorySource = new WorldMapProofs(directory.handle(), files.files());
            var numeric = numericSource.bind(7, Cancellation.NONE);
            var path = directorySource.bind(7, Cancellation.NONE);
            assertEquals(WorldMapProofs.WdtSource.MAP_COLUMN, numeric.wdtSource());
            assertNull(numeric.wdtPath());
            assertEquals(numeric.wdtId(), path.wdtId(), "Same map and WDT, different binding");
            assertFalse(numeric.sourceStamp().equals(path.sourceStamp()));
            assertFalse(numeric.identity().equals(path.identity()));
            assertFalse(directorySource.revalidate(numeric, Cancellation.NONE));
            assertFalse(numericSource.revalidate(path, Cancellation.NONE));
            assertEquals(0, files.totalOpens());
        } finally {
            numericTables.close();
            directory.close();
        }
    }

    @Test
    void unreviewedLayoutsAndMissingDirectoryWdtsRefuseWithAStatedReason() {
        var files = new WorldTerrainTextureSourceTest.Files();
        files.paths.put(AUTHORED_WDT, 1L);
        var unreviewed = directoryTables(files, "01020304");
        try {
            var source = new WorldMapProofs(unreviewed.handle(), files.files());
            var refused =
                    assertThrows(
                            WorldTerrainStreaming.Refusal.class,
                            () -> source.bind(7, Cancellation.NONE));
            assertEquals("NUMERIC_WDT", refused.site());
            assertTrue(refused.detail().orElseThrow().contains("01020304"));
            assertTrue(refused.detail().orElseThrow().contains("not been reviewed"));
            assertEquals(0, files.pathLookups.get(), "An unreviewed layout never resolves a path");
            assertNull(unreviewed.last);
        } finally {
            unreviewed.close();
        }
        files.paths.clear();
        var missing = directoryTables(files, "B47C6F05");
        try {
            var source = new WorldMapProofs(missing.handle(), files.files());
            var refused =
                    assertThrows(
                            WorldTerrainStreaming.Refusal.class,
                            () -> source.bind(7, Cancellation.NONE));
            assertEquals("NUMERIC_WDT", refused.site());
            assertTrue(refused.detail().orElseThrow().contains("no WDT"));
            assertNull(missing.last);
            assertEquals(0, files.totalOpens());
        } finally {
            missing.close();
        }
    }

    @Test
    void directoryBindingAdmitsOnlyAReviewedLayoutWithAPlainVerifiedDirectory() {
        // 70862's Map: an unverified WdtFileDataID admitted by its narrow D43AFAC3 review.
        assertEquals(
                new WorldMapProofs.WdtColumn(WorldMapProofs.WdtSource.MAP_COLUMN, 1),
                WorldMapProofs.wdtColumn(
                        schema("D43AFAC3", directory(TableSchema.ColumnKind.STRING, true), wdt())));
        assertEquals(
                new WorldMapProofs.WdtColumn(WorldMapProofs.WdtSource.DIRECTORY_PATH, 0),
                WorldMapProofs.wdtColumn(
                        schema("B47C6F05", directory(TableSchema.ColumnKind.STRING, true))));
        // A WDT field that is present but not admitted never falls back to the directory.
        assertRefused(
                schema("B47C6F05", directory(TableSchema.ColumnKind.STRING, true), wdt()), false);
        assertRefused(schema("01020304", directory(TableSchema.ColumnKind.STRING, true)), true);
        assertRefused(schema("B47C6F05", directory(TableSchema.ColumnKind.STRING, false)), true);
        assertRefused(schema("B47C6F05", directory(TableSchema.ColumnKind.LOCSTRING, true)), true);
        assertRefused(schema("B47C6F05"), true);
    }

    private static void assertRefused(TableTypes.SchemaResult schema, boolean stated) {
        var refused =
                assertThrows(
                        WorldTerrainStreaming.Refusal.class,
                        () -> WorldMapProofs.wdtColumn(schema));
        assertEquals("NUMERIC_WDT", refused.site());
        assertEquals(stated, refused.detail().isPresent());
    }

    private static TableSchema.SchemaColumn directory(
            TableSchema.ColumnKind kind, boolean verified) {
        return new TableSchema.SchemaColumn(
                "Directory",
                kind,
                null,
                null,
                1,
                null,
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                verified,
                WowLocale.EN_US,
                null,
                null);
    }

    private static TableSchema.SchemaColumn wdt() {
        return new TableSchema.SchemaColumn(
                "WdtFileDataID",
                TableSchema.ColumnKind.INT,
                32,
                false,
                1,
                new TableSchema.ForeignKey("FileData", "ID"),
                TableSchema.KeyStorage.NONE,
                TableSchema.KeyStorage.NONE,
                false,
                WowLocale.EN_US,
                null,
                null);
    }

    private static TableTypes.SchemaResult schema(
            String layout, TableSchema.SchemaColumn... fields) {
        var columns =
                java.util.Arrays.stream(fields)
                        .map(
                                f ->
                                        new Db2Types.Column(
                                                f.name(),
                                                f.kind() == TableSchema.ColumnKind.INT
                                                        ? "integer"
                                                        : "string",
                                                false,
                                                null,
                                                null))
                        .toList();
        return new TableTypes.SchemaResult(
                new TableTypes.Entry(
                        1,
                        "Map",
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(1, 1, 0, 0, "WDC5", layout),
                        null),
                columns,
                new TableSchema(
                        List.of(fields),
                        List.of(layout),
                        TableSchema.SchemaMatch.LAYOUT,
                        WowLocale.EN_US,
                        null));
    }
}
