package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ALPHA_TO_COVERAGE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;

import com.vibeminer.rendering.world.TerrainCamera;
import org.junit.jupiter.api.Test;

/** Issue #357: fog fades geometry out completely before the cut, into the sky behind it. */
class WorldFogTest {
    private static final WorldFog FOG = WorldFog.STREAM;
    private static final int WIDTH = 800, HEIGHT = 500;

    @Test
    void fogIsFullAtTheViewCut() {
        assertEquals(1f, FOG.factor(TerrainCamera.WHOLE_TRIANGLE_FAR));
        assertEquals(1f, FOG.factor(TerrainCamera.WHOLE_TRIANGLE_FAR + 500));
        assertEquals(0f, FOG.factor(600));
        assertEquals(0f, FOG.factor(10));
        assertEquals(0.5f, FOG.factor(1400), 1e-6);
        assertArrayEquals(
                new float[] {600, 2200, 1, (float) TerrainCamera.WHOLE_TRIANGLE_FAR},
                FOG.rangeUniform());
    }

    @Test
    void streamFogIsClampedToReachFullFogByTheCut() {
        var late = WorldFog.stream(WorldFog.FOG_COLOUR, WorldFog.SKY_TOP, 600, 5000, 0.75f);
        assertEquals((float) TerrainCamera.WHOLE_TRIANGLE_FAR, late.end());
        assertEquals(1f, late.maximum());
        assertEquals(1f, late.factor(TerrainCamera.WHOLE_TRIANGLE_FAR));
        // A fog that would end past its cut is refused.
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldFog(WorldFog.FOG_COLOUR, WorldFog.SKY_TOP, 0, 3000, 1, 2200, 1));
    }

    @Test
    void theColdPassKeepsItsPreviousFlatFog() {
        assertEquals(0.85f, WorldFog.LEGACY.factor(2200), 1e-6);
        assertEquals((1800 - 600) / 1600f, WorldFog.LEGACY.factor(1800), 1e-6);
        assertArrayEquals(new float[] {153, 184, 196, 0.75f}, WorldFog.LEGACY.fogUniform());
        // Flat: the same colour at every elevation.
        assertArrayEquals(new float[] {153, 184, 196, 0}, WorldFog.LEGACY.topUniform());
        assertArrayEquals(new float[] {153, 184, 196}, WorldFog.LEGACY.sky(1.2), 0);
    }

    @Test
    void fogFadesIntoTheSkyAtItsOwnElevation() {
        // At full fog a surface is the sky colour at its elevation, which is what the sky shader
        // draws for a view ray at that elevation: equal at the cut above and below the horizon.
        for (double elevation : new double[] {-0.3, 0, 0.1, 0.4, 0.9}) {
            float[] sky = FOG.sky(elevation);
            double dx = Math.cos(elevation), dz = Math.sin(elevation);
            assertArrayEquals(sky, FOG.sky(WorldFog.elevation(dx * 2200, 0, dz * 2200)), 1e-4f);
        }
        assertArrayEquals(new float[] {153, 184, 196}, FOG.sky(-0.2), 0);
        assertArrayEquals(new float[] {91, 141, 177}, FOG.sky(0.75), 0);
        float[] middle = FOG.sky(0.375);
        assertEquals((153 + 91) / 2f, middle[0], 1e-3);
    }

    @Test
    void skyPixelsFollowTheirViewRayElevation() {
        double focal = TerrainCamera.focalPixels(WIDTH);
        // Level camera: the centre pixel's ray is level, so it is the horizon colour.
        assertArrayEquals(
                new float[] {153, 184, 196},
                FOG.skyPixel(0, focal, WIDTH, HEIGHT, WIDTH / 2, HEIGHT / 2),
                0.3f);
        // Top-centre: elevation atan(249.5 / focal).
        double top = Math.atan(249.5 / focal);
        assertArrayEquals(FOG.sky(top), FOG.skyPixel(0, focal, WIDTH, HEIGHT, 399, 0), 0.05f);
        // A top-corner ray rises less than the top-centre one: it points farther sideways.
        float[] corner = FOG.skyPixel(0, focal, WIDTH, HEIGHT, 0, 0);
        assertTrue(corner[0] > FOG.skyPixel(0, focal, WIDTH, HEIGHT, 399, 0)[0]);
        // Pitch adds to the ray's elevation.
        assertArrayEquals(FOG.sky(0.3), FOG.skyPixel(0.3, focal, WIDTH, HEIGHT, 399, 249), 0.3f);
    }

    @Test
    void skyViewUniformCarriesTheSharedFocalLength() {
        assertArrayEquals(
                new float[] {-0.3f, (float) TerrainCamera.focalPixels(WIDTH), 0.75f, 0},
                FOG.viewUniform(-0.3, WIDTH),
                1e-6f);
        // A different focal length moves the horizon gradient: the same row sees a different
        // elevation, so the sky colour differs.
        float[] shared = FOG.skyPixel(0, TerrainCamera.focalPixels(WIDTH), WIDTH, HEIGHT, 399, 50);
        float[] narrow = FOG.skyPixel(0, 0.8 * WIDTH, WIDTH, HEIGHT, 399, 50);
        assertTrue(Math.abs(shared[0] - narrow[0]) > 2, shared[0] + " vs " + narrow[0]);
    }

    @Test
    void uniformsCarryHorizonTopAndElevation() {
        assertArrayEquals(new float[] {153, 184, 196, 0.75f}, FOG.fogUniform());
        assertArrayEquals(new float[] {153, 184, 196, 0}, FOG.horizonUniform());
        assertArrayEquals(new float[] {91, 141, 177, 0}, FOG.topUniform());
    }

    @Test
    void coloursAreCopiedNotShared() {
        var colour = new float[] {1, 2, 3};
        var fog = WorldFog.stream(colour, colour, 0, 100, 1);
        colour[0] = 99;
        assertEquals(1f, fog.horizon()[0]);
        fog.horizon()[0] = 50;
        assertEquals(1f, fog.horizon()[0]);
        assertNotSame(fog.horizon(), fog.horizon());
    }

    @Test
    void invalidFogIsRefused() {
        float[] c = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 10, 10, 1, 20, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 0, 10, 1.5f, 20, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldFog(new float[2], c, 0, 10, 1, 20, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldFog(c, c, 0, 10, 1, 20, 0));
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
