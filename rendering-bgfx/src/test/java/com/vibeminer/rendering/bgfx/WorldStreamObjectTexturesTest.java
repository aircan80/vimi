package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Object textures shared across sets by exact image identity, reference counted (#173). */
final class WorldStreamObjectTexturesTest {
    private final List<Short> destroyed = new ArrayList<>();
    private final WorldStreamObjectTextures cache = new WorldStreamObjectTextures(destroyed::add);
    private short nextHandle = 100;

    private static WorldStreamObjectTextures.Key key(long id) {
        return new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC1, 64, 64, id, ~id);
    }

    private static WorldStreamObjectTextures.Key[] keys(long... ids) {
        var keys = new WorldStreamObjectTextures.Key[ids.length];
        for (int i = 0; i < ids.length; i++) {
            keys[i] = key(ids[i]);
        }
        return keys;
    }

    private static long[] sizes(int count, long each) {
        var sizes = new long[count];
        java.util.Arrays.fill(sizes, each);
        return sizes;
    }

    /** Plans, holds and builds a whole set the way the renderer does: creates and fills its own. */
    private WorldStreamObjectTextures.Entry[] build(long... ids) {
        var plan = cache.plan(keys(ids), sizes(ids.length, 1000));
        var held = cache.hold(plan);
        for (int i = 0; i < held.length; i++) {
            if (plan.creates(i)) {
                cache.created(held[i], nextHandle++);
                cache.issued(held[i], 1000);
                cache.complete(held[i]);
            }
        }
        return held;
    }

    private void releaseAll(WorldStreamObjectTextures.Entry[] held) {
        for (var entry : held) {
            cache.release(entry);
        }
    }

    @Test
    void identityIsTheUploadFormatSizeAndDigest() {
        var a = new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC1, 64, 64, 1, 2);
        assertEquals(a, new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC1, 64, 64, 1, 2));
        assertFalse(
                a.equals(
                        new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC3, 64, 64, 1, 2)));
        assertFalse(
                a.equals(
                        new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC1, 32, 32, 1, 2)));
        assertFalse(
                a.equals(
                        new WorldStreamObjectTextures.Key(ObjectTextureFormats.BC1, 64, 64, 1, 3)));
    }

    @Test
    void packingGivesTheSameChainInDifferentSetsTheSameKeyAndOtherChainsOthers() {
        try (var one =
                        WorldStreamPackedObjects.pack(
                                WorldStreamObjectsTextureBudgetProof.images(3, 7), () -> false);
                var two =
                        WorldStreamPackedObjects.pack(
                                WorldStreamObjectsTextureBudgetProof.images(4, 7), () -> false);
                var other =
                        WorldStreamPackedObjects.pack(
                                WorldStreamObjectsTextureBudgetProof.images(3, 8), () -> false)) {
            for (int i = 0; i < 3; i++) {
                assertEquals(one.key(i), two.key(i), "Same chain, same key");
                assertFalse(one.key(i).equals(other.key(i)), "Other texels, other key");
                assertEquals(ObjectTextureFormats.RGBA8, one.key(i).format());
                assertEquals(4, one.key(i).width());
                assertEquals(one.imageBytes(i), one.pixels.capacity() / 3);
            }
            assertFalse(one.key(0).equals(one.key(1)));
            assertEquals(one.payloadBytes(), one.bufferBytes() + one.pixels.capacity());
        }
    }

    @Test
    void theDigestCoversTheStoredFormatAndSizeNotOnlyTheBytes() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[84];
        long[] rgba = WorldStreamPackedObjects.identity(digest, 0, 4, 4, bytes, 0, 84);
        assertArrayEquals(rgba, WorldStreamPackedObjects.identity(digest, 0, 4, 4, bytes, 0, 84));
        assertFalse(
                java.util.Arrays.equals(
                        rgba, WorldStreamPackedObjects.identity(digest, 1, 4, 4, bytes, 0, 84)));
        assertFalse(
                java.util.Arrays.equals(
                        rgba, WorldStreamPackedObjects.identity(digest, 0, 8, 2, bytes, 0, 84)));
        bytes[83] = 1;
        assertFalse(
                java.util.Arrays.equals(
                        rgba, WorldStreamPackedObjects.identity(digest, 0, 4, 4, bytes, 0, 84)));
    }

    @Test
    void aSecondSetSharesEveryCompleteTextureAndNeedsNoHandleForIt() {
        var first = build(1, 2, 3);
        assertEquals(3, cache.entries());
        assertEquals(3000, cache.reservedBytes());
        var plan = cache.plan(keys(2, 3, 4), sizes(3, 1000));
        assertEquals(1, plan.handles());
        assertEquals(1000, plan.newBytes());
        assertSame(first[1], plan.shared()[0]);
        assertSame(first[2], plan.shared()[1]);
        assertTrue(plan.creates(2));
        var second = cache.hold(plan);
        assertEquals(4, cache.entries());
        assertEquals(6, cache.references());
        assertEquals(2, cache.sharedReferences());
        assertEquals(first[1].handle(), second[0].handle());
        assertEquals(1, cache.uncreated());
        assertEquals(4000, cache.reservedBytes(), "A shared texture is charged once");
    }

    @Test
    void repeatsInsideOneSetCreateOneTexture() {
        var plan = cache.plan(keys(5, 6, 5, 5), sizes(4, 1000));
        assertEquals(2, plan.handles());
        assertEquals(2000, plan.newBytes());
        assertArrayEquals(new int[] {-1, -1, 0, 0}, plan.repeats());
        var held = cache.hold(plan);
        assertSame(held[0], held[2]);
        assertSame(held[0], held[3]);
        assertEquals(3, held[0].references());
        assertEquals(2, cache.entries());
        cache.created(held[0], (short) 7);
        releaseAll(held);
        assertEquals(List.of((short) 7), destroyed, "Created once, destroyed once");
        assertEquals(0, cache.entries());
        assertEquals(0, cache.uncreated());
    }

    @Test
    void theLastReleaseDestroysAndFreesTheChargeEarlierOnesDoNot() {
        var first = build(1, 2);
        var second = build(1, 2);
        assertEquals(2, cache.entries());
        assertEquals(2000, cache.issuedBytes());
        releaseAll(first);
        assertTrue(destroyed.isEmpty(), "Still drawn by the second set");
        assertEquals(2, cache.entries());
        assertEquals(2000, cache.reservedBytes());
        releaseAll(second);
        assertEquals(List.of((short) 100, (short) 101), destroyed);
        assertEquals(0, cache.entries());
        assertEquals(0, cache.references());
        assertEquals(0, cache.reservedBytes());
        assertEquals(0, cache.issuedBytes());
        // Released identities are not shared any more: a later set creates them again.
        assertEquals(2, cache.plan(keys(1, 2), sizes(2, 1000)).handles());
    }

    @Test
    void aTextureStillBeingFilledIsNotSharedAndAFailedBuildDestroysOnlyItsOwn() {
        var resident = build(1);
        var plan = cache.plan(keys(1, 2, 3), sizes(3, 1000));
        var building = cache.hold(plan);
        cache.created(building[1], (short) 50);
        cache.issued(building[1], 400); // partly filled; image 3 not created yet
        // Another set planned now shares only the complete texture.
        var other = cache.plan(keys(1, 2), sizes(2, 1000));
        assertSame(resident[0], other.shared()[0]);
        assertTrue(other.creates(1), "A partly filled texture is never shared");
        assertEquals(1, cache.uncreated());
        // The build fails: its partly filled texture and its reservation go, the shared one stays.
        releaseAll(building);
        assertEquals(List.of((short) 50), destroyed);
        assertEquals(1, cache.entries());
        assertEquals(0, cache.uncreated());
        assertEquals(1000, cache.reservedBytes());
        assertEquals(1000, cache.issuedBytes());
        assertEquals(1, resident[0].references());
    }

    @Test
    void twoSetsFillingTheSameIdentityKeepTheFirstCompleteOneShared() {
        var planA = cache.plan(keys(9), sizes(1, 1000));
        var a = cache.hold(planA);
        var planB = cache.plan(keys(9), sizes(1, 1000));
        assertTrue(planB.creates(0));
        var b = cache.hold(planB);
        assertNotSame(a[0], b[0]);
        cache.created(a[0], (short) 1);
        cache.created(b[0], (short) 2);
        cache.complete(b[0]);
        cache.complete(a[0]);
        assertSame(b[0], cache.plan(keys(9), sizes(1, 1000)).shared()[0]);
        cache.release(b[0]);
        assertEquals(List.of((short) 2), destroyed);
        // The private duplicate never became shared: the identity is free again.
        assertTrue(cache.plan(keys(9), sizes(1, 1000)).creates(0));
        cache.release(a[0]);
        assertEquals(List.of((short) 2, (short) 1), destroyed);
        assertEquals(0, cache.entries());
    }

    @Test
    void misuseIsRefused() {
        var held = build(1);
        cache.release(held[0]);
        assertThrows(IllegalStateException.class, () -> cache.release(held[0]));
        var plan = cache.plan(keys(2), sizes(1, 10));
        var reserved = cache.hold(plan);
        assertThrows(IllegalStateException.class, () -> cache.issued(reserved[0], 1));
        assertThrows(IllegalStateException.class, () -> cache.complete(reserved[0]));
        cache.created(reserved[0], (short) 3);
        assertThrows(IllegalStateException.class, () -> cache.created(reserved[0], (short) 4));
        // A plan made before its shared texture was released cannot be held.
        cache.complete(reserved[0]);
        var stale = cache.plan(keys(2), sizes(1, 10));
        cache.release(reserved[0]);
        assertThrows(IllegalStateException.class, () -> cache.hold(stale));
        assertThrows(IllegalArgumentException.class, () -> cache.plan(keys(1, 2), sizes(1, 10)));
        assertEquals(0, cache.entries());
    }
}
