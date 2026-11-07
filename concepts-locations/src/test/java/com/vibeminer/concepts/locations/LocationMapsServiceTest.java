package com.vibeminer.concepts.locations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.BuildFiles;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.images.BlpImageTypes;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LocationMapsServiceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, String kind, int array, String target) {}

    private static Field n(String name) {
        return new Field(name, "integer", 1, null);
    }

    private static Field r(String name, String target) {
        return new Field(name, "integer", 1, target);
    }

    private static Field t(String name) {
        return new Field(name, "string", 1, null);
    }

    private static Field f(String name, int array) {
        return new Field(name, "float", array, null);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), null);
    }

    private static MemoryTables.FakeTable table(
            String name, String hash, List<Field> fields, List<Db2Types.Row> rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            for (int i = 0; i < field.array(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.array() == 1 ? field.name() : field.name() + "[" + i + "]",
                                field.kind(),
                                false,
                                null,
                                null));
            }
            var kind =
                    switch (field.kind()) {
                        case "float" -> TableSchema.ColumnKind.FLOAT;
                        case "string" -> TableSchema.ColumnKind.LOCSTRING;
                        default -> TableSchema.ColumnKind.INT;
                    };
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            kind,
                            kind == TableSchema.ColumnKind.LOCSTRING ? null : 32,
                            kind == TableSchema.ColumnKind.LOCSTRING ? null : true,
                            field.array(),
                            field.target() == null
                                    ? null
                                    : new TableSchema.ForeignKey(field.target(), "ID"),
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            true,
                            WowLocale.parse("enUS"),
                            null,
                            null));
        }
        var entry =
                new TableTypes.Entry(
                        Integer.toUnsignedLong(name.hashCode()),
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "fixture", hash),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of(hash),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static MemoryTables.FakeTable assignments(List<Db2Types.Row> rows) {
        return table(
                "UiMapAssignment",
                "C9CC8DFB",
                List.of(
                        f("UiMin", 2),
                        f("UiMax", 2),
                        f("Region", 6),
                        r("UiMapID", "UiMap"),
                        r("AreaID", "AreaTable"),
                        r("MapID", "Map"),
                        n("WMODoodadPlacementID"),
                        n("WMOGroupID")),
                rows);
    }

    private static List<MemoryTables.FakeTable> fixture() {
        return new ArrayList<>(
                List.of(
                        table(
                                "UiMap",
                                "DB51D55F",
                                List.of(
                                        t("Name_lang"),
                                        n("ParentUiMapID"),
                                        n("Type"),
                                        n("System"),
                                        n("VisibilityPlayerConditionID")),
                                List.of(
                                        row(1411, "Authored zone", "1414", "3", "0", "0"),
                                        row(14, "Unrelated same-number map", "0", "3", "0", "0"),
                                        row(1414, "Authored continent", "0", "2", "0", "0"))),
                        assignments(
                                List.of(
                                        row(
                                                500, "0", "0", "1", "1", "100", "-100", "-1000",
                                                "200", "100", "1000", "1411", "14", "1", "0",
                                                "0"))),
                        table(
                                "UiMapXMapArt",
                                "EAE7DA2A",
                                List.of(
                                        r("UiMapID", "UiMap"),
                                        r("UiMapArtID", "UiMapArt"),
                                        n("PhaseID")),
                                List.of(row(1, "1411", "2169", "0"))),
                        table(
                                "UiMapArt",
                                "3AE7D144",
                                List.of(n("UiMapArtStyleID")),
                                List.of(row(2169, "1"))),
                        table(
                                "UiMapArtStyleLayer",
                                "3F597F5A",
                                List.of(
                                        n("UiMapArtStyleID"),
                                        n("LayerIndex"),
                                        n("LayerWidth"),
                                        n("LayerHeight"),
                                        n("TileWidth"),
                                        n("TileHeight")),
                                List.of(row(70, "1", "0", "6", "4", "4", "4"))),
                        tiles(
                                List.of(
                                        row(800, "2169", "0", "0", "0", "1000"),
                                        row(801, "2169", "0", "0", "1", "1001")))));
    }

    private static MemoryTables.FakeTable tiles(List<Db2Types.Row> rows) {
        return table(
                "UiMapArtTile",
                "2DA5B77B",
                List.of(
                        r("UiMapArtID", "UiMapArt"),
                        n("LayerIndex"),
                        n("RowIndex"),
                        n("ColIndex"),
                        r("FileDataID", "FileData")),
                rows);
    }

    private static BuildTables open(List<MemoryTables.FakeTable> fixture) {
        return new MemoryTables()
                .add(BUILD, "enUS", fixture)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    private static void replace(
            List<MemoryTables.FakeTable> fixture, MemoryTables.FakeTable value) {
        fixture.removeIf(t -> t.entry().name().equals(value.entry().name()));
        fixture.add(value);
    }

    private static LocationTypes.Location area() {
        return LocationAdapters.questieLocations(
                        LocationAdaptersTest.NPC,
                        Map.of("spawns", Map.of("14", List.of(List.of(25, 75)))),
                        LocationAdaptersTest.QUESTIE)
                .locations()
                .getFirst();
    }

    private static LocationTypes.Location world() {
        return LocationAdapters.polestarLocations(
                        LocationAdaptersTest.NPC,
                        List.of(
                                Map.of(
                                        "kind", "npc", "id", 42, "map", 1, "n", 1, "x", 175, "y",
                                        -50)),
                        LocationAdaptersTest.POLESTAR)
                .locations()
                .getFirst();
    }

    private static byte[] blp(int width, int height) {
        var bytes = ByteBuffer.allocate(148 + width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("BLP2".getBytes(StandardCharsets.US_ASCII))
                .putInt(1)
                .put((byte) 3)
                .put((byte) 8)
                .put((byte) 0)
                .put((byte) 1)
                .putInt(width)
                .putInt(height);
        bytes.putInt(20, 148);
        bytes.putInt(84, width * height * 4);
        for (int i = 148; i < bytes.capacity(); i += 4) {
            bytes.put(i, (byte) 0x44);
            bytes.put(i + 1, (byte) 0x88);
            bytes.put(i + 2, (byte) 0x22);
            bytes.put(i + 3, (byte) 0xff);
        }
        return bytes.array();
    }

    private static BuildFiles files(byte[] bytes) {
        var file =
                new FileTypes.BuildFile(
                        1000,
                        null,
                        FileTypes.FilePathSource.NONE,
                        (long) bytes.length,
                        "d".repeat(32),
                        FileTypes.FileFormat.BLP,
                        FileTypes.FileFormatSource.MAGIC,
                        FileTypes.FileStorage.STORED,
                        false);
        return new MemoryFiles()
                .add(BUILD, "enUS", file, bytes, List.of())
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .files();
    }

    @Test
    void areaPercentResolvesThroughDeclaredAssignmentRatherThanEqualNumericMapId() {
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var result = service.resolve(area(), Cancellation.NONE).join();
            assertEquals(MapTypes.State.READY, result.state());
            assertEquals(1, result.candidates().size());
            var map = result.candidates().getFirst();
            assertEquals(1411, map.map().id());
            assertEquals(.25, map.x());
            assertEquals(.75, map.y());
            assertEquals(500L, map.assignmentId());
            assertTrue(
                    map.sources().stream()
                            .anyMatch(s -> s.column().equals("AreaID") && s.value().equals("14")));
        }
    }

    @Test
    void batchResolutionMatchesIndividualResolutionInOrder() {
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var batch = service.resolveAll(List.of(world(), area()), Cancellation.NONE).join();
            assertEquals(2, batch.size());
            assertEquals(service.resolve(world(), Cancellation.NONE).join(), batch.get(0));
            assertEquals(service.resolve(area(), Cancellation.NONE).join(), batch.get(1));
            assertTrue(service.resolveAll(List.of(), Cancellation.NONE).join().isEmpty());
            var tooMany = java.util.Collections.nCopies(LocationMapsService.MAX_BATCH + 1, area());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.resolveAll(tooMany, Cancellation.NONE));
            BuildLocationMaps fallback =
                    new BuildLocationMaps() {
                        public java.util.concurrent.CompletableFuture<MapTypes.Resolution> resolve(
                                LocationTypes.Location location, Cancellation token) {
                            return service.resolve(location, token);
                        }

                        public java.util.concurrent.CompletableFuture<MapTypes.Artwork> artwork(
                                long uiMapId, Cancellation token) {
                            throw new AssertionError();
                        }

                        public java.util.concurrent.CompletableFuture<MapTypes.TileImage> tile(
                                long uiMapId, long tileRowId, Cancellation token) {
                            throw new AssertionError();
                        }

                        public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                    };
            assertEquals(
                    batch, fallback.resolveAll(List.of(world(), area()), Cancellation.NONE).join());
        }
    }

    @Test
    void worldProjectionUsesSwappedInvertedAxesAndMatchingMapOnly() {
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var map = service.resolve(world(), Cancellation.NONE).join().candidates().getFirst();
            assertEquals(.75, map.x());
            assertEquals(.25, map.y());
            assertEquals(MapTypes.Basis.WORLD_ASSIGNMENT, map.basis());
            var unrelated =
                    LocationAdapters.polestarLocations(
                                    LocationAdaptersTest.NPC,
                                    List.of(
                                            Map.of(
                                                    "kind", "npc", "id", 42, "map", 0, "n", 1, "x",
                                                    175, "y", -50)),
                                    LocationAdaptersTest.POLESTAR)
                            .locations()
                            .getFirst();
            assertTrue(service.resolve(unrelated, Cancellation.NONE).join().candidates().isEmpty());
        }
    }

    @Test
    void recordedUiMapAlternativeAvoidsDuplicateProjectedMarkerForOneObservation() {
        var source =
                LocationAdapters.egLocations(
                                LocationAdaptersTest.NPC,
                                Map.of(
                                        "positions",
                                        List.of(
                                                Map.of(
                                                        "uiMap",
                                                        1411,
                                                        "mapX",
                                                        .7,
                                                        "mapY",
                                                        .2,
                                                        "instance",
                                                        1,
                                                        "worldX",
                                                        175,
                                                        "worldY",
                                                        -50))),
                                LocationAdaptersTest.EG)
                        .locations()
                        .getFirst();
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var result = service.resolve(source, Cancellation.NONE).join();
            assertEquals(1, result.candidates().size());
            assertEquals(.7, result.candidates().getFirst().x());
            assertEquals(MapTypes.Basis.RECORDED_UI_MAP, result.candidates().getFirst().basis());
        }
    }

    @Test
    void partialUiRectangleOrMissingWmoContextKeepsAssociationWithoutGuessingMarker() {
        var fixture = fixture();
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        500, ".1", "0", ".9", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "1411", "14", "1", "0", "0"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.resolve(area(), Cancellation.NONE).join();
            assertEquals(1411, result.candidates().getFirst().map().id());
            assertNull(result.candidates().getFirst().x());
            assertEquals(MapTypes.State.PARTIAL, result.state());
        }
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        500, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "1411", "14", "1", "7", "0"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            assertNull(
                    service.resolve(area(), Cancellation.NONE).join().candidates().getFirst().x());
            assertTrue(service.resolve(world(), Cancellation.NONE).join().candidates().isEmpty());
        }
    }

    @Test
    void duplicateAssignmentsRemainExplicitInsteadOfChoosingAnArbitraryFirstRow() {
        var fixture = fixture();
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        500, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "1411", "14", "1", "0", "0"),
                                row(
                                        501, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "1411", "14", "1", "0", "0"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.resolve(area(), Cancellation.NONE).join();
            assertEquals(2, result.candidates().size());
            assertEquals(MapTypes.State.PARTIAL, result.state());
        }
    }

    @Test
    void phaseZeroArtworkProvidesDeclaredTileGridAndCroppedEdgeDimensions() {
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var result = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.READY, result.state());
            assertEquals(2169L, result.artId());
            assertEquals(6, result.layer().width());
            assertEquals(2, result.tiles().size());
            assertEquals(4, result.tiles().get(1).x());
            assertEquals(2, result.tiles().get(1).visibleWidth());
            assertEquals(
                    MapTypes.State.UNAVAILABLE,
                    service.tile(1411, 800, Cancellation.NONE).join().state());
        }
    }

    @Test
    void absentOrAmbiguousPhaseZeroDoesNotFallBackToAnotherPhase() {
        var fixture = fixture();
        replace(
                fixture,
                table(
                        "UiMapXMapArt",
                        "EAE7DA2A",
                        List.of(r("UiMapID", "UiMap"), r("UiMapArtID", "UiMapArt"), n("PhaseID")),
                        List.of(row(1, "1411", "2169", "7"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.UNAVAILABLE, result.state());
            assertNull(result.artId());
            assertEquals(7, result.links().getFirst().phaseId());
        }
        replace(
                fixture,
                table(
                        "UiMapXMapArt",
                        "EAE7DA2A",
                        List.of(r("UiMapID", "UiMap"), r("UiMapArtID", "UiMapArt"), n("PhaseID")),
                        List.of(row(1, "1411", "2169", "0"), row(2, "1411", "2170", "0"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            assertEquals(
                    MapTypes.State.UNAVAILABLE,
                    service.artwork(1411, Cancellation.NONE).join().state());
        }
    }

    @Test
    void missingAndConflictingTileCellsRetainOtherReadableTiles() {
        var fixture = fixture();
        replace(fixture, tiles(List.of(row(800, "2169", "0", "0", "0", "1000"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.PARTIAL, result.state());
            assertEquals(1, result.tiles().size());
        }
        replace(
                fixture,
                tiles(
                        List.of(
                                row(800, "2169", "0", "0", "0", "1000"),
                                row(801, "2169", "0", "0", "1", "1001"),
                                row(802, "2169", "0", "0", "0", "1002"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(1, result.tiles().size());
            assertEquals(801, result.tiles().getFirst().rowId());
            assertEquals(MapTypes.State.PARTIAL, result.state());
        }
    }

    @Test
    void unsupportedOptionalTileLayoutKeepsKnownMapArtAndLayer() {
        var fixture = fixture();
        replace(
                fixture,
                table("UiMapArtTile", "DEADBEEF", List.of(r("UiMapArtID", "UiMapArt")), List.of()));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.UNSUPPORTED, result.state());
            assertEquals(2169L, result.artId());
            assertEquals(6, result.layer().width());
        }
    }

    @Test
    void readablePartialTableIsAcceptedAndMetadataDoesNotReadImagePayloads() {
        var fixture = fixture();
        var original =
                fixture.stream()
                        .filter(t -> t.entry().name().equals("UiMapArtTile"))
                        .findFirst()
                        .orElseThrow();
        var e = original.entry();
        var partial =
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(3, 2, 1, 1, "fixture", "2DA5B77B"),
                        e.contentKey());
        replace(
                fixture,
                new MemoryTables.FakeTable(
                        partial,
                        original.columns(),
                        original.rows(),
                        original.schema(),
                        Map.of(),
                        true));
        var reads = new AtomicInteger();
        var closes = new AtomicInteger();
        try (var tables = open(fixture);
                var files = files(blp(4, 4))) {
            var borrowed =
                    (BuildFiles)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    BuildFiles.class.getClassLoader(),
                                    new Class<?>[] {BuildFiles.class},
                                    (proxy, method, args) -> {
                                        if (List.of(
                                                        "get",
                                                        "readRange",
                                                        "openRead",
                                                        "stat",
                                                        "statContent")
                                                .contains(method.getName())) {
                                            reads.incrementAndGet();
                                        }
                                        if (method.getName().equals("close")) {
                                            closes.incrementAndGet();
                                        }
                                        try {
                                            return method.invoke(files, args);
                                        } catch (
                                                java.lang.reflect.InvocationTargetException
                                                        failure) {
                                            throw failure.getCause();
                                        }
                                    });
            var service = new LocationMapsService(tables, borrowed, new MemoryGeneratedStore());
            assertEquals(0, reads.get());
            var art = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.PARTIAL, art.state());
            assertEquals(2, art.tiles().size());
            service.resolve(area(), Cancellation.NONE).join();
            assertEquals(0, reads.get());
            service.closeAsync().join();
            assertEquals(0, closes.get());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("UiMap"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void explicitTileReadUsesBoundedBlpDecoderAndPersistentImageCache() {
        try (var tables = open(fixture());
                var files = files(blp(4, 4));
                var service = new LocationMapsService(tables, files, new MemoryGeneratedStore())) {
            var first = service.tile(1411, 800, Cancellation.NONE).join();
            assertEquals(MapTypes.State.READY, first.state());
            assertEquals(4, first.image().image().width());
            assertEquals(16, first.image().image().height() * first.image().image().width());
            var second = service.tile(1411, 800, Cancellation.NONE).join();
            assertEquals(BlpImageTypes.Cache.HIT, second.image().cache());
            assertEquals(
                    MapTypes.State.UNAVAILABLE,
                    service.tile(1411, 999, Cancellation.NONE).join().state());
        }
    }

    @Test
    void tileDimensionsAndLayerBudgetsAreNotGuessedOrSilentlyResized() {
        try (var tables = open(fixture());
                var files = files(blp(2, 2));
                var service = new LocationMapsService(tables, files, new MemoryGeneratedStore())) {
            assertEquals(
                    MapTypes.State.UNSUPPORTED,
                    service.tile(1411, 800, Cancellation.NONE).join().state());
        }
        var fixture = fixture();
        replace(
                fixture,
                table(
                        "UiMapArtStyleLayer",
                        "3F597F5A",
                        List.of(
                                n("UiMapArtStyleID"),
                                n("LayerIndex"),
                                n("LayerWidth"),
                                n("LayerHeight"),
                                n("TileWidth"),
                                n("TileHeight")),
                        List.of(row(70, "1", "0", "8192", "8192", "256", "256"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            assertEquals(
                    MapTypes.State.UNSUPPORTED,
                    service.artwork(1411, Cancellation.NONE).join().state());
        }
    }

    @Test
    void unreadableOptionalNamesAndHeightBoundsDoNotBlockKnownMapsOrXY() {
        var fixture = fixture();
        var maps =
                fixture.stream()
                        .filter(t -> t.entry().name().equals("UiMap"))
                        .findFirst()
                        .orElseThrow();
        var changed = new ArrayList<>(maps.rows());
        var original = changed.removeFirst();
        changed.add(
                new Db2Types.Row(
                        original.id(),
                        false,
                        null,
                        original.values(),
                        List.of(new Db2Types.TextIssue(0, "TEXT_INVALID_UTF8", "FF"))));
        replace(
                fixture,
                new MemoryTables.FakeTable(
                        maps.entry(), maps.columns(), changed, maps.schema(), Map.of(), true));
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        500, "0", "0", "1", "1", "100", "-100", "bad", "200", "100",
                                        "bad", "1411", "14", "1", "0", "0"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var resolved = service.resolve(world(), Cancellation.NONE).join();
            assertEquals(1, resolved.candidates().size());
            assertEquals(.75, resolved.candidates().getFirst().x());
            assertEquals("Map #1411", resolved.candidates().getFirst().map().name());
            var art = service.artwork(1411, Cancellation.NONE).join();
            assertEquals(MapTypes.State.PARTIAL, art.state());
            assertEquals(2, art.tiles().size());
            assertEquals(2169L, art.artId());
        }
    }

    /** Group-scoped rows on map 1 and elsewhere, beside the unscoped fixture row 500. */
    private static List<MemoryTables.FakeTable> groupFixture() {
        var fixture = fixture();
        replace(
                fixture,
                table(
                        "UiMap",
                        "DB51D55F",
                        List.of(
                                t("Name_lang"),
                                n("ParentUiMapID"),
                                n("Type"),
                                n("System"),
                                n("VisibilityPlayerConditionID")),
                        List.of(
                                row(1411, "Authored zone", "1414", "3", "0", "0"),
                                row(14, "Unrelated same-number map", "0", "3", "0", "0"),
                                row(1414, "Authored continent", "0", "2", "0", "0"),
                                row(2001, "Upper hall", "1411", "4", "0", "0"),
                                row(2002, "Lower hall", "2001", "4", "0", "0"))));
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        500, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "1411", "14", "1", "0", "0"),
                                // Group-scoped, same region and area as 500: map 1, group 77.
                                row(
                                        501, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2001", "0", "1", "0", "77"),
                                row(
                                        502, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2002", "0", "1", "0", "78"),
                                // Placement-scoped, recorded but never matched here.
                                row(
                                        503, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2002", "0", "1", "9", "79"),
                                // Missing UiMap record.
                                row(
                                        504, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "3000", "0", "1", "0", "80"),
                                // Another world map.
                                row(
                                        505, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2001", "0", "2", "0", "77"))));
        return fixture;
    }

    @Test
    void groupAssignmentsListOnlyRecordedGroupScopedRowsOfTheRequestedMap() {
        try (var tables = open(groupFixture());
                var service = new LocationMapsService(tables)) {
            var result = service.wmoGroupAssignments(1, Cancellation.NONE).join();
            assertEquals(1, result.worldMapId());
            assertEquals(
                    List.of(501L, 502L, 503L, 504L),
                    result.assignments().stream()
                            .map(MapTypes.WmoGroupAssignment::assignmentId)
                            .toList());
            var upper = result.assignments().getFirst();
            assertEquals(2001, upper.uiMapId());
            assertEquals("Upper hall", upper.map().name());
            assertEquals(1411L, upper.map().parentId());
            assertEquals(4, upper.map().type());
            assertEquals(77, upper.wmoGroupId());
            assertEquals(0, upper.wmoDoodadPlacementId());
            assertTrue(
                    upper.sources().stream()
                            .anyMatch(
                                    s ->
                                            s.column().equals("WMOGroupID")
                                                    && s.value().equals("77")));
            assertEquals(9, result.assignments().get(2).wmoDoodadPlacementId());
            assertNull(result.assignments().get(3).map(), "missing UiMap stays recorded as null");
            assertEquals(
                    List.of(1411L, 2001L),
                    result.parents().stream().map(MapTypes.MapInfo::id).toList());
            assertEquals(0, result.unreadable());
            assertEquals(MapTypes.State.PARTIAL, result.state(), "missing UiMap is reported");
            var other = service.wmoGroupAssignments(2, Cancellation.NONE).join();
            assertEquals(
                    List.of(505L),
                    other.assignments().stream().map(a -> a.assignmentId()).toList());
            var none = service.wmoGroupAssignments(7, Cancellation.NONE).join();
            assertTrue(none.assignments().isEmpty());
            assertEquals(MapTypes.State.READY, none.state());
            assertTrue(none.message().contains("No group-scoped"));
        }
    }

    @Test
    void groupAssignmentQueryLeavesExistingResolutionsIdentical() {
        // Group-scoped rows were already in the table; the new query must not change resolve().
        try (var tables = open(groupFixture());
                var before = new LocationMapsService(tables);
                var after = new LocationMapsService(tables)) {
            var expectedWorld = before.resolve(world(), Cancellation.NONE).join();
            var expectedArea = before.resolve(area(), Cancellation.NONE).join();
            after.wmoGroupAssignments(1, Cancellation.NONE).join();
            assertEquals(expectedWorld, after.resolve(world(), Cancellation.NONE).join());
            assertEquals(expectedArea, after.resolve(area(), Cancellation.NONE).join());
            assertEquals(
                    List.of(500L),
                    expectedWorld.candidates().stream().map(c -> c.assignmentId()).toList(),
                    "world projection still uses only the unscoped row");
            assertEquals(
                    List.of(500L),
                    expectedArea.candidates().stream().map(c -> c.assignmentId()).toList());
        }
        // The unscoped-only filter used by resolve() is unchanged: the plain fixture still yields
        // the same projection, and group rows add no candidate.
        try (var tables = open(fixture());
                var plain = new LocationMapsService(tables);
                var groups = open(groupFixture());
                var scoped = new LocationMapsService(groups)) {
            assertEquals(
                    plain.resolve(world(), Cancellation.NONE).join().candidates(),
                    scoped.resolve(world(), Cancellation.NONE).join().candidates());
            assertEquals(
                    plain.resolve(area(), Cancellation.NONE).join().candidates(),
                    scoped.resolve(area(), Cancellation.NONE).join().candidates());
        }
    }

    @Test
    void unreadableGroupAssignmentFieldsAreCountedNeverMatched() {
        var fixture = groupFixture();
        replace(
                fixture,
                assignments(
                        List.of(
                                row(
                                        510, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2001", "0", "1", "bad", "77"),
                                row(
                                        511, "0", "0", "1", "1", "100", "-100", "-1000", "200",
                                        "100", "1000", "2001", "0", "1", "0", "78"))));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.wmoGroupAssignments(1, Cancellation.NONE).join();
            assertEquals(1, result.unreadable());
            assertEquals(
                    List.of(511L),
                    result.assignments().stream().map(a -> a.assignmentId()).toList());
            assertEquals(MapTypes.State.PARTIAL, result.state());
        }
    }

    @Test
    void groupAssignmentsBeyondTheListLimitAreCountedSeparately() {
        var fixture = groupFixture();
        var rows = new ArrayList<Db2Types.Row>();
        int total = MapTypes.WmoGroupAssignments.MAX_ASSIGNMENTS + 3;
        for (int i = 0; i < total; i++) {
            rows.add(
                    row(
                            10_000 + i,
                            "0",
                            "0",
                            "1",
                            "1",
                            "100",
                            "-100",
                            "-1000",
                            "200",
                            "100",
                            "1000",
                            "2001",
                            "0",
                            "1",
                            "0",
                            String.valueOf(100 + i)));
        }
        replace(fixture, assignments(rows));
        try (var tables = open(fixture);
                var service = new LocationMapsService(tables)) {
            var result = service.wmoGroupAssignments(1, Cancellation.NONE).join();
            assertEquals(MapTypes.WmoGroupAssignments.MAX_ASSIGNMENTS, result.assignments().size());
            assertEquals(3, result.overLimit());
            assertEquals(0, result.unreadable());
        }
    }

    @Test
    void defaultGroupAssignmentQueryIsUnsupported() {
        BuildLocationMaps minimal =
                new BuildLocationMaps() {
                    public java.util.concurrent.CompletableFuture<MapTypes.Resolution> resolve(
                            LocationTypes.Location location, Cancellation token) {
                        throw new AssertionError();
                    }

                    public java.util.concurrent.CompletableFuture<MapTypes.Artwork> artwork(
                            long uiMapId, Cancellation token) {
                        throw new AssertionError();
                    }

                    public java.util.concurrent.CompletableFuture<MapTypes.TileImage> tile(
                            long uiMapId, long tileRowId, Cancellation token) {
                        throw new AssertionError();
                    }

                    public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                };
        var result = minimal.wmoGroupAssignments(1, Cancellation.NONE).join();
        assertEquals(MapTypes.State.UNSUPPORTED, result.state());
        assertTrue(result.assignments().isEmpty());
    }

    @Test
    void cancelledAndClosedMapQueriesDoNotTakeOwnershipOfBorrowedTables() {
        try (var tables = open(fixture())) {
            var service = new LocationMapsService(tables);
            var cancelled = new Cancellation();
            cancelled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.artwork(1411, cancelled).join());
            assertThrows(
                    IllegalArgumentException.class, () -> service.artwork(0, Cancellation.NONE));
            assertEquals(
                    MapTypes.State.READY, service.artwork(1411, Cancellation.NONE).join().state());
            service.closeAsync().join();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.artwork(1411, Cancellation.NONE).join());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("UiMap"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }

    @Test
    void worldFramesInvertTheRecordedProjectionExactly() {
        try (var tables = open(fixture());
                var service = new LocationMapsService(tables)) {
            var candidate =
                    service.resolve(world(), Cancellation.NONE).join().candidates().getFirst();
            var frames = service.worldFrames(1, Cancellation.NONE).join();
            assertEquals(MapTypes.State.READY, frames.state());
            var frame =
                    frames.frames().stream()
                            .filter(f -> f.assignmentId() == candidate.assignmentId())
                            .findFirst()
                            .orElseThrow();
            var back = frame.toWorld(candidate.x(), candidate.y());
            var coordinate = world().coordinates().getFirst();
            assertEquals(coordinate.x(), back[0], 1e-9);
            assertEquals(coordinate.y(), back[1], 1e-9);
            assertTrue(service.worldFrames(999, Cancellation.NONE).join().frames().isEmpty());
        }
    }
}
