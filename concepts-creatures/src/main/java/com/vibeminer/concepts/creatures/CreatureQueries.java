package com.vibeminer.concepts.creatures;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.core.ConceptQueries;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureName;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureNameState;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import java.util.Objects;

public final class CreatureQueries {
    public static final String QUERY_POLICY = "primary-name-ordinal-ignorecase-contains-v1",
            ORDER = "table-row-order-v1";
    public static final int MAX_LIMIT = 500;

    private CreatureQueries() {}

    public static void validate(CreatureQuery query) {
        Objects.requireNonNull(query);
        if (query.limit() < 1
                || query.limit() > MAX_LIMIT
                || !ConceptQueries.isValidQuery(query.query())
                || query.exactId() != null
                        && (query.exactId() < 0
                                || query.exactId() > 0xffffffffL
                                || !query.query().isEmpty())
                || query.after() != null && !ConceptQueries.wellFormedCursor(query.after())) {
            throw new IllegalArgumentException("Invalid creature query");
        }
    }

    public static boolean matches(CreatureName name, String query) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(query);
        return query.isEmpty()
                || name.state() == CreatureNameState.VALID
                        && ConceptQueries.containsOrdinalIgnoreCase(name.value(), query);
    }

    public static String binding(BuildRef build, String locale, CreatureQuery query) {
        return ConceptQueries.binding(
                build, QUERY_POLICY, ORDER, locale, query.query(), query.exactId(), query.limit());
    }

    public static String encodeCursor(String binding, long after, String identity) {
        return ConceptQueries.encode(new ConceptQueries.Cursor(binding, after, identity, null));
    }

    public static ConceptQueries.Cursor decodeCursor(String value, String binding) {
        var cursor = ConceptQueries.decode(value, binding);
        return cursor == null || cursor.o() != null ? null : cursor;
    }
}
