package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class BlteChunkTableTest {
    private static byte[] header(byte[]... chunks) {
        byte[] bytes = new byte[12 + chunks.length * 24];
        ByteBuffer b = ByteBuffer.wrap(bytes);
        b.put(new byte[] {'B', 'L', 'T', 'E'})
                .putInt(bytes.length)
                .put((byte) 15)
                .put((byte) (chunks.length >>> 16))
                .put((byte) (chunks.length >>> 8))
                .put((byte) chunks.length);
        for (byte[] chunk : chunks) {
            b.putInt(chunk.length).putInt(chunk.length).put(Format.hash("MD5", chunk));
        }
        return bytes;
    }

    private static ObjectKey key(byte[] header) {
        return new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", header));
    }

    @Test
    void headerAndIndependentChunksAreProvenWithoutAnObjectStore() {
        byte[] a = {'N', 1, 2}, b = {'N', 3, 4, 5};
        byte[] header = header(a, b);
        long length = header.length + a.length + b.length;
        var proof = BlteChunkTable.prove(key(header), length, header);
        assertNull(proof.refusal());
        var table = proof.table();
        assertEquals(2, table.chunkCount());
        assertEquals(header.length, table.chunkOffset(0));
        assertEquals(3, table.chunkLength(0));
        assertEquals(1, table.chunkAt(length - 1));
        assertEquals(1, table.chunkAt(header.length + 3L));
        assertNull(table.checkChunk(0, a));
        assertEquals("OBJECT_SIZE_MISMATCH", table.checkChunk(1, a).code());
        b[0] ^= 1;
        assertEquals("OBJECT_BLTE_INVALID", table.checkChunk(1, b).code());
        header[0] = 0;
        byte[] copy = table.header();
        copy[0] = 0;
        assertEquals('B', table.header()[0]);
        assertThrows(IllegalArgumentException.class, () -> table.chunkAt(0));
        assertThrows(IllegalArgumentException.class, () -> table.chunkAt(length));
    }

    @Test
    void publicProofHasNoStoredPayloadLimit() {
        byte[] header = header(new byte[] {'N'});
        ByteBuffer.wrap(header).putInt(12, 1_000_000_000);
        var proof = BlteChunkTable.prove(key(header), header.length + 1_000_000_000L, header);
        assertNull(proof.refusal());
        assertEquals(1_000_000_000L, proof.table().chunkLength(0));
    }

    @Test
    void malformedPrefixesAndHeaderlessTablesAreRefused() {
        for (byte[] prefix :
                new byte[][] {
                    new byte[7], new byte[8], new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 1}
                }) {
            assertNotNull(BlteChunkTable.readHeaderLength(prefix, 100).refusal());
        }
        byte[] prefix = {'B', 'L', 'T', 'E', 0, 0, 0, 0};
        assertEquals(0, BlteChunkTable.readHeaderLength(prefix, 9).length());
        assertNotNull(BlteChunkTable.readHeaderLength(prefix, 8).refusal());
        assertEquals(
                "OBJECT_BLTE_INVALID",
                BlteChunkTable.prove(key(prefix), 9, prefix).refusal().code());
    }

    @Test
    void keyKindNamespaceSizesAndTableVersionHaveReferenceRefusalPrecedence() {
        byte[] header = header(new byte[] {'N'});
        ObjectKey key = key(header);
        assertEquals(
                "OBJECT_KEY_TYPE_UNSUPPORTED",
                BlteChunkTable.prove(new ObjectKey(ObjectKey.CONFIG, key.bytes()), 37, header)
                        .refusal()
                        .code());
        assertEquals(
                "OBJECT_NAMESPACE_UNSUPPORTED",
                BlteChunkTable.prove(new ObjectKey(ObjectKey.ENCODED, 1, key.bytes()), 37, header)
                        .refusal()
                        .code());
        assertEquals("OBJECT_TOO_LARGE", BlteChunkTable.prove(key, 0, header).refusal().code());
        assertEquals(
                "OBJECT_SIZE_MISMATCH", BlteChunkTable.prove(key, 38, header).refusal().code());
        byte[] changed = header.clone();
        changed[8] = 0;
        assertEquals(
                "OBJECT_KEY_MISMATCH", BlteChunkTable.prove(key, 37, changed).refusal().code());
        assertEquals(
                "OBJECT_BLTE_UNSUPPORTED",
                BlteChunkTable.prove(key(changed), 37, changed).refusal().code());
        ByteBuffer.wrap(header).putInt(12, 0);
        assertEquals(
                "OBJECT_BLTE_INVALID",
                BlteChunkTable.prove(key(header), 37, header).refusal().code());
    }

    @Test
    void publicProofAgreesWithIncrementalWholeObjectVerification() {
        byte[] chunk = {'N', 1, 2, 3},
                header = header(chunk),
                whole = Arrays.copyOf(header, header.length + chunk.length);
        System.arraycopy(chunk, 0, whole, header.length, chunk.length);
        assertNull(Verification.CDN.check(key(header), whole, whole.length));
        assertNull(
                BlteChunkTable.prove(key(header), whole.length, header)
                        .table()
                        .checkChunk(0, chunk));
        whole[whole.length - 1] ^= 1;
        assertEquals(
                "OBJECT_BLTE_INVALID",
                Verification.CDN.check(key(header), whole, whole.length).code());
        assertArrayEquals(
                header, BlteChunkTable.prove(key(header), whole.length, header).table().header());
    }
}
