package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The interior light of set doodads, from WebWowViewerCpp's applyLightingParamsToDoodad. */
class WorldDoodadLightTest {
    /** R, G, B, alpha 255, stored B, G, R, A. */
    private static int colour(int r, int g, int b) {
        return 0xff00_0000 | r << 16 | g << 8 | b;
    }

    private static final long AMBIENT = colour(0x0b, 0x0c, 0x0d) & 0xffff_ffffL;

    private static float[] direct(WorldExteriorObjects.DoodadLight l) {
        return new float[] {l.directR(), l.directG(), l.directB()};
    }

    private static float[] ambient(WorldExteriorObjects.DoodadLight l) {
        return new float[] {l.ambientR(), l.ambientG(), l.ambientB()};
    }

    private static float[] toward(WorldExteriorObjects.DoodadLight l) {
        return new float[] {l.towardX(), l.towardY(), l.towardZ()};
    }

    @Test
    void aPlainDoodadIsLitByItsColourRaisedToADirectFloor() {
        // Largest channel 0x30: the ambient keeps it (under 0x60), the direct light is raised so
        // its largest channel is 0x70, along the default interior direction.
        var l = WorldExteriorObjects.doodadLight(0, colour(0x30, 0x20, 0x10), AMBIENT, null);
        assertArrayEquals(new float[] {0x30 / 255f, 0x20 / 255f, 0x10 / 255f}, ambient(l), 1e-6f);
        float k = 0x70 / (float) 0x30;
        assertArrayEquals(
                new float[] {0x30 * k / 255f, 0x20 * k / 255f, 0x10 * k / 255f}, direct(l), 1e-6f);
        double[] t = WorldExteriorObjects.INTERIOR_TOWARD;
        assertArrayEquals(new float[] {(float) t[0], (float) t[1], (float) t[2]}, toward(l));
    }

    @Test
    void aBrightColourCapsTheAmbientAndKeepsTheDirectLight() {
        var l = WorldExteriorObjects.doodadLight(0, colour(0xff, 0x80, 0x00), AMBIENT, null);
        float k = 0x60 / 255f;
        assertArrayEquals(new float[] {k, k * 0x80 / 255f, 0}, ambient(l), 1e-6f);
        assertArrayEquals(new float[] {1, 0x80 / 255f, 0}, direct(l), 1e-6f);
    }

    @Test
    void blackStaysBlack() {
        var l = WorldExteriorObjects.doodadLight(0, colour(0, 0, 0), AMBIENT, null);
        assertArrayEquals(new float[] {0, 0, 0}, ambient(l));
        assertArrayEquals(new float[] {0, 0, 0}, direct(l));
    }

    @Test
    void interiorLightingTakesTheBuildingAmbientAndTheColourTowardTheGroup() {
        double[] toward = {0, 0, 1};
        var l = WorldExteriorObjects.doodadLight(0x2, colour(0x30, 0x20, 0x10), AMBIENT, toward);
        assertArrayEquals(new float[] {0x0b / 255f, 0x0c / 255f, 0x0d / 255f}, ambient(l), 1e-6f);
        assertArrayEquals(new float[] {0x30 / 255f, 0x20 / 255f, 0x10 / 255f}, direct(l), 1e-6f);
        assertArrayEquals(new float[] {0, 0, 1}, toward(l));
        // Without a group direction, the default one.
        var fallback =
                WorldExteriorObjects.doodadLight(0x2, colour(0x30, 0x20, 0x10), AMBIENT, null);
        assertEquals((float) WorldExteriorObjects.INTERIOR_TOWARD[2], fallback.towardZ());
    }

    @Test
    void mddiAddsTheAmbientToTheColourNormalized() {
        var l = WorldExteriorObjects.doodadLight(0x12, colour(0xf0, 0x20, 0x10), AMBIENT, null);
        // 0xf0 + 0x0b = 0xfb, under 1: no normalization.
        assertArrayEquals(new float[] {0xfb / 255f, 0x2c / 255f, 0x1d / 255f}, direct(l), 1e-6f);
        assertArrayEquals(
                new float[] {1, 0.5f, 0.25f},
                WorldExteriorObjects.sumNormalized(
                        new float[] {1, 0.5f, 0.25f}, new float[] {1, 0.5f, 0.25f}),
                1e-6f);
    }

    @Test
    void moltLightsAreNotRead() {
        assertNull(WorldExteriorObjects.doodadLight(0x4, colour(1, 2, 3), AMBIENT, null));
        assertNull(WorldExteriorObjects.doodadLight(0x6, colour(1, 2, 3), AMBIENT, null));
        assertNull(WorldExteriorObjects.doodadLight(0xc, colour(1, 2, 3), AMBIENT, null));
    }

    @Test
    void mohdLikeAmbientOverrideTakesTheRawColour() {
        // 0x8 without 0x2: ambient the raw colour, uncapped (0xff over the 0x60 cap); direct as
        // without 0x8.
        var bright = WorldExteriorObjects.doodadLight(0x8, colour(0xff, 0x80, 0), AMBIENT, null);
        assertArrayEquals(new float[] {1, 0x80 / 255f, 0}, ambient(bright), 1e-6f);
        assertArrayEquals(new float[] {1, 0x80 / 255f, 0}, direct(bright), 1e-6f);
        var dim = WorldExteriorObjects.doodadLight(0x8, colour(0x30, 0x20, 0x10), AMBIENT, null);
        assertArrayEquals(new float[] {0x30 / 255f, 0x20 / 255f, 0x10 / 255f}, ambient(dim), 1e-6f);
        assertEquals(0x70 / 255f, dim.directR(), 1e-6f);
        // 0x8 with 0x2: ambient the colour, not the building's; direct the colour toward the group.
        double[] up = {0, 0, 1};
        var inside = WorldExteriorObjects.doodadLight(0xa, colour(0x30, 0x20, 0x10), AMBIENT, up);
        assertArrayEquals(ambient(dim), ambient(inside), 1e-6f);
        assertArrayEquals(
                new float[] {0x30 / 255f, 0x20 / 255f, 0x10 / 255f}, direct(inside), 1e-6f);
        assertArrayEquals(new float[] {0, 0, 1}, toward(inside));
        // 0x8 with 0x10: the colour takes the building ambient's place in the sum.
        var mddi = WorldExteriorObjects.doodadLight(0x1a, colour(0x30, 0x20, 0x10), AMBIENT, up);
        assertArrayEquals(new float[] {0x60 / 255f, 0x40 / 255f, 0x20 / 255f}, direct(mddi), 1e-6f);
    }
}
