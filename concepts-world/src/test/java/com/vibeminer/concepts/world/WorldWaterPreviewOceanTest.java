package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** #171: the client's ocean rule, LiquidObject 42 or LiquidType 14. */
class WorldWaterPreviewOceanTest {
    @Test
    void liquidObject42OrLiquidType14IsOceanAndAnythingElseIsRiver() {
        assertTrue(WorldWaterPreview.ocean(42, 0), "LiquidObject 42 alone");
        assertTrue(WorldWaterPreview.ocean(42, 2), "LiquidObject 42 with an ocean-less type");
        assertTrue(WorldWaterPreview.ocean(0, 14), "LiquidType 14 alone");
        assertTrue(WorldWaterPreview.ocean(41, 14), "LiquidType 14 with another object");
        assertTrue(WorldWaterPreview.ocean(42, 14), "both");
        for (int object : new int[] {0, 1, 41, 43, 14}) {
            for (int type : new int[] {0, 1, 2, 13, 15, 42}) {
                assertFalse(
                        WorldWaterPreview.ocean(object, type),
                        "LiquidObject " + object + ", LiquidType " + type + " is river or lake");
            }
        }
    }
}
