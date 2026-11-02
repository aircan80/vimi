package com.vibeminer.formats.definitions;

import com.vibeminer.formats.definitions.DbdTypes.Block;
import com.vibeminer.formats.definitions.DbdTypes.Field;
import java.util.ArrayList;
import java.util.List;

/** Typed selected DBD block; equality includes fields, layouts, locale and matching provenance. */
public record TableSchema(
        List<SchemaColumn> columns,
        List<String> layoutHashes,
        SchemaMatch match,
        WowLocale locale,
        String comment) {
    public enum SchemaMatch {
        BUILD,
        LAYOUT
    }

    public enum ColumnKind {
        INT,
        FLOAT,
        STRING,
        LOCSTRING
    }

    public enum KeyStorage {
        NONE,
        INLINE,
        NONINLINE
    }

    public record ForeignKey(String table, String column) {}

    public record SchemaColumn(
            String name,
            ColumnKind kind,
            Integer bits,
            Boolean unsigned,
            int arrayLength,
            ForeignKey foreignKey,
            KeyStorage id,
            KeyStorage relation,
            boolean verifiedName,
            WowLocale locale,
            String columnComment,
            String fieldComment) {
        public boolean isArray() {
            return arrayLength > 1;
        }
    }

    public TableSchema {
        columns = List.copyOf(columns);
        layoutHashes = List.copyOf(layoutHashes);
        java.util.Objects.requireNonNull(match);
        java.util.Objects.requireNonNull(locale);
    }

    public static TableSchema fromBlock(Block block, SchemaMatch match, WowLocale locale) {
        var columns = new ArrayList<SchemaColumn>();
        for (var field : block.fields()) {
            columns.add(column(field, locale));
        }
        return new TableSchema(columns, block.layoutHashes(), match, locale, block.comment());
    }

    private static SchemaColumn column(Field field, WowLocale locale) {
        var c = field.column();
        ColumnKind kind =
                switch (c.family()) {
                    case "int" -> ColumnKind.INT;
                    case "float" -> ColumnKind.FLOAT;
                    case "string" -> ColumnKind.STRING;
                    case "locstring" -> ColumnKind.LOCSTRING;
                    default -> throw refuse(c.name(), "Unknown type family");
                };
        if (kind == ColumnKind.INT && (field.integerBits() == null || field.unsigned() == null)) {
            throw refuse(c.name(), "Integer width required");
        }
        if (kind != ColumnKind.INT && (field.integerBits() != null || field.unsigned() != null)) {
            throw refuse(c.name(), "Only integers have widths");
        }
        if (field.arrayLength() < 1) {
            throw refuse(c.name(), "Positive array length required");
        }
        if (c.reference() != null && kind != ColumnKind.INT) {
            throw refuse(c.name(), "Only integers can be foreign keys");
        }
        if (field.isId() || field.relationStorage()) {
            if (field.isId() && field.relationStorage()
                    || kind != ColumnKind.INT
                    || field.arrayLength() != 1) {
                throw refuse(c.name(), "Id/relation must be separate scalar integers");
            }
        } else if (field.nonInline()) {
            throw refuse(c.name(), "Noninline needs id/relation");
        }
        var storage = field.nonInline() ? KeyStorage.NONINLINE : KeyStorage.INLINE;
        String own = field.comment();
        if (c.comment() != null && own != null) {
            if (own.equals(c.comment())) {
                own = null;
            } else if (own.startsWith(c.comment() + "\n")) {
                own = own.substring(c.comment().length() + 1);
            }
        }
        return new SchemaColumn(
                c.name(),
                kind,
                field.integerBits(),
                field.unsigned(),
                field.arrayLength(),
                c.reference() == null
                        ? null
                        : new ForeignKey(c.reference().table(), c.reference().column()),
                field.isId() ? storage : KeyStorage.NONE,
                field.relationStorage() ? storage : KeyStorage.NONE,
                c.verifiedName(),
                kind == ColumnKind.LOCSTRING ? locale : null,
                c.comment(),
                own);
    }

    private static DefinitionException refuse(String field, String reason) {
        return new DefinitionException("DEFINITION_BLOCK_UNSUPPORTED", field + ": " + reason);
    }
}
