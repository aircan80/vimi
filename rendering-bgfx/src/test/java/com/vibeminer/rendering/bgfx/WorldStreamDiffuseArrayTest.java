package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorldStreamDiffuseArrayTest {
    private static WorldStreamDiffuseArray.Key key(long id) {
        return new WorldStreamDiffuseArray.Key(id, String.format("%032x", id));
    }

    private static List<WorldStreamDiffuseArray.Key> keys(long from, long to) {
        var out = new ArrayList<WorldStreamDiffuseArray.Key>();
        for (long id = from; id < to; id++) {
            out.add(key(id));
        }
        return out;
    }

    @Test
    void layoutIsAFullChainPerLayerAndAFixedArray() {
        assertEquals(87_380, WorldStreamDiffuseArray.LAYER_BYTES);
        assertEquals(1, WorldStreamDiffuseArray.side(WorldStreamDiffuseArray.LEVELS - 1));
        assertEquals(87_380L * 256, WorldStreamDiffuseArray.ARRAY_BYTES);
        // About 21.3 MiB: fixed regardless of view distance.
        assertTrue(WorldStreamDiffuseArray.ARRAY_BYTES < 22L << 20);
    }

    @Test
    void sharedKeysResolveToOneLayerUploadedOnce() {
        var array = new WorldStreamDiffuseArray();
        var first = array.bind(0, List.of(key(10), key(11)));
        assertEquals(2, first.uploads().size());
        var second = array.bind(1, List.of(key(11), key(12)));
        assertEquals(first.layers()[1], second.layers()[0], "Shared diffuse, shared layer");
        assertEquals(1, second.uploads().size(), "Only the new diffuse is uploaded");
        assertEquals(3, array.resident());
        assertEquals(3, array.referenced());
    }

    @Test
    void referencedLayersAreNeverEvictedAndAFullArrayFallsBack() {
        var array = new WorldStreamDiffuseArray();
        var full = array.bind(0, keys(1, 1 + WorldStreamDiffuseArray.CAPACITY));
        assertEquals(WorldStreamDiffuseArray.CAPACITY, full.uploads().size());
        var refused = array.bind(1, List.of(key(5000)));
        assertArrayEquals(new int[] {-1}, refused.layers(), "No free layer: atlas fallback");
        assertTrue(refused.uploads().isEmpty());
        // Releasing the first slot leaves its layers cached; a new key evicts the least recent.
        array.release(0);
        assertEquals(0, array.referenced());
        var evicting = array.bind(1, List.of(key(5000)));
        assertNotEquals(-1, evicting.layers()[0]);
        assertEquals(1, evicting.uploads().size());
        assertEquals(WorldStreamDiffuseArray.CAPACITY, array.resident());
    }

    @Test
    void releasedLayersStayCachedForTheNextTile() {
        var array = new WorldStreamDiffuseArray();
        var first = array.bind(0, List.of(key(10)));
        array.release(0);
        var again = array.bind(3, List.of(key(10)));
        assertEquals(first.layers()[0], again.layers()[0]);
        assertTrue(again.uploads().isEmpty(), "A cached layer needs no upload");
    }

    @Test
    void rebindingASlotNeverEvictsALayerItKeeps() {
        var array = new WorldStreamDiffuseArray();
        // Fill the array; slot 0 holds the last key.
        array.bind(1, keys(1, WorldStreamDiffuseArray.CAPACITY));
        var kept = array.bind(0, List.of(key(9999)));
        array.release(1);
        // Rebind slot 0 to a full array's worth of new keys with the kept key listed last. A
        // release-first rebind would let the new keys evict the kept layer, leaving it -1.
        var wanted = new ArrayList<>(keys(20_000, 20_000 + WorldStreamDiffuseArray.CAPACITY));
        wanted.add(key(9999));
        var rebound = array.bind(0, wanted);
        assertEquals(kept.layers()[0], rebound.layers()[wanted.size() - 1]);
        assertEquals(WorldStreamDiffuseArray.CAPACITY - 1, rebound.uploads().size());
        assertEquals(
                1,
                java.util.Arrays.stream(rebound.layers()).filter(layer -> layer < 0).count(),
                "One new key finds no layer; never the kept one");
    }

    @Test
    void idleLayersAreEvictedLeastRecentlyReleasedFirst() {
        var array = new WorldStreamDiffuseArray();
        int a = array.bind(0, List.of(key(1))).layers()[0];
        int b = array.bind(1, List.of(key(2))).layers()[0];
        int c = array.bind(2, List.of(key(3))).layers()[0];
        array.bind(3, keys(100, 100 + WorldStreamDiffuseArray.CAPACITY - 3));
        // Release order B, A, C: B is the least recently released.
        array.release(1);
        array.release(0);
        array.release(2);
        assertEquals(b, array.bind(4, List.of(key(500))).layers()[0], "B evicted first");
        assertEquals(key(500), array.key(b));
        assertEquals(a, array.bind(5, List.of(key(501))).layers()[0], "then A");
        assertEquals(c, array.bind(6, List.of(key(502))).layers()[0], "then C");
        assertArrayEquals(new int[] {-1}, array.bind(7, List.of(key(503))).layers(), "then none");
        // A re-referenced idle layer leaves the eviction queue.
        array.release(6);
        array.bind(8, List.of(key(502)));
        assertArrayEquals(new int[] {-1}, array.bind(9, List.of(key(504))).layers());
    }

    @Test
    void discardedLayersAreFreedAndForgotten() {
        var array = new WorldStreamDiffuseArray();
        var bound = array.bind(0, List.of(key(10)));
        int layer = bound.layers()[0];
        array.discard(layer);
        assertNull(array.key(layer));
        assertEquals(0, array.resident());
        var again = array.bind(0, List.of(key(10)));
        assertEquals(1, again.uploads().size(), "A discarded layer is uploaded again");
    }

    @Test
    void chainOfAUniformDiffuseIsUniformAtEveryLevel() {
        var source = ByteBuffer.allocate(8 * 8 * 4);
        for (int i = 0; i < 64; i++) {
            source.put((byte) 200).put((byte) 100).put((byte) 50).put((byte) 255);
        }
        source.flip();
        var chain = WorldStreamDiffuseArray.chain(source, 8);
        assertEquals(WorldStreamDiffuseArray.LAYER_BYTES, chain.capacity());
        for (int level = 0; level < WorldStreamDiffuseArray.LEVELS; level++) {
            int at = WorldStreamDiffuseArray.offset(level);
            int side = WorldStreamDiffuseArray.side(level);
            for (int texel = 0; texel < side * side; texel++) {
                assertEquals((byte) 200, chain.get(at + texel * 4));
                assertEquals((byte) 50, chain.get(at + texel * 4 + 2));
            }
        }
    }

    @Test
    void enlargementWrapsBecauseTheDiffuseRepeats() {
        // A 2 x 2 checker: the enlarged edges blend with the opposite edge, not with black.
        var source = ByteBuffer.allocate(16);
        int[] values = {0, 255, 255, 0};
        for (int v : values) {
            source.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
        }
        source.flip();
        var chain = WorldStreamDiffuseArray.chain(source, 2);
        // Texel (0, 0) of level 0 lies a quarter texel from the source corner: a wrap blend.
        int corner = chain.get(0) & 255;
        assertTrue(corner > 0 && corner < 255, "Corner blends across the wrap: " + corner);
        // The 1 x 1 level is the mean of the checker.
        int last = WorldStreamDiffuseArray.offset(WorldStreamDiffuseArray.LEVELS - 1);
        assertEquals(128, chain.get(last) & 255, 1);
        assertEquals(255, chain.get(last + 3) & 255);
    }

    @Test
    void chainRefusesNonSquareOrOversizedSources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamDiffuseArray.chain(ByteBuffer.allocate(256 * 256 * 4), 256));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamDiffuseArray.chain(ByteBuffer.allocate(3 * 3 * 4), 3));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamDiffuseArray.chain(ByteBuffer.allocate(10), 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamDiffuseArray.Key(0, "a".repeat(32)));
    }
}
