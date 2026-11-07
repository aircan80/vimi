package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.TerrainLayerBlocks;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TerrainDiffuseDetailsTest {
    private static String key(long id) {
        return String.format("%032x", id);
    }

    private static TerrainDiffuseDetails.Detail detail(long id) {
        return new TerrainDiffuseDetails.Detail(
                id, key(id), new byte[TerrainLayerBlocks.CHAIN_BYTES], false, "BGRA8");
    }

    /** RGB of texel 0 of one chain level. */
    private static int[] texel(TerrainDiffuseDetails.Detail detail, int level) {
        int side = TerrainLayerBlocks.side(level);
        var rgba =
                ObjectTextureFormats.decodeLevel(
                        ObjectTextureFormats.BC1,
                        side,
                        side,
                        detail.blocks(),
                        TerrainLayerBlocks.levelOffset(level));
        return new int[] {rgba[0] & 255, rgba[1] & 255, rgba[2] & 255};
    }

    @Test
    void cacheIsBoundedByEntriesAndEvictsLeastRecentlyUsed() {
        var cache = new TerrainDiffuseDetails();
        for (long id = 1; id <= TerrainDiffuseDetails.MAX_ENTRIES; id++) {
            cache.put(detail(id));
        }
        assertEquals(TerrainDiffuseDetails.MAX_ENTRIES, cache.size());
        assertEquals(TerrainDiffuseDetails.MAX_BYTES, cache.bytes());
        // Touch the oldest entry, then overflow by one: the second oldest is evicted.
        assertTrue(cache.get(1, key(1)) != null);
        cache.put(detail(1000));
        assertEquals(TerrainDiffuseDetails.MAX_ENTRIES, cache.size());
        assertTrue(cache.get(1, key(1)) != null);
        assertNull(cache.get(2, key(2)));
        assertTrue(cache.bytes() <= TerrainDiffuseDetails.MAX_BYTES);
    }

    @Test
    void replacingAnEntryKeepsTheByteCountExact() {
        var cache = new TerrainDiffuseDetails();
        cache.put(detail(7));
        cache.put(detail(7));
        assertEquals(1, cache.size());
        assertEquals(TerrainLayerBlocks.CHAIN_BYTES, cache.bytes());
        assertNull(cache.get(7, "e".repeat(32)), "A different content key is a different entry");
    }

    @Test
    void detailsAreExactlyOneLayerChain() {
        assertEquals(256L * 43_704, TerrainDiffuseDetails.MAX_BYTES);
        for (int bytes : new int[] {0, TerrainLayerBlocks.CHAIN_BYTES - 1, 128 * 128 * 4}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new TerrainDiffuseDetails.Detail(
                                    7, key(7), new byte[bytes], true, "BC1"));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new TerrainDiffuseDetails.Detail(
                                7,
                                "X".repeat(32),
                                new byte[TerrainLayerBlocks.CHAIN_BYTES],
                                true,
                                "BC1"));
        assertTrue(detail(7).blocks().isReadOnly());
    }

    @Test
    void aSmallDiffuseIsDecodedEncodedOnceAndRevalidates() throws Exception {
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
            // The authored BLP2 is 8 x 8: too small to carry, so it is enlarged and encoded.
            assertFalse(first.fileBlocks());
            assertEquals("BC2", first.sourceEncoding());
            assertEquals(1, source.details.encodedLayers(), "one lossy encode counted");
            assertEquals(0, source.details.fileBlockLayers());
            assertArrayEquals(new int[] {255, 0, 0}, texel(first, 0), "BC2 0xf800 stays red");
            assertArrayEquals(new int[] {255, 0, 0}, texel(first, 8));
            int opens = files.opens(id);
            assertSame(first, source.detail(binding, request), "Second request is a cache hit");
            assertEquals(opens, files.opens(id), "A cache hit reads nothing");
            assertEquals(1, source.details.hits());
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aNonSquareDiffuseIsStretchedToASquareLayer() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 10;
        // 32 x 8, left half red and right half blue: resampled to 32 x 32, then the layer chain.
        files.sources.put(id, WorldTerrainTextureSourceTest.blpBgraHalves(32, 8, 4));
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var detail = source.detail(bound(files, id), request);
            assertFalse(detail.fileBlocks());
            assertEquals("BGRA8", detail.sourceEncoding());
            // Enlarging to the layer side filters across the tile's wrapped edge, so texel 0 is
            // mostly, not purely, red.
            int[] edge = texel(detail, 0);
            assertTrue(edge[0] > edge[2], java.util.Arrays.toString(edge));
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aSquareLayerThatIsNotAPowerOfTwoSaysWhyItIsRefused() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 10;
        files.sources.put(id, WorldTerrainTextureSourceTest.blpBgraHalves(12, 12, 1));
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var refused =
                    assertThrows(
                            RuntimeException.class, () -> source.detail(bound(files, id), request));
            assertEquals(
                    WorldTerrainTextureSource.DiffuseRefusal.MIP_SHAPE,
                    WorldTerrainTextureSource.diffuseRefusal(refused));
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
    void a256DiffuseCarriesItsOwnBlocks() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 11;
        files.sources.put(id, blpChain(256));
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var detail = source.detail(bound(files, id), request);
            assertTrue(detail.fileBlocks(), "a 256 BC2 chain is carried as blocks");
            assertEquals("BC2", detail.sourceEncoding(), "alpha dropped, source format kept");
            assertEquals(1, source.details.fileBlockLayers());
            assertEquals(0, source.details.encodedLayers());
            // Level 0 of the file is blue, every lower level red: the layer keeps that split.
            assertArrayEquals(new int[] {0, 0, 255}, texel(detail, 0));
            for (int level = 1; level < TerrainLayerBlocks.LEVELS; level++) {
                assertArrayEquals(new int[] {255, 0, 0}, texel(detail, level), "level " + level);
            }
        } finally {
            source.closeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void aDiffuseLargerThanOneMebibyteCarriesItsMipAt256() throws Exception {
        var files = new WorldTerrainTextureSourceTest.Files();
        long id = 12;
        byte[] large = blpChain(1024);
        assertTrue(large.length > 1 << 20, "A full 1024 BC2 chain exceeds the old 1 MiB cap");
        files.sources.put(id, large);
        var source = new WorldTerrainTextureSource(files.files(), new TerrainAtlasPool());
        try {
            var request =
                    new WorldTerrainTextureSource.Request(
                            source, null, List.of(), Cancellation.NONE, progress -> {}, false);
            var binding =
                    new WorldTerrainTextureSource.FileBinding(
                            id,
                            key(id),
                            large.length,
                            (long) large.length,
                            WorldTerrainTextureSource.DIFFUSE_SOURCE_LIMIT);
            var detail = source.detail(binding, request);
            assertTrue(detail.fileBlocks());
            assertArrayEquals(new int[] {255, 0, 0}, texel(detail, 0), "File level 2 (256) is red");
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
    void detailBlocksCannotBeMutatedThroughTheRecord() {
        byte[] input = new byte[TerrainLayerBlocks.CHAIN_BYTES];
        var value = new TerrainDiffuseDetails.Detail(7, key(7), input, true, "BC1");
        input[0] = 9;
        assertEquals(0, value.chain()[0], "The constructor copies its input");
        value.chain()[0] = 9;
        assertEquals(0, value.chain()[0], "The accessor returns a copy");
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
