package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExteriorObjectLimitsTest {
    @Test
    void fileTablesAreTheSumsOfThePools() {
        // 24 building roots and 256 building meshes; 256 doodad models (one mesh each) and 64 more
        // for set doodads tied to an interior group, with 320 and 80 images of their own.
        assertEquals(256, ExteriorObjectLimits.DOODAD_MODELS);
        assertEquals(320, ExteriorObjectLimits.DOODAD_IMAGES);
        assertEquals(64, ExteriorObjectLimits.DOODAD_INTERIOR_MODELS);
        assertEquals(80, ExteriorObjectLimits.DOODAD_INTERIOR_IMAGES);
        assertEquals(24 + 256 + 64, ExteriorObjectLimits.MODELS);
        assertEquals(256 + 256 + 64, ExteriorObjectLimits.MESHES);
        // Very large buildings drawn by group add their own vertex, index and range allowance,
        // their interiors another, and both their own opaque images; they take no model, mesh or
        // placement rows of their own.
        assertEquals(1792 + 512 + 1024, ExteriorObjectLimits.RANGES);
        assertEquals(196_608 + 131_072 + 327_680, ExteriorObjectLimits.VERTICES);
        assertEquals(589_824 + 393_216 + 983_040, ExteriorObjectLimits.INDICES);
        // #308: 64 building + 4,096 doodad placements, two draws a doodad.
        assertEquals(64 + 4096, ExteriorObjectLimits.PLACEMENTS);
        assertEquals(64 + 64 + 64 + 32 + 320 + 80, ExteriorObjectLimits.IMAGES);
        assertEquals(8192, ExteriorObjectLimits.DRAWS);
    }
}
