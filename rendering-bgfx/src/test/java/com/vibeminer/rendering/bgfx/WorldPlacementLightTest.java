package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** Placements with their own light draw in runs of one light. */
class WorldPlacementLightTest {
    @Test
    void aModelGroupIsOrderedWorldLitFirstThenByLight() {
        // Placements 0..5: lights 2, -1, 0, 2, -1, 0.
        int[] lights = {2, -1, 0, 2, -1, 0};
        int[] order = {9, 0, 1, 2, 3, 4, 5, 9};
        BgfxWorldStreamRenderer.sortByLight(order, 1, 6, lights);
        assertArrayEquals(new int[] {9, 1, 4, 2, 5, 0, 3, 9}, order, "stable within a light");
    }

    @Test
    void aGroupWithOneLightIsUntouched() {
        int[] lights = {-1, -1, -1};
        int[] order = {2, 0, 1};
        BgfxWorldStreamRenderer.sortByLight(order, 0, 3, lights);
        assertArrayEquals(new int[] {2, 0, 1}, order);
    }
}
