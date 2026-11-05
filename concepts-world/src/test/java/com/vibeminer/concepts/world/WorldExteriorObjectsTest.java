package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Authored chunk streams only; no game assets, services or GPU. */
class WorldExteriorObjectsTest {
    private static final WorldService.MapInfo MAP = new WorldService.MapInfo(7, "A", "B", 0, 1L);
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 4, 0, 3, 0, 0);
    private static final WorldFormats.Tile NO_OBJ0 =
            new WorldFormats.Tile(7, 19, 2, 0, 0, 0, 0, 3, 0, 0);
    private static final long ROOT = 100, GROUP = 200, INTERIOR = 201, IMAGE = 300, IMAGE2 = 301;
    private static final int EXT = 0x9, INT = 0x2009;
    private static final List<WorldExteriorObjects.Count> PLACEMENT_BUCKETS =
            List.of(
                    WorldExteriorObjects.Count.PLACEMENT_DRAWN,
                    WorldExteriorObjects.Count.PLACEMENT_SHARED_DUPLICATE,
                    WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED,
                    WorldExteriorObjects.Count.PLACEMENT_CONFLICTING,
                    WorldExteriorObjects.Count.PLACEMENT_CAPPED,
                    WorldExteriorObjects.Count.PLACEMENT_FAILED_ROOT,
                    WorldExteriorObjects.Count.PLACEMENT_EMPTY);

    // ---- authored chunk streams -------------------------------------------------------------

    private static ByteBuffer buffer(int count) {
        return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] floats(float... values) {
        var b = buffer(values.length * 4);
        for (float f : values) {
            b.putFloat(f);
        }
        return b.array();
    }

    private static byte[] chunk(String tag, byte[] bytes) {
        int n = 0;
        for (char c : tag.toCharArray()) {
            n = n << 8 | c;
        }
        return buffer(bytes.length + 8).putInt(n).putInt(bytes.length).put(bytes).array();
    }

    private static byte[] join(byte[]... parts) {
        var b = new ByteArrayOutputStream();
        for (var p : parts) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    private static byte[] version(int v) {
        return chunk("MVER", buffer(4).putInt(v).array());
    }

    record Modf(
            long id,
            long uid,
            int flags,
            float x,
            float y,
            float z,
            float pitch,
            float heading,
            float roll,
            int scale) {
        static Modf of(long id, long uid, int flags) {
            return new Modf(id, uid, flags, 100, 20, 300, 0, 90, 0, 2048);
        }

        byte[] bytes() {
            var b = buffer(64);
            b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
            b.put(floats(pitch, heading, roll)).put(floats(-10, -10, -10, 10, 10, 10));
            b.putShort((short) flags).putShort((short) 0).putShort((short) 0);
            b.putShort((short) scale);
            return b.array();
        }
    }

    private static byte[] obj0(List<Modf> records, int doodads) {
        var modf = new ByteArrayOutputStream();
        for (var r : records) {
            modf.writeBytes(r.bytes());
        }
        return join(
                version(18),
                chunk("MDDF", new byte[36 * doodads]),
                chunk("MODF", modf.toByteArray()));
    }

    /** Material: blend mode word 2, texture1 FileDataID word 3. */
    record Material(int blend, long texture, int shader) {}

    private static byte[] root(
            int groupCount, long[] gfid, List<Material> materials, byte[]... extras) {
        var h = buffer(64);
        h.putInt(materials.size()).putInt(groupCount).putInt(0).putInt(0).putInt(0).putInt(0);
        h.putInt(0).putInt(0xff0b0b0b).putInt(123);
        h.put(floats(-1, -1, -1, 2, 2, 2)).putShort((short) 0).putShort((short) 0);
        var m = buffer(64 * materials.size());
        for (int i = 0; i < materials.size(); i++) {
            var material = materials.get(i);
            m.putInt(i * 64 + 4, material.shader())
                    .putInt(i * 64 + 8, material.blend())
                    .putInt(i * 64 + 12, (int) material.texture());
        }
        var g = buffer(4 * gfid.length);
        for (long id : gfid) {
            g.putInt((int) id);
        }
        return join(
                version(17),
                chunk("MOHD", h.array()),
                chunk("MOMT", m.array()),
                chunk("GFID", g.array()),
                join(extras));
    }

    private static byte[] root(long... gfid) {
        return root(gfid.length, gfid, List.of(new Material(0, IMAGE, 0)));
    }

    /** One batch: first index, index count, MOBA flags byte, material byte. */
    record Batch(int first, int count, int flags, int material) {}

    private static byte[] group(int flags, int vertexCount, int triangles, List<Batch> batches) {
        var h = buffer(68);
        h.putInt(0).putInt(0).putInt(flags).put(floats(-1, -1, -1, 2, 2, 2));
        h.putShort(44, (short) batches.size());
        var positions = new float[vertexCount * 3];
        var normals = new float[vertexCount * 3];
        var uv = new float[vertexCount * 2];
        for (int v = 0; v < vertexCount; v++) {
            positions[v * 3] = v % 3;
            positions[v * 3 + 1] = v / 3 % 3;
            normals[v * 3 + 2] = 2; // non-unit source normal; normalized in double
            uv[v * 2] = v;
        }
        var indices = buffer(triangles * 6);
        for (int t = 0; t < triangles; t++) {
            indices.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        }
        var moba = buffer(24 * batches.size());
        for (int i = 0; i < batches.size(); i++) {
            var b = batches.get(i);
            moba.putInt(i * 24 + 12, b.first())
                    .putShort(i * 24 + 16, (short) b.count())
                    .putShort(i * 24 + 18, (short) 0)
                    .putShort(i * 24 + 20, (short) 2)
                    .put(i * 24 + 22, (byte) b.flags())
                    .put(i * 24 + 23, (byte) b.material());
        }
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOVT", floats(positions)),
                                chunk("MONR", floats(normals)),
                                chunk("MOTV", floats(uv)),
                                chunk("MOVI", indices.array()),
                                chunk("MOPY", new byte[triangles * 2]),
                                chunk("MOBA", moba.array()))));
    }

    private static byte[] group(int flags) {
        return group(flags, 3, 1, List.of(new Batch(0, 3, 0, 0)));
    }

    /** BC1 BLP2 with the given declared mip chain (base width/height halved per level). */
    private static byte[] blp(int width, int height, int levels, int color) {
        var data = new ByteArrayOutputStream();
        var header = buffer(148);
        header.put(new byte[] {'B', 'L', 'P', '2'}).putInt(1);
        header.put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 1);
        header.putInt(width).putInt(height);
        int offset = 148;
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            int blocks = ((w + 3) / 4) * ((h + 3) / 4);
            header.putInt(20 + level * 4, offset).putInt(84 + level * 4, blocks * 8);
            var b = buffer(blocks * 8);
            for (int i = 0; i < blocks; i++) {
                b.putShort((short) color).putShort((short) 0).putInt(0);
            }
            data.writeBytes(b.array());
            offset += blocks * 8;
        }
        return join(header.array(), data.toByteArray());
    }

    // ---- fixture ----------------------------------------------------------------------------

    private static final class Fixture implements AutoCloseable {
        final WorldTerrainTextureSourceTest.Files files;
        final AuthoredNativeMap nativeMap;
        final WorldStreamScopes engine;
        final WorldStreamScopes.Scope scope;

        Fixture(List<Modf> records) {
            this(records, new WorldTerrainTextureSourceTest.Files());
        }

        /** Null records leave the authored WDT without an obj0 reference for the tile. */
        Fixture(List<Modf> records, WorldTerrainTextureSourceTest.Files files) {
            this.files = files;
            if (records != null) {
                int offset = 12 + 40 + 8 + 32768 + 8 + (7 * 64 + 19) * 32;
                ByteBuffer.wrap(files.sources.get(1L))
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(offset + 4, 4);
                files.sources.put(4L, obj0(records, 0));
            }
            files.sources.put(ROOT, root(GROUP));
            files.sources.put(GROUP, group(EXT));
            files.sources.put(INTERIOR, group(INT));
            files.sources.put(IMAGE, blp(4, 4, 1, 0xf800));
            files.sources.put(IMAGE2, blp(4, 4, 1, 0x001f));
            nativeMap = new AuthoredNativeMap(files.files());
            engine = new WorldStreamScopes(nativeMap.tables(), files.files());
            scope = engine.create(MAP, Cancellation.NONE);
        }

        WorldExteriorObjects.TileObjects run() {
            return WorldExteriorObjects.prepare(engine, scope, TILE, Cancellation.NONE);
        }

        public void close() {
            engine.closeAsync().join();
            nativeMap.close();
        }
    }

    private static Map<WorldExteriorObjects.Count, Integer> counts(
            WorldExteriorObjects.TileObjects set) {
        var result =
                new EnumMap<WorldExteriorObjects.Count, Integer>(WorldExteriorObjects.Count.class);
        for (var c : WorldExteriorObjects.Count.values()) {
            if (set.count(c) != 0) {
                result.put(c, set.count(c));
            }
        }
        return result;
    }

    private static void assertPlacementsAccounted(
            WorldExteriorObjects.TileObjects set, int records) {
        int sum = 0;
        for (var bucket : PLACEMENT_BUCKETS) {
            sum += set.count(bucket);
        }
        assertEquals(records, sum, "every MODF record lands in exactly one placement bucket");
    }

    private static WorldPlacements.WorldObject declared(Modf r) {
        return WorldPlacements.readObj0(obj0(List.of(r), 0), () -> false).worldObjects().getFirst();
    }

    // ---- tests ------------------------------------------------------------------------------

    @Test
    void multiPlacementMultiGroupExteriorSetSharesOneModelAndKeepsAdtTransforms() {
        var scaled = new Modf(ROOT, 77, 0xc, 100, 20, 300, 0, 90, 0, 2048);
        var unscaled = new Modf(ROOT, 78, 0xa, 50, 5, 60, 0, 180, 0, 4096);
        try (var f = new Fixture(List.of(scaled, unscaled))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            3,
                            new long[] {GROUP, INTERIOR, GROUP + 2},
                            List.of(
                                    new Material(0, IMAGE, 0),
                                    new Material(1, IMAGE, 0), // alpha key: cutout batch
                                    // MOMT word 1 (shader) 6 is admitted, texture1 only.
                                    new Material(0, IMAGE2, 6))));
            f.files.sources.put(
                    GROUP,
                    group(
                            EXT,
                            3,
                            3,
                            List.of(
                                    new Batch(0, 3, 0, 0),
                                    new Batch(3, 3, 0, 1),
                                    new Batch(6, 3, 0, 2))));
            f.files.sources.put(GROUP + 2, group(0x8 | 0x4 | 0x1000)); // MOCV/MLIQ flags ignored
            try (var set = f.run()) {
                assertEquals(WorldExteriorObjects.RECIPE, set.recipe());
                assertEquals(7, set.mapId());
                assertEquals(7, set.tileX());
                assertEquals(19, set.tileY());
                assertEquals(1, set.models().size());
                var model = set.models().getFirst();
                assertEquals(ROOT, model.rootId());
                assertEquals(2, model.meshes().size());
                var mesh = model.meshes().getFirst();
                assertEquals(0, mesh.groupOrdinal());
                assertEquals(GROUP, mesh.groupId());
                // Opaque ranges come first, exactly as the opaque-only profile resolved them;
                // the alpha-key batch follows as a cutout range with its own alpha-keeping image.
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE),
                                new WorldExteriorObjects.DrawRange(
                                        3, 3, 1, 2, WorldExteriorObjects.OPAQUE),
                                new WorldExteriorObjects.DrawRange(
                                        6, 3, 2, 1, WorldExteriorObjects.CUTOUT)),
                        mesh.ranges());
                assertEquals(9, mesh.indices().remaining());
                assertTrue(mesh.vertices().isReadOnly());
                var v = mesh.vertices();
                assertEquals(1, v.get(8), 0); // second vertex x
                assertEquals(1, v.get(5), 0); // normalized source normal (0,0,2)
                assertEquals(1, v.get(14), 0); // second vertex u
                assertEquals(2, model.meshes().get(1).groupOrdinal());
                assertEquals(3, set.images().size());
                var red = set.images().getFirst();
                assertEquals(IMAGE, red.id());
                assertEquals(255, red.rgba().get(0) & 255);
                assertEquals(255, red.rgba().get(3) & 255);
                // The same source is decoded again for the cutout range (alpha not forced).
                assertEquals(IMAGE2, set.images().get(1).id());
                assertEquals(IMAGE, set.images().get(2).id());
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 2,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 2,
                                WorldExteriorObjects.Count.GROUP_INTERIOR, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 4,
                                WorldExteriorObjects.Count.BATCH_CUTOUT, 1,
                                WorldExteriorObjects.Count.IMAGE_DECODED, 3),
                        counts(set));
                assertEquals(2, set.placements().size());
                for (int i = 0; i < 2; i++) {
                    var p = set.placements().get(i);
                    var r = i == 0 ? scaled : unscaled;
                    assertEquals(i, p.ordinal());
                    assertEquals(r.uid(), p.uniqueId());
                    assertEquals(0, p.model());
                    // Reviewed ADT heading rule; scale applies only with flag 0x4.
                    double scale = i == 0 ? 2.0 : 1.0;
                    var oracle = new WorldBasicWmo.Transform(declared(r), scale);
                    assertEquals(oracle.originX(), p.x(), 0);
                    assertEquals(oracle.originY(), p.y(), 0);
                    assertEquals(oracle.originZ(), p.z(), 0);
                    assertEquals(scale, p.scale(), 0);
                    assertEquals(oracle.cos(), p.cos(), 0);
                    assertEquals(oracle.sin(), p.sin(), 0);
                }
                // Independent oracle: heading 90, scale 2 maps local (1, 2) as WorldBasicWmoTest.
                double c = 51200.0 / 3.0;
                var p = set.placements().getFirst();
                assertEquals(c - 300 + 4, p.x() + p.scale() * (-p.cos() * 1 + p.sin() * 2), 1e-10);
                assertEquals(c - 100 - 2, p.y() + p.scale() * (-p.sin() * 1 - p.cos() * 2), 1e-10);
            }
        }
    }

    @Test
    void unsupportedFlagsRotationScaleAndDuplicatesAreCountedNotGuessed() {
        var records =
                List.of(
                        Modf.of(ROOT, 1, 0x8),
                        Modf.of(ROOT, 2, 0x9), // 0x1 unsupported
                        Modf.of(ROOT, 3, 0x4), // name-table reference
                        new Modf(ROOT, 4, 0x8, 1, 2, 3, 5, 0, 0, 1024), // pitch
                        new Modf(ROOT, 5, 0x8, 1, 2, 3, 0, 0, 5, 1024), // roll
                        new Modf(ROOT, 6, 0xc, 1, 2, 3, 0, 0, 0, 0), // scaled to zero
                        new Modf(ROOT, 7, 0x8, 1, 2, 3, 0, 0, 0, 0), // raw scale ignored
                        Modf.of(ROOT, 8, 0x8),
                        Modf.of(ROOT, 8, 0x8), // identical duplicate shares one placement
                        Modf.of(ROOT, 9, 0x8),
                        new Modf(ROOT, 9, 0x8, 1, 1, 1, 0, 0, 0, 1024), // conflicting
                        Modf.of(0, 10, 0x8), // zero FileDataID
                        Modf.of(ROOT, 11, 0x18)); // undocumented 0x10
        try (var f = new Fixture(records);
                var set = f.run()) {
            assertEquals(
                    Map.of(
                            WorldExteriorObjects.Count.PLACEMENT_DRAWN, 3,
                            WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED, 7,
                            WorldExteriorObjects.Count.PLACEMENT_SHARED_DUPLICATE, 1,
                            WorldExteriorObjects.Count.PLACEMENT_CONFLICTING, 2,
                            WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                            WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                            WorldExteriorObjects.Count.BATCH_DRAWN, 1,
                            WorldExteriorObjects.Count.IMAGE_DECODED, 1),
                    counts(set));
            assertPlacementsAccounted(set, records.size());
            assertEquals(
                    List.of(0, 6, 7),
                    set.placements().stream()
                            .map(WorldExteriorObjects.Placement::ordinal)
                            .toList());
            assertEquals(1.0, set.placements().get(1).scale(), 0);
        }
    }

    @Test
    void rootFailuresAndUnavailableSourcesOmitOnlyTheirPlacements() {
        long[] ids = {101, 102, 103, 104, 105, 106, 107};
        var records = new ArrayList<Modf>();
        for (int i = 0; i < ids.length; i++) {
            records.add(Modf.of(ids[i], 20 + i, 0x8));
        }
        records.add(Modf.of(ROOT, 40, 0x8));
        records.add(Modf.of(101, 41, 0x8)); // reuses the cached failed root, no second read
        try (var f = new Fixture(records)) {
            f.files.sources.put(
                    101L,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            chunk("MOTX", new byte[4]))); // name-offset texture words
            f.files.sources.put(
                    102L,
                    root(2, new long[] {GROUP, GROUP, GROUP}, List.of(new Material(0, IMAGE, 0))));
            f.files.sources.put(103L, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
            // 104 missing entirely.
            f.files.sources.put(105L, root(GROUP));
            f.files.protectedId = 105;
            f.files.sources.put(106L, new byte[(1 << 20) + 1]); // over the 1 MiB source cap
            f.files.sources.put(107L, root(0, new long[0], List.of(new Material(0, IMAGE, 0))));
            try (var set = f.run()) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                WorldExteriorObjects.Count.PLACEMENT_FAILED_ROOT, 8,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.ROOT_FAILED, 1,
                                WorldExteriorObjects.Count.ROOT_UNSUPPORTED, 3,
                                WorldExteriorObjects.Count.ROOT_UNAVAILABLE, 3,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 1,
                                WorldExteriorObjects.Count.IMAGE_DECODED, 1),
                        counts(set));
                assertPlacementsAccounted(set, records.size());
                assertEquals(1, f.files.opens.get(101L).get());
                assertEquals(null, f.files.opens.get(106L), "oversize declined before opening");
            }
        }
    }

    @Test
    void groupOutcomesInteriorZeroUnavailableMalformedAndLodEntriesNotRead() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            // groupCount 5, GFID holds two LOD sets; LOD1 entries (900..904) are never read.
            f.files.sources.put(
                    ROOT,
                    root(
                            5,
                            new long[] {GROUP, INTERIOR, 0, 210, 211, 900, 901, 902, 903, 904},
                            List.of(new Material(0, IMAGE, 0))));
            // 210 missing; 211 has neither exterior nor interior flag.
            f.files.sources.put(211L, group(0x1));
            for (long lod = 900; lod <= 904; lod++) {
                f.files.sources.put(lod, group(EXT));
            }
            try (var set = f.run()) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_INTERIOR, 1,
                                WorldExteriorObjects.Count.GROUP_UNSUPPORTED, 2,
                                WorldExteriorObjects.Count.GROUP_UNAVAILABLE, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 1,
                                WorldExteriorObjects.Count.IMAGE_DECODED, 1),
                        counts(set));
                for (long lod = 900; lod <= 904; lod++) {
                    assertEquals(null, f.files.opens.get(lod), "LOD" + lod);
                }
            }
        }
        for (int mode = 0; mode < 3; mode++) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(
                        GROUP,
                        switch (mode) {
                            case 0 -> new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
                            case 1 ->
                                    group(
                                            EXT,
                                            3,
                                            2,
                                            List.of(
                                                    new Batch(0, 6, 0, 0),
                                                    new Batch(3, 3, 0, 0))); // overlapping batches
                            default -> {
                                var bytes = group(EXT);
                                // Zero-length normal for the first vertex.
                                int at = indexOf(bytes, "MONR") + 8 + 8;
                                ByteBuffer.wrap(bytes)
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .putFloat(at, 0);
                                yield bytes;
                            }
                        });
                try (var set = f.run()) {
                    assertEquals(
                            Map.of(
                                    WorldExteriorObjects.Count.PLACEMENT_EMPTY, 1,
                                    WorldExteriorObjects.Count.ROOT_EMPTY, 1,
                                    WorldExteriorObjects.Count.GROUP_UNSUPPORTED, 1),
                            counts(set),
                            "mode " + mode);
                    assertTrue(set.models().isEmpty());
                    assertTrue(set.placements().isEmpty());
                }
            }
        }
    }

    @Test
    void batchAndBlendQualificationAndMissingImagesOmitOnlyTheirBatches() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(
                                    new Material(0, IMAGE, 0),
                                    new Material(2, IMAGE, 0), // blended: drawn, alpha kept
                                    new Material(3, IMAGE, 0), // additive: unsupported
                                    new Material(0, 0, 0), // no texture1
                                    new Material(0, 400, 0), // missing image source
                                    new Material(0, 401, 0), // undecodable
                                    new Material(0, 402, 0)))); // protected
            var batches = new ArrayList<Batch>();
            for (int material = 0; material < 7; material++) {
                batches.add(new Batch(material * 3, 3, 0, material));
            }
            batches.add(new Batch(21, 3, 2, 0)); // MOBA flags 2: large material index form
            batches.add(new Batch(24, 3, 0, 9)); // material out of range
            f.files.sources.put(GROUP, group(EXT, 3, 9, batches));
            f.files.sources.put(401L, join(new byte[] {'B', 'L', 'P', '1'}, new byte[200]));
            f.files.sources.put(402L, blp(4, 4, 1, 0));
            f.files.protectedId = 402;
            try (var set = f.run()) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 2,
                                WorldExteriorObjects.Count.BATCH_BLENDED, 1,
                                WorldExteriorObjects.Count.BATCH_UNSUPPORTED, 4,
                                WorldExteriorObjects.Count.BATCH_MISSING_IMAGE, 3,
                                WorldExteriorObjects.Count.IMAGE_DECODED, 2,
                                WorldExteriorObjects.Count.IMAGE_UNAVAILABLE, 2,
                                WorldExteriorObjects.Count.IMAGE_UNDECODABLE, 1),
                        counts(set));
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE),
                                new WorldExteriorObjects.DrawRange(
                                        3, 3, 1, 1, WorldExteriorObjects.BLENDED)),
                        set.models().getFirst().meshes().getFirst().ranges());
            }
        }
    }

    @Test
    void mipSelectionPrefersLargestUpTo256AndFallsBackNearThePixelBudget() {
        assertEquals(0, WorldExteriorObjects.mipFor(256, 256, 1 << 20));
        assertEquals(2, WorldExteriorObjects.mipFor(1024, 512, 1 << 20));
        assertEquals(1, WorldExteriorObjects.mipFor(512, 8, 1 << 20));
        assertEquals(1, WorldExteriorObjects.mipFor(256, 256, 128 * 128));
        assertEquals(2, WorldExteriorObjects.mipFor(256, 256, 64 * 64));
        assertEquals(-1, WorldExteriorObjects.mipFor(256, 256, 64 * 64 - 1));
        assertEquals(0, WorldExteriorObjects.mipFor(4, 4, 16));
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(IMAGE, blp(512, 8, 2, 0xf800));
            try (var set = f.run()) {
                var image = set.images().getFirst();
                assertEquals(1, image.mipLevel());
                assertEquals(256, image.width());
                assertEquals(4, image.height());
                assertEquals(256 * 4, set.pixels());
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(IMAGE, blp(512, 8, 1, 0xf800)); // level 1 not declared
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_UNDECODABLE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_EMPTY));
            }
        }
    }

    @Test
    void placementCapKeepsFirstSixtyFourInSourceOrder() {
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 70; i++) {
            records.add(Modf.of(ROOT, 1000 + i, 0x8));
        }
        try (var f = new Fixture(records);
                var set = f.run()) {
            assertEquals(64, set.placements().size());
            assertEquals(63, set.placements().getLast().ordinal());
            assertEquals(6, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
            assertEquals(1, f.files.opens.get(ROOT).get(), "one root read shared by placements");
            assertPlacementsAccounted(set, records.size());
        }
    }

    @Test
    void rootReadCapAndGroupReadCapNeverSplitARoot() {
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 26; i++) {
            records.add(Modf.of(500 + i, 2000 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            for (int i = 0; i < 26; i++) {
                f.files.sources.put(500L + i, root(GROUP));
            }
            try (var set = f.run()) {
                assertEquals(24, set.models().size());
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(null, f.files.opens.get(524L));
                assertEquals(null, f.files.opens.get(525L));
                assertPlacementsAccounted(set, records.size());
            }
        }
        // 200 + 100 LOD0 groups exceed the 256 group-read budget: the second root is omitted
        // whole before any of its groups is read, and a later small root still fits.
        try (var f =
                new Fixture(
                        List.of(
                                Modf.of(600, 1, 0x8),
                                Modf.of(601, 2, 0x8),
                                Modf.of(ROOT, 3, 0x8)))) {
            f.files.sources.put(600L, root(repeat(GROUP, 200)));
            f.files.sources.put(601L, root(repeat(GROUP + 1, 100)));
            f.files.sources.put(GROUP + 1, group(EXT));
            try (var set = f.run()) {
                assertEquals(
                        List.of(600L, ROOT),
                        set.models().stream().map(WorldExteriorObjects.Model::rootId).toList());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(null, f.files.opens.get(GROUP + 1));
                assertEquals(201, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
        }
    }

    @Test
    void geometryCapOmitsWholeRootAndKeepsLaterSmallerRoot() {
        // Seven 16384-vertex groups exceed 98,304 vertices; the root is omitted whole.
        try (var f = new Fixture(List.of(Modf.of(700, 1, 0x8), Modf.of(ROOT, 2, 0x8)))) {
            f.files.sources.put(700L, root(repeat(701, 7)));
            f.files.sources.put(701L, group(EXT, 16384, 1, List.of(new Batch(0, 3, 0, 0))));
            try (var set = f.run()) {
                assertEquals(1, set.models().size());
                assertEquals(ROOT, set.models().getFirst().rootId());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(3, set.vertices());
            }
        }
        // Six such groups fit exactly at 98,304 vertices.
        try (var f = new Fixture(List.of(Modf.of(700, 1, 0x8)))) {
            f.files.sources.put(700L, root(repeat(701, 6)));
            f.files.sources.put(701L, group(EXT, 16384, 1, List.of(new Batch(0, 3, 0, 0))));
            try (var set = f.run()) {
                assertEquals(WorldExteriorObjects.MAX_VERTICES, set.vertices());
                assertEquals(6, set.models().getFirst().meshes().size());
            }
        }
    }

    @Test
    void imageReadCapCountsMissingImagesDeterministically() {
        // WmoGroupSpans admits at most 64 batches per group, so two groups carry 66 materials.
        var materials = new ArrayList<Material>();
        var first = new ArrayList<Batch>();
        var second = new ArrayList<Batch>();
        for (int i = 0; i < 66; i++) {
            materials.add(new Material(0, 3000 + i, 0));
            (i < 33 ? first : second).add(new Batch(i % 33 * 3, 3, 0, i));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(2, new long[] {GROUP, GROUP + 1}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 33, first));
            f.files.sources.put(GROUP + 1, group(EXT, 3, 33, second));
            for (int i = 0; i < 66; i++) {
                f.files.sources.put(3000L + i, blp(4, 4, 1, i));
            }
            try (var set = f.run()) {
                assertEquals(64, set.images().size());
                assertEquals(64, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                assertEquals(2, set.count(WorldExteriorObjects.Count.IMAGE_CAPPED));
                assertEquals(null, f.files.opens.get(3064L));
                assertEquals(3063L, set.images().getLast().id());
            }
        }
    }

    @Test
    void tileWithoutObj0IsEmptyAndUnqualifiedDoodadsAreCountedNotDrawn() {
        try (var f = new Fixture(null);
                var set =
                        WorldExteriorObjects.prepare(
                                f.engine, f.scope, NO_OBJ0, Cancellation.NONE)) {
            assertTrue(counts(set).isEmpty());
            assertTrue(set.placements().isEmpty());
        }
        try (var f = new Fixture(List.of())) {
            f.files.sources.put(4L, obj0(List.of(Modf.of(ROOT, 1, 0x8)), 3));
            try (var set = f.run()) {
                // Three zeroed MDDF records: no direct-ID flag, then identical duplicates.
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_UNSUPPORTED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_SHARED_DUPLICATE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
            }
        }
    }

    @Test
    void sourceChangeAfterReadAndCancellationRetireTheWholeSet() {
        for (long id : new long[] {4, ROOT, GROUP, IMAGE}) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.changedId = id;
                f.files.changeAfterRead = true;
                // The key changes between open and the post-read stat: never an omission.
                var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::run, "id " + id);
                assertEquals("SOURCE_BINDING", refusal.site(), "id " + id);
            }
        }
        // A short read of a found, unprotected group fails the whole set at SOURCE_INPUT; it is
        // not counted as an unavailable group.
        var truncated =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                        var actual = super.open(id, cancel);
                        if (id != GROUP) {
                            return actual;
                        }
                        return new com.vibeminer.files.FileReadStream() {
                            public long length() {
                                return actual.length();
                            }

                            public long position() {
                                return actual.position();
                            }

                            public void seek(long position) {
                                actual.seek(position);
                            }

                            public List<com.vibeminer.files.FileTypes.FileHole> holes() {
                                return actual.holes();
                            }

                            public java.util.concurrent.CompletableFuture<Integer> read(
                                    ByteBuffer destination, Cancellation token) {
                                return java.util.concurrent.CompletableFuture.completedFuture(-1);
                            }

                            public void close() {
                                actual.close();
                            }
                        };
                    }
                };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), truncated)) {
            var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::run);
            assertEquals("SOURCE_INPUT", refusal.site());
        }
        var token = new Cancellation();
        var groupOpens = new AtomicInteger();
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                        if (id == GROUP && groupOpens.incrementAndGet() == 1) {
                            token.cancel(); // between the first and second group read
                        }
                        return super.open(id, cancel);
                    }
                };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            f.files.sources.put(ROOT, root(GROUP, GROUP));
            assertThrows(
                    CancellationException.class,
                    () -> WorldExteriorObjects.prepare(f.engine, f.scope, TILE, token));
            assertEquals(1, groupOpens.get(), "no further source read after cancellation");
            // The engine stays healthy for the next operation.
            try (var set = f.run()) {
                assertEquals(2, set.models().getFirst().meshes().size());
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            var set = f.run();
            set.close();
            assertThrows(IllegalStateException.class, set::placements);
        }
    }

    @Test
    void indexAndRangeCapsOmitWholeRootsBeforeImagesAreRead() {
        // Seven groups of 16,384 triangles (49,152 indices each) exceed 294,912 indices.
        try (var f = new Fixture(List.of(Modf.of(710, 1, 0x8), Modf.of(ROOT, 2, 0x8)))) {
            f.files.sources.put(710L, root(7, repeat(711, 7), List.of(new Material(0, 712, 0))));
            f.files.sources.put(711L, group(EXT, 3, 16384, List.of(new Batch(0, 49152, 0, 0))));
            f.files.sources.put(712L, blp(4, 4, 1, 0));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(
                        List.of(ROOT),
                        set.models().stream().map(WorldExteriorObjects.Model::rootId).toList());
                assertEquals(null, f.files.opens.get(712L), "capped root reads no image");
            }
        }
        // Seventeen groups of 64 batches exceed 1,024 draw ranges.
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 64; i++) {
            batches.add(new Batch(i * 3, 3, 0, 0));
        }
        try (var f = new Fixture(List.of(Modf.of(720, 1, 0x8)))) {
            f.files.sources.put(720L, root(repeat(721, 17)));
            f.files.sources.put(721L, group(EXT, 3, 64, batches));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
            }
        }
        // Sixteen such groups fit exactly at 1,024 ranges.
        try (var f = new Fixture(List.of(Modf.of(720, 1, 0x8)))) {
            f.files.sources.put(720L, root(repeat(721, 16)));
            f.files.sources.put(721L, group(EXT, 3, 64, batches));
            try (var set = f.run()) {
                assertEquals(WorldExteriorObjects.MAX_RANGES, set.ranges());
            }
        }
    }

    @Test
    void tilePixelBudgetFallsBackToSmallerMipThenCapsImage() {
        // 15 x 256^2 + 256x128 leave 32,768 pixels: the next 256^2 image falls to its 128^2
        // level. The last then also selects level 1 by budget, which it does not declare.
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 18; i++) {
            materials.add(new Material(0, 4000 + i, 0));
            batches.add(new Batch(i * 3, 3, 0, i));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 18, batches));
            for (int i = 0; i < 15; i++) {
                f.files.sources.put(4000L + i, blp(256, 256, 1, i));
            }
            f.files.sources.put(4015L, blp(256, 128, 1, 0));
            f.files.sources.put(4016L, blp(256, 256, 3, 0));
            f.files.sources.put(4017L, blp(256, 256, 1, 0)); // only level 0 declared
            try (var set = f.run()) {
                var fallback = set.images().get(16);
                assertEquals(4016L, fallback.id());
                assertEquals(1, fallback.mipLevel());
                assertEquals(128, fallback.width());
                // 16,384 pixels remain: 4017 selects level 1 by budget but declares none.
                assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_UNDECODABLE));
                assertEquals(17, set.images().size());
                assertEquals(15 * 65536 + 32768 + 16384, set.pixels());
            }
        }
    }

    @Test
    void preparationIsDeterministicAcrossRuns() {
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 5; i++) {
            records.add(new Modf(ROOT, 10 + i, 0xc, i, 2 * i, 3 * i, 0, 37 * i, 0, 1024 + i));
        }
        List<Object> first = null;
        for (int run = 0; run < 2; run++) {
            try (var f = new Fixture(records);
                    var set = f.run()) {
                var summary = new ArrayList<Object>(set.placements());
                summary.add(counts(set));
                summary.add(set.vertices());
                if (first == null) {
                    first = summary;
                } else {
                    assertEquals(first, summary);
                }
            }
        }
    }

    private static long[] repeat(long id, int count) {
        var result = new long[count];
        java.util.Arrays.fill(result, id);
        return result;
    }

    private static int indexOf(byte[] bytes, String name) {
        int tag = 0;
        for (char c : name.toCharArray()) {
            tag = tag << 8 | c;
        }
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= bytes.length - 8; i++) {
            if (b.getInt(i) == tag) {
                return i;
            }
        }
        throw new AssertionError("Missing authored chunk " + name);
    }

    // ---- BASIC doodads and material classes -------------------------------------------------

    private static final long MODEL = 500, SKIN = 900, MODEL_IMAGE = 700;

    private static byte[] resource(String name) {
        try (var in = WorldExteriorObjectsTest.class.getResourceAsStream(name)) {
            return java.util.Objects.requireNonNull(in, name).readAllBytes();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Authored chunked 274 M2 (SFID 900, TXID 700): 4 vertices, one opaque one-triangle batch. */
    private static byte[] model() {
        return resource("doodad-274.m2");
    }

    private static byte[] skin() {
        return resource("doodad-274.skin");
    }

    /** The authored model with material 0's blend mode replaced. */
    private static byte[] model(int blend) {
        var bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(40 + 537 + 2, (short) blend);
        return bytes;
    }

    /** DXT1 with 1-bit alpha whose every texel is transparent. */
    private static byte[] transparentBlp() {
        var bytes = blp(4, 4, 1, 0);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).put(9, (byte) 1);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(148, (short) 0);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(150, (short) 0xffff);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(152, -1);
        return bytes;
    }

    record Mddf(
            long id,
            long uid,
            int flags,
            float x,
            float y,
            float z,
            float pitch,
            float heading,
            float roll,
            int scale) {
        static Mddf of(long id, long uid, int flags) {
            return new Mddf(id, uid, flags, 100, 20, 300, 0, 90, 0, 2048);
        }

        byte[] bytes() {
            var b = buffer(36);
            b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
            b.put(floats(pitch, heading, roll)).putShort((short) scale).putShort((short) flags);
            return b.array();
        }
    }

    /** obj0 with MDDF doodads and MODF placements; doodadSets patch each MODF's doodad set. */
    private static byte[] obj0(List<Modf> records, List<Mddf> doodads, int... doodadSets) {
        var mddf = new ByteArrayOutputStream();
        for (var d : doodads) {
            mddf.writeBytes(d.bytes());
        }
        var modf = new ByteArrayOutputStream();
        for (int i = 0; i < records.size(); i++) {
            var bytes = records.get(i).bytes();
            if (i < doodadSets.length) {
                ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(58, (short) doodadSets[i]);
            }
            modf.writeBytes(bytes);
        }
        return join(
                version(18), chunk("MDDF", mddf.toByteArray()), chunk("MODF", modf.toByteArray()));
    }

    /** One MODD definition in WMO-local axes (quaternion x, y, z, w). */
    record Modd(int reference, float x, float y, float z, double[] q, float scale) {
        byte[] bytes() {
            var b = buffer(40);
            b.putInt(reference).put(floats(x, y, z));
            b.put(floats((float) q[0], (float) q[1], (float) q[2], (float) q[3]));
            b.putFloat(scale).putInt(0);
            return b.array();
        }
    }

    private static byte[] doodadChunks(int[][] sets, long[] fileIds, List<Modd> defs) {
        var mods = buffer(32 * sets.length);
        for (int i = 0; i < sets.length; i++) {
            mods.putInt(i * 32 + 20, sets[i][0]).putInt(i * 32 + 24, sets[i][1]);
        }
        var modi = buffer(4 * fileIds.length);
        for (long id : fileIds) {
            modi.putInt((int) id);
        }
        var modd = new ByteArrayOutputStream();
        for (var d : defs) {
            modd.writeBytes(d.bytes());
        }
        return join(
                chunk("MODS", mods.array()),
                chunk("MODI", modi.array()),
                chunk("MODD", modd.toByteArray()));
    }

    private static void putModel(Fixture f, long id, byte[] bytes) {
        f.files.sources.put(id, bytes);
        f.files.sources.put(SKIN, skin());
        f.files.sources.put(MODEL_IMAGE, blp(4, 4, 1, 0x07e0));
    }

    private static WorldExteriorObjects.TileObjects run(Fixture f, byte[] obj0) {
        f.files.sources.put(4L, obj0);
        return f.run();
    }

    private static void assertRotation(
            WorldExteriorObjects.Rotation expected, WorldExteriorObjects.Rotation actual) {
        var e = expected.rowMajor();
        var a = actual.rowMajor();
        for (int i = 0; i < 9; i++) {
            assertEquals(e[i], a[i], 1e-12, "rotation element " + i);
        }
    }

    @Test
    void mddfDoodadsShareOneStaticModelUnderTheReviewedHeadingRule() {
        // Pitched and rolled records now draw too; their transforms are checked against the
        // wowdev placement-matrix oracle in mddfPitchRollAndHeadingFollowThePinnedPlacementMatrix.
        var drawn = Mddf.of(MODEL, 1, 0x40);
        var shrub = new Mddf(MODEL, 2, 0x42, 50, 5, 60, 0, 180, 0, 512);
        var doodads =
                List.of(
                        drawn,
                        shrub,
                        shrub, // identical duplicate shares one placement
                        Mddf.of(MODEL, 3, 0x41), // 0x1 is not admitted
                        Mddf.of(MODEL, 4, 0x2), // name-table reference
                        new Mddf(MODEL, 5, 0x40, 1, 2, 3, 5, 0, 0, 1024), // pitch
                        new Mddf(MODEL, 6, 0x40, 1, 2, 3, 0, 0, 5, 1024), // roll
                        new Mddf(MODEL, 7, 0x40, 1, 2, 3, 0, 0, 0, 0), // zero scale
                        Mddf.of(0, 8, 0x40), // zero FileDataID
                        Mddf.of(MODEL + 1, 9, 0x40), // unavailable model
                        Mddf.of(MODEL, 10, 0x40),
                        new Mddf(MODEL, 10, 0x40, 1, 1, 1, 0, 0, 0, 1024)); // conflicting
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            try (var set = run(f, obj0(List.of(), doodads))) {
                assertEquals(
                        Map.ofEntries(
                                Map.entry(WorldExteriorObjects.Count.DOODAD_DRAWN, 4),
                                Map.entry(WorldExteriorObjects.Count.DOODAD_SHARED_DUPLICATE, 1),
                                Map.entry(WorldExteriorObjects.Count.DOODAD_UNSUPPORTED, 4),
                                Map.entry(WorldExteriorObjects.Count.DOODAD_CONFLICTING, 2),
                                Map.entry(WorldExteriorObjects.Count.DOODAD_FAILED_MODEL, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_UNAVAILABLE, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_BATCH_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_IMAGE_DECODED, 1)),
                        counts(set));
                assertEquals(1, set.models().size());
                var model = set.models().getFirst();
                assertEquals(MODEL, model.rootId());
                assertTrue(model.doodad());
                var mesh = model.meshes().getFirst();
                assertEquals(SKIN, mesh.groupId());
                // The SKIN vertex lookup (2, 0, 1, 3) orders the mesh vertices.
                assertEquals(4, mesh.vertexCount());
                var v = mesh.vertices();
                assertEquals(0, v.get(0), 0);
                assertEquals(3, v.get(1), 0);
                assertEquals(1, v.get(5), 0); // unit source normal (0, 0, 1)
                assertEquals(1, v.get(7), 0); // uv0 v of global vertex 2
                assertEquals(0, v.get(8), 0); // second: global vertex 0
                assertEquals(2, v.get(16), 0); // third: global vertex 1
                assertEquals(List.of(0, 1, 2), toList(mesh.indices()));
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE)),
                        mesh.ranges());
                assertEquals(MODEL_IMAGE, set.images().getFirst().id());
                assertEquals(4, set.placements().size());
                for (int i = 0; i < 2; i++) {
                    var p = set.placements().get(i);
                    var r = i == 0 ? drawn : shrub;
                    assertEquals(WorldExteriorObjects.Kind.DOODAD, p.kind());
                    assertEquals(i, p.ordinal());
                    assertEquals(r.uid(), p.uniqueId());
                    var oracle =
                            new WorldBasicWmo.Transform(
                                    new WorldPlacements.Vec3(r.x(), r.y(), r.z()),
                                    r.heading(),
                                    r.scale() / 1024.0);
                    assertEquals(oracle.originX(), p.x(), 0);
                    assertEquals(oracle.originY(), p.y(), 0);
                    assertEquals(oracle.originZ(), p.z(), 0);
                    assertEquals(r.scale() / 1024.0, p.scale(), 0);
                    assertRotation(
                            WorldExteriorObjects.Rotation.heading(oracle.cos(), oracle.sin()),
                            p.rotation());
                }
            }
        }
    }

    private static List<Integer> toList(java.nio.IntBuffer buffer) {
        var out = new ArrayList<Integer>();
        while (buffer.hasRemaining()) {
            out.add(buffer.get());
        }
        return out;
    }

    @Test
    void modelBlendClassesKeepAlphaAndUnsupportedBlendsEmptyTheModel() {
        for (int blend : new int[] {1, 2, 3, 4}) {
            try (var f = new Fixture(List.of())) {
                putModel(f, MODEL, model(blend));
                f.files.sources.put(MODEL_IMAGE, transparentBlp());
                try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                    if (blend > 2) {
                        assertEquals(
                                Map.of(
                                        WorldExteriorObjects.Count.DOODAD_EMPTY, 1,
                                        WorldExteriorObjects.Count.MODEL_EMPTY, 1,
                                        WorldExteriorObjects.Count.MODEL_BATCH_UNSUPPORTED, 1),
                                counts(set));
                        assertTrue(set.models().isEmpty());
                        continue;
                    }
                    var range = set.models().getFirst().meshes().getFirst().ranges().getFirst();
                    assertEquals(blend, range.blend());
                    // Alpha is kept for cutout and blended ranges.
                    assertEquals(0, set.images().getFirst().rgba().get(3) & 255);
                }
            }
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(MODEL_IMAGE, transparentBlp());
            try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                // Opaque ranges force alpha.
                assertEquals(255, set.images().getFirst().rgba().get(3) & 255);
            }
        }
    }

    @Test
    void wmoCutoutKeepsAlphaWhileTheOpaqueDecodeForcesIt() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE, 0))));
            f.files.sources.put(
                    GROUP, group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 1))));
            f.files.sources.put(IMAGE, transparentBlp());
            try (var set = f.run()) {
                assertEquals(2, set.images().size());
                assertEquals(255, set.images().get(0).rgba().get(3) & 255);
                assertEquals(0, set.images().get(1).rgba().get(3) & 255);
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
            }
        }
    }

    @Test
    void modelFailuresAreCountedAndOmitOnlyTheirDoodads() {
        var bad = model();
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(40 + 4, 273); // version
        var truncated = java.util.Arrays.copyOf(model(), 100);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(MODEL + 1, bad);
            f.files.sources.put(MODEL + 2, truncated);
            var noSkin = model();
            ByteBuffer.wrap(noSkin).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 901); // SFID
            f.files.sources.put(MODEL + 3, noSkin);
            var doodads =
                    List.of(
                            Mddf.of(MODEL + 1, 1, 0x40),
                            Mddf.of(MODEL + 2, 2, 0x40),
                            Mddf.of(MODEL + 3, 3, 0x40),
                            Mddf.of(MODEL, 4, 0x40));
            try (var set = run(f, obj0(List.of(), doodads))) {
                assertEquals(3, set.count(WorldExteriorObjects.Count.DOODAD_FAILED_MODEL));
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.MODEL_UNSUPPORTED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.MODEL_FAILED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.MODEL_UNAVAILABLE));
                assertEquals(1, set.placements().size());
            }
        }
    }

    @Test
    void doodadPlacementAndModelCapsKeepSourceOrder() {
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_DOODADS + 3; i++) {
            doodads.add(Mddf.of(MODEL, i + 1, 0x40));
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            try (var set = run(f, obj0(List.of(), doodads))) {
                assertEquals(
                        WorldExteriorObjects.MAX_DOODADS,
                        set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(3, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertEquals(
                        WorldExteriorObjects.MAX_DOODADS - 1, set.placements().getLast().ordinal());
            }
        }
        var distinct = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_MODELS + 2; i++) {
            distinct.add(Mddf.of(MODEL + i, i + 1, 0x40));
        }
        try (var f = new Fixture(List.of())) {
            for (int i = 0; i < WorldExteriorObjects.MAX_MODELS + 2; i++) {
                putModel(f, MODEL + i, model());
            }
            try (var set = run(f, obj0(List.of(), distinct))) {
                assertEquals(
                        WorldExteriorObjects.MAX_MODELS,
                        set.count(WorldExteriorObjects.Count.MODEL_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertEquals(WorldExteriorObjects.MAX_MODELS, set.models().size());
            }
        }
    }

    @Test
    void wmoDoodadSetsComposeTheBuildingTransform() {
        double h = Math.sqrt(0.5);
        var defs =
                List.of(
                        new Modd(0, 1, 2, 3, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 0, 0, 0, new double[] {0, 0, h, h}, 0.5f), // 90 deg about z
                        new Modd(1, 0, 0, 0, new double[] {0, 0, 0, 1}, 1), // MODI entry 0
                        new Modd(5, 0, 0, 0, new double[] {0, 0, 0, 1}, 1), // outside MODI
                        new Modd(0, 0, 0, 0, new double[] {0, 0, 0, 2}, 1), // not unit
                        new Modd(0, 0, 0, 0, new double[] {h, 0, 0, h}, 1), // pitched: drawn
                        new Modd(0x02000000, 0, 0, 0, new double[] {0, 0, 0, 1}, 1)); // flag 0x2
        var chunks =
                doodadChunks(new int[][] {{0, 2}, {2, 5}, {4, 1}}, new long[] {MODEL, 0}, defs);
        var building = new Modf(ROOT, 77, 0xc, 100, 20, 300, 0, 90, 0, 2048);
        var other = new Modf(ROOT, 78, 0x8, 50, 5, 60, 0, 180, 0, 1024);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            // The first building selects set 1 besides set 0; the second names a missing set 9.
            try (var set = run(f, obj0(List.of(building, other), List.of(), 1, 9))) {
                assertEquals(
                        Map.ofEntries(
                                Map.entry(WorldExteriorObjects.Count.PLACEMENT_DRAWN, 2),
                                Map.entry(WorldExteriorObjects.Count.ROOT_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.GROUP_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.BATCH_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.IMAGE_DECODED, 1),
                                Map.entry(WorldExteriorObjects.Count.SET_DOODAD_DRAWN, 5),
                                Map.entry(WorldExteriorObjects.Count.SET_DOODAD_UNSUPPORTED, 4),
                                Map.entry(WorldExteriorObjects.Count.SET_UNSUPPORTED, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_BATCH_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_IMAGE_DECODED, 1)),
                        counts(set));
                // Buildings first, then building 77's set 0 and set 1, then building 78's set 0.
                var kinds = set.placements().stream().map(p -> p.kind()).toList();
                assertEquals(
                        List.of(
                                WorldExteriorObjects.Kind.BUILDING,
                                WorldExteriorObjects.Kind.BUILDING,
                                WorldExteriorObjects.Kind.SET_DOODAD,
                                WorldExteriorObjects.Kind.SET_DOODAD,
                                WorldExteriorObjects.Kind.SET_DOODAD,
                                WorldExteriorObjects.Kind.SET_DOODAD,
                                WorldExteriorObjects.Kind.SET_DOODAD),
                        kinds);
                var parent = new WorldBasicWmo.Transform(declared(building), 2.0);
                var heading = WorldExteriorObjects.Rotation.heading(parent.cos(), parent.sin());
                var first = set.placements().get(2);
                assertEquals(0, first.ordinal());
                assertEquals(77, first.uniqueId());
                assertEquals(parent.x(1, 2), first.x(), 1e-9);
                assertEquals(parent.y(1, 2), first.y(), 1e-9);
                assertEquals(parent.z(3), first.z(), 1e-9);
                assertEquals(2.0, first.scale(), 0);
                assertRotation(heading, first.rotation());
                var turned = set.placements().get(3);
                assertEquals(1, turned.ordinal());
                assertEquals(1.0, turned.scale(), 1e-9);
                assertRotation(
                        heading.times(WorldExteriorObjects.Rotation.quaternion(0, 0, h, h)),
                        turned.rotation());
                // A quarter turn about local z maps local x to local y before the heading.
                var r = turned.rotation();
                assertEquals(heading.m01(), r.m00(), 1e-12);
                assertEquals(heading.m11(), r.m10(), 1e-12);
                var pitched = set.placements().get(4);
                assertEquals(5, pitched.ordinal());
                assertEquals(77, pitched.uniqueId());
                // A quarter turn about local x maps local z to local -y before the heading.
                assertEquals(-heading.m01(), pitched.rotation().m02(), 1e-12);
                assertEquals(-heading.m11(), pitched.rotation().m12(), 1e-12);
                assertEquals(0, pitched.rotation().m22(), 1e-12);
                assertEquals(78, set.placements().get(5).uniqueId());
                assertEquals(0, set.placements().get(5).ordinal());
                assertEquals(1, set.placements().get(6).ordinal());
            }
        }
    }

    /**
     * An independent oracle in the style of gl-matrix (column vectors, every call post-multiplies
     * the current matrix), used to transcribe the wowdev placement-matrix examples step by step.
     * It never calls {@link WorldExteriorObjects.Rotation}.
     */
    private static final class GlMat4 {
        double[][] m = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};

        GlMat4 times(double[][] b) {
            var out = new double[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    for (int k = 0; k < 4; k++) {
                        out[i][j] += m[i][k] * b[k][j];
                    }
                }
            }
            m = out;
            return this;
        }

        GlMat4 rotate(char axis, double degrees) {
            double t = Math.toRadians(degrees), c = Math.cos(t), s = Math.sin(t);
            return times(
                    switch (axis) {
                        case 'x' -> new double[][] {
                            {1, 0, 0, 0}, {0, c, -s, 0}, {0, s, c, 0}, {0, 0, 0, 1}
                        };
                        case 'y' -> new double[][] {
                            {c, 0, s, 0}, {0, 1, 0, 0}, {-s, 0, c, 0}, {0, 0, 0, 1}
                        };
                        default -> new double[][] {
                            {c, -s, 0, 0}, {s, c, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}
                        };
                    });
        }

        GlMat4 translate(double x, double y, double z) {
            return times(new double[][] {{1, 0, 0, x}, {0, 1, 0, y}, {0, 0, 1, z}, {0, 0, 0, 1}});
        }

        GlMat4 scale(double s) {
            return times(new double[][] {{s, 0, 0, 0}, {0, s, 0, 0}, {0, 0, s, 0}, {0, 0, 0, 1}});
        }

        /** gl-matrix mat4.fromQuat for a unit quaternion (x, y, z, w). */
        GlMat4 quat(double x, double y, double z, double w) {
            return times(
                    new double[][] {
                        {1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y), 0},
                        {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x), 0},
                        {2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y), 0},
                        {0, 0, 0, 1}
                    });
        }

        double[] apply(double x, double y, double z) {
            double[] v = {x, y, z, 1}, out = new double[3];
            for (int i = 0; i < 3; i++) {
                for (int k = 0; k < 4; k++) {
                    out[i] += m[i][k] * v[k];
                }
            }
            return out;
        }

        /**
         * wowdev ADT/v18 (rev 37119) "How to compute a matrix to map M2 to world coordinates",
         * transcribed call by call; MODF uses the same math. Its leading rotateX(90) rotateY(90)
         * turns the Y-up placement frame (32*TILESIZE - x, y, 32*TILESIZE - z) into the native
         * terrain axes (32*TILESIZE - z, 32*TILESIZE - x, y).
         */
        static GlMat4 adtPlacement(
                float x, float y, float z, float rx, float ry, float rz, double scale) {
            double tile = 51200.0 / 3.0;
            return new GlMat4()
                    .rotate('x', 90)
                    .rotate('y', 90)
                    .translate(tile - x, y, tile - z)
                    .rotate('y', ry - 270.0)
                    .rotate('z', -rx)
                    .rotate('x', rz - 90.0)
                    .scale(scale);
        }
    }

    /** The drawn placement maps local (a, b, c) to its origin plus scale * rotation * local. */
    private static double[] world(WorldExteriorObjects.Placement p, double a, double b, double c) {
        var r = p.rotation();
        double s = p.scale();
        return new double[] {
            p.x() + s * (r.m00() * a + r.m01() * b + r.m02() * c),
            p.y() + s * (r.m10() * a + r.m11() * b + r.m12() * c),
            p.z() + s * (r.m20() * a + r.m21() * b + r.m22() * c)
        };
    }

    private static void assertMapsLike(GlMat4 oracle, WorldExteriorObjects.Placement p) {
        double[][] locals = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {2.5, -1.25, 4}};
        for (var v : locals) {
            var expected = oracle.apply(v[0], v[1], v[2]);
            var actual = world(p, v[0], v[1], v[2]);
            for (int i = 0; i < 3; i++) {
                assertEquals(expected[i], actual[i], 1e-9, "axis " + i + " of local " + List.of(
                        v[0], v[1], v[2]));
            }
        }
        var r = p.rotation().rowMajor();
        double det =
                r[0] * (r[4] * r[8] - r[5] * r[7])
                        - r[1] * (r[3] * r[8] - r[5] * r[6])
                        + r[2] * (r[3] * r[7] - r[4] * r[6]);
        assertEquals(1, det, 1e-12);
    }

    @Test
    void mddfPitchRollAndHeadingFollowThePinnedPlacementMatrix() {
        var tilted = new Mddf(MODEL, 1, 0x240, 1234.5f, 87.25f, 2345.75f, 17.5f, -121.25f, 33, 1536);
        var wrapped = new Mddf(MODEL, 2, 0x40, 500, 40, 600, -8, 405, -71, 700);
        var pitched = new Mddf(MODEL, 3, 0x1262, 10, 0, 20, 90, 0, 0, 1024);
        var rolled = new Mddf(MODEL, 4, 0x240, 10, 0, 20, 0, 0, 90, 1024);
        var upright = new Mddf(MODEL, 5, 0x240, 10, 0, 20, 0, 63.5f, 0, 1024);
        var doodads =
                List.of(
                        tilted,
                        wrapped,
                        pitched,
                        rolled,
                        upright,
                        new Mddf(MODEL, 6, 0x340, 1, 2, 3, 0, 0, 0, 1024), // 0x100 is unpinned
                        new Mddf(MODEL, 8, 0x200, 1, 2, 3, 0, 0, 0, 1024)); // name reference
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            try (var set = run(f, obj0(List.of(), doodads))) {
                assertEquals(
                        Map.ofEntries(
                                Map.entry(WorldExteriorObjects.Count.DOODAD_DRAWN, 5),
                                Map.entry(WorldExteriorObjects.Count.DOODAD_UNSUPPORTED, 2),
                                Map.entry(WorldExteriorObjects.Count.MODEL_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_BATCH_DRAWN, 1),
                                Map.entry(WorldExteriorObjects.Count.MODEL_IMAGE_DECODED, 1)),
                        counts(set));
                var drawn = List.of(tilted, wrapped, pitched, rolled, upright);
                assertEquals(drawn.size(), set.placements().size());
                for (int i = 0; i < drawn.size(); i++) {
                    var r = drawn.get(i);
                    var p = set.placements().get(i);
                    assertEquals(r.uid(), p.uniqueId());
                    assertMapsLike(
                            GlMat4.adtPlacement(
                                    r.x(), r.y(), r.z(), r.pitch(), r.heading(), r.roll(),
                                    r.scale() / 1024.0),
                            p);
                }
                // wowdev's axis table: rotation x turns about the West/East axis (native y), so a
                // 90 degree pitch at heading 0 lays the model's up axis in the native x-z plane
                // (along -x); rotation z turns about North/South (native x), laying it in the
                // y-z plane (along +y).
                var up = set.placements().get(2).rotation();
                assertEquals(-1, up.m02(), 1e-12);
                assertEquals(0, up.m12(), 1e-12);
                assertEquals(0, up.m22(), 1e-12);
                var side = set.placements().get(3).rotation();
                assertEquals(0, side.m02(), 1e-12);
                assertEquals(1, side.m12(), 1e-12);
                assertEquals(0, side.m22(), 1e-12);
                // Zero pitch and roll keep the heading-only profile bit for bit.
                var heading =
                        new WorldBasicWmo.Transform(
                                new WorldPlacements.Vec3(10, 0, 20), 63.5f, 1.0);
                assertEquals(
                        WorldExteriorObjects.Rotation.heading(heading.cos(), heading.sin()),
                        set.placements().get(4).rotation());
            }
        }
    }

    @Test
    void moddQuaternionsWithPitchRollAndYawComposeLikeThePinnedPlacementMatrix() {
        // 50 degrees about the axis (1, 2, -0.5), and yaw 30 * pitch 20 * roll 10 as a product.
        double n = Math.sqrt(1 + 4 + 0.25), half = Math.toRadians(25);
        double[] axisAngle = {
            Math.sin(half) / n, 2 * Math.sin(half) / n, -0.5 * Math.sin(half) / n, Math.cos(half)
        };
        double[] euler =
                hamilton(
                        hamilton(axisQuat(0, 0, 1, 30), axisQuat(0, 1, 0, 20)),
                        axisQuat(1, 0, 0, 10));
        var defs =
                List.of(
                        new Modd(0, 3, -4, 5, axisAngle, 0.75f),
                        new Modd(0, -7.5f, 2, 0.5f, euler, 1.25f));
        var chunks = doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, defs);
        var building = new Modf(ROOT, 77, 0xc, 812.5f, 33, 1290.25f, 0, 37.5f, 0, 2048);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            try (var set = run(f, obj0(List.of(building), List.of()))) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(3, set.placements().size());
                for (int i = 0; i < defs.size(); i++) {
                    var d = defs.get(i);
                    // The recorded float components, normalized in double.
                    double[] q = {
                        (float) d.q()[0], (float) d.q()[1], (float) d.q()[2], (float) d.q()[3]
                    };
                    double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
                    // wowdev WMO (rev 37115) MODD: building * T(pos) * fromQuat(q) * S(scale).
                    var oracle =
                            GlMat4.adtPlacement(
                                            building.x(),
                                            building.y(),
                                            building.z(),
                                            building.pitch(),
                                            building.heading(),
                                            building.roll(),
                                            2.0)
                                    .translate(d.x(), d.y(), d.z())
                                    .quat(q[0] / norm, q[1] / norm, q[2] / norm, q[3] / norm)
                                    .scale(d.scale());
                    var p = set.placements().get(1 + i);
                    assertEquals(WorldExteriorObjects.Kind.SET_DOODAD, p.kind());
                    assertEquals(i, p.ordinal());
                    assertMapsLike(oracle, p);
                }
            }
        }
    }

    private static double[] axisQuat(double x, double y, double z, double degrees) {
        double h = Math.toRadians(degrees) / 2, s = Math.sin(h);
        return new double[] {x * s, y * s, z * s, Math.cos(h)};
    }

    /** Hamilton product a * b of quaternions (x, y, z, w): b is applied first. */
    private static double[] hamilton(double[] a, double[] b) {
        return new double[] {
            a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
        };
    }

    /** Files whose reads of {@code shortId} end early (a found, unprotected short read). */
    private static WorldTerrainTextureSourceTest.Files shortRead(long shortId) {
        return new WorldTerrainTextureSourceTest.Files() {
            @Override
            com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                var actual = super.open(id, cancel);
                if (id != shortId) {
                    return actual;
                }
                return new com.vibeminer.files.FileReadStream() {
                    public long length() {
                        return actual.length();
                    }

                    public long position() {
                        return actual.position();
                    }

                    public void seek(long position) {
                        actual.seek(position);
                    }

                    public List<com.vibeminer.files.FileTypes.FileHole> holes() {
                        return actual.holes();
                    }

                    public java.util.concurrent.CompletableFuture<Integer> read(
                            ByteBuffer destination, Cancellation token) {
                        return java.util.concurrent.CompletableFuture.completedFuture(-1);
                    }

                    public void close() {
                        actual.close();
                    }
                };
            }
        };
    }

    @Test
    void doodadInputRefusalsOmitOnlyDoodadsButChangedSourcesFailTheSet() {
        for (long id : new long[] {MODEL, SKIN, MODEL_IMAGE}) {
            try (var f = new Fixture(List.of(), shortRead(id))) {
                putModel(f, MODEL, model());
                var obj0 = obj0(List.of(Modf.of(ROOT, 1, 0x8)), List.of(Mddf.of(MODEL, 2, 0x40)));
                try (var set = run(f, obj0)) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN), "" + id);
                    assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_SOURCE_INPUT));
                    assertEquals(
                            id == MODEL_IMAGE ? 1 : 0,
                            set.count(WorldExteriorObjects.Count.MODEL_IMAGE_UNAVAILABLE));
                    assertEquals(
                            id == MODEL_IMAGE ? 0 : 1,
                            set.count(WorldExteriorObjects.Count.MODEL_UNAVAILABLE));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                }
            }
            try (var f = new Fixture(List.of())) {
                putModel(f, MODEL, model());
                f.files.changedId = id;
                f.files.changeAfterRead = true;
                f.files.sources.put(4L, obj0(List.of(), List.of(Mddf.of(MODEL, 2, 0x40))));
                var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::run);
                assertEquals("SOURCE_BINDING", refusal.site(), "id " + id);
            }
        }
    }

    @Test
    void anUnreadableCutoutOrBlendedImageOmitsOnlyItsSurfaces() {
        for (int blend : new int[] {1, 2}) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), shortRead(IMAGE2))) {
                f.files.sources.put(
                        ROOT,
                        root(
                                1,
                                new long[] {GROUP},
                                List.of(
                                        new Material(0, IMAGE, 0),
                                        new Material(blend, IMAGE2, 0))));
                f.files.sources.put(
                        GROUP,
                        group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 1))));
                // The opaque-only profile drew this building; an unreadable cutout or blended
                // texture must not cost it.
                try (var set = f.run()) {
                    assertEquals(
                            Map.of(
                                    WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                    WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                    WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                    WorldExteriorObjects.Count.BATCH_DRAWN, 1,
                                    WorldExteriorObjects.Count.BATCH_UNREADABLE, 1,
                                    WorldExteriorObjects.Count.IMAGE_DECODED, 1,
                                    WorldExteriorObjects.Count.IMAGE_UNAVAILABLE, 1),
                            counts(set),
                            "blend " + blend);
                    assertEquals(
                            List.of(
                                    new WorldExteriorObjects.DrawRange(
                                            0, 3, 0, 0, WorldExteriorObjects.OPAQUE)),
                            set.models().getFirst().meshes().getFirst().ranges());
                }
            }
        }
        // The opaque-only profile's own reads keep failing the whole set, as before.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), shortRead(IMAGE))) {
            var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::run);
            assertEquals("SOURCE_INPUT", refusal.site());
        }
    }

    @Test
    void cutoutAndBlendedSurfacesNeverCapTheOpaqueBudget() {
        // A root whose opaque geometry exactly fills the vertex cap, plus a cutout batch in a
        // second group: the opaque outcome is unchanged and the cutout draws from its own budget
        // or is capped there, never capping the root.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            int vertices = 16_384;
            var groups = new long[6];
            for (int g = 0; g < 6; g++) {
                groups[g] = GROUP + 10 + g;
                f.files.sources.put(
                        groups[g], group(EXT, vertices, 1, List.of(new Batch(0, 3, 0, 0))));
            }
            f.files.sources.put(
                    ROOT,
                    root(
                            7,
                            java.util.stream.LongStream.concat(
                                            java.util.Arrays.stream(groups),
                                            java.util.stream.LongStream.of(GROUP + 20))
                                    .toArray(),
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP + 20, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 1))));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(7, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                assertEquals(16_384 * 6 + 3, set.vertices());
            }
        }
    }

    @Test
    void cutoutRangesAreDroppedBeforeAnOpaqueBuildingIsCappedAtTheDrawBudget() {
        // A group of 64 opaque batches and a group with one cutout batch: 64 placements cost
        // exactly the 4,096-draw budget under the opaque-only profile, 4,160 with the cutout.
        var batches = new ArrayList<Batch>();
        for (int b = 0; b < 64; b++) {
            batches.add(new Batch(b * 3, 3, 0, 0));
        }
        var cutout = group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 1)));
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 64; i++) {
            records.add(Modf.of(ROOT, i + 1, 0x8));
        }
        try (var f = new Fixture(records)) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, GROUP + 1},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 64, batches));
            f.files.sources.put(GROUP + 1, cutout);
            putModel(f, MODEL, model());
            f.files.sources.put(4L, obj0(records, List.of(Mddf.of(MODEL, 900, 0x40))));
            try (var set = f.run()) {
                assertEquals(64, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                assertEquals(64, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                // The cutout-only group is dropped whole; the opaque group is untouched.
                assertEquals(1, set.models().getFirst().meshes().size());
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                var mesh = set.models().getFirst().meshes().getFirst();
                assertEquals(64, mesh.ranges().size());
                assertEquals(64 * 3, mesh.indices().remaining());
                assertTrue(
                        mesh.ranges().stream()
                                .allMatch(r -> r.blend() == WorldExteriorObjects.OPAQUE));
                // The budget is full, so the doodad is capped, never a building.
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
            }
        }
        // One placement fewer: the cutout range fits (63 * 65 = 4,095 draws) and is kept.
        try (var f = new Fixture(records.subList(0, 63))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, GROUP + 1},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 64, batches));
            f.files.sources.put(GROUP + 1, cutout);
            try (var set = f.run()) {
                assertEquals(63, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
            }
        }
    }
}
