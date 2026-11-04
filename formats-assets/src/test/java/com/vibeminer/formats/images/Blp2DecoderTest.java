package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.images.Blp2Types.Encoding;
import com.vibeminer.formats.images.Blp2Types.Limits;
import com.vibeminer.formats.images.Blp2Types.MipStorage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Blp2DecoderTest {
    @Test
    void synchronousDecodeStaysOnCallerAndPreservesOwnedPixelsAndMetadata() {
        byte[] bytes = build(2, 0, 0, 4, 4, block(0xf800, 0x07e0, 0));
        var input = AssetSource.bytes(bytes);
        Thread caller = Thread.currentThread();
        var reads = new AtomicInteger();
        var observed =
                new AssetSource() {
                    public long length() {
                        assertEquals(caller, Thread.currentThread());
                        return input.length();
                    }

                    public CompletableFuture<byte[]> readAt(
                            long offset, int count, Cancellation token) {
                        assertEquals(caller, Thread.currentThread());
                        reads.incrementAndGet();
                        return input.readAt(offset, count, token);
                    }
                };
        var actual =
                Blp2Decoder.decodeSynchronous(
                        observed, 0, new Limits(), Cancellation.NONE, Blp2Types.MipProfile.STRICT);
        var expected = Blp2Decoder.decode(input).join();
        assertTrue(reads.get() >= 2);
        assertEquals(expected.encoding(), actual.encoding());
        assertEquals(expected.mips(), actual.mips());
        assertEquals(expected.width(), actual.width());
        assertEquals(expected.height(), actual.height());
        assertArrayEquals(expected.rgba(), actual.rgba());
        byte[] borrowedCopy = actual.rgba();
        borrowedCopy[0] ^= 1;
        assertArrayEquals(expected.rgba(), actual.rgba());
    }

    @Test
    void synchronousDecodePreservesStrictLimitsAndDirectFormatFailure() {
        byte[] bytes = build(2, 0, 0, 4, 4, block(0xf800, 0x07e0, 0));
        var limit =
                assertThrows(
                        BlpFormatException.class,
                        () ->
                                Blp2Decoder.decodeSynchronous(
                                        AssetSource.bytes(bytes),
                                        0,
                                        new Limits(148, 4, 16, 8, 64),
                                        Cancellation.NONE,
                                        Blp2Types.MipProfile.STRICT));
        assertEquals("BLP_LIMIT", limit.code());
        bytes[0] = 0;
        var malformed =
                assertThrows(
                        BlpFormatException.class,
                        () ->
                                Blp2Decoder.decodeSynchronous(
                                        AssetSource.bytes(bytes),
                                        0,
                                        new Limits(),
                                        Cancellation.NONE,
                                        Blp2Types.MipProfile.STRICT));
        assertEquals("BLP_UNSUPPORTED", malformed.code());
    }

    @Test
    void synchronousDecodeHonorsCancellationBeforeOpeningSource() {
        var stop = new Cancellation();
        stop.cancel();
        var forbidden =
                new AssetSource() {
                    public long length() {
                        throw new AssertionError("Canceled source was inspected");
                    }

                    public CompletableFuture<byte[]> readAt(
                            long offset, int count, Cancellation token) {
                        throw new AssertionError("Canceled source was read");
                    }
                };
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        Blp2Decoder.decodeSynchronous(
                                forbidden, 0, new Limits(), stop, Blp2Types.MipProfile.STRICT));
    }

    static byte[] build(int encoding, int depth, int alpha, int width, int height, byte[]... mips) {
        int start = 148 + (encoding == 1 ? 1024 : 0), length = start;
        for (byte[] mip : mips) {
            length += mip.length;
        }
        byte[] bytes = new byte[length];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'B', 'L', 'P', '2'})
                .putInt(1)
                .put((byte) encoding)
                .put((byte) depth)
                .put((byte) alpha)
                .put((byte) (mips.length > 1 ? 1 : 0))
                .putInt(width)
                .putInt(height);
        int at = start;
        for (int i = 0; i < mips.length; i++) {
            b.putInt(20 + i * 4, at);
            b.putInt(84 + i * 4, mips[i].length);
            System.arraycopy(mips[i], 0, bytes, at, mips[i].length);
            at += mips[i].length;
        }
        if (encoding == 1) {
            for (int i = 0; i < 256; i++) {
                bytes[148 + i * 4] = (byte) (i + 2);
                bytes[149 + i * 4] = (byte) (i + 1);
                bytes[150 + i * 4] = (byte) i;
                bytes[151 + i * 4] = 0;
            }
        }
        return bytes;
    }

    static byte[] block(int a, int b, int selectors) {
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) a)
                .putShort((short) b)
                .putInt(selectors)
                .array();
    }

    static void code(String expected, byte[] bytes, int mip) {
        Throwable error =
                AssetTasks.unwrap(
                        assertThrows(
                                java.util.concurrent.CompletionException.class,
                                () ->
                                        Blp2Decoder.decode(
                                                        AssetSource.bytes(bytes),
                                                        mip,
                                                        new Limits(),
                                                        Cancellation.NONE)
                                                .join()));
        assertInstanceOf(BlpFormatException.class, error);
        assertEquals(expected, ((BlpFormatException) error).code());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 8})
    void paletteUsesLowBitAlphaAndIgnoresPaletteAlpha(int depth) {
        byte[] payload =
                switch (depth) {
                    case 0 -> new byte[] {1, 2};
                    case 1 -> new byte[] {1, 2, 1};
                    case 4 -> new byte[] {1, 2, 0x21};
                    default -> new byte[] {1, 2, 17, 34};
                };
        var image = Blp2Decoder.decode(AssetSource.bytes(build(1, depth, 0, 2, 1, payload))).join();
        assertEquals(Encoding.PALETTED, image.encoding());
        assertArrayEquals(
                new byte[] {
                    1,
                    2,
                    3,
                    (byte) (depth == 0 || depth == 1 ? 255 : 17),
                    2,
                    3,
                    4,
                    (byte) (depth == 0 ? 255 : depth == 1 ? 0 : 34)
                },
                image.rgba());
    }

    @Test
    void paletteEightBitAlphaAcceptsRecordedEncodingEightAndKeepsAllBounds() {
        byte[] bytes = build(1, 8, 8, 2, 1, new byte[] {1, 2, 17, 34});
        var decoded = Blp2Decoder.decode(AssetSource.bytes(bytes)).join();
        assertEquals(Encoding.PALETTED, decoded.encoding());
        assertArrayEquals(new byte[] {1, 2, 3, 17, 2, 3, 4, 34}, decoded.rgba());
        assertTrue(Blp2Decoder.isSupported(Encoding.PALETTED, 8, 8));
        code("BLP_INVALID", java.util.Arrays.copyOf(bytes, bytes.length - 1), 0);
    }

    @Test
    void bc1FloorInterpolationAndEndpointBitReplication() {
        var image =
                Blp2Decoder.decode(
                                AssetSource.bytes(
                                        build(2, 0, 0, 4, 1, block(0xf800, 0x001f, 0xe4))))
                        .join();
        assertArrayEquals(
                new byte[] {
                    (byte) 255,
                    0,
                    0,
                    (byte) 255,
                    0,
                    0,
                    (byte) 255,
                    (byte) 255,
                    (byte) 170,
                    0,
                    85,
                    (byte) 255,
                    85,
                    0,
                    (byte) 170,
                    (byte) 255
                },
                image.rgba());
        var endpoint =
                Blp2Decoder.decode(AssetSource.bytes(build(2, 0, 0, 1, 1, block(0x8c71, 0, 0))))
                        .join();
        assertArrayEquals(
                new byte[] {(byte) 140, (byte) 142, (byte) 140, (byte) 255}, endpoint.rgba());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void bc1ThreeColorFourthAlphaPolicy(int depth) {
        var image =
                Blp2Decoder.decode(AssetSource.bytes(build(2, depth, 0, 1, 1, block(0, 0xffff, 3))))
                        .join();
        assertArrayEquals(new byte[] {0, 0, 0, (byte) (depth == 0 ? 255 : 0)}, image.rgba());
    }

    @Test
    void bc2AlwaysUsesFourColorsAndExplicitNibbles() {
        byte[] payload = new byte[16];
        Arrays.fill(payload, 0, 8, (byte) 0x21);
        System.arraycopy(block(0, 0xffff, 0xeeeeeeee), 0, payload, 8, 8);
        var image = Blp2Decoder.decode(AssetSource.bytes(build(2, 4, 1, 4, 4, payload))).join();
        for (int i = 0; i < 16; i++) {
            byte[] pixel = Arrays.copyOfRange(image.rgba(), i * 4, i * 4 + 4);
            int value = i % 2 == 0 ? 85 : 170;
            assertArrayEquals(
                    new byte[] {
                        (byte) value, (byte) value, (byte) value, (byte) (i % 2 == 0 ? 17 : 34)
                    },
                    pixel);
        }
    }

    @Test
    void bc3BothAlphaBranchesHighSelectorsAndObservedDepth72() {
        for (int[] endpoints : new int[][] {{210, 0}, {10, 20}, {50, 50}}) {
            byte[] p = new byte[16];
            p[0] = (byte) endpoints[0];
            p[1] = (byte) endpoints[1];
            long selectors = 0;
            for (int i = 0; i < 16; i++) {
                selectors |= (long) (i % 8) << (i * 3);
            }
            for (int i = 0; i < 6; i++) {
                p[2 + i] = (byte) (selectors >>> (i * 8));
            }
            System.arraycopy(block(0, 0xffff, -1), 0, p, 8, 8);
            var image = Blp2Decoder.decode(AssetSource.bytes(build(2, 72, 7, 4, 4, p))).join();
            int[] expected =
                    endpoints[0] == 210
                            ? new int[] {210, 0, 180, 150, 120, 90, 60, 30}
                            : endpoints[0] == 10
                                    ? new int[] {10, 20, 12, 14, 16, 18, 0, 255}
                                    : new int[] {50, 50, 50, 50, 50, 50, 0, 255};
            for (int i = 0; i < 16; i++) {
                assertEquals(expected[i % 8], image.rgba()[i * 4 + 3] & 255);
            }
        }
    }

    @Test
    void bgraPreservesOrientationStraightAlphaAndOwnedBytes() {
        var image =
                Blp2Decoder.decode(
                                AssetSource.bytes(
                                        build(
                                                3,
                                                8,
                                                0,
                                                1,
                                                2,
                                                new byte[] {
                                                    20,
                                                    10,
                                                    (byte) 200,
                                                    (byte) 128,
                                                    (byte) 240,
                                                    60,
                                                    50,
                                                    0
                                                })))
                        .join();
        byte[] expected = {(byte) 200, 10, 20, (byte) 128, 50, 60, (byte) 240, 0};
        assertArrayEquals(expected, image.rgba());
        byte[] changed = image.rgba();
        changed[0] = 0;
        assertArrayEquals(expected, image.rgba());
    }

    @Test
    void selectedMipOnlyReadsHeaderPaletteAndOwnPayload() {
        byte[] bytes = build(1, 0, 0, 4, 4, new byte[16], new byte[4]);
        AtomicInteger reads = new AtomicInteger();
        AssetSource inner = AssetSource.bytes(bytes);
        AssetSource source =
                new AssetSource() {
                    public long length() {
                        return inner.length();
                    }

                    public CompletableFuture<byte[]> readAt(long at, int n, Cancellation token) {
                        reads.addAndGet(n);
                        return inner.readAt(at, n, token);
                    }
                };
        var image =
                Blp2Decoder.decode(
                                source,
                                1,
                                new Limits(64L << 20, 16384, 4, 4, 16),
                                Cancellation.NONE)
                        .join();
        assertEquals(2, image.width());
        assertEquals(148 + 1024 + 4, reads.get());
        code("BLP_MIP_UNAVAILABLE", bytes, 2);
    }

    @Test
    void compactBlockLevelsAreDescribedAndNeverInvented() {
        byte[] bytes = build(2, 8, 1, 8, 2, new byte[16], new byte[16]);
        var mips =
                Blp2Decoder.readMips(AssetSource.bytes(bytes), new Limits(), Cancellation.NONE)
                        .join();
        assertEquals(MipStorage.COMPACT, mips.getFirst().storage());
        code("BLP_UNSUPPORTED", bytes, 0);
        assertEquals(
                1,
                Blp2Decoder.decodeLargestWithin(
                                AssetSource.bytes(bytes), 8, new Limits(), Cancellation.NONE)
                        .join()
                        .mipLevel());
    }

    @Test
    void descriptorsStructureIsGlobalButRangeValidationOnlySelected() {
        byte[] bytes = build(1, 0, 0, 4, 4, new byte[16], new byte[4]);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, -1);
        assertEquals(4, Blp2Decoder.decode(AssetSource.bytes(bytes)).join().width());
        code("BLP_INVALID", bytes, 1);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 0);
        code("BLP_INVALID", bytes, 0);
    }

    @Test
    void allPaletteAndPayloadTruncationsRefused() {
        byte[] bytes = build(1, 4, 0, 3, 1, new byte[] {1, 2, 3, 0x21, 15});
        for (int size = 0; size < bytes.length; size++) {
            code("BLP_INVALID", Arrays.copyOf(bytes, size), 0);
        }
    }

    @Test
    void unsupportedProfilesAndFlagsStayExplicit() {
        for (int[] p : new int[][] {{1, 1, 8}, {2, 71, 7}, {2, 72, 1}, {3, 0, 0}, {3, 8, 8}}) {
            code("BLP_UNSUPPORTED", build(p[0], p[1], p[2], 1, 1, new byte[16]), 0);
        }
        byte[] b = build(3, 8, 0, 1, 1, new byte[4]);
        b[11] = 0x12;
        code("BLP_UNSUPPORTED", b, 0);
        assertTrue(List.of(0, 1, 16, 17).stream().allMatch(Blp2Decoder::isMipFlags));
    }

    @Test
    void cancellationAndChangingLengthAreNotSuccessfulImages() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        assertInstanceOf(
                java.util.concurrent.CancellationException.class,
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class,
                                () ->
                                        Blp2Decoder.decode(
                                                        AssetSource.bytes(
                                                                build(3, 8, 0, 1, 1, new byte[4])),
                                                        0,
                                                        new Limits(),
                                                        canceled)
                                                .join())));
        AssetSource delegate = AssetSource.bytes(build(3, 8, 0, 1, 1, new byte[4]));
        AtomicInteger calls = new AtomicInteger();
        AssetSource changing =
                new AssetSource() {
                    public long length() {
                        return delegate.length() + (calls.get() > 1 ? 1 : 0);
                    }

                    public CompletableFuture<byte[]> readAt(long at, int n, Cancellation token) {
                        calls.incrementAndGet();
                        return delegate.readAt(at, n, token);
                    }
                };
        Throwable failed =
                AssetTasks.unwrap(
                        assertThrows(
                                RuntimeException.class, () -> Blp2Decoder.decode(changing).join()));
        assertEquals("BLP_INVALID", ((BlpFormatException) failed).code());
    }
}
