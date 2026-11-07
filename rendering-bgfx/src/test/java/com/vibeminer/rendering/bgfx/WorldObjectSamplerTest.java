package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIP_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_MASK;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_MASK;

import com.vibeminer.rendering.world.ObjectTextureMips;
import org.junit.jupiter.api.Test;

/** Issues #265, #357: object textures sample their mip chains anisotropically and tile. */
class WorldObjectSamplerTest {
    @Test
    void objectPassSamplesAnisotropicWithLinearMipsAndRepeatAddressing() {
        int flags = BgfxWorldStreamRenderer.OBJECT_SAMPLER_FLAGS;
        // Anisotropic min and mag (issue #357) over linear mips; zero U/V fields are repeat.
        assertEquals(
                BGFX_SAMPLER_MIN_ANISOTROPIC, flags & BGFX_SAMPLER_MIN_MASK, "anisotropic min");
        assertEquals(
                BGFX_SAMPLER_MAG_ANISOTROPIC, flags & BGFX_SAMPLER_MAG_MASK, "anisotropic mag");
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
