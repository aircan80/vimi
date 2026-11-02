package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.MemoryFiles;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.images.BlpImages;
import com.vibeminer.formats.tables.MemoryTables;
import com.vibeminer.formats.tables.TableTypes;
import com.vibeminer.generated.MemoryGeneratedStore;
import java.util.List;
import java.util.Map;

final class ItemsWorld {
    static final BuildRef BUILD = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
    static final Cancellation TOKEN = new Cancellation();
    static final TableTypes.Priority PRIORITY = TableTypes.Priority.INTERACTIVE;

    static List<Db2Types.Column> columns(String... names) {
        return java.util.Arrays.stream(names)
                .map(
                        name ->
                                new Db2Types.Column(
                                        name,
                                        name.endsWith("_lang") ? "string" : "integer",
                                        name.equals("ID"),
                                        null,
                                        null))
                .toList();
    }

    static Db2Types.Row row(long id, String... cells) {
        return new Db2Types.Row(id, false, null, List.of(cells), null);
    }

    static MemoryTables.FakeTable table(
            int id, String name, List<Db2Types.Column> columns, List<Db2Types.Row> rows) {
        return table(id, name, columns, rows, TableTypes.State.READY, Map.of());
    }

    static MemoryTables.FakeTable table(
            int id,
            String name,
            List<Db2Types.Column> columns,
            List<Db2Types.Row> rows,
            TableTypes.State state,
            Map<Long, String> protectedIds) {
        return new MemoryTables.FakeTable(
                new TableTypes.Entry(
                        id,
                        name,
                        state,
                        true,
                        null,
                        new TableTypes.Counts(rows.size(), rows.size(), 0, 0, "WDC5", "0"),
                        "key-" + name),
                columns,
                rows,
                null,
                protectedIds,
                true);
    }

    static List<Db2Types.Column> ITEM =
            columns("ID", "ClassID", "SubclassID", "InventoryType", "IconFileDataID");
    static List<Db2Types.Column> SPARSE =
            columns("ID", "Display_lang", "OverallQualityID", "ItemLevel", "InventoryType");

    static MemoryTables data(List<Db2Types.Row> items, List<Db2Types.Row> sparse) {
        return new MemoryTables()
                .add(
                        BUILD,
                        "enUS",
                        List.of(
                                table(1, "Item", ITEM, items),
                                table(2, "ItemSparse", SPARSE, sparse)));
    }

    static BuildItems open(MemoryTables data) {
        return new ItemsService(data, new MemoryFiles(), new BlpImages(new MemoryGeneratedStore()))
                .forBuild(BUILD, "enUS", TOKEN)
                .join()
                .items();
    }
}
