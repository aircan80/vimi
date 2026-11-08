package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Authored skeleton files: chunks built in code, with arrays placed after each chunk header. */
class M2SkeletonFileTest {
    private static ByteBuffer le(int bytes) {
        return ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] chunk(String id, byte[] payload) {
        return le(8 + payload.length)
                .put(id.getBytes(StandardCharsets.US_ASCII))
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    private static byte[] file(byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static byte[] skl1(int flags) {
        return chunk("SKL1", le(16).putInt(flags).array());
    }

    /** SKS1: 32-byte header, then globals (500, 0), Walk (4) and Stand (0, alias of 1). */
    private static byte[] sks1(Consumer<ByteBuffer> change) {
        var b = le(32 + 8 + 2 * 64 + 4);
        b.putInt(0, 2).putInt(4, 32);
        b.putInt(8, 2).putInt(12, 40);
        b.putInt(16, 2).putInt(20, 168);
        b.putInt(32, 500).putInt(36, 0);
        b.putShort(40, (short) 4).putInt(44, 800).putInt(52, 0x20);
        b.putShort(104, (short) 0).putShort(106, (short) 1).putInt(108, 1333);
        // Byte 60 (next variation) differs from byte 62 (alias index), which is the one read.
        b.putInt(116, 0x1060).putShort(164, (short) 7).putShort(166, (short) 1);
        b.putShort(168, (short) 1).putShort(170, (short) 0);
        change.accept(b);
        return chunk("SKS1", b.array());
    }

    /** SKB1: 16-byte header, then a root bone and its child, then a two-entry key-bone lookup. */
    private static byte[] skb1(Consumer<ByteBuffer> change) {
        var b = le(16 + 2 * 88 + 4);
        b.putInt(0, 2).putInt(4, 16);
        b.putInt(8, 2).putInt(12, 192);
        for (int bone = 0; bone < 2; bone++) {
            int row = 16 + bone * 88;
            b.putInt(row, bone == 0 ? 0 : -1);
            b.putInt(row + 4, bone == 0 ? 0x200 : 0x8);
            b.putShort(row + 8, (short) (bone - 1));
            b.putShort(row + 10, (short) bone);
            b.putFloat(row + 76, 1).putFloat(row + 80, 2 * bone).putFloat(row + 84, 3);
        }
        change.accept(b);
        return chunk("SKB1", b.array());
    }

    private static byte[] skpd(int parent) {
        return chunk("SKPD", le(16).putInt(8, parent).array());
    }

    private static byte[] afid() {
        return chunk(
                "AFID",
                le(16).putShort((short) 4)
                        .putShort((short) 0)
                        .putInt(1001)
                        .putShort((short) 0)
                        .putShort((short) 1)
                        .putInt(1002)
                        .array());
    }

    private static byte[] bfid() {
        return chunk("BFID", le(8).putInt(2001).putInt(2002).array());
    }

    private static byte[] valid() {
        return file(
                skl1(0x100),
                sks1(b -> {}),
                skb1(b -> {}),
                chunk("SKA1", new byte[16]),
                afid(),
                skpd(3001),
                bfid());
    }

    private static M2SkeletonFile.Skeleton read(byte[] bytes) {
        return M2SkeletonFile.read(AssetSource.bytes(bytes), Cancellation.NONE).join();
    }

    private static void refused(String code, String message, byte[] bytes) {
        var error = AssetTasks.unwrap(assertThrows(RuntimeException.class, () -> read(bytes)));
        var format = assertInstanceOf(ModelFormatException.class, error);
        assertEquals(code, format.code());
        assertEquals(message, format.getMessage());
    }

    @Test
    void readsEveryChunksFacts() {
        byte[] bytes = valid();
        var skeleton = read(bytes);
        assertEquals(bytes.length, skeleton.sourceBytes());
        assertEquals(0x100, skeleton.flags());
        assertEquals(
                List.of("SKL1", "SKS1", "SKB1", "SKA1", "AFID", "SKPD", "BFID"),
                skeleton.chunks().stream().map(M2Types.Chunk::id).toList());
        assertEquals(3001, skeleton.parentFileDataId());
        assertEquals(List.of(500L, 0L), skeleton.globalSequenceMillis());
        assertEquals(
                List.of(
                        new M2SkeletonFile.Sequence(4, 0, 800, 0x20, 0),
                        new M2SkeletonFile.Sequence(0, 1, 1333, 0x1060, 1)),
                skeleton.sequences());
        assertEquals(
                List.of(
                        new M2Types.Bone(0, 0x200, -1, 0, new M2Types.Vec3(1, 0, 3)),
                        new M2Types.Bone(-1, 0x8, 0, 1, new M2Types.Vec3(1, 2, 3))),
                skeleton.bones());
        assertEquals(
                List.of(
                        new M2Types.AnimationFile(4, 0, 1001),
                        new M2Types.AnimationFile(0, 1, 1002)),
                skeleton.animationFiles());
        assertEquals(List.of(2001L, 2002L), skeleton.boneFileDataIds());
        // Chunk offsets point at chunk data, after the 8-byte header.
        var skb1 = skeleton.chunk("SKB1");
        assertEquals(8 + 16 + 8 + 32 + 8 + 2 * 64 + 4 + 8, skb1.offset());
        assertEquals(16 + 2 * 88 + 4, skb1.byteLength());
        assertNull(skeleton.chunk("SKID"));
        // 'Z' is the top of the identifier range.
        assertEquals("SKZ1", read(file(skl1(0), chunk("SKZ1", new byte[0]))).chunks().get(1).id());
    }

    @Test
    void absentChunksReadAsEmpty() {
        var skeleton = read(file(skl1(0)));
        assertEquals(0, skeleton.parentFileDataId());
        assertEquals(List.of(), skeleton.globalSequenceMillis());
        assertEquals(List.of(), skeleton.sequences());
        assertEquals(List.of(), skeleton.bones());
        assertEquals(List.of(), skeleton.animationFiles());
        assertEquals(List.of(), skeleton.boneFileDataIds());
        // Empty arrays may carry any offset.
        var empty = read(file(skl1(0), sks1(b -> b.putInt(16, 0).putInt(20, 999_999))));
        assertEquals(2, empty.sequences().size());
    }

    @Test
    void structuralDisagreementIsInvalid() {
        refused("MODEL_INVALID", "The skeleton lacks its SKL1 chunk.", file(skb1(b -> {})));
        refused(
                "MODEL_INVALID",
                "The skeleton repeats its SKB1 chunk.",
                file(skl1(0), skb1(b -> {}), skb1(b -> {})));
        refused(
                "MODEL_INVALID",
                "The skeleton ends inside a chunk header.",
                file(skl1(0), new byte[4]));
        refused("MODEL_INVALID", "The skeleton ends inside a chunk header.", new byte[4]);
        byte[] overrun = valid();
        ByteBuffer.wrap(overrun).order(ByteOrder.LITTLE_ENDIAN).putInt(4, overrun.length);
        refused("MODEL_INVALID", "Skeleton chunk SKL1 exceeds its source.", overrun);
        refused(
                "MODEL_INVALID",
                "The skeleton has a malformed chunk identifier.",
                file(skl1(0), chunk("sk\u0001!", new byte[0])));
        refused(
                "MODEL_INVALID",
                "The skeleton's SKL1 chunk is truncated.",
                file(chunk("SKL1", new byte[2])));
        refused(
                "MODEL_INVALID",
                "The skeleton's SKPD chunk is truncated.",
                file(skl1(0), chunk("SKPD", new byte[8])));
        refused(
                "MODEL_INVALID",
                "The skeleton's SKS1 chunk is truncated.",
                file(skl1(0), chunk("SKS1", new byte[16])));
        // Arrays must lie inside their chunk, after its array headers.
        refused(
                "MODEL_INVALID",
                "The skeleton's sequences lie outside its SKS1 chunk.",
                file(skl1(0), sks1(b -> b.putInt(12, 120))));
        refused(
                "MODEL_INVALID",
                "The skeleton's sequences lie outside its SKS1 chunk.",
                file(skl1(0), sks1(b -> b.putInt(12, 8))));
        // The SKS1 array headers take 32 bytes; an array may not start inside them.
        refused(
                "MODEL_INVALID",
                "The skeleton's sequences lie outside its SKS1 chunk.",
                file(skl1(0), sks1(b -> b.putInt(12, 24))));
        // Chunk identifiers are '0'..'Z'; '[' is just above the range.
        refused(
                "MODEL_INVALID",
                "The skeleton has a malformed chunk identifier.",
                file(skl1(0), chunk("SK[1", new byte[0])));
        // A non-finite bone pivot, worded for the skeleton.
        refused(
                "MODEL_INVALID",
                "A skeleton bone pivot is not finite.",
                file(skl1(0), skb1(b -> b.putFloat(16 + 88 + 80, Float.NaN))));
        refused(
                "MODEL_INVALID",
                "The skeleton's sequence lookups lie outside its SKS1 chunk.",
                file(skl1(0), sks1(b -> b.putInt(16, 3))));
        refused(
                "MODEL_INVALID",
                "The skeleton's bones lie outside its SKB1 chunk.",
                file(skl1(0), skb1(b -> b.putInt(0, 3))));
        refused(
                "MODEL_INVALID",
                "The skeleton's key-bone lookups lie outside its SKB1 chunk.",
                file(skl1(0), skb1(b -> b.putInt(12, 4))));
        refused(
                "MODEL_INVALID",
                "A skeleton bone has an invalid parent index.",
                file(skl1(0), skb1(b -> b.putShort(16 + 88 + 8, (short) 1))));
        refused(
                "MODEL_INVALID",
                "A skeleton bone has an invalid parent index.",
                file(skl1(0), skb1(b -> b.putShort(16 + 8, (short) 2))));
        refused(
                "MODEL_INVALID",
                "The skeleton's AFID chunk has a partial record.",
                file(skl1(0), chunk("AFID", new byte[12])));
        refused(
                "MODEL_INVALID",
                "The skeleton's BFID chunk has a partial ID.",
                file(skl1(0), chunk("BFID", new byte[6])));
    }

    @Test
    void budgetsAreLimits() {
        refused(
                "MODEL_LIMIT",
                "The skeleton has 4,097 sequences; skeletons read at most 4,096.",
                file(skl1(0), sks1(b -> b.putInt(8, 4097))));
        refused(
                "MODEL_LIMIT",
                "The skeleton has 1,025 global sequences; skeletons read at most 1,024.",
                file(skl1(0), sks1(b -> b.putInt(0, 1025))));
        refused(
                "MODEL_LIMIT",
                "The skeleton has 4,097 bones; skeletons read at most 4,096.",
                file(skl1(0), skb1(b -> b.putInt(0, 4097))));
        var many = new byte[M2SkeletonFile.MAX_CHUNKS + 1][];
        many[0] = skl1(0);
        for (int i = 1; i < many.length; i++) {
            many[i] = chunk(String.format("X%03d", i), new byte[0]);
        }
        refused("MODEL_LIMIT", "The skeleton has more than 64 chunks.", file(many));
    }
}
