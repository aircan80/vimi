package com.vibeminer.files;

import com.vibeminer.files.FileTypes.FileReadState;
import com.vibeminer.network.CdnKey;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Pure authenticated chunk-at-a-time BLTE decoding. */
final class BlteChunks {
    static final int MAX_CHUNKS = 65536,
            MAX_CHUNK_BYTES = 16 << 20,
            MAX_HEADER = 12 + 24 * MAX_CHUNKS,
            ENVELOPE = 16;

    record Chunk(
            int ordinal,
            long encodedOffset,
            int encodedSize,
            long decodedOffset,
            int decodedSize,
            byte[] hash) {}

    record Layout(
            boolean headerless,
            int headerLength,
            long encodedLength,
            long decodedLength,
            List<Chunk> chunks) {
        int indexAt(long offset) {
            if (offset < 0 || offset >= decodedLength) {
                throw new IllegalArgumentException("Outside decoded file");
            }
            int low = 0, high = chunks.size() - 1;
            while (low < high) {
                int m = low + (high - low + 1) / 2;
                if (chunks.get(m).decodedOffset <= offset) {
                    low = m;
                } else {
                    high = m - 1;
                }
            }
            return low;
        }
    }

    record Decoded(byte[] bytes, String hole) {}

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final FileReadState state;
        final String code;

        Failure(FileReadState state, String code, String message) {
            super(message);
            this.state = state;
            this.code = code;
        }
    }

    private BlteChunks() {}

    static int headerLength(byte[] prefix, long encodedLength) {
        if (prefix.length != 8
                || prefix[0] != 'B'
                || prefix[1] != 'L'
                || prefix[2] != 'T'
                || prefix[3] != 'E') {
            throw invalid("BLTE prefix is invalid");
        }
        long n = Integer.toUnsignedLong(ByteBuffer.wrap(prefix).getInt(4));
        if (n == 0) {
            return 0;
        }
        if (n > MAX_HEADER) {
            throw limit("BLTE header exceeds chunk limit");
        }
        if (n < 12 || n > encodedLength) {
            throw invalid("BLTE header length is invalid");
        }
        return (int) n;
    }

    static Layout parse(byte[] header, CdnKey ekey, long encodedLength, Long decodedLength) {
        if (header.length < 8) {
            throw invalid("Truncated BLTE header");
        }
        int n = headerLength(Arrays.copyOf(header, 8), encodedLength);
        if (decodedLength != null && decodedLength < 0) {
            throw invalid("Negative decoded length");
        }
        if (n == 0) {
            if (header.length != 8 || encodedLength < 9 || decodedLength == null) {
                throw invalid("Headerless BLTE needs declared lengths");
            }
            if (encodedLength - 8 > (256L << 20) || decodedLength > (256L << 20)) {
                throw limit("Headerless BLTE exceeds limit");
            }
            return new Layout(
                    true,
                    0,
                    encodedLength,
                    decodedLength,
                    List.of(
                            new Chunk(
                                    0,
                                    8,
                                    (int) (encodedLength - 8),
                                    0,
                                    decodedLength.intValue(),
                                    null)));
        }
        if (header.length != n) {
            throw invalid("BLTE header length differs");
        }
        verify(header, ekey.bytes());
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        if ((header[8] & 255) != 15) {
            throw unsupported("BLTE table format is unsupported");
        }
        int count = (header[9] & 255) << 16 | (header[10] & 255) << 8 | header[11] & 255;
        if (count < 1 || count > MAX_CHUNKS || n != 12 + 24 * count) {
            throw invalid("BLTE chunk count is invalid");
        }
        List<Chunk> chunks = new ArrayList<>(count);
        long encoded = n, decoded = 0;
        for (int i = 0; i < count; i++) {
            b.position(12 + 24 * i);
            long en = Integer.toUnsignedLong(b.getInt()), de = Integer.toUnsignedLong(b.getInt());
            if (en == 0) {
                throw invalid("Chunk has no mode");
            }
            if (en > MAX_CHUNK_BYTES || de > MAX_CHUNK_BYTES) {
                throw limit("BLTE chunk exceeds byte limit");
            }
            byte[] hash = new byte[16];
            b.get(hash);
            chunks.add(new Chunk(i, encoded, (int) en, decoded, (int) de, hash));
            encoded += en;
            decoded += de;
        }
        if (encoded != encodedLength || decodedLength != null && decoded != decodedLength) {
            throw invalid("BLTE totals differ from declared lengths");
        }
        return new Layout(false, n, encoded, decoded, List.copyOf(chunks));
    }

    static Long peek(byte[] prefix) {
        if (prefix.length == 0) {
            throw invalid("Chunk has no mode");
        }
        return prefix[0] == 'E' ? envelope(prefix) : null;
    }

    static long envelope(byte[] bytes) {
        if (bytes.length < 2) {
            throw invalid("Truncated encrypted envelope");
        }
        if (bytes[1] != 8) {
            throw unsupported("Unsupported key-name length");
        }
        if (bytes.length < 11) {
            throw invalid("Truncated encrypted envelope");
        }
        if (bytes[10] != 4) {
            throw unsupported("Unsupported IV length");
        }
        if (bytes.length < ENVELOPE) {
            throw invalid("Truncated encrypted envelope");
        }
        if (bytes[15] != 'S') {
            throw unsupported("Unsupported cipher");
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(2);
    }

    static Decoded decode(
            Layout layout, Chunk chunk, byte[] encoded, CdnKey ekey, TactKeySource keys) {
        if (encoded.length != chunk.encodedSize) {
            throw invalid("Encoded chunk length differs");
        }
        if (layout.headerless) {
            MessageDigest md = md5();
            md.update(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0});
            md.update(encoded);
            if (!MessageDigest.isEqual(md.digest(), ekey.bytes())) {
                throw integrity();
            }
        } else {
            verify(encoded, chunk.hash);
        }
        if (encoded[0] == 'E') {
            long name = envelope(encoded);
            if (encoded.length == ENVELOPE) {
                throw invalid("Encrypted chunk has no payload");
            }
            byte[] key = keys.find(name);
            if (key == null) {
                return new Decoded(null, String.format(java.util.Locale.ROOT, "%016X", name));
            }
            if (key.length != 16) {
                throw new IllegalArgumentException("TACT key is16 bytes");
            }
            byte[] nonce = new byte[8];
            System.arraycopy(encoded, 11, nonce, 0, 4);
            for (int i = 0; i < 4; i++) {
                nonce[i] ^= (byte) (chunk.ordinal >>> (8 * i));
            }
            byte[] plain = Arrays.copyOfRange(encoded, ENVELOPE, encoded.length);
            try {
                Salsa20.transform(key, nonce, plain);
                if (plain[0] != 'N' && plain[0] != 'Z') {
                    throw mismatch();
                }
                try {
                    return new Decoded(payload(plain, chunk.decodedSize), null);
                } catch (Failure e) {
                    throw mismatch();
                }
            } finally {
                Arrays.fill(plain, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(key, (byte) 0);
            }
        }
        return new Decoded(payload(encoded, chunk.decodedSize), null);
    }

    private static byte[] payload(byte[] encoded, int size) {
        if (encoded[0] == 'N') {
            if (encoded.length - 1 != size) {
                throw invalid("Raw chunk length differs");
            }
            return Arrays.copyOfRange(encoded, 1, encoded.length);
        }
        if (encoded[0] != 'Z') {
            throw unsupported("Unsupported chunk mode");
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(encoded, 1, encoded.length - 1);
            byte[] result = new byte[size];
            int at = 0;
            while (at < size) {
                int read = inflater.inflate(result, at, size - at);
                if (read == 0) {
                    break;
                }
                at += read;
            }
            byte[] probe = new byte[1];
            int extra = inflater.inflate(probe);
            if (at != size || extra != 0 || !inflater.finished() || inflater.getRemaining() != 0) {
                throw invalid("Invalid, truncated or trailing zlib data");
            }
            return result;
        } catch (DataFormatException e) {
            throw invalid("Invalid zlib data");
        } finally {
            inflater.end();
        }
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static void verify(byte[] bytes, byte[] expected) {
        if (!MessageDigest.isEqual(md5().digest(bytes), expected)) {
            throw integrity();
        }
    }

    static Failure invalid(String message) {
        return new Failure(FileReadState.CORRUPT, "BLTE_INVALID", message);
    }

    static Failure limit(String message) {
        return new Failure(FileReadState.CORRUPT, "BLTE_LIMIT", message);
    }

    static Failure unsupported(String message) {
        return new Failure(FileReadState.UNSUPPORTED, "BLTE_UNSUPPORTED", message);
    }

    static Failure integrity() {
        return new Failure(FileReadState.CORRUPT, "BLTE_INTEGRITY", "BLTE checksum mismatch");
    }

    static Failure mismatch() {
        return new Failure(
                FileReadState.WRONG_KEY,
                "BLTE_KEY_MISMATCH",
                "Known TACT key did not decrypt chunk");
    }
}
