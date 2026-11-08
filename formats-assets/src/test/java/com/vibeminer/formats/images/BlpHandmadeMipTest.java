package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.Blp2Types.MipProfile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BlpHandmadeMipTest {
    private static byte[] bc2(int color, int alpha, int blocks) {
        var out = ByteBuffer.allocate(blocks * 16).order(ByteOrder.LITTLE_ENDIAN);
        long nibbles = alpha * 0x1111111111111111L;
        for (int i = 0; i < blocks; i++) {
            out.putLong(nibbles).put(Blp2DecoderTest.block(color, 0, 0));
        }
        return out.array();
    }

    private static byte[] handmade() {
        var bytes =
                Blp2DecoderTest.build(
                        2,
                        8,
                        1,
                        8,
                        8,
                        bc2(0xf800, 15, 4),
                        bc2(0x07e0, 8, 1),
                        bc2(0x001f, 4, 1),
                        bc2(0xffff, 15, 1));
        bytes[11] = 2;
        return bytes;
    }

    private static byte[] handmadeBc3() {
        var mips = new byte[9][];
        for (int level = 0; level < mips.length; level++) {
            int side = 256 >> level, blocks = ((side + 3) / 4) * ((side + 3) / 4);
            var out = ByteBuffer.allocate(blocks * 16).order(ByteOrder.LITTLE_ENDIAN);
            for (int block = 0; block < blocks; block++) {
                out.put((byte) (17 + level * 23)).put(new byte[7]);
                out.put(Blp2DecoderTest.block(0xf800, 0, 0));
            }
            mips[level] = out.array();
        }
        var bytes = Blp2DecoderTest.build(2, 8, 7, 256, 256, mips);
        bytes[11] = 2;
        return bytes;
    }

    /**
     * The reported terrain diffuse header: palette, 8-bit alpha, alpha encoding 8, mip byte 0x02,
     * with a complete 8x8 -> 1x1 chain. Each level's indexes and alpha bytes are distinct.
     */
    private static byte[] handmadePaletted() {
        var mips = new byte[4][];
        for (int level = 0; level < mips.length; level++) {
            int pixels = (8 >> level) * (8 >> level);
            mips[level] = new byte[pixels * 2];
            for (int i = 0; i < pixels; i++) {
                mips[level][i] = (byte) (level * 64 + i);
                mips[level][pixels + i] = (byte) (255 - level * 50 - i);
            }
        }
        var bytes = Blp2DecoderTest.build(1, 8, 8, 8, 8, mips);
        bytes[11] = 2;
        return bytes;
    }

    @Test
    void handmadePalettedTerrainHeaderDecodesEveryDeclaredMipWithItsAlpha() {
        var bytes = handmadePaletted();
        var mips =
                Blp2Decoder.readMips(
                                AssetSource.bytes(bytes),
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertEquals(4, mips.size());
        for (int level = 0; level < mips.size(); level++) {
            int side = 8 >> level;
            assertEquals(side, mips.get(level).width());
            assertEquals(side * side * 2L, mips.get(level).byteLength());
            var image =
                    Blp2Decoder.decode(
                                    AssetSource.bytes(bytes),
                                    level,
                                    new Limits(),
                                    Cancellation.NONE,
                                    MipProfile.DECLARED_HANDMADE_V1)
                            .join();
            assertEquals(Blp2Types.Encoding.PALETTED, image.encoding());
            assertEquals(8, image.alphaDepth());
            assertEquals(8, image.alphaEncoding());
            assertEquals(2, image.mipFlags());
            byte[] expected = new byte[side * side * 4];
            for (int i = 0; i < side * side; i++) {
                int index = (level * 64 + i) & 255;
                // Blp2DecoderTest.build stores palette entry i as B=i+2, G=i+1, R=i.
                expected[i * 4] = (byte) index;
                expected[i * 4 + 1] = (byte) (index + 1);
                expected[i * 4 + 2] = (byte) (index + 2);
                expected[i * 4 + 3] = (byte) (255 - level * 50 - i);
            }
            assertArrayEquals(expected, image.rgba(), "level " + level);
        }
        var largest =
                Blp2Decoder.decodeLargestWithin(
                                AssetSource.bytes(bytes),
                                4,
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertEquals(1, largest.mipLevel());
        assertEquals(4, largest.width());
        // Strict mode still refuses the declared handmade byte.
        refusedStrict(bytes);
    }

    @Test
    void malformedHandmadePalettedChainsStayInvalid() {
        // Missing the 1x1 level.
        var short3 = new byte[3][];
        for (int level = 0; level < 3; level++) {
            short3[level] = new byte[(8 >> level) * (8 >> level) * 2];
        }
        var incomplete = Blp2DecoderTest.build(1, 8, 8, 8, 8, short3);
        incomplete[11] = 2;
        refused("BLP_INVALID", incomplete);
        // Index-only levels (no alpha bytes), trailing bytes and a truncated last level.
        var noAlpha = handmadePaletted();
        put(noAlpha, 84, 64);
        refused("BLP_INVALID", noAlpha);
        refused("BLP_INVALID", Arrays.copyOf(handmadePaletted(), handmadePaletted().length + 1));
        refused("BLP_INVALID", Arrays.copyOf(handmadePaletted(), handmadePaletted().length - 1));
    }

    @Test
    void unreviewedHandmadePaletteTuplesStayUnsupported() {
        for (int[] tuple : new int[][] {{0, 0}, {1, 0}, {4, 0}, {8, 0}, {0, 8}}) {
            var bytes = handmadePaletted();
            bytes[9] = (byte) tuple[0];
            bytes[10] = (byte) tuple[1];
            refused("BLP_UNSUPPORTED", bytes);
        }
    }

    private static void refusedStrict(byte[] bytes) {
        assertThrows(
                RuntimeException.class,
                () ->
                        Blp2Decoder.readMips(
                                        AssetSource.bytes(bytes), new Limits(), Cancellation.NONE)
                                .join());
    }

    @Test
    void handmadeBc3ReportedTerrainHeaderDecodesEveryDeclaredMipAndAlpha() {
        var bytes = handmadeBc3();
        var mips =
                Blp2Decoder.readMips(
                                AssetSource.bytes(bytes),
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertEquals(9, mips.size());
        for (int level = 0; level < mips.size(); level++) {
            int side = 256 >> level;
            assertEquals(side, mips.get(level).width());
            assertEquals(((side + 3) / 4L) * ((side + 3) / 4L) * 16, mips.get(level).byteLength());
            var image =
                    Blp2Decoder.decode(
                                    AssetSource.bytes(bytes),
                                    level,
                                    new Limits(),
                                    Cancellation.NONE,
                                    MipProfile.DECLARED_HANDMADE_V1)
                            .join();
            var expected = new byte[side * side * 4];
            for (int at = 0; at < expected.length; at += 4) {
                expected[at] = (byte) 255;
                expected[at + 3] = (byte) (17 + level * 23);
            }
            assertArrayEquals(expected, image.rgba());
            assertEquals(Blp2Types.Encoding.BC3, image.encoding());
            assertEquals(2, image.mipFlags());
        }
        var selected =
                Blp2Decoder.decodeLargestWithin(
                                AssetSource.bytes(bytes),
                                4,
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertEquals(6, selected.mipLevel());
        assertEquals(4, selected.width());
        Blp2DecoderTest.code("BLP_UNSUPPORTED", bytes, 0);
        var wrongBlocks = bytes.clone();
        put(wrongBlocks, 84, 32768);
        refused("BLP_INVALID", wrongBlocks);
        refused("BLP_INVALID", Arrays.copyOf(bytes, bytes.length - 1));
    }

    private static final int[] BC3_ALPHA = {200, 40, 177, 154, 131, 108, 85, 62};
    private static final int[][] BC3_COLORS = {
        {255, 0, 0}, {0, 0, 255}, {170, 0, 85}, {85, 0, 170}
    };

    /**
     * One BC3 block: alpha 200/40 eight-value ramp, pixel i alpha index (i + shift) % 8, colour
     * index (x + level) % 4. Distinct shifts make every level and every block within a level
     * byte-distinct, so a wrong level offset or block stride changes the decoded pixels.
     */
    private static byte[] bc3Block(int level, int shift) {
        var out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        long alphaSelectors = 0;
        int colorSelectors = 0;
        for (int i = 0; i < 16; i++) {
            alphaSelectors |= (long) ((i + shift) % 8) << (i * 3);
            colorSelectors |= ((i % 4 + level) % 4) << (i * 2);
        }
        out.put((byte) 200).put((byte) 40);
        for (int i = 0; i < 6; i++) {
            out.put((byte) (alphaSelectors >>> (i * 8)));
        }
        return out.put(Blp2DecoderTest.block(0xf800, 0x001f, colorSelectors)).array();
    }

    /**
     * Non-square 16x4 handmade BC3 chain whose 4x1, 2x1 and 1x1 levels each keep one whole block.
     */
    private static byte[] handmadeBc3NonSquare(int blockBytes) {
        var mips = new byte[5][];
        for (int level = 0; level < mips.length; level++) {
            int w = Math.max(1, 16 >> level), h = Math.max(1, 4 >> level);
            int blocks = ((w + 3) / 4) * ((h + 3) / 4);
            var out = ByteBuffer.allocate(blocks * blockBytes);
            for (int block = 0; block < blocks; block++) {
                out.put(bc3Block(level, level + block), 0, blockBytes);
            }
            mips[level] = out.array();
        }
        var bytes = Blp2DecoderTest.build(2, 8, 7, 16, 4, mips);
        bytes[11] = 2;
        return bytes;
    }

    @Test
    void handmadeBc3NonSquareChainDecodesInterpolatedAlphaAndCroppedTerminalBlocks() {
        var bytes = handmadeBc3NonSquare(16);
        var mips =
                Blp2Decoder.readMips(
                                AssetSource.bytes(bytes),
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        long[] expectedLengths = {64, 32, 16, 16, 16};
        assertEquals(expectedLengths.length, mips.size());
        for (int level = 0; level < mips.size(); level++) {
            int w = Math.max(1, 16 >> level), h = Math.max(1, 4 >> level);
            assertEquals(w, mips.get(level).width());
            assertEquals(h, mips.get(level).height());
            assertEquals(expectedLengths[level], mips.get(level).byteLength());
            assertEquals(Blp2Types.MipStorage.EXACT, mips.get(level).storage());
            var image =
                    Blp2Decoder.decode(
                                    AssetSource.bytes(bytes),
                                    level,
                                    new Limits(),
                                    Cancellation.NONE,
                                    MipProfile.DECLARED_HANDMADE_V1)
                            .join();
            var expected = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int px = x % 4, py = y % 4, at = (y * w + x) * 4;
                    int block = (y / 4) * ((w + 3) / 4) + x / 4;
                    int[] color = BC3_COLORS[(px + level) % 4];
                    expected[at] = (byte) color[0];
                    expected[at + 1] = (byte) color[1];
                    expected[at + 2] = (byte) color[2];
                    expected[at + 3] = (byte) BC3_ALPHA[(py * 4 + px + level + block) % 8];
                }
            }
            assertArrayEquals(expected, image.rgba(), "level " + level);
        }
    }

    @Test
    void malformedOrTruncatedBc3StaysRefusedInHandmadeAndStrictModes() {
        // BC1-sized (8-byte) blocks under a BC3 header are neither exact nor compact BC3.
        refused("BLP_INVALID", handmadeBc3NonSquare(8));
        var valid = handmadeBc3NonSquare(16);
        // Every cut inside the 16-byte terminal 1x1 block, and inside the first level.
        for (int cut = 1; cut < 16; cut++) {
            refused("BLP_INVALID", Arrays.copyOf(valid, valid.length - cut));
        }
        refused("BLP_INVALID", Arrays.copyOf(valid, 148 + 63));
        // Terminal level declared with BC1's 8-byte minimum while the file still holds 16 bytes.
        var shortTerminal = valid.clone();
        put(shortTerminal, 84 + 4 * 4, 8);
        refused("BLP_INVALID", shortTerminal);
        // Strict (non-handmade) single-level BC3: every truncation and every wrong declared length.
        var strict = Blp2DecoderTest.build(2, 8, 7, 4, 4, bc3Block(0, 0));
        assertEquals(16 * 4, Blp2Decoder.decode(AssetSource.bytes(strict)).join().rgba().length);
        for (int size = 0; size < strict.length; size++) {
            Blp2DecoderTest.code("BLP_INVALID", Arrays.copyOf(strict, size), 0);
        }
        for (int declared : new int[] {8, 15, 17, 32}) {
            var wrong = Arrays.copyOf(strict, 148 + Math.max(16, declared));
            put(wrong, 84, declared);
            Blp2DecoderTest.code("BLP_INVALID", wrong, 0);
        }
    }

    private static void refused(String code, byte[] bytes) {
        for (boolean metadata : new boolean[] {false, true}) {
            var error =
                    AssetTasks.unwrap(
                            assertThrows(
                                    RuntimeException.class,
                                    () -> {
                                        if (metadata) {
                                            Blp2Decoder.readMips(
                                                            AssetSource.bytes(bytes),
                                                            new Limits(),
                                                            Cancellation.NONE,
                                                            MipProfile.DECLARED_HANDMADE_V1)
                                                    .join();
                                        } else {
                                            Blp2Decoder.decode(
                                                            AssetSource.bytes(bytes),
                                                            1,
                                                            new Limits(),
                                                            Cancellation.NONE,
                                                            MipProfile.DECLARED_HANDMADE_V1)
                                                    .join();
                                        }
                                    }));
            assertInstanceOf(BlpFormatException.class, error);
            assertEquals(code, ((BlpFormatException) error).code());
        }
    }

    @Test
    void unsupportedHandmadeProfileReportsActualEncodingAndAlphaTuple() {
        var bytes = handmadeBc3();
        bytes[9] = 72;
        var error =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        Blp2Decoder.readMips(
                                                        AssetSource.bytes(bytes),
                                                        new Limits(),
                                                        Cancellation.NONE,
                                                        MipProfile.DECLARED_HANDMADE_V1)
                                                .join()));
        assertInstanceOf(BlpFormatException.class, error);
        assertTrue(error.getMessage().contains("colour encoding 2 (BC3)"));
        assertTrue(error.getMessage().contains("alpha depth 72"));
        assertTrue(error.getMessage().contains("alpha encoding 7"));
    }

    @Test
    void explicitProfileDecodesDeclaredAuthoredBc2PixelsAndPreservesRawFlag() {
        var bytes = handmade();
        var image =
                Blp2Decoder.decodeLargestWithin(
                                AssetSource.bytes(bytes),
                                4,
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertEquals(1, image.mipLevel());
        assertEquals(4, image.width());
        assertEquals(2, image.mipFlags());
        assertEquals(4, image.mips().size());
        byte[] expected = new byte[4 * 4 * 4];
        for (int at = 0; at < expected.length; at += 4) {
            expected[at + 1] = (byte) 255;
            expected[at + 3] = (byte) 136;
        }
        assertArrayEquals(expected, image.rgba());
        assertEquals(
                4,
                Blp2Decoder.readMips(
                                AssetSource.bytes(bytes),
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join()
                        .size());
        assertFalse(Blp2Decoder.isMipFlags(2));
        assertFalse(Blp2Decoder.allowsMipLevels(2));
        Blp2DecoderTest.code("BLP_UNSUPPORTED", bytes, 1);
        assertThrows(
                RuntimeException.class,
                () ->
                        Blp2Decoder.readMips(
                                        AssetSource.bytes(bytes), new Limits(), Cancellation.NONE)
                                .join());
        assertThrows(
                RuntimeException.class,
                () ->
                        Blp2Decoder.decodeLargestWithin(
                                        AssetSource.bytes(bytes),
                                        4,
                                        new Limits(),
                                        Cancellation.NONE)
                                .join());
        assertNull(
                BlpImagePayload.read(BlpImagePayload.write(image), 1, new Limits()),
                "The strict persisted image contract does not acquire handmade authority");
        bytes[11] = 1;
        var strict =
                Blp2Decoder.decode(AssetSource.bytes(bytes), 1, new Limits(), Cancellation.NONE)
                        .join();
        var optin =
                Blp2Decoder.decode(
                                AssetSource.bytes(bytes),
                                1,
                                new Limits(),
                                Cancellation.NONE,
                                MipProfile.DECLARED_HANDMADE_V1)
                        .join();
        assertArrayEquals(strict.rgba(), optin.rgba());
        assertEquals(strict.mips(), optin.mips());
        assertEquals(strict.mipFlags(), optin.mipFlags());
    }

    @Test
    void fullChainProofRejectsEvenUnselectedDamageAndTrailingPayload() {
        var invalid = new ArrayList<byte[]>();
        var incomplete =
                Blp2DecoderTest.build(2, 8, 1, 8, 8, bc2(0xf800, 15, 4), bc2(0x07e0, 15, 1));
        incomplete[11] = 2;
        invalid.add(incomplete);
        var nonPower = handmade();
        put(nonPower, 12, 7);
        invalid.add(nonPower);
        var outOfRange = handmade();
        put(outOfRange, 28, 100000);
        invalid.add(outOfRange);
        var overlap = handmade();
        put(overlap, 28, 212);
        invalid.add(overlap);
        var reverse = handmade();
        put(reverse, 28, 244);
        put(reverse, 32, 228);
        invalid.add(reverse);
        var badSize = handmade();
        put(badSize, 92, 8);
        invalid.add(badSize);
        invalid.add(Arrays.copyOf(handmade(), handmade().length + 1));
        invalid.add(Arrays.copyOf(handmade(), handmade().length - 1));
        var absent = handmade();
        put(absent, 28, 0);
        put(absent, 92, 0);
        invalid.add(absent);
        for (var bytes : invalid) {
            refused("BLP_INVALID", bytes);
        }
    }

    @Test
    void optinDoesNotAdmitOtherFlagsOrPixelProfiles() {
        for (int flag : new int[] {3, 0x12, 0x22, 255}) {
            var bytes = handmade();
            bytes[11] = (byte) flag;
            refused("BLP_UNSUPPORTED", bytes);
        }
        for (int[] tuple :
                new int[][] {{2, 4, 1}, {2, 72, 7}, {2, 0, 0}, {2, 1, 0}, {3, 8, 0}, {1, 8, 0}}) {
            var bytes = handmade();
            bytes[8] = (byte) tuple[0];
            bytes[9] = (byte) tuple[1];
            bytes[10] = (byte) tuple[2];
            refused("BLP_UNSUPPORTED", bytes);
        }
    }

    @Test
    void validatingEveryDescriptorDoesNotReadEveryPayloadOrRaiseWorkingLimits() {
        byte[] bytes = handmade();
        AssetSource inner = AssetSource.bytes(bytes);
        var reads = new AtomicInteger();
        AssetSource source =
                new AssetSource() {
                    public long length() {
                        return inner.length();
                    }

                    public CompletableFuture<byte[]> readAt(
                            long at, int length, Cancellation token) {
                        reads.addAndGet(length);
                        return inner.readAt(at, length, token);
                    }
                };
        var limits = new Limits(1024, 8, 16, 16, 64);
        Blp2Decoder.decodeLargestWithin(
                        source, 4, limits, Cancellation.NONE, MipProfile.DECLARED_HANDMADE_V1)
                .join();
        assertEquals(148 + 16, reads.get());
        var canceled = new Cancellation();
        canceled.cancel();
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        Blp2Decoder.decode(
                                                        AssetSource.bytes(bytes),
                                                        1,
                                                        limits,
                                                        canceled,
                                                        MipProfile.DECLARED_HANDMADE_V1)
                                                .join())));
        assertThrows(
                NullPointerException.class,
                () ->
                        Blp2Decoder.decode(
                                AssetSource.bytes(bytes), 1, limits, Cancellation.NONE, null));
    }

    private static void put(byte[] bytes, int at, int value) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
    }
}
