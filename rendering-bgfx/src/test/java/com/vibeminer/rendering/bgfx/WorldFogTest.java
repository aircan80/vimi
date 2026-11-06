package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA_TO_COVERAGE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;

import com.vibeminer.rendering.world.TerrainCamera;
import org.junit.jupiter.api.Test;

/** Issue #357: fog fades geometry out completely before the cut, into the sky's horizon colour. */
class WorldFogTest {
    private static final WorldFog FOG = WorldFog.STREAM;

    @Test
    void fogIsFullAtTheViewCut() {
        assertEquals(1f, FOG.factor(TerrainCamera.WHOLE_TRIANGLE_FAR));
        assertEquals(1f, FOG.factor(TerrainCamera.WHOLE_TRIANGLE_FAR + 500));
        assertEquals(0f, FOG.factor(600));
        assertEquals(0f, FOG.factor(10));
        assertEquals(0.5f, FOG.factor(1400), 1e-6);
        assertArrayEquals(new float[] {600, 2200, 1, 0}, FOG.rangeUniform());
    }

    @Test
    void theColdPassKeepsItsPreviousFog() {
        assertEquals(0.85f, WorldFog.LEGACY.factor(2200), 1e-6);
        assertEquals((1800 - 600) / 1600f, WorldFog.LEGACY.factor(1800), 1e-6);
        assertArrayEquals(new float[] {153, 184, 196, 0}, WorldFog.LEGACY.colourUniform());
    }

    @Test
    void skyHorizonIsTheFogColour() {
        // One colour uniform value feeds terrain fog, object fog and the sky horizon.
        assertArrayEquals(new float[] {153, 184, 196, 0}, FOG.colourUniform());
        int width = 800, height = 500;
        // Level camera: the frame's middle row is the horizon.
        assertArrayEquals(new float[] {153, 184, 196}, FOG.sky(0, width, height, height / 2), 0.2f);
        // At and below the horizon the sky is exactly the fog colour.
        assertArrayEquals(new float[] {153, 184, 196}, FOG.sky(0, width, height, height - 1), 0);
        assertArrayEquals(new float[] {153, 184, 196}, FOG.sky(-0.5, width, height, 0), 0);
    }

    @Test
    void skyRisesToItsTopColourAboveTheHorizon() {
        int width = 800, height = 500;
        assertArrayEquals(new float[] {91, 141, 177}, FOG.sky(1.2, width, height, 0), 0);
        float[] middle = FOG.sky(0, width, height, 0);
        // Top row of a level 800 x 500 frame: atan(250 / 640) of 0.75 rad toward the top colour.
        double t = Math.atan(249.5 / 640) / 0.75;
        assertEquals(153 + (91 - 153) * t, middle[0], 1e-3);
        assertTrue(middle[2] < 196 && middle[2] > 177);
        assertArrayEquals(new float[] {-0.3f, 640, 0.75f, 0}, FOG.viewUniform(-0.3, width), 1e-6f);
        assertArrayEquals(new float[] {91, 141, 177, 0}, FOG.topUniform());
    }

    @Test
    void invalidFogIsRefused() {
        float[] c = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 10, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 0, 10, 1.5f, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new WorldFog(new float[2], c, 0, 10, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 0, 10, 1, 0));
    }

    @Test
    void multisampledCutoutsUseAlphaToCoverageWithoutWritingAlpha() {
        assertArrayEquals(
                new float[] {BgfxWorldStreamRenderer.CUTOUT_REFERENCE, 0, 1, 0},
                BgfxWorldStreamRenderer.cutoutMaterial(true));
        long coverage = BgfxWorldStreamRenderer.cutoutState(true);
        assertEquals(
                BGFX_STATE_BLEND_ALPHA_TO_COVERAGE, coverage & BGFX_STATE_BLEND_ALPHA_TO_COVERAGE);
        assertEquals(0, coverage & BGFX_STATE_WRITE_A, "the frame's alpha stays opaque");
        assertEquals(
                BgfxWorldWmoPass.DRAW_STATE & ~BGFX_STATE_WRITE_A,
                coverage & ~BGFX_STATE_BLEND_ALPHA_TO_COVERAGE);
    }

    @Test
    void singleSampledCutoutsKeepThePlainAlphaTest() {
        assertArrayEquals(
                new float[] {BgfxWorldStreamRenderer.CUTOUT_REFERENCE, 0, 0, 0},
                BgfxWorldStreamRenderer.cutoutMaterial(false));
        assertEquals(BgfxWorldWmoPass.DRAW_STATE, BgfxWorldStreamRenderer.cutoutState(false));
    }
}
