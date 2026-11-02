package com.vibeminer.concepts.creatures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CreaturePageLimitTest {
    @Test
    void acceptsLargerLocalPagesAndRejectsOutOfBounds() {
        for (int limit : new int[] {1, 50, 100, 500}) {
            assertDoesNotThrow(
                    () ->
                            CreatureQueries.validate(
                                    new CreatureTypes.CreatureQuery("", null, limit, null)));
        }
        for (int limit : new int[] {0, 501, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            CreatureQueries.validate(
                                    new CreatureTypes.CreatureQuery("", null, limit, null)));
        }
    }
}
