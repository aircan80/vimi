package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.rendering.world.WmoMaterialClass;
import com.vibeminer.rendering.world.WorldObjectPicking;
import org.junit.jupiter.api.Test;

/** Issue #297: the dungeon viewer draws a material as the outdoor objects do. */
class GlobalWmoMaterialTest {
    @Test
    void passesFollowTheOutdoorOrder() {
        assertEquals(0, BgfxGlobalWmoRenderer.pass(WmoMaterialClass.of(0, 0)));
        assertEquals(0, BgfxGlobalWmoRenderer.pass(WmoMaterialClass.of(0x4, 1)));
        assertEquals(1, BgfxGlobalWmoRenderer.pass(WmoMaterialClass.of(0, 2)));
        assertEquals(2, BgfxGlobalWmoRenderer.pass(WmoMaterialClass.of(0x1, 3)));
    }

    /**
     * At the batch limit, every draw pass reads the same three preallocated vectors: the per-batch
     * path allocates nothing, so a 64 KiB stack holds any interior (#482 review).
     */
    @Test
    void materialVectorsAreAllocatedOncePerDrawAtTheBatchLimit() {
        var stack = org.lwjgl.system.MemoryStack.create(64 * 1024);
        try (var frame = stack.push()) {
            frame.mallocFloat(16);
            frame.mallocFloat(16);
            var vectors = BgfxGlobalWmoRenderer.MaterialVectors.on(frame);
            long pointer = frame.getPointer();
            int[] classes = {
                WmoMaterialClass.of(0, 0),
                WmoMaterialClass.of(0x4, 1),
                WmoMaterialClass.of(0, 2),
                WmoMaterialClass.of(0x1, 3)
            };
            for (int pass = 0; pass < 3; pass++) {
                for (int b = 0;
                        b < com.vibeminer.rendering.world.GlobalInteriorLimits.BATCHES;
                        b++) {
                    int rangeClass = classes[b % classes.length];
                    var vector = vectors.of(rangeClass);
                    float[] value = new float[4];
                    vector.get(0, value);
                    assertArrayEquals(BgfxGlobalWmoRenderer.materialUniform(rangeClass), value);
                }
            }
            assertEquals(pointer, frame.getPointer(), "No stack allocation per batch");
        }
    }

    @Test
    void materialUniformAndStateMatchTheOutdoorObjects() {
        assertArrayEquals(
                new float[] {0, 0, 0, 0},
                BgfxGlobalWmoRenderer.materialUniform(WorldObjectPicking.OPAQUE));
        assertArrayEquals(
                new float[] {WorldObjectPicking.CUTOUT_REFERENCE, 0, 0, 0},
                BgfxGlobalWmoRenderer.materialUniform(
                        WorldObjectPicking.CUTOUT | WorldObjectPicking.TWO_SIDED));
        assertArrayEquals(
                new float[] {0, 1, 0, 0},
                BgfxGlobalWmoRenderer.materialUniform(WorldObjectPicking.BLENDED));
        assertArrayEquals(
                new float[] {0, 1, 0, 0},
                BgfxGlobalWmoRenderer.materialUniform(
                        WorldObjectPicking.ADDITIVE | WorldObjectPicking.UNLIT));
        assertEquals(
                BgfxWorldStreamRenderer.BLENDED_STATE,
                BgfxGlobalWmoRenderer.state(WorldObjectPicking.BLENDED));
        assertEquals(
                BgfxWorldStreamRenderer.ADDITIVE_STATE,
                BgfxGlobalWmoRenderer.state(WorldObjectPicking.ADDITIVE));
        assertEquals(
                BgfxGlobalWmoRenderer.state(WorldObjectPicking.OPAQUE),
                BgfxGlobalWmoRenderer.state(WorldObjectPicking.CUTOUT));
    }
}
