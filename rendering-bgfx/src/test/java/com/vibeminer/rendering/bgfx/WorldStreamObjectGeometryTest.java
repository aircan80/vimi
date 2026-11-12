package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The session's shared object model geometry (#380 stage 4). */
class WorldStreamObjectGeometryTest {
    private static final WorldStreamObjectAssets.Digest A =
            new WorldStreamObjectAssets.Digest(1, 1);
    private static final WorldStreamObjectAssets.Digest B =
            new WorldStreamObjectAssets.Digest(2, 2);

    private final List<short[]> destroyed = new ArrayList<>();
    private short next = 10;

    private WorldStreamObjectGeometry library(long zombies) {
        return new WorldStreamObjectGeometry((v, i) -> destroyed.add(new short[] {v, i}), zombies);
    }

    /** Holds a set of {@code keys}, creating and completing every model it creates. */
    private WorldStreamObjectGeometry.Entry[] build(
            WorldStreamObjectGeometry library, WorldStreamObjectAssets.Digest... keys) {
        var bytes = new long[keys.length];
        java.util.Arrays.fill(bytes, 100);
        var plan = library.plan(keys, bytes);
        var held = library.hold(plan);
        for (int m = 0; m < keys.length; m++) {
            if (plan.creates(m)) {
                library.created(held[m], next++, next++);
                library.complete(held[m]);
            }
        }
        return held;
    }

    @Test
    void aModelTwoSetsPlaceIsUploadedOnceAndChargedOnce() {
        var library = library(0);
        var first = build(library, A, B);
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100});
        assertFalse(plan.creates(0), "complete: shared");
        assertEquals(0, plan.newBytes());
        var second = library.hold(plan);
        assertSame(first[0], second[0]);
        assertEquals(2, first[0].references());
        assertEquals(2, library.entries());
        assertEquals(200, library.reservedBytes());
        assertEquals(1, library.sharedReferences());
    }

    @Test
    void aRepeatInOneSetTakesItsFirstModelsEntry() {
        var library = library(0);
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A, A}, new long[] {100, 100});
        assertTrue(plan.creates(0));
        assertFalse(plan.creates(1));
        assertEquals(0, plan.repeats()[1]);
        assertEquals(100, plan.newBytes());
        var held = library.hold(plan);
        assertSame(held[0], held[1]);
        assertEquals(2, held[0].references());
    }

    @Test
    void anUnfinishedEntryIsNotSharedAndIsDestroyedWithItsSet() {
        var library = library(1 << 20);
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100});
        var held = library.hold(plan);
        library.created(held[0], (short) 3, (short) 4);
        // Not complete: another set plans to create its own.
        assertTrue(
                library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100})
                        .creates(0));
        library.release(held[0]);
        assertEquals(1, destroyed.size(), "a failed build is destroyed, never a zombie");
        assertEquals(0, library.entries());
        assertEquals(0, library.reservedBytes());
    }

    @Test
    void theLastReleaseKeepsASharedEntryAsAZombieThatComesBack() {
        var library = library(1 << 20);
        var held = build(library, A);
        library.release(held[0]);
        assertTrue(destroyed.isEmpty());
        assertEquals(100, library.zombieBytes());
        assertEquals(100, library.reservedBytes(), "a zombie stays charged");
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100});
        assertFalse(plan.creates(0), "a zombie is shared");
        var again = library.hold(plan);
        assertSame(held[0], again[0]);
        assertEquals(0, library.zombieBytes());
        assertEquals(1, library.revived());
    }

    @Test
    void zombiesAreDestroyedOldestFirstForRoomOrPastTheirLimit() {
        var library = library(150);
        var a = build(library, A);
        var b = build(library, B);
        library.release(a[0]);
        library.release(b[0]);
        assertEquals(1, destroyed.size(), "past the 150-byte limit the oldest goes");
        assertEquals(1, library.evicted());
        assertEquals(100, library.zombieBytes());
        assertTrue(
                library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100})
                        .creates(0));
        assertEquals(100, library.evict(1));
        assertEquals(0, library.zombieBytes());
        assertEquals(0, library.reservedBytes());
        assertEquals(0, library.entries());
    }

    @Test
    void withoutAZombieLimitTheLastReleaseDestroys() {
        var library = library(0);
        var held = build(library, A);
        library.release(held[0]);
        assertEquals(1, destroyed.size());
        assertEquals(0, library.entries());
    }

    @Test
    void closeDestroysTheZombies() {
        var library = library(1 << 20);
        library.release(build(library, A)[0]);
        library.release(build(library, B)[0]);
        library.close();
        assertEquals(2, destroyed.size());
        assertEquals(0, library.reservedBytes());
    }

    @Test
    void evictingForAPlanKeepsTheZombiesItShares() {
        var library = library(1 << 20);
        var a = build(library, A);
        var b = build(library, B);
        library.release(a[0]);
        library.release(b[0]);
        // A is the oldest zombie, but the plan shares it: B goes instead.
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100});
        assertSame(a[0], plan.shared()[0]);
        assertEquals(100, library.evict(100, plan));
        assertEquals(1, library.entries());
        assertEquals(100, library.zombieBytes());
        assertTrue(a[0].complete(), "the shared zombie is kept");
        assertEquals(0, library.evict(100, plan), "nothing else to give");
        var held = library.hold(plan);
        assertSame(a[0], held[0]);
        assertEquals(1, library.revived());
        assertEquals(0, library.zombieBytes());
    }

    @Test
    void aPlanStaleAfterAnEvictionIsRefused() {
        var library = library(1 << 20);
        library.release(build(library, A)[0]);
        var plan = library.plan(new WorldStreamObjectAssets.Digest[] {A}, new long[] {100});
        library.evict(100);
        assertThrows(IllegalStateException.class, () -> library.hold(plan));
        assertEquals(0, library.references());
    }

    @Test
    void twoSetsFillingOneAssetEachKeepTheirOwnAndTheFirstCompleteIsShared() {
        var library = library(0);
        var keys = new WorldStreamObjectAssets.Digest[] {A};
        var first = library.hold(library.plan(keys, new long[] {100}));
        var second = library.hold(library.plan(keys, new long[] {100}));
        library.created(first[0], (short) 1, (short) 2);
        library.created(second[0], (short) 3, (short) 4);
        library.complete(second[0]);
        library.complete(first[0]);
        var plan = library.plan(keys, new long[] {100});
        assertSame(second[0], plan.shared()[0]);
        library.release(first[0]);
        assertEquals(1, destroyed.size(), "the private copy goes with its set");
        assertNull(null);
        assertThrows(IllegalStateException.class, () -> library.release(first[0]));
    }
}
