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
        // F_UNFOGGED, F_EXTLIGHT, F_SIDN and F_WINDOW change nothing: no source gives a formula.
        assertEquals(WorldObjectPicking.OPAQUE, WmoMaterialClass.of(0x2 | 0x8 | 0x10 | 0x20, 0));
        assertTrue(WmoMaterialClass.ignoresFlags(0x8));
        assertTrue(WmoMaterialClass.ignoresFlags(0x10));
        assertTrue(WmoMaterialClass.ignoresFlags(0x1 | 0x100));
        assertFalse(WmoMaterialClass.ignoresFlags(0x1 | 0x4));
        for (int flags : new int[] {0, 0x1, 0x4, 0x5, 0x40, 0x80, 0xc5}) {
            for (int mode = 0; mode <= 3; mode++) {
                assertTrue(WorldObjectPicking.validClass(WmoMaterialClass.of(flags, mode)));
            }
        }
    }

    @Test
    void clampBitsSelectClampedAddressingPerAxis() {
        assertEquals(
                WorldObjectPicking.CUTOUT | WorldObjectPicking.CLAMP_S,
                WmoMaterialClass.of(0x40, 1));
        assertEquals(
                WorldObjectPicking.OPAQUE | WorldObjectPicking.CLAMP_T,
                WmoMaterialClass.of(0x80, 0));
        assertEquals(
                WorldObjectPicking.BLENDED
                        | WorldObjectPicking.UNLIT
                        | WorldObjectPicking.CLAMP_S
                        | WorldObjectPicking.CLAMP_T,
                WmoMaterialClass.of(0x1 | 0x40 | 0x80, 2));
        assertFalse(WmoMaterialClass.ignoresFlags(0x40 | 0x80));
        // Clamping changes sampling only: the kind is unchanged.
        assertEquals(
                WorldObjectPicking.ADDITIVE,
                WorldObjectPicking.kind(WmoMaterialClass.of(0x40 | 0x80, 3)));
        assertFalse(WorldObjectPicking.validClass(0x2000), "an unknown flag stays invalid");
    }
}
