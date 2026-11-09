package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ExteriorObjectLimitsTest {
    @Test
    void fileTablesAreTheSumsOfThePools() {
        // Unchanged pools keep the tables they had before #308.
        assertEquals(120, ExteriorObjectLimits.MODELS);
        assertEquals(352, ExteriorObjectLimits.MESHES);
        // Very large buildings drawn by group add their own vertex, index and range allowance,
        // their interiors another, and both their own opaque images; they take no model, mesh or
        // placement rows of their own.
        assertEquals(1792 + 512 + 1024, ExteriorObjectLimits.RANGES);
        assertEquals(196_608 + 131_072 + 327_680, ExteriorObjectLimits.VERTICES);
        assertEquals(589_824 + 393_216 + 983_040, ExteriorObjectLimits.INDICES);
        // #308: 64 building + 4,096 doodad placements, 64 + 32 + 192 images, two draws a doodad.
        assertEquals(64 + 4096, ExteriorObjectLimits.PLACEMENTS);
        assertEquals(64 + 64 + 64 + 32 + 192, ExteriorObjectLimits.IMAGES);
        assertEquals(8192, ExteriorObjectLimits.DRAWS);
    }
}
