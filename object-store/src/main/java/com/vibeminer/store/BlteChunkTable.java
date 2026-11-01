package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Refusal;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/** Public layer 1 BLTE proofs for unstored ranges; no object-store payload size limit applies. */
public final class BlteChunkTable {
    public static final int PREFIX_BYTES = 8,
            MAX_CHUNKS = 65536,
            MAX_HEADER_BYTES = 12 + 24 * MAX_CHUNKS;

    public record HeaderLength(long length, Refusal refusal) {}

    public record Proof(BlteChunkTable table, Refusal refusal) {}

    private final ObjectKey key;
    private final long declaredLength;
    private final byte[] header;
    private final long[] offsets;

    private BlteChunkTable(ObjectKey key, long declaredLength, byte[] header, long[] offsets) {
        this.key = key;
        this.declaredLength = declaredLength;
        this.header = header;
        this.offsets = offsets;
    }

    public ObjectKey key() {
        return key;
    }

    public long declaredLength() {
        return declaredLength;
    }

    public byte[] header() {
        return header.clone();
    }

    public int headerLength() {
        return header.length;
    }

    public int chunkCount() {
        return offsets.length;
    }

    public long chunkOffset(int index) {
        return offsets[Objects.checkIndex(index, offsets.length)];
    }

    public long chunkLength(int index) {
        Objects.checkIndex(index, offsets.length);
        return Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(12 + 24 * index));
    }

    public int chunkAt(long offset) {
        if (offset < header.length || offset >= declaredLength) {
            throw new IllegalArgumentException("Offset is outside the BLTE chunks");
        }
        int found = Arrays.binarySearch(offsets, offset);
        return found >= 0 ? found : -found - 2;
    }

    public Refusal checkChunk(int index, byte[] bytes) {
        if (bytes.length != chunkLength(index)) {
            return Verification.refusal("OBJECT_SIZE_MISMATCH");
        }
        return MessageDigest.isEqual(
                        Format.hash("MD5", bytes),
                        Arrays.copyOfRange(header, 20 + 24 * index, 36 + 24 * index))
                ? null
                : Verification.refusal("OBJECT_BLTE_INVALID");
    }

    public static HeaderLength readHeaderLength(byte[] prefix, long declaredLength) {
        Objects.requireNonNull(prefix);
        if (prefix.length < PREFIX_BYTES
                || prefix[0] != 'B'
                || prefix[1] != 'L'
                || prefix[2] != 'T'
                || prefix[3] != 'E') {
            return badLength();
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(prefix).getInt(4));
        if (length == 0) {
            return declaredLength >= 9 ? new HeaderLength(0, null) : badLength();
        }
        return length < 36 || length > MAX_HEADER_BYTES || length >= declaredLength
                ? badLength()
                : new HeaderLength(length, null);
    }

    private static HeaderLength badLength() {
        return new HeaderLength(-1, Verification.refusal("OBJECT_BLTE_INVALID"));
    }

    private static Proof refused(String code) {
        return new Proof(null, Verification.refusal(code));
    }

    public static Proof prove(ObjectKey key, long declaredLength, byte[] header) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(header);
        if (key.type() != ObjectKey.ENCODED) {
            return refused("OBJECT_KEY_TYPE_UNSUPPORTED");
        }
        if (key.namespace() != 0) {
            return refused("OBJECT_NAMESPACE_UNSUPPORTED");
        }
        if (declaredLength <= 0) {
            return refused("OBJECT_TOO_LARGE");
        }
        HeaderLength claim = readHeaderLength(header, declaredLength);
        if (claim.refusal() != null) {
            return new Proof(null, claim.refusal());
        }
        if (claim.length() == 0 || header.length != claim.length()) {
            return refused("OBJECT_BLTE_INVALID");
        }
        byte[] owned = header.clone();
        if (!MessageDigest.isEqual(Format.hash("MD5", owned), key.bytes())) {
            return refused("OBJECT_KEY_MISMATCH");
        }
        if (owned[8] != 15) {
            return refused("OBJECT_BLTE_UNSUPPORTED");
        }
        int count =
                (Byte.toUnsignedInt(owned[9]) << 16)
                        | (Byte.toUnsignedInt(owned[10]) << 8)
                        | Byte.toUnsignedInt(owned[11]);
        if (count < 1 || count > MAX_CHUNKS || owned.length != 12 + 24L * count) {
            return refused("OBJECT_BLTE_INVALID");
        }
        long[] offsets = new long[count];
        long total = owned.length;
        for (int i = 0; i < count; i++) {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(owned).getInt(12 + 24 * i));
            if (size == 0) {
                return refused("OBJECT_BLTE_INVALID");
            }
            offsets[i] = total;
            total += size;
        }
        if (total != declaredLength) {
            return refused("OBJECT_SIZE_MISMATCH");
        }
        return new Proof(new BlteChunkTable(key, declaredLength, owned, offsets), null);
    }
}
