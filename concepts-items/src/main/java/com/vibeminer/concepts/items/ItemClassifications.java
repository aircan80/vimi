package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.definitions.TableSchema;
import com.vibeminer.formats.tables.BuildTables;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes;
import java.util.ArrayList;
import java.util.List;

/** Reviewed signed8 semantic classification joins, never row-ID label lookup. */
public final class ItemClassifications {
    public static final String POLICY_REVISION = "classification-label-index-v2",
            CLASS_TABLE = "ItemClass",
            SUBCLASS_TABLE = "ItemSubClass",
            PROFILE_UNSUPPORTED_CODE = "ITEM_CLASSIFICATION_PROFILE_UNSUPPORTED",
            TEXT_LIMIT_CODE = "ITEM_CLASSIFICATION_TEXT_LIMIT",
            SCAN_LIMIT_CODE = "ITEM_CLASSIFICATION_SCAN_LIMIT";
    public static final int MAX_TEXT_UNITS = 4096;

    private ItemClassifications() {}

    public record SourceKey(
            ItemClassLabelState state, int classId, int subclassId, List<ItemSourceValue> keys) {
        public SourceKey {
            keys = List.copyOf(keys);
        }
    }

    public static SourceKey sourceKey(List<ItemSourceValue> values, boolean subclass) {
        var cls =
                values.stream()
                        .filter(
                                v ->
                                        v.table().equals(ItemLayout.ITEM)
                                                && v.column().equals("ClassID"))
                        .toList();
        var sub =
                subclass
                        ? values.stream()
                                .filter(
                                        v ->
                                                v.table().equals(ItemLayout.ITEM)
                                                        && v.column().equals("SubclassID"))
                                .toList()
                        : List.<ItemSourceValue>of();
        var keys = new ArrayList<ItemSourceValue>(cls);
        keys.addAll(sub);
        Long c = cls.size() == 1 ? integer(cls.getFirst().value()) : null;
        Long s =
                !subclass
                        ? Long.valueOf(0)
                        : sub.size() == 1 ? integer(sub.getFirst().value()) : null;
        if (c == null
                || c < Integer.MIN_VALUE
                || c > Integer.MAX_VALUE
                || s == null
                || s < 0
                || s > 255) {
            return new SourceKey(ItemClassLabelState.FIELD_UNAVAILABLE, 0, 0, keys);
        }
        if (c < -128 || c > 127 || s > 127) {
            return new SourceKey(ItemClassLabelState.KEY_OUT_OF_RANGE, 0, 0, keys);
        }
        return new SourceKey(null, c.intValue(), s.intValue(), keys);
    }

    private static Long integer(String value) {
        var n = TableQueries.parseInteger(value);
        return n != null && n.bitLength() < 64 ? n.longValue() : null;
    }

    static ItemClassLabels resolve(
            BuildTables tables,
            ItemSummary summary,
            int maxScanRows,
            TableTypes.Priority priority,
            Cancellation token) {
        var schema =
                tables.schema(TableTypes.Ref.forName(ItemLayout.ITEM), priority, token)
                        .join()
                        .schema();
        var values =
                summary.values().stream()
                        .filter(
                                v ->
                                        !v.table().equals(ItemLayout.ITEM)
                                                || switch (v.column()) {
                                                    case "ClassID" ->
                                                            qualifies(schema, "ClassID", 32, false);
                                                    case "SubclassID" ->
                                                            qualifies(
                                                                    schema, "SubclassID", 8, true);
                                                    default -> true;
                                                })
                        .toList();
        var cls = sourceKey(values, false);
        var sub = sourceKey(values, true);
        return new ItemClassLabels(
                cls.state() != null
                        ? result(
                                cls.state(),
                                cls.keys(),
                                null,
                                null,
                                null,
                                null,
                                0,
                                null,
                                null,
                                null)
                        : lookup(
                                tables,
                                CLASS_TABLE,
                                "ClassName_lang",
                                List.of(new Key("ClassID", cls.classId())),
                                cls.keys(),
                                maxScanRows,
                                priority,
                                token),
                sub.state() != null
                        ? result(
                                sub.state(),
                                sub.keys(),
                                null,
                                null,
                                null,
                                null,
                                0,
                                null,
                                null,
                                null)
                        : lookup(
                                tables,
                                SUBCLASS_TABLE,
                                "DisplayName_lang",
                                List.of(
                                        new Key("ClassID", sub.classId()),
                                        new Key("SubClassID", sub.subclassId())),
                                sub.keys(),
                                maxScanRows,
                                priority,
                                token));
    }

    private record Key(String column, int value) {}

    private static boolean qualifies(TableSchema schema, String name, int bits, boolean unsigned) {
        var column =
                schema == null
                        ? null
                        : schema.columns().stream()
                                .filter(c -> c.name().equals(name))
                                .findFirst()
                                .orElse(null);
        return column == null
                || column.kind() == TableSchema.ColumnKind.INT
                        && column.arrayLength() == 1
                        && column.verifiedName()
                        && Integer.valueOf(bits).equals(column.bits())
                        && Boolean.valueOf(unsigned).equals(column.unsigned());
    }

    private static boolean textQualifies(TableSchema schema, String name) {
        var column =
                schema == null
                        ? null
                        : schema.columns().stream()
                                .filter(c -> c.name().equals(name))
                                .findFirst()
                                .orElse(null);
        return column == null
                || (column.kind() == TableSchema.ColumnKind.STRING
                                || column.kind() == TableSchema.ColumnKind.LOCSTRING)
                        && column.arrayLength() == 1
                        && column.verifiedName();
    }

    private static int plain(List<Db2Types.Column> columns, String name, String kind) {
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            var c = columns.get(i);
            if (c.name().equals(name) && c.kind().equals(kind) && c.interpretation() == null) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    private static ItemClassLabel result(
            ItemClassLabelState state,
            List<ItemSourceValue> keys,
            String text,
            TableTypes.Row row,
            Integer index,
            Long candidates,
            long scanned,
            TableTypes.Entry table,
            String code,
            String message) {
        return new ItemClassLabel(
                state, keys, text, row, index, candidates, scanned, table, code, message);
    }

    private static ItemClassLabel lookup(
            BuildTables tables,
            String name,
            String textName,
            List<Key> keys,
            List<ItemSourceValue> sourceKeys,
            int maxScanRows,
            TableTypes.Priority priority,
            Cancellation token) {
        var table = TableTypes.Ref.forName(name);
        var schema = tables.schema(table, priority, token).join();
        if (!TableQueries.isReadable(schema.table().state())) {
            return result(
                    ItemClassLabelState.TABLE_NOT_READABLE,
                    sourceKeys,
                    null,
                    null,
                    null,
                    null,
                    0,
                    schema.table(),
                    null,
                    null);
        }
        var columns = schema.columns() == null ? List.<Db2Types.Column>of() : schema.columns();
        int text = plain(columns, textName, "string");
        if (text < 0
                || !textQualifies(schema.schema(), textName)
                || keys.stream()
                        .anyMatch(
                                k ->
                                        plain(columns, k.column(), "integer") < 0
                                                || !qualifies(
                                                        schema.schema(), k.column(), 8, false))) {
            return result(
                    ItemClassLabelState.UNSUPPORTED,
                    sourceKeys,
                    null,
                    null,
                    null,
                    null,
                    0,
                    schema.table(),
                    PROFILE_UNSUPPORTED_CODE,
                    "Classification profile is unsupported");
        }
        var filters =
                keys.stream()
                        .map(
                                k ->
                                        new TableTypes.Filter(
                                                k.column(),
                                                TableTypes.FilterOp.EQUALS,
                                                Integer.toString(k.value()),
                                                null))
                        .toList();
        TableTypes.Row first = null;
        long candidates = 0, scanned = 0, after = -1;
        String identity = null;
        var entry = schema.table();
        for (; ; ) {
            token.check();
            var page =
                    tables.page(
                                    new TableTypes.PageRequest(
                                            table, after, 1000, filters, identity),
                                    priority,
                                    token)
                            .join();
            entry = page.table();
            if (page.state() != TableTypes.PageState.OK) {
                var state =
                        switch (page.state()) {
                            case NOT_READABLE -> ItemClassLabelState.TABLE_NOT_READABLE;
                            case BAD_FILTER -> ItemClassLabelState.UNSUPPORTED;
                            default -> ItemClassLabelState.STALE;
                        };
                return result(
                        state,
                        sourceKeys,
                        null,
                        null,
                        null,
                        null,
                        scanned,
                        entry,
                        page.code(),
                        page.message());
            }
            identity = page.identity();
            scanned += Math.max(page.scanned(), 1);
            candidates += page.rows().size();
            if (first == null && !page.rows().isEmpty()) {
                first = page.rows().getFirst();
            }
            if (page.nextAfter() == null) {
                break;
            }
            if (scanned >= maxScanRows) {
                return result(
                        ItemClassLabelState.SCAN_LIMIT,
                        sourceKeys,
                        null,
                        null,
                        null,
                        candidates,
                        scanned,
                        entry,
                        SCAN_LIMIT_CODE,
                        "Classification scan reached its bound");
            }
            after = page.nextAfter();
        }
        boolean complete = entry.state() == TableTypes.State.READY;
        if (candidates == 0) {
            return result(
                    complete
                            ? ItemClassLabelState.NOT_FOUND
                            : ItemClassLabelState.INCOMPLETE_TARGET,
                    sourceKeys,
                    null,
                    null,
                    null,
                    0L,
                    scanned,
                    entry,
                    null,
                    null);
        }
        if (candidates > 1) {
            return result(
                    ItemClassLabelState.AMBIGUOUS,
                    sourceKeys,
                    null,
                    null,
                    null,
                    candidates,
                    scanned,
                    entry,
                    null,
                    null);
        }
        if (!complete) {
            return result(
                    ItemClassLabelState.INCOMPLETE_TARGET,
                    sourceKeys,
                    null,
                    null,
                    null,
                    1L,
                    scanned,
                    entry,
                    null,
                    null);
        }
        var row = first.row();
        String value = text < row.values().size() ? row.values().get(text) : "";
        boolean invalid = text >= row.values().size() || ItemLayout.issue(row, text);
        var state =
                invalid
                        ? ItemClassLabelState.INVALID_TEXT
                        : value.length() > MAX_TEXT_UNITS
                                ? ItemClassLabelState.UNSUPPORTED
                                : value.isEmpty()
                                        ? ItemClassLabelState.EMPTY_TEXT
                                        : ItemClassLabelState.READY;
        return result(
                state,
                sourceKeys,
                state == ItemClassLabelState.READY || state == ItemClassLabelState.EMPTY_TEXT
                        ? value
                        : null,
                first,
                text,
                1L,
                scanned,
                entry,
                state == ItemClassLabelState.UNSUPPORTED ? TEXT_LIMIT_CODE : null,
                state == ItemClassLabelState.UNSUPPORTED
                        ? "Classification text exceeds bound"
                        : null);
    }
}
