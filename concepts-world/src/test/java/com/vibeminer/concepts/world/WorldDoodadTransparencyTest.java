package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Doodad batches whose texture weight is 0 at the Stand sample are left out. */
class WorldDoodadTransparencyTest {
    private static final float NAN = Float.NaN;

    @Test
    void aBatchWhoseWeightTrackIsZeroIsTransparent() {
        // Lookup: combo 0 names track 1, combo 1 names track 0.
        var lookup = List.of(1, 0);
        float[] weights = {1f, 0f};
        assertTrue(WorldExteriorObjects.transparent(lookup, 0, weights));
        assertFalse(WorldExteriorObjects.transparent(lookup, 1, weights));
    }

    @Test
    void anUndecidedOrPartialWeightIsDrawnAsBefore() {
        var lookup = List.of(0, 1);
        float[] weights = {NAN, 0.25f};
        assertFalse(WorldExteriorObjects.transparent(lookup, 0, weights), "undecided");
        assertFalse(WorldExteriorObjects.transparent(lookup, 1, weights), "partial");
    }

    @Test
    void noTrackOrNoWeightsIsDrawn() {
        assertFalse(WorldExteriorObjects.transparent(List.of(65535), 0, new float[] {0f}));
        assertFalse(WorldExteriorObjects.transparent(List.of(0), 0, null), "Stand not read");
        assertFalse(WorldExteriorObjects.transparent(List.of(0), 1, new float[] {0f}), "combo");
        assertFalse(WorldExteriorObjects.transparent(List.of(0), -1, new float[] {0f}));
        assertFalse(WorldExteriorObjects.transparent(List.of(3), 0, new float[] {0f}), "track");
    }
}
