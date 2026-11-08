package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Types.Bounds;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Vec2;
import com.vibeminer.formats.models.M2Types.Vec3;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;

final class M2Input {
    record Span(String name, long offset, int count, int stride) {}

    final AssetSource source;
    final Cancellation token;
    final long length;

    /** "model", or "skeleton" for skeleton files: the noun in source-level refusals. */
    final String subject;

    M2Input(AssetSource source, Limits limits, Cancellation token) {
        this(source, limits, token, "model");
    }

    M2Input(AssetSource source, Limits limits, Cancellation token, String subject) {
        this.source = Objects.requireNonNull(source);
        this.token = Objects.requireNonNull(token);
        this.subject = Objects.requireNonNull(subject);
        checkLimits(limits);
        token.check();
        if (source.protectedRanges()) {
            throw error(
                    "UNSUPPORTED",
                    "The initial "
                            + subject
                            + " profile requires a complete decoded source, not protected ranges.");
        }
        length = source.length();
        if (length > limits.maxSourceBytes()) {
            throw error("LIMIT", "The " + subject + " source exceeds its byte budget.");
        }
        if (length < 4) {
            throw error("INVALID", "The " + subject + " magic is truncated.");
        }
    }

    static ModelFormatException error(String code, String message) {
        return new ModelFormatException("MODEL_" + code, message);
    }

    static void checkLimits(Limits l) {
        Limits ceiling = new Limits();
        long[] values = {
            l.maxSourceBytes(),
            l.maxOutputBytes(),
            l.maxVertices(),
            l.maxVertexLookup(),
            l.maxTriangleIndices(),
            l.maxSections(),
            l.maxMaterials(),
            l.maxBatches(),
            l.maxTextures(),
            l.maxSkinProfiles(),
            l.maxChunks(),
            l.maxNameBytes(),
            l.maxMetadataEntries()
        };
        long[] caps = {
            ceiling.maxSourceBytes(),
            ceiling.maxOutputBytes(),
            ceiling.maxVertices(),
            ceiling.maxVertexLookup(),
            ceiling.maxTriangleIndices(),
            ceiling.maxSections(),
            ceiling.maxMaterials(),
            ceiling.maxBatches(),
            ceiling.maxTextures(),
            ceiling.maxSkinProfiles(),
            ceiling.maxChunks(),
            ceiling.maxNameBytes(),
            ceiling.maxMetadataEntries()
        };
        for (int i = 0; i < values.length; i++) {
            if (values[i] < (i == 0 ? 4 : i == 4 ? 3 : 1) || values[i] > caps[i]) {
                throw new IllegalArgumentException("Model limits must fit finite decoder ceilings");
            }
        }
    }

    byte[] at(long offset, int count) {
        token.check();
        if (offset < 0
                || count < 0
                || count > 65536
                || offset > length
                || count > length - offset) {
            throw error("INVALID", "A " + subject + " read lies outside its source.");
        }
        byte[] bytes;
        try {
            bytes = source.readAt(offset, count, token).join();
        } catch (RuntimeException e) {
            if (AssetTasks.unwrap(e) instanceof java.io.EOFException) {
                throw error("INVALID", "A " + subject + " structure ended unexpectedly.");
            }
            throw e;
        }
        if (bytes.length != count) {
            throw error("INVALID", "A " + subject + " structure ended unexpectedly.");
        }
        return bytes;
    }

    Span span(
            String name,
            long count,
            long offset,
            int stride,
            int maximum,
            long origin,
            long bytes,
            int header) {
        if (count > maximum) {
            boolean skin = name.startsWith("SKIN ");
            String raw = skin ? name.substring(5) : name;
            StringBuilder words = new StringBuilder();
            for (char c : raw.toCharArray()) {
                if (Character.isUpperCase(c)) {
                    words.append(' ').append(Character.toLowerCase(c));
                } else {
                    words.append(c);
                }
            }
            String owner = skin ? "The SKIN file" : "The " + subject;
            String fact =
                    stride == 1
                            ? owner + "'s " + words + " is " + number(count) + " bytes"
                            : owner
                                    + " has "
                                    + number(count)
                                    + " "
                                    + words
                                    + (words.toString().endsWith("lookup") ? " entries" : "");
            throw error(
                    "LIMIT",
                    fact
                            + "; geometry reads at most "
                            + number(maximum)
                            + (stride == 1 ? " bytes" : "")
                            + ".");
        }
        if (count != 0
                && (offset < header
                        || offset > bytes
                        || count * Math.max(1, stride) > bytes - offset)) {
            throw error(
                    "INVALID",
                    "The "
                            + name
                            + " span lies outside its "
                            + subject
                            + " section or overlaps its header.");
        }
        return new Span(name, origin + offset, (int) count, stride);
    }

    static String number(long n) {
        return String.format(java.util.Locale.ROOT, "%,d", n);
    }

    void records(Span span, BiConsumer<byte[], Integer> consume) {
        token.check();
        if (span.count() == 0) {
            return;
        }
        if (span.stride() < 1 || span.stride() > 256) {
            throw new IllegalStateException("Only fixed bounded records may be read");
        }
        int batch = Math.max(1, 65536 / span.stride());
        for (int first = 0; first < span.count(); ) {
            int rows = Math.min(batch, span.count() - first);
            byte[] buffer = at(span.offset() + (long) first * span.stride(), rows * span.stride());
            for (int row = 0; row < rows; row++) {
                token.check();
                consume.accept(
                        Arrays.copyOfRange(buffer, row * span.stride(), (row + 1) * span.stride()),
                        first + row);
            }
            first += rows;
        }
    }

    void finish() {
        token.check();
        if (source.length() != length) {
            throw error("INVALID", "The source length changed during decoding.");
        }
    }

    /**
     * One 88-byte bone record (key-bone ID, flags, parent, submesh, pivot) at {@code index} of
     * {@code count}; the parent must be -1 or another bone of the same array.
     */
    static M2Types.Bone bone(byte[] row, int index, int count, String subject) {
        int parent = i16(row, 8);
        if (parent < -1 || parent >= count || parent == index) {
            throw error(
                    "INVALID",
                    subject.equals("model")
                            ? "A bone has an invalid parent index."
                            : "A " + subject + " bone has an invalid parent index.");
        }
        float[] pivot = new float[3];
        for (int c = 0; c < 3; c++) {
            pivot[c] = Float.intBitsToFloat(i32(row, 76 + 4 * c));
            if (!Float.isFinite(pivot[c])) {
                throw error(
                        "INVALID",
                        subject.equals("model")
                                ? "A model geometry value is not finite."
                                : "A " + subject + " bone pivot is not finite.");
            }
        }
        return new M2Types.Bone(
                i32(row, 0),
                u32(row, 4),
                parent,
                u16(row, 10),
                new Vec3(pivot[0], pivot[1], pivot[2]));
    }

    static int u16(byte[] b) {
        return u16(b, 0);
    }

    static int u16(byte[] b, int at) {
        return (b[at] & 255) | (b[at + 1] & 255) << 8;
    }

    static int i16(byte[] b, int at) {
        return (short) u16(b, at);
    }

    static long u32(byte[] b) {
        return u32(b, 0);
    }

    static long u32(byte[] b, int at) {
        return (b[at] & 255L)
                | (b[at + 1] & 255L) << 8
                | (b[at + 2] & 255L) << 16
                | (b[at + 3] & 255L) << 24;
    }

    static int i32(byte[] b, int at) {
        return (int) u32(b, at);
    }

    static float f(byte[] b, int at) {
        float v = Float.intBitsToFloat(i32(b, at));
        if (!Float.isFinite(v)) {
            throw error("INVALID", "A model geometry value is not finite.");
        }
        return v;
    }

    static Vec3 v3(byte[] b, int at) {
        return new Vec3(f(b, at), f(b, at + 4), f(b, at + 8));
    }

    static Vec2 v2(byte[] b, int at) {
        return new Vec2(f(b, at), f(b, at + 4));
    }

    static Bounds bounds(byte[] b, int at, boolean empty) {
        Vec3 min = v3(b, at), max = v3(b, at + 12);
        float radius = f(b, at + 24);
        if (radius < 0) {
            throw error("INVALID", "A declared model bound has a negative radius.");
        }
        if (empty && min.x() > max.x() && min.y() > max.y() && min.z() > max.z() && radius == 0) {
            return new Bounds(min, max, radius);
        }
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            throw error("INVALID", "A declared model bound is inverted.");
        }
        return new Bounds(min, max, radius);
    }

    static final class Budget {
        final long maximum;
        long used;

        Budget(long maximum) {
            this(maximum, 0);
        }

        Budget(long maximum, long initial) {
            this.maximum = maximum;
            used = initial;
        }

        void add(long count, int bytes) {
            if (count < 0
                    || bytes < 0
                    || used > maximum
                    || count > (maximum - used) / Math.max(1, bytes)) {
                throw error(
                        "LIMIT",
                        "The combined retained model/SKIN/assembly output exceeds its byte budget.");
            }
            used += count * bytes;
        }
    }
}
