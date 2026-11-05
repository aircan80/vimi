package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Authored bone files: a version word, then BIDA and BOMT chunks built in code. */
class M2BoneFileTest {
    private static final float[] SHIFT = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0.25f, -0.5f, 0, 1};

    private static byte[] chunk(String id, byte[] payload) {
        var buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static byte[] ids(int... bones) {
        var buffer = ByteBuffer.allocate(bones.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int bone : bones) {
            buffer.putShort((short) bone);
        }
        return buffer.array();
    }

    private static byte[] matrices(float[]... values) {
        var buffer = ByteBuffer.allocate(values.length * 64).order(ByteOrder.LITTLE_ENDIAN);
        for (float[] matrix : values) {
            for (float value : matrix) {
                buffer.putFloat(value);
            }
        }
        return buffer.array();
    }

    private static byte[] file(long version, byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(
                ByteBuffer.allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt((int) version)
                        .array());
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] valid() {
        return file(1, chunk("BIDA", ids(3, 1)), chunk("BOMT", matrices(SHIFT, identity())));
    }

    private static float[] identity() {
        return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static M2BoneFile.Bones read(byte[] bytes, int bones) {
        return M2BoneFile.read(AssetSource.bytes(bytes), bones, Cancellation.NONE).join();
    }

    private static void refused(String code, byte[] bytes) {
        M2ReadersTest.failure(code, () -> read(bytes, 4));
    }

    @Test
    void readsEveryEntryInFileOrder() {
        var bones = read(valid(), 4);
        assertEquals(2, bones.count());
        assertArrayEquals(new int[] {3, 1}, bones.bones());
        float[] expected = new float[32];
        System.arraycopy(SHIFT, 0, expected, 0, 16);
        System.arraycopy(identity(), 0, expected, 16, 16);
        assertArrayEquals(expected, bones.matrices());
        // Returned arrays are copies.
        bones.bones()[0] = 9;
        assertEquals(3, bones.bones()[0]);
    }

    @Test
    void chunkOrderIsNotSignificant() {
        var bones = read(file(1, chunk("BOMT", matrices(SHIFT)), chunk("BIDA", ids(0))), 1);
        assertArrayEquals(new int[] {0}, bones.bones());
    }

    @Test
    void unreviewedVersionsChunksAndMatricesAreUnsupported() {
        refused(
                "MODEL_UNSUPPORTED",
                file(2, chunk("BIDA", ids(0)), chunk("BOMT", matrices(SHIFT))));
        refused(
                "MODEL_UNSUPPORTED",
                file(
                        1,
                        chunk("BIDA", ids(0)),
                        chunk("BOMT", matrices(SHIFT)),
                        chunk("XTRA", new byte[4])));
        float[] projective = identity();
        projective[3] = 0.5f;
        refused(
                "MODEL_UNSUPPORTED",
                file(1, chunk("BIDA", ids(0)), chunk("BOMT", matrices(projective))));
        float[] weighted = identity();
        weighted[15] = 2;
        refused(
                "MODEL_UNSUPPORTED",
                file(1, chunk("BIDA", ids(0)), chunk("BOMT", matrices(weighted))));
    }

    @Test
    void structuralDisagreementIsInvalid() {
        // Missing, repeated or empty chunks.
        refused("MODEL_INVALID", file(1, chunk("BIDA", ids(0))));
        refused("MODEL_INVALID", file(1, chunk("BOMT", matrices(SHIFT))));
        refused(
                "MODEL_INVALID",
                file(
                        1,
                        chunk("BIDA", ids(0)),
                        chunk("BIDA", ids(1)),
                        chunk("BOMT", matrices(SHIFT))));
        refused(
                "MODEL_INVALID",
                file(
                        1,
                        chunk("BIDA", ids(0)),
                        chunk("BOMT", matrices(SHIFT)),
                        chunk("BOMT", matrices(SHIFT))));
        refused("MODEL_INVALID", file(1, chunk("BIDA", new byte[0]), chunk("BOMT", new byte[0])));
        // Partial id, and matrix count that differs from the ids.
        refused(
                "MODEL_INVALID",
                file(1, chunk("BIDA", new byte[3]), chunk("BOMT", matrices(SHIFT))));
        refused("MODEL_INVALID", file(1, chunk("BIDA", ids(0, 1)), chunk("BOMT", matrices(SHIFT))));
        // Bones outside the model, or named twice.
        refused("MODEL_INVALID", file(1, chunk("BIDA", ids(4)), chunk("BOMT", matrices(SHIFT))));
        refused(
                "MODEL_INVALID",
                file(1, chunk("BIDA", ids(2, 2)), chunk("BOMT", matrices(SHIFT, SHIFT))));
        // Non-finite matrix values.
        float[] nan = identity();
        nan[12] = Float.NaN;
        refused("MODEL_INVALID", file(1, chunk("BIDA", ids(0)), chunk("BOMT", matrices(nan))));
        // Truncated version, trailing partial header and a chunk past the end.
        refused("MODEL_INVALID", new byte[3]);
        byte[] valid = valid();
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 5);
        refused("MODEL_INVALID", trailing);
        byte[] overrun = valid.clone();
        ByteBuffer.wrap(overrun).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1_000);
        refused("MODEL_INVALID", overrun);
    }

    @Test
    void sizeAndEntryBudgetsAreLimits() {
        refused("MODEL_LIMIT", new byte[M2BoneFile.MAX_BYTES + 1]);
        int count = M2BoneFile.MAX_ENTRIES + 1;
        int[] many = new int[count];
        float[][] values = new float[count][];
        for (int i = 0; i < count; i++) {
            many[i] = i;
            values[i] = identity();
        }
        M2ReadersTest.failure(
                "MODEL_LIMIT",
                () ->
                        read(
                                file(1, chunk("BIDA", ids(many)), chunk("BOMT", matrices(values))),
                                65536));
    }

    @Test
    void readsAreBoundedAndCancellable() {
        // Larger than one source read, so the reader must assemble several bounded reads.
        int count = 1100;
        int[] bones = new int[count];
        float[][] values = new float[count][];
        for (int i = 0; i < count; i++) {
            bones[i] = i;
            values[i] = identity();
        }
        byte[] bytes = file(1, chunk("BIDA", ids(bones)), chunk("BOMT", matrices(values)));
        assertTrue(bytes.length > AssetSource.MAX_READ_BYTES);
        assertEquals(count, read(bytes, count).count());
        var token = new Cancellation();
        token.cancel();
        var failure =
                assertThrows(
                        RuntimeException.class,
                        () -> M2BoneFile.read(AssetSource.bytes(valid()), 4, token).join());
        assertTrue(AssetTasks.unwrap(failure) instanceof CancellationException);
        assertThrows(
                IllegalArgumentException.class,
                () -> M2BoneFile.read(AssetSource.bytes(valid()), 0, Cancellation.NONE));
    }
}
