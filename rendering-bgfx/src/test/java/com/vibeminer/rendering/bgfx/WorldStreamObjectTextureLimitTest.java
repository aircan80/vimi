package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Object sets share bgfx's texture handles with the rest of the session (issue #173). */
final class WorldStreamObjectTextureLimitTest {
    /** BGFX_CONFIG_MAX_TEXTURES of the pinned LWJGL bgfx build, read from bgfx_get_caps. */
    private static final int BGFX_TEXTURES = 4096;

    @Test
    void theObjectShareIsBgfxLimitLessEveryOtherTextureOfTheSession() {
        int slots = BgfxWorldStreamRenderer.SLOT_COUNT;
        // FAR: 26 slots. Live at open: one atlas per slot, the splat dummy array and a context
        // texture; later: a splat mask and table per slot, the shared splat array and the owner.
        int live = slots + 2;
        int limit = BgfxWorldStreamRenderer.objectTextureLimit(BGFX_TEXTURES, live, slots);
        assertEquals(BGFX_TEXTURES - live - 2 * slots - 1 - 16, limit);
        assertEquals(3999, limit);
        assertEquals(0, BgfxWorldStreamRenderer.objectTextureLimit(64, 60, slots));
        assertThrows(
                IllegalArgumentException.class,
                () -> BgfxWorldStreamRenderer.objectTextureLimit(BGFX_TEXTURES, -1, slots));
    }

    @Test
    void aSetFitsOnlyWhenAllItsImagesFitBesideTheHeldHandles() {
        int limit = 3999;
        assertTrue(BgfxWorldStreamRenderer.objectTexturesFit(limit, 0, limit));
        assertTrue(BgfxWorldStreamRenderer.objectTexturesFit(limit, limit - 600, 600));
        assertFalse(BgfxWorldStreamRenderer.objectTexturesFit(limit, limit - 600, 601));
        assertTrue(BgfxWorldStreamRenderer.objectTexturesFit(limit, limit, 0));
        assertFalse(BgfxWorldStreamRenderer.objectTexturesFit(limit, limit, 1));
        assertFalse(BgfxWorldStreamRenderer.objectTexturesFit(limit, -1, 0));
        assertFalse(BgfxWorldStreamRenderer.objectTexturesFit(limit, 0, -1));
        assertFalse(BgfxWorldStreamRenderer.objectTexturesFit(0, 0, 1));
    }

    @Test
    void aFarSessionAtEveryCapNeedsNearlyFourTimesBgfxLimitSoTheCheckMustBind() {
        // 25 active sets and one staged, each at its image cap.
        long worst = (long) BgfxWorldStreamRenderer.SLOT_COUNT * WorldStreamObjectsFile.MAX_IMAGES;
        assertTrue(worst > 3L * BGFX_TEXTURES, "worst case " + worst);
        // Filled nearest first, the sets that fit attach and the next is refused.
        int limit = 3999, held = 0, attached = 0;
        while (BgfxWorldStreamRenderer.objectTexturesFit(
                limit, held, WorldStreamObjectsFile.MAX_IMAGES)) {
            held += WorldStreamObjectsFile.MAX_IMAGES;
            attached++;
        }
        assertEquals(limit / WorldStreamObjectsFile.MAX_IMAGES, attached);
        assertTrue(attached < BgfxWorldStreamRenderer.SLOT_COUNT);
    }

    @Test
    void theRefusalLineStatesTheNeedTheHeldHandlesAndTheRetry() {
        assertEquals(
                "World objects set refused for texture handles: needs 612, 3600 of 3999 object"
                        + " handles held (bgfx limit 4096); offered again when a set is released"
                        + " (1 refusal this session)",
                BgfxWorldStreamRenderer.textureRefusal(612, 3600, 3999, 4096, 1));
        assertTrue(
                BgfxWorldStreamRenderer.textureRefusal(1, 2, 3, 4, 2)
                        .endsWith("(2 refusals this session)"));
    }

    @Test
    void aWaitForReleasedHandlesIsBoundedInTimeNotSteps() {
        long bound = BgfxWorldStreamRenderer.TEXTURE_WAIT_NANOS;
        assertEquals(250_000_000L, bound);
        assertFalse(BgfxWorldStreamRenderer.textureWaitOver(1_000, 1_000, bound));
        assertFalse(BgfxWorldStreamRenderer.textureWaitOver(1_000, 1_000 + bound - 1, bound));
        assertTrue(BgfxWorldStreamRenderer.textureWaitOver(1_000, 1_000 + bound, bound));
        // nanoTime may be negative and wrap; only the difference counts.
        assertTrue(
                BgfxWorldStreamRenderer.textureWaitOver(
                        Long.MAX_VALUE - 10, Long.MAX_VALUE - 10 + bound, bound));
        assertTrue(BgfxWorldStreamRenderer.textureWaitOver(5, 5, 0));
    }

    @Test
    void theLiveSpareLeavesNoLessRoomThanTheAccountingDuringAnOwnerResize() {
        // The owner holds five handles, ten while a resize's old target is returned; the
        // accounting kept OWNER_TEXTURE_RESERVE for it.
        assertEquals(
                BgfxWorldStreamRenderer.OWNER_TEXTURE_RESERVE,
                10 + BgfxWorldStreamRenderer.LIVE_TEXTURE_SPARE);
    }
}
