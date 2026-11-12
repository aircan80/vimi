package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class M2CollisionTest {
    /** An MD20 body with a one-triangle collision mesh after its header. */
    static byte[] md20(int indexCount, int vertexCount, int index2) {
        int indexAt = 0x100, vertexAt = 0x120;
        var b = ByteBuffer.allocate(0x160).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, "MD20".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(0xD8, indexCount)
                .putInt(0xDC, indexAt)
                .putInt(0xE0, vertexCount)
                .putInt(0xE4, vertexAt);
        b.putShort(indexAt, (short) 0)
                .putShort(indexAt + 2, (short) 1)
                .putShort(indexAt + 4, (short) index2);
        float[] v = {0, 0, 0, 2, 0, 0, 0, 3, 1};
        for (int i = 0; i < v.length; i++) {
            b.putFloat(vertexAt + i * 4, v[i]);
        }
        return b.array();
    }

    static byte[] md21(byte[] body) {
        var b = ByteBuffer.allocate(8 + body.length + 12).order(ByteOrder.LITTLE_ENDIAN);
        b.put("MD21".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putInt(body.length)
                .put(body);
        b.put("SFID".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(4).putInt(7);
        return b.array();
    }

    @Test
    void readsTheCollisionMeshOfChunkedAndBareModels() {
        for (var bytes : new byte[][] {md20(3, 3, 2), md21(md20(3, 3, 2))}) {
            var c = M2Collision.read(bytes);
            assertEquals(1, c.triangles());
            assertEquals(3, c.vertices());
            assertEquals(2, c.corner(0, 2));
            assertEquals(3f, c.position(2, 1));
            assertEquals(1f, c.position(2, 2));
        }
    }

    @Test
    void aModelWithoutAMeshIsEmpty() {
        assertEquals(0, M2Collision.read(md21(md20(0, 0, 2))).triangles());
    }

    @Test
    void malformedMeshesAreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> M2Collision.read(md20(3, 3, 7)),
                "index past vertices");
        assertThrows(
                IllegalArgumentException.class,
                () -> M2Collision.read(md20(4, 3, 2)),
                "not whole triangles");
        assertThrows(
                IllegalArgumentException.class,
                () -> M2Collision.read(md20(3, 300, 2)),
                "past the model");
        assertThrows(
                IllegalArgumentException.class, () -> M2Collision.read(new byte[16]), "no MD20");
    }
}
