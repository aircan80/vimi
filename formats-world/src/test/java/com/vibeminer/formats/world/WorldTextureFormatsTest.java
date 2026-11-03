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
                                1, WorldTextureFormats.ChunkIssue.MASK_SUM));
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
    void unknownFieldsAndInvalidIntervalsDeclineOnlyTheirChunk() {
        for (int[] mutation : new int[][] {{12, 16}, {16, -1}, {24, 1}, {28, 0x340}, {32, 1}}) {
            byte[] first = chunk(1, false);
            bytes(first).putInt(mutation[0], mutation[1]);
            try (var data = WorldTextureFormats.read(texture(first), () -> false)) {
                assertFalse(
                        data.decodeAlphas(0, ByteBuffer.allocate(4096), () -> false).admitted());
                assertTrue(data.decodeAlphas(1, ByteBuffer.allocate(0), () -> false).admitted());
            }
        }
        byte[] nine = texture(chunk(8, false));
        assertEquals(
                WorldTextureFormats.ChunkIssue.LAYER_SHAPE,
                decode(nine, ByteBuffer.allocate(32768)).issue());
    }

    @Test
    void zeroOverflowTruncatedOrTrailingRleAndExcessWeightsDoNotProduceAdmittedMasks() {
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
        byte[] two = chunk(2, false);
        int firstAlpha = 8 + 48 + 8;
        for (int i = firstAlpha + 1; i < two.length; i += 2) {
            two[i] = (byte) 200;
        }
        assertEquals(
                WorldTextureFormats.ChunkIssue.MASK_SUM,
                decode(texture(two), ByteBuffer.allocate(8192)).issue());
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
                    join(good, part("MTXP", new byte[16])),
                    join(good, part("MDID", ints(1))),
                    Arrays.copyOf(good, good.length - 1),
                    new byte[(8 << 20) + 1]
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldTextureFormats.read(invalid, () -> false));
        }
        var height = good.clone();
        bytes(height).putInt(44, 17);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldTextureFormats.read(height, () -> false));
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
        bytes(mphd).putInt(0, 4);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldTextureFormats.inventory(
                                join(
                                        part("MVER", ints(18)),
                                        part("MPHD", mphd),
                                        part("MAIN", main),
                                        part("MAID", maid)),
                                () -> false));
        assertEquals(
                1,
                WorldFormats.wdt(duplicate, () -> false).tiles().size(),
                "Optional texture gate does not tighten old geometry inventory");
    }
}
