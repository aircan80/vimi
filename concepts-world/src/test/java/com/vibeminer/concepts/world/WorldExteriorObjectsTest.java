package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Arrays;
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
            int scale,
            float extent) {
        Modf(
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
            this(id, uid, flags, x, y, z, pitch, heading, roll, scale, 10);
        }

        static Modf of(long id, long uid, int flags) {
            return new Modf(id, uid, flags, 100, 20, 300, 0, 90, 0, 2048);
        }

        /** The same placement with recorded MODF bounds of half-size {@code extent}. */
        Modf sized(float half) {
            return new Modf(id, uid, flags, x, y, z, pitch, heading, roll, scale, half);
        }

        byte[] bytes() {
            var b = buffer(64);
            b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
            b.put(floats(pitch, heading, roll))
                    .put(floats(-extent, -extent, -extent, extent, extent, extent));
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

    /**
     * One batch: first index, index count, MOBA flags byte, material byte and the sixth bounds
     * word, which holds the material index under MOBA flag 0x2.
     */
    record Batch(int first, int count, int flags, int material, int large) {
        Batch(int first, int count, int flags, int material) {
            this(first, count, flags, material, 0);
        }
    }

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
            moba.putShort(i * 24 + 10, (short) b.large())
                    .putInt(i * 24 + 12, b.first())
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
            return run(WorldExteriorObjects.ModelReader.STANDARD);
        }

        WorldExteriorObjects.TileObjects run(WorldExteriorObjects.ImageDetail detail) {
            return WorldExteriorObjects.prepare(
                    engine,
                    scope,
                    TILE,
                    Cancellation.NONE,
                    WorldExteriorObjects.ModelReader.STANDARD,
                    detail);
        }

        WorldExteriorObjects.TileObjects run(WorldExteriorObjects.ModelReader reader) {
            return WorldExteriorObjects.prepare(engine, scope, TILE, Cancellation.NONE, reader);
        }

        public void close() {
            engine.closeAsync().join();
            nativeMap.close();
        }
    }

    /** Mip-chain sources describe decoded images, not outcomes; they have their own test. */
    private static final java.util.Set<WorldExteriorObjects.Count> MIP_SOURCES =
            java.util.EnumSet.of(
                    WorldExteriorObjects.Count.IMAGE_MIPS_FILE,
                    WorldExteriorObjects.Count.IMAGE_MIPS_PARTIAL,
                    WorldExteriorObjects.Count.IMAGE_MIPS_GENERATED);

    /** Non-zero outcome buckets. */
    private static Map<WorldExteriorObjects.Count, Integer> counts(
            WorldExteriorObjects.TileObjects set) {
        var result =
                new EnumMap<WorldExteriorObjects.Count, Integer>(WorldExteriorObjects.Count.class);
        for (var c : WorldExteriorObjects.Count.values()) {
            if (set.count(c) != 0 && !MIP_SOURCES.contains(c)) {
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
                        new Modf(ROOT, 4, 0x8, 1, 2, 3, 5, 0, 0, 1024), // pitch: drawn (#268)
                        new Modf(ROOT, 5, 0x8, 1, 2, 3, 0, 0, 5, 1024), // roll: drawn (#268)
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
                            WorldExteriorObjects.Count.PLACEMENT_DRAWN, 5,
                            WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED, 5,
                            WorldExteriorObjects.Count.PLACEMENT_SHARED_DUPLICATE, 1,
                            WorldExteriorObjects.Count.PLACEMENT_CONFLICTING, 2,
                            WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                            WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                            WorldExteriorObjects.Count.BATCH_DRAWN, 1,
                            WorldExteriorObjects.Count.IMAGE_DECODED, 1),
                    counts(set));
            assertPlacementsAccounted(set, records.size());
            assertEquals(
                    List.of(0, 3, 4, 6, 7),
                    set.placements().stream()
                            .map(WorldExteriorObjects.Placement::ordinal)
                            .toList());
            assertEquals(1.0, set.placements().get(3).scale(), 0);
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
            // MOBA flags 2: the material index is the sixth bounds word, not the byte.
            batches.add(new Batch(21, 3, 2, 0, 1));
            batches.add(new Batch(24, 3, 0, 9)); // material out of range
            batches.add(new Batch(27, 3, 2, 0, 9)); // large material index out of range
            batches.add(new Batch(30, 3, 3, 0, 0)); // any other flag combination
            f.files.sources.put(GROUP, group(EXT, 3, 11, batches));
            f.files.sources.put(401L, join(new byte[] {'B', 'L', 'P', '1'}, new byte[200]));
            f.files.sources.put(402L, blp(4, 4, 1, 0));
            f.files.protectedId = 402;
            try (var set = f.run()) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 3,
                                WorldExteriorObjects.Count.BATCH_BLENDED, 2,
                                WorldExteriorObjects.Count.BATCH_UNSUPPORTED, 5,
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
                                        3, 3, 1, 1, WorldExteriorObjects.BLENDED),
                                new WorldExteriorObjects.DrawRange(
                                        6, 3, 1, 1, WorldExteriorObjects.BLENDED)),
                        set.models().getFirst().meshes().getFirst().ranges());
            }
        }
    }

    /**
     * A newer exterior group, as in recent world maps: MPY2 triangle records instead of MOPY, a
     * second MOTV and MOCV declared by MOGP flags 0x2000000 and 0x1000000, and one batch per
     * material using the large MOBA material index (flag 0x2).
     */
    private static byte[] modernGroup(int materials) {
        var classic = group(EXT, 3, materials, List.of());
        int body = indexOf(classic, "MOGP") + 8;
        var h = buffer(68);
        h.put(classic, body, 68);
        h.putInt(8, EXT | 0x4 | 0x1000000 | 0x2000000);
        h.putShort(44, (short) materials);
        var moba = buffer(24 * materials);
        for (int i = 0; i < materials; i++) {
            moba.putShort(i * 24 + 10, (short) i)
                    .putInt(i * 24 + 12, i * 3)
                    .putShort(i * 24 + 16, (short) 3)
                    .putShort(i * 24 + 20, (short) 2)
                    .put(i * 24 + 22, (byte) 2);
        }
        var uv = floats(0, 0, 1, 0, 0, 1);
        var colors = new byte[12];
        int movt = indexOf(classic, "MOVT"), mopy = indexOf(classic, "MOPY");
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                h.array(),
                                chunk("MOGX", new byte[4]),
                                chunk("MPY2", new byte[materials * 4]),
                                // MOVT, MONR, MOTV and MOVI exactly as the classic group has them.
                                Arrays.copyOfRange(classic, movt, mopy),
                                chunk("MOBA", moba.array()),
                                chunk("MOCV", colors),
                                chunk("MOTV", uv),
                                chunk("MOCV", colors))));
    }

    @Test
    void newerGroupsWithMpy2ExtraSetsAndLargeMaterialIndicesAreDrawn() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP, 0, 0, 0},
                            List.of(new Material(0, IMAGE, 0), new Material(0, IMAGE2, 0))));
            f.files.sources.put(GROUP, modernGroup(2));
            try (var set = f.run()) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_DRAWN, 1,
                                WorldExteriorObjects.Count.ROOT_DRAWN, 1,
                                WorldExteriorObjects.Count.GROUP_DRAWN, 1,
                                WorldExteriorObjects.Count.BATCH_DRAWN, 2,
                                WorldExteriorObjects.Count.IMAGE_DECODED, 2),
                        counts(set));
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE),
                                new WorldExteriorObjects.DrawRange(
                                        3, 3, 1, 1, WorldExteriorObjects.OPAQUE)),
                        set.models().getFirst().meshes().getFirst().ranges());
            }
        }
    }

    @Test
    void mipSelectionPrefersLargestUpTo256AndFallsBackNearThePixelBudget() {
        assertEquals(0, WorldImageBudget.levelFor(256, 256, 256, 9));
        assertEquals(2, WorldImageBudget.levelFor(1024, 512, 256, 11));
        assertEquals(1, WorldImageBudget.levelFor(512, 8, 256, 10));
        assertEquals(0, WorldImageBudget.levelFor(4, 4, 256, 3));
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
    void imagesCarryTheirFullChainUsingTheFilesOwnMipsWhenTheyArePresent() {
        // 4x4 has levels 4x4, 2x2, 1x1. Each fixture level is one flat colour.
        for (int declared = 1; declared <= 3; declared++) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(IMAGE, blp(4, 4, declared, 0xf800));
                try (var set = f.run()) {
                    var image = set.images().getFirst();
                    assertEquals(4, image.width());
                    assertEquals(
                            com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(4, 4) * 4,
                            image.rgba().remaining());
                    var expected =
                            switch (declared) {
                                case 3 ->
                                        com.vibeminer.rendering.world.ObjectTextureMips.Source.FILE;
                                case 2 ->
                                        com.vibeminer.rendering.world.ObjectTextureMips.Source
                                                .PARTIAL;
                                default ->
                                        com.vibeminer.rendering.world.ObjectTextureMips.Source
                                                .GENERATED;
                            };
                    assertEquals(expected, image.mips());
                    assertEquals(
                            declared == 3 ? 1 : 0,
                            set.count(WorldExteriorObjects.Count.IMAGE_MIPS_FILE));
                    assertEquals(
                            declared == 2 ? 1 : 0,
                            set.count(WorldExteriorObjects.Count.IMAGE_MIPS_PARTIAL));
                    assertEquals(
                            declared == 1 ? 1 : 0,
                            set.count(WorldExteriorObjects.Count.IMAGE_MIPS_GENERATED));
                    // Opaque: every level of the chain is red with alpha 255.
                    var rgba = image.rgba();
                    for (int at = 0; at < rgba.remaining(); at += 4) {
                        assertEquals(255, rgba.get(at) & 255);
                        assertEquals(255, rgba.get(at + 3) & 255);
                    }
                    // Budgets still count the drawn top level only.
                    assertEquals(16, set.pixels());
                }
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
    void tilePixelBudgetLowersLevelsInsteadOfDroppingImages() {
        // 15 x 256^2 + 256x128 chains (1,354,406 texels) leave 43,695 of the 1,398,101-texel
        // chain budget, so the last two images still
        // draw at 128^2 instead of being dropped. 4016 records level 1, so its own mip is read
        // again from the file; 4017 records only level 0, so it is reduced by a 2x2 box.
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
                assertEquals(18, set.images().size());
                assertEquals(18, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_CAPPED));
                for (int i = 0; i < 15; i++) {
                    assertEquals(256, set.images().get(i).width(), "image " + i);
                }
                var recorded = set.images().get(16);
                assertEquals(4016L, recorded.id());
                assertEquals(1, recorded.mipLevel());
                assertEquals(128, recorded.width());
                // Registered at 128 (its recorded level 1); its chain takes the recorded level 2
                // below it from the bytes kept since registration, so one read in all (#265).
                assertEquals(1, f.files.opens.get(4016L).get());
                assertEquals(
                        com.vibeminer.rendering.world.ObjectTextureMips.Source.PARTIAL,
                        recorded.mips());
                var reduced = set.images().get(17);
                assertEquals(4017L, reduced.id());
                assertEquals(1, reduced.mipLevel());
                assertEquals(128, reduced.width());
                assertEquals(128, reduced.height());
                assertEquals(15 * 65536 + 32768 + 2 * 16384, set.pixels());
                // Single-level files are registered at their only level: one read each.
                assertEquals(1, f.files.opens.get(4000L).get());
            }
            // The HIGH tier doubles the pixel budget: every image keeps its 256 level.
            try (var set = f.run(WorldExteriorObjects.ImageDetail.HIGH)) {
                assertEquals(18, set.images().size());
                assertTrue(set.images().stream().allMatch(i -> i.width() == 256));
                assertEquals(17 * 65536 + 32768, set.pixels());
            }
        }
    }

    @Test
    void largerBuildingsAreServedFirstAndEveryImageStillDraws() {
        // obj0 records a small building with 16 distinct 256^2 images first and a large one
        // with a single 256^2 image second. The large building is admitted and upgraded first:
        // its image is full size, and the small building's images all draw, some smaller.
        long smallRoot = ROOT, largeRoot = 110, largeGroup = 210, largeImage = 5100;
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 16; i++) {
            materials.add(new Material(0, 5000 + i, 0));
            batches.add(new Batch(i * 3, 3, 0, i));
        }
        var small = new Modf(smallRoot, 1, 0x8, 100, 20, 300, 0, 90, 0, 2048).sized(5);
        var large = new Modf(largeRoot, 2, 0x8, 400, 20, 300, 0, 90, 0, 2048).sized(200);
        try (var f = new Fixture(List.of(small, large))) {
            f.files.sources.put(smallRoot, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 16, batches));
            for (int i = 0; i < 16; i++) {
                f.files.sources.put(5000L + i, blp(256, 256, 3, i));
            }
            f.files.sources.put(
                    largeRoot,
                    root(1, new long[] {largeGroup}, List.of(new Material(0, largeImage, 0))));
            f.files.sources.put(largeGroup, group(EXT));
            f.files.sources.put(largeImage, blp(256, 256, 3, 7));
            try (var set = f.run()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                assertEquals(17, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertTrue(set.pixels() <= WorldExteriorObjects.MAX_PIXELS);
                var big =
                        set.images().stream()
                                .filter(i -> i.id() == largeImage)
                                .findFirst()
                                .orElseThrow();
                assertEquals(256, big.width(), "the large building keeps full resolution");
                assertTrue(
                        set.images().stream()
                                .anyMatch(i -> i.id() != largeImage && i.width() < 256),
                        "the small building absorbs the shortfall");
                assertEquals(
                        List.of(0, 1),
                        set.placements().stream()
                                .map(WorldExteriorObjects.Placement::ordinal)
                                .toList(),
                        "placements keep obj0 source order");
                assertEquals(largeRoot, set.models().getFirst().rootId(), "admitted first");
            }
        }
    }

    @Test
    void anUndecodableRegistrationLevelFallsBackToAnotherRecordedLevel() {
        // A 256^2 file whose level 1 runs past the file: registration tries level 1 first, then
        // level 0, which the old 256-side profile drew; the image is not lost.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            byte[] bad = blp(256, 256, 2, 0xf800);
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(88, 1 << 16);
            f.files.sources.put(IMAGE, bad);
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_DECODED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_UNDECODABLE));
                assertEquals(256, set.images().getFirst().width());
                assertEquals(0, set.images().getFirst().mipLevel());
            }
        }
    }

    @Test
    void aRefusedSecondReadOfACutoutImageKeepsItsRegisteredLevel() {
        // The cutout image registers at 128 (level 1) and is allotted 256 (level 0); its second
        // read is refused at SOURCE_INPUT. The tile keeps the registered level instead of failing.
        var files =
                new WorldTerrainTextureSourceTest.Files() {
                    @Override
                    com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                        var actual = super.open(id, cancel);
                        if (id != IMAGE2 || opens.get(id).get() < 2) {
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
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(
                    GROUP, group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 1))));
            f.files.sources.put(IMAGE2, blp(256, 256, 3, 0x001f));
            // Normally the bytes kept since registration serve the relevel (#265): no second
            // read, and the allotted level is drawn.
            try (var set = f.run()) {
                var cutout =
                        set.images().stream()
                                .filter(i -> i.id() == IMAGE2)
                                .findFirst()
                                .orElseThrow();
                assertEquals(256, cutout.width(), "allotted level from the kept bytes");
                assertEquals(1, f.files.opens.get(IMAGE2).get(), "no second read");
            }
            // With nothing retained, the second read happens and its refusal keeps the level.
            f.files.opens.clear();
            try (var set = retaining(f, 0)) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                var cutout =
                        set.images().stream()
                                .filter(i -> i.id() == IMAGE2)
                                .findFirst()
                                .orElseThrow();
                assertEquals(128, cutout.width(), "registered level kept");
                assertEquals(2, f.files.opens.get(IMAGE2).get(), "the second read was attempted");
                assertEquals(0, set.retainedSourceBytes);
            }
        }
    }

    @Test
    void theChainReusesTheRegisteredLevelInsteadOfDecodingItAgain() {
        // A 256^2 file recording 256 and 128: registered at 128 (level 1), allotted 256. With
        // nothing retained, the relevel needs a second read; the chain's only lower level is the
        // registered one, so it comes from the first decode and no third read happens.
        for (int materialClass : new int[] {0, 1}) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(
                        ROOT,
                        root(
                                1,
                                new long[] {GROUP},
                                List.of(new Material(materialClass, IMAGE, 0))));
                f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
                f.files.sources.put(IMAGE, blp(256, 256, 2, 0x001f));
                try (var set = retaining(f, 0)) {
                    var image = set.images().getFirst();
                    assertEquals(256, image.width(), "allotted level");
                    assertEquals(2, f.files.opens.get(IMAGE).get(), "class " + materialClass);
                    assertEquals(2, set.levelDecodes, "registration and relevel only");
                    assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_MIPS_PARTIAL));
                    // Level 1 is the file's own 128^2 (blue), not a reduction artefact.
                    var rgba = image.rgba();
                    int at =
                            com.vibeminer.rendering.world.ObjectTextureMips.levelOffset(
                                    256, 256, 1);
                    assertEquals(0, rgba.get(at) & 255);
                    assertEquals(255, rgba.get(at + 2) & 255);
                }
            }
        }
    }

    @Test
    void aReusedLevelMidChainIsDecodedOnceForEveryMaterialClass() {
        // A 1024^2 file recording all 11 levels registers at 128 (level 3) and is allotted 256
        // (level 2). The chain below 256 takes level 3 from the registration decode and decodes
        // levels 4 to 10 once each: 1 + 1 + 7 decodes; decoding level 3 again would make 10.
        for (int materialClass : new int[] {0, 1, 2}) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(
                        ROOT,
                        root(
                                1,
                                new long[] {GROUP},
                                List.of(new Material(materialClass, IMAGE, 0))));
                f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
                f.files.sources.put(IMAGE, blp(1024, 1024, 11, 0x07e0));
                try (var set = f.run()) {
                    var image = set.images().getFirst();
                    assertEquals(256, image.width(), "allotted level, class " + materialClass);
                    assertEquals(2, image.mipLevel());
                    assertEquals(9, set.levelDecodes, "class " + materialClass);
                    assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_MIPS_FILE));
                    assertEquals(1, f.files.opens.get(IMAGE).get(), "the kept bytes serve");
                    // Level 1 of the chain (the reused 128^2) is the file's green.
                    int at =
                            com.vibeminer.rendering.world.ObjectTextureMips.levelOffset(
                                    256, 256, 1);
                    assertEquals(255, image.rgba().get(at + 1) & 255);
                }
            }
        }
    }

    @Test
    void registeredPixelsOfTheWrongLengthAreNotReused() {
        byte[] level = new byte[4 * 4 * 4];
        assertTrue(level == WorldExteriorObjects.reusable(level, 4, 4));
        assertNull(WorldExteriorObjects.reusable(level, 8, 4), "a level of another size");
        assertNull(WorldExteriorObjects.reusable(null, 4, 4), "released pixels");
    }

    private static WorldExteriorObjects.TileObjects retaining(Fixture f, long limit) {
        return WorldExteriorObjects.prepare(
                f.engine,
                f.scope,
                TILE,
                Cancellation.NONE,
                WorldExteriorObjects.ModelReader.STANDARD,
                WorldExteriorObjects.ImageDetail.STANDARD,
                limit);
    }

    @Test
    void retainedImageSourcesStayWithinTheirBoundAndCountEachFileOnce() {
        // One 256^2 file with three recorded levels, drawn opaque and cutout: two images (one per
        // material class) from one source file.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE2, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(
                    GROUP, group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 1))));
            byte[] file = blp(256, 256, 3, 0x001f);
            f.files.sources.put(IMAGE2, file);
            try (var set = retaining(f, WorldExteriorObjects.RETAINED_SOURCE_BYTES)) {
                assertEquals(2, set.images().size(), "one image per material class");
                assertEquals(file.length, set.retainedSourceBytes, "the file is kept once");
            }
            // A bound below one file keeps nothing, yet the tile draws the same images.
            try (var set = retaining(f, file.length - 1)) {
                assertEquals(0, set.retainedSourceBytes);
                assertEquals(2, set.images().size());
                assertTrue(set.images().stream().allMatch(i -> i.width() == 256));
            }
        }
        // Many files: the kept bytes never pass the bound, and the drawn result is unchanged.
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 18; i++) {
            materials.add(new Material(0, 4000 + i, 0));
            batches.add(new Batch(i * 3, 3, 0, i));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 18, batches));
            for (int i = 0; i < 18; i++) {
                f.files.sources.put(4000L + i, blp(256, 256, 3, i));
            }
            long one = f.files.sources.get(4000L).length, bound = one * 5 + one / 2;
            java.util.List<Integer> unbounded;
            try (var set = f.run()) {
                unbounded = set.images().stream().map(i -> i.width()).toList();
            }
            try (var set = retaining(f, bound)) {
                assertTrue(set.retainedSourceBytes <= bound, "kept " + set.retainedSourceBytes);
                assertEquals(one * 5, set.retainedSourceBytes, "whole files only, up to the bound");
                assertEquals(unbounded, set.images().stream().map(i -> i.width()).toList());
            }
        }
    }

    @Test
    void cutoutPoolSharesItsOwnBudgetFairly() {
        // 12 distinct 256^2 cutout images in the 256 Ki cutout pool: all draw, at 128^2
        // (12 x 16 Ki = 192 Ki); the next round to 256^2 would need 48 Ki more each, and the
        // first of them (priority order) takes it.
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        materials.add(new Material(0, IMAGE, 0));
        batches.add(new Batch(0, 3, 0, 0));
        for (int i = 0; i < 12; i++) {
            materials.add(new Material(1, 6000 + i, 0));
            batches.add(new Batch((i + 1) * 3, 3, 0, i + 1));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 13, batches));
            for (int i = 0; i < 12; i++) {
                f.files.sources.put(6000L + i, blp(256, 256, 3, i));
            }
            try (var set = f.run()) {
                assertEquals(13, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                long cutoutPixels = 0;
                int full = 0;
                for (var image : set.images()) {
                    if (image.id() >= 6000) {
                        cutoutPixels += (long) image.width() * image.height();
                        full += image.width() == 256 ? 1 : 0;
                    }
                }
                assertTrue(cutoutPixels <= WorldExteriorObjects.MAX_MATERIAL_PIXELS);
                assertEquals(1, full, "only the highest-priority cutout image reaches 256");
                assertEquals(
                        256,
                        set.images().stream()
                                .filter(i -> i.id() >= 6000)
                                .findFirst()
                                .orElseThrow()
                                .width());
            }
        }
    }

    @Test
    void boxReductionWeightsColourByAlphaAndTruncatesOddSides() throws Exception {
        // 3x2 RGBA: a red opaque texel beside a transparent blue one, etc.
        byte[] rgba = {
            (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 9, 9, 9, (byte) 255,
            (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 9, 9, 9, (byte) 255
        };
        var make =
                WorldExteriorObjects.Image.class.getDeclaredConstructor(
                        long.class, int.class, int.class, int.class, byte[].class);
        make.setAccessible(true);
        var image = make.newInstance(1L, 3, 2, 0, rgba);
        var reduce =
                Class.forName("com.vibeminer.concepts.world.WorldExteriorObjects$Builder")
                        .getDeclaredMethod("reduce", WorldExteriorObjects.Image.class, int.class);
        reduce.setAccessible(true);
        var out = (WorldExteriorObjects.Image) reduce.invoke(null, image, 1);
        assertEquals(1, out.width(), "the odd third column is dropped");
        assertEquals(1, out.height());
        assertEquals(1, out.mipLevel());
        var px = new byte[4];
        out.rgba().get(px);
        assertEquals(255, px[0] & 255, "transparent blue does not tint the colour");
        assertEquals(0, px[2] & 255);
        assertEquals(128, px[3] & 255, "alpha is the plain mean");
    }

    @Test
    void recordedSizeIsTheBoundsDiagonalAndZeroWhenNotFinite() {
        var unit = declared(new Modf(ROOT, 1, 0x8, 0, 0, 0, 0, 0, 0, 1024).sized(1));
        assertEquals(Math.sqrt(12), WorldExteriorObjects.recordedSize(unit), 1e-9);
        var inverted = declared(new Modf(ROOT, 1, 0x8, 0, 0, 0, 0, 0, 0, 1024).sized(-1));
        assertEquals(
                Math.sqrt(12),
                WorldExteriorObjects.recordedSize(inverted),
                1e-9,
                "an inverted box has the same diagonal; it is never negative");
        // Non-finite bounds never reach it: obj0 parsing refuses them, and recordedSize would
        // still answer 0 rather than NaN.
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> declared(new Modf(ROOT, 1, 0x8, 0, 0, 0, 0, 0, 0, 1024).sized(bad)));
        }
        // Ties keep obj0 order: equal sizes are admitted in source order (all other tests use
        // the same authored bounds and rely on it).
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

    /**
     * The authored model with three bones appended (bone 0 plain, bone 1 carrying {@code
     * billboardFlags}, bone 2 a plain child of bone 1) and every global vertex weighted fully to
     * one bone: globals 0, 1 and 2 to bone 0, global 3 (SKIN local 3) to bone 2.
     */
    private static byte[] billboardModel(long rootFlags, long billboardFlags) {
        var base = model();
        int md20 = 40, rel = (base.length - md20 + 3) & ~3, stride = 88;
        var bytes = Arrays.copyOf(base, md20 + rel + 3 * stride);
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(36, rel + 3 * stride); // MD21 chunk size
        b.putInt(md20 + 44, 3).putInt(md20 + 48, rel); // bones
        long[] flags = {rootFlags, billboardFlags, 0};
        int[] parents = {-1, 0, 1};
        for (int i = 0; i < 3; i++) {
            int at = md20 + rel + i * stride;
            b.putInt(at, -1).putInt(at + 4, (int) flags[i]).putShort(at + 8, (short) parents[i]);
        }
        int vertices = md20 + b.getInt(md20 + 64);
        int[] bones = {0, 0, 0, 2};
        for (int v = 0; v < 4; v++) {
            b.putInt(vertices + v * 48 + 12, 255).putInt(vertices + v * 48 + 16, bones[v]);
        }
        return bytes;
    }

    /**
     * A SKIN over the authored lookup (2, 0, 1, 3) with two sections and one batch each: section 0
     * holds (0, 1, 2) and (0, 2, 3), section 1 holds (1, 3, 2). Local 3 is the billboarded vertex.
     */
    private static byte[] twoSectionSkin() {
        return twoSectionSkin(false);
    }

    /**
     * As {@link #twoSectionSkin()}; with {@code unsupportedBatch} a third batch over section 0
     * records no texture (texture count 0), which the profile refuses as MODEL_BATCH_UNSUPPORTED.
     */
    private static byte[] twoSectionSkin(boolean unsupportedBatch) {
        int batches = unsupportedBatch ? 3 : 2;
        int[] indices = {0, 1, 2, 0, 2, 3, 1, 3, 2};
        int[][] sections = {{0, 6}, {6, 3}};
        int vertices = 0x38, triangles = vertices + 8, properties = triangles + 2 * indices.length;
        int sectionAt = properties + 16, batchAt = sectionAt + 48 * 2, end = batchAt + 24 * batches;
        var b = buffer(end);
        b.put(new byte[] {'S', 'K', 'I', 'N'});
        b.putInt(4).putInt(vertices).putInt(indices.length).putInt(triangles);
        b.putInt(4).putInt(properties).putInt(2).putInt(sectionAt).putInt(batches).putInt(batchAt);
        b.position(vertices);
        b.putShort((short) 2).putShort((short) 0).putShort((short) 1).putShort((short) 3);
        for (int i : indices) {
            b.putShort((short) i);
        }
        for (int i = 0; i < 4; i++) {
            b.putInt(0x03020100);
        }
        for (var s : sections) {
            int at = b.position();
            b.putShort(at + 6, (short) 4).putShort(at + 8, (short) s[0]);
            b.putShort(at + 10, (short) s[1]);
            b.position(at + 48);
        }
        for (int s = 0; s < 2; s++) {
            int at = batchAt + s * 24;
            b.putShort(at + 4, (short) s).putShort(at + 6, (short) s).putShort(at + 8, (short) -1);
            b.putShort(at + 14, (short) 1);
        }
        if (unsupportedBatch) {
            b.putShort(batchAt + 48 + 8, (short) -1); // section 0, no colour, texture count 0
        }
        return b.array();
    }

    @Test
    void billboardWeightedTrianglesAreLeftOutAndTheRestOfTheModelDraws() {
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, billboardModel(0, 0x8));
            f.files.sources.put(SKIN, twoSectionSkin());
            try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.DOODAD_DRAWN, 1,
                                WorldExteriorObjects.Count.MODEL_DRAWN, 1,
                                WorldExteriorObjects.Count.MODEL_BATCH_DRAWN, 1,
                                WorldExteriorObjects.Count.MODEL_BATCH_BILLBOARD, 1,
                                WorldExteriorObjects.Count.MODEL_BATCH_TRIMMED, 1,
                                WorldExteriorObjects.Count.MODEL_TRIANGLE_BILLBOARD, 2,
                                WorldExteriorObjects.Count.MODEL_IMAGE_DECODED, 1),
                        counts(set));
                var mesh = set.models().getFirst().meshes().getFirst();
                // (0, 2, 3) of section 0 and all of section 1 touch local 3, a child of bone 1.
                assertEquals(List.of(0, 1, 2), toList(mesh.indices()));
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE)),
                        mesh.ranges());
            }
        }
        // Without a billboard bone both sections draw in full.
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, billboardModel(0, 0));
            f.files.sources.put(SKIN, twoSectionSkin());
            try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.MODEL_BATCH_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.MODEL_BATCH_BILLBOARD));
                var mesh = set.models().getFirst().meshes().getFirst();
                assertEquals(9, mesh.indices().remaining());
            }
        }
    }

    @Test
    void aModelWhoseEveryTriangleBillboardsIsStillRefused() {
        for (long flags : new long[] {0x8, 0x10, 0x20, 0x40}) {
            try (var f = new Fixture(List.of())) {
                putModel(f, MODEL, billboardModel(flags, 0));
                f.files.sources.put(SKIN, twoSectionSkin());
                try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                    assertEquals(
                            Map.of(
                                    WorldExteriorObjects.Count.DOODAD_FAILED_MODEL, 1,
                                    WorldExteriorObjects.Count.MODEL_ALL_BILLBOARD, 1,
                                    WorldExteriorObjects.Count.MODEL_BATCH_BILLBOARD, 2,
                                    WorldExteriorObjects.Count.MODEL_TRIANGLE_BILLBOARD, 3),
                            counts(set),
                            "flags 0x" + Long.toHexString(flags));
                    assertTrue(set.models().isEmpty());
                }
            }
        }
    }

    @Test
    void anAllBillboardModelWithAnUnsupportedBatchKeepsBothBatchCounts() {
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, billboardModel(0x8, 0));
            f.files.sources.put(SKIN, twoSectionSkin(true));
            try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x40))))) {
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.DOODAD_FAILED_MODEL, 1,
                                WorldExteriorObjects.Count.MODEL_ALL_BILLBOARD, 1,
                                WorldExteriorObjects.Count.MODEL_BATCH_BILLBOARD, 2,
                                WorldExteriorObjects.Count.MODEL_BATCH_UNSUPPORTED, 1,
                                WorldExteriorObjects.Count.MODEL_TRIANGLE_BILLBOARD, 3),
                        counts(set));
                assertTrue(set.models().isEmpty());
            }
        }
    }

    @Test
    void billboardBonesIncludeDescendantsAndSurviveParentCycles() {
        var zero = new com.vibeminer.formats.models.M2Types.Vec3(0, 0, 0);
        var bones =
                List.of(
                        new com.vibeminer.formats.models.M2Types.Bone(-1, 0x200, -1, 0, zero),
                        new com.vibeminer.formats.models.M2Types.Bone(-1, 0x10, 0, 0, zero),
                        new com.vibeminer.formats.models.M2Types.Bone(-1, 0, 1, 0, zero),
                        new com.vibeminer.formats.models.M2Types.Bone(-1, 0, 4, 0, zero),
                        new com.vibeminer.formats.models.M2Types.Bone(-1, 0, 3, 0, zero));
        var set = WorldExteriorObjects.billboardBones(bones);
        assertEquals("{1, 2}", set.toString());
        assertTrue(WorldExteriorObjects.billboardBones(bones.subList(3, 5)).isEmpty());
        var vertex =
                new com.vibeminer.formats.models.M2Types.Vertex(
                        zero,
                        0x0000ff00L,
                        0x00000200L,
                        zero,
                        new com.vibeminer.formats.models.M2Types.Vec2(0, 0),
                        new com.vibeminer.formats.models.M2Types.Vec2(0, 0));
        assertTrue(WorldExteriorObjects.billboardWeighted(vertex, set));
        // A zero weight on a billboard bone does not count.
        var unweighted =
                new com.vibeminer.formats.models.M2Types.Vertex(
                        zero,
                        0x000000ffL,
                        0x00000200L,
                        zero,
                        new com.vibeminer.formats.models.M2Types.Vec2(0, 0),
                        new com.vibeminer.formats.models.M2Types.Vec2(0, 0));
        assertTrue(!WorldExteriorObjects.billboardWeighted(unweighted, set));
    }

    @Test
    void unexpectedReaderFailuresAreCountedAndKeptForTheCallerToLog() {
        var bug = new IllegalStateException("Authored reader bug");
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(MODEL + 1, model());
            var doodads = List.of(Mddf.of(MODEL + 1, 1, 0x40), Mddf.of(MODEL, 2, 0x40));
            f.files.sources.put(4L, obj0(List.of(), doodads));
            WorldExteriorObjects.ModelReader failing =
                    (id, raw, token) -> {
                        if (id == MODEL + 1) {
                            throw bug;
                        }
                        return WorldExteriorObjects.ModelReader.STANDARD.read(id, raw, token);
                    };
            try (var set = f.run(failing)) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.MODEL_FAILED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_FAILED_MODEL));
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertTrue(set.modelFailure() == bug, "the failure itself is kept");
            }
            try (var set = f.run()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.MODEL_FAILED));
                assertNull(set.modelFailure());
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
    void moddFlagsThatNeverChangeReferenceOrTransformAreAdmitted() {
        // Zephras Isle records 0x10, 0x12, 0x16 and 0x1e; 0x1 to 0x10 are admitted uninterpreted,
        // unseen higher bits stay refused.
        int[] flags = {0x01, 0x02, 0x04, 0x08, 0x10, 0x12, 0x16, 0x1e, 0x20, 0x40, 0x80};
        var defs = new ArrayList<Modd>();
        for (int flag : flags) {
            defs.add(new Modd(flag << 24, 0, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        var chunks = doodadChunks(new int[][] {{0, flags.length}}, new long[] {MODEL}, defs);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            try (var set = run(f, obj0(List.of(Modf.of(ROOT, 1, 0x8)), List.of(), 0))) {
                assertEquals(8, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(3, set.count(WorldExteriorObjects.Count.SET_DOODAD_UNSUPPORTED));
            }
        }
    }

    @Test
    void tiltedBuildingsFollowThePinnedPlacementMatrixAndCarryTheirSetDoodads() {
        // MODF pitch (rotation x) and roll (rotation z) use the MDDF construction: the wowdev
        // ADT/v18 placement matrix is the oracle, as for MDDF. A set doodad at a local offset
        // lands where the full building matrix puts it, not where the heading alone would.
        var tilted =
                new Modf(ROOT, 1, 0x8, 1234.5f, 87.25f, 2345.75f, 17.18f, 1.41f, -22.42f, 1024);
        var steep = new Modf(ROOT, 2, 0xc, 500, 40, 600, 37.85f, 56.82f, -5.54f, 1536);
        var defs = List.of(new Modd(0, 3, -2, 5, new double[] {0, 0, 0, 1}, 1));
        var chunks = doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, defs);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            try (var set = run(f, obj0(List.of(tilted, steep), List.of()))) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_UNSUPPORTED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                var buildings =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.BUILDING)
                                .toList();
                var doodads =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .toList();
                for (int i = 0; i < 2; i++) {
                    var r = i == 0 ? tilted : steep;
                    var oracle =
                            GlMat4.adtPlacement(
                                    r.x(),
                                    r.y(),
                                    r.z(),
                                    r.pitch(),
                                    r.heading(),
                                    r.roll(),
                                    r.flags() == 0xc ? r.scale() / 1024.0 : 1.0);
                    assertMapsLike(oracle, buildings.get(i));
                    var expected = oracle.apply(3, -2, 5);
                    var doodad = doodads.get(i);
                    assertEquals(expected[0], doodad.x(), 1e-6);
                    assertEquals(expected[1], doodad.y(), 1e-6);
                    assertEquals(expected[2], doodad.z(), 1e-6);
                }
            }
        }
    }

    @Test
    void flag0x80BuildingsTakeTheirDoodadSetsFromMwdrAndMwds() {
        // MODF 0x80: doodadSet 1 names MWDR entry 1 = MWDS[1..2] = sets {2, 1}; set 0 is kept.
        // A second building names MWDR entry 5, which is not recorded: set 0 only, reported.
        var defs =
                List.of(
                        new Modd(0, 0, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 1, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 2, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 3, 0, 0, new double[] {0, 0, 0, 1}, 1));
        var chunks =
                doodadChunks(
                        new int[][] {{0, 1}, {1, 1}, {2, 1}, {3, 1}}, new long[] {MODEL}, defs);
        var mwdr = buffer(16).putInt(0).putInt(0).putInt(1).putInt(2).array();
        var mwds = buffer(6).putShort((short) 3).putShort((short) 2).putShort((short) 1).array();
        var listed = new Modf(ROOT, 1, 0x88, 100, 20, 300, 0, 90, 0, 1024);
        var missing = new Modf(ROOT, 2, 0x88, 50, 5, 60, 0, 180, 0, 1024);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            byte[] objects =
                    join(
                            obj0(List.of(listed, missing), List.of(), 1, 5),
                            chunk("MWDR", mwdr),
                            chunk("MWDS", mwds));
            try (var set = run(f, objects)) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(4, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_UNSUPPORTED));
                var sets =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .map(p -> p.uniqueId() + ":" + p.doodadSet())
                                .toList();
                assertEquals(List.of("1:0", "1:2", "1:1", "2:0"), sets);
            }
        }
    }

    @Test
    void aGroupFileOverOneMebibyteIsReadAndDrawn() {
        // The BASIC_EXTERIOR read profile follows WmoGroupSpans.MAX_BYTES (8 MiB): a group padded
        // past the old 1 MiB cap with an unparsed chunk still draws.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            byte[] small = group(EXT);
            int mogp = indexOf(small, "MOGP");
            byte[] pad = chunk("ZZZZ", new byte[1 << 20]);
            byte[] big = join(small, pad);
            ByteBuffer.wrap(big)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(mogp + 4, small.length - mogp - 8 + pad.length);
            assertTrue(big.length > 1 << 20);
            f.files.sources.put(GROUP, big);
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
            }
        }
    }

    @Test
    void pendingGroupCopiesStayWithinWhatTheBudgetsCanDraw() {
        // A cutout-only group larger than the whole material vertex budget is capped before its
        // copy is held (BATCH_CAPPED, GROUP_EMPTY), exactly as resolving it would have; its image
        // is never read.
        int vertices = WorldExteriorObjects.MAX_MATERIAL_VERTICES + 1;
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, GROUP + 1},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP + 1, group(EXT, vertices, 1, List.of(new Batch(0, 3, 0, 1))));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_EMPTY));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                assertNull(f.files.opens.get(IMAGE2), "the capped cutout image is never read");
            }
        }
        // Hostile input: three groups each with one opaque triangle and 65,535 cutout triangles,
        // hold more candidate indices than both index budgets together; the root is capped whole
        // instead of keeping every copy.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            3,
                            new long[] {GROUP, GROUP + 1, GROUP + 2},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            for (int g = 0; g < 3; g++) {
                f.files.sources.put(
                        GROUP + g,
                        group(
                                EXT,
                                3,
                                65536,
                                List.of(
                                        new Batch(0, 3, 0, 0),
                                        // MOBA counts are u16: three batches of 21,845 triangles
                                        new Batch(3, 65535, 0, 1),
                                        new Batch(3 + 65535, 65535, 0, 1),
                                        new Batch(3 + 2 * 65535, 65535, 0, 1))));
            }
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertTrue(set.models().isEmpty());
            }
        }
    }

    @Test
    void mwdsEdgeRangesAreRefusedWholeAndListedSetsAreDeduplicated() {
        // Four MODS sets, one definition each. MWDR entries: 0 = [1, 0] (begin > end),
        // 1 = [0, 9] (end past MWDS), 2 = [0, 1] (lists set 7, past MODS), 3 = [2, 4] (lists
        // sets 0, 2 and 2 again). MWDS = [3, 7, 0, 2, 2].
        var defs = new ArrayList<Modd>();
        for (int i = 0; i < 4; i++) {
            defs.add(new Modd(0, i, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        var chunks =
                doodadChunks(
                        new int[][] {{0, 1}, {1, 1}, {2, 1}, {3, 1}}, new long[] {MODEL}, defs);
        var mwdr =
                buffer(32)
                        .putInt(1)
                        .putInt(0)
                        .putInt(0)
                        .putInt(9)
                        .putInt(0)
                        .putInt(1)
                        .putInt(2)
                        .putInt(4)
                        .array();
        var mwds = buffer(10);
        for (int id : new int[] {3, 7, 0, 2, 2}) {
            mwds.putShort((short) id);
        }
        int[] entries = {0, 1, 2, 3};
        var records = new ArrayList<Modf>();
        for (int i = 0; i < entries.length; i++) {
            records.add(new Modf(ROOT, 1 + i, 0x88, 100 + 10 * i, 20, 300, 0, 90, 0, 1024));
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            byte[] objects =
                    join(
                            obj0(records, List.of(), entries),
                            chunk("MWDR", mwdr),
                            chunk("MWDS", mwds.array()));
            try (var set = run(f, objects)) {
                assertEquals(4, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                // The first three ranges are refused whole (all-or-nothing): set 0 only.
                assertEquals(3, set.count(WorldExteriorObjects.Count.SET_UNSUPPORTED));
                var sets =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .map(p -> p.uniqueId() + ":" + p.doodadSet())
                                .toList();
                // The fourth lists 0 (already drawn) and 2 twice: sets 0 and 2, each once.
                assertEquals(List.of("1:0", "2:0", "3:0", "4:0", "4:2"), sets);
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
                        new Modd(0x20000000, 0, 0, 0, new double[] {0, 0, 0, 1}, 1)); // flag 0x20
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
     * the current matrix), used to transcribe the wowdev placement-matrix examples step by step. It
     * never calls {@link WorldExteriorObjects.Rotation}.
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
                        case 'x' ->
                                new double[][] {
                                    {1, 0, 0, 0}, {0, c, -s, 0}, {0, s, c, 0}, {0, 0, 0, 1}
                                };
                        case 'y' ->
                                new double[][] {
                                    {c, 0, s, 0}, {0, 1, 0, 0}, {-s, 0, c, 0}, {0, 0, 0, 1}
                                };
                        default ->
                                new double[][] {
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
                assertEquals(
                        expected[i],
                        actual[i],
                        1e-9,
                        "axis " + i + " of local " + List.of(v[0], v[1], v[2]));
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
    void texture1CoordinatesComeFromTheVertexShaderNotTheUnusedLookupTable() {
        // Non-combiner IDs: Diffuse_T1 (0, 16), Diffuse_T1_T2 (0x4000 with two textures) use UV0;
        // Diffuse_Env (0x80) and Diffuse_T2 (0x4000 with one texture) do not.
        assertTrue(WorldExteriorObjects.firstTextureUsesUv0(0, 1));
        assertTrue(WorldExteriorObjects.firstTextureUsesUv0(16, 1));
        assertTrue(WorldExteriorObjects.firstTextureUsesUv0(0x4000, 2));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x80, 1));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x88, 2));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x4000, 1));
        // Combiners: 0 Diffuse_T1_Env, 18 Diffuse_T1, 19 Diffuse_EdgeFade_T1, 31 Color_T1_T2_T3,
        // 35 the last entry (EdgeFade), 36 outside the table.
        assertTrue(WorldExteriorObjects.firstTextureUsesUv0(0x8000, 2));
        assertTrue(WorldExteriorObjects.firstTextureUsesUv0(0x8000 | 18, 1));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x8000 | 19, 1));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x8000 | 31, 3));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x8000 | 35, 2));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x8000 | 36, 1));
        // Bits outside the named selection are refused, as M2Combiners.admit refuses them.
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x0001, 1));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x0100, 2));
        assertTrue(!WorldExteriorObjects.firstTextureUsesUv0(0x2000, 1));
        // Real models declare an empty texture-coordinate lookup (unused since Cataclysm); the
        // authored model with its one-entry table cleared still draws.
        var bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(40 + 136, 0);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, bytes);
            try (var set = run(f, obj0(List.of(), List.of(Mddf.of(MODEL, 1, 0x240))))) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.MODEL_BATCH_DRAWN));
            }
        }
    }

    @Test
    void mddfPitchRollAndHeadingFollowThePinnedPlacementMatrix() {
        var tilted =
                new Mddf(MODEL, 1, 0x240, 1234.5f, 87.25f, 2345.75f, 17.5f, -121.25f, 33, 1536);
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
                                    r.x(),
                                    r.y(),
                                    r.z(),
                                    r.pitch(),
                                    r.heading(),
                                    r.roll(),
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

    @Test
    void setDoodadsRecordTheSetTheyCameFrom() {
        var defs =
                List.of(
                        new Modd(0, 1, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 2, 0, 0, new double[] {0, 0, 0, 1}, 1));
        var chunks = doodadChunks(new int[][] {{0, 1}, {1, 1}}, new long[] {MODEL}, defs);
        var building = new Modf(ROOT, 77, 0x8, 100, 20, 300, 0, 90, 0, 1024);
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0)), chunks));
            try (var set = run(f, obj0(List.of(building), List.of(), 1))) {
                var placements = set.placements();
                assertEquals(3, placements.size());
                assertEquals(WorldExteriorObjects.Kind.BUILDING, placements.get(0).kind());
                assertEquals(1, placements.get(0).doodadSet());
                // Set 0 is always drawn, then the building's MODF doodad set.
                assertEquals(0, placements.get(1).ordinal());
                assertEquals(0, placements.get(1).doodadSet());
                assertEquals(1, placements.get(2).ordinal());
                assertEquals(1, placements.get(2).doodadSet());
                assertEquals(77, placements.get(2).uniqueId());
            }
        }
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
                // The dropped cutout range's image is not uploaded.
                assertTrue(set.images().stream().noneMatch(image -> image.id() == IMAGE2));
                assertImageSlotsValid(set);
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

    private static void assertImageSlotsValid(WorldExteriorObjects.TileObjects set) {
        for (var model : set.models()) {
            for (var mesh : model.meshes()) {
                for (var range : mesh.ranges()) {
                    assertTrue(range.image() >= 0 && range.image() < set.images().size());
                }
            }
        }
    }

    @Test
    void aMaterialOnlyBuildingNeverCapsALaterOpaqueBuildingAndIsTrimmedInstead() {
        // A building with only two cutout batches, placed first, then a building with 65 opaque
        // batches in two groups. The opaque-only profile left the first empty, so it costs
        // nothing at admission: 63 opaque placements (4,095 draws) are all drawn, and the cutout
        // building, which no longer fits, returns to its opaque-only outcome.
        long cutoutRoot = ROOT + 10, cutoutGroup = GROUP + 10;
        var opaque = new ArrayList<Batch>();
        for (int b = 0; b < 64; b++) {
            opaque.add(new Batch(b * 3, 3, 0, 0));
        }
        // Two cutout placements then 63 opaque (the review's repro), then one cutout and 63 or 62.
        for (int[] scenario : new int[][] {{2, 63}, {1, 63}, {1, 62}}) {
            int cutouts = scenario[0], buildings = scenario[1];
            var records = new ArrayList<Modf>();
            for (int i = 0; i < cutouts; i++) {
                records.add(Modf.of(cutoutRoot, 1000 + i, 0x8));
            }
            for (int i = 0; i < buildings; i++) {
                records.add(Modf.of(ROOT, i + 2, 0x8));
            }
            try (var f = new Fixture(records)) {
                // 64 opaque batches in one group and one in a second: 65 opaque ranges.
                f.files.sources.put(
                        ROOT,
                        root(2, new long[] {GROUP, GROUP + 1}, List.of(new Material(0, IMAGE, 0))));
                f.files.sources.put(GROUP, group(EXT, 3, 64, opaque));
                f.files.sources.put(GROUP + 1, group(EXT));
                f.files.sources.put(
                        cutoutRoot,
                        root(1, new long[] {cutoutGroup}, List.of(new Material(1, IMAGE2, 0))));
                f.files.sources.put(
                        cutoutGroup,
                        group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 0))));
                try (var set = f.run()) {
                    // Slots: only a material-only placement beyond 64 placements is capped.
                    int capped =
                            Math.max(0, cutouts + buildings - WorldExteriorObjects.MAX_PLACEMENTS);
                    assertEquals(
                            capped,
                            set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED),
                            counts(set).toString());
                    assertImageSlotsValid(set);
                    if (buildings == 63) {
                        assertEquals(
                                63,
                                set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN),
                                counts(set).toString());
                        assertEquals(
                                cutouts - capped,
                                set.count(WorldExteriorObjects.Count.PLACEMENT_EMPTY));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
                        assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                        assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_EMPTY));
                        assertEquals(1, set.models().size());
                        assertEquals(63, set.placements().size());
                        assertTrue(
                                set.placements().stream()
                                        .allMatch(p -> p.rootId() == ROOT && p.model() == 0));
                        // Only the opaque image is uploaded.
                        assertEquals(1, set.images().size());
                        assertEquals(IMAGE, set.images().getFirst().id());
                    } else {
                        assertEquals(63, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                        assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                        assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                        assertEquals(2, set.images().size());
                    }
                }
            }
        }
    }

    @Test
    void materialOnlyPlacementsNeverTakeABuildingSlotFromAnOpaqueOne() {
        // Cutout-only placements first, then opaque one-batch buildings. Only opaque placements
        // fill the 64 slots at admission; material-only ones beyond them are capped, last first.
        long cutoutRoot = ROOT + 10, cutoutGroup = GROUP + 10;
        for (int[] scenario : new int[][] {{3, 63}, {2, 64}}) {
            int cutouts = scenario[0], buildings = scenario[1];
            var records = new ArrayList<Modf>();
            for (int i = 0; i < cutouts; i++) {
                records.add(Modf.of(cutoutRoot, 1000 + i, 0x8));
            }
            for (int i = 0; i < buildings; i++) {
                records.add(Modf.of(ROOT, i + 2, 0x8));
            }
            try (var f = new Fixture(records)) {
                f.files.sources.put(
                        cutoutRoot,
                        root(1, new long[] {cutoutGroup}, List.of(new Material(1, IMAGE2, 0))));
                f.files.sources.put(cutoutGroup, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
                try (var set = f.run()) {
                    assertImageSlotsValid(set);
                    long opaque = set.placements().stream().filter(p -> p.rootId() == ROOT).count();
                    assertEquals(buildings, opaque, counts(set).toString());
                    assertEquals(
                            WorldExteriorObjects.MAX_PLACEMENTS,
                            set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                    assertEquals(WorldExteriorObjects.MAX_PLACEMENTS, set.placements().size());
                    assertEquals(
                            cutouts + buildings - WorldExteriorObjects.MAX_PLACEMENTS,
                            set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                    // Kept placements stay in source order, and each names its own model.
                    var uids = set.placements().stream().map(p -> p.uniqueId()).toList();
                    assertEquals(
                            records.stream().map(Modf::uid).filter(uids::contains).toList(), uids);
                    for (var p : set.placements()) {
                        assertEquals(p.rootId(), set.models().get(p.model()).rootId());
                    }
                    if (cutouts + buildings - WorldExteriorObjects.MAX_PLACEMENTS == cutouts) {
                        // Every cutout placement was capped: its building is removed whole.
                        assertEquals(1, set.models().size());
                        assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
                        assertEquals(1, set.images().size());
                    } else {
                        // The first cutout placement is kept; the later ones are capped.
                        assertEquals(1000, set.placements().getFirst().uniqueId());
                        assertEquals(2, set.models().size());
                    }
                }
            }
        }
    }

    @Test
    void materialOnlyBuildingsSetDoodadsNeverSpendBudgetAheadOfAnOpaqueBuildings() {
        // A cutout-only building placed first declares 512 set doodads, the whole doodad budget;
        // an opaque building placed after it declares one. The opaque building's set comes first.
        long cutoutRoot = ROOT + 10, cutoutGroup = GROUP + 10;
        var many = new ArrayList<Modd>();
        for (int i = 0; i < WorldExteriorObjects.MAX_DOODADS; i++) {
            many.add(new Modd(0, i, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        var one = List.of(new Modd(0, 0, 0, 0, new double[] {0, 0, 0, 1}, 1));
        var records = List.of(Modf.of(cutoutRoot, 1, 0x8), Modf.of(ROOT, 2, 0x8));
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    cutoutRoot,
                    root(
                            1,
                            new long[] {cutoutGroup},
                            List.of(new Material(1, IMAGE2, 0)),
                            doodadChunks(
                                    new int[][] {{0, WorldExteriorObjects.MAX_DOODADS}},
                                    new long[] {MODEL},
                                    many)));
            f.files.sources.put(cutoutGroup, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, one)));
            try (var set = run(f, obj0(records, List.of(), 0, 0))) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(
                        WorldExteriorObjects.MAX_DOODADS,
                        set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN),
                        counts(set).toString());
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_CAPPED));
                var setDoodads =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .toList();
                // The opaque building's doodad is drawn, and first.
                assertEquals(2, setDoodads.getFirst().uniqueId());
                assertEquals(1, setDoodads.stream().filter(p -> p.uniqueId() == 2).count());
                // Buildings keep their source order ahead of every doodad.
                assertEquals(1, set.placements().get(0).uniqueId());
                assertEquals(2, set.placements().get(1).uniqueId());
            }
        }
    }

    /**
     * Files whose group reads each take a while and are counted while open, so overlapping reads
     * are visible. Serial reads never have two groups open at once.
     */
    private static final class SlowGroups extends WorldTerrainTextureSourceTest.Files {
        final AtomicInteger open = new AtomicInteger(), peak = new AtomicInteger();
        final AtomicInteger groupOpens = new AtomicInteger();
        volatile Runnable onGroupOpen = () -> {};

        @Override
        com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
            var actual = super.open(id, cancel);
            if (id < GROUP + 10 || id >= GROUP + 100) {
                return actual;
            }
            groupOpens.incrementAndGet();
            onGroupOpen.run();
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new com.vibeminer.files.FileReadStream() {
                boolean closed;

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
                    return actual.read(destination, token);
                }

                public void close() {
                    if (!closed) {
                        closed = true;
                        open.decrementAndGet();
                    }
                    actual.close();
                }
            };
        }
    }

    private static long[] manyGroups(SlowGroups files, int count) {
        var ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = GROUP + 10 + i;
            files.sources.put(ids[i], group(EXT));
        }
        return ids;
    }

    @Test
    void anUncappedRootReadsItsGroupsAheadWithinTheReadBoundAndDrawsTheSame() {
        List<Object> serial = null;
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
                f.files.sources.put(ROOT, root(manyGroups(files, 12)));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                var summary = new ArrayList<Object>();
                try (var set = f.run()) {
                    summary.add(counts(set));
                    summary.add(set.vertices());
                    summary.add(set.placements());
                    summary.add(set.models().getFirst().meshes().size());
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                }
                assertEquals(12, files.groupOpens.get(), "each group is read exactly once");
                assertEquals(0, files.open.get(), "every read is closed");
                if (!ahead) {
                    assertEquals(1, files.peak.get(), "serial reads never overlap");
                    serial = summary;
                } else {
                    assertTrue(files.peak.get() >= 2, "groups were read concurrently");
                    // At most the read-ahead bound plus the operation's own read.
                    assertTrue(
                            files.peak.get() <= WorldStreamScopes.Prefetcher.READS + 1,
                            "peak " + files.peak.get());
                    assertEquals(serial, summary, "read-ahead changes no output");
                }
            }
        }
    }

    @Test
    void cancellationDrainsAndClosesEveryReadAheadBeforeThePreparationReturns() {
        var files = new SlowGroups();
        var token = new Cancellation();
        files.onGroupOpen =
                () -> {
                    if (files.groupOpens.get() == 2) {
                        token.cancel();
                    }
                };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            f.files.sources.put(ROOT, root(manyGroups(files, 12)));
            assertThrows(
                    CancellationException.class,
                    () -> WorldExteriorObjects.prepare(f.engine, f.scope, TILE, token));
            int opened = files.groupOpens.get();
            assertEquals(0, files.open.get(), "no read-ahead stream is left open");
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
            }
            assertEquals(opened, files.groupOpens.get(), "no read starts after the return");
            // The engine stays healthy for the next operation.
            try (var set = f.run()) {
                assertEquals(12, set.models().getFirst().meshes().size());
            }
        }
    }
}
