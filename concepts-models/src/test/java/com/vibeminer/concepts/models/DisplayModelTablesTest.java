package com.vibeminer.concepts.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableTypes;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * DisplayModelService's display references over authored in-memory tables: the geoset-row lookup
 * (CreatureDisplayInfoGeosetData) and absent versus unreadable CreatureGeosetDataID. No game data.
 */
class DisplayModelTablesTest {
    /** One authored table: integer columns (name to foreign key, null for none) and rows by ID. */
    static final class Table {
        final String name;
        TableTypes.State state = TableTypes.State.READY;
        final Map<String, TableSchema.ForeignKey> columns = new LinkedHashMap<>();
        final Map<Long, List<String>> rows = new LinkedHashMap<>();
        final Map<Long, Integer> unreadable = new HashMap<>();

        /** Page reads still to refuse with the table scheduler's busy failure. */
        int busyPages;

        Table(String name, String... columns) {
            this.name = name;
            this.columns.put("ID", null);
            for (int i = 0; i < columns.length; i += 2) {
                this.columns.put(
                        columns[i],
                        columns[i + 1] == null
                                ? null
                                : new TableSchema.ForeignKey(columns[i + 1], "ID"));
            }
        }

        Table row(long id, long... values) {
            var all = new ArrayList<String>();
            all.add(Long.toString(id));
            for (long value : values) {
                all.add(Long.toString(value));
            }
            rows.put(id, all);
            return this;
        }

        TableTypes.Entry entry() {
            return new TableTypes.Entry(1, name, state, true, null, null, "k");
        }

        TableTypes.SchemaResult schema() {
            if (state == TableTypes.State.NOT_IN_BUILD) {
                return new TableTypes.SchemaResult(entry(), null, null);
            }
            var physical = new ArrayList<Db2Types.Column>();
            var declared = new ArrayList<TableSchema.SchemaColumn>();
            for (var column : columns.entrySet()) {
                physical.add(
                        new Db2Types.Column(
                                column.getKey(),
                                "integer",
                                column.getKey().equals("ID"),
                                null,
                                null));
                declared.add(
                        new TableSchema.SchemaColumn(
                                column.getKey(),
                                TableSchema.ColumnKind.INT,
                                32,
                                false,
                                1,
                                column.getValue(),
                                column.getKey().equals("ID")
                                        ? TableSchema.KeyStorage.INLINE
                                        : TableSchema.KeyStorage.NONE,
                                TableSchema.KeyStorage.NONE,
                                true,
                                null,
                                null,
                                null));
            }
            return new TableTypes.SchemaResult(
                    entry(),
                    physical,
                    new TableSchema(
                            declared,
                            List.of(),
                            TableSchema.SchemaMatch.BUILD,
                            com.vibeminer.formats.definitions.WowLocale.EN_US,
                            null));
        }

        Db2Types.Row db2(long id) {
            var issues =
                    unreadable.containsKey(id)
                            ? List.of(new Db2Types.TextIssue(unreadable.get(id), "BAD", "00"))
                            : null;
            return new Db2Types.Row(id, false, null, rows.get(id), issues);
        }
    }

    static final class Tables {
        final Map<String, Table> tables = new HashMap<>();
        final Map<String, AtomicInteger> pages = new HashMap<>();

        Tables add(Table table) {
            tables.put(table.name, table);
            return this;
        }

        int pages(String name) {
            return pages.computeIfAbsent(name, n -> new AtomicInteger()).get();
        }

        BuildTables handle() {
            return (BuildTables)
                    Proxy.newProxyInstance(
                            BuildTables.class.getClassLoader(),
                            new Class<?>[] {BuildTables.class},
                            (proxy, method, args) ->
                                    switch (method.getName()) {
                                        case "schema" -> {
                                            var table = table((TableTypes.Ref) args[0]);
                                            yield CompletableFuture.completedFuture(
                                                    table == null
                                                            ? new TableTypes.SchemaResult(
                                                                    null, null, null)
                                                            : table.schema());
                                        }
                                        case "get" -> {
                                            var table = table((TableTypes.Ref) args[0]);
                                            long id = (Long) args[1];
                                            boolean found = table.rows.containsKey(id);
                                            yield CompletableFuture.completedFuture(
                                                    new TableTypes.RowResult(
                                                            table.entry(),
                                                            found
                                                                    ? TableTypes.RowOutcome.FOUND
                                                                    : TableTypes.RowOutcome.ABSENT,
                                                            found
                                                                    ? new TableTypes.Row(
                                                                            0, table.db2(id))
                                                                    : null,
                                                            null));
                                        }
                                        case "page" -> {
                                            var request = (TableTypes.PageRequest) args[0];
                                            var table = table(request.table());
                                            pages.computeIfAbsent(
                                                            table.name, n -> new AtomicInteger())
                                                    .incrementAndGet();
                                            if (table.busyPages > 0) {
                                                table.busyPages--;
                                                yield CompletableFuture.failedFuture(busy());
                                            }
                                            var rows = new ArrayList<TableTypes.Row>();
                                            for (long id : table.rows.keySet()) {
                                                rows.add(new TableTypes.Row(id, table.db2(id)));
                                            }
                                            yield CompletableFuture.completedFuture(
                                                    new TableTypes.Page(
                                                            table.entry(),
                                                            TableTypes.PageState.OK,
                                                            rows,
                                                            null,
                                                            "identity",
                                                            rows.size(),
                                                            null,
                                                            null));
                                        }
                                        case "closeAsync" ->
                                                CompletableFuture.completedFuture(null);
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected " + method.getName());
                                    });
        }

        private Table table(TableTypes.Ref ref) {
            return tables.get(ref.name());
        }
    }

    /** Displays 5 and 6 on model 9 (file 1234); model 9 records CreatureGeosetDataID 0. */
    private static Tables displays(boolean geosetColumn) {
        var displays =
                new Table("CreatureDisplayInfo", "ModelID", "CreatureModelData")
                        .row(5, 9)
                        .row(6, 9);
        var models =
                geosetColumn
                        ? new Table(
                                        "CreatureModelData",
                                        "FileDataID",
                                        "FileData",
                                        "CreatureGeosetDataID",
                                        null)
                                .row(9, 1234, 0)
                        : new Table("CreatureModelData", "FileDataID", "FileData").row(9, 1234);
        return new Tables().add(displays).add(models);
    }

    private static Table geosetRows(long... displayIds) {
        var table =
                new Table(
                        DisplayModelService.GEOSET_TABLE,
                        "CreatureDisplayInfoID",
                        "CreatureDisplayInfo");
        for (int i = 0; i < displayIds.length; i++) {
            table.row(100 + i, displayIds[i]);
        }
        return table;
    }

    private static DisplayModelService service(Tables tables) {
        var files =
                (com.vibeminer.files.BuildFiles)
                        Proxy.newProxyInstance(
                                com.vibeminer.files.BuildFiles.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.files.BuildFiles.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("No file IO: " + method.getName());
                                });
        return new DisplayModelService(tables.handle(), files, null);
    }

    private static DisplayModelTypes.Reference resolve(DisplayModelService service, long id) {
        return service.resolve(id, Cancellation.NONE, new ArrayList<>());
    }

    @Test
    void geosetRowsAreReadOnceAndAnswerPerDisplay() {
        var tables = displays(true).add(geosetRows(6));
        var service = service(tables);
        var five = resolve(service, 5);
        var six = resolve(service, 6);
        assertEquals(Boolean.FALSE, five.displayGeosets(), "no rows for display 5");
        assertEquals(Boolean.TRUE, six.displayGeosets(), "display 6 records its own geosets");
        assertEquals(1, tables.pages(DisplayModelService.GEOSET_TABLE), "the table is read once");
        assertEquals(0L, five.creatureGeosetDataId());
        service.closeAsync().join();
    }

    @Test
    void anEmptyOrMissingTableMeansNoRowsAndAPartialOrBadOneIsUnknown() {
        assertEquals(
                Boolean.FALSE,
                resolve(service(displays(true).add(geosetRows())), 5).displayGeosets(),
                "an empty table has no rows");
        var missing = geosetRows(5);
        missing.state = TableTypes.State.NOT_IN_BUILD;
        assertEquals(
                Boolean.FALSE,
                resolve(service(displays(true).add(missing)), 5).displayGeosets(),
                "a table the build does not have means no rows");
        var partial = geosetRows(6);
        partial.state = TableTypes.State.PARTIAL;
        var tables = displays(true).add(partial);
        var service = service(tables);
        assertNull(resolve(service, 5).displayGeosets(), "a partial table is unknown");
        assertNull(resolve(service, 6).displayGeosets());
        assertEquals(1, tables.pages(DisplayModelService.GEOSET_TABLE), "refused once, not reread");
        var unrelated =
                new Table(DisplayModelService.GEOSET_TABLE, "CreatureDisplayInfoID", null)
                        .row(1, 5);
        assertNull(
                resolve(service(displays(true).add(unrelated)), 5).displayGeosets(),
                "an unverified join column is unknown");
        var stated = new ArrayList<String>();
        service(displays(true)).resolve(5, Cancellation.NONE, stated);
        assertTrue(
                stated.stream().anyMatch(s -> s.contains("Display geoset selections are unknown")),
                "an unavailable table is unknown, with the reason: " + stated);
    }

    private static RuntimeException busy() throws ReflectiveOperationException {
        var constructor =
                com.vibeminer.formats.tables.TableWorkScheduler.BusyException.class
                        .getDeclaredConstructor(TableTypes.Priority.class);
        constructor.setAccessible(true);
        return constructor.newInstance(TableTypes.Priority.INTERACTIVE);
    }

    @Test
    void aBusyGeosetTableIsRetriedAWhileThenRefused() {
        var once = geosetRows(5);
        once.busyPages = 1;
        var tables = displays(true).add(once);
        var service = service(tables);
        assertNull(resolve(service, 5).displayGeosets(), "busy now: unknown, not cached");
        assertEquals(Boolean.TRUE, resolve(service, 5).displayGeosets(), "read again later");
        var stuck = geosetRows(5);
        stuck.busyPages = Integer.MAX_VALUE;
        var stuckTables = displays(true).add(stuck);
        var stuckService = service(stuckTables);
        for (int i = 0; i < DisplayModelService.GEOSET_RETRIES + 3; i++) {
            assertNull(resolve(stuckService, 5).displayGeosets());
        }
        assertEquals(
                DisplayModelService.GEOSET_RETRIES,
                stuckTables.pages(DisplayModelService.GEOSET_TABLE),
                "a stuck table is tried a bounded number of times");
    }

    @Test
    void anOverBoundTableIsUnknown() {
        var many = geosetRows();
        for (int i = 0; i <= DisplayModelService.GEOSET_ROWS; i++) {
            many.row(i + 1, 5);
        }
        assertNull(resolve(service(displays(true).add(many)), 5).displayGeosets());
    }

    @Test
    void anAbsentGeosetColumnIsZeroAndAnUnreadableOneIsNull() {
        assertEquals(
                0L,
                resolve(service(displays(false).add(geosetRows())), 5).creatureGeosetDataId(),
                "an absent column means no geoset data");
        var tables = displays(true).add(geosetRows());
        tables.tables.get("CreatureModelData").unreadable.put(9L, 2);
        assertNull(
                resolve(service(tables), 5).creatureGeosetDataId(),
                "an unreadable value stays unknown");
    }
}
