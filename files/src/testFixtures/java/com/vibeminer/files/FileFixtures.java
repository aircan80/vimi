package com.vibeminer.files;

import com.vibeminer.network.CdnKey;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/** Synthetic authenticated BLTE objects for Files and Formats tests. */
public final class FileFixtures {
    private FileFixtures() {}

    public record Chunk(byte[] encoded, int decodedSize) {
        public Chunk {
            encoded = encoded.clone();
        }

        @Override
        public byte[] encoded() {
            return encoded.clone();
        }
    }

    public record Encoded(CdnKey key, byte[] bytes) {
        public Encoded {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public static byte[] md5(byte[] bytes) {
        return BlteChunks.md5().digest(bytes);
    }

    public static Chunk raw(byte[] decoded) {
        byte[] encoded = new byte[decoded.length + 1];
        encoded[0] = 'N';
        System.arraycopy(decoded, 0, encoded, 1, decoded.length);
        return new Chunk(encoded, decoded.length);
    }

    public static Chunk zlib(byte[] decoded) {
        Deflater d = new Deflater();
        try {
            d.setInput(decoded);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write('Z');
            byte[] buffer = new byte[1024];
            while (!d.finished()) {
                int n = d.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return new Chunk(out.toByteArray(), decoded.length);
        } finally {
            d.end();
        }
    }

    public static Chunk encrypted(long name, byte[] key, Chunk inner, int ordinal) {
        byte[] payload = inner.encoded();
        byte[] nonce = new byte[] {1, 2, 3, 4, 0, 0, 0, 0};
        for (int i = 0; i < 4; i++) {
            nonce[i] ^= (byte) (ordinal >>> (8 * i));
        }
        Salsa20.transform(key, nonce, payload);
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'E')
                .put((byte) 8)
                .putLong(name)
                .put((byte) 4)
                .put(new byte[] {1, 2, 3, 4})
                .put((byte) 'S')
                .put(payload);
        return new Chunk(b.array(), inner.decodedSize());
    }

    public static Encoded chunked(List<Chunk> chunks) {
        int header = 12 + 24 * chunks.size(), total = header;
        for (var c : chunks) {
            total += c.encoded.length;
        }
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put(new byte[] {'B', 'L', 'T', 'E'})
                .putInt(header)
                .put((byte) 15)
                .put((byte) (chunks.size() >>> 16))
                .put((byte) (chunks.size() >>> 8))
                .put((byte) chunks.size());
        for (var c : chunks) {
            b.putInt(c.encoded.length).putInt(c.decodedSize).put(md5(c.encoded));
        }
        byte[] key = md5(Arrays.copyOf(b.array(), header));
        for (var c : chunks) {
            b.put(c.encoded);
        }
        return new Encoded(new CdnKey(key), b.array());
    }

    public static Encoded headerless(Chunk chunk) {
        byte[] bytes = new byte[8 + chunk.encoded.length];
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E'}, 0, bytes, 0, 4);
        System.arraycopy(chunk.encoded, 0, bytes, 8, chunk.encoded.length);
        return new Encoded(new CdnKey(md5(bytes)), bytes);
    }
}
