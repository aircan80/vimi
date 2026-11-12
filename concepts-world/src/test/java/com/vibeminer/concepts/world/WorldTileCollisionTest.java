package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.ObjectCollision;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorldTileCollisionTest {
    static final long OBJ0 = 10, ROOT = 20, GROUP = 21, MODEL = 30, SET_MODEL = 31, BROKEN = 32;
    static final double C = 51200.0 / 3.0;

    // ---- authored sources ------------------------------------------------------------------

    static ByteBuffer buffer(int count) {
        return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float f : values) {
            b.putFloat(f);
        }
        return b.array();
    }

    static byte[] chunk(String tag, byte[] bytes) {
        int n = 0;
        for (char c : tag.toCharArray()) {
            n = n << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(n).putInt(bytes.length).put(bytes).array();
    }

    static byte[] join(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (var p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    static byte[] version(int v) {
        return chunk("MVER", buffer(4).putInt(v).array());
    }

    /** One MODF record: raw position (x, y, z), heading, flags, doodad set, scale. */
    static byte[] modf(
            long id,
            long uid,
            int flags,
            float x,
            float y,
            float z,
            float heading,
            int set,
            int scale) {
        var b = buffer(64);
        b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
        b.put(floats(0, heading, 0)).put(floats(-50, -50, -50, 50, 50, 50));
        b.putShort((short) flags).putShort((short) set).putShort((short) 0).putShort((short) scale);
        return b.array();
    }

    static byte[] mddf(
            long id, long uid, int flags, float x, float y, float z, float heading, int scale) {
        var b = buffer(36);
        b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
        b.put(floats(0, heading, 0)).putShort((short) scale).putShort((short) flags);
        return b.array();
    }

    static byte[] obj0(List<byte[]> modf, List<byte[]> mddf) {
        return join(
                version(18),
                chunk("MDDF", join(mddf.toArray(byte[][]::new))),
                chunk("MODF", join(modf.toArray(byte[][]::new))));
    }

    /** A root with one group and three doodad sets of one definition each (set models by MODI). */
    static byte[] root() {
        var h = buffer(64);
        h.putInt(1).putInt(1).putInt(0).putInt(0).putInt(0).putInt(0);
        h.putInt(0).putInt(0xff0b0b0b).putInt(123);
        h.put(floats(-1, -1, -1, 2, 2, 2)).putShort((short) 0).putShort((short) 0);
        var momt = buffer(64);
        var gfid = buffer(4).putInt((int) GROUP);
        var mods = buffer(32 * 3);
        for (int i = 0; i < 3; i++) {
            mods.putInt(i * 32 + 20, i).putInt(i * 32 + 24, 1);
        }
        var modi = buffer(4).putInt((int) SET_MODEL);
        var modd = new ByteArrayOutputStream();
        // Definition d at local (10 (d + 1), 0, 0), identity quaternion, scale 2.
        for (int d = 0; d < 3; d++) {
            var b = buffer(40);
            b.putInt(0)
                    .put(floats(10 * (d + 1), 0, 0))
                    .put(floats(0, 0, 0, 1))
                    .putFloat(2)
                    .putInt(0);
            modd.writeBytes(b.array());
        }
        return join(
                version(17),
                chunk("MOHD", h.array()),
                chunk("MOMT", momt.array()),
                chunk("GFID", gfid.array()),
                chunk("MODS", mods.array()),
                chunk("MODI", modi.array()),
                chunk("MODD", modd.toByteArray()));
    }

    /** A group of one solid triangle. */
    static byte[] group() {
        var movi = buffer(6).putShort((short) 0).putShort((short) 1).putShort((short) 2);
        var mopy = buffer(2).put((byte) 0x20).put((byte) 0);
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                new byte[68],
                                chunk("MOVT", floats(0, 0, 0, 1, 0, 0, 0, 1, 0)),
                                chunk("MOVI", movi.array()),
                                chunk("MOPY", mopy.array()))));
    }

    /** An MD20 model whose collision mesh is one triangle. */
    static byte[] model() {
        var b = buffer(0x160);
        b.put(0, "MD20".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(0xD8, 3).putInt(0xDC, 0x100).putInt(0xE0, 3).putInt(0xE4, 0x120);
        b.putShort(0x100, (short) 0).putShort(0x102, (short) 1).putShort(0x104, (short) 2);
        float[] v = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        for (int i = 0; i < v.length; i++) {
            b.putFloat(0x120 + i * 4, v[i]);
        }
        return b.array();
    }

    /** Sources by ID that count reads; IDs in {@code unreadable} throw until removed. */
    static final class Files implements WorldTileCollision.Source {
        final Map<Long, byte[]> bytes = new HashMap<>();
        final Map<Long, Integer> reads = new HashMap<>();
        final Set<Long> unreadable = new java.util.HashSet<>();

        @Override
        public byte[] read(long id, Cancellation token) {
            reads.merge(id, 1, Integer::sum);
            if (unreadable.contains(id) || !bytes.containsKey(id)) {
                throw new WorldTileCollision.Unreadable("not local", null);
            }
            return bytes.get(id);
        }
    }

    static final WorldFormats.Tile TILE = new WorldFormats.Tile(32, 32, 1, 0, 0, OBJ0, 0, 0, 0, 0);

    static Files files(byte[] obj0) {
        var f = new Files();
        f.bytes.put(OBJ0, obj0);
        f.bytes.put(ROOT, root());
        f.bytes.put(GROUP, group());
        f.bytes.put(MODEL, model());
        f.bytes.put(SET_MODEL, model());
        f.bytes.put(BROKEN, new byte[16]);
        return f;
    }

    static Set<String> keys(WorldTileCollision.Part part) {
        return part.placements().stream()
                .map(
                        p -> {
                            var k = (WorldTileCollision.Key) p.key();
                            return k.kind() + ":" + k.uniqueId() + ":" + k.ordinal();
                        })
                .collect(Collectors.toCollection(TreeSet::new));
    }

    // ---- tests -----------------------------------------------------------------------------

    @Test
    void aBuildingPlacesItsGroupsSetZeroAndItsOwnSet() {
        var f = files(obj0(List.of(modf(ROOT, 7, 0x8, 100, 20, 300, 90, 2, 1024)), List.of()));
        var part = new WorldTileCollision(f, 1 << 20).prepare(TILE, Cancellation.NONE);
        assertEquals(Set.of("W:7:0", "S:7:0", "S:7:2"), keys(part), "set 1 is not placed");
        assertTrue(part.complete());
    }

    @Test
    void setDoodadsComposeWithTheirBuilding() {
        // Heading 90: the heading rule maps local (a, b) to (-cos a + sin b, -sin a - cos b) = (b,
        // -a).
        var f =
                files(
                        obj0(
                                List.of(modf(ROOT, 7, 0x8 | 0x4, 100, 20, 300, 90, 0, 2048)),
                                List.of()));
        var part = new WorldTileCollision(f, 1 << 20).prepare(TILE, Cancellation.NONE);
        var building =
                part.placements().stream()
                        .filter(p -> ((WorldTileCollision.Key) p.key()).kind() == 'W')
                        .findFirst()
                        .orElseThrow();
        var doodad =
                part.placements().stream()
                        .filter(p -> ((WorldTileCollision.Key) p.key()).kind() == 'S')
                        .findFirst()
                        .orElseThrow();
        assertEquals(C - 300, building.x(), 1e-6);
        assertEquals(C - 100, building.y(), 1e-6);
        assertEquals(20, building.z(), 1e-6);
        assertEquals(2, building.scale(), 1e-12);
        // Definition 0 at local (10, 0, 0), building scale 2: 20 yd along local -> (0, -20).
        assertEquals(building.x(), doodad.x(), 1e-6);
        assertEquals(building.y() - 20, doodad.y(), 1e-6);
        assertEquals(building.z(), doodad.z(), 1e-6);
        assertEquals(4, doodad.scale(), 1e-12, "building scale times the MODD scale");
        assertArrayEquals(
                building.rotation(),
                doodad.rotation(),
                1e-9,
                "identity quaternion keeps the heading");
        // The placed triangle really lands there.
        var placed = ObjectCollision.of(List.of(doodad));
        assertArrayEquals(
                new double[] {
                    doodad.x() - 4,
                    doodad.y() - 4,
                    doodad.z(),
                    doodad.x() + 4,
                    doodad.y() + 4,
                    doodad.z()
                },
                placed.bounds(),
                4.01);
    }

    @Test
    void unsupportedPlacementsAndBadSourcesAreCountedByReason() {
        var f =
                files(
                        obj0(
                                List.of(modf(ROOT, 1, 0, 0, 0, 0, 0, 0, 1024)),
                                List.of(
                                        mddf(MODEL, 2, 0, 0, 0, 0, 0, 1024),
                                        mddf(BROKEN, 3, 0x40, 0, 0, 0, 0, 1024),
                                        mddf(MODEL, 4, 0x40, 5, 5, 5, 0, 1024))));
        var reader = new WorldTileCollision(f, 1 << 20);
        var part = reader.prepare(TILE, Cancellation.NONE);
        assertEquals(
                Map.of(
                        "building not by file ID",
                        1,
                        "doodad not by file ID",
                        1,
                        "doodad model malformed",
                        1),
                part.skipped());
        assertEquals(Set.of("D:4:0"), keys(part));
        assertTrue(part.complete(), "malformed is not unreadable");
        reader.prepare(TILE, Cancellation.NONE);
        assertEquals(1, f.reads.get(BROKEN), "a malformed model is remembered, not read again");
    }

    @Test
    void unreadableSourcesAreRetriedNotRemembered() {
        var f = files(obj0(List.of(modf(ROOT, 7, 0x8, 0, 0, 0, 0, 0, 1024)), List.of()));
        f.unreadable.add(GROUP);
        var reader = new WorldTileCollision(f, 1 << 20);
        var first = reader.prepare(TILE, Cancellation.NONE);
        assertFalse(first.complete());
        assertEquals(1, first.skipped().get("building group unreadable"));
        assertFalse(keys(first).contains("W:7:0"));
        f.unreadable.clear();
        var second = reader.prepare(TILE, Cancellation.NONE);
        assertTrue(second.complete());
        assertTrue(keys(second).contains("W:7:0"), "read again once readable");
    }

    @Test
    void theCacheStaysWithinItsBudget() {
        var f =
                files(
                        obj0(
                                List.of(modf(ROOT, 7, 0x8, 0, 0, 0, 0, 0, 1024)),
                                List.of(mddf(MODEL, 4, 0x40, 5, 5, 5, 0, 1024))));
        var roomy = new WorldTileCollision(f, 1 << 20);
        roomy.prepare(TILE, Cancellation.NONE);
        roomy.prepare(TILE, Cancellation.NONE);
        assertEquals(1, f.reads.get(GROUP), "a roomy cache reads each source once");
        assertEquals(4, roomy.cachedEntries(), "root, group and two models");
        var tight = new WorldTileCollision(f, 200);
        tight.prepare(TILE, Cancellation.NONE);
        assertTrue(
                tight.cachedBytes() <= 200 + 1024,
                "at most the budget plus the newest entry: " + tight.cachedBytes());
        assertTrue(
                tight.cachedEntries() < 4, "older entries were evicted: " + tight.cachedEntries());
        int before = f.reads.get(GROUP);
        tight.prepare(TILE, Cancellation.NONE);
        assertTrue(f.reads.get(GROUP) > before, "an evicted source is read again");
    }
}
