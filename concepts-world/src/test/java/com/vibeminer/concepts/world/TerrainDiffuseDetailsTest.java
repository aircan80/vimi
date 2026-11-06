package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TerrainDiffuseDetailsTest {
    private static String key(long id) {
        return String.format("%032x", id);
    }

    private static TerrainDiffuseDetails.Detail detail(long id, int side) {
        return new TerrainDiffuseDetails.Detail(id, key(id), side, new byte[side * side * 4]);
    }

    @Test
    void cacheIsBoundedByEntriesAndEvictsLeastRecentlyUsed() {
        var cache = new TerrainDiffuseDetails();
        for (long id = 1; id <= TerrainDiffuseDetails.MAX_ENTRIES; id++) {
            cache.put(detail(id, TerrainDiffuseDetails.MAX_SIDE));
        }
        assertEquals(TerrainDiffuseDetails.MAX_ENTRIES, cache.size());
        assertEquals(TerrainDiffuseDetails.MAX_BYTES, cache.bytes());
        // Touch the oldest entry, then overflow by one: the second oldest is evicted.
        assertTrue(cache.get(1, key(1)) != null);
        cache.put(detail(1000, TerrainDiffuseDetails.MAX_SIDE));
        assertEquals(TerrainDiffuseDetails.MAX_ENTRIES, cache.size());
        assertTrue(cache.get(1, key(1)) != null);
        assertNull(cache.get(2, key(2)));
        assertTrue(cache.bytes() <= TerrainDiffuseDetails.MAX_BYTES);
    }

    @Test
    void replacingAnEntryKeepsTheByteCountExact() {
        var cache = new TerrainDiffuseDetails();
        cache.put(detail(7, 8));
        cache.put(detail(7, 16));
        assertEquals(1, cache.size());
        assertEquals(16 * 16 * 4, cache.bytes());
        assertNull(cache.get(7, "e".repeat(32)), "A different content key is a different entry");
    }

    @Test
    void detailsAreSquarePowerOfTwoWithinTheLayerSide() {
        for (int side : new int[] {0, 3, 6, 256}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new TerrainDiffuseDetails.Detail(
                                    7, key(7), side, new byte[side * side * 4]));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainDiffuseDetails.Detail(7, key(7), 4, new byte[63]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainDiffuseDetails.Detail(7, "X".repeat(32), 4, new byte[64]));
        assertTrue(detail(7, 4).pixels().isReadOnly());
    }

    @Test
    void sourceDecodesTheLargestMipWithinTheLayerSideOnceAndRevalidates() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 10;
        files.sources.put(id, WorldTerrainTextureSourceTest.blpForDetail(0xf800));
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            long length = files.sources.get(id).length;
            // As stat() binds it: the recorded size is both the length and the declared size.
            var binding =
                    new WorldTerrainTextureSource.FileBinding(id, key(id), length, length, 1 << 20);
            var first = source.detail(binding, request);
            // The authored BLP2 is 8 x 8 at its base: the largest mip within 128.
            assertEquals(8, first.side());
            var pixels = first.pixels().order(ByteOrder.LITTLE_ENDIAN);
            // BC2 color 0xf800 is pure red with full alpha.
            assertEquals((byte) 0xff, pixels.get(0));
            assertEquals(0, pixels.get(1));
            assertEquals(0, pixels.get(2));
            assertEquals((byte) 0xff, pixels.get(3));
            int opens = files.opens(id);
            assertSame(first, source.detail(binding, request), "Second request is a cache hit");
            assertEquals(opens, files.opens(id), "A cache hit reads nothing");
            assertEquals(1, source.details.hits());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void absentDiffuseIsASourceFailureAndCachesNothing() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var binding = new WorldTerrainTextureSource.FileBinding(99, key(99), -1, null, 1 << 20);
            assertThrows(RuntimeException.class, () -> source.detail(binding, request));
            assertEquals(0, source.details.size());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    static ByteBuffer unused() {
        return ByteBuffer.allocate(0);
    }
}
