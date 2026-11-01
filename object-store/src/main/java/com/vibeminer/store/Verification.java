package com.vibeminer.store;

import java.nio.*;
import java.security.MessageDigest;
import java.util.*;
import com.vibeminer.store.StoreTypes.*;

/** Original format-1 identity verification, independent of SQLite, packs and acquisition. */
public final class Verification implements ObjectVerifier {
    public static final Verification CDN = new Verification(Kind.CDN);
    public static final Verification GENERATED = new Verification(Kind.GENERATED);
    private final Kind kind;
    private Verification(Kind kind) { this.kind = kind; }
    static long limit(int type) {
        return switch (type) { case 1, 5 -> 16L << 20; case 2, 3, 7 -> Format.WHOLE_LIMIT;
            case 4 -> Format.MAX_PAYLOAD; case 6 -> 1136; default -> 0; };
    }
    private Refusal bounds(ObjectKey key, long length) {
        if (!Format.known(key.type(), kind)) return refusal("OBJECT_KEY_TYPE_UNSUPPORTED");
        if (key.namespace() != 0) return refusal("OBJECT_NAMESPACE_UNSUPPORTED");
        if (length <= 0 || length > limit(key.type())) return refusal("OBJECT_TOO_LARGE");
        return null;
    }
    static Refusal refusal(String code) { return new Refusal(code, code.substring(7).replace('_', ' ').toLowerCase(Locale.ROOT)); }
    @Override public Refusal check(ObjectKey key, byte[] bytes, long declaredLength) {
        Refusal bound = bounds(key, declaredLength); if (bound != null) return bound;
        if (bytes.length != declaredLength) return refusal("OBJECT_SIZE_MISMATCH");
        if (declaredLength > Format.WHOLE_LIMIT) return refusal("OBJECT_TOO_LARGE");
        ObjectCheck check = begin(key, declaredLength); Refusal r = check.append(bytes); return r == null ? check.complete() : r;
    }
    @Override public ObjectCheck begin(ObjectKey key, long declaredLength) { return new Check(key, declaredLength, bounds(key, declaredLength)); }
    private static final class Invalid extends RuntimeException {
        final String code;
        Invalid(String code) { this.code = code; }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new Invalid(code); }
    private static final class Check implements ObjectCheck {
        private final ObjectKey key;
        private final long declared;
        private long position, proven;
        private Refusal refused;
        private final MessageDigest md5 = Format.digest("MD5");
        private byte[] collected, header = new byte[8];
        private int headerFill, headerLength = -1, chunk;
        private long remaining;
        private long[] chunkSizes;
        private byte[][] chunkHashes;
        private boolean completed;
        Check(ObjectKey key, long declared, Refusal refused) {
            this.key = key; this.declared = declared; this.refused = refused;
            if (refused == null && (key.type() == 2 || key.type() == 3 || key.type() == 6)) collected = new byte[(int)declared];
        }
        @Override public long provenLength() { return proven; }
        @Override public Refusal append(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (refused != null) return refused;
            if (completed) throw new IllegalStateException("Verification has completed");
            try {
                require(length <= declared - position, "OBJECT_SIZE_MISMATCH");
                if (collected != null) System.arraycopy(bytes, offset, collected, (int)position, length);
                else if (key.type() == 4) blte(bytes, offset, length);
                else if (key.type() != 7) md5.update(bytes, offset, length);
                position += length; return null;
            } catch (Invalid e) { return refused = refusal(e.code); }
        }
        private void blte(byte[] data, int start, int length) {
            int end = start + length;
            while (start < end) {
                if (headerLength < 0 || headerFill < header.length) {
                    int n = Math.min(header.length - headerFill, end - start);
                    System.arraycopy(data, start, header, headerFill, n); start += n; headerFill += n;
                    if (headerFill < header.length) continue;
                    if (headerLength < 0) {
                        require(Arrays.equals(Arrays.copyOf(header, 4), new byte[]{'B','L','T','E'}), "OBJECT_BLTE_INVALID");
                        long h = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(4));
                        if (h == 0) {
                            require(declared >= 9, "OBJECT_BLTE_INVALID"); headerLength = 0; md5.update(header);
                        } else {
                            require(h >= 36 && h <= 12 + 24L * 65536 && h < declared, "OBJECT_BLTE_INVALID");
                            headerLength = (int)h; header = Arrays.copyOf(header, headerLength);
                        }
                    } else proveHeader();
                } else if (headerLength == 0) { md5.update(data, start, end - start); return; }
                else {
                    require(chunk < chunkSizes.length, "OBJECT_BLTE_INVALID");
                    int n = (int)Math.min(remaining, end - start); md5.update(data, start, n); remaining -= n; start += n;
                    if (remaining == 0) {
                        require(MessageDigest.isEqual(md5.digest(), chunkHashes[chunk]), "OBJECT_BLTE_INVALID");
                        proven += chunkSizes[chunk++]; if (chunk < chunkSizes.length) remaining = chunkSizes[chunk];
                    }
                }
            }
        }
        private void proveHeader() {
            require(MessageDigest.isEqual(Format.hash("MD5", header), key.bytes()), "OBJECT_KEY_MISMATCH");
            require(header[8] == 15, "OBJECT_BLTE_UNSUPPORTED");
            int count = (Byte.toUnsignedInt(header[9]) << 16) | (Byte.toUnsignedInt(header[10]) << 8) | Byte.toUnsignedInt(header[11]);
            require(count >= 1 && count <= 65536 && headerLength == 12 + 24 * count, "OBJECT_BLTE_INVALID");
            chunkSizes = new long[count]; chunkHashes = new byte[count][]; long total = headerLength;
            for (int i = 0; i < count; i++) {
                chunkSizes[i] = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(12 + i * 24));
                require(chunkSizes[i] >= 1, "OBJECT_BLTE_INVALID"); total += chunkSizes[i];
                chunkHashes[i] = Arrays.copyOfRange(header, 20 + i * 24, 36 + i * 24);
            }
            require(total == declared, "OBJECT_SIZE_MISMATCH"); proven = headerLength; remaining = chunkSizes[0];
        }
        @Override public Refusal complete() {
            if (refused != null) return refused;
            if (completed) return null;
            try {
                require(position == declared, "OBJECT_SIZE_MISMATCH");
                switch (key.type()) {
                    case 1, 5 -> require(MessageDigest.isEqual(md5.digest(), key.bytes()), "OBJECT_KEY_MISMATCH");
                    case 2, 3 -> index();
                    case 4 -> {
                        require(headerLength >= 0 && (headerLength == 0 || headerFill == header.length), "OBJECT_BLTE_INVALID");
                        if (headerLength == 0) require(MessageDigest.isEqual(md5.digest(), key.bytes()), "OBJECT_KEY_MISMATCH");
                        else require(chunk == chunkSizes.length, "OBJECT_BLTE_INVALID");
                    }
                    case 6 -> {
                        require(MessageDigest.isEqual(Format.hash("MD5", collected), key.bytes()), "OBJECT_KEY_MISMATCH");
                        require(History.parse(collected, key) != null, "OBJECT_HISTORY_INVALID");
                    }
                    default -> { }
                }
                completed = true; return null;
            } catch (Invalid e) { return refused = refusal(e.code); }
        }
        private void index() {
            require(collected.length >= 28, "OBJECT_INDEX_INVALID");
            byte[] footer = Arrays.copyOfRange(collected, collected.length - 28, collected.length);
            require(MessageDigest.isEqual(Format.hash("MD5", footer), key.bytes()), "OBJECT_KEY_MISMATCH");
            byte[] inner = Arrays.copyOfRange(footer, 8, 28); Arrays.fill(inner, 12, 20, (byte)0);
            require(Arrays.equals(Arrays.copyOf(Format.hash("MD5", inner), 8), Arrays.copyOfRange(footer, 20, 28)), "OBJECT_INDEX_INVALID");
            int width = key.type() == 2 ? 4 : 0;
            require(footer[8] == 1 && footer[9] == 0 && footer[10] == 0 && footer[11] == 4 && footer[12] == width && footer[13] == 4 && footer[14] == 16 && footer[15] == 8, "OBJECT_INDEX_UNSUPPORTED");
            long entries = Integer.toUnsignedLong(Format.le(footer).getInt(16)), perPage = 4096 / (20 + width);
            long pages = (entries + perPage - 1) / perPage;
            require(pages * 4096 + pages * 24 + 28 == collected.length, "OBJECT_INDEX_INVALID");
            byte[] toc = Arrays.copyOfRange(collected, (int)(pages * 4096), collected.length - 28);
            require(Arrays.equals(Arrays.copyOf(Format.hash("MD5", toc), 8), Arrays.copyOf(footer, 8)), "OBJECT_INDEX_INVALID");
            for (int i = 0; i < pages; i++) require(Arrays.equals(Arrays.copyOf(Format.hash("MD5", Arrays.copyOfRange(collected, i * 4096, (i + 1) * 4096)), 8), Arrays.copyOfRange(toc, (int)pages * 16 + i * 8, (int)pages * 16 + (i + 1) * 8)), "OBJECT_INDEX_INVALID");
        }
    }
}
