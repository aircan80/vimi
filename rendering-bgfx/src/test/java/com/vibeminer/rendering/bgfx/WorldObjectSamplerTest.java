package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_MASK;

import com.vibeminer.rendering.world.ObjectTextureMips;
import org.junit.jupiter.api.Test;

/** Issue #265: object textures sample their mip chains trilinearly and tile. */
class WorldObjectSamplerTest {
    @Test
    void objectPassSamplesTrilinearWithRepeatAddressing() {
        int flags = BgfxWorldStreamRenderer.OBJECT_SAMPLER_FLAGS;
        // Zero min, mag and mip fields are bgfx's linear filtering; zero U/V fields are repeat.
        assertEquals(0, flags & BGFX_SAMPLER_MIN_MASK, "linear minification");
        assertEquals(0, flags & BGFX_SAMPLER_MAG_MASK, "linear magnification");
        assertEquals(0, flags & BGFX_SAMPLER_MIP_MASK, "linear between mip levels");
        assertEquals(0, flags & BGFX_SAMPLER_U_MASK, "repeat in U");
        assertEquals(0, flags & BGFX_SAMPLER_V_MASK, "repeat in V");
    }

    @Test
    void cutoutChainsPreserveCoverageAtTheReferenceTheShaderTests() {
        assertEquals(0.5f, BgfxWorldStreamRenderer.CUTOUT_REFERENCE);
        assertEquals(ObjectTextureMips.CUTOUT_REFERENCE, BgfxWorldStreamRenderer.CUTOUT_REFERENCE);
        assertEquals(WorldStreamObjectsFile.OPAQUE, ObjectTextureMips.OPAQUE);
        assertEquals(WorldStreamObjectsFile.CUTOUT, ObjectTextureMips.CUTOUT);
        assertEquals(WorldStreamObjectsFile.BLENDED, ObjectTextureMips.BLENDED);
    }
}
