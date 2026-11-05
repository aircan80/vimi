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
    void viewDistancesUseFewerSlotsOfTheSameSizeUnderTheCeiling() {
        var near = WorldStreamTransport.ViewDistance.NEAR;
        var far = WorldStreamTransport.ViewDistance.FAR;
        assertEquals(1, near.radius());
        assertEquals(9, near.residents());
        assertEquals(10, near.slots());
        assertEquals(2, far.radius());
        assertEquals(WorldStreamState.MAX_ACTIVE, far.residents());
        assertEquals(BgfxWorldStreamRenderer.SLOT_COUNT, far.slots());
        for (var distance : WorldStreamTransport.ViewDistance.values()) {
            int side = 2 * distance.radius() + 1;
            assertEquals(side * side, distance.residents());
            var session =
                    new WorldStreamEnvelope.Session(
                            new WorldStreamState.Nonce(1, 2),
                            "ab".repeat(32),
                            1,
                            false,
                            distance.residents());
            assertEquals(distance.slots(), session.slots());
        }
        // Default specs and sessions stay near: safe on integrated GPUs.
        assertEquals(
                near,
                new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.DEFAULT)
                        .viewDistance());
        assertEquals(
                9,
                new WorldStreamEnvelope.Session(
                                new WorldStreamState.Nonce(1, 2), "ab".repeat(32), 1)
                        .maxActive());
    }

    @Test
    void stateRefusesTablesAboveItsSessionBound() {
        var nonce = new WorldStreamState.Nonce(3, 4);
        var near = new WorldStreamState(nonce, 9);
        var table = new java.util.ArrayList<WorldStreamState.Lease>();
        for (int slot = 0; slot < 10; slot++) {
            table.add(new WorldStreamState.Lease(slot, 1, slot + 1));
        }
        assertEquals(WorldStreamState.Result.INVALID_TABLE, near.commit(nonce, 1, 0, 1, table));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new WorldStreamState(nonce, 26));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new WorldStreamState(nonce, 0));
    }

    @Test
    void terrainGpuMemoryStaysFixedPerSlot() {
        long buffer =
                (long) BgfxWorldStreamRenderer.MAX_TRIANGLES
                        * BgfxWorldStreamRenderer.INSTANCE_BYTES;
        assertEquals(3_932_160, buffer);
        long atlas = WorldStreamAtlasMips.CHAIN_BYTES;
        long terrain = BgfxWorldStreamRenderer.SLOT_COUNT * (buffer + atlas);
        // FAR, 26 slots: 138.6 MB (5.33 MB per slot with the atlas mip chain); NEAR, 10: 53.3 MB.
        assertEquals(138_586_760, terrain);
        assertEquals(53_302_600, WorldStreamTransport.ViewDistance.NEAR.slots() * (buffer + atlas));
    }

    @Test
    void objectTextureTiersSelectAPinnedSessionBudget() {
        var standard = WorldStreamTransport.ObjectTextures.STANDARD;
        var high = WorldStreamTransport.ObjectTextures.HIGH;
        assertEquals(160L << 20, standard.budgetBytes());
        assertEquals(256L << 20, high.budgetBytes());
        assertEquals(standard.budgetBytes(), BgfxWorldStreamRenderer.OBJECT_BUDGET_BYTES);
        assertEquals(
                standard,
                new WorldStreamTransport.Spec(
                                "ab".repeat(32), 1, WorldStreamTransport.Backend.DEFAULT)
                        .objectTextures());
        // Measured real-data means per tile object set with #286 mip chains (40 Elwynn tiles):
        // STANDARD 6.87 MiB, HIGH 9.72 MiB.
        long standardMean = (long) (6.87 * (1 << 20)), highMean = (long) (9.72 * (1 << 20));
        long near = WorldStreamTransport.ViewDistance.NEAR.residents();
        long far = WorldStreamTransport.ViewDistance.FAR.residents();
        // NEAR fits either tier; FAR + HIGH fits the HIGH budget.
        assertTrue(
                BgfxWorldStreamRenderer.objectsFit(standard.budgetBytes(), 0, near * standardMean));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(high.budgetBytes(), 0, near * highMean));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(high.budgetBytes(), 0, far * highMean));
        // Documented degradation: FAR + STANDARD at the mean (about 172 MiB) exceeds 160 MiB, so
        // some outer-ring sets report FAILED and keep their terrain (objects load nearest-first).
        assertFalse(
                BgfxWorldStreamRenderer.objectsFit(standard.budgetBytes(), 0, far * standardMean));
    }

    @Test
    void exteriorObjectsShareTheFormerTenSlotWorstCase() {
        long budget = BgfxWorldStreamRenderer.OBJECT_BUDGET_BYTES;
        // Pinned to main's former ten-slot worst case, independent of later per-set limits.
        assertEquals(160L << 20, budget);
        assertTrue(WorldStreamObjectsFile.MAX_BYTES <= budget);
        assertTrue(BgfxWorldStreamRenderer.objectsFit(0, WorldStreamObjectsFile.MAX_BYTES));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(budget - 100, 100));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(budget - 100, 101));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(budget, 1));
        assertTrue(BgfxWorldStreamRenderer.objectsFit(budget, 0));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(-1, 0));
        assertFalse(BgfxWorldStreamRenderer.objectsFit(0, -1));
        // Ten 16 MiB sets (main's per-set maximum) fit; the eleventh fails alone.
        long reserved = 0;
        for (int set = 0; set < 10; set++) {
            assertTrue(BgfxWorldStreamRenderer.objectsFit(reserved, 16L << 20));
            reserved += 16L << 20;
        }
        assertFalse(BgfxWorldStreamRenderer.objectsFit(reserved, 1));
    }
}
