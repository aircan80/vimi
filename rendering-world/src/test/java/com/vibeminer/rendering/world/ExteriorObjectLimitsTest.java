package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExteriorObjectLimitsTest {
    @Test
    void fileTablesAreTheSumsOfThePools() {
        // 24 building roots and 256 building meshes; 640 doodad models (one mesh each) and 64 more
        // for set doodads tied to an interior group, with 800 and 80 images of their own.
        assertEquals(640, ExteriorObjectLimits.DOODAD_MODELS);
        assertEquals(800, ExteriorObjectLimits.DOODAD_IMAGES);
        assertEquals(64, ExteriorObjectLimits.DOODAD_INTERIOR_MODELS);
        assertEquals(80, ExteriorObjectLimits.DOODAD_INTERIOR_IMAGES);
        assertEquals(24 + 640 + 64, ExteriorObjectLimits.MODELS);
        assertEquals(256 + 640 + 64, ExteriorObjectLimits.MESHES);
        // Very large buildings drawn by group add their own vertex, index and range allowance,
        // their interiors another, and both their own opaque images; they take no model, mesh or
        // placement rows of their own.
        assertEquals(2304 + 512 + 1024, ExteriorObjectLimits.RANGES);
        assertEquals(294_912 + 131_072 + 327_680, ExteriorObjectLimits.VERTICES);
        assertEquals(884_736 + 393_216 + 983_040, ExteriorObjectLimits.INDICES);
        // 64 building + 5,120 doodad placements; the draw budget stays at 8,192 (measured).
        assertEquals(64 + 5120, ExteriorObjectLimits.PLACEMENTS);
        assertEquals(64 + 64 + 96 + 32 + 800 + 80, ExteriorObjectLimits.IMAGES);
        assertEquals(8192, ExteriorObjectLimits.DRAWS);
    }
}
