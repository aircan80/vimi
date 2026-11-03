package com.vibeminer.concepts.mounts;

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

final class MountsServiceTest {
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

    @Test
    void catalogUsesMountIdsAndKeepsVariantsAndConditionsInStableOrder() {
        var list =
                List.of(
                        table(
                                1,
                                "Mount",
                                List.of(
                                        text("Name_lang"),
                                        text("Description_lang"),
                                        text("SourceText_lang"),
                                        reference("SourceSpellID", "Spell")),
                                List.of(row(6, "Brown Horse", "A horse", "Vendor", "458"))),
                        table(
                                2,
                                "MountXDisplay",
                                List.of(
                                        reference("MountID", "Mount"),
                                        reference("CreatureDisplayInfoID", "CreatureDisplayInfo"),
                                        reference("PlayerConditionID", "PlayerCondition")),
                                List.of(
                                        row(9, "6", "2405", "42"),
                                        row(1, "6", "2404", "0"),
                                        row(2, "7", "2320", "0"))));
        try (var tables =
                        new MemoryTables()
                                .add(BUILD, "enUS", list)
                                .forBuild(BUILD, "enUS", Cancellation.NONE)
                                .join()
                                .tables();
                var service = new MountsService(tables)) {
            var catalog = service.catalog(Cancellation.NONE).join();
            assertEquals(1, catalog.mounts().size());
            assertEquals(6, catalog.mounts().getFirst().id());
            assertEquals(458L, catalog.mounts().getFirst().sourceSpellId());
            var detail = service.overview(6, Cancellation.NONE).join();
            assertEquals(
                    List.of(1L, 9L),
                    detail.variants().stream().map(MountTypes.Variant::recordId).toList());
            assertEquals(42L, detail.variants().getLast().conditionId());
            assertEquals(
                    MountTypes.State.UNAVAILABLE,
                    service.overview(458, Cancellation.NONE).join().state());
        }
    }

    @Test
    void partialMountAndLinkTablesRetainReadableRows() {
        var mount =
                partial(
                        table(
                                1,
                                "Mount",
                                List.of(text("Name_lang")),
                                List.of(row(6, "Brown Horse"))));
        var links =
                partial(
                        table(
                                2,
                                "MountXDisplay",
                                List.of(
                                        reference("MountID", "Mount"),
                                        reference("CreatureDisplayInfoID", "CreatureDisplayInfo")),
                                List.of(row(1, "6", "2404"))));
        try (var tables =
                        new MemoryTables()
                                .add(BUILD, "enUS", List.of(mount, links))
                                .forBuild(BUILD, "enUS", Cancellation.NONE)
                                .join()
                                .tables();
                var service = new MountsService(tables)) {
            var result = service.overview(6, Cancellation.NONE).join();
            assertEquals(MountTypes.State.PARTIAL, result.state());
            assertEquals(1, result.variants().size());
            assertTrue(result.issues().stream().anyMatch(i -> i.contains("partial")));
        }
    }

    @Test
    void metadataSurvivesUnavailableDisplayTableAndBorrowedTablesRemainOpenAfterClose() {
        try (var tables =
                new MemoryTables()
                        .add(
                                BUILD,
                                "enUS",
                                List.of(
                                        table(
                                                1,
                                                "Mount",
                                                List.of(text("Name_lang")),
                                                List.of(row(6, "Brown Horse")))))
                        .forBuild(BUILD, "enUS", Cancellation.NONE)
                        .join()
                        .tables()) {
            var service = new MountsService(tables);
            var result = service.overview(6, Cancellation.NONE).join();
            assertEquals("Brown Horse", result.mount().name());
            assertTrue(result.variants().isEmpty());
            assertFalse(result.issues().isEmpty());
            service.close();
            assertEquals(
                    TableTypes.State.READY,
                    tables.check(
                                    TableTypes.Ref.forName("Mount"),
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
    void legacyDirectDisplaysRequireExactVerifiedForeignKey() {
        var correct =
                table(
                        1,
                        "Mount",
                        List.of(
                                text("Name_lang"),
                                reference("CreatureDisplayInfoID", "CreatureDisplayInfo")),
                        List.of(row(6, "Horse", "2404")));
        try (var tables =
                        new MemoryTables()
                                .add(BUILD, "enUS", List.of(correct))
                                .forBuild(BUILD, "enUS", Cancellation.NONE)
                                .join()
                                .tables();
                var service = new MountsService(tables)) {
            var result = service.overview(6, Cancellation.NONE).join();
            assertEquals(
                    List.of(new MountTypes.Variant(6, 2404, null, "Mount")), result.variants());
        }
        var wrong =
                table(
                        1,
                        "Mount",
                        List.of(text("Name_lang"), reference("CreatureDisplayInfoID", "Spell")),
                        List.of(row(6, "Horse", "2404")));
        try (var tables =
                        new MemoryTables()
                                .add(BUILD, "enUS", List.of(wrong))
                                .forBuild(BUILD, "enUS", Cancellation.NONE)
                                .join()
                                .tables();
                var service = new MountsService(tables)) {
            assertTrue(service.overview(6, Cancellation.NONE).join().variants().isEmpty());
        }
    }

    @Test
    void cancellationAndIdBoundsAreExplicit() {
        try (var tables =
                        new MemoryTables()
                                .add(BUILD, "enUS", List.of())
                                .forBuild(BUILD, "enUS", Cancellation.NONE)
                                .join()
                                .tables();
                var service = new MountsService(tables)) {
            assertThrows(
                    IllegalArgumentException.class, () -> service.overview(0, Cancellation.NONE));
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> service.catalog(token).join());
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
