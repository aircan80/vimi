package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainLayerBlocks;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        // 256 x 256 BC1 down to 1 x 1 per layer: about 10.7 MiB for the whole array.
        assertEquals(43_704, WorldStreamDiffuseArray.LAYER_BYTES);
        assertEquals(43_704L * 256, WorldStreamDiffuseArray.ARRAY_BYTES);
        assertTrue(WorldStreamDiffuseArray.ARRAY_BYTES < 11L << 20);
        // The RGBA8 fallback: 128 x 128 down to 1 x 1, about 21.3 MiB.
        assertEquals(87_380, WorldStreamDiffuseArray.FALLBACK_LAYER_BYTES);
        assertEquals(
                1,
                WorldStreamDiffuseArray.fallbackSide(WorldStreamDiffuseArray.FALLBACK_LEVELS - 1));
        assertTrue(WorldStreamDiffuseArray.FALLBACK_ARRAY_BYTES < 22L << 20);
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
    void fallbackIsTheChainsLowerLevelsDecoded() {
        // Level k of the layer chain: blocks of one colour per level.
        var chain =
                ByteBuffer.allocate(WorldStreamDiffuseArray.LAYER_BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
        for (int level = 0; level < WorldStreamDiffuseArray.LEVELS; level++) {
            int colour = (level * 3) << 11 | (level * 7) << 5 | level;
            for (int at = TerrainLayerBlocks.levelOffset(level);
                    at
                            < TerrainLayerBlocks.levelOffset(level)
                                    + TerrainLayerBlocks.levelBytes(level);
                    at += 8) {
                chain.putShort(at, (short) colour).putShort(at + 2, (short) 0).putInt(at + 4, 0);
            }
        }
        var rgba = WorldStreamDiffuseArray.fallback(chain.asReadOnlyBuffer());
        assertEquals(WorldStreamDiffuseArray.FALLBACK_LAYER_BYTES, rgba.capacity());
        for (int level = 0; level < WorldStreamDiffuseArray.FALLBACK_LEVELS; level++) {
            int source = level + 1, side = WorldStreamDiffuseArray.fallbackSide(level);
            int r = source * 3, g = source * 7;
            int last = WorldStreamDiffuseArray.fallbackOffset(level) + (side * side - 1) * 4;
            assertEquals((r << 3) | (r >>> 2), rgba.get(last) & 255, "level " + level);
            assertEquals((g << 2) | (g >>> 4), rgba.get(last + 1) & 255, "level " + level);
            assertEquals(255, rgba.get(last + 3) & 255);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldStreamDiffuseArray.fallback(ByteBuffer.allocate(87_380)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamDiffuseArray.Key(0, "a".repeat(32)));
    }
}
