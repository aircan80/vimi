package com.vibeminer.concepts.dungeons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.definitions.WowLocale;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DungeonsServiceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, boolean text, String target) {}

    private static Field number(String name) {
        return new Field(name, false, null);
    }

    private static Field text(String name) {
        return new Field(name, true, null);
    }

    private static Field reference(String name, String target) {
        return new Field(name, false, target);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        return table(id, name, fields, rows, "0FC0F0D5");
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows, String layout) {
        var columns = new ArrayList<Db2Types.Column>();
        var schema = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            columns.add(
                    new Db2Types.Column(
                            field.name(), field.text() ? "string" : "integer", false, null, null));
            schema.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.text()
                                    ? TableSchema.ColumnKind.LOCSTRING
                                    : TableSchema.ColumnKind.INT,
                            field.text() ? null : 32,
                            field.text() ? null : true,
                            1,
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
                        id,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "fixture", layout),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of(layout),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static MemoryTables.FakeTable maps() {
        return table(
                1,
                "Map",
                List.of(
                        text("MapName_lang"),
                        number("InstanceType"),
                        number("MaxPlayers"),
                        reference("AreaTableID", "AreaTable"),
                        text("MapDescription0_lang"),
                        text("MapDescription1_lang")),
                List.of(
                        row(0, "World", "0", "0", "0", "", ""),
                        row(
                                33,
                                "Authored Keep",
                                "1",
                                "5",
                                "100",
                                "Horde briefing",
                                "Alliance briefing"),
                        row(44, "Authored Citadel", "2", "40", "101", "", ""),
                        row(55, "Arena", "4", "10", "0", "", "")));
    }

    private static com.vibeminer.formats.tables.BuildTables open(
            List<MemoryTables.FakeTable> tables) {
        return new MemoryTables()
                .add(BUILD, "enUS", tables)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void catalogueIncludesPartyAndRaidMapsOnlyAndPreservesMapIdentity() {
        try (var tables = open(List.of(maps()));
                var service = new DungeonsService(tables)) {
            var result = service.catalog(Cancellation.NONE).join();
            assertEquals(
                    List.of(44L, 33L),
                    result.dungeons().stream().map(DungeonTypes.Dungeon::mapId).toList());
            assertEquals(DungeonTypes.Kind.RAID, result.dungeons().getFirst().kind());
            assertEquals(5L, result.dungeons().getLast().maxPlayers());
            assertEquals(
                    DungeonTypes.State.UNAVAILABLE,
                    service.overview(0, Cancellation.NONE).join().state());
        }
    }

    @Test
    void difficultyCapacitiesRemainDistinctAndQueueCompositionNeverBecomesMapCapacity() {
        var difficulty =
                table(
                        2,
                        "Difficulty",
                        List.of(text("Name_lang")),
                        List.of(row(1, "Normal"), row(2, "Heroic")));
        var variants =
                table(
                        3,
                        "MapDifficulty",
                        List.of(
                                reference("MapID", "Map"),
                                reference("DifficultyID", "Difficulty"),
                                number("MaxPlayers")),
                        List.of(row(10, "33", "1", "5"), row(11, "33", "2", "10")));
        var queues =
                table(
                        4,
                        "LFGDungeons",
                        List.of(
                                text("Name_lang"),
                                reference("MapID", "Map"),
                                reference("DifficultyID", "Difficulty"),
                                number("MinLevel"),
                                number("MaxLevel"),
                                number("Count_tank"),
                                number("Count_healer"),
                                number("Count_damage")),
                        List.of(
                                row(
                                        50,
                                        "Authored queue",
                                        "33",
                                        "1",
                                        "15",
                                        "25",
                                        "99",
                                        "99",
                                        "99")));
        try (var tables = open(List.of(maps(), difficulty, variants, queues));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertEquals(5L, result.dungeon().maxPlayers());
            assertEquals(
                    List.of(5L, 10L),
                    result.difficulties().stream()
                            .map(DungeonTypes.Difficulty::maxPlayers)
                            .toList());
            assertEquals("Normal", result.difficulties().getFirst().name());
            assertEquals(15L, result.queues().getFirst().minLevel());
            assertEquals(25L, result.queues().getFirst().maxLevel());
        }
    }

    @Test
    void encountersFollowExactMapReferencesAndKeepDifficultyAndOrdering() {
        var encounters =
                table(
                        2,
                        "DungeonEncounter",
                        List.of(
                                text("Name_lang"),
                                reference("MapID", "Map"),
                                reference("DifficultyID", "Difficulty"),
                                number("OrderIndex"),
                                reference("CreatureDisplayID", "CreatureDisplayInfo")),
                        List.of(
                                row(21, "Gatekeeper", "33", "1", "2", "1001"),
                                row(20, "Sentinel", "33", "1", "1", "1002"),
                                row(22, "Sentinel", "33", "2", "1", "1002"),
                                row(23, "Other map", "44", "1", "0", "1003")));
        try (var tables = open(List.of(maps(), encounters));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertEquals(
                    List.of(20L, 21L, 22L),
                    result.encounters().stream().map(DungeonTypes.Encounter::id).toList());
            assertEquals(1002L, result.encounters().getFirst().displayId());
            assertEquals(3, result.encounters().size());
        }
    }

    @Test
    void associatedAreaHierarchyIsVerifiedAndCyclesStayBounded() {
        var areas =
                table(
                        2,
                        "AreaTable",
                        List.of(
                                text("AreaName_lang"),
                                reference("ParentAreaID", "AreaTable"),
                                reference("ContinentID", "Map")),
                        List.of(
                                row(100, "Keep interior", "200", "33"),
                                row(200, "Recorded parent", "100", "0")));
        try (var tables = open(List.of(maps(), areas));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertEquals(
                    List.of(200L, 100L),
                    result.areas().stream().map(DungeonTypes.Area::id).toList());
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("cyclic")));
            assertEquals(33L, result.areas().getLast().mapId());
        }
    }

    @Test
    void readablePartialMapsAndEncountersSurviveMissingJournalAndAreaData() {
        var encounters =
                partial(
                        table(
                                2,
                                "DungeonEncounter",
                                List.of(text("Name_lang"), reference("MapID", "Map")),
                                List.of(row(20, "Sentinel", "33"))));
        try (var tables = open(List.of(partial(maps()), encounters));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertEquals(DungeonTypes.State.PARTIAL, result.state());
            assertEquals(1, result.encounters().size());
            assertEquals(2, result.descriptions().size());
            assertFalse(result.sources().isEmpty());
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("partial")));
        }
    }

    @Test
    void wrongEncounterForeignKeyDoesNotHideDungeonOrInventEncounters() {
        var wrong =
                table(
                        2,
                        "DungeonEncounter",
                        List.of(text("Name_lang"), reference("MapID", "AreaTable")),
                        List.of(row(20, "Wrong relation", "33")));
        try (var tables = open(List.of(maps(), wrong));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertTrue(result.encounters().isEmpty());
            assertEquals("Authored Keep", result.dungeon().name());
        }
    }

    @Test
    void closingDrainsRequestsWithoutClosingBorrowedTablesAndCallbacksCanClose() throws Exception {
        try (var tables = open(List.of(maps()))) {
            var service = new DungeonsService(tables);
            service.catalog(Cancellation.NONE)
                    .thenRun(() -> service.closeAsync().join())
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("Map"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> service.catalog(Cancellation.NONE).join());
        }
    }

    @Test
    void cancellationAndUnsignedMapBoundsRemainExplicit() {
        try (var tables = open(List.of(maps()));
                var service = new DungeonsService(tables)) {
            assertThrows(
                    IllegalArgumentException.class, () -> service.overview(-1, Cancellation.NONE));
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.catalog(token).join());
        }
    }

    @Test
    void overviewBoundsKeepLargeSourceGroupsFromCreatingUnboundedPanels() {
        var rows = new ArrayList<Db2Types.Row>();
        for (int i = 0; i < 300; i++) {
            rows.add(row(i + 1, "33", "1", "5"));
        }
        var variants =
                table(
                        2,
                        "MapDifficulty",
                        List.of(
                                reference("MapID", "Map"),
                                reference("DifficultyID", "Difficulty"),
                                number("MaxPlayers")),
                        rows);
        try (var tables = open(List.of(maps(), variants));
                var service = new DungeonsService(tables)) {
            var result = service.overview(33, Cancellation.NONE).join();
            assertEquals(256, result.difficulties().size());
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("overview bound")));
            assertEquals("Authored Keep", result.dungeon().name());
        }
    }

    private static MemoryTables.FakeTable partial(MemoryTables.FakeTable original) {
        var e = original.entry();
        var changed =
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        TableTypes.State.PARTIAL,
                        e.checked(),
                        e.reason(),
                        new TableTypes.Counts(
                                e.counts().declaredRows() + 1,
                                e.counts().readableRows(),
                                1,
                                1,
                                "fixture",
                                "0FC0F0D5"),
                        e.contentKey());
        return new MemoryTables.FakeTable(
                changed,
                original.columns(),
                original.rows(),
                original.schema(),
                original.protectedIds(),
                true);
    }
}
