package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class WorldTextureFormatsTest {
    @Test
    void publicMetadataChecksBoundsBeforeCopyingAndRejectsImpossibleResults() {
        var oversized =
                new AbstractList<WorldTextureFormats.Layer>() {
                    @Override
                    public int size() {
                        return 9;
                    }

                    @Override
                    public WorldTextureFormats.Layer get(int index) {
                        throw new AssertionError("Oversized list must not be copied");
                    }
                };
        var none = WorldTextureFormats.ChunkIssue.NONE;
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldTextureFormats.ChunkTexture(0, oversized, none));
        for (int ordinal : new int[] {-1, 256}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorldTextureFormats.ChunkTexture(ordinal, List.of(), none));
        }
        for (int count : new int[] {-1, 8}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new WorldTextureFormats.AlphaResult(count, none));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldTextureFormats.AlphaResult(
                                1, WorldTextureFormats.ChunkIssue.MASK_ENCODING));
        assertThrows(
                NullPointerException.class, () -> new WorldTextureFormats.AlphaResult(0, null));
        assertEquals(7, new WorldTextureFormats.AlphaResult(7, none).maskCount());
    }

    private static byte[] ints(int... values) {
        var b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) {
            b.putInt(v);
        }
        return b.array();
    }

    private static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] part(String name, byte[] bytes) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        return join(ints(tag, bytes.length), bytes);
    }

    private static byte[] rle(int value) {
        var out = new ByteArrayOutputStream();
        for (int at = 0; at < 4096; ) {
            int n = Math.min(127, 4096 - at);
            out.write(128 | n);
            out.write(value);
            at += n;
        }
        return out.toByteArray();
    }

    private static byte[] chunk(int overlays, boolean raw) {
        var layers = ByteBuffer.allocate((overlays + 1) * 16).order(ByteOrder.LITTLE_ENDIAN);
        layers.putInt(0).putInt(0).putInt(0).putInt(77);
        var alpha = new ByteArrayOutputStream();
        for (int i = 0; i < overlays; i++) {
            byte[] mask;
            if (raw) {
                mask = new byte[4096];
                Arrays.fill(mask, (byte) (i + 1));
            } else {
                mask = rle(i + 1);
            }
            layers.putInt(0).putInt(raw ? 0x100 : 0x300).putInt(alpha.size()).putInt(123 + i);
            alpha.writeBytes(mask);
        }
        return join(part("MCLY", layers.array()), part("MCAL", alpha.toByteArray()));
    }

    private static byte[] texture(byte[] first) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                join(
                        part("MVER", ints(18)),
                        part("MAMP", ints(0)),
                        part("MDID", ints(12345)),
                        part("MHID", ints(0))));
        out.writeBytes(part("MCNK", first));
        for (int i = 1; i < 256; i++) {
            out.writeBytes(part("MCNK", chunk(0, false)));
        }
        return out.toByteArray();
    }

    private static ByteBuffer bytes(byte[] source) {
        return ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static WorldTextureFormats.AlphaResult decode(byte[] source, ByteBuffer target) {
        try (var data = WorldTextureFormats.read(source, () -> false)) {
            return data.decodeAlphas(0, target, () -> false);
        }
    }

    @Test
    void rawAndRleMaskBytesAreExactDetachedAndCallerScratchIsNeverRetained() {
        for (boolean raw : new boolean[] {true, false}) {
            byte[] source = texture(chunk(2, raw));
            try (var data = WorldTextureFormats.read(source, () -> false)) {
                Arrays.fill(source, (byte) 0);
                var target = ByteBuffer.allocate(8192 + 11);
                target.position(11);
                var result = data.decodeAlphas(0, target, () -> false);
                assertTrue(result.admitted());
                assertEquals(2, result.maskCount());
                assertEquals(8203, target.position());
                for (int i = 0; i < 4096; i++) {
                    assertEquals(1, target.get(11 + i));
                    assertEquals(2, target.get(4107 + i));
                }
                target.put(11, (byte) 99);
                target.position(11);
                data.decodeAlphas(0, target, () -> false);
                assertEquals(1, target.get(11));
                assertSame(data.chunks(), data.chunks());
                assertThrows(UnsupportedOperationException.class, () -> data.diffuseIds().clear());
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> data.chunks().getFirst().layers().clear());
                assertTrue(data.metadataBytes() < WorldTextureFormats.MAX_METADATA_BYTES);
            }
        }
    }

    @Test
    void fullEightLayerTileUsesOnlyPerChunkScratchAndOwnerCloseReleasesSource() {
        byte[] all = chunk(7, true);
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                join(
                        part("MVER", ints(18)),
                        part("MAMP", ints(0)),
                        part("MDID", ints(123)),
                        part("MHID", ints(0))));
        for (int i = 0; i < 256; i++) {
            out.writeBytes(part("MCNK", all));
        }
        var data = WorldTextureFormats.read(out.toByteArray(), () -> false);
        var scratch = ByteBuffer.allocate(WorldTextureFormats.MAX_MASK_SCRATCH);
        int retained = data.retainedSourceBytes();
        for (int i = 0; i < 256; i++) {
            scratch.clear();
            var result = data.decodeAlphas(i, scratch, () -> false);
            assertTrue(result.admitted());
            assertEquals(7, result.maskCount());
            assertEquals(28672, scratch.position());
            assertEquals(retained, data.retainedSourceBytes());
        }
        assertEquals(256, data.chunks().size());
        assertTrue(data.metadataBytes() < 1 << 20);
        assertThrows(
                IllegalArgumentException.class,
                () -> data.decodeAlphas(0, ByteBuffer.allocate(28671), () -> false));
        data.close();
        data.close();
        assertEquals(0, data.retainedSourceBytes());
        assertThrows(IllegalStateException.class, () -> data.decodeAlphas(0, scratch, () -> false));
    }

    @Test
    void invalidReferencesIntervalsAndMissingAlphaFlagDeclineOnlyTheirChunk() {
        for (int[] mutation : new int[][] {{16, -1}, {24, 1}, {28, 0x200}, {28, 0}, {32, 1}}) {
            byte[] first = chunk(1, false);
            bytes(first).putInt(mutation[0], mutation[1]);
            try (var data = WorldTextureFormats.read(texture(first), () -> false)) {
                assertFalse(
                        data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).admitted());
                assertTrue(data.decodeAlphas(1, ByteBuffer.allocate(0), () -> false).admitted());
            }
        }
        // Animation, overbright and reflection bits are carried, not refused.
        for (int[] mutation :
                new int[][] {{12, 0x10}, {12, 0x80}, {12, 0x100}, {28, 0x340}, {28, 0x7ff}}) {
            byte[] first = chunk(1, false);
            bytes(first).putInt(mutation[0], mutation[1]);
            try (var data = WorldTextureFormats.read(texture(first), () -> false)) {
                assertTrue(data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).admitted());
                var layer = data.chunks().getFirst().layers().get(mutation[0] == 12 ? 0 : 1);
                assertEquals(mutation[1], layer.flags());
            }
        }
        byte[] animated = chunk(1, false);
        bytes(animated).putInt(28, 0x300 | 0x40 | 0x8 | 0x3);
        try (var data = WorldTextureFormats.read(texture(animated), () -> false)) {
            var layer = data.chunks().getFirst().layers().get(1);
            assertEquals(0x4b, layer.animation());
            assertFalse(layer.overbright());
            assertTrue(layer.compressed());
        }
        byte[] nine = texture(chunk(8, false));
        assertEquals(
                WorldTextureFormats.ChunkIssue.LAYER_SHAPE,
                decode(nine, ByteBuffer.allocate(32768)).issue());
    }

    @Test
    void zeroOverflowTruncatedOrTrailingRleDoNotProduceAdmittedMasks() {
        byte[] base = chunk(1, false);
        int alpha = 8 + 32 + 8;
        var zero = base.clone();
        zero[alpha] = (byte) 128;
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                decode(texture(zero), ByteBuffer.allocate(4096)).issue());
        var overflow = base.clone();
        overflow[overflow.length - 2] = (byte) 255;
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                decode(texture(overflow), ByteBuffer.allocate(4096)).issue());
        var truncated = Arrays.copyOf(base, base.length - 1);
        bytes(truncated).putInt(8 + 32 + 4, base.length - alpha - 1);
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                decode(texture(truncated), ByteBuffer.allocate(4096)).issue());
        var trailing = Arrays.copyOf(base, base.length + 1);
        bytes(trailing).putInt(8 + 32 + 4, base.length - alpha + 1);
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                decode(texture(trailing), ByteBuffer.allocate(4096)).issue());
    }

    @Test
    void overlappingWeightsAreKeptAsStoredForTheConsumerToClamp() {
        byte[] two = chunk(2, true);
        int firstAlpha = 8 + 48 + 8;
        Arrays.fill(two, firstAlpha, two.length, (byte) 200);
        var target = ByteBuffer.allocate(8192);
        assertTrue(decode(texture(two), target).admitted());
        assertEquals(200, target.get(0) & 255);
        assertEquals(200, target.get(4096) & 255);
    }

    @Test
    void literalsPreserveRasterOrderAndMetadataCannotAdmitUnsupportedGlobalProfiles() {
        var encoded = new ByteArrayOutputStream();
        encoded.write(64);
        for (int i = 0; i < 64; i++) {
            encoded.write(i);
        }
        for (int at = 64; at < 4096; ) {
            int n = Math.min(127, 4096 - at);
            encoded.write(128 | n);
            encoded.write(0);
            at += n;
        }
        var layers = ints(0, 0, 0, 0, 0, 0x300, 0, 0);
        var target = ByteBuffer.allocate(4096);
        assertTrue(
                decode(
                                texture(
                                        join(
                                                part("MCLY", layers),
                                                part("MCAL", encoded.toByteArray()))),
                                target)
                        .admitted());
        for (int i = 0; i < 64; i++) {
            assertEquals(i, target.get(i));
        }
        assertEquals(0, target.get(64));
        byte[] good = texture(chunk(1, false));
        for (byte[] invalid :
                new byte[][] {
                    join(good, part("MDID", ints(1))),
                    join(good, part("MAMP", ints(0))),
                    Arrays.copyOf(good, good.length - 1),
                    new byte[(8 << 20) + 1]
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldTextureFormats.read(invalid, () -> false));
        }
        var height = good.clone();
        bytes(height).putInt(44, 17);
        try (var data = WorldTextureFormats.read(height, () -> false)) {
            assertEquals(List.of(17L), data.heightIds());
        }
        assertThrows(CancellationException.class, () -> WorldTextureFormats.read(good, () -> true));
        try (var data = WorldTextureFormats.read(good, () -> false)) {
            assertThrows(
                    CancellationException.class,
                    () -> data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> true));
        }
    }

    @Test
    void cancellationDuringMaskDecodeKeepsTheDetachedOwnerReusable() {
        try (var data = WorldTextureFormats.read(texture(chunk(1, false)), () -> false)) {
            var scratch = ByteBuffer.allocate(4096);
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            assertThrows(
                    CancellationException.class,
                    () -> data.decodeAlphas(0, scratch, () -> checks.incrementAndGet() > 5));
            assertTrue(scratch.position() > 0 && scratch.position() < 4096);
            scratch.clear();
            assertTrue(data.decodeAlphas(0, scratch, () -> false).admitted());
            assertEquals(4096, scratch.position());
        }
    }

    @Test
    void optionalInventoryChecksExactHeaderAndUsesRecordedTex0Companion() {
        byte[] mphd = new byte[32];
        bytes(mphd).putInt(0, 0x3ca);
        byte[] main = new byte[4096 * 8], maid = new byte[4096 * 32];
        int tile = 7 * 64 + 19;
        bytes(main).putInt(tile * 8, 1);
        bytes(maid).putInt(tile * 32, 111).putInt(tile * 32 + 12, 222);
        byte[] wdt =
                join(
                        part("MVER", ints(18)),
                        part("MPHD", mphd),
                        part("MAIN", main),
                        part("MAID", maid));
        var facts = WorldTextureFormats.inventory(wdt, () -> false);
        assertEquals(0x3ca, facts.flags());
        assertEquals(222, facts.world().tiles().getFirst().tex0Id());
        assertEquals(7, facts.world().tiles().getFirst().x());
        assertEquals(19, facts.world().tiles().getFirst().y());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldTextureFormats.inventory(
                                join(
                                        part("MVER", ints(18)),
                                        part("MPHD", mphd),
                                        part("MAIN", main)),
                                () -> false));
        var duplicate = join(wdt, part("MPHD", mphd));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTextureFormats.inventory(duplicate, () -> false));
        assertEquals(WorldTextureFormats.AlphaFormat.BIG_8BIT, facts.alphaFormat());
        // Any MPHD flags are accepted; only 0x4 and 0x80 select 8-bit alpha maps.
        for (long[] flags :
                new long[][] {{4, 1}, {0x80, 1}, {0x84, 1}, {0, 0}, {0x2, 0}, {0x8000_0001L, 0}}) {
            bytes(mphd).putInt(0, (int) flags[0]);
            var other =
                    WorldTextureFormats.inventory(
                            join(
                                    part("MVER", ints(18)),
                                    part("MPHD", mphd),
                                    part("MAIN", main),
                                    part("MAID", maid)),
                            () -> false);
            assertEquals(flags[0], other.flags());
            assertEquals(
                    flags[1] == 1
                            ? WorldTextureFormats.AlphaFormat.BIG_8BIT
                            : WorldTextureFormats.AlphaFormat.SMALL_4BIT,
                    other.alphaFormat());
        }
        assertEquals(
                1,
                WorldFormats.wdt(duplicate, () -> false).tiles().size(),
                "Optional texture gate does not tighten old geometry inventory");
    }

    private static byte[] smallChunk(byte[] packed) {
        var layers = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        layers.putInt(0).putInt(0).putInt(0).putInt(0);
        layers.putInt(0).putInt(0x100).putInt(0).putInt(0);
        return join(part("MCLY", layers.array()), part("MCAL", packed));
    }

    @Test
    void fourBitMapsExpandLowNibbleFirstAndFixTheLastRowAndColumnUnlessFlagged() {
        byte[] packed = new byte[2048];
        for (int row = 0; row < 64; row++) {
            for (int pair = 0; pair < 32; pair++) {
                // Low nibble: even column; high nibble: odd column. The last pair is 0, 15.
                packed[row * 32 + pair] =
                        (byte) (pair == 31 ? 0xf0 : (row % 16) | (pair % 16) << 4);
            }
        }
        packed[63 * 32] = (byte) 0xff;
        byte[] source = texture(smallChunk(packed));
        try (var data =
                WorldTextureFormats.read(
                        source, WorldTextureFormats.AlphaFormat.SMALL_4BIT, () -> false)) {
            assertEquals(WorldTextureFormats.AlphaFormat.SMALL_4BIT, data.alphaFormat());
            var exact = ByteBuffer.allocate(4096);
            var result =
                    data.decodeAlphas(
                            0, WorldTextureFormats.DO_NOT_FIX_ALPHA_MAP, exact, () -> false);
            assertTrue(result.admitted());
            assertEquals(4096, exact.position());
            assertEquals(5 * 17, exact.get(5 * 64 + 6) & 255);
            assertEquals(3 * 17, exact.get(5 * 64 + 7) & 255);
            assertEquals(0, exact.get(5 * 64 + 62) & 255);
            assertEquals(255, exact.get(5 * 64 + 63) & 255);
            assertEquals(255, exact.get(63 * 64) & 255);
            var fixed = ByteBuffer.allocate(4096 + 3);
            fixed.position(3);
            assertTrue(data.decodeAlphas(0, 0, fixed, () -> false).admitted());
            assertEquals(4099, fixed.position());
            // The last column repeats column 62 and the last row repeats row 62.
            assertEquals(0, fixed.get(3 + 5 * 64 + 63) & 255);
            assertEquals(14 * 17, fixed.get(3 + 63 * 64) & 255);
            assertEquals(14 * 17, fixed.get(3 + 62 * 64) & 255);
            assertEquals(5 * 17, fixed.get(3 + 5 * 64 + 6) & 255);
        }
        // An 8-bit map in a 4-bit layout, and the reverse, are refused.
        try (var data =
                WorldTextureFormats.read(
                        texture(chunk(1, true)),
                        WorldTextureFormats.AlphaFormat.SMALL_4BIT,
                        () -> false)) {
            assertEquals(
                    WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                    data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).issue());
        }
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                decode(source, ByteBuffer.allocate(4096)).issue());
        // Without big alpha the 0x200 bit means nothing: 2048 raw 4-bit bytes are read, and an
        // RLE stream is not decoded.
        byte[] flagged = smallChunk(new byte[2048]);
        Arrays.fill(flagged, flagged.length - 2048, flagged.length, (byte) 0x21);
        bytes(flagged).putInt(8 + 16 + 4, 0x300);
        try (var data =
                WorldTextureFormats.read(
                        texture(flagged),
                        WorldTextureFormats.AlphaFormat.SMALL_4BIT,
                        () -> false)) {
            assertTrue(data.chunks().getFirst().layers().get(1).compressed());
            var target = ByteBuffer.allocate(4096);
            assertTrue(
                    data.decodeAlphas(
                                    0,
                                    WorldTextureFormats.DO_NOT_FIX_ALPHA_MAP,
                                    target,
                                    () -> false)
                            .admitted());
            assertEquals(17, target.get(0));
            assertEquals(34, target.get(1));
        }
        try (var data =
                WorldTextureFormats.read(
                        texture(chunk(1, false)),
                        WorldTextureFormats.AlphaFormat.SMALL_4BIT,
                        () -> false)) {
            assertEquals(
                    WorldTextureFormats.ChunkIssue.MASK_ENCODING,
                    data.decodeAlphas(0, 0, ByteBuffer.allocate(4096), () -> false).issue());
        }
        // In an 8-bit layout the same bit selects RLE.
        var rle = ByteBuffer.allocate(4096);
        assertTrue(decode(texture(chunk(1, false)), rle).admitted());
        assertEquals(1, rle.get(4095));
    }

    @Test
    void loneBaseLayerIgnoresMcalBytesAndOverrunningUnknownChunksAreRefused() {
        byte[] lone = join(part("MCLY", ints(0, 0, 0, 0)), part("MCAL", new byte[4096]));
        try (var data = WorldTextureFormats.read(texture(lone), () -> false)) {
            assertEquals(WorldTextureFormats.ChunkIssue.NONE, data.chunks().getFirst().issue());
            var target = ByteBuffer.allocate(0);
            var result = data.decodeAlphas(0, target, () -> false);
            assertTrue(result.admitted());
            assertEquals(0, result.maskCount());
        }
        byte[] good = texture(chunk(1, false));
        // An unknown chunk whose declared size runs past the end is still a bounds failure.
        byte[] overrun = join(good, ints(0x5a5a5a5a, 64), new byte[8]);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTextureFormats.read(overrun, () -> false));
        byte[] negative = join(good, ints(0x5a5a5a5a, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTextureFormats.read(negative, () -> false));
    }

    @Test
    void unusedTopLevelAndChunkSubChunksAreSkippedAndOptionalHeadersCarried() {
        byte[] first =
                join(
                        part("MCSH", new byte[512]),
                        chunk(1, false),
                        part("MCMT", new byte[4]),
                        part("ZZZZ", new byte[0]));
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                join(
                        part("MVER", ints(18)),
                        part("MTXF", ints(0, 1)),
                        part("MAMP", new byte[] {2, 0, 0, 0}),
                        part("MDID", ints(12345, 678)),
                        part("MTXP", new byte[32])));
        out.writeBytes(part("MCNK", first));
        // A lone base layer may omit MCAL.
        var single = part("MCLY", ints(1, 0, 0, 0));
        for (int i = 1; i < 256; i++) {
            out.writeBytes(part("MCNK", single));
        }
        out.writeBytes(part("MLDB", new byte[8]));
        try (var data = WorldTextureFormats.read(out.toByteArray(), () -> false)) {
            assertEquals(2, data.amplifier());
            assertEquals(List.of(12345L, 678L), data.diffuseIds());
            assertEquals(List.of(0L, 0L), data.heightIds());
            var target = ByteBuffer.allocate(4096);
            assertTrue(data.decodeAlphas(0, target, () -> false).admitted());
            assertEquals(1, target.get(0));
            assertTrue(data.decodeAlphas(1, ByteBuffer.allocate(0), () -> false).admitted());
            assertEquals(1L, data.chunks().get(1).layers().getFirst().textureIndex());
        }
        // A duplicate MCLY or MCAL is still a malformed chunk.
        for (byte[] duplicate :
                new byte[][] {
                    join(chunk(1, false), part("MCLY", ints(0, 0, 0, 0))),
                    join(chunk(1, false), part("MCAL", new byte[0]))
                }) {
            assertFalse(decode(texture(duplicate), ByteBuffer.allocate(4096)).admitted());
        }
        // An overlay without MCAL has no masks to read.
        byte[] noAlpha = part("MCLY", ints(0, 0, 0, 0, 0, 0x100, 0, 0));
        assertEquals(
                WorldTextureFormats.ChunkIssue.LAYER_SHAPE,
                decode(texture(noAlpha), ByteBuffer.allocate(4096)).issue());
    }

    @Test
    void shadowMapsDecodeLeastSignificantBitFirstAndNeverAffectCoverage() {
        byte[] bits = new byte[WorldTextureFormats.SHADOW_BYTES];
        bits[0] = 0b0000_0101;
        bits[7] = (byte) 0x80;
        bits[62 * 8] = 1;
        Arrays.fill(bits, 63 * 8, 64 * 8, (byte) 0);
        byte[] source =
                texture(join(part("MCSH", bits), chunk(1, false), part("MCMT", new byte[4])));
        try (var data = WorldTextureFormats.read(source, () -> false)) {
            assertEquals(WorldTextureFormats.ShadowState.BITS, data.shadowState(0));
            assertEquals(WorldTextureFormats.ShadowState.ABSENT, data.shadowState(1));
            assertTrue(data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).admitted());
            var exact = ByteBuffer.allocate(4096 + 5);
            exact.position(5);
            assertTrue(
                    data.decodeShadow(
                            0, WorldTextureFormats.DO_NOT_FIX_ALPHA_MAP, exact, () -> false));
            assertEquals(4101, exact.position());
            assertEquals(255, exact.get(5) & 255);
            assertEquals(0, exact.get(5 + 1) & 255);
            assertEquals(255, exact.get(5 + 2) & 255);
            assertEquals(255, exact.get(5 + 63) & 255);
            assertEquals(255, exact.get(5 + 62 * 64) & 255);
            assertEquals(0, exact.get(5 + 63 * 64) & 255);
            var fixed = ByteBuffer.allocate(4096);
            assertTrue(data.decodeShadow(0, 0, fixed, () -> false));
            // Last column repeats column 62; last row repeats row 62.
            assertEquals(0, fixed.get(63) & 255);
            assertEquals(255, fixed.get(63 * 64) & 255);
            var none = ByteBuffer.allocate(4096);
            assertFalse(data.decodeShadow(1, 0, none, () -> false));
            assertEquals(0, none.position());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> data.decodeShadow(0, 0, ByteBuffer.allocate(4095), () -> false));
            assertThrows(
                    CancellationException.class,
                    () -> data.decodeShadow(0, 0, ByteBuffer.allocate(4096), () -> true));
            assertThrows(IllegalArgumentException.class, () -> data.shadowState(256));
        }
        var closed = WorldTextureFormats.read(source, () -> false);
        closed.close();
        assertThrows(
                IllegalStateException.class,
                () -> closed.decodeShadow(0, 0, ByteBuffer.allocate(4096), () -> false));
        for (byte[] bad :
                new byte[][] {
                    join(part("MCSH", new byte[511]), chunk(1, false)),
                    join(part("MCSH", bits), part("MCSH", bits), chunk(1, false))
                }) {
            try (var data = WorldTextureFormats.read(texture(bad), () -> false)) {
                assertEquals(WorldTextureFormats.ShadowState.UNSUPPORTED, data.shadowState(0));
                assertFalse(data.decodeShadow(0, 0, ByteBuffer.allocate(4096), () -> false));
                assertTrue(data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).admitted());
            }
        }
    }
}
