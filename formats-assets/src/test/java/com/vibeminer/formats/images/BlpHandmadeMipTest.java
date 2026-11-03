package com.vibeminer.formats.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        for (int[] tuple : new int[][] {{2, 4, 1}, {2, 8, 7}, {2, 0, 0}, {3, 8, 0}, {1, 8, 0}}) {
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
