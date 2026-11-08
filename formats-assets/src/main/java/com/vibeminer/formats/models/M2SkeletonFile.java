package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.i32;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded reader for the facts of one external skeleton file ({@code .skel}) named by a model's
 * SKID chunk (#384).
 *
 * <p>The file is a sequence of chunks, each at most once: {@code SKL1} (skeleton flags) is
 * required; {@code SKS1} (global sequence lengths, sequences, sequence lookups), {@code SKB1}
 * (bones, key-bone lookup), {@code SKPD} (parent skeleton file ID), {@code AFID} (animation files)
 * and {@code BFID} (bone files) are read when present. Other chunks (for example {@code SKA1}
 * attachments) are listed but not read. Array offsets inside {@code SKS1} and {@code SKB1} count
 * from the start of that chunk's data, and every array must lie inside its chunk after the chunk's
 * array headers.
 *
 * <p>Bones and sequences use the same 88- and 64-byte records as the model's MD21 payload. This
 * reads their facts only; bone tracks are not read here (their arrays stay addressable through
 * {@link Skeleton#chunks()}), and what a child skeleton takes from its parent is not interpreted.
 */
public final class M2SkeletonFile {
    public static final String REVISION = "m2-skeleton-file-v1";
    public static final int MAX_CHUNKS = 64;
    public static final int MAX_BONES = 4096;
    public static final int MAX_SEQUENCES = 4096;
    public static final int MAX_GLOBAL_SEQUENCES = 1024;
    public static final int MAX_FILE_IDS = 65_536;
    private static final int SKS1_HEADER = 32, SKB1_HEADER = 16, SKPD_BYTES = 12;

    private M2SkeletonFile() {}

    /** One sequence record: its ID, variation, duration, flags and alias index (byte 62). */
    public record Sequence(int id, int variation, long durationMillis, int flags, int aliasNext) {}

    /** The facts of one skeleton file. Lists are immutable; absent chunks read as empty or 0. */
    public record Skeleton(
            long sourceBytes,
            long flags,
            List<M2Types.Chunk> chunks,
            long parentFileDataId,
            List<Long> globalSequenceMillis,
            List<Sequence> sequences,
            List<M2Types.Bone> bones,
            List<M2Types.AnimationFile> animationFiles,
            List<Long> boneFileDataIds) {
        public Skeleton {
            chunks = List.copyOf(chunks);
            globalSequenceMillis = List.copyOf(globalSequenceMillis);
            sequences = List.copyOf(sequences);
            bones = List.copyOf(bones);
            animationFiles = List.copyOf(animationFiles);
            boneFileDataIds = List.copyOf(boneFileDataIds);
        }

        /** The chunk with this ID, or null. */
        public M2Types.Chunk chunk(String id) {
            return chunks.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
        }
    }

    /**
     * Reads one skeleton file. Errors are {@link ModelFormatException}s with codes {@code
     * MODEL_INVALID}, {@code MODEL_UNSUPPORTED} or {@code MODEL_LIMIT}.
     */
    public static CompletableFuture<Skeleton> read(AssetSource source, Cancellation token) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(token);
        return AssetTasks.run(() -> readCore(source, token));
    }

    private static Skeleton readCore(AssetSource source, Cancellation token) {
        token.check();
        if (source.length() < 8) {
            throw error("INVALID", "The skeleton ends inside a chunk header.");
        }
        var input = new M2Input(source, new M2Types.Limits(), token, "skeleton");
        var chunks = new ArrayList<M2Types.Chunk>();
        Map<String, M2Types.Chunk> known = new HashMap<>();
        for (long at = 0; at < input.length; ) {
            token.check();
            if (chunks.size() == MAX_CHUNKS) {
                throw error("LIMIT", "The skeleton has more than " + MAX_CHUNKS + " chunks.");
            }
            if (input.length - at < 8) {
                throw error("INVALID", "The skeleton ends inside a chunk header.");
            }
            byte[] header = input.at(at, 8);
            for (int i = 0; i < 4; i++) {
                if (header[i] < '0' || header[i] > 'Z') {
                    throw error("INVALID", "The skeleton has a malformed chunk identifier.");
                }
            }
            String id = new String(header, 0, 4, StandardCharsets.US_ASCII);
            long size = u32(header, 4);
            if (size > input.length - at - 8) {
                throw error("INVALID", "Skeleton chunk " + id + " exceeds its source.");
            }
            var chunk = new M2Types.Chunk(id, at + 8, size);
            if (known.putIfAbsent(id, chunk) != null) {
                throw error("INVALID", "The skeleton repeats its " + id + " chunk.");
            }
            chunks.add(chunk);
            at += 8 + size;
        }
        var skl1 = known.get("SKL1");
        if (skl1 == null) {
            throw error("INVALID", "The skeleton lacks its SKL1 chunk.");
        }
        if (skl1.byteLength() < 4) {
            throw error("INVALID", "The skeleton's SKL1 chunk is truncated.");
        }
        long flags = u32(input.at(skl1.offset(), 4));

        long parent = 0;
        var skpd = known.get("SKPD");
        if (skpd != null) {
            if (skpd.byteLength() < SKPD_BYTES) {
                throw error("INVALID", "The skeleton's SKPD chunk is truncated.");
            }
            parent = u32(input.at(skpd.offset(), SKPD_BYTES), 8);
        }

        var globals = new ArrayList<Long>();
        var sequences = new ArrayList<Sequence>();
        var sks1 = known.get("SKS1");
        if (sks1 != null) {
            byte[] head = header(input, sks1, SKS1_HEADER);
            input.records(
                    array(sks1, head, 0, 4, MAX_GLOBAL_SEQUENCES, SKS1_HEADER, "global sequences"),
                    (row, i) -> globals.add(u32(row)));
            input.records(
                    array(sks1, head, 8, 64, MAX_SEQUENCES, SKS1_HEADER, "sequences"),
                    (row, i) ->
                            sequences.add(
                                    new Sequence(
                                            u16(row),
                                            u16(row, 2),
                                            u32(row, 4),
                                            i32(row, 12),
                                            u16(row, 62))));
            // Sequence lookups are not read, but must lie inside the chunk.
            array(sks1, head, 16, 2, MAX_SEQUENCES, SKS1_HEADER, "sequence lookups");
        }

        var bones = new ArrayList<M2Types.Bone>();
        var skb1 = known.get("SKB1");
        if (skb1 != null) {
            byte[] head = header(input, skb1, SKB1_HEADER);
            var span = array(skb1, head, 0, 88, MAX_BONES, SKB1_HEADER, "bones");
            int count = span.count();
            input.records(span, (row, i) -> bones.add(M2Input.bone(row, i, count, "skeleton")));
            array(skb1, head, 8, 2, MAX_BONES, SKB1_HEADER, "key-bone lookups");
        }

        var animations = new ArrayList<M2Types.AnimationFile>();
        var afid = known.get("AFID");
        if (afid != null) {
            if (afid.byteLength() % 8 != 0) {
                throw error("INVALID", "The skeleton's AFID chunk has a partial record.");
            }
            input.records(
                    ids(afid, 8, "AFID"),
                    (row, i) ->
                            animations.add(
                                    new M2Types.AnimationFile(u16(row), u16(row, 2), u32(row, 4))));
        }
        var boneFiles = new ArrayList<Long>();
        var bfid = known.get("BFID");
        if (bfid != null) {
            if (bfid.byteLength() % 4 != 0) {
                throw error("INVALID", "The skeleton's BFID chunk has a partial ID.");
            }
            input.records(ids(bfid, 4, "BFID"), (row, i) -> boneFiles.add(u32(row)));
        }
        input.finish();
        return new Skeleton(
                input.length,
                flags,
                chunks,
                parent,
                globals,
                sequences,
                bones,
                animations,
                boneFiles);
    }

    private static byte[] header(M2Input input, M2Types.Chunk chunk, int bytes) {
        if (chunk.byteLength() < bytes) {
            throw error("INVALID", "The skeleton's " + chunk.id() + " chunk is truncated.");
        }
        return input.at(chunk.offset(), bytes);
    }

    /** The array whose count and chunk-relative offset are at {@code at} in the chunk header. */
    private static M2Input.Span array(
            M2Types.Chunk chunk,
            byte[] head,
            int at,
            int stride,
            int maximum,
            int headerBytes,
            String name) {
        long count = u32(head, at), offset = u32(head, at + 4);
        if (count > maximum) {
            throw error(
                    "LIMIT",
                    "The skeleton has "
                            + M2Input.number(count)
                            + " "
                            + name
                            + "; skeletons read at most "
                            + M2Input.number(maximum)
                            + ".");
        }
        if (count != 0
                && (offset < headerBytes
                        || offset > chunk.byteLength()
                        || count * stride > chunk.byteLength() - offset)) {
            throw error(
                    "INVALID",
                    "The skeleton's " + name + " lie outside its " + chunk.id() + " chunk.");
        }
        return new M2Input.Span(name, chunk.offset() + offset, (int) count, stride);
    }

    private static M2Input.Span ids(M2Types.Chunk chunk, int stride, String id) {
        long count = chunk.byteLength() / stride;
        if (count > MAX_FILE_IDS) {
            throw error(
                    "LIMIT",
                    "The skeleton's "
                            + id
                            + " chunk names "
                            + M2Input.number(count)
                            + " files; skeletons read at most "
                            + M2Input.number(MAX_FILE_IDS)
                            + ".");
        }
        return new M2Input.Span(id, chunk.offset(), (int) count, stride);
    }
}
