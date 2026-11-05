package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The 5 x 5 World neighborhood (issue #173) and the fixed budgets derived from it. */
final class WorldStreamNeighborhoodBudgetTest {
    @Test
    void everyProtocolBoundDerivesFromTheFiveByFiveNeighborhood() {
        assertEquals(25, WorldStreamState.MAX_ACTIVE);
        assertEquals(WorldStreamState.MAX_ACTIVE + 1, WorldStreamState.SLOTS);
        assertEquals(WorldStreamState.MAX_ACTIVE, WorldStreamTransport.MAX_RESIDENTS);
        assertEquals(WorldStreamState.SLOTS, WorldStreamTransport.MAX_PREPARED);
        assertEquals(WorldStreamState.SLOTS, BgfxWorldStreamRenderer.SLOT_COUNT);
        // A commit envelope: 72 header bytes and 16 bytes per member.
        assertTrue(72 + 16 * WorldStreamState.MAX_ACTIVE <= WorldStreamEnvelope.BYTES);
    }

    @Test
    void terrainGpuMemoryStaysFixedPerSlot() {
        long buffer =
                (long) BgfxWorldStreamRenderer.MAX_TRIANGLES
                        * BgfxWorldStreamRenderer.INSTANCE_BYTES;
        assertEquals(3_932_160, buffer);
        long atlas = WorldStreamTileFile.ATLAS_BYTES;
        long terrain = BgfxWorldStreamRenderer.SLOT_COUNT * (buffer + atlas);
        // 26 slots: 129.5 MB with 1 MiB atlases (138.6 MB once atlases carry a mip chain).
        assertEquals(129_499_136, terrain);
    }

    @Test
    void exteriorObjectsShareTheFormerTenSlotWorstCase() {
        long budget = BgfxWorldStreamRenderer.OBJECT_BUDGET_BYTES;
        assertEquals(10L * WorldStreamObjectsFile.MAX_BYTES, budget);
        assertTrue(BgfxWorldStreamRenderer.objectsFit(0, WorldStreamObjectsFile.MAX_BYTES));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(budget - 100, 100));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(budget - 100, 101));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(budget, 1));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(budget, 0));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(-1, 0));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(0, -1));
        // Ten maximum-profile sets fit; the eleventh fails alone.
        long reserved = 0;
        for (int set = 0; set < 10; set++) {
            assertTrue(
                    BgfxWorldStreamRenderer.objectsFit(reserved, WorldStreamObjectsFile.MAX_BYTES));
            reserved += WorldStreamObjectsFile.MAX_BYTES;
        }
        assertFalse(BgfxWorldStreamRenderer.objectsFit(reserved, 1));
    }
}
