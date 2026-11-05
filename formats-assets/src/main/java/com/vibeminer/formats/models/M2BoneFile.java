package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded, exact reader for one external bone file named by a model's BFID chunk.
 *
 * <p>The admitted layout is a little-endian {@code uint32} version 1 followed by exactly two
 * chunks, each once and in any order: {@code BIDA} (one {@code uint16} model bone index per entry)
 * and {@code BOMT} (one 4x4 {@code float} matrix of 64 bytes per entry, in file order). The chunks
 * must consume the file exactly. Unknown chunks, other versions and matrices whose fourth column is
 * not {@code (0, 0, 0, 1)} are refused as unsupported; structural disagreement is invalid.
 *
 * <p>This reads the file's facts only. Which bone file the client applies for a given appearance,
 * and how its matrices combine with animated bone transforms, are not interpreted here.
 */
public final class M2BoneFile {
    public static final String REVISION = "m2-bone-file-v1";
    public static final long VERSION = 1;
    public static final int MAX_BYTES = 1 << 20;
    public static final int MAX_ENTRIES = 4096;
    private static final int MATRIX_BYTES = 64;

    private M2BoneFile() {}

    /** Validated entries: model bone indices and their matrices, 16 floats per entry. */
    public static final class Bones {
        private final int[] bones;
        private final float[] matrices;

        private Bones(int[] bones, float[] matrices) {
            this.bones = bones;
            this.matrices = matrices;
        }

        public int count() {
            return bones.length;
        }

        public int[] bones() {
            return bones.clone();
        }

        /**
         * Row {@code i} of {@link #bones()} owns floats {@code [16 i, 16 i + 16)} in file order.
         */
        public float[] matrices() {
            return matrices.clone();
        }
    }

    /**
     * Reads one bone file whose entries must index into a model with {@code modelBones} bones.
     * Errors are {@link ModelFormatException}s with codes {@code MODEL_INVALID}, {@code
     * MODEL_UNSUPPORTED} or {@code MODEL_LIMIT}.
     */
    public static CompletableFuture<Bones> read(
            AssetSource source, int modelBones, Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(token);
        if (modelBones < 1 || modelBones > 65536) {
            throw new IllegalArgumentException("Model bone count must be 1..65536");
        }
        return AssetTasks.run(() -> readCore(source, modelBones, token));
    }

    private static Bones readCore(AssetSource source, int modelBones, Cancellation token) {
        token.check();
        if (source.protectedRanges()) {
            throw error("UNSUPPORTED", "The bone file has protected ranges.");
        }
        long length = source.length();
        if (length > MAX_BYTES) {
            throw error(
                    "LIMIT",
                    "The bone file is "
                            + M2Input.number(length)
                            + " bytes; bone files read at most "
                            + M2Input.number(MAX_BYTES)
                            + ".");
        }
        if (length < 4) {
            throw error("INVALID", "The bone file version is truncated.");
        }
        byte[] data = readAll(source, (int) length, token);
        long version = u32(data, 0);
        if (version != VERSION) {
            throw error(
                    "UNSUPPORTED",
                    "Bone file version "
                            + version
                            + " is not the reviewed version "
                            + VERSION
                            + ".");
        }
        int ids = -1, idBytes = 0, matrices = -1, matrixBytes = 0;
        int at = 4;
        while (at < data.length) {
            token.check();
            if (data.length - at < 8) {
                throw error("INVALID", "The bone file ends inside a chunk header.");
            }
            String id = new String(data, at, 4, StandardCharsets.US_ASCII);
            long size = u32(data, at + 4);
            if (size > data.length - at - 8L) {
                throw error("INVALID", "Bone file chunk " + printable(id) + " exceeds its source.");
            }
            switch (id) {
                case "BIDA" -> {
                    if (ids >= 0) {
                        throw error("INVALID", "The bone file repeats its BIDA chunk.");
                    }
                    ids = at + 8;
                    idBytes = (int) size;
                }
                case "BOMT" -> {
                    if (matrices >= 0) {
                        throw error("INVALID", "The bone file repeats its BOMT chunk.");
                    }
                    matrices = at + 8;
                    matrixBytes = (int) size;
                }
                default ->
                        throw error(
                                "UNSUPPORTED",
                                "The bone file contains unreviewed chunk " + printable(id) + ".");
            }
            at += 8 + (int) size;
        }
        if (ids < 0 || matrices < 0) {
            throw error("INVALID", "The bone file lacks its BIDA or BOMT chunk.");
        }
        if (idBytes == 0 || idBytes % 2 != 0) {
            throw error("INVALID", "The bone file's BIDA chunk is empty or has a partial entry.");
        }
        int count = idBytes / 2;
        if (count > MAX_ENTRIES) {
            throw error(
                    "LIMIT",
                    "The bone file has "
                            + M2Input.number(count)
                            + " entries; bone files read at most "
                            + M2Input.number(MAX_ENTRIES)
                            + ".");
        }
        if (matrixBytes != count * MATRIX_BYTES) {
            throw error(
                    "INVALID", "The bone file's BOMT matrix count differs from its BIDA entries.");
        }
        int[] bones = new int[count];
        var seen = new BitSet(modelBones);
        for (int i = 0; i < count; i++) {
            int bone = u16(data, ids + i * 2);
            if (bone >= modelBones) {
                throw error(
                        "INVALID",
                        "Bone file entry "
                                + i
                                + " names bone "
                                + bone
                                + " outside the model's "
                                + modelBones
                                + " bones.");
            }
            if (seen.get(bone)) {
                throw error("INVALID", "The bone file names bone " + bone + " twice.");
            }
            seen.set(bone);
            bones[i] = bone;
        }
        float[] values = new float[count * 16];
        for (int i = 0; i < values.length; i++) {
            if ((i & 1023) == 0) {
                token.check();
            }
            float value = Float.intBitsToFloat((int) u32(data, matrices + i * 4));
            if (!Float.isFinite(value)) {
                throw error("INVALID", "A bone file matrix value is not finite.");
            }
            values[i] = value;
        }
        for (int i = 0; i < count; i++) {
            int m = i * 16;
            if (values[m + 3] != 0
                    || values[m + 7] != 0
                    || values[m + 11] != 0
                    || values[m + 15] != 1) {
                throw error(
                        "UNSUPPORTED",
                        "Bone file matrix "
                                + i
                                + " is not affine with translation in its last row.");
            }
        }
        return new Bones(bones, values);
    }

    private static byte[] readAll(AssetSource source, int length, Cancellation token) {
        byte[] data = new byte[length];
        for (int at = 0; at < length; at += AssetSource.MAX_READ_BYTES) {
            token.check();
            int count = Math.min(AssetSource.MAX_READ_BYTES, length - at);
            byte[] part;
            try {
                part = source.readAt(at, count, token).join();
            } catch (RuntimeException e) {
                if (AssetTasks.unwrap(e) instanceof java.io.EOFException) {
                    throw error("INVALID", "The bone file ended unexpectedly.");
                }
                throw e;
            }
            if (part.length != count) {
                throw error("INVALID", "The bone file ended unexpectedly.");
            }
            System.arraycopy(part, 0, data, at, count);
        }
        if (source.length() != length) {
            throw error("INVALID", "The bone file length changed while it was read.");
        }
        return data;
    }

    private static String printable(String id) {
        return id.chars().allMatch(c -> c >= 0x20 && c < 0x7f) ? "'" + id + "'" : "(binary)";
    }
}
