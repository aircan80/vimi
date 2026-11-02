package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ItemPageLimitTest {
    @Test
    void acceptsLargerLocalPagesAndRejectsOutOfBounds() {
        for (int limit : new int[] {1, 50, 100, 500}) {
            assertDoesNotThrow(() -> ItemQueries.validate(new ItemQuery("", null, limit, null)));
        }
        for (int limit : new int[] {0, 501, Integer.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemQueries.validate(new ItemQuery("", null, limit, null)));
        }
    }
}
