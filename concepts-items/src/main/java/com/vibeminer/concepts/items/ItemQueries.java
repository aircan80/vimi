package com.vibeminer.concepts.items;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.ConceptQueries;

/** Stable pinned item literal/query binding and canonical base64url cursor policy. */
public final class ItemQueries {
    public static final String QUERY_POLICY = "ordinal-ignorecase-contains-v1",
            ORDER = "table-row-order-v1";
    public static final int MAX_LIMIT = 500,
            MAX_QUERY_SCALARS = 128,
            MAX_QUERY_UTF8_BYTES = 512,
            MAX_QUERY_LENGTH = 256,
            MAX_CURSOR_LENGTH = 2048;

    private ItemQueries() {}

    public static void validate(ItemQuery query) {
        java.util.Objects.requireNonNull(query);
        if (query.limit() < 1
                || query.limit() > MAX_LIMIT
                || !isValidQuery(query.query())
                || query.exactId() != null
                        && (query.exactId() < 0
                                || query.exactId() > 0xffffffffL
                                || !query.query().isEmpty())
                || query.after() != null && !isWellFormedCursor(query.after())) {
            throw new IllegalArgumentException("Malformed item query");
        }
    }

    public static boolean isValidQuery(String value) {
        return ConceptQueries.isValidQuery(value);
    }

    public static Long tryParseItemId(String value) {
        return ConceptQueries.tryParseId(value);
    }

    public static boolean isWellFormedCursor(String value) {
        return ConceptQueries.wellFormedCursor(value);
    }

    public static boolean matches(ItemName name, String query) {
        return query.isEmpty()
                || name.state() == ItemNameState.VALID
                        && ConceptQueries.containsOrdinalIgnoreCase(name.value(), query);
    }

    public static String binding(BuildRef build, String locale, ItemQuery query) {
        return ConceptQueries.binding(
                build, QUERY_POLICY, ORDER, locale, query.query(), query.exactId(), query.limit());
    }

    public static String encodeCursor(ItemCursor cursor) {
        return ConceptQueries.encode(
                new ConceptQueries.Cursor(cursor.b(), cursor.a(), cursor.i(), cursor.o()));
    }

    public static ItemCursor decodeCursor(String value, String binding) {
        var cursor = ConceptQueries.decode(value, binding);
        return cursor == null
                ? null
                : new ItemCursor(cursor.b(), cursor.a(), cursor.i(), cursor.o());
    }
}
