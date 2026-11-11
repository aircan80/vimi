package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ItemModelFilesTest {
    private static ItemResourceFile file(long id, long race, long gender, long position) {
        return new ItemResourceFile(
                id,
                null,
                new ItemComponentKey(
                        ItemRefState.FOUND,
                        race,
                        gender,
                        0L,
                        position,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    @Test
    void theNpcsOwnRaceAndSexComeFirstThenAnyRaceOrEitherSex() {
        var files =
                List.of(
                        file(30, 0, 2, -1), // any race, either sex
                        file(20, 1, 2, -1), // human, either sex
                        file(10, 1, 0, -1), // human male
                        file(11, 1, 1, -1), // human female
                        file(40, 3, 0, -1)); // dwarf male
        assertEquals(10L, ItemModelFiles.componentFile(files, 1, 0, -1));
        assertEquals(11L, ItemModelFiles.componentFile(files, 1, 1, -1));
        assertEquals(30L, ItemModelFiles.componentFile(files, 4, 0, -1));
        assertEquals(40L, ItemModelFiles.componentFile(files, 3, 0, -1));
        // The position must match: a helmet records -1, shoulders 0 and 1.
        assertNull(ItemModelFiles.componentFile(files, 1, 0, 0));
        var shoulders = List.of(file(51, 0, 2, 1), file(50, 0, 2, 0));
        assertEquals(50L, ItemModelFiles.componentFile(shoulders, 1, 0, 0));
        assertEquals(51L, ItemModelFiles.componentFile(shoulders, 1, 0, 1));
        // Equal ranks: the lowest file ID.
        assertEquals(
                60L,
                ItemModelFiles.componentFile(
                        List.of(file(61, 1, 0, -1), file(60, 1, 0, -1)), 1, 0, -1));
    }
}
