package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_GEQUAL;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_DEPTH_TEST_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Per-frame draw order of the World pass: blended sort keys and the sky's depth state. */
class WorldStreamFrameOrderTest {
    @Test
    void blendKeyRoundTripsItsSlotAndPlacement() {
        for (int slot = 0; slot < BgfxWorldStreamRenderer.SLOT_COUNT; slot++) {
            for (int placement : new int[] {0, 1, 575, 1023}) {
                long key = BgfxWorldStreamRenderer.blendKey(123.25, slot, placement);
                assertEquals(slot, BgfxWorldStreamRenderer.blendPosition(key));
                assertEquals(placement, BgfxWorldStreamRenderer.blendPlacement(key));
            }
        }
    }

    @Test
    void blendKeyRefusesWhatItCannotEncode() {
        assertThrows(
                IllegalArgumentException.class, () -> BgfxWorldStreamRenderer.blendKey(-1, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BgfxWorldStreamRenderer.blendKey(Double.NaN, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BgfxWorldStreamRenderer.blendKey(1, BgfxWorldStreamRenderer.SLOT_COUNT, 0));
        assertThrows(
                IllegalArgumentException.class, () -> BgfxWorldStreamRenderer.blendKey(1, 0, 1024));
    }

    /**
     * The primitive sort read backwards draws in the same order as the boxed stable sort it
     * replaced: farthest first, equal distances in active-position-then-placement order.
     */
    @Test
    void primitiveBlendOrderMatchesTheStableFarthestFirstOrder() {
        var random = new Random(356);
        for (int round = 0; round < 50; round++) {
            int count = 1 + random.nextInt(600);
            var slots = new int[count];
            var placements = new int[count];
            var distances = new double[count];
            for (int i = 0; i < count; i++) {
                // Entries in insertion order: active positions, then placements ascending.
                slots[i] = i * BgfxWorldStreamRenderer.SLOT_COUNT / count;
                placements[i] = i;
                // Few distinct distances, so ties are common; zero included.
                distances[i] =
                        random.nextInt(4) == 0
                                ? random.nextInt(5) * 1000.0
                                : random.nextDouble() * 4_000_000;
            }
            Integer[] expected = new Integer[count];
            for (int i = 0; i < count; i++) {
                expected[i] = i;
            }
            Arrays.sort(
                    expected, Comparator.comparingDouble((Integer i) -> distances[i]).reversed());
            var keys = new long[count];
            for (int i = 0; i < count; i++) {
                keys[i] = BgfxWorldStreamRenderer.blendKey(distances[i], slots[i], placements[i]);
            }
            Arrays.sort(keys);
            var actual = new int[count * 2];
            var wanted = new int[count * 2];
            for (int i = 0; i < count; i++) {
                long key = keys[count - 1 - i];
                actual[i * 2] = BgfxWorldStreamRenderer.blendPosition(key);
                actual[i * 2 + 1] = BgfxWorldStreamRenderer.blendPlacement(key);
                wanted[i * 2] = slots[expected[i]];
                wanted[i * 2 + 1] = placements[expected[i]];
            }
            assertArrayEquals(wanted, actual, "round " + round);
        }
    }

    @Test
    void tiesAcrossTilesFollowTheActiveOrderNotTheSlotNumbers() {
        // Producer order of the active tiles: slot 7, then slot 2, then slot 5.
        int[] activeSlots = {7, 2, 5};
        var keys = new long[activeSlots.length];
        for (int position = 0; position < activeSlots.length; position++) {
            keys[position] = BgfxWorldStreamRenderer.blendKey(250.0, position, 3);
        }
        Arrays.sort(keys);
        var drawn = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            drawn[i] =
                    activeSlots[BgfxWorldStreamRenderer.blendPosition(keys[keys.length - 1 - i])];
        }
        assertArrayEquals(activeSlots, drawn);
    }

    @Test
    void blendKeyBitsHoldEveryActivePositionAndPlacement() {
        assertTrue(
                WorldStreamObjectsFile.MAX_PLACEMENTS
                        <= 1 << BgfxWorldStreamRenderer.BLEND_PLACEMENT_BITS);
        assertTrue(
                BgfxWorldStreamRenderer.SLOT_COUNT
                        <= 1
                                << (BgfxWorldStreamRenderer.BLEND_ENTRY_BITS
                                        - BgfxWorldStreamRenderer.BLEND_PLACEMENT_BITS));
        // The largest entry round-trips.
        long key =
                BgfxWorldStreamRenderer.blendKey(
                        1,
                        BgfxWorldStreamRenderer.SLOT_COUNT - 1,
                        WorldStreamObjectsFile.MAX_PLACEMENTS - 1);
        assertEquals(
                BgfxWorldStreamRenderer.SLOT_COUNT - 1, BgfxWorldStreamRenderer.blendPosition(key));
        assertEquals(
                WorldStreamObjectsFile.MAX_PLACEMENTS - 1,
                BgfxWorldStreamRenderer.blendPlacement(key));
    }

    @Test
    void skyShadesOnlyPixelsStillAtTheClearedReversedDepth() {
        long state = BgfxWorldStreamRenderer.SKY_STATE;
        assertEquals(BGFX_STATE_DEPTH_TEST_GEQUAL, state & BGFX_STATE_DEPTH_TEST_MASK);
        assertEquals(0, state & BGFX_STATE_WRITE_Z, "the sky writes no depth");
    }
}
