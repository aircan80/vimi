package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_ONE;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_Z;

import com.vibeminer.rendering.world.WorldObjectPicking;
import org.junit.jupiter.api.Test;

/** Issue #367: unlit and additive object surfaces. */
class WorldObjectMaterialClassTest {
    private static final int UNLIT = WorldStreamObjectsFile.UNLIT;

    @Test
    void materialUniformCarriesUnlitAndAdditive() {
        assertArrayEquals(
                new float[] {0, 0, 0, 0},
                BgfxWorldStreamRenderer.materialUniform(WorldStreamObjectsFile.OPAQUE, true));
        assertArrayEquals(
                new float[] {0, 0, 0, 1},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.OPAQUE | UNLIT, true));
        assertArrayEquals(
                new float[] {0, 1, 0, 2},
                BgfxWorldStreamRenderer.materialUniform(WorldStreamObjectsFile.ADDITIVE, false));
        assertArrayEquals(
                new float[] {0, 1, 0, 3},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.ADDITIVE | UNLIT, false));
        // Unlit cutouts keep their alpha-to-coverage ramp.
        assertArrayEquals(
                new float[] {BgfxWorldStreamRenderer.CUTOUT_REFERENCE, 0, 1, 1},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.CUTOUT | UNLIT, true));
    }

    @Test
    void additiveDrawsInTheSortedPassAddingSourceTimesAlpha() {
        assertTrue(WorldStreamObjectsFile.sorted(WorldStreamObjectsFile.ADDITIVE));
        assertTrue(WorldStreamObjectsFile.sorted(WorldStreamObjectsFile.ADDITIVE | UNLIT));
        assertTrue(WorldStreamObjectsFile.sorted(WorldStreamObjectsFile.BLENDED));
        assertFalse(WorldStreamObjectsFile.sorted(WorldStreamObjectsFile.OPAQUE | UNLIT));
        assertFalse(WorldStreamObjectsFile.sorted(WorldStreamObjectsFile.CUTOUT));
        long state = BgfxWorldStreamRenderer.ADDITIVE_STATE;
        assertEquals(
                BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_ONE),
                state & BGFX_STATE_BLEND_MASK);
        assertEquals(0, state & BGFX_STATE_WRITE_Z, "additive writes no depth");
        assertEquals(0, state & BGFX_STATE_WRITE_A, "the frame's alpha stays opaque");
    }

    @Test
    void rangeClassesAreKindPlusKnownFlagsOnly() {
        assertTrue(WorldObjectPicking.validClass(WorldStreamObjectsFile.ADDITIVE | UNLIT));
        assertTrue(WorldObjectPicking.validClass(WorldStreamObjectsFile.OPAQUE));
        assertFalse(WorldObjectPicking.validClass(4), "modulate stays refused");
        assertTrue(
                WorldObjectPicking.validClass(
                        WorldStreamObjectsFile.CUTOUT | UNLIT | WorldStreamObjectsFile.TWO_SIDED),
                "two-sided (#375)");
        assertFalse(WorldObjectPicking.validClass(0x400), "unknown flag");
        assertFalse(WorldObjectPicking.validClass(-1));
        assertEquals(
                WorldStreamObjectsFile.CUTOUT,
                WorldStreamObjectsFile.kind(WorldStreamObjectsFile.CUTOUT | UNLIT));
    }
}
