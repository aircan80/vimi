package com.vibeminer.concepts.items;

import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.formats.tables.TableQueries;
import com.vibeminer.formats.tables.TableTypes.RowOutcome;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

record ItemLayout(
        List<Db2Types.Column> itemColumns,
        int[] itemFields,
        int icon,
        List<Db2Types.Column> sparseColumns,
        int[] sparseFields,
        int name) {
    static final String ITEM = "Item", SPARSE = "ItemSparse", NAME_FIELD = "Display_lang";

    static ItemLayout resolve(List<Db2Types.Column> item, List<Db2Types.Column> sparse) {
        var itemFields = fields(item, List.of("ClassID", "SubclassID", "InventoryType"));
        int icon = column(item, "IconFileDataID", "integer");
        if (sparse == null) {
            return new ItemLayout(item, itemFields, icon, null, new int[0], -1);
        }
        int name = column(sparse, NAME_FIELD, "string");
        if (name < 0) {
            throw new ItemsSourceException(
                    ItemsSourceException.NAME_UNSUPPORTED,
                    "ItemSparse has no plain Display_lang text column");
        }
        return new ItemLayout(
                item,
                itemFields,
                icon,
                sparse,
                fields(sparse, List.of("OverallQualityID", "ItemLevel", "InventoryType")),
                name);
    }

    private static int[] fields(List<Db2Types.Column> columns, List<String> names) {
        return names.stream()
                .mapToInt(name -> column(columns, name, "integer"))
                .filter(index -> index >= 0)
                .toArray();
    }

    static int column(List<Db2Types.Column> columns, String name, String kind) {
        int found = -1;
        for (int i = 0; i < columns.size(); i++) {
            var c = columns.get(i);
            if (!c.name().equals(name) || !c.kind().equals(kind) || c.interpretation() != null) {
                continue;
            }
            if (found >= 0) {
                throw new ItemsSourceException(
                        ItemsSourceException.SCHEMA_AMBIGUOUS, "Ambiguous item column " + name);
            }
            found = i;
        }
        return found;
    }

    ItemName nameOf(Db2Types.Row row) {
        String value = cell(row, name);
        return projectedName(value, issue(row, name));
    }

    static ItemName projectedName(String value, boolean invalid) {
        if (invalid) {
            return new ItemName(ItemNameState.INVALID, null);
        }
        return value.isEmpty()
                ? new ItemName(ItemNameState.EMPTY, "")
                : new ItemName(ItemNameState.VALID, value);
    }

    record SparseValue(ItemName name, List<ItemSourceValue> values) {
        SparseValue {
            values = List.copyOf(values);
        }
    }

    SparseValue sparseValue(Db2Types.Row sparse) {
        var values = new ArrayList<ItemSourceValue>();
        var name = nameAndValues(sparse, RowOutcome.FOUND, values);
        return new SparseValue(name, values);
    }

    ItemSummary summary(Db2Types.Row item, Db2Types.Row sparse, RowOutcome outcome, String key) {
        return projected(item, sparse == null ? null : sparseValue(sparse), outcome, key);
    }

    ItemSummary projected(Db2Types.Row item, SparseValue sparse, RowOutcome outcome, String key) {
        var values = new ArrayList<ItemSourceValue>();
        for (int i : itemFields) {
            values.add(new ItemSourceValue(ITEM, itemColumns.get(i).name(), i, integer(item, i)));
        }
        ItemName name;
        if (sparse == null) {
            name = nameAndValues(null, outcome, values);
        } else {
            name = sparse.name();
            values.addAll(sparse.values());
        }
        return new ItemSummary(
                item.id(),
                item.isCopy(),
                item.copySourceId(),
                name,
                values,
                icon < 0 ? null : iconOf(cell(item, icon)),
                sparse == null && outcome == RowOutcome.PROTECTED ? key : null,
                ItemRowState.FOUND,
                null);
    }

    ItemSummary unread(
            long id,
            RowOutcome item,
            String itemKey,
            Db2Types.Row sparse,
            RowOutcome outcome,
            String sparseKey) {
        var values = new ArrayList<ItemSourceValue>();
        var n = nameAndValues(sparse, outcome, values);
        var state = item == RowOutcome.PROTECTED ? ItemRowState.PROTECTED : ItemRowState.UNKNOWN;
        return new ItemSummary(
                id,
                sparse != null && sparse.isCopy(),
                sparse == null ? null : sparse.copySourceId(),
                n,
                values,
                null,
                sparse == null && outcome == RowOutcome.PROTECTED ? sparseKey : null,
                state,
                state == ItemRowState.PROTECTED ? itemKey : null);
    }

    private ItemName nameAndValues(
            Db2Types.Row sparse, RowOutcome outcome, List<ItemSourceValue> values) {
        if (sparse != null) {
            var n = nameOf(sparse);
            for (int i : sparseFields) {
                values.add(
                        new ItemSourceValue(
                                SPARSE, sparseColumns.get(i).name(), i, integer(sparse, i)));
            }
            return n;
        }
        return new ItemName(
                switch (outcome) {
                    case ABSENT -> ItemNameState.MISSING_SPARSE;
                    case PROTECTED -> ItemNameState.SPARSE_PROTECTED;
                    default -> ItemNameState.SPARSE_UNKNOWN;
                },
                null);
    }

    static boolean issue(Db2Types.Row row, int index) {
        return row.textIssues() != null
                && row.textIssues().stream().anyMatch(i -> i.columnIndex() == index);
    }

    static String cell(Db2Types.Row row, int index) {
        if (index < 0 || index >= row.values().size()) {
            throw new ItemsSourceException(
                    ItemsSourceException.SOURCE_INVALID, "Source row differs from header");
        }
        return row.values().get(index);
    }

    private static String integer(Db2Types.Row row, int index) {
        String value = cell(row, index);
        if (!canonical(value)) {
            throw new ItemsSourceException(
                    ItemsSourceException.SOURCE_INVALID,
                    "Summary field is not canonical int64/uint64");
        }
        return value;
    }

    static boolean canonical(String value) {
        var n = TableQueries.parseInteger(value);
        return n != null
                && n.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0
                && n.compareTo(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)) <= 0;
    }

    private static Long iconOf(String value) {
        if (!canonical(value)) {
            throw new ItemsSourceException(
                    ItemsSourceException.SOURCE_INVALID, "Icon cell is not canonical integer");
        }
        var n = new BigInteger(value);
        return n.signum() >= 0 && n.compareTo(BigInteger.valueOf(0xffffffffL)) <= 0
                ? n.longValue()
                : null;
    }
}
