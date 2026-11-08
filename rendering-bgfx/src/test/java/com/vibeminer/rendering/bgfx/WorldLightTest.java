package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Issue #373: the shared World light and its terrain switch. */
class WorldLightTest {
    @Test
    void fixedLightIsTheObjectShadersLight() {
        float[] uniform = WorldLight.FIXED.uniform(true);
        assertEquals(12, uniform.length);
        assertEquals(-0.408248f, uniform[0], 1e-6);
        assertEquals(-0.408248f, uniform[1], 1e-6);
        assertEquals(0.816497f, uniform[2], 1e-6);
        assertEquals(1, uniform[3]);
        assertArrayEquals(
                new float[] {2 / 3f, 2 / 3f, 2 / 3f, 0},
                java.util.Arrays.copyOfRange(uniform, 4, 8));
        assertArrayEquals(
                new float[] {1 / 3f, 1 / 3f, 1 / 3f, 0},
                java.util.Arrays.copyOfRange(uniform, 8, 12));
        assertEquals(0, WorldLight.FIXED.uniform(false)[3]);
        // Facing the light: full; facing away: ambient only; flat ground in between.
        assertEquals(1, WorldLight.FIXED.factor(0, -0.408248, -0.408248, 0.816497), 1e-5);
        assertEquals(1 / 3f, WorldLight.FIXED.factor(0, 0.408248, 0.408248, -0.816497), 1e-6);
        assertEquals(1 / 3.0 + 2 / 3.0 * 0.816497, WorldLight.FIXED.factor(1, 0, 0, 1), 1e-5);
    }

    @Test
    void invalidLightsAreRefused() {
        float[] grey = {0.5f, 0.5f, 0.5f};
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldLight(new float[] {0, 0, 2}, grey, grey));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldLight(new float[] {0, 0, 1}, new float[] {-1, 0, 0}, grey));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldLight(new float[] {0, 1}, grey, grey));
    }

    @Test
    void terrainLightingIsOnUnlessExactlyFalse() {
        assertTrue(BgfxWorldStreamRenderer.terrainLightingEnabled(null));
        assertTrue(BgfxWorldStreamRenderer.terrainLightingEnabled("no"));
        assertFalse(BgfxWorldStreamRenderer.terrainLightingEnabled("False"));
    }

    @Test
    void packetAttributesAreChecked() {
        WorldStreamTileFile.validateAttributes(new byte[16]);
        WorldStreamTileFile.validateAttributes(new byte[] {1, 2, 3, 0, 4, 5, 6, (byte) 255});
        for (byte[] bad :
                new byte[][] {
                    {0, 0, 127, 1, 0, 0, 0, 0}, // pad byte set
                    {0, 0, 127, 0, 9, 0, 0, 0}, // a colour without its presence byte
                    {0, 0, 127, 0, 9, 0, 0, 7}, // a presence byte that is neither 0 nor 255
                    new byte[7]
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldStreamTileFile.validateAttributes(bad));
        }
    }

    @Test
    void aSkysRecordedLightIsMixedInByItsWeight() {
        var explorer = WorldStreamTransport.Sky.EXPLORER;
        assertSame(WorldLight.FIXED, WorldLight.FIXED.withRecorded(null));
        assertSame(WorldLight.FIXED, WorldLight.FIXED.withRecorded(explorer));
        var full =
                new WorldStreamTransport.Sky(
                        explorer.horizon(),
                        explorer.top(),
                        0,
                        0,
                        0,
                        new float[] {255, 0, 51},
                        new float[] {0, 255, 102},
                        1);
        var recorded = WorldLight.FIXED.withRecorded(full);
        assertArrayEquals(new float[] {1, 0, 0.2f}, recorded.diffuse(), 1e-6f);
        assertArrayEquals(new float[] {0, 1, 0.4f}, recorded.ambient(), 1e-6f);
        assertArrayEquals(WorldLight.FIXED.direction(), recorded.direction(), 0);
        var half =
                new WorldStreamTransport.Sky(
                        explorer.horizon(),
                        explorer.top(),
                        0,
                        0,
                        0,
                        new float[] {255, 0, 51},
                        new float[] {0, 255, 102},
                        0.5);
        var mixed = WorldLight.FIXED.withRecorded(half);
        assertArrayEquals(
                new float[] {(2 / 3f + 1) / 2, (2 / 3f) / 2, (2 / 3f + 0.2f) / 2},
                mixed.diffuse(),
                1e-6f);
    }
}
