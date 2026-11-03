package com.vibeminer.concepts.races;

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

final class RacesServiceTest {
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
                            !name.equals("ChrRaceRacialAbility"),
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
                        new TableTypes.Counts(
                                rows.size(), rows.size(), 0, 0, "fixture", "0FC0F0D5"),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        schema,
                        List.of("0FC0F0D5"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    @Test
    void mediaVariantsFollowRecordedForeignKeysAndKeepReadablePartialJoins() {
        var media =
                List.of(
                        table(
                                101,
                                "ChrRaceXChrModel",
                                List.of(
                                        reference("ChrRacesID", "ChrRaces"),
                                        reference("ChrModelID", "ChrModel")),
                                List.of(row(1, "1", "10"), row(2, "1", "11"), row(3, "2", "12"))),
                        table(
                                102,
                                "ChrModel",
                                List.of(
                                        reference("DisplayID", "CreatureDisplayInfo"),
                                        number("Sex")),
                                List.of(row(10, "20", "1"))),
                        table(
                                103,
                                "CreatureDisplayInfo",
                                List.of(reference("ModelID", "CreatureModelData")),
                                List.of(row(20, "30"))),
                        table(
                                104,
                                "CreatureModelData",
                                List.of(number("FileDataID")),
                                List.of(row(30, "1234"))));
        var tables =
                new MemoryTables()
                        .add(BUILD, "enUS", media)
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables();
        var service = new RacesService(tables);
        try {
            var variants = service.modelVariants(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.PARTIAL, variants.state());
            assertEquals(
                    List.of(new RaceMediaTypes.Variant(10, 1, 20, 30, 1234)), variants.variants());
            assertFalse(variants.issues().isEmpty());
            var unavailable = service.preview(1, 1, 10, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.UNAVAILABLE, unavailable.state());
            assertTrue(unavailable.message().contains("Build files"));
        } finally {
            service.closeAsync().join();
            tables.closeAsync().join();
        }
    }

    private static List<MemoryTables.FakeTable> fixture() {
        return List.of(
                table(
                        1,
                        "ChrRaces",
                        List.of(
                                text("Name_lang"),
                                text("LoreDescription_lang"),
                                number("UiDisplayOrder"),
                                reference("FactionID", "FactionTemplate")),
                        List.of(
                                row(1, "Human", "Human lore", "1", "10"),
                                row(95, "Recorded race", "Other lore", "2", "0"),
                                row(99, "Internal", "", "99", "0"))),
                table(
                        2,
                        "CharBaseInfo",
                        List.of(
                                reference("RaceID", "ChrRaces"),
                                reference("ClassID", "ChrClasses")),
                        List.of(
                                row(1, "1", "1"),
                                row(2, "1", "2"),
                                row(3, "95", "1"),
                                row(4, "1", "1"))),
                table(
                        3,
                        "ChrClasses",
                        List.of(text("Name_lang")),
                        List.of(row(1, "Warrior"), row(2, "Mage"))),
                table(
                        4,
                        "ChrRaceRacialAbility",
                        List.of(
                                text("Name_lang"),
                                text("Description_lang"),
                                number("Icon"),
                                number("Order"),
                                reference("ChrRacesID", "ChrRaces")),
                        List.of(
                                row(
                                        101,
                                        "Diplomacy",
                                        "Reputation gains increased.",
                                        "132328",
                                        "0",
                                        "1"),
                                row(102, "Other racial", "Readable description.", "0", "1", "95"))),
                table(
                        5,
                        "FactionTemplate",
                        List.of(reference("Faction", "Faction")),
                        List.of(row(10, "20"))),
                table(6, "Faction", List.of(text("Name_lang")), List.of(row(20, "Human faction"))));
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
    void catalogKeepsInternalRecordsSeparateFromRecordedClassCombinations() {
        try (var tables = open(fixture());
                var races = new RacesService(tables)) {
            var result = races.catalog(Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, result.state());
            assertEquals(3, result.races().size());
            assertEquals(2, result.races().getFirst().recordedClasses());
            assertEquals(0, result.races().getLast().recordedClasses());
            var detail = races.overview(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.READY, detail.state());
            assertEquals("Human lore", detail.lore());
            assertEquals("Human faction", detail.faction());
            assertEquals(2, detail.classes().size());
            assertEquals("Diplomacy", detail.abilities().getFirst().name());
            assertEquals(132328L, detail.abilities().getFirst().iconFileDataId());
        }
    }

    @Test
    void partialRowsAndUnresolvedClassNamesRemainUsable() {
        var all = new ArrayList<>(fixture());
        var source = all.get(2);
        var e = source.entry();
        var partial =
                new TableTypes.Entry(
                        e.fileDataId(),
                        e.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(2, 1, 1, 1, "fixture", "0FC0F0D5"),
                        e.contentKey());
        all.set(
                2,
                new MemoryTables.FakeTable(
                        partial,
                        source.columns(),
                        List.of(source.rows().getFirst()),
                        source.schema(),
                        Map.of(),
                        false));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var result = races.overview(1, Cancellation.NONE).join();
            assertEquals(RaceTypes.State.PARTIAL, result.state());
            assertEquals(2, result.classes().size());
            assertTrue(result.classes().stream().anyMatch(item -> item.name().equals("Class #2")));
            assertEquals(1, result.abilities().size());
        }
    }

    @Test
    void missingRacialTableDoesNotDiscardRaceAndClassFacts() {
        var all =
                fixture().stream()
                        .filter(table -> !table.entry().name().equals("ChrRaceRacialAbility"))
                        .toList();
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var result = races.overview(1, Cancellation.NONE).join();
            assertEquals("Human", result.race().name());
            assertEquals(2, result.classes().size());
            assertEquals(RaceTypes.State.PARTIAL, result.state());
            assertTrue(result.abilities().isEmpty());
        }
    }

    @Test
    void missingPartialRaceRowRetainsRecordedRaceIdAndKnownClasses() {
        var all = new ArrayList<>(fixture());
        var source = all.getFirst();
        var entry = source.entry();
        var partial =
                new TableTypes.Entry(
                        entry.fileDataId(),
                        entry.name(),
                        TableTypes.State.PARTIAL,
                        true,
                        null,
                        new TableTypes.Counts(3, 2, 1, 1, "fixture", "0FC0F0D5"),
                        entry.contentKey());
        all.set(
                0,
                new MemoryTables.FakeTable(
                        partial,
                        source.columns(),
                        source.rows().subList(1, 3),
                        source.schema(),
                        Map.of(),
                        false));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals("Race #1", overview.race().name());
            assertEquals(2, overview.classes().size());
            assertEquals(1, overview.abilities().size());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
        }
    }

    @Test
    void unknownUnverifiedRacialLayoutDoesNotGuessNamesOrJoin() {
        var all = new ArrayList<>(fixture());
        var source = all.get(3);
        var schema = source.schema();
        all.set(
                3,
                new MemoryTables.FakeTable(
                        source.entry(),
                        source.columns(),
                        source.rows(),
                        new TableSchema(
                                schema.columns(),
                                List.of("DEADBEEF"),
                                schema.match(),
                                schema.locale(),
                                null),
                        Map.of(),
                        true));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals("Human", overview.race().name());
            assertEquals(2, overview.classes().size());
            assertTrue(overview.abilities().isEmpty());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
        }
    }

    @Test
    void excessiveClassLinksRemainBoundedAndExplicit() {
        var all = new ArrayList<>(fixture());
        var links = new ArrayList<Db2Types.Row>();
        for (int id = 1; id <= 200; id++) {
            links.add(row(id, "1", Integer.toString(id)));
        }
        all.set(
                1,
                table(
                        2,
                        "CharBaseInfo",
                        List.of(
                                reference("RaceID", "ChrRaces"),
                                reference("ClassID", "ChrClasses")),
                        links));
        try (var tables = open(all);
                var races = new RacesService(tables)) {
            var overview = races.overview(1, Cancellation.NONE).join();
            assertEquals(128, overview.classes().size());
            assertEquals(RaceTypes.State.PARTIAL, overview.state());
            assertTrue(
                    overview.issues().stream().anyMatch(issue -> issue.contains("display bounds")));
        }
    }

    @Test
    void borrowedTablesRemainOpenAfterComponentCloseAndCanceledQueriesCanRetry() {
        try (var tables = open(fixture())) {
            var races = new RacesService(tables);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> races.catalog(token).join());
            assertFalse(races.catalog(Cancellation.NONE).join().races().isEmpty());
            races.close();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> races.catalog(Cancellation.NONE).join());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("ChrRaces"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }
}
