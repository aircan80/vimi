package com.vibeminer.concepts.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.effects.EffectsTypes.Query;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ItemEffectQueriesTest {
    @Test
    void malformedFramingAndNoncanonicalBase64AreBadArguments() {
        for (String after : List.of("", "bad=", "not a cursor!", "A", "AB", "a".repeat(2049))) {
            assertFalse(ItemEffectQueries.isWellFormedCursor(after), after);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemEffectQueries.validate(new Query(2, after)));
        }
        for (int limit : new int[] {0, 17, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemEffectQueries.validate(new Query(limit)));
        }
    }

    @Test
    void validBase64ButNotAnEffectsCursorIsTypedInvalidCursor() {
        String after =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("not JSON".getBytes(StandardCharsets.UTF_8));
        assertTrue(ItemEffectQueries.isWellFormedCursor(after));
        try (var handle =
                new EffectsWorld()
                        .with(
                                EffectsWorld.retail()
                                        .toArray(
                                                com.vibeminer.formats.tables.MemoryTables.FakeTable
                                                                []
                                                        ::new))
                        .open()) {
            var result =
                    handle.get(1, new Query(2, after), EffectsWorld.NOW, Cancellation.NONE).join();
            assertEquals(EffectsTypes.PageState.INVALID_CURSOR, result.state());
            assertEquals("INVALID_CURSOR", result.code());
        }
    }

    @Test
    void cursorRoundTripRequiresExactShapeOrderNumbersAndRequestBinding() {
        String binding = ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 1, 2);
        String canonical =
                ItemEffectQueries.encodeCursor(
                        new ItemEffectQueries.Cursor(binding, 2, "identity", "ItemXItemEffect"));
        for (String text :
                List.of(
                        "{\"b\":\""
                                + binding
                                + "\",\"a\":-1,\"i\":\"identity\",\"l\":\"ItemXItemEffect\"}",
                        "{\"b\":\""
                                + binding
                                + "\",\"a\":2,\"i\":\"identity\",\"l\":\"ItemXItemEffect\",\"extra\":0}",
                        "{\"a\":2,\"b\":\""
                                + binding
                                + "\",\"i\":\"identity\",\"l\":\"ItemXItemEffect\"}")) {
            String value =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
            assertNull(ItemEffectQueries.decodeCursor(value, binding));
        }
        assertNull(ItemEffectQueries.decodeCursor(canonical, "other"));
        assertEquals(
                canonical,
                ItemEffectQueries.encodeCursor(ItemEffectQueries.decodeCursor(canonical, binding)));
    }

    @Test
    void bindingIncludesBuildLocaleRegionItemAndLimit() {
        String expected = ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 1, 2);
        assertNotEquals(expected, ItemEffectQueries.binding(EffectsWorld.BUILD, "deDE", 1, 2));
        assertNotEquals(expected, ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 2, 2));
        assertNotEquals(expected, ItemEffectQueries.binding(EffectsWorld.BUILD, "enUS", 1, 3));
        assertNotEquals(
                expected,
                ItemEffectQueries.binding(
                        new BuildRef(
                                "wow",
                                EffectsWorld.BUILD.buildConfig(),
                                EffectsWorld.BUILD.cdnConfig(),
                                "eu"),
                        "enUS",
                        1,
                        2));
    }

    @Test
    void rawAndStrictNumericEvidenceRequireCanonicalAsciiCells() {
        for (String value : List.of("+1", "-0", "01", " 1", "1 ", "1.0", "1e0", "١")) {
            assertFalse(ItemEffectProfiles.signed(value, Integer.MIN_VALUE, 0xffffffffL), value);
        }
        assertTrue(ItemEffectProfiles.signed("-2147483648", Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertTrue(ItemEffectProfiles.signed("4294967295", 0, 0xffffffffL));
        assertFalse(ItemEffectProfiles.signed("4294967296", 0, 0xffffffffL));
    }
}
