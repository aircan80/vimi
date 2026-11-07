package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WmoMaterialClassTest {
    @Test
    void blendModesMapToKindsAndModulateIsUnsupported() {
        assertEquals(WorldObjectPicking.OPAQUE, WmoMaterialClass.of(0, 0));
        assertEquals(WorldObjectPicking.CUTOUT, WmoMaterialClass.of(0, 1));
        assertEquals(WorldObjectPicking.BLENDED, WmoMaterialClass.of(0, 2));
        assertEquals(WorldObjectPicking.ADDITIVE, WmoMaterialClass.of(0, 3));
        for (long mode : new long[] {4, 5, 6, 7, -1, 0xffff_ffffL}) {
            assertEquals(WmoMaterialClass.UNSUPPORTED, WmoMaterialClass.of(0, mode));
        }
    }

    @Test
    void unlitAndUnculledAreFlagsAndOtherBitsAreIgnored() {
        assertEquals(
                WorldObjectPicking.ADDITIVE | WorldObjectPicking.UNLIT,
                WmoMaterialClass.of(0x1, 3));
        assertEquals(
                WorldObjectPicking.CUTOUT | WorldObjectPicking.TWO_SIDED,
                WmoMaterialClass.of(0x4, 1));
        // F_UNFOGGED, F_EXTLIGHT, F_SIDN, F_WINDOW and the clamp bits change nothing.
        assertEquals(WorldObjectPicking.OPAQUE, WmoMaterialClass.of(0x2 | 0x8 | 0x10 | 0x20, 0));
        assertTrue(WmoMaterialClass.ignoresFlags(0x8));
        assertTrue(WmoMaterialClass.ignoresFlags(0x1 | 0x100));
        assertFalse(WmoMaterialClass.ignoresFlags(0x1 | 0x4));
        for (int flags : new int[] {0, 0x1, 0x4, 0x5}) {
            for (int mode = 0; mode <= 3; mode++) {
                assertTrue(WorldObjectPicking.validClass(WmoMaterialClass.of(flags, mode)));
            }
        }
    }
}
