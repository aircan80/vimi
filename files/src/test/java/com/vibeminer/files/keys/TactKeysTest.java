package com.vibeminer.files.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class TactKeysTest {
    @Test
    void parsesUnsignedNamesOptionalFieldsAndReturnsOwnedKeys() {
        var keys =
                TactKeys.parse(
                        ("# comment\nFFFFFFFFFFFFFFFF 00112233445566778899aabbccddeeff future-fields\n"
                                        + "ffffffffffffffff 00112233445566778899AABBCCDDEEFF\n")
                                .getBytes(StandardCharsets.US_ASCII));
        assertEquals(1, keys.count());
        byte[] first = keys.find(-1L);
        first[0] = 99;
        assertEquals(0, keys.find(-1L)[0]);
        assertEquals(1, keys.fingerprints().size());
    }

    @Test
    void refusesMalformedConflictingAndEmptyLists() {
        for (String text :
                new String[] {
                    "",
                    "# no keys",
                    "bad html",
                    "0000000000000001 0000",
                    "0000000000000001 00000000000000000000000000000000\n0000000000000001 11111111111111111111111111111111"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TactKeys.parse(text.getBytes(StandardCharsets.US_ASCII)));
        }
    }
}
