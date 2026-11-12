package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class WmoCollisionTest {
    static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] chunk(String tag, byte[] payload) {
        var out = buffer(8 + payload.length);
        // File tags are stored reversed ("PGOM" for MOGP).
        out.put(
                new StringBuilder(tag)
                        .reverse()
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.putInt(payload.length).put(payload);
        return out.array();
    }

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Four unit triangles side by side along X (triangle t spans x = 2t..2t+1), flagged in order:
     * RENDER (solid), RENDER|DETAIL (not solid), COLLISION (solid, collision only), none.
     */
    static byte[] group(byte[] mobn, byte[] mobr) {
        var positions = buffer(4 * 3 * 12);
        var indices = buffer(4 * 3 * 2);
        for (int t = 0; t < 4; t++) {
            positions.putFloat(2 * t).putFloat(0).putFloat(0);
            positions.putFloat(2 * t + 1).putFloat(0).putFloat(0);
            positions.putFloat(2 * t).putFloat(1).putFloat(0);
            indices.putShort((short) (t * 3))
                    .putShort((short) (t * 3 + 1))
                    .putShort((short) (t * 3 + 2));
        }
        int[] flags = {
            WmoCollision.RENDER,
            WmoCollision.RENDER | WmoCollision.DETAIL,
            WmoCollision.COLLISION,
            0
        };
        var mopy = buffer(8);
        for (int f : flags) {
            mopy.put((byte) f).put((byte) (f == WmoCollision.COLLISION ? -1 : 0));
        }
        var parts =
                new ArrayList<byte[]>(
                        List.of(
                                new byte[68],
                                chunk("MOVT", positions.array()),
                                chunk("MOVI", indices.array()),
                                chunk("MOPY", mopy.array())));
        if (mobn != null) {
            parts.add(chunk("MOBN", mobn));
        }
        if (mobr != null) {
            parts.add(chunk("MOBR", mobr));
        }
        return join(
                chunk("MVER", buffer(4).putInt(17).array()),
                chunk("MOGP", join(parts.toArray(byte[][]::new))));
    }

    static byte[] node(int flags, int negative, int positive, int faces, int first, float plane) {
        return buffer(16)
                .putShort((short) flags)
                .putShort((short) negative)
                .putShort((short) positive)
                .putShort((short) faces)
                .putInt(first)
                .putFloat(plane)
                .array();
    }

    /** Root split on X at 3.5: triangles 0 and 1 on the negative side, 2 and 3 on the positive. */
    static byte[] split() {
        return join(node(0, 1, 2, 0, 0, 3.5f), node(4, -1, -1, 2, 0, 0), node(4, -1, -1, 2, 2, 0));
    }

    static byte[] refs(int... faces) {
        var out = buffer(faces.length * 2);
        for (int f : faces) {
            out.putShort((short) f);
        }
        return out.array();
    }

    static TreeSet<Integer> query(WmoCollision c, float x0, float x1) {
        var out = new TreeSet<Integer>();
        c.query(x0, -1, -1, x1, 2, 1, out::add);
        return out;
    }

    @Test
    void theClientCollisionRuleDecidesWhatIsSolid() {
        assertTrue(WmoCollision.collides(WmoCollision.RENDER));
        assertFalse(WmoCollision.collides(WmoCollision.RENDER | WmoCollision.DETAIL));
        assertTrue(WmoCollision.collides(WmoCollision.COLLISION));
        assertTrue(WmoCollision.collides(WmoCollision.COLLISION | WmoCollision.DETAIL));
        assertFalse(WmoCollision.collides(0));
        var c = WmoCollision.read(group(null, null), () -> false);
        assertEquals(4, c.triangles());
        assertEquals(12, c.vertices());
        assertEquals(2, c.solidTriangles());
        assertTrue(c.solid(0) && !c.solid(1) && c.solid(2) && !c.solid(3));
        assertEquals(6f, c.position(c.corner(3, 0), 0));
        assertFalse(c.hasBsp());
        assertEquals(
                new TreeSet<>(List.of(0, 2)), query(c, -10, 10), "no BSP: every solid triangle");
    }

    @Test
    void theBspNarrowsQueriesToTheLeavesTheBoxTouches() {
        var c = WmoCollision.read(group(split(), refs(0, 1, 2, 3)), () -> false);
        assertTrue(c.hasBsp());
        assertEquals(new TreeSet<>(List.of(0)), query(c, 0, 1));
        assertEquals(new TreeSet<>(List.of(2)), query(c, 4, 7));
        assertEquals(
                new TreeSet<>(List.of(0, 2)),
                query(c, 3, 4),
                "a box across the plane takes both sides");
        assertTrue(c.retainedBytes() > 0);
    }

    @Test
    void anInvalidBspFallsBackToEverySolidTriangle() {
        // A face reference past the triangles, a cycle, and a leaf range past MOBR.
        for (var bad :
                List.of(
                        new byte[][] {split(), refs(0, 1, 2, 9)},
                        new byte[][] {
                            join(node(0, 1, 1, 0, 0, 3.5f), node(4, -1, -1, 2, 0, 0)),
                            refs(0, 1, 2, 3)
                        },
                        new byte[][] {split(), refs(0, 1, 2)})) {
            var c = WmoCollision.read(group(bad[0], bad[1]), () -> false);
            assertFalse(c.hasBsp());
            assertEquals(new TreeSet<>(List.of(0, 2)), query(c, 0, 1));
        }
    }

    @Test
    void newerGroupsWithMpy2FlagsAndMovxIndicesAreRead() {
        var positions =
                buffer(36)
                        .putFloat(0)
                        .putFloat(0)
                        .putFloat(0)
                        .putFloat(1)
                        .putFloat(0)
                        .putFloat(0)
                        .putFloat(0)
                        .putFloat(1)
                        .putFloat(0);
        var movx = buffer(24).putInt(0).putInt(1).putInt(2).putInt(2).putInt(1).putInt(0);
        // 16-bit flags: COLLISION with a high bit set, then DETAIL|RENDER (not solid).
        var mpy2 =
                buffer(8)
                        .putShort((short) (0x100 | WmoCollision.COLLISION))
                        .putShort((short) 3)
                        .putShort((short) (WmoCollision.DETAIL | WmoCollision.RENDER))
                        .putShort((short) 3);
        var bytes =
                join(
                        chunk("MVER", buffer(4).putInt(17).array()),
                        chunk(
                                "MOGP",
                                join(
                                        new byte[68],
                                        chunk("MPY2", mpy2.array()),
                                        chunk("MOVX", movx.array()),
                                        chunk("MOVT", positions.array()))));
        var c = WmoCollision.read(bytes, () -> false);
        assertEquals(2, c.triangles());
        assertTrue(c.solid(0) && !c.solid(1));
        assertEquals(2, c.corner(1, 0));
        assertEquals("no MOBN", c.bspIssue());
    }

    @Test
    void malformedGroupsAreRefusedAndMeshlessOnesAreEmpty() {
        assertThrows(
                IllegalArgumentException.class, () -> WmoCollision.read(new byte[4], () -> false));
        var truncated = group(null, null);
        var cut = java.util.Arrays.copyOf(truncated, truncated.length);
        ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 1 << 20);
        assertThrows(IllegalArgumentException.class, () -> WmoCollision.read(cut, () -> false));
        var empty =
                WmoCollision.read(
                        join(
                                chunk("MVER", buffer(4).putInt(17).array()),
                                chunk("MOGP", new byte[68])),
                        () -> false);
        assertEquals(0, empty.triangles());
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> WmoCollision.read(group(null, null), () -> true));
    }
}
