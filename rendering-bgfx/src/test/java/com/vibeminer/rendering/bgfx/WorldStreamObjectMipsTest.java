package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.ObjectTextureMips;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldStreamObjectMipsTest {
    private static final int BUDGET = BgfxWorldStreamRenderer.OBJECT_CHUNK;

    /** Every byte of the chain once, in level and row order, each step within the budget. */
    private static void assertCovers(
            int width, int height, int budget, List<List<WorldStreamObjectMips.Slice>> plan) {
        int levels = ObjectTextureMips.levels(width, height);
        int level = 0, row = 0, offset = 0;
        for (var step : plan) {
            int bytes = 0;
            assertTrue(!step.isEmpty());
            for (var slice : step) {
                if (row == ObjectTextureMips.levelWidth(height, level)) {
                    level++;
                    row = 0;
                }
                int pitch = ObjectTextureMips.levelWidth(width, slice.level()) * 4;
                assertEquals(level, slice.level(), "levels in order");
                assertEquals(row, slice.row(), "rows in order, no gaps");
                assertTrue(slice.rows() > 0);
                assertEquals(slice.rows() * pitch, slice.bytes());
                assertEquals(offset, slice.offset(), "contiguous in the packed chain");
                assertEquals(
                        ObjectTextureMips.levelOffset(width, height, level) + row * pitch,
                        slice.offset());
                row += slice.rows();
                offset += slice.bytes();
                bytes += slice.bytes();
            }
            assertTrue(bytes > 0 && bytes <= budget, width + "x" + height + " step " + bytes);
        }
        assertEquals(levels - 1, level);
        assertEquals(ObjectTextureMips.levelWidth(height, level), row);
        assertEquals(ObjectTextureMips.chainPixels(width, height) * 4, offset);
    }

    @Test
    void everyProfileImageIsCoveredOnceWithinTheObjectStepBound() {
        int pairs = 0;
        for (int width = 1; width <= WorldStreamObjectsFile.MAX_IMAGE_SIDE; width *= 2) {
            for (int height = 1; height <= WorldStreamObjectsFile.MAX_IMAGE_SIDE; height *= 2) {
                assertCovers(
                        width, height, BUDGET, WorldStreamObjectMips.plan(width, height, BUDGET));
                pairs++;
            }
        }
        assertEquals(81, pairs);
        assertTrue(BUDGET <= BgfxWorldStreamRenderer.STEP_BOUND);
    }

    @Test
    void largestImageTakesTwoStepsAndSmallerOnesOne() {
        var largest = WorldStreamObjectMips.plan(256, 256, BUDGET);
        assertEquals(2, largest.size());
        assertEquals(262_144, largest.get(0).stream().mapToInt(s -> s.bytes()).sum());
        assertEquals(87_380, largest.get(1).stream().mapToInt(s -> s.bytes()).sum());
        assertEquals(1, WorldStreamObjectMips.plan(128, 128, BUDGET).size());
        var wide = WorldStreamObjectMips.plan(256, 64, BUDGET);
        assertEquals(1, wide.size());
        assertEquals(9, wide.get(0).size());
    }

    @Test
    void smallBudgetRowBandsTheTopLevel() {
        var plan = WorldStreamObjectMips.plan(256, 256, 4096);
        assertCovers(256, 256, 4096, plan);
        // Four 1 KiB rows per step.
        assertEquals(4, plan.get(0).get(0).rows());
        assertTrue(plan.size() > 64);
    }

    @Test
    void budgetBelowOneRowIsRefused() {
        assertThrows(
                IllegalArgumentException.class, () -> WorldStreamObjectMips.plan(256, 4, 1023));
    }
}
