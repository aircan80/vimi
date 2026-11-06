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

    /** BC2 BLP2 with standard mips from {@code base} down to 1; level 0 blue, the rest red. */
    static byte[] blpChain(int base) {
        int levels = Integer.numberOfTrailingZeros(base) + 1;
        int bytes = 0;
        for (int level = 0; level < levels; level++) {
            int side = Math.max(4, base >> level);
            bytes += (side / 4) * (side / 4) * 16;
        }
        var data = ByteBuffer.allocate(148 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) 2)
                .put((byte) 8)
                .put((byte) 1)
                .put((byte) 1)
                .putInt(base)
                .putInt(base);
        int at = 148;
        for (int level = 0; level < levels; level++) {
            int side = Math.max(4, base >> level);
            int length = (side / 4) * (side / 4) * 16;
            data.putInt(20 + level * 4, at).putInt(84 + level * 4, length);
            short color = (short) (level == 0 ? 0x001f : 0xf800);
            for (int block = 0; block < length / 16; block++) {
                data.position(at + block * 16);
                data.putLong(-1).putShort(color).putShort((short) 0).putInt(0);
            }
            at += length;
        }
        return data.array();
    }

    private static WorldTerrainTextureSource.FileBinding bound(
            WorldTerrainTextureSourceTest.Files files, long id) {
        long length = files.sources.get(id).length;
        return new WorldTerrainTextureSource.FileBinding(id, key(id), length, length, 1 << 20);
    }

    @Test
    void aBaseAbove128SelectsTheLargestLowerMip() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 11;
        files.sources.put(id, blpChain(256));
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var detail = source.detail(bound(files, id), request);
            assertEquals(128, detail.side(), "256 base: level 1 is the largest within 128");
            var pixels = detail.pixels();
            // Level 1 is red; level 0 (blue) was not selected.
            assertEquals((byte) 0xff, pixels.get(0));
            assertEquals(0, pixels.get(2));
            assertEquals(128 * 128 * 4, pixels.remaining());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aContentKeyChangeDuringTheReadFailsAndCachesNothing() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 10;
        files.sources.put(id, WorldTerrainTextureSourceTest.blpForDetail(0xf800));
        files.changedId = id;
        files.changeAfterRead = true;
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            assertThrows(RuntimeException.class, () -> source.detail(bound(files, id), request));
            assertEquals(0, source.details.size(), "A changed source is never cached");
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void detailPixelsCannotBeMutatedThroughTheRecord() {
        byte[] input = new byte[4 * 4 * 4];
        var value = new TerrainDiffuseDetails.Detail(7, key(7), 4, input);
        input[0] = 9;
        assertEquals(0, value.rgba()[0], "The constructor copies its input");
        value.rgba()[0] = 9;
        assertEquals(0, value.rgba()[0], "The accessor returns a copy");
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
