package com.vibeminer.formats.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Collision facts of one WMO group file: its vertices (MOVT), triangles (MOVI), the triangle flags
 * (MOPY, or MPY2 with 16-bit flags in newer groups; MOVX 32-bit indices are read too) and the group
 * BSP (MOBN nodes, MOBR face references), read independently of the drawing parser so collision
 * never depends on what is drawn. Only the triangles the client collides with are kept as solid
 * ({@link #collides}); a BSP that fails validation is dropped and queries fall back to every solid
 * triangle.
 *
 * <p>MOPY flags follow wowdev WMO (MOPY): 0x04 DETAIL, 0x08 COLLISION, 0x20 RENDER; a triangle
 * collides when it has COLLISION, or RENDER without DETAIL. MOBN nodes are 16 bytes: flags (axis 0
 * X, 1 Y, 2 Z, 0x4 leaf), the negative and positive child (-1 for none), the face count, the first
 * MOBR entry and the split plane distance. Bounded and allocation-checked; never retains the input.
 */
public final class WmoCollision {
    public static final int DETAIL = 0x04, COLLISION = 0x08, RENDER = 0x20;

    /** At most this many vertices and triangles per group (MOVI and MOBR index with 16 bits). */
    public static final int MAX_VERTICES = 65_536, MAX_TRIANGLES = 131_072;

    /** BSP bounds: nodes, face references and traversal depth. */
    public static final int MAX_NODES = 65_536, MAX_FACE_REFS = 1 << 20, MAX_DEPTH = 128;

    private static final int MVER = 0x4d564552,
            MOGP = 0x4d4f4750,
            MOVT = 0x4d4f5654,
            MOVI = 0x4d4f5649,
            MOPY = 0x4d4f5059,
            MPY2 = 0x4d505932,
            MOVX = 0x4d4f5658,
            MOBN = 0x4d4f424e,
            MOBR = 0x4d4f4252;

    private final float[] positions;
    private final char[] indices;
    private final long[] solid;
    private final int solidCount;
    private final short[] nodeFlags, negative, positive;
    private final char[] faceCount;
    private final int[] faceStart;
    private final float[] planes;
    private final char[] faces;
    private final boolean bsp;
    private final String bspIssue;

    private WmoCollision(
            float[] positions,
            char[] indices,
            long[] solid,
            int solidCount,
            short[] nodeFlags,
            short[] negative,
            short[] positive,
            char[] faceCount,
            int[] faceStart,
            float[] planes,
            char[] faces,
            String bspIssue) {
        this.positions = positions;
        this.indices = indices;
        this.solid = solid;
        this.solidCount = solidCount;
        this.nodeFlags = nodeFlags;
        this.negative = negative;
        this.positive = positive;
        this.faceCount = faceCount;
        this.faceStart = faceStart;
        this.planes = planes;
        this.faces = faces;
        this.bsp = bspIssue == null;
        this.bspIssue = bspIssue;
    }

    /** Whether a triangle with these MOPY flags blocks movement. */
    public static boolean collides(int flags) {
        return (flags & COLLISION) != 0 || (flags & RENDER) != 0 && (flags & DETAIL) == 0;
    }

    /**
     * Reads one group file. Throws {@link IllegalArgumentException} for a malformed file; a group
     * without MOVT, MOVI or MOPY has no collision and reads as empty.
     */
    public static WmoCollision read(byte[] bytes, BooleanSupplier canceled) {
        if (bytes == null || bytes.length < 12 || bytes.length > WmoFormats.MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("WMO group size outside bounds");
        }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] positions = null;
        char[] indices = null;
        // Per-triangle flags: MOPY (flags byte, material byte) or MPY2 (16-bit flags, material).
        int[] flags = null;
        byte[] nodes = null;
        char[] refs = null;
        boolean header = false;
        int chunks = 0;
        while (input.remaining() >= 8) {
            check(canceled);
            if (++chunks > WmoFormats.MAX_CHUNKS) {
                throw new IllegalArgumentException("WMO chunk count");
            }
            int tag = input.getInt(), size = input.getInt();
            if (size < 0 || size > input.remaining()) {
                throw new IllegalArgumentException("WMO chunk interval");
            }
            int end = input.position() + size;
            if (tag == MOGP) {
                if (size < 68) {
                    throw new IllegalArgumentException("MOGP header truncated");
                }
                header = true;
                input.position(input.position() + 68);
                int nested = 0;
                while (end - input.position() >= 8) {
                    check(canceled);
                    if (++nested > WmoFormats.MAX_CHUNKS) {
                        throw new IllegalArgumentException("WMO chunk count");
                    }
                    int inner = input.getInt(), length = input.getInt();
                    if (length < 0 || length > end - input.position()) {
                        throw new IllegalArgumentException("WMO chunk interval");
                    }
                    int innerEnd = input.position() + length;
                    switch (inner) {
                        case MOVT -> positions = floats(input, length, MAX_VERTICES * 3);
                        case MOVI -> indices = shorts(input, length, MAX_TRIANGLES * 3);
                        case MOVX -> {
                            if (length % 4 != 0 || length / 4 > MAX_TRIANGLES * 3) {
                                throw new IllegalArgumentException("MOVX size");
                            }
                            indices = new char[length / 4];
                            for (int i = 0; i < indices.length; i++) {
                                long index = Integer.toUnsignedLong(input.getInt());
                                if (index >= MAX_VERTICES) {
                                    throw new IllegalArgumentException(
                                            "Index outside vertex inventory");
                                }
                                indices[i] = (char) index;
                            }
                        }
                        case MOPY -> {
                            if (length % 2 != 0 || length / 2 > MAX_TRIANGLES) {
                                throw new IllegalArgumentException("MOPY size");
                            }
                            flags = new int[length / 2];
                            for (int t = 0; t < flags.length; t++) {
                                flags[t] = Byte.toUnsignedInt(input.get());
                                input.get();
                            }
                        }
                        case MPY2 -> {
                            if (length % 4 != 0 || length / 4 > MAX_TRIANGLES) {
                                throw new IllegalArgumentException("MPY2 size");
                            }
                            flags = new int[length / 4];
                            for (int t = 0; t < flags.length; t++) {
                                flags[t] = Short.toUnsignedInt(input.getShort());
                                input.getShort();
                            }
                        }
                        case MOBN -> {
                            if (length % 16 != 0 || length / 16 > MAX_NODES) {
                                throw new IllegalArgumentException("MOBN size");
                            }
                            nodes = new byte[length];
                            input.get(nodes);
                        }
                        case MOBR -> refs = shorts(input, length, MAX_FACE_REFS);
                        default -> {}
                    }
                    input.position(innerEnd);
                }
            } else if (tag != MVER && !header && chunks == 1) {
                throw new IllegalArgumentException("Not a WMO group");
            }
            input.position(end);
        }
        if (!header) {
            throw new IllegalArgumentException("WMO group has no MOGP");
        }
        if (positions == null || indices == null || flags == null) {
            return empty();
        }
        if (positions.length % 3 != 0
                || indices.length % 3 != 0
                || flags.length != indices.length / 3) {
            throw new IllegalArgumentException("WMO collision shape disagrees");
        }
        int vertices = positions.length / 3, triangles = indices.length / 3;
        for (char index : indices) {
            if (index >= vertices) {
                throw new IllegalArgumentException("Index outside vertex inventory");
            }
        }
        for (float value : positions) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Nonfinite WMO vertex");
            }
        }
        long[] solid = new long[(triangles + 63) / 64];
        int solidCount = 0;
        for (int t = 0; t < triangles; t++) {
            if (collides(flags[t])) {
                solid[t >>> 6] |= 1L << t;
                solidCount++;
            }
        }
        int count = nodes == null ? 0 : nodes.length / 16;
        short[] nodeFlags = new short[count],
                negative = new short[count],
                positive = new short[count];
        char[] faceCount = new char[count];
        int[] faceStart = new int[count];
        float[] planes = new float[count];
        String issue = count == 0 ? "no MOBN" : refs == null ? "no MOBR" : null;
        if (count > 0) {
            var n = ByteBuffer.wrap(nodes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                nodeFlags[i] = n.getShort();
                negative[i] = n.getShort();
                positive[i] = n.getShort();
                faceCount[i] = n.getChar();
                faceStart[i] = n.getInt();
                planes[i] = n.getFloat();
                if (issue != null) {
                    continue;
                }
                if ((nodeFlags[i] & 4) != 0) {
                    if (faceStart[i] < 0 || (long) faceStart[i] + faceCount[i] > refs.length) {
                        issue = "leaf faces outside MOBR";
                    }
                } else if ((nodeFlags[i] & 3) == 3 || !Float.isFinite(planes[i])) {
                    issue = "split axis or plane";
                } else if (!child(negative[i], i, count) || !child(positive[i], i, count)) {
                    issue = "child outside the nodes";
                }
            }
            if (issue == null && !tree(negative, positive, nodeFlags)) {
                issue = "not a tree";
            }
            if (issue == null) {
                for (char face : refs) {
                    if (face >= triangles) {
                        issue = "face reference outside the triangles";
                        break;
                    }
                }
            }
        }
        boolean valid = issue == null;
        return new WmoCollision(
                positions,
                indices,
                solid,
                solidCount,
                nodeFlags,
                negative,
                positive,
                faceCount,
                faceStart,
                planes,
                valid ? refs : new char[0],
                issue);
    }

    /** A child is -1 (none) or another node. */
    private static boolean child(short value, int node, int count) {
        return value == -1 || value >= 0 && value < count && value != node;
    }

    /** Whether the nodes reached from node 0 form a tree no deeper than {@link #MAX_DEPTH}. */
    private static boolean tree(short[] negative, short[] positive, short[] flags) {
        var seen = new boolean[flags.length];
        int[] stack = new int[flags.length + 1], depth = new int[flags.length + 1];
        int top = 0;
        stack[top] = 0;
        depth[top++] = 1;
        while (top > 0) {
            int node = stack[--top], level = depth[top];
            if (seen[node] || level > MAX_DEPTH) {
                return false;
            }
            seen[node] = true;
            if ((flags[node] & 4) != 0) {
                continue;
            }
            for (short next : new short[] {negative[node], positive[node]}) {
                if (next != -1) {
                    if (top >= stack.length) {
                        return false;
                    }
                    stack[top] = next;
                    depth[top++] = level + 1;
                }
            }
        }
        return true;
    }

    private static WmoCollision empty() {
        return new WmoCollision(
                new float[0],
                new char[0],
                new long[0],
                0,
                new short[0],
                new short[0],
                new short[0],
                new char[0],
                new int[0],
                new float[0],
                new char[0],
                "no mesh");
    }

    public int vertices() {
        return positions.length / 3;
    }

    public int triangles() {
        return indices.length / 3;
    }

    /** Triangles that block movement. */
    public int solidTriangles() {
        return solidCount;
    }

    /** Whether a valid BSP narrows queries; false means every solid triangle is offered. */
    public boolean hasBsp() {
        return bsp;
    }

    /** Why the BSP is not used ("no MOBN", "not a tree" and so on), or null when it is. */
    public String bspIssue() {
        return bspIssue;
    }

    public boolean solid(int triangle) {
        return (solid[triangle >>> 6] >>> triangle & 1) != 0;
    }

    /** Vertex {@code index} of triangle {@code triangle} (0..2). */
    public int corner(int triangle, int index) {
        return indices[triangle * 3 + index];
    }

    /** Local-space coordinate {@code axis} (0 X, 1 Y, 2 Z) of vertex {@code vertex}. */
    public float position(int vertex, int axis) {
        return positions[vertex * 3 + axis];
    }

    /** Approximate retained bytes, for collision budgets. */
    public long retainedBytes() {
        return 96L
                + positions.length * 4L
                + indices.length * 2L
                + solid.length * 8L
                + nodeFlags.length * 16L
                + faces.length * 2L;
    }

    /**
     * Offers every solid triangle that may touch the local-space box, each once at most per leaf (a
     * triangle in two leaves may be offered twice). Without a valid BSP every solid triangle is
     * offered.
     */
    public void query(
            float x0, float y0, float z0, float x1, float y1, float z1, IntConsumer triangle) {
        if (!bsp) {
            for (int t = 0, n = triangles(); t < n; t++) {
                if (solid(t)) {
                    triangle.accept(t);
                }
            }
            return;
        }
        float[] low = {x0, y0, z0}, high = {x1, y1, z1};
        int[] stack = new int[MAX_DEPTH * 2];
        int top = 0;
        stack[top++] = 0;
        int visits = 0;
        while (top > 0) {
            int node = stack[--top];
            if (++visits > nodeFlags.length) {
                break;
            }
            int flags = nodeFlags[node];
            if ((flags & 4) != 0) {
                int start = faceStart[node], end = start + faceCount[node];
                for (int i = start; i < end; i++) {
                    if (solid(faces[i])) {
                        triangle.accept(faces[i]);
                    }
                }
                continue;
            }
            int axis = flags & 3;
            float plane = planes[node];
            if (low[axis] <= plane && negative[node] != -1 && top < stack.length) {
                stack[top++] = negative[node];
            }
            if (high[axis] >= plane && positive[node] != -1 && top < stack.length) {
                stack[top++] = positive[node];
            }
        }
    }

    private static float[] floats(ByteBuffer input, int bytes, int maximum) {
        if (bytes % 4 != 0 || bytes / 4 > maximum) {
            throw new IllegalArgumentException("WMO float array size");
        }
        var out = new float[bytes / 4];
        for (int i = 0; i < out.length; i++) {
            out[i] = input.getFloat();
        }
        return out;
    }

    private static char[] shorts(ByteBuffer input, int bytes, int maximum) {
        if (bytes % 2 != 0 || bytes / 2 > maximum) {
            throw new IllegalArgumentException("WMO index array size");
        }
        var out = new char[bytes / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = input.getChar();
        }
        return out;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
