package com.vibeminer.formats.models;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The collision mesh an M2 model records for the client's movement collision: collision vertices
 * and 16-bit triangle indices (the MD20 header's collisionTriangles at 0xD8 and collisionVertices
 * at 0xE0, wowdev M2), in model space. Read from the raw file without the drawing reader, bounded,
 * and never retaining the input. A model without a collision mesh reads as empty: it does not block
 * movement.
 */
public final class M2Collision {
    public static final int MAX_VERTICES = 65_536, MAX_INDICES = 3 * 65_536;
    private static final int TRIANGLES_AT = 0xD8, VERTICES_AT = 0xE0, HEADER_BYTES = 0xF0;

    private static final M2Collision EMPTY = new M2Collision(new float[0], new char[0]);

    private final float[] positions;
    private final char[] indices;

    private M2Collision(float[] positions, char[] indices) {
        this.positions = positions;
        this.indices = indices;
    }

    /**
     * Reads the collision mesh of a raw M2 file (chunked MD21 or bare MD20). Throws {@link
     * IllegalArgumentException} for a malformed header or arrays.
     */
    public static M2Collision read(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("M2 file too small");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int base = -1, length = 0;
        if (input.getInt(0) == tag("MD20")) {
            base = 0;
            length = bytes.length;
        } else {
            for (int at = 0, chunks = 0; at + 8 <= bytes.length && chunks < 1024; chunks++) {
                int tag = input.getInt(at), size = input.getInt(at + 4);
                if (size < 0 || size > bytes.length - at - 8) {
                    throw new IllegalArgumentException("M2 chunk interval");
                }
                if (tag == tag("MD21")) {
                    base = at + 8;
                    length = size;
                    break;
                }
                at += 8 + size;
            }
        }
        if (base < 0 || length < HEADER_BYTES || input.getInt(base) != tag("MD20")) {
            throw new IllegalArgumentException("M2 has no MD20 header");
        }
        long indexCount = u32(input, base + TRIANGLES_AT),
                indexOffset = u32(input, base + TRIANGLES_AT + 4);
        long vertexCount = u32(input, base + VERTICES_AT),
                vertexOffset = u32(input, base + VERTICES_AT + 4);
        if (indexCount == 0 || vertexCount == 0) {
            return EMPTY;
        }
        if (indexCount > MAX_INDICES || indexCount % 3 != 0 || vertexCount > MAX_VERTICES) {
            throw new IllegalArgumentException("M2 collision counts outside bounds");
        }
        if (indexOffset + indexCount * 2 > length || vertexOffset + vertexCount * 12 > length) {
            throw new IllegalArgumentException("M2 collision arrays outside the model");
        }
        var indices = new char[(int) indexCount];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = input.getChar(base + (int) indexOffset + i * 2);
            if (indices[i] >= vertexCount) {
                throw new IllegalArgumentException("M2 collision index outside its vertices");
            }
        }
        var positions = new float[(int) vertexCount * 3];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = input.getFloat(base + (int) vertexOffset + i * 4);
            if (!Float.isFinite(positions[i])) {
                throw new IllegalArgumentException("Nonfinite M2 collision vertex");
            }
        }
        return new M2Collision(positions, indices);
    }

    public int vertices() {
        return positions.length / 3;
    }

    public int triangles() {
        return indices.length / 3;
    }

    /** Vertex {@code index} (0..2) of triangle {@code triangle}. */
    public int corner(int triangle, int index) {
        return indices[triangle * 3 + index];
    }

    /** Model-space coordinate {@code axis} (0 X, 1 Y, 2 Z) of vertex {@code vertex}. */
    public float position(int vertex, int axis) {
        return positions[vertex * 3 + axis];
    }

    /** Approximate retained bytes, for collision budgets. */
    public long retainedBytes() {
        return 48L + positions.length * 4L + indices.length * 2L;
    }

    private static long u32(ByteBuffer input, int at) {
        return Integer.toUnsignedLong(input.getInt(at));
    }

    private static int tag(String name) {
        return name.charAt(0) | name.charAt(1) << 8 | name.charAt(2) << 16 | name.charAt(3) << 24;
    }
}
