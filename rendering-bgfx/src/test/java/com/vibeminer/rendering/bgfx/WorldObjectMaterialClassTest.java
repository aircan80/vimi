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

    /**
     * Issue #383: an interior-light range adds 4 to w, on top of unlit and additive, so the shader
     * lights it from its vertex colour instead of the sun; every other component is unchanged and
     * every combination has its own frame material.
     */
    @Test
    void materialUniformCarriesInteriorLight() {
        int interior = WorldStreamObjectsFile.INTERIOR_LIGHT;
        assertArrayEquals(
                new float[] {0, 0, 0, 4},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.OPAQUE | interior, true));
        assertArrayEquals(
                new float[] {BgfxWorldStreamRenderer.CUTOUT_REFERENCE, 0, 1, 5},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.CUTOUT | UNLIT | interior, true));
        assertArrayEquals(
                new float[] {0, 1, 0, 6},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.ADDITIVE | interior, false));
        assertArrayEquals(
                new float[] {0, 1, 0, 4},
                BgfxWorldStreamRenderer.materialUniform(
                        WorldStreamObjectsFile.BLENDED
                                | interior
                                | WorldStreamObjectsFile.TWO_SIDED,
                        false));
        assertTrue(WorldObjectPicking.validClass(WorldStreamObjectsFile.CUTOUT | interior));
        var slots = new java.util.HashSet<Integer>();
        for (int kind = 0; kind <= WorldStreamObjectsFile.ADDITIVE; kind++) {
            for (int flags : new int[] {0, UNLIT, interior, UNLIT | interior}) {
                int slot = BgfxWorldStreamRenderer.ObjectFrame.materialSlot(kind | flags);
                assertTrue(slot >= 0 && slot < 16);
                assertTrue(slots.add(slot));
            }
        }
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var frame = BgfxWorldStreamRenderer.ObjectFrame.on(stack, false);
            assertEquals(4f, frame.material(WorldStreamObjectsFile.OPAQUE | interior).get(3));
            assertEquals(0f, frame.material(WorldStreamObjectsFile.OPAQUE).get(3));
            assertEquals(
                    7f, frame.material(WorldStreamObjectsFile.ADDITIVE | UNLIT | interior).get(3));
        }
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

    /** Issue #375: vertex colours are on unless the helper's property is false (any case). */
    @Test
    void vertexColoursAreAnOptOutShaderSwitch() {
        assertTrue(BgfxWorldStreamRenderer.vertexColoursEnabled(null));
        assertTrue(BgfxWorldStreamRenderer.vertexColoursEnabled("0"));
        assertFalse(BgfxWorldStreamRenderer.vertexColoursEnabled("False"));
        float[] on = BgfxWorldStreamRenderer.wmoSkyTop(WorldFog.STREAM, true);
        float[] off = BgfxWorldStreamRenderer.wmoSkyTop(WorldFog.STREAM, false);
        assertEquals(1, on[3]);
        assertEquals(0, off[3]);
        float[] top = WorldFog.STREAM.topUniform();
        for (int i = 0; i < 3; i++) {
            assertEquals(top[i], on[i]);
            assertEquals(top[i], off[i]);
        }
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
        assertTrue(
                WorldObjectPicking.validClass(WorldStreamObjectsFile.INTERIOR_LIGHT),
                "interior light (#383)");
        assertFalse(WorldObjectPicking.validClass(0x800), "unknown flag");
        assertFalse(WorldObjectPicking.validClass(-1));
        assertEquals(
                WorldStreamObjectsFile.CUTOUT,
                WorldStreamObjectsFile.kind(WorldStreamObjectsFile.CUTOUT | UNLIT));
    }

    @Test
    void additiveRangesDrawAfterBlendedOnesWithinAPlacement() {
        // #449: steps run in order, so a placement's additive ranges follow its blended ones
        // whatever their order in the file.
        assertEquals(
                BgfxWorldStreamRenderer.STEP_UNSORTED,
                BgfxWorldStreamRenderer.step(WorldStreamObjectsFile.OPAQUE | UNLIT));
        assertEquals(
                BgfxWorldStreamRenderer.STEP_UNSORTED,
                BgfxWorldStreamRenderer.step(WorldStreamObjectsFile.CUTOUT));
        assertEquals(
                BgfxWorldStreamRenderer.STEP_BLENDED,
                BgfxWorldStreamRenderer.step(WorldStreamObjectsFile.BLENDED | UNLIT));
        assertEquals(
                BgfxWorldStreamRenderer.STEP_ADDITIVE,
                BgfxWorldStreamRenderer.step(WorldStreamObjectsFile.ADDITIVE));
        assertTrue(BgfxWorldStreamRenderer.STEP_BLENDED < BgfxWorldStreamRenderer.STEP_ADDITIVE);
    }
}
