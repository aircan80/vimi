package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.core.ConceptQueries;
import com.vibeminer.generated.DerivedDatabase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Immutable, versioned SQLite projection. Only a bounded result page crosses the UI boundary. */
final class ItemGridStore implements AutoCloseable {
    static final String FORMAT = "vibeminer-item-grid-sqlite-v1";
    private final DerivedDatabase database;
    private final String identity;
    private final Path path;
    private long available;
    private String message;

    private ItemGridStore(DerivedDatabase database, String identity, Path path) {
        this.database = database;
        this.identity = identity;
        this.path = path;
    }

    List<Path> files() {
        return List.of(path, path.resolveSibling(path.getFileName() + "-wal"));
    }

    static ItemGridStore open(Path directory, String identity, Cancellation token) {
        try (var bridge = new Token(token)) {
            var db =
                    DerivedDatabase.open(
                                    directory.resolve("items-" + identity + ".sqlite"),
                                    bridge.value)
                            .join();
            var store =
                    new ItemGridStore(
                            db, identity, directory.resolve("items-" + identity + ".sqlite"));
            try {
                db.transaction(
                                session -> {
                                    var app =
                                            (Long)
                                                    session.query("PRAGMA application_id")
                                                            .getFirst()[0];
                                    if (app != 0 && app != 0x564d6974L) {
                                        throw new IllegalStateException(
                                                "Unrecognized derived item index");
                                    }
                                    session.execute("PRAGMA application_id=" + 0x564d6974);
                                    session.execute(
                                            "CREATE TABLE IF NOT EXISTS metadata(identity TEXT PRIMARY KEY, complete INTEGER NOT NULL, count INTEGER NOT NULL, message TEXT NOT NULL)");
                                    session.execute(
                                            "CREATE TABLE IF NOT EXISTS items(id INTEGER PRIMARY KEY,name TEXT NOT NULL,name_key TEXT NOT NULL,icon INTEGER,class_id TEXT,sub_id TEXT,class_name TEXT,sub_name TEXT,quality_id TEXT,quality_name TEXT,slot_id TEXT,slot_name TEXT,level INTEGER)");
                                    return null;
                                },
                                bridge.value)
                        .join();
                var meta =
                        db.read(
                                        session ->
                                                session.query(
                                                        "SELECT count,message FROM metadata WHERE identity=? AND complete=1",
                                                        identity),
                                        bridge.value)
                                .join();
                if (!meta.isEmpty()) {
                    store.available = (Long) meta.getFirst()[0];
                    store.message = (String) meta.getFirst()[1];
                }
                return store;
            } catch (Throwable failure) {
                db.close();
                throw failure;
            }
        }
    }

    boolean ready() {
        return message != null;
    }

    void populate(ItemCatalog catalog, Cancellation token) {
        populate(catalog, BuildItems.CatalogProgress.NONE, token);
    }

    /** Stores the catalog, reporting rows stored every thousand (#338). */
    void populate(ItemCatalog catalog, BuildItems.CatalogProgress progress, Cancellation token) {
        try (var bridge = new Token(token)) {
            database.transaction(
                            session -> {
                                session.execute("DELETE FROM items");
                                session.execute("DELETE FROM metadata");
                                long stored = 0;
                                for (var entry : catalog.entries()) {
                                    token.check();
                                    if (++stored % 1000 == 0) {
                                        progress.report("catalog rows stored", stored);
                                    }
                                    var row = project(entry);
                                    var name = entry.item().name();
                                    String key =
                                            name != null && name.state() == ItemNameState.VALID
                                                    ? ConceptQueries.searchKey(name.value())
                                                    : "";
                                    session.execute(
                                            "INSERT INTO items VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                            row.id(),
                                            row.name(),
                                            key,
                                            row.iconFileDataId(),
                                            row.classId(),
                                            row.subclassId(),
                                            row.itemClass(),
                                            row.subclass(),
                                            row.qualityId(),
                                            row.quality(),
                                            row.slotId(),
                                            row.slot(),
                                            row.itemLevel());
                                }
                                session.execute(
                                        "CREATE INDEX IF NOT EXISTS items_name ON items(name_key,id)");
                                session.execute(
                                        "CREATE INDEX IF NOT EXISTS items_class ON items(class_id,sub_id,id)");
                                session.execute(
                                        "CREATE INDEX IF NOT EXISTS items_quality ON items(quality_id,id)");
                                session.execute(
                                        "CREATE INDEX IF NOT EXISTS items_slot ON items(slot_id,id)");
                                session.execute(
                                        "CREATE INDEX IF NOT EXISTS items_level ON items(level,id)");
                                session.execute(
                                        "INSERT INTO metadata VALUES(?,1,?,?)",
                                        identity,
                                        (long) catalog.entries().size(),
                                        catalog.message());
                                return null;
                            },
                            bridge.value)
                    .join();
            available = catalog.entries().size();
            message = catalog.message();
        }
    }

    ItemGridPage query(ItemGridQuery query, Cancellation token) {
        try (var bridge = new Token(token)) {
            return database.read(
                            session -> {
                                var where = new StringBuilder(" WHERE 1=1");
                                var parameters = new ArrayList<Object>();
                                Long exact = ConceptQueries.tryParseId(query.search());
                                if (exact != null) {
                                    where.append(" AND id=?");
                                    parameters.add(exact);
                                } else if (!query.search().isEmpty()) {
                                    where.append(" AND instr(name_key,?)>0");
                                    parameters.add(ConceptQueries.searchKey(query.search()));
                                }
                                filter(
                                        where,
                                        parameters,
                                        "class_id",
                                        "class_name",
                                        query.itemClass());
                                if (query.subclass() != null) {
                                    if (query.subclass().equals("?")) {
                                        where.append(" AND sub_name IS NULL");
                                    } else {
                                        var pair = query.subclass().split(":", -1);
                                        if (pair.length != 2) {
                                            throw new IllegalArgumentException(
                                                    "Invalid subclass choice");
                                        }
                                        where.append(" AND class_id=? AND sub_id=?");
                                        parameters.add(pair[0]);
                                        parameters.add(pair[1]);
                                    }
                                }
                                filter(
                                        where,
                                        parameters,
                                        "quality_id",
                                        "quality_name",
                                        query.quality());
                                filter(where, parameters, "slot_id", "slot_name", query.slot());
                                long matches =
                                        (Long)
                                                session.query(
                                                                "SELECT count(*) FROM items"
                                                                        + where,
                                                                parameters.toArray())
                                                        .getFirst()[0];
                                String order =
                                        switch (query.sort()) {
                                            case ID -> "id";
                                            case NAME -> "name_key";
                                            case CLASS -> "class_name";
                                            case SUBCLASS -> "sub_name";
                                            case QUALITY -> "CAST(quality_id AS INTEGER)";
                                            case SLOT -> "slot_name";
                                            case LEVEL -> "level";
                                        };
                                var pageParameters = new ArrayList<>(parameters);
                                pageParameters.add((long) query.limit());
                                pageParameters.add((long) query.offset());
                                var rows =
                                        session.query(
                                                "SELECT id,name,icon,class_id,sub_id,class_name,sub_name,quality_id,quality_name,slot_id,slot_name,level FROM items"
                                                        + where
                                                        + " ORDER BY "
                                                        + order
                                                        + (query.descending() ? " DESC" : " ASC")
                                                        + ",id ASC LIMIT ? OFFSET ?",
                                                pageParameters.toArray());
                                var entries =
                                        rows.stream()
                                                .map(
                                                        row ->
                                                                new ItemGridRow(
                                                                        (Long) row[0],
                                                                        (String) row[1],
                                                                        (Long) row[2],
                                                                        (String) row[3],
                                                                        (String) row[4],
                                                                        (String) row[5],
                                                                        (String) row[6],
                                                                        (String) row[7],
                                                                        (String) row[8],
                                                                        (String) row[9],
                                                                        (String) row[10],
                                                                        (Long) row[11]))
                                                .toList();
                                var classes =
                                        choices(
                                                session.query(
                                                        "SELECT DISTINCT class_id,class_name FROM items WHERE class_name IS NOT NULL ORDER BY class_name,class_id"));
                                var subWhere = new StringBuilder(" WHERE sub_name IS NOT NULL");
                                var subParameters = new ArrayList<Object>();
                                filter(
                                        subWhere,
                                        subParameters,
                                        "class_id",
                                        "class_name",
                                        query.itemClass());
                                var subclasses = new ArrayList<ItemGridPage.Choice>();
                                for (var row :
                                        session.query(
                                                "SELECT DISTINCT class_id,sub_id,sub_name,class_name FROM items"
                                                        + subWhere
                                                        + " ORDER BY sub_name,class_name",
                                                subParameters.toArray())) {
                                    subclasses.add(
                                            new ItemGridPage.Choice(
                                                    row[0] + ":" + row[1],
                                                    row[2]
                                                            + (query.itemClass() == null
                                                                    ? " · " + row[3]
                                                                    : "")));
                                }
                                var qualities =
                                        choices(
                                                session.query(
                                                        "SELECT DISTINCT quality_id,quality_name FROM items WHERE quality_name IS NOT NULL ORDER BY CAST(quality_id AS INTEGER)"));
                                var slots =
                                        choices(
                                                session.query(
                                                        "SELECT DISTINCT slot_id,slot_name FROM items WHERE slot_name IS NOT NULL ORDER BY slot_name"));
                                return new ItemGridPage(
                                        ItemsPageState.OK,
                                        entries,
                                        matches,
                                        available,
                                        new ItemGridPage.Options(
                                                classes, subclasses, qualities, slots),
                                        message);
                            },
                            bridge.value)
                    .join();
        }
    }

    private static List<ItemGridPage.Choice> choices(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new ItemGridPage.Choice((String) row[0], (String) row[1]))
                .toList();
    }

    private static void filter(
            StringBuilder sql, List<Object> parameters, String column, String label, String value) {
        if (value == null) {
            return;
        }
        if (value.equals("?")) {
            sql.append(" AND ").append(label).append(" IS NULL");
        } else {
            sql.append(" AND ").append(column).append("=?");
            parameters.add(value);
        }
    }

    private static String value(ItemSummary summary, String column) {
        var values =
                summary.values().stream()
                        .filter(v -> v.column().equals(column))
                        .map(ItemSourceValue::value)
                        .distinct()
                        .toList();
        return values.size() == 1 ? values.getFirst() : null;
    }

    private static ItemGridRow project(ItemListLabels entry) {
        var item = entry.item();
        var labels = entry.labels();
        String name = item.name() == null ? null : item.name().value();
        if (name == null || name.isBlank()) {
            name = "Item " + item.id();
        }
        String cls = labels == null ? null : classText(labels.itemClass());
        String sub = labels == null ? null : classText(labels.subclass());
        String quality = value(item, "OverallQualityID"),
                slot = value(item, "InventoryType"),
                level = value(item, "ItemLevel");
        Long itemLevel = null;
        try {
            if (level != null) {
                itemLevel = Long.valueOf(level);
            }
        } catch (NumberFormatException invalid) {
            /* Unknown remains null. */
        }
        return new ItemGridRow(
                item.id(),
                name,
                item.iconFileDataId(),
                value(item, "ClassID"),
                value(item, "SubclassID"),
                cls,
                sub,
                quality,
                (quality == null ? null : ItemFacts.QUALITY.get(quality)),
                slot,
                (slot == null ? null : ItemFacts.INVENTORY_TYPE.get(slot)),
                itemLevel);
    }

    private static String classText(ItemClassLabel label) {
        if (label == null || label.text() == null || label.text().isBlank()) {
            return null;
        }
        return switch (label.state()) {
            case READY -> label.text();
            case INCOMPLETE_TARGET -> label.text() + " (partial)";
            default -> null;
        };
    }

    @Override
    public void close() {
        database.close();
    }

    private static final class Token implements AutoCloseable {
        final com.vibeminer.generated.Cancellation value =
                new com.vibeminer.generated.Cancellation();
        private final Cancellation.Registration registration;

        Token(Cancellation token) {
            registration = token.onCancel(value::cancel);
        }

        public void close() {
            registration.close();
        }
    }
}
