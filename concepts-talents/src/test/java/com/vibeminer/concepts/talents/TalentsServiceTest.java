package com.vibeminer.concepts.talents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

final class TalentsServiceTest {
    private static final BuildRef BUILD =
            new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");

    private record Field(String name, boolean text, int count) {}

    private static Field number(String name) {
        return new Field(name, false, 1);
    }

    private static Field text(String name) {
        return new Field(name, true, 1);
    }

    private static Field array(String name, int size) {
        return new Field(name, false, size);
    }

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), List.of());
    }

    private static MemoryTables.FakeTable table(
            long id, String name, List<Field> fields, List<Db2Types.Row> rows) {
        var columns = new ArrayList<Db2Types.Column>();
        var definitions = new ArrayList<TableSchema.SchemaColumn>();
        for (var field : fields) {
            definitions.add(
                    new TableSchema.SchemaColumn(
                            field.name(),
                            field.text()
                                    ? TableSchema.ColumnKind.LOCSTRING
                                    : TableSchema.ColumnKind.INT,
                            field.text() ? null : 32,
                            field.text() ? null : true,
                            field.count(),
                            null,
                            TableSchema.KeyStorage.NONE,
                            TableSchema.KeyStorage.NONE,
                            true,
                            WowLocale.parse("enUS"),
                            null,
                            null));
            for (int i = 0; i < field.count(); i++) {
                columns.add(
                        new Db2Types.Column(
                                field.count() == 1 ? field.name() : field.name() + "[" + i + "]",
                                field.text() ? "string" : "integer",
                                false,
                                null,
                                null));
            }
        }
        var entry =
                new TableTypes.Entry(
                        id,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(
                                rows.size(), rows.size(), 0, 0, "fixture", "12345678"),
                        "c".repeat(32));
        return new MemoryTables.FakeTable(
                entry,
                columns,
                rows,
                new TableSchema(
                        definitions,
                        List.of("12345678"),
                        TableSchema.SchemaMatch.BUILD,
                        WowLocale.parse("enUS"),
                        null),
                Map.of(),
                true);
    }

    private static Db2Types.Row talent(
            long id, int tab, int tier, int col, long spell, long prereq, int rank) {
        var values = new ArrayList<String>(List.of("" + tab, "" + tier, "" + col));
        for (int i = 0; i < 9; i++) {
            values.add(i == 0 ? "" + spell : "0");
        }
        values.addAll(List.of("" + prereq, "0", "0", "" + rank, "0", "0"));
        return new Db2Types.Row(id, false, null, values, List.of());
    }

    private static List<MemoryTables.FakeTable> fixture(List<Db2Types.Row> talents) {
        return List.of(
                table(
                        1,
                        "ChrClasses",
                        List.of(text("Name_lang"), number("IconFileDataID")),
                        List.of(
                                row(1, "Warrior", "100"),
                                row(2, "Paladin", "101"),
                                row(30, "Unused", "0"))),
                table(
                        2,
                        "TalentTab",
                        List.of(
                                text("Name_lang"),
                                number("ClassMask"),
                                number("OrderIndex"),
                                text("BackgroundFile")),
                        List.of(
                                row(10, "Arms", "1", "0", "WarriorArms"),
                                row(11, "Fury", "1", "1", "WarriorFury"),
                                row(12, "Protection", "1", "2", "WarriorProtection"),
                                row(99, "Pet", "0", "0", "Pet"))),
                table(
                        3,
                        "Talent",
                        List.of(
                                number("TabID"),
                                number("TierID"),
                                number("ColumnIndex"),
                                array("SpellRank", 9),
                                array("PrereqTalent", 3),
                                array("PrereqRank", 3)),
                        talents),
                table(
                        4,
                        "SpellName",
                        List.of(text("Name_lang")),
                        List.of(
                                row(1000, "First talent"),
                                row(1001, "Second talent"),
                                row(1002, "Fury talent"),
                                row(1003, "Protection talent"))),
                table(
                        5,
                        "Spell",
                        List.of(text("Description_lang")),
                        List.of(row(1000, "Raw $s1 description"))),
                table(
                        6,
                        "SpellMisc",
                        List.of(
                                number("SpellID"),
                                number("DifficultyID"),
                                number("SpellIconFileDataID")),
                        List.of(
                                row(55, "1000", "0", "900"),
                                row(56, "1001", "0", "901"),
                                row(57, "1002", "0", "902"))));
    }

    private static List<Db2Types.Row> nodes() {
        return List.of(
                talent(1, 10, 0, 1, 1000, 0, 0),
                talent(2, 10, 1, 1, 1001, 1, 0),
                talent(3, 11, 0, 0, 1002, 0, 0),
                talent(4, 12, 0, 3, 1003, 0, 0));
    }

    private static com.vibeminer.formats.tables.BuildTables handle(
            List<MemoryTables.FakeTable> tables) {
        return new MemoryTables()
                .add(BUILD, "enUS", tables)
                .forBuild(BUILD, "enUS", Cancellation.NONE)
                .join()
                .tables();
    }

    @Test
    void projectsThreeTreesAndOnlyClassesWithPlayerTalents() {
        try (var tables = handle(fixture(nodes()));
                var service = new TalentsService(tables, "1.60.1.70000")) {
            var catalog = service.catalog(Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, catalog.state());
            assertEquals(
                    List.of("Warrior"),
                    catalog.classes().stream().map(TalentTypes.PlayerClass::name).toList());
            var result = service.trees(1, Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, result.state());
            assertEquals(
                    List.of("Arms", "Fury", "Protection"),
                    result.trees().stream().map(TalentTypes.Tree::name).toList());
            var first = result.trees().getFirst().talents().getFirst();
            assertEquals("First talent", first.name());
            assertEquals(900L, first.iconFileDataId());
            assertEquals("Raw $s1 description", first.description());
            assertEquals(
                    1,
                    result.trees()
                            .getFirst()
                            .talents()
                            .get(1)
                            .prerequisites()
                            .getFirst()
                            .talentId());
            assertEquals(
                    0,
                    result.trees()
                            .getFirst()
                            .talents()
                            .get(1)
                            .prerequisites()
                            .getFirst()
                            .rankIndex());
        }
    }

    @Test
    void malformedOptionalTextKeepsTheValidatedTrees() {
        var data = new ArrayList<>(fixture(nodes()));
        data.set(3, table(4, "SpellName", List.of(text("Name_lang")), List.of(row(1000))));
        data.set(4, table(5, "Spell", List.of(text("Description_lang")), List.of(row(1000))));
        try (var tables = handle(data);
                var service = new TalentsService(tables, "1.60.1.70000")) {
            var result = service.trees(1, Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, result.state());
            assertEquals("Spell 1000", result.trees().getFirst().talents().getFirst().name());
            assertEquals(null, result.trees().getFirst().talents().getFirst().description());
            assertFalse(result.issues().isEmpty());
        }
    }

    @Test
    void rejectsRetailAndMistsModelsEvenWhenLegacyTablesExist() {
        try (var tables = handle(fixture(nodes()));
                var service = new TalentsService(tables, "5.5.4.70000")) {
            assertEquals(
                    TalentTypes.State.UNSUPPORTED,
                    service.catalog(Cancellation.NONE).join().state());
        }
        assertFalse(TalentsService.classicModel("wow", "1.60.1.70000"));
        assertFalse(TalentsService.classicModel("wowdev", "1.60.1.70000"));
    }

    @Test
    void rejectsDuplicateCoordinatesAndMissingOrCyclicPrerequisites() {
        for (var rows :
                List.of(
                        List.of(
                                talent(1, 10, 0, 1, 1000, 0, 0),
                                talent(2, 10, 0, 1, 1001, 0, 0),
                                talent(3, 11, 0, 0, 1002, 0, 0),
                                talent(4, 12, 0, 0, 1003, 0, 0)),
                        List.of(
                                talent(1, 10, 0, 1, 1000, 9, 0),
                                talent(3, 11, 0, 0, 1002, 0, 0),
                                talent(4, 12, 0, 0, 1003, 0, 0)),
                        List.of(
                                talent(1, 10, 0, 1, 1000, 2, 0),
                                talent(2, 10, 1, 1, 1001, 1, 0),
                                talent(3, 11, 0, 0, 1002, 0, 0),
                                talent(4, 12, 0, 0, 1003, 0, 0)))) {
            try (var tables = handle(fixture(rows));
                    var service = new TalentsService(tables, "1.60.1.70000")) {
                assertEquals(
                        TalentTypes.State.INVALID,
                        service.trees(1, Cancellation.NONE).join().state());
            }
        }
    }

    @Test
    void refusesMissingThirdTreeRatherThanFabricatingAnEmptyTree() {
        try (var tables = handle(fixture(nodes().subList(0, 3)));
                var service = new TalentsService(tables, "2.5.6.70000")) {
            assertEquals(
                    TalentTypes.State.UNSUPPORTED,
                    service.trees(1, Cancellation.NONE).join().state());
        }
    }

    @Test
    void closeDoesNotCloseBorrowedTablesOrCancelCallerToken() {
        var caller = new Cancellation();
        try (var tables = handle(fixture(nodes()))) {
            var service = new TalentsService(tables, "1.60.1.70000");
            service.catalog(caller).join();
            service.close();
            assertFalse(caller.isCanceled());
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("Talent"),
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
    void encryptedAndUnverifiedSchemasRemainExplicitFailures() {
        var rows = fixture(nodes());
        var encrypted = new ArrayList<>(rows);
        var source = rows.get(2);
        var entry =
                new TableTypes.Entry(
                        source.entry().fileDataId(),
                        source.entry().name(),
                        TableTypes.State.ENCRYPTED,
                        true,
                        null,
                        source.entry().counts(),
                        source.entry().contentKey());
        encrypted.set(
                2,
                new MemoryTables.FakeTable(
                        entry, source.columns(), source.rows(), source.schema(), Map.of(), true));
        try (var tables = handle(encrypted);
                var service = new TalentsService(tables, "1.60.1.70000")) {
            var result = service.catalog(Cancellation.NONE).join();
            assertEquals(TalentTypes.State.UNAVAILABLE, result.state());
            assertEquals("TALENTS_TABLE_ENCRYPTED", result.code());
        }
        var invalid = new ArrayList<>(rows);
        var fields = new ArrayList<>(source.schema().columns());
        var field = fields.getFirst();
        fields.set(
                0,
                new TableSchema.SchemaColumn(
                        field.name(),
                        field.kind(),
                        field.bits(),
                        field.unsigned(),
                        field.arrayLength(),
                        field.foreignKey(),
                        field.id(),
                        field.relation(),
                        false,
                        field.locale(),
                        null,
                        null));
        invalid.set(
                2,
                new MemoryTables.FakeTable(
                        source.entry(),
                        source.columns(),
                        source.rows(),
                        new TableSchema(
                                fields,
                                source.schema().layoutHashes(),
                                source.schema().match(),
                                source.schema().locale(),
                                null),
                        Map.of(),
                        true));
        try (var tables = handle(invalid);
                var service = new TalentsService(tables, "1.60.1.70000")) {
            var result = service.catalog(Cancellation.NONE).join();
            assertEquals(TalentTypes.State.UNSUPPORTED, result.state());
            assertEquals("TALENTS_SCHEMA_UNSUPPORTED", result.code());
        }
    }

    @Test
    void ambiguousIconReferencesDoNotChooseAnArbitraryImage() {
        var data = new ArrayList<>(fixture(nodes()));
        var source = data.get(5);
        var rows = new ArrayList<>(source.rows());
        rows.add(row(99, "1000", "0", "999"));
        data.set(
                5,
                new MemoryTables.FakeTable(
                        source.entry(), source.columns(), rows, source.schema(), Map.of(), true));
        try (var tables = handle(data);
                var service = new TalentsService(tables, "1.60.1.70000")) {
            var result = service.trees(1, Cancellation.NONE).join();
            assertEquals(TalentTypes.State.READY, result.state());
            assertEquals(null, result.trees().getFirst().talents().getFirst().iconFileDataId());
            assertEquals(
                    true, result.issues().stream().anyMatch(issue -> issue.contains("Ambiguous")));
        }
    }

    @Test
    void completionCallbackMayCloseAndCanceledRequestDoesNotPoisonFollowingQueries()
            throws Exception {
        try (var tables = handle(fixture(nodes()))) {
            var service = new TalentsService(tables, "1.60.1.70000");
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.catalog(canceled).join());
            service.catalog(Cancellation.NONE)
                    .thenRun(() -> service.closeAsync().join())
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("Talent"),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        }
    }
}
