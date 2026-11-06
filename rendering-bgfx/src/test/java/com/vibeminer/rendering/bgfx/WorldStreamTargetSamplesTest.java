package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_RESET_MAXANISOTROPY;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MSAA_X4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY;

import org.junit.jupiter.api.Test;

/** Issue #357: the World target multisamples where the device can, and falls back cleanly. */
class WorldStreamTargetSamplesTest {
    @Test
    void multisamplesOnlyWhenColourAndDepthBothCan() {
        assertEquals(4, WorldStreamNativeOwner.targetSamples(true, true));
        assertEquals(1, WorldStreamNativeOwner.targetSamples(true, false));
        assertEquals(1, WorldStreamNativeOwner.targetSamples(false, true));
        assertEquals(1, WorldStreamNativeOwner.targetSamples(false, false));
    }

    @Test
    void formatCapsDecideMultisamplingEvenWhenTextureValidationAccepts() {
        int msaa = BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER_MSAA;
        int framebuffer = BGFX_CAPS_FORMAT_TEXTURE_FRAMEBUFFER;
        assertTrue(WorldStreamNativeOwner.multisampled(framebuffer | msaa, true));
        // A device whose format caps lack the MSAA bit falls back, whatever validation says.
        assertFalse(WorldStreamNativeOwner.multisampled(framebuffer, true));
        assertFalse(WorldStreamNativeOwner.multisampled(framebuffer | msaa, false));
        assertEquals(
                1,
                WorldStreamNativeOwner.targetSamples(
                        WorldStreamNativeOwner.multisampled(framebuffer | msaa, true),
                        WorldStreamNativeOwner.multisampled(framebuffer, true)));
    }

    @Test
    void multisampledColourKeepsItsResolveTextureAndDepthIsWriteOnly() {
        assertEquals(
                BGFX_TEXTURE_RT | BGFX_TEXTURE_RT_MSAA_X4, WorldStreamNativeOwner.colourFlags(4));
        assertEquals(
                BGFX_TEXTURE_RT | BGFX_TEXTURE_RT_MSAA_X4 | BGFX_TEXTURE_RT_WRITE_ONLY,
                WorldStreamNativeOwner.depthFlags(4));
    }

    @Test
    void singleSampledFallbackIsThePreviousTarget() {
        assertEquals(BGFX_TEXTURE_RT, WorldStreamNativeOwner.colourFlags(1));
        assertEquals(BGFX_TEXTURE_RT, WorldStreamNativeOwner.depthFlags(1));
    }

    @Test
    void resetEnablesMaximumAnisotropy() {
        assertEquals(BGFX_RESET_MAXANISOTROPY, WorldStreamNativeOwner.RESET_FLAGS);
    }
}
