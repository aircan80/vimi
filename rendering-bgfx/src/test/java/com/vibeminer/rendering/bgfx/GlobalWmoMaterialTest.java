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
