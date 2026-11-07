package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.formats.tables.Tables;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemGridSqliteTest {
    private static final BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
    @TempDir Path directory;

    private static Db2Types.Row row(long id, String... values) {
        return new Db2Types.Row(id, false, null, List.of(values), null);
    }

    private static MemoryTables.FakeTable table(
            int id, String name, String[] names, List<Db2Types.Row> rows) {
        var columns =
                java.util.Arrays.stream(names)
                        .map(
                                column ->
                                        new Db2Types.Column(
                                                column,
                                                column.endsWith("_lang") ? "string" : "integer",
                                                column.equals("ID"),
                                                null,
                                                null))
                        .toList();
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        id,
                        name,
                        TableTypes.State.READY,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "WDC5", "0"),
                        "content-" + name),
                columns,
                rows,
                null,
                Map.of(),
                true);
    }

    private static MemoryTables.FakeTable classificationTable(
            boolean partial, int id, String name, String[] names, List<Db2Types.Row> rows) {
        var table = table(id, name, names, rows);
        var e = table.entry();
        return partial
                ? new MemoryTables.FakeTable(
                        new TableTypes.Entry(
                                e.fileDataId(),
                                e.name(),
                                TableTypes.State.PARTIAL,
                                true,
                                e.reason(),
                                e.counts(),
                                e.contentKey()),
                        table.columns(),
                        table.rows(),
                        table.schema(),
                        Map.of(),
                        true)
                : table;
    }

    private static MemoryTables data() {
        return data(false);
    }

    private static MemoryTables data(boolean partialLabels) {
        var item = new ArrayList<Db2Types.Row>();
        var sparse = new ArrayList<Db2Types.Row>();
        for (int id = 1; id <= 1200; id++) {
            boolean target = id == 1200;
            item.add(
                    row(
                            id,
                            Integer.toString(id),
                            target ? "2" : "4",
                            target ? "7" : "4",
                            target ? "13" : "1",
                            "0"));
            sparse.add(
                    row(
                            id,
                            Integer.toString(id),
                            target
                                    ? "Target beyond the first page"
                                    : id == 100 ? "Straße" : "Item " + id,
                            target ? "3" : "4",
                            target ? "500" : "60",
                            target ? "13" : "1"));
        }
        return new MemoryTables()
                .add(
                        BUILD,
                        "deDE",
                        List.of(
                                table(
                                        1,
                                        "Item",
                                        new String[] {
                                            "ID",
                                            "ClassID",
                                            "SubclassID",
                                            "InventoryType",
                                            "IconFileDataID"
                                        },
                                        item),
                                table(
                                        2,
                                        "ItemSparse",
                                        new String[] {
                                            "ID",
                                            "Display_lang",
                                            "OverallQualityID",
                                            "ItemLevel",
                                            "InventoryType"
                                        },
                                        sparse),
                                classificationTable(
                                        partialLabels,
                                        3,
                                        "ItemClass",
                                        new String[] {"ID", "ClassID", "ClassName_lang"},
                                        List.of(
                                                row(900, "900", "4", "Rüstung"),
                                                row(901, "901", "2", "Waffen"))),
                                classificationTable(
                                        partialLabels,
                                        4,
                                        "ItemSubClass",
                                        new String[] {
                                            "ID", "ClassID", "SubClassID", "DisplayName_lang"
                                        },
                                        List.of(
                                                row(902, "902", "4", "4", "Platte"),
                                                row(903, "903", "2", "7", "Schwerter")))));
    }

    private BuildItems open(Tables tables, String inputs) {
        var store = new MemoryGeneratedStore();
        var forbidden =
                (com.vibeminer.files.Files)
                        Proxy.newProxyInstance(
                                com.vibeminer.files.Files.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.Files.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError(
                                            "Grid loaded media: " + method.getName());
                                });
        return new ItemsService(tables, forbidden, new BlpImages(store), store, directory, inputs)
                .forBuild(BUILD, "deDE", Cancellation.NONE)
                .join()
                .items();
    }

    @Test
    void persistsReadablePartialClassificationCandidatesWithUncertainty() {
        try (var handle = open(data(true), "partial-labels")) {
            var grid =
                    handle.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals("Rüstung (partial)", grid.rows().getFirst().itemClass());
            assertEquals("Platte (partial)", grid.rows().getFirst().subclass());
            assertTrue(
                    grid.options().classes().stream()
                            .anyMatch(choice -> choice.label().equals("Waffen (partial)")));
        }
        try (var reopened = open(data(true), "partial-labels")) {
            var grid =
                    reopened.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals("Rüstung (partial)", grid.rows().getFirst().itemClass());
        }
    }

    @Test
    void queriesWholeCatalogBeforePagingAndRestoresWithoutSourceRowScans() {
        var source = data();
        try (var handle = open(source, "keys-v1")) {
            var first =
                    handle.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(100, first.rows().size());
            assertEquals(1200, first.matches());
            assertEquals(1200, first.available());
            assertTrue(
                    first.options().classes().stream()
                            .anyMatch(
                                    choice ->
                                            choice.value().equals("2")
                                                    && choice.label().equals("Waffen")));
            assertEquals("Rüstung", first.rows().getFirst().itemClass());
            var query =
                    new ItemGridQuery(
                            "Target", "2", "2:7", "3", "13", ItemGridQuery.Sort.LEVEL, true, 0, 20);
            var filtered =
                    handle.grid(query, TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
            assertEquals(1, filtered.matches());
            assertEquals(1200, filtered.rows().getFirst().id());
            assertEquals("Schwerter", filtered.rows().getFirst().subclass());
            var sorted =
                    handle.grid(
                                    new ItemGridQuery(
                                            "",
                                            null,
                                            null,
                                            null,
                                            null,
                                            ItemGridQuery.Sort.LEVEL,
                                            true,
                                            0,
                                            10),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(1200, sorted.rows().getFirst().id());
            var second =
                    handle.grid(
                                    new ItemGridQuery(
                                            "",
                                            null,
                                            null,
                                            null,
                                            null,
                                            ItemGridQuery.Sort.ID,
                                            false,
                                            100,
                                            100),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(101, second.rows().getFirst().id());
            assertEquals(1200, second.matches());
            assertEquals(
                    0,
                    handle.grid(
                                    new ItemGridQuery(
                                            "STRASSE",
                                            null,
                                            null,
                                            null,
                                            null,
                                            ItemGridQuery.Sort.ID,
                                            false,
                                            0,
                                            100),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .matches());
            assertEquals(
                    1,
                    handle.grid(
                                    new ItemGridQuery(
                                            "straße",
                                            null,
                                            null,
                                            null,
                                            null,
                                            ItemGridQuery.Sort.ID,
                                            false,
                                            0,
                                            100),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .matches());
            assertEquals(
                    0,
                    handle.grid(
                                    new ItemGridQuery(
                                            "' OR 1=1 --",
                                            null,
                                            null,
                                            null,
                                            null,
                                            ItemGridQuery.Sort.ID,
                                            false,
                                            0,
                                            100),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .matches());
        }
        Tables noScans =
                (build, locale, token) ->
                        source.forBuild(build, locale, token)
                                .thenApply(
                                        open -> {
                                            var wrapped =
                                                    (com.vibeminer.formats.tables.BuildTables)
                                                            Proxy.newProxyInstance(
                                                                    com.vibeminer.formats.tables
                                                                            .BuildTables.class
                                                                            .getClassLoader(),
                                                                    new Class<?>[] {
                                                                        com.vibeminer.formats.tables
                                                                                .BuildTables.class
                                                                    },
                                                                    (proxy, method, args) -> {
                                                                        if (method.getName()
                                                                                        .equals(
                                                                                                "page")
                                                                                || method.getName()
                                                                                        .equals(
                                                                                                "get")) {
                                                                            throw new AssertionError(
                                                                                    "Index reopen rescanned source tables");
                                                                        }
                                                                        try {
                                                                            return method.invoke(
                                                                                    open.tables(),
                                                                                    args);
                                                                        } catch (
                                                                                InvocationTargetException
                                                                                        failure) {
                                                                            throw failure
                                                                                    .getCause();
                                                                        }
                                                                    });
                                            return new TableTypes.OpenResult(
                                                    open.build(),
                                                    open.locale(),
                                                    open.state(),
                                                    wrapped,
                                                    open.code(),
                                                    open.message(),
                                                    open.observedAt());
                                        });
        try (var handle = open(noScans, "keys-v1")) {
            var page =
                    handle.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(1200, page.matches());
        }
    }

    private record Report(String stage, long done) {}

    /** #338: a cold build reports rows read and stored; a catalog reused from disk reports none. */
    @Test
    void reportsCatalogRowsWhileBuildingAndNothingWhenReused() {
        var source = data();
        var built = new ArrayList<Report>();
        try (var handle = open(source, "progress")) {
            var page =
                    handle.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    (stage, done) -> built.add(new Report(stage, done)),
                                    Cancellation.NONE)
                            .join();
            assertEquals(1200, page.matches());
        }
        assertEquals(List.of(1000L, 1200L), done(built, "item text rows read"));
        assertEquals(List.of(1000L, 1200L), done(built, "item rows read"));
        assertEquals(List.of(1000L), done(built, "catalog rows stored"));
        var reused = new ArrayList<Report>();
        try (var handle = open(source, "progress")) {
            var page =
                    handle.grid(
                                    new ItemGridQuery(),
                                    TableTypes.Priority.INTERACTIVE,
                                    (stage, done) -> reused.add(new Report(stage, done)),
                                    Cancellation.NONE)
                            .join();
            assertEquals(1200, page.matches());
        }
        assertEquals(List.of(), reused);
    }

    private static List<Long> done(List<Report> reports, String stage) {
        return reports.stream().filter(r -> r.stage().equals(stage)).map(Report::done).toList();
    }
}
