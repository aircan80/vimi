package com.vibeminer.store;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared public format vectors for differential Java/C# validation. */
public final class VerificationVectors {
    private VerificationVectors() {}

    public record Vector(ObjectKey key, long declared, byte[] bytes, String expected) {}

    public static List<Vector> all() {
        List<Vector> result = new ArrayList<>();
        byte[] plain = {1, 2, 3};
        for (int type : new int[] {1, 5}) {
            ObjectKey key = new ObjectKey(type, Format.hash("MD5", plain));
            result.add(new Vector(key, 3, plain, null));
            result.add(new Vector(key, 2, plain, "OBJECT_SIZE_MISMATCH"));
            result.add(
                    new Vector(
                            new ObjectKey(type, 1, key.bytes()),
                            0,
                            plain,
                            "OBJECT_NAMESPACE_UNSUPPORTED"));
            result.add(new Vector(key, 0, plain, "OBJECT_TOO_LARGE"));
            result.add(
                    new Vector(new ObjectKey(type, new byte[16]), 3, plain, "OBJECT_KEY_MISMATCH"));
        }
        result.add(
                new Vector(
                        new ObjectKey(255, 1, new byte[16]),
                        0,
                        plain,
                        "OBJECT_KEY_TYPE_UNSUPPORTED"));
        for (int type : new int[] {2, 3}) {
            // A valid empty index consists of a footer and MD5(empty)[0..7] TOC checksum.
            byte[] bytes = new byte[28];
            System.arraycopy(Format.hash("MD5", new byte[0]), 0, bytes, 0, 8);
            bytes[8] = 1;
            bytes[11] = 4;
            bytes[12] = (byte) (type == 2 ? 4 : 0);
            bytes[13] = 4;
            bytes[14] = 16;
            bytes[15] = 8;
            checksum(bytes);
            add(result, type, bytes, null);
            bytes = bytes.clone();
            bytes[8] = 2;
            checksum(bytes);
            add(result, type, bytes, "OBJECT_INDEX_UNSUPPORTED");
            bytes = bytes.clone();
            bytes[20] ^= 1;
            add(result, type, bytes, "OBJECT_INDEX_INVALID");
            add(result, type, new byte[27], "OBJECT_INDEX_INVALID");
        }
        EncodedFixture f = EncodedFixture.sample();
        result.add(new Vector(f.key(), f.bytes().length, f.bytes(), null));
        byte[] bytes = f.bytes().clone();
        bytes[bytes.length - 1] ^= 1;
        result.add(new Vector(f.key(), bytes.length, bytes, "OBJECT_BLTE_INVALID"));
        bytes = f.bytes().clone();
        bytes[8] = 14;
        result.add(new Vector(f.key(), bytes.length, bytes, "OBJECT_KEY_MISMATCH"));
        result.add(
                new Vector(
                        headerKey(bytes, f.headerLength()),
                        bytes.length,
                        bytes,
                        "OBJECT_BLTE_UNSUPPORTED"));
        bytes = f.bytes().clone();
        ByteBuffer.wrap(bytes).putInt(12, 5);
        result.add(
                new Vector(
                        headerKey(bytes, f.headerLength()),
                        bytes.length,
                        bytes,
                        "OBJECT_SIZE_MISMATCH"));
        for (int length : new int[] {1, 7, 8}) {
            add(
                    result,
                    4,
                    Arrays.copyOf(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0}, length),
                    "OBJECT_BLTE_INVALID");
        }
        add(result, 4, new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N', 1}, null);
        bytes =
                History.encode(
                        4,
                        "",
                        "us",
                        Instant.EPOCH,
                        "\ufeffé\u0000",
                        new ObjectKey(5, new byte[16]));
        add(result, 6, bytes, null);
        bytes = bytes.clone();
        bytes[2] = 1;
        add(result, 6, bytes, "OBJECT_HISTORY_INVALID");
        bytes = History.encode(1, "wow", "us", Instant.EPOCH, "", new ObjectKey(5, new byte[16]));
        add(result, 6, bytes, null);
        add(result, 6, new byte[33], "OBJECT_HISTORY_INVALID");
        return List.copyOf(result);
    }

    private static void checksum(byte[] bytes) {
        byte[] inner = Arrays.copyOfRange(bytes, 8, 28);
        Arrays.fill(inner, 12, 20, (byte) 0);
        System.arraycopy(Format.hash("MD5", inner), 0, bytes, 20, 8);
    }

    private static ObjectKey headerKey(byte[] bytes, int length) {
        return new ObjectKey(4, Format.hash("MD5", Arrays.copyOf(bytes, length)));
    }

    private static void add(List<Vector> vectors, int type, byte[] bytes, String expected) {
        byte[] identity =
                type == 2 || type == 3
                        ? Arrays.copyOfRange(bytes, Math.max(0, bytes.length - 28), bytes.length)
                        : bytes;
        vectors.add(
                new Vector(
                        new ObjectKey(type, Format.hash("MD5", identity)),
                        bytes.length,
                        bytes,
                        expected));
    }
}
