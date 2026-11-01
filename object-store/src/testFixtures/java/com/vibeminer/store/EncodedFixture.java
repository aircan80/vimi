package com.vibeminer.store;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Deterministic served BLTE bytes; chunk modes are deliberately opaque to the store. */
public record EncodedFixture(ObjectKey key, byte[] bytes, int headerLength, int firstChunkEnd) {
    public static EncodedFixture chunks(byte[]... chunks) {
        int headerLength = 12 + 24 * chunks.length;
        int length = headerLength;
        for (byte[] chunk : chunks) {
            length = Math.addExact(length, chunk.length);
        }
        byte[] bytes = new byte[length];
        ByteBuffer header = ByteBuffer.wrap(bytes);
        header.put(new byte[] {'B', 'L', 'T', 'E'})
                .putInt(headerLength)
                .put((byte) 15)
                .put((byte) (chunks.length >>> 16))
                .put((byte) (chunks.length >>> 8))
                .put((byte) chunks.length);
        for (byte[] chunk : chunks) {
            header.putInt(chunk.length).putInt(0).put(Format.hash("MD5", chunk));
        }
        int at = headerLength;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, bytes, at, chunk.length);
            at += chunk.length;
        }
        return new EncodedFixture(
                new ObjectKey(4, Format.hash("MD5", Arrays.copyOf(bytes, headerLength))),
                bytes,
                headerLength,
                headerLength + chunks[0].length);
    }

    public static EncodedFixture sample() {
        return chunks(new byte[] {'N', 1, 2, 3}, new byte[] {'E', 4, 5, 6, 7});
    }
}
