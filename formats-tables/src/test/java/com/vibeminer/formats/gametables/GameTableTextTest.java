package com.vibeminer.formats.gametables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GameTableTextTest {
    private static GameTableText parse(String text) {
        return GameTableText.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void retainsSourceTokensAndJoinsExplicitLevel() {
        var parsed =
                parse(
                        "\ufeffLevel\tArmorMultiplier\tWeaponMultiplier\r\n60\t2.000\t1.5\r\n1\t1\t1\r\n");
        var cell = parsed.cell(60, "ArmorMultiplier");
        assertEquals("2.000", cell.text());
        assertEquals(2f, cell.value());
        assertEquals(60, cell.row());
        assertEquals(1, cell.index());
        assertNull(parsed.cell(50, "ArmorMultiplier"));
        assertNull(parsed.cell(60, "Unknown"));
    }

    @Test
    void rejectsAmbiguousMalformedAndUnboundedInputs() {
        for (String text :
                new String[] {
                    "Level Armor Armor\n1 2 3",
                    "Level Armor\n1 2\n1 3",
                    "Level Armor\n1 NaN",
                    "Level Armor\n1 Infinity",
                    "Level Armor\n1",
                    "NotALevel Armor\n1 2"
                }) {
            assertThrows(IllegalArgumentException.class, () -> parse(text));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> GameTableText.parse(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(
                IllegalArgumentException.class, () -> GameTableText.parse(new byte[(8 << 20) + 1]));
    }
}
