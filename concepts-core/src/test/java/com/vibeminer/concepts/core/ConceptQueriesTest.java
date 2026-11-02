package com.vibeminer.concepts.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.internal.Json;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ConceptQueriesTest {
    @Test
    void originalCsharpUnicodeQueriesBindingsAndCursorBytesMatch() throws IOException {
        Map<String, Object> root;
        try (var in = getClass().getResourceAsStream("/golden/queries.json")) {
            root =
                    Json.object(
                            Json.parse(java.util.Objects.requireNonNull(in).readAllBytes(), 10000));
        }
        for (Object item : Json.array(root.get("matches"))) {
            var row = Json.object(item);
            assertEquals(
                    row.get("matches"),
                    ConceptQueries.containsOrdinalIgnoreCase(
                            Json.string(row.get("text")), Json.string(row.get("query"))),
                    row.toString());
        }
        for (Object item : Json.array(root.get("validation"))) {
            var row = Json.object(item);
            String units = Json.string(row.get("units"));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < units.length(); i += 4) {
                text.append((char) Integer.parseInt(units.substring(i, i + 4), 16));
            }
            assertEquals(row.get("valid"), ConceptQueries.isValidQuery(text.toString()), units);
        }
        var build = new BuildRef("wow", "a".repeat(32), "b".repeat(32), "us");
        for (Object item : Json.array(root.get("cursors"))) {
            var row = Json.object(item);
            String query = Json.string(row.get("query"));
            String binding =
                    ConceptQueries.binding(
                            build,
                            "ordinal-ignorecase-contains-v1",
                            "table-row-order-v1",
                            "enUS",
                            query,
                            null,
                            17);
            assertEquals(row.get("binding"), binding);
            assertEquals(
                    row.get("creatureBinding"),
                    ConceptQueries.binding(
                            build,
                            "primary-name-ordinal-ignorecase-contains-v1",
                            "table-row-order-v1",
                            "enUS",
                            query,
                            null,
                            17));
            var cursor = new ConceptQueries.Cursor(binding, 7, "f".repeat(64), "c".repeat(32));
            assertEquals(row.get("cursor"), ConceptQueries.encode(cursor));
            assertEquals(cursor, ConceptQueries.decode(ConceptQueries.encode(cursor), binding));
        }
    }

    @Test
    void framingIdsAndRequestBoundariesStayStrict() {
        assertEquals(0L, ConceptQueries.tryParseId("0"));
        assertEquals(4294967295L, ConceptQueries.tryParseId("4294967295"));
        for (String text : List.of("01", "-0", "+1", "4294967296", " 1", "1 ", "1.0")) {
            assertNull(ConceptQueries.tryParseId(text));
        }
        for (String text : List.of("", "A", "AA=", "AB", "AA/", "_", " ")) {
            assertFalse(ConceptQueries.wellFormedCursor(text), text);
        }
        assertTrue(ConceptQueries.wellFormedCursor("AA"));
        String encoded = ConceptQueries.encode(new ConceptQueries.Cursor("bound", 0, null, null));
        assertNull(ConceptQueries.decode(encoded, "other"));
        assertNull(
                ConceptQueries.decode(
                        ConceptQueries.encode(new ConceptQueries.Cursor("bound", -1, null, null)),
                        "bound"));
        assertFalse(ConceptQueries.isValidQuery(null));
        assertFalse(ConceptQueries.isValidQuery("\ud800"));
    }
}
