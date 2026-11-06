package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldFrameLimitsTest {
    private static WorldFrameLimits.Size frame(double w, double h, double output, double render) {
        return WorldFrameLimits.frame(w, h, output, render);
    }

    @Test
    void frameFollowsTheViewportAtNativeResolution() {
        assertEquals(new WorldFrameLimits.Size(1600, 900), frame(1600, 900, 1, 1));
        assertEquals(new WorldFrameLimits.Size(1, 1), frame(0, -5, 1, 1));
    }

    @Test
    void outputScaleGivesHiDpiPixels() {
        assertEquals(new WorldFrameLimits.Size(1920, 1080), frame(1280, 720, 1.5, 1));
        assertEquals(new WorldFrameLimits.Size(2400, 1500), frame(1200, 750, 2, 1));
    }

    @Test
    void theCapKeepsTheAspectRatio() {
        // 4K at 1x: scaled to fit 2560 wide, 16:9 kept.
        assertEquals(new WorldFrameLimits.Size(2560, 1440), frame(3840, 2160, 1, 1));
        // Tall window: height bound.
        assertEquals(new WorldFrameLimits.Size(800, 1600), frame(1000, 2000, 1, 1));
        // 1440p at 2x HiDPI is capped too.
        assertEquals(new WorldFrameLimits.Size(2560, 1440), frame(2560, 1440, 2, 1));
    }

    @Test
    void renderScaleIsClampedToItsRange() {
        assertEquals(new WorldFrameLimits.Size(960, 540), frame(1920, 1080, 1, .5));
        assertEquals(new WorldFrameLimits.Size(960, 540), frame(1920, 1080, 1, .1));
        assertEquals(new WorldFrameLimits.Size(1920, 1080), frame(1920, 1080, 1, 3));
        assertEquals(
                new WorldFrameLimits.Size(1920, 1080), frame(1920, 1080, Double.NaN, Double.NaN));
    }

    @Test
    void fitsIsTheOnlyBound() {
        assertTrue(WorldFrameLimits.fits(2560, 1600));
        assertTrue(WorldFrameLimits.fits(1, 1));
        assertFalse(WorldFrameLimits.fits(2561, 1));
        assertFalse(WorldFrameLimits.fits(1, 1601));
        assertFalse(WorldFrameLimits.fits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new WorldFrameLimits.Size(2561, 1));
    }
}
