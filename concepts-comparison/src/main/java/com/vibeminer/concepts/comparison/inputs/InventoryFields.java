package com.vibeminer.concepts.comparison.inputs;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import java.util.List;
import java.util.Locale;

/** Canonical inventory metadata only; these helpers never admit game-row semantics. */
final class InventoryFields {
    private InventoryFields() {}

    static String key(long id, boolean zeroAllowed) {
        if (id < (zeroAllowed ? 0 : 1) || id > 0xffffffffL) {
            throw new IllegalArgumentException("Inventory FileDataID is outside unsigned32 bounds");
        }
        return String.format(Locale.ROOT, "%020d", id);
    }

    static ComparisonTypes.Field field(
            String meaning,
            ComparisonTypes.ValueKind kind,
            ComparisonTypes.ValueState state,
            String value,
            ComparisonTypes.Aspect aspect,
            String source,
            String identity,
            String column) {
        if (value != null && value.length() > 8192) {
            state = ComparisonTypes.ValueState.LIMITED;
            value = null;
        }
        return new ComparisonTypes.Field(
                meaning,
                kind,
                state,
                value,
                aspect,
                List.of(new ComparisonTypes.Evidence(source, identity, column, value)));
    }

    static ComparisonTypes.Field text(
            String meaning,
            String value,
            ComparisonTypes.Aspect aspect,
            String source,
            String identity,
            String column) {
        return field(
                meaning,
                ComparisonTypes.ValueKind.TEXT,
                value == null
                        ? ComparisonTypes.ValueState.UNKNOWN
                        : ComparisonTypes.ValueState.VALUE,
                value,
                aspect,
                source,
                identity,
                column);
    }

    static ComparisonTypes.Field number(
            String meaning,
            Long value,
            ComparisonTypes.Aspect aspect,
            String source,
            String identity,
            String column) {
        return field(
                meaning,
                ComparisonTypes.ValueKind.INTEGER,
                value == null
                        ? ComparisonTypes.ValueState.UNKNOWN
                        : value < 0
                                ? ComparisonTypes.ValueState.INVALID
                                : ComparisonTypes.ValueState.VALUE,
                value == null || value < 0 ? null : Long.toString(value),
                aspect,
                source,
                identity,
                column);
    }

    static ComparisonTypes.Field contentKey(String value, String source, String identity) {
        var state =
                value == null
                        ? ComparisonTypes.ValueState.UNKNOWN
                        : value.matches("[0-9a-fA-F]{32}")
                                ? ComparisonTypes.ValueState.VALUE
                                : ComparisonTypes.ValueState.INVALID;
        return field(
                "decoded-content-key-md5-v1",
                ComparisonTypes.ValueKind.BYTES_IDENTITY,
                state,
                state == ComparisonTypes.ValueState.VALUE ? value.toLowerCase(Locale.ROOT) : null,
                ComparisonTypes.Aspect.CONTENT,
                source,
                identity,
                "contentKey");
    }

    /**
     * Length prefixes distinguish missing values, separators and empty strings without guessing.
     */
    static String metadata(Object... values) {
        var result = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                result.append("-1:");
            } else {
                String text = value.toString();
                if (text.length() > 8192 || result.length() + text.length() + 16 > 8192) {
                    return null;
                }
                result.append(text.length()).append(':').append(text);
            }
        }
        return result.toString();
    }
}
