package com.vibeminer.concepts.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.AuthoredNativeMap;
import com.vibeminer.formats.world.WmoFormats;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldPlacements;
import com.vibeminer.formats.world.WorldStageTimings;
import com.vibeminer.rendering.world.WmoPortalSet;
import com.vibeminer.rendering.world.WmoPortalTraversal;
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
                    WorldExteriorObjects.Count.PLACEMENT_EMPTY,
                    WorldExteriorObjects.Count.PLACEMENT_OTHER_TILE);

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
            float extent,
            float[] bounds) {
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
                int scale,
                float extent) {
            this(id, uid, flags, x, y, z, pitch, heading, roll, scale, extent, null);
        }

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

        /** The same placement with recorded MODF bounds from raw lower to raw upper corner. */
        Modf bounded(float... lowerThenUpper) {
            return new Modf(
                    id, uid, flags, x, y, z, pitch, heading, roll, scale, extent, lowerThenUpper);
        }

        byte[] bytes() {
            var b = buffer(64);
            b.putInt((int) id).putInt((int) uid).put(floats(x, y, z));
            b.put(floats(pitch, heading, roll))
                    .put(
                            bounds != null
                                    ? floats(bounds)
                                    : floats(-extent, -extent, -extent, extent, extent, extent));
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

    /** The fixture root's MOHD ambient and flags (root(...)): 0x0b on each channel, no flags. */
    private static final long FIXTURE_AMBIENT = 0xff0b0b0bL;

    /** A pure interior group's MOGP flags: INT without the exterior flag 0x8. */
    private static final int INTERIOR_ONLY = INT & ~0x8;

    /** The interior light of a group's MOCV in a fixture root, with no transition batches. */
    private static int[] interiorLight(byte[] mocv) {
        return WmoInteriorLight.colours(
                i -> mocv[i] & 0xff, mocv.length / 4, 0, FIXTURE_AMBIENT, 0);
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

    /** BC3 (DXT5) BLP2 with {@code levels} recorded levels, colour {@code color}, alpha 128. */
    private static byte[] blp3(int width, int height, int levels, int color) {
        var data = new ByteArrayOutputStream();
        var header = buffer(148);
        header.put(new byte[] {'B', 'L', 'P', '2'}).putInt(1);
        header.put((byte) 2).put((byte) 8).put((byte) 7).put((byte) 1);
        header.putInt(width).putInt(height);
        int offset = 148;
        for (int level = 0; level < levels; level++) {
            int w = Math.max(1, width >> level), h = Math.max(1, height >> level);
            int blocks = ((w + 3) / 4) * ((h + 3) / 4);
            header.putInt(20 + level * 4, offset).putInt(84 + level * 4, blocks * 16);
            var b = buffer(blocks * 16);
            for (int i = 0; i < blocks; i++) {
                b.put((byte) 128).put((byte) 128).put(new byte[6]);
                b.putShort((short) color).putShort((short) 0).putInt(0);
            }
            data.writeBytes(b.array());
            offset += blocks * 16;
        }
        return join(header.array(), data.toByteArray());
    }

    /** One building image of {@code materialClass} from {@code file}, through a full run. */
    private static WorldExteriorObjects.Image single(int materialClass, byte[] file) {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(1, new long[] {GROUP}, List.of(new Material(materialClass, IMAGE, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(IMAGE, file);
            try (var set = f.run()) {
                return set.images().getFirst();
            }
        }
    }

    /** One opaque building image from {@code file}, for a session sampling {@code formats}. */
    private static WorldExteriorObjects.TileObjects sampling(int formats, byte[] file) {
        var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)));
        f.files.sources.put(ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0))));
        f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
        f.files.sources.put(IMAGE, file);
        try {
            return f.runSampling(formats);
        } finally {
            f.close();
        }
    }

    @Test
    void aSessionSamplingBc1TakesTheLargerSideAtItsCompressedCost() {
        int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        byte[] file = blp(1024, 1024, 11, 0x07e0);
        try (var set = sampling(bc1, file)) {
            var image = set.images().getFirst();
            assertEquals(512, image.width(), "a sampled BC1 chain reaches the 512 side");
            assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.BC1, image.format());
            assertEquals(bc1, set.compressedFormats());
            // Charged as uploaded: its stored bytes in RGBA8 texel units, not 512 x 512.
            assertEquals(
                    com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                                    com.vibeminer.rendering.world.ObjectTextureFormats.BC1,
                                    512,
                                    512)
                            / 4,
                    set.pixels());
            // The 512 level is never decoded to RGBA8: only the registration decode ran.
            assertEquals(1, set.levelDecodes);
            assertEquals(255, image.rgba().get(1) & 255, "green");
        }
        try (var set = sampling(0, file)) {
            // A session that decodes BC1 keeps the RGBA8 cap and charge: 256 at most.
            assertEquals(256, set.images().getFirst().width());
            assertEquals(0, set.compressedFormats());
        }
    }

    @Test
    void unusableBlocksAtALargerSideFallBackToRgbaWithinItsCharge() {
        int bc3 = com.vibeminer.rendering.world.ObjectTextureFormats.BC3;
        // A blended BC3 512^2 allotted 512 (87,388 texel units); nothing retained and its
        // blocks' re-read refused (a blended image keeps what its first read gave). RGBA8 512 would
        // cost 4x: the fallback may take at most 256^2 (87,381), within the charge and cap.
        try (var f = blocksUnreadable(blp3(512, 512, 10, 0x07e0), 2);
                var set = sampling(f, 0, 1 << bc3)) {
            var image = set.images().getFirst();
            assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, image.format());
            // The charge admits 256^2 (rgbaLevelWithin, WorldImageBudgetTest), but with the re-read
            // refused the largest level at hand is the 128 registration.
            assertEquals(128, image.width());
            assertTrue(
                    WorldImageBudget.cost(256, 256, 0)
                            <= com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                                            bc3, 512, 512)
                                    / 4);
            assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS_FALLBACK));
            assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS));
        }
    }

    /** One building image of material {@code blend} whose second read is refused. */
    private static Fixture blocksUnreadable(byte[] file, int blend) {
        var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingSecondRead(IMAGE));
        f.files.sources.put(
                ROOT, root(1, new long[] {GROUP}, List.of(new Material(blend, IMAGE, 0))));
        f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
        f.files.sources.put(IMAGE, file);
        return f;
    }

    @Test
    void sampledRegistrationPixelsCountAsRetainedUntilTheirBlocksAreRead() {
        int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        // Nothing else retained: the only retained bytes are the 128^2 registration pixels the
        // sampled chain kept for its fallback, dropped once its blocks were read.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(IMAGE, blp(1024, 1024, 11, 0x07e0));
            try (var set = sampling(f, 0, bc1)) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS));
                assertEquals(128 * 128 * 4, set.retainedSourceBytes);
            }
            try (var set = sampling(f, 0, 0)) {
                assertEquals(0, set.retainedSourceBytes, "an RGBA8 plan keeps none of them");
            }
        }
    }

    @Test
    void blocksWhoseLevelsOverlapTheHeaderAreNeverPlanned() {
        int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        byte[] file = blp(1024, 1024, 11, 0x07e0);
        // Level 2 (the 256 side) points inside the header: blockFormat refuses the file up
        // front, so it is planned as RGBA8 within its cap and never needs the fallback.
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(20 + 2 * 4, 100);
        try (var set = sampling(bc1, file)) {
            var image = set.images().getFirst();
            assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, image.format());
            assertTrue(image.width() <= 256);
            assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS_FALLBACK));
            assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS));
        }
    }

    @Test
    void sampledChainsUnderOneBlockAreNotCountedAsBlockFallbacks() {
        int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        // A 2 x 2 DXT1 source in a session that samples BC1: its allotted level is under one 4 x 4
        // block, so it is planned and decoded as RGBA8 from the start. The blocks were never
        // planned, so it is no fallback and is not decoded a second time.
        int rgbaDecodes;
        try (var set = sampling(0, blp(2, 2, 2, 0x07e0))) {
            rgbaDecodes = set.levelDecodes;
        }
        try (var set = sampling(bc1, blp(2, 2, 2, 0x07e0))) {
            var image = set.images().getFirst();
            assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, image.format());
            assertEquals(2, image.width());
            assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS_FALLBACK));
            assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS));
            // The same decodes as a session that samples no blocks: none is redone.
            assertEquals(rgbaDecodes, set.levelDecodes);
        }
    }

    @Test
    void unusableBlocksAt256FallBackWithinTheirCharge() {
        int bc3 = com.vibeminer.rendering.world.ObjectTextureFormats.BC3;
        // A blended BC3 256^2 registered at 128 and allotted 256 (21,852 texel units), its re-read
        // refused: RGBA8 256 would cost 4x, so it draws its 128 registration level (21,845).
        try (var f = blocksUnreadable(blp3(256, 256, 9, 0x07e0), 2);
                var set = sampling(f, 0, 1 << bc3)) {
            var image = set.images().getFirst();
            assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, image.format());
            assertEquals(128, image.width());
            assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS_FALLBACK));
        }
    }

    @Test
    void unusableBlocksBelowTheRegistrationLevelStillFallBack() {
        int bc3 = com.vibeminer.rendering.world.ObjectTextureFormats.BC3;
        // The material pool holds 3 cutout 256^2 and 28 cutout 256x128 RGBA8 chains ahead of one
        // blended 256^2 BC3 chain; by the 128 round the pool has too little left to raise the
        // BC3 chain past 64, below its 128 registration level. Its 32 x 32 level points inside
        // the header, so the blocks are unusable and the RGBA8 fallback needs the registration
        // pixels, which the release step must keep for a sampled chain.
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        materials.add(new Material(0, IMAGE, 0));
        batches.add(new Batch(0, 3, 0, 0));
        for (int i = 0; i < 32; i++) {
            materials.add(new Material(i < 31 ? 1 : 2, 6000 + i, 0));
            batches.add(new Batch((i + 1) * 3, 3, 0, i + 1));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingSecondRead(6031L))) {
            f.files.sources.put(ROOT, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 33, batches));
            f.files.sources.put(IMAGE, blp(64, 64, 7, 0x07e0));
            for (int i = 0; i < 31; i++) {
                f.files.sources.put(6000L + i, blp(256, i < 3 ? 256 : 128, 3, i));
            }
            f.files.sources.put(6031L, blp3(256, 256, 9, 0x07e0));
            try (var set = sampling(f, 0, 1 << bc3)) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                var image =
                        set.images().stream()
                                .filter(i -> i.id() == 6031L)
                                .findFirst()
                                .orElseThrow();
                // Allotted 64 as BC3 (1,372 texel units): a 64^2 RGBA8 chain (5,461) would
                // overspend, so the fallback takes 32^2 (1,365).
                assertEquals(32, image.width(), "within what its blocks were charged");
                assertEquals(
                        com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, image.format());
                assertEquals(255, image.rgba().get(1) & 255, "green");
            }
        }
    }

    @Test
    void opaqueDxtWithItsWholeChainIsCarriedAsTheFilesOwnBlocks() {
        byte[] file = blp(256, 256, 9, 0x07e0);
        var image = single(0, file);
        assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.BC1, image.format());
        var data = image.data();
        assertEquals(
                com.vibeminer.rendering.world.ObjectTextureFormats.chainBytes(
                        com.vibeminer.rendering.world.ObjectTextureFormats.BC1, 256, 256),
                data.remaining());
        // Byte for byte the file's level blocks, in level order.
        byte[] expected = java.util.Arrays.copyOfRange(file, 148, file.length);
        byte[] actual = new byte[data.remaining()];
        data.get(actual);
        assertArrayEquals(expected, actual);
        // The RGBA8 view decodes it: green.
        assertEquals(255, image.rgba().get(1) & 255);
    }

    @Test
    void blendedTakesBc3BlocksButNotBc1AndCutoutStaysRgba() {
        var bc3 = single(2, blp3(64, 64, 7, 0x001f));
        assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.BC3, bc3.format());
        assertEquals(128, bc3.rgba().get(3) & 255, "BC3 alpha carried");
        // BC1 alpha is not sampled on every backend: a blended BC1 surface stays RGBA8.
        assertEquals(
                com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                single(2, blp(64, 64, 7, 0x001f)).format());
        // Cutout chains rescale alpha coverage per level (#265): always RGBA8.
        assertEquals(
                com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                single(1, blp3(64, 64, 7, 0x001f)).format());
    }

    @Test
    void levelsInsideTheHeaderOrOverlappingAreNeverCarriedAsBlocks() {
        // 64 x 64 BC1, seven levels; the 1 x 1 level (6) is one 8-byte block.
        byte[] header = blp(64, 64, 7, 0x07e0);
        var h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(20 + 6 * 4, 100); // inside the 148-byte header
        var inside = single(0, header);
        assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, inside.format());
        byte[] overlap = blp(64, 64, 7, 0x07e0);
        var o = ByteBuffer.wrap(overlap).order(ByteOrder.LITTLE_ENDIAN);
        o.putInt(20 + 6 * 4, o.getInt(20 + 5 * 4)); // the 1 x 1 level reuses the 2 x 2 bytes
        var shared = single(0, overlap);
        assertEquals(com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8, shared.format());
        // Both still draw, from the RGBA8 path.
        assertEquals(64, inside.width());
        assertEquals(64, shared.width());
    }

    @Test
    void filesWithoutAWholeChainOrUnderOneBlockStayRgba() {
        // Three recorded levels of seven: the chain would need generated levels.
        assertEquals(
                com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                single(0, blp(64, 64, 3, 0x07e0)).format());
        // A 2 x 2 image is under one block; D3D11 refuses such compressed textures.
        assertEquals(
                com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8,
                single(0, blp(2, 2, 2, 0x07e0)).format());
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
            this(records, files, WorldSourceCache.off());
        }

        Fixture(
                List<Modf> records,
                WorldTerrainTextureSourceTest.Files files,
                WorldSourceCache sources) {
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
            // Re-read paths are under test with the cache off; kept files where it is given.
            engine = new WorldStreamScopes(nativeMap.tables(), files.files(), sources);
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

        /** A run for a GPU session that samples {@code formats} as blocks (#374). */
        WorldExteriorObjects.TileObjects runSampling(int formats) {
            return WorldExteriorObjects.prepare(
                    engine,
                    scope,
                    TILE,
                    Cancellation.NONE,
                    WorldExteriorObjects.ModelReader.STANDARD,
                    WorldExteriorObjects.ImageDetail.STANDARD,
                    WorldExteriorObjects.RETAINED_SOURCE_BYTES,
                    false,
                    formats);
        }

        /** An open-world map: MOGI-interior groups are read after every exterior (#383). */
        WorldExteriorObjects.TileObjects runOpenWorld() {
            return prepareWith(this, WorldExteriorObjects.Interiors.OPEN_WORLD, 0);
        }

        /** A dungeon or raid map: interior WMO groups are drawn too (#280). */
        WorldExteriorObjects.TileObjects runInstance() {
            return WorldExteriorObjects.prepare(
                    engine,
                    scope,
                    TILE,
                    Cancellation.NONE,
                    WorldExteriorObjects.ModelReader.STANDARD,
                    WorldExteriorObjects.ImageDetail.STANDARD,
                    true);
        }

        WorldExteriorObjects.TileObjects run(WorldExteriorObjects.ModelReader reader) {
            return WorldExteriorObjects.prepare(engine, scope, TILE, Cancellation.NONE, reader);
        }

        public void close() {
            engine.closeAsync().join();
            nativeMap.close();
        }
    }

    /** A run with the given interior mode, for a session sampling {@code formats}. */
    private static WorldExteriorObjects.TileObjects prepareWith(
            Fixture f, WorldExteriorObjects.Interiors interiors, int formats) {
        return prepareWith(f, interiors, WorldExteriorObjects.RETAINED_SOURCE_BYTES, formats);
    }

    private static WorldExteriorObjects.TileObjects prepareWith(
            Fixture f, WorldExteriorObjects.Interiors interiors, long retain, int formats) {
        return WorldExteriorObjects.prepare(
                f.engine,
                f.scope,
                TILE,
                Cancellation.NONE,
                WorldExteriorObjects.ModelReader.STANDARD,
                WorldExteriorObjects.ImageDetail.STANDARD,
                retain,
                interiors,
                formats);
    }

    /** Files that also record the order in which sources are opened. */
    private static final class OrderedFiles extends WorldTerrainTextureSourceTest.Files {
        final List<Long> order = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
            order.add(id);
            return super.open(id, cancel);
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
    void stageTimingsTallyEveryCountAndTimeTheTileWhenEnabled() {
        WorldStageTimings.reset();
        WorldStageTimings.enable(true);
        try (var f = new Fixture(List.of(new Modf(ROOT, 77, 0, 100, 20, 300, 0, 90, 0, 1024)));
                var set = f.run()) {
            set.tallyCounts();
            var snapshot = WorldStageTimings.snapshot();
            assertEquals(1, snapshot.get("objects.count.tiles").sum());
            for (var count : WorldExteriorObjects.Count.values()) {
                var series = snapshot.get("objects.count." + count.name());
                assertEquals(set.count(count), series == null ? 0 : series.sum(), count.name());
            }
            assertEquals(set.vertices(), snapshot.get("objects.count.vertices").sum());
            assertEquals(1, snapshot.get("objects.tile.total").count());
            int buildingModels = 0, doodadModels = 0;
            for (var model : set.models()) {
                if (model.doodad()) {
                    doodadModels++;
                } else {
                    buildingModels++;
                }
            }
            assertEquals(buildingModels, snapshot.get("objects.models.buildingsPerTile").sum());
            assertEquals(doodadModels, snapshot.get("objects.models.doodadsPerTile").sum());
            var perModel = snapshot.get("objects.models.placementsPerModel");
            assertEquals(set.placements().size(), perModel == null ? 0 : perModel.sum());
            assertTrue(snapshot.get("source.read.BASIC_EXTERIOR").count() > 0);
            assertTrue(snapshot.get("objects.phase.buildings").count() == 1);
        } finally {
            WorldStageTimings.enable(false);
            WorldStageTimings.reset();
        }
    }

    @Test
    void modelUseCountsDistinctModelsDrawsAndPlacementsLargestFirst() {
        // Models: 0 building (3 ranges), 1 doodad (2 ranges), 2 doodad (1 range, unused).
        var use =
                WorldExteriorObjects.ModelUse.of(
                        new int[] {3, 2, 1},
                        new boolean[] {false, true, true},
                        new int[] {1, 0, 1, 1, 0, 1});
        assertEquals(1, use.buildingModels());
        assertEquals(1, use.doodadModels());
        assertEquals(2 * 3 + 4 * 2, use.draws());
        assertEquals(3 + 2, use.instancedDraws());
        assertTrue(Arrays.equals(new int[] {4, 2}, use.placementsPerModel()));
        var empty = WorldExteriorObjects.ModelUse.of(new int[0], new boolean[0], new int[0]);
        assertEquals(0, empty.draws());
        assertEquals(0, empty.placementsPerModel().length);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldExteriorObjects.ModelUse.of(new int[1], new boolean[0], new int[0]));
    }

    @Test
    void stageTimingsStayEmptyWhenDisabled() {
        WorldStageTimings.reset();
        WorldStageTimings.enable(false);
        try (var f = new Fixture(List.of(new Modf(ROOT, 77, 0, 100, 20, 300, 0, 90, 0, 1024)));
                var set = f.run()) {
            set.tallyCounts();
            assertTrue(WorldStageTimings.snapshot().series().isEmpty());
        }
    }

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
    void instanceMapsDrawInteriorGroupsAfterTheExterior() {
        // One root: an interior group recorded first, then an exterior group, then a group with
        // neither flag. On an open-world map the interior is omitted; on a dungeon or raid map it
        // draws, and the flagless group stays unsupported either way.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(INTERIOR, GROUP, 210));
            f.files.sources.put(210L, group(0x1));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
            try (var set = f.runInstance()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_UNSUPPORTED));
                assertEquals(2, set.models().getFirst().meshes().size());
            }
        }
        // An interior-only root (a dungeon's own building) draws on an instance map.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(INTERIOR));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
            }
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
            }
        }
    }

    /** MOGI: one 32-byte group-info record per group, flags first (wowdev SMOGroupInfo). */
    private static byte[] mogi(int... flags) {
        var b = buffer(32 * flags.length);
        for (int i = 0; i < flags.length; i++) {
            b.putInt(i * 32, flags[i]);
        }
        return chunk("MOGI", b.array());
    }

    private static int[] repeatFlags(int flags, int count) {
        var out = new int[count];
        Arrays.fill(out, flags);
        return out;
    }

    private static byte[] mogiRoot(long[] gfid, int[] flags) {
        return root(gfid.length, gfid, List.of(new Material(0, IMAGE, 0)), mogi(flags));
    }

    @Test
    void doodadModelsNeverTakeTheBuildingMeshRowsInteriorsNeed() {
        // More distinct doodad models (one mesh each) than the 256 building mesh rows: they have
        // rows of their own, so the building's interior, admitted after them, still draws.
        long first = 10_000;
        int count = WorldExteriorObjects.MAX_MESHES + 4;
        assertTrue(count <= WorldExteriorObjects.MAX_MODELS);
        var distinct = new ArrayList<Mddf>();
        for (int i = 0; i < count; i++) {
            distinct.add(Mddf.of(first + i, 1000 + i, 0x40));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            for (int i = 0; i < count; i++) {
                putModel(f, first + i, model());
            }
            f.files.sources.put(ROOT, root(GROUP, INTERIOR));
            f.files.sources.put(4L, obj0(List.of(Modf.of(ROOT, 1, 0x8)), distinct));
            try (var set = f.runInstance()) {
                assertEquals(count, set.count(WorldExteriorObjects.Count.MODEL_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
            }
        }
    }

    @Test
    void mogiInteriorGroupsAreNeverReadOutsideInstances() {
        // MOGI marks the first group interior: on an open-world map it is counted GROUP_INTERIOR
        // exactly as a read interior group was, but never opened or read ahead. An instance map
        // still reads and draws it.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, mogiRoot(new long[] {INTERIOR, GROUP}, new int[] {INT, EXT}));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(null, f.files.opens.get(INTERIOR), "skipped before reading");
            }
            try (var set = f.runInstance()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertTrue(f.files.opens.get(INTERIOR).get() > 0);
            }
        }
    }

    @Test
    void aReadGroupFollowsItsOwnMogpFlagsOverMogi() {
        // MOGI says exterior for a group whose MOGP says interior: it is read, and its MOGP flags
        // drop it as GROUP_INTERIOR. Interior wins when MOGI records both flags.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(
                    ROOT,
                    mogiRoot(
                            new long[] {INTERIOR, GROUP, GROUP + 50},
                            new int[] {EXT, EXT, EXT | INT}));
            try (var set = f.run()) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertTrue(f.files.opens.get(INTERIOR).get() > 0, "MOGI exterior is read");
                assertEquals(null, f.files.opens.get(GROUP + 50), "MOGI interior wins");
            }
        }
    }

    @Test
    void mogiThatDoesNotCoverEveryGroupSkipsNothing() {
        // One MOGI record for two groups is not retained: every group is read, as without MOGI.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {INTERIOR, GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            mogi(INT)));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertTrue(f.files.opens.get(INTERIOR).get() > 0);
            }
        }
    }

    @Test
    void mogiInteriorsDoNotSpendTheTileGroupReads() {
        // A 300-group city root (over the old 256 ceiling) with 280 MOGI-interior groups reads only
        // its 20 exterior groups, so it is not capped by MAX_GROUPS, and a later 200-group root
        // still fits the tile's reads. Every skipped interior is counted GROUP_INTERIOR.
        long city = 800, later = 801;
        var cityGroups = new long[300];
        var cityFlags = new int[300];
        for (int g = 0; g < 300; g++) {
            boolean interior = g % 15 != 0; // 20 exterior groups spread through the root
            cityGroups[g] = interior ? INTERIOR : GROUP;
            cityFlags[g] = interior ? INT : EXT;
        }
        try (var f = new Fixture(List.of(Modf.of(city, 1, 0x8), Modf.of(later, 2, 0x8)))) {
            f.files.sources.put(city, mogiRoot(cityGroups, cityFlags));
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(later, mogiRoot(repeat(GROUP + 50, 200), repeatFlags(EXT, 200)));
            try (var set = f.run()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(280, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(220, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(null, f.files.opens.get(INTERIOR));
            }
            // An instance map reads every group, so the same city root is over the tile's reads.
            try (var set = f.runInstance()) {
                assertTrue(set.count(WorldExteriorObjects.Count.ROOT_CAPPED) >= 1);
            }
            // On an open-world map both roots' exteriors are read first (220 reads); the city's
            // 280 interiors do not fit the 36 reads left, so they are capped whole and never
            // read, and neither root is capped (#383).
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(220, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(280, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(null, f.files.opens.get(INTERIOR), "capped interiors are not read");
            }
        }
    }

    @Test
    void openWorldInteriorsAreReadAfterEveryExteriorAndDraw() {
        // A larger root with 2 exterior and 3 MOGI-interior groups, and a smaller exterior-only
        // root. On an open-world map every group draws, and the larger root's interiors are
        // opened only after the smaller root's exterior group.
        long cave = 810, hut = 811, hutGroup = GROUP + 60;
        var files = new OrderedFiles();
        try (var f =
                new Fixture(
                        List.of(Modf.of(cave, 1, 0x8).sized(50), Modf.of(hut, 2, 0x8)), files)) {
            f.files.sources.put(
                    cave,
                    mogiRoot(
                            new long[] {GROUP, INTERIOR, INTERIOR, INTERIOR, GROUP},
                            new int[] {EXT, INT, INT, INT, EXT}));
            f.files.sources.put(hutGroup, group(EXT));
            f.files.sources.put(hut, mogiRoot(new long[] {hutGroup}, new int[] {EXT}));
            try (var set = f.run()) {
                assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
            files.order.clear();
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(6, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertTrue(
                        files.order.indexOf(hutGroup) < files.order.indexOf(INTERIOR),
                        "interiors are opened after every exterior: " + files.order);
            }
            // On an instance map the larger root reads its interiors with its exteriors, before
            // the smaller root is read at all.
            files.order.clear();
            try (var set = f.runInstance()) {
                assertEquals(6, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertTrue(
                        files.order.indexOf(INTERIOR) < files.order.indexOf(hutGroup),
                        "instance interiors are read inline: " + files.order);
            }
        }
    }

    @Test
    void instanceMapsReadADungeonRootWholeBeforeSmallerRoots() {
        // A dungeon's own building (the largest root) has 6 exterior and 240 MOGI-interior groups;
        // a smaller exterior root has 20. On an instance map the dungeon root is read whole first
        // and drawn, and the smaller root no longer fits the tile's reads. On an open-world map
        // the exteriors come first instead: the smaller root draws and the dungeon's interiors
        // are capped.
        long dungeon = 850, small = 851;
        var groups = new long[246];
        var flags = new int[246];
        for (int g = 0; g < 246; g++) {
            boolean interior = g >= 6;
            groups[g] = interior ? INTERIOR : GROUP;
            flags[g] = interior ? INT : EXT;
        }
        try (var f =
                new Fixture(List.of(Modf.of(dungeon, 1, 0x8).sized(90), Modf.of(small, 2, 0x8)))) {
            f.files.sources.put(dungeon, mogiRoot(groups, flags));
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(small, mogiRoot(repeat(GROUP + 50, 20), repeatFlags(EXT, 20)));
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(246, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertTrue(set.placements().stream().noneMatch(p -> p.rootId() == small));
            }
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(26, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(240, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
            }
        }
    }

    @Test
    void interiorImagesNeverLowerExteriorImageLevels() {
        // 40 exterior and 24 interior groups, each drawing its own 512^2 BC1 image, on a session
        // sampling BC1 (512 sides at their compressed cost): the building pool cannot give every
        // image 512. Exterior levels are allotted first, from the pool less the interiors'
        // smallest levels, so here they are the same with and without interiors; the interiors
        // get what is left, and on every map each keeps at least its smallest side.
        int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        int exteriors = 40, interiors = 24, n = exteriors + interiors;
        var gfid = new long[n];
        var mogiFlags = new int[n];
        var materials = new ArrayList<Material>();
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            for (int g = 0; g < n; g++) {
                boolean interior = g >= exteriors;
                gfid[g] = 7000 + g;
                mogiFlags[g] = interior ? INT : EXT;
                materials.add(new Material(0, 9000 + g, 0));
                f.files.sources.put(
                        gfid[g], group(interior ? INT : EXT, 3, 1, List.of(new Batch(0, 3, 0, g))));
                f.files.sources.put(9000L + g, blp(512, 512, 10, 0x07e0));
            }
            f.files.sources.put(ROOT, root(n, gfid, materials, mogi(mogiFlags)));
            var without = new java.util.TreeMap<Long, Integer>();
            try (var set = prepareWith(f, WorldExteriorObjects.Interiors.NONE, bc1)) {
                for (var image : set.images()) {
                    without.put(image.id(), image.width());
                }
            }
            assertEquals(exteriors, without.size());
            assertTrue(without.containsValue(256), "the exteriors alone already compete");
            var with = new java.util.TreeMap<Long, Integer>();
            int interiorImages = 0;
            try (var set = prepareWith(f, WorldExteriorObjects.Interiors.OPEN_WORLD, bc1)) {
                for (var image : set.images()) {
                    if (image.id() < 9000 + exteriors) {
                        with.put(image.id(), image.width());
                    } else {
                        interiorImages++;
                    }
                }
            }
            assertEquals(without, with, "exterior levels are unchanged by interiors");
            assertEquals(interiors, interiorImages);
            // A dungeon or raid map shares the pool the same way: every interior image is drawn,
            // at its smallest side or larger.
            var instance = new java.util.TreeMap<Long, Integer>();
            try (var set = prepareWith(f, WorldExteriorObjects.Interiors.INSTANCE, bc1)) {
                assertEquals(n, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                for (var image : set.images()) {
                    if (image.id() >= 9000 + exteriors) {
                        assertTrue(image.width() >= 32, "smallest side kept: " + image.id());
                        instance.put(image.id(), image.width());
                    } else {
                        assertEquals(without.get(image.id()), image.width());
                    }
                }
            }
            assertEquals(interiors, instance.size());
        }
    }

    @Test
    void aRefusedSecondReadOfAnInteriorImageNeverFailsTheSet() {
        // Nothing is retained, so each opaque image is read again for its allotted level. The
        // interior image's second read is refused at SOURCE_INPUT: it keeps its registered level
        // and the set builds. The same refusal on the exterior image still fails the set.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingSecondRead(IMAGE2))) {
            putTwoImageRoot(f);
            try (var set = prepareWith(f, WorldExteriorObjects.Interiors.OPEN_WORLD, 0, 0)) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(2, f.files.opens.get(IMAGE2).get(), "the second read was tried");
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingSecondRead(IMAGE))) {
            putTwoImageRoot(f);
            assertThrows(
                    WorldTerrainStreaming.Refusal.class,
                    () -> prepareWith(f, WorldExteriorObjects.Interiors.OPEN_WORLD, 0, 0));
        }
    }

    /** An exterior group drawing IMAGE and a MOGI-interior group drawing IMAGE2, both 512^2. */
    private static void putTwoImageRoot(Fixture f) {
        f.files.sources.put(
                ROOT,
                root(
                        2,
                        new long[] {GROUP, INTERIOR},
                        List.of(new Material(0, IMAGE, 0), new Material(0, IMAGE2, 0)),
                        mogi(EXT, INT)));
        f.files.sources.put(GROUP, group(EXT, 3, 1, List.of(new Batch(0, 3, 0, 0))));
        f.files.sources.put(INTERIOR, group(INT, 3, 1, List.of(new Batch(0, 3, 0, 1))));
        f.files.sources.put(IMAGE, blp(512, 512, 10, 0x07e0));
        f.files.sources.put(IMAGE2, blp(512, 512, 10, 0x001f));
    }

    @Test
    void anUnreadableInteriorGroupOrImageNeverCostsTheExterior() {
        // A MOGI-interior group refused at SOURCE_INPUT (not readable now) is only unavailable;
        // the exterior still draws and the set does not fail.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingReads(INTERIOR, 1))) {
            f.files.sources.put(ROOT, mogiRoot(new long[] {GROUP, INTERIOR}, new int[] {EXT, INT}));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_UNAVAILABLE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
            }
        }
        // An interior group whose only opaque image is refused at SOURCE_INPUT is omitted alone.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), refusingReads(IMAGE2, 1))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, INTERIOR},
                            List.of(new Material(0, IMAGE, 0), new Material(0, IMAGE2, 0)),
                            mogi(EXT, INT)));
            f.files.sources.put(INTERIOR, group(INT, 3, 1, List.of(new Batch(0, 3, 0, 1))));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.models().getFirst().meshes().size());
                assertTrue(set.count(WorldExteriorObjects.Count.IMAGE_UNAVAILABLE) >= 1);
            }
        }
    }

    @Test
    void interiorReadsNeverCapALaterExteriorRoot() {
        // The first (largest) root has 6 exterior and 200 MOGI-interior groups; a later root has
        // 200 exterior groups. Exteriors come first: the later root is read whole and drawn, the
        // first root's interiors are capped whole, and a smaller third root's 40 interiors still
        // fit the reads that are left (256 - 206 - 6 = 44).
        long city = 820, later = 821, small = 822;
        var cityGroups = new long[206];
        var cityFlags = new int[206];
        for (int g = 0; g < 206; g++) {
            boolean interior = g >= 6;
            cityGroups[g] = interior ? INTERIOR : GROUP;
            cityFlags[g] = interior ? INT : EXT;
        }
        var smallGroups = new long[46];
        var smallFlags = new int[46];
        for (int g = 0; g < 46; g++) {
            boolean interior = g >= 6;
            smallGroups[g] = interior ? INTERIOR : GROUP;
            smallFlags[g] = interior ? INT : EXT;
        }
        try (var f =
                new Fixture(
                        List.of(
                                Modf.of(city, 1, 0x8).sized(90),
                                Modf.of(later, 2, 0x8).sized(60),
                                Modf.of(small, 3, 0x8).sized(30)))) {
            f.files.sources.put(city, mogiRoot(cityGroups, cityFlags));
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(later, mogiRoot(repeat(GROUP + 50, 200), repeatFlags(EXT, 200)));
            f.files.sources.put(small, mogiRoot(smallGroups, smallFlags));
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(3, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(200, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(6 + 200 + 46, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(
                        6,
                        set.models().stream()
                                .filter(m -> m.rootId() == city)
                                .findFirst()
                                .get()
                                .meshes()
                                .size(),
                        "the city keeps every exterior group");
                assertEquals(
                        46,
                        set.models().stream()
                                .filter(m -> m.rootId() == small)
                                .findFirst()
                                .get()
                                .meshes()
                                .size());
            }
        }
    }

    @Test
    void aLaterSetReadsNoSourceTheSessionCacheHolds() {
        var cache = new WorldSourceCache(1 << 20);
        try (var f =
                new Fixture(
                        List.of(Modf.of(ROOT, 1, 0x8)),
                        new WorldTerrainTextureSourceTest.Files(),
                        cache)) {
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
            }
            int root = f.files.opens.get(ROOT).get(), group = f.files.opens.get(GROUP).get();
            long hits = cache.hits();
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
            assertEquals(root, f.files.opens.get(ROOT).get(), "the root is read once");
            assertEquals(group, f.files.opens.get(GROUP).get(), "the group is read once");
            assertTrue(cache.hits() >= hits + 2);
            assertTrue(cache.bytes(WorldSourceCache.FILE) > 0);
        }
    }

    @Test
    void anInteriorOnlyRootWhoseReadsDoNotFitIsCapped() {
        // 250 exterior reads leave 6; a root whose 10 groups are all MOGI interior cannot be read
        // and has nothing else to draw: its root and placement are capped, not drawn empty.
        long big = 830, cave = 831;
        try (var f = new Fixture(List.of(Modf.of(big, 1, 0x8).sized(90), Modf.of(cave, 2, 0x8)))) {
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(big, mogiRoot(repeat(GROUP + 50, 250), repeatFlags(EXT, 250)));
            f.files.sources.put(cave, mogiRoot(repeat(INTERIOR, 10), repeatFlags(INT, 10)));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(10, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertTrue(set.placements().stream().noneMatch(p -> p.rootId() == cave));
                assertEquals(null, f.files.opens.get(INTERIOR));
            }
            // With reads to spare the same interior-only root draws.
            f.files.sources.put(big, mogiRoot(repeat(GROUP + 50, 200), repeatFlags(EXT, 200)));
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertTrue(set.placements().stream().anyMatch(p -> p.rootId() == cave));
            }
        }
    }

    @Test
    void skipInteriorNeedsRetainedMogiAndAnOpenWorldMap() {
        var flagged =
                WmoFormats.root(
                        mogiRoot(new long[] {GROUP, GROUP}, new int[] {INT, EXT}), () -> false);
        assertTrue(WorldExteriorObjects.skipInterior(flagged, 0, false));
        assertFalse(WorldExteriorObjects.skipInterior(flagged, 1, false));
        assertFalse(WorldExteriorObjects.skipInterior(flagged, 0, true), "interiors read all");
        assertTrue(WorldExteriorObjects.deferInterior(flagged, 0, true), "read after exteriors");
        assertFalse(WorldExteriorObjects.deferInterior(flagged, 1, true));
        assertFalse(WorldExteriorObjects.deferInterior(flagged, 0, false));
        var bare = WmoFormats.root(root(GROUP, GROUP), () -> false);
        assertFalse(bare.groupInfoRetained());
        assertFalse(WorldExteriorObjects.skipInterior(bare, 0, false));
        assertFalse(WorldExteriorObjects.deferInterior(bare, 0, true));
    }

    @Test
    void anInteriorOnlyRootNeedsAFreeBuildingSlot() {
        // A larger interior-only root is admitted first without taking a slot (it has no opaque
        // draws yet); 64 exterior placements then fill every slot. Its interior must not make
        // it a 65th building: it is capped, not drawn.
        long interiorRoot = 120;
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 64; i++) {
            records.add(Modf.of(ROOT, 1 + i, 0x8));
        }
        records.add(Modf.of(interiorRoot, 100, 0x8).sized(50));
        try (var f = new Fixture(records)) {
            f.files.sources.put(interiorRoot, root(INTERIOR));
            try (var set = f.runInstance()) {
                assertEquals(64, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(64, set.placements().size());
                assertTrue(set.placements().stream().noneMatch(p -> p.rootId() == interiorRoot));
            }
            // With a free slot the same interior-only root draws.
            records.removeFirst();
        }
        try (var f = new Fixture(records)) {
            f.files.sources.put(interiorRoot, root(INTERIOR));
            try (var set = f.runInstance()) {
                assertEquals(64, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertTrue(set.placements().stream().anyMatch(p -> p.rootId() == interiorRoot));
            }
        }
    }

    @Test
    void doodadsKeepTheirDrawsAheadOfInstanceInteriors() {
        // One exterior building (1 draw), an interior-only root placed 60 times and 300 MDDF
        // doodads (1 draw each). The interior's batch count is derived from MAX_DRAWS so that the
        // interior alone fits but not after the doodads: the doodads and the exterior are spent
        // first, and the interior is what is capped, never a doodad. The batches are split over
        // interior groups of at most WmoGroupSpans.MAX_BATCHES each, admitted group by group, so
        // the last group is the one that no longer fits.
        long interiorRoot = 120;
        int batchCount = (WorldExteriorObjects.MAX_DRAWS - 1 - 300) / 60 + 1;
        int perGroup = com.vibeminer.formats.world.WmoGroupSpans.MAX_BATCHES;
        var interiorGroups = new long[(batchCount + perGroup - 1) / perGroup];
        var interiorSources = new java.util.LinkedHashMap<Long, byte[]>();
        for (int g = 0; g < interiorGroups.length; g++) {
            interiorGroups[g] = INTERIOR + 10L * g;
            int size = Math.min(perGroup, batchCount - g * perGroup);
            var batches = new ArrayList<Batch>();
            for (int b = 0; b < size; b++) {
                batches.add(new Batch(b * 3, 3, 0, 0));
            }
            interiorSources.put(interiorGroups[g], group(INT, 3, size, batches));
        }
        var records = new ArrayList<Modf>();
        records.add(Modf.of(ROOT, 1, 0x8));
        for (int i = 0; i < 60; i++) {
            records.add(Modf.of(interiorRoot, 100 + i, 0x8));
        }
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < 300; i++) {
            doodads.add(Mddf.of(MODEL, 1000 + i, 0x40));
        }
        try (var f = new Fixture(records)) {
            putModel(f, MODEL, model());
            f.files.sources.put(interiorRoot, root(interiorGroups));
            f.files.sources.putAll(interiorSources);
            f.files.sources.put(4L, obj0(records, doodads));
            assertTrue(1 + 60 * batchCount + 300 > WorldExteriorObjects.MAX_DRAWS);
            assertTrue(1 + 60 * batchCount <= WorldExteriorObjects.MAX_DRAWS);
            try (var set = f.runInstance()) {
                assertEquals(300, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertTrue(interiorGroups.length > 1);
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(
                        interiorGroups.length - 1,
                        set.models().stream()
                                .filter(m -> m.rootId() == interiorRoot)
                                .mapToInt(m -> m.meshes().size())
                                .sum());
                assertTrue(set.placements().stream().anyMatch(p -> p.rootId() == ROOT));
            }
        }
        // With only the doodads that fit (no more than MAX_DRAWS leaves), the interior fits after
        // them and draws too.
        try (var f = new Fixture(records)) {
            putModel(f, MODEL, model());
            f.files.sources.put(interiorRoot, root(interiorGroups));
            f.files.sources.putAll(interiorSources);
            int fitting = WorldExteriorObjects.MAX_DRAWS - 1 - 60 * batchCount;
            f.files.sources.put(4L, obj0(records, doodads.subList(0, fitting)));
            try (var set = f.runInstance()) {
                assertEquals(fitting, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(
                        60,
                        set.placements().stream().filter(p -> p.rootId() == interiorRoot).count());
            }
        }
    }

    @Test
    void interiorsNeverCostExteriorCutoutOrBlendedDraws() {
        // 64 placements of a building with a group of 64 opaque batches and a group of cutout
        // batches draw all but 64 of the draw budget (MAX_DRAWS / 64 - 1 batches per placement).
        // A 2-range interior would need 128 more: it is capped, and the exterior's cutout batches
        // are all still drawn, exactly as without interiors.
        int perPlacement = WorldExteriorObjects.MAX_DRAWS / 64 - 1;
        int cutouts = perPlacement - 64;
        assertTrue(cutouts > 0 && cutouts <= com.vibeminer.formats.world.WmoGroupSpans.MAX_BATCHES);
        var opaque = new ArrayList<Batch>();
        for (int i = 0; i < 64; i++) {
            opaque.add(new Batch(i * 3, 3, 0, 0));
        }
        var cutout = new ArrayList<Batch>();
        for (int i = 0; i < cutouts; i++) {
            cutout.add(new Batch(i * 3, 3, 0, 1));
        }
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 64; i++) {
            records.add(Modf.of(ROOT, 1 + i, 0x8));
        }
        java.util.function.Function<Boolean, Map<WorldExteriorObjects.Count, Integer>> run =
                interiors -> {
                    try (var f = new Fixture(records)) {
                        f.files.sources.put(
                                ROOT,
                                root(
                                        3,
                                        new long[] {GROUP, GROUP + 2, INTERIOR},
                                        List.of(
                                                new Material(0, IMAGE, 0),
                                                new Material(1, IMAGE2, 0))));
                        f.files.sources.put(GROUP, group(EXT, 3, 64, opaque));
                        f.files.sources.put(GROUP + 2, group(EXT, 3, cutouts, cutout));
                        f.files.sources.put(
                                INTERIOR,
                                group(
                                        INT,
                                        3,
                                        2,
                                        List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 0))));
                        try (var set = interiors ? f.runInstance() : f.run()) {
                            return counts(set);
                        }
                    }
                };
        var without = run.apply(false);
        var with = run.apply(true);
        assertEquals(cutouts, with.get(WorldExteriorObjects.Count.BATCH_CUTOUT));
        assertEquals(
                without.get(WorldExteriorObjects.Count.BATCH_CUTOUT),
                with.get(WorldExteriorObjects.Count.BATCH_CUTOUT));
        assertEquals(
                without.get(WorldExteriorObjects.Count.BATCH_DRAWN),
                with.get(WorldExteriorObjects.Count.BATCH_DRAWN));
        assertNull(with.get(WorldExteriorObjects.Count.BATCH_CAPPED));
        assertEquals(1, with.get(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
    }

    @Test
    void anInteriorOnlyRootWhoseGroupsAllFailIsRemovedWithItsDoodads() {
        // The held interior group's image is missing, so nothing draws: the root and its
        // placement are EMPTY, and its doodad set is not placed.
        var chunks =
                doodadChunks(
                        new int[][] {{0, 1}},
                        new long[] {MODEL},
                        List.of(new Modd(0, 0, 0, 0, new double[] {0, 0, 0, 1}, 1)));
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT,
                    root(1, new long[] {INTERIOR}, List.of(new Material(0, 4444, 0)), chunks));
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_EMPTY));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertTrue(set.placements().isEmpty());
                assertTrue(set.models().isEmpty());
            }
        }
    }

    @Test
    void heldInteriorCopiesAreBoundedWhileReading() {
        // Four 65,536-vertex interior groups: three fit the held bound (the building pool, the
        // interior allowance and the cutout budget together, 196,608 vertices); the fourth is
        // counted capped as it is read. Of the three held, two fit what the exterior left of the
        // pool and the interior allowance (163,837 vertices).
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(GROUP, 211, 212, 213, 214));
            for (long id = 211; id <= 214; id++) {
                f.files.sources.put(id, group(INT, 65536, 1, List.of(new Batch(0, 3, 0, 0))));
            }
            try (var set = f.runInstance()) {
                assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(3 + 2 * 65536, set.vertices());
            }
        }
    }

    /** {@code count} batches of one triangle each, all of material 0. */
    private static List<Batch> batches(int count) {
        var list = new ArrayList<Batch>();
        for (int b = 0; b < count; b++) {
            list.add(new Batch(3 * b, 3, 0, 0));
        }
        return list;
    }

    @Test
    void tileInteriorsNeverCostASplitRootsExteriorItsDraws() {
        // Seven placements of the tile's building, with 130 interior groups of 64 ranges: each
        // group costs 448 draws, far past the 8,192 budget. The split root's two exteriors here
        // have 64 ranges each (128 draws). Exteriors come first tile-wide: the split exteriors
        // draw in full, and the interiors take what is left, the rest capped. (Had the interiors
        // come first, they would have left 121 draws, too few for the split exteriors.)
        int interiors = 130;
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 7; i++) {
            records.add(Modf.of(ROOT, 1 + i, 0x8));
        }
        records.add(largePlacement());
        try (var f = new Fixture(records)) {
            long[] gfid = new long[interiors + 1];
            gfid[0] = GROUP;
            for (int g = 1; g <= interiors; g++) {
                gfid[g] = 20_000 + g;
                f.files.sources.put(gfid[g], group(INT, 3, 64, batches(64)));
            }
            f.files.sources.put(
                    ROOT, root(interiors + 1, gfid, List.of(new Material(0, IMAGE, 0))));
            f.files.sources.put(BIG + 1, group(EXT, 60_000, 64, batches(64)));
            f.files.sources.put(BIG + 2, group(EXT, 3, 64, batches(64)));
            f.files.sources.put(BIG + 3, bigGroup(EXT, 60_000));
            f.files.sources.put(
                    BIG,
                    root(
                            3,
                            new long[] {BIG + 1, BIG + 2, BIG + 3},
                            List.of(new Material(0, IMAGE, 0)),
                            mogiBoxes(new int[] {EXT, EXT, EXT}, new float[] {0, 1, 200})));
            try (var set = f.runInstance()) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                int drawn = groupsOf(set, ROOT).size() - 1;
                assertTrue(drawn > 0, "interiors take what is left");
                assertEquals(
                        interiors - drawn,
                        set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
            }
        }
    }

    @Test
    void tileInteriorsNeverCostASplitShareItsDraws() {
        // Seven placements of building D (its own tile's), with 130 MOGI-interior groups of 64
        // ranges: 448 draws a group. BIG is decided whole by its in-tile placement A and split by
        // the owner rule; its crossing placement C (77) draws its share here, exterior 0 with 64
        // ranges. The share's draws are kept for it before D's interiors are admitted: C's share
        // draws, and D's interiors are capped. (Unreserved, D's interiors would have left 56.)
        long d = 40_000;
        int interiors = 130;
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var records = new ArrayList<Modf>(List.of(inTile, largePlacement()));
        for (int i = 0; i < 7; i++) {
            records.add(Modf.of(d, 91 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            int[] flags = new int[interiors + 1];
            int[] sizes = new int[interiors + 1];
            float[] centres = new float[interiors + 1];
            flags[0] = EXT;
            sizes[0] = 3;
            for (int g = 1; g <= interiors; g++) {
                flags[g] = ROOM;
                sizes[g] = 3;
                centres[g] = 1;
            }
            putRoot(f, d, flags, sizes, centres);
            for (int g = 1; g <= interiors; g++) {
                f.files.sources.put(d + 1 + g, group(ROOM, 3, 64, batches(64)));
            }
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000},
                    new float[] {0, 200, 5, 205},
                    splitPortals());
            f.files.sources.put(BIG + 1, group(EXT, 3, 64, batches(64)));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertTrue(keysOf(set, 77).contains("77:0"));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertTrue(set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED) > 0);
            }
        }
    }

    @Test
    void splitInteriorsNeverCostALaterShareItsExteriors() {
        // Split root 970 has a 3-vertex exterior here and seven 60,000-vertex interiors here,
        // drawn from the large allowance (458,752 vertices with the interior one). BIG is decided
        // whole by its in-tile placement A and split by the owner rule; C's share here is its
        // 60,000-vertex exterior 0. The share is placed after the split root's interiors, but
        // its exterior is held first: it draws in full, and the interiors take what is left (six,
        // one capped). Unheld, the interiors would have left 38,749 vertices, too few for it.
        long other = 970;
        var split =
                new Modf(other, 90, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -900, 4100, 10500, 900, 4500);
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        try (var f = new Fixture(List.of(split, inTile, largePlacement()))) {
            putRoot(
                    f,
                    other,
                    new int[] {EXT, EXT, ROOM, ROOM, ROOM, ROOM, ROOM, ROOM, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 3, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 6, 7, 8, 9, 10, 11},
                    splitPortals());
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {60_000, 3, 60_000, 60_000},
                    new float[] {0, 200, 5, 205},
                    splitPortals());
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertTrue(keysOf(set, 77).contains("77:0"));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(List.of(0, 2, 4, 5, 6, 7, 8), groupsOf(set, other).subList(0, 7));
                assertTrue(set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED) >= 1);
            }
        }
    }

    @Test
    void tileInteriorsDrawFromTheirAllowanceButExteriorsNeverDo() {
        // A 65,536-vertex exterior leaves 32,768 vertices of the building pool; a 60,000-vertex
        // interior draws from that and the tile interior allowance (65,536 more).
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(INTERIOR, GROUP));
            f.files.sources.put(GROUP, group(EXT, 65536, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(INTERIOR, group(INT, 60000, 1, List.of(new Batch(0, 3, 0, 0))));
            try (var set = f.runInstance()) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(125_536, set.vertices());
                assertTrue(set.vertices() > WorldExteriorObjects.MAX_VERTICES);
            }
        }
        // Two 60,000-vertex exteriors (120,000) are over the building pool: the allowance is
        // never theirs, so the root falls back to the large allowance as before.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(GROUP, GROUP + 1));
            f.files.sources.put(GROUP, group(EXT, 60000, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(GROUP + 1, group(EXT, 60000, 1, List.of(new Batch(0, 3, 0, 0))));
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
        }
    }

    @Test
    void anInteriorThatDoesNotFitIsSkippedWithoutCappingTheExterior() {
        // A 65,536-vertex exterior group leaves 98,304 vertices of the building pool and the
        // interior allowance; of two 65,536-vertex interior groups recorded before it, the first
        // draws and the second is skipped alone, and the building keeps its exterior.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(INTERIOR, INTERIOR + 20, GROUP));
            f.files.sources.put(GROUP, group(EXT, 65536, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(INTERIOR, group(INT, 65536, 1, List.of(new Batch(0, 3, 0, 0))));
            f.files.sources.put(
                    INTERIOR + 20, group(INT, 65536, 1, List.of(new Batch(0, 3, 0, 0))));
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(2 * 65536, set.vertices());
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
                                    new Material(3, IMAGE, 0), // additive (#367): drawn, sorted
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
                                WorldExteriorObjects.Count.BATCH_DRAWN, 4,
                                WorldExteriorObjects.Count.BATCH_BLENDED, 3,
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
                                        3, 3, 1, 1, WorldExteriorObjects.BLENDED),
                                // The additive image is prepared as a blended one: same slot.
                                new WorldExteriorObjects.DrawRange(
                                        6, 3, 1, 2, WorldExteriorObjects.ADDITIVE),
                                new WorldExteriorObjects.DrawRange(
                                        9, 3, 1, 1, WorldExteriorObjects.BLENDED)),
                        set.models().getFirst().meshes().getFirst().ranges());
            }
        }
    }

    /**
     * Issue #374 with #367's range classes: one DXT file drawn by every kind, lit and unlit, takes
     * the format the objects file admits for that class. BC1 only for opaque ranges (unlit too);
     * blended and additive ones carry BC2/BC3 or fall back to RGBA8; cutout ones always RGBA8.
     */
    @Test
    void eachRangeClassCarriesTheBlockFormatItsClassAdmits() {
        int bc1 = com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
        int bc3 = com.vibeminer.rendering.world.ObjectTextureFormats.BC3;
        int rgba = com.vibeminer.rendering.world.ObjectTextureFormats.RGBA8;
        int o = WorldExteriorObjects.OPAQUE, c = WorldExteriorObjects.CUTOUT;
        int b = WorldExteriorObjects.BLENDED, a = WorldExteriorObjects.ADDITIVE;
        int u = WorldExteriorObjects.UNLIT;
        int[] classes = {o, o | u, c, c | u, b, b | u, a, a | u};
        // Per class: the format from a DXT1 file, then from a DXT5 file.
        int[][] expected = {
            {bc1, bc3}, {bc1, bc3}, {rgba, rgba}, {rgba, rgba},
            {rgba, bc3}, {rgba, bc3}, {rgba, bc3}, {rgba, bc3}
        };
        for (int file = 0; file < 2; file++) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                var materials = new ArrayList<Material>();
                var batches = new ArrayList<Batch>();
                for (int i = 0; i < classes.length; i++) {
                    materials.add(new Material(WorldExteriorObjects.kind(classes[i]), IMAGE, 0));
                    batches.add(new Batch(i * 3, 3, 0, i));
                }
                byte[] root = root(1, new long[] {GROUP}, materials);
                int momt = indexOf(root, "MOMT") + 8;
                var data = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < classes.length; i++) {
                    if ((classes[i] & u) != 0) {
                        data.putInt(momt + i * 64, 0x1); // MOMT F_UNLIT
                    }
                }
                f.files.sources.put(ROOT, root);
                f.files.sources.put(GROUP, group(EXT, 3, classes.length, batches));
                f.files.sources.put(
                        IMAGE, file == 0 ? blp(256, 256, 9, 0x07e0) : blp3(256, 256, 9, 0x07e0));
                try (var set = f.run()) {
                    var ranges = set.models().getFirst().meshes().getFirst().ranges();
                    assertEquals(classes.length, ranges.size());
                    for (int i = 0; i < classes.length; i++) {
                        var range = ranges.get(i);
                        assertEquals(classes[i], range.blend(), "range " + i);
                        int format = set.images().get(range.image()).format();
                        assertEquals(
                                expected[i][file],
                                format,
                                "class 0x" + Integer.toHexString(classes[i]) + " file " + file);
                        // The rule the producer applies, exactly as the objects file reads it.
                        int rule =
                                WorldExteriorObjects.blockFormatFor(
                                        file == 0 ? bc1 : bc3, classes[i]);
                        assertEquals(rule < 0 ? rgba : rule, format);
                    }
                }
            }
        }
    }

    /**
     * Issue #367: MOMT flags bit 0x1 (F_UNLIT) marks a range unlit, whatever its kind; blend mode 3
     * is additive; modes above 3 (modulate and variants) stay unsupported. Bit 0x4 (F_UNCULLED,
     * #375) marks it two-sided.
     */
    @Test
    void unlitFlagAndAdditiveBlendMapToRangeClasses() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            byte[] root =
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(
                                    new Material(0, IMAGE, 0), // lit opaque
                                    new Material(0, IMAGE, 0), // unlit opaque (flag set below)
                                    new Material(3, IMAGE, 0), // unlit additive (flag below)
                                    new Material(4, IMAGE, 0), // modulate: unsupported
                                    new Material(5, IMAGE, 0))); // modulate 2x: unsupported
            int momt = indexOf(root, "MOMT") + 8;
            var data = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(momt + 64, 0x1);
            data.putInt(momt + 2 * 64, 0x1 | 0x4);
            f.files.sources.put(ROOT, root);
            var batches = new ArrayList<Batch>();
            for (int material = 0; material < 5; material++) {
                batches.add(new Batch(material * 3, 3, 0, material));
            }
            f.files.sources.put(GROUP, group(EXT, 3, 5, batches));
            try (var set = f.run()) {
                var ranges = set.models().getFirst().meshes().getFirst().ranges();
                assertEquals(
                        List.of(
                                new WorldExteriorObjects.DrawRange(
                                        0, 3, 0, 0, WorldExteriorObjects.OPAQUE),
                                new WorldExteriorObjects.DrawRange(
                                        3,
                                        3,
                                        0,
                                        1,
                                        WorldExteriorObjects.OPAQUE | WorldExteriorObjects.UNLIT),
                                new WorldExteriorObjects.DrawRange(
                                        6,
                                        3,
                                        1,
                                        2,
                                        WorldExteriorObjects.ADDITIVE
                                                | WorldExteriorObjects.UNLIT
                                                | WorldExteriorObjects.TWO_SIDED)),
                        ranges);
                assertEquals(2, counts(set).get(WorldExteriorObjects.Count.BATCH_UNSUPPORTED));
                assertEquals(
                        WorldExteriorObjects.ADDITIVE,
                        WorldExteriorObjects.kind(ranges.get(2).blend()));
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
                                WorldExteriorObjects.Count.IMAGE_DECODED, 2,
                                // MOGP 0x4 with a first MOCV: its colours are carried.
                                WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS, 1),
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

    /** One exterior group as {@link #group(int)}, with a first MOCV appended to its MOGP. */
    private static byte[] groupWithColours(int flags, byte[] mocv) {
        var classic = group(flags);
        int body = indexOf(classic, "MOGP") + 8;
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                Arrays.copyOfRange(classic, body, classic.length),
                                chunk("MOCV", mocv))));
    }

    private static int[] colours(WorldExteriorObjects.TileObjects set) {
        var buffer = set.models().getFirst().meshes().getFirst().colours();
        var out = new int[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    /**
     * Issue #375: an exterior group's first MOCV (B, G, R, A per vertex) is carried as {@code r | g
     * << 8 | b << 16} only under MOGP flag 0x4; the alpha byte is dropped. Without the flag, or
     * without the chunk, the mesh has no colours.
     */
    @Test
    void vertexColoursAreCarriedOnlyUnderTheirGroupFlag() {
        byte[] mocv = {
            0x10,
            0x20,
            0x30,
            (byte) 0xff, // vertex 0: B, G, R, A
            0x01,
            0x02,
            0x03,
            0x00,
            (byte) 0xff,
            (byte) 0x80,
            0x00,
            0x7f
        };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(GROUP, groupWithColours(EXT | 0x4, mocv));
            try (var set = f.run()) {
                assertArrayEquals(new int[] {0x102030, 0x010203, 0xff8000}, colours(set));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(GROUP, groupWithColours(EXT, mocv));
            try (var set = f.run()) {
                assertArrayEquals(new int[0], colours(set));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS));
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(GROUP, group(EXT | 0x4));
            try (var set = f.run()) {
                assertArrayEquals(new int[0], colours(set));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS));
            }
        }
    }

    /** {@link #groupWithColours} with vertex 2 moved so the triangle has area (winding counts). */
    private static byte[] colouredWithArea(int flags, byte[] mocv) {
        var bytes = groupWithColours(flags, mocv);
        int movt = indexOf(bytes, "MOVT") + 8;
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(movt + 2 * 12, 0)
                .putFloat(movt + 2 * 12 + 4, 1);
        return bytes;
    }

    private static WorldExteriorObjects.Mesh interiorMesh(WorldExteriorObjects.TileObjects set) {
        return set.models().stream()
                .flatMap(model -> model.meshes().stream())
                .filter(mesh -> (mesh.groupFlags() & 0x2000) != 0)
                .findFirst()
                .orElseThrow();
    }

    private static int[] colours(WorldExteriorObjects.Mesh mesh) {
        var buffer = mesh.colours();
        var out = new int[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    /**
     * Issue #383: an interior group (MOGP 0x2000) with MOGP 0x4 and a MOCV is lit by its vertex
     * colours instead of the World sun. On a dungeon or raid map (#280) its colours are carried as
     * the client lights them: the MOCV adjusted at load plus the root's MOHD ambient (0x0b on each
     * channel in the fixture), never darker than the ambient ({@link WmoInteriorLight}). Its ranges
     * carry {@link WorldExteriorObjects#INTERIOR_LIGHT}, and it is counted as an interior light
     * group, not in the exterior colour or winding evidence. The same group as an exterior one
     * carries the same colours and no flag.
     */
    @Test
    void instanceInteriorGroupsAreLitByTheirAdjustedVertexColoursAndAmbient() {
        byte[] mocv = {
            0x10, 0x20, 0x30, (byte) 0xff, 0x01, 0x02, 0x03, 0x00, (byte) 0xfa, 0x05, 0x06, 0
        };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(INTERIOR));
            f.files.sources.put(INTERIOR, colouredWithArea(INTERIOR_ONLY | 0x4, mocv));
            try (var set = f.runInstance()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                // R 48 with alpha 255: ((48 * 255 >> 6) + 48 - 11) / 2 = 114, light 239; B 250
                // with alpha 0: (250 - 11) / 2 = 119, light 249; dark channels keep the ambient.
                assertArrayEquals(
                        new int[] {
                            WorldExteriorObjects.packColour(79, 159, 239),
                            0x0b0b0b,
                            WorldExteriorObjects.packColour(249, 11, 11)
                        },
                        colours(interiorMesh(set)));
                assertArrayEquals(interiorLight(mocv), colours(interiorMesh(set)));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_AGREES));
                assertEquals(0, set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_OPPOSES));
                for (var range : interiorMesh(set).ranges()) {
                    assertEquals(
                            WorldExteriorObjects.OPAQUE | WorldExteriorObjects.INTERIOR_LIGHT,
                            range.blend());
                    assertEquals(
                            WorldExteriorObjects.OPAQUE, WorldExteriorObjects.kind(range.blend()));
                }
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, root(GROUP));
            f.files.sources.put(GROUP, colouredWithArea(EXT | 0x4, mocv));
            try (var set = f.runInstance()) {
                assertArrayEquals(new int[] {0x102030, 0x010203, 0xfa0506}, colours(set));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_VERTEX_COLOURS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                assertEquals(
                        List.of(WorldExteriorObjects.OPAQUE),
                        set.models().getFirst().meshes().getFirst().ranges().stream()
                                .map(WorldExteriorObjects.DrawRange::blend)
                                .toList());
                assertEquals(
                        1,
                        set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_AGREES)
                                + set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_OPPOSES));
            }
        }
    }

    /**
     * Issue #383 on open-world maps: a MOGI-interior group read in the interior pass is lit by its
     * own light like an instance interior: its MOCV as the client adjusts it plus the MOHD ambient
     * ({@link WmoInteriorLight}), or the ambient alone without a MOCV. A group also flagged
     * exterior (MOGP 0x8) is lit as outdoors: no colours without MOGP 0x4 and a MOCV, and no {@link
     * WorldExteriorObjects#INTERIOR_LIGHT}.
     */
    @Test
    void openWorldInteriorsAreLitByTheirOwnLight() {
        byte[] mocv = {0x10, 0x20, 0x30, (byte) 0xff, 0x01, 0x02, 0x03, 0x00, 0x04, 0x05, 0x06, 0};
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, mogiRoot(new long[] {GROUP, INTERIOR}, new int[] {EXT, INT}));
            f.files.sources.put(INTERIOR, colouredWithArea(INTERIOR_ONLY | 0x4, mocv));
            try (var set = f.runOpenWorld()) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                var interior = interiorMesh(set);
                assertArrayEquals(interiorLight(mocv), colours(interior));
                // Colours under the ambient leave the ambient.
                assertEquals(0x0b0b0b, colours(interior)[1]);
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                assertTrue(
                        interior.ranges().stream()
                                .allMatch(
                                        r ->
                                                (r.blend() & WorldExteriorObjects.INTERIOR_LIGHT)
                                                        != 0));
                // The exterior group keeps the World light.
                for (var model : set.models()) {
                    for (var mesh : model.meshes()) {
                        if (mesh != interior) {
                            assertTrue(
                                    mesh.ranges().stream()
                                            .noneMatch(
                                                    r ->
                                                            (r.blend()
                                                                            & WorldExteriorObjects
                                                                                    .INTERIOR_LIGHT)
                                                                    != 0));
                        }
                    }
                }
            }
        }
        for (byte[] group : new byte[][] {colouredWithArea(INT, mocv), group(INT)}) {
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(
                        ROOT, mogiRoot(new long[] {GROUP, INTERIOR}, new int[] {EXT, INT}));
                f.files.sources.put(INTERIOR, group);
                try (var set = f.runOpenWorld()) {
                    assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                    var interior = interiorMesh(set);
                    assertArrayEquals(new int[0], colours(interior));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                    assertEquals(
                            1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_EXTERIOR_LIT));
                    assertTrue(
                            interior.ranges().stream()
                                    .noneMatch(
                                            r ->
                                                    (r.blend()
                                                                    & WorldExteriorObjects
                                                                            .INTERIOR_LIGHT)
                                                            != 0));
                }
            }
        }
        // A pure interior group without a MOCV is lit by the ambient alone.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, mogiRoot(new long[] {GROUP, INTERIOR}, new int[] {EXT, INT}));
            f.files.sources.put(INTERIOR, group(INTERIOR_ONLY));
            try (var set = f.runOpenWorld()) {
                var interior = interiorMesh(set);
                assertTrue(colours(interior).length > 0);
                for (int colour : colours(interior)) {
                    assertEquals(0x0b0b0b, colour);
                }
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_AMBIENT_ONLY));
                assertTrue(
                        interior.ranges().stream()
                                .allMatch(
                                        r ->
                                                (r.blend() & WorldExteriorObjects.INTERIOR_LIGHT)
                                                        != 0));
            }
        }
    }

    /**
     * A group whose MOGP flags say both exterior (0x8) and interior (0x2000) is lit as outdoors, as
     * the client lights it: FixColorVertexAlpha marks its vertices outdoor (alpha 255) and the WMO
     * shader blends them fully to the exterior light. Its MOCV is the addition to the World light
     * an exterior group carries (#375), with no {@link WorldExteriorObjects#INTERIOR_LIGHT}.
     * Exterior lit (0x40) does the same.
     */
    @Test
    void aGroupFlaggedExteriorAndInteriorIsLitAsOutdoors() {
        byte[] mocv = {0x10, 0x20, 0x30, (byte) 0xff, 0x01, 0x02, 0x03, 0x00, 0x04, 0x05, 0x06, 0};
        for (int outdoor : new int[] {0x8, 0x40}) {
            int flags = 0x2000 | 0x4 | outdoor;
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
                f.files.sources.put(ROOT, root(INTERIOR));
                f.files.sources.put(INTERIOR, colouredWithArea(flags, mocv));
                try (var set = f.runInstance()) {
                    var mesh = interiorMesh(set);
                    assertEquals(flags, mesh.groupFlags() & flags);
                    assertArrayEquals(new int[] {0x102030, 0x010203, 0x040506}, colours(mesh));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_LIGHT));
                    assertEquals(
                            1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_EXTERIOR_LIT));
                    assertTrue(
                            mesh.ranges().stream()
                                    .noneMatch(
                                            r ->
                                                    (r.blend()
                                                                    & WorldExteriorObjects
                                                                            .INTERIOR_LIGHT)
                                                            != 0));
                }
            }
        }
    }

    /**
     * Root portal chunks (#383): {@code portals} triangles in the plane x = 1, the first leading
     * from group 0 to group 1 and back when {@code door}.
     */
    private static byte[] portalChunks(int portals, boolean door) {
        var v = buffer(portals * 36);
        var t = buffer(portals * 20);
        for (int i = 0; i < portals; i++) {
            v.put(floats(1, 0, 0, 1, 1, 0, 1, 1, 1));
            t.putShort((short) (i * 3)).putShort((short) 3).put(floats(1, 0, 0, -1));
        }
        var r = buffer(door ? 16 : 0);
        if (door) {
            r.putShort((short) 0).putShort((short) 1).putShort((short) -1).putShort((short) 0);
            r.putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 0);
        }
        return join(chunk("MOPV", v.array()), chunk("MOPT", t.array()), chunk("MOPR", r.array()));
    }

    /** A group file with its MOGP portal range set. */
    private static byte[] portalRange(byte[] group, int first, int count) {
        var bytes = group.clone();
        int header = indexOf(bytes, "MOGP") + 8;
        ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(header + 36, (short) first)
                .putShort(header + 38, (short) count);
        return bytes;
    }

    /**
     * Issue #383 step 3a: on a dungeon or raid map a drawn root carries its retained portals and
     * every LOD0 group's header (flags, box, MOPR range), and each mesh names its group; nothing
     * else changes. Open-world maps carry none.
     */
    @Test
    void instanceRootsCarryTheirPortalsAndGroupHeaders() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, INTERIOR},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(1, true)));
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            f.files.sources.put(INTERIOR, portalRange(group(INT), 1, 1));
            try (var set = f.runInstance()) {
                var model = set.models().getFirst();
                var portals = model.portals();
                assertNotNull(portals);
                assertEquals(
                        List.of(
                                new WmoPortalTraversal.Group(EXT, -1, -1, -1, 2, 2, 2, 0, 1),
                                new WmoPortalTraversal.Group(INT, -1, -1, -1, 2, 2, 2, 1, 1)),
                        portals.groups());
                assertEquals(1, portals.portals().size());
                assertArrayEquals(
                        new double[] {1, 0, 0, 1, 1, 0, 1, 1, 1},
                        portals.portals().getFirst().vertices());
                assertEquals(
                        List.of(
                                new WmoPortalTraversal.Ref(0, 1, -1),
                                new WmoPortalTraversal.Ref(0, 0, 1)),
                        portals.refs());
                assertEquals(
                        List.of(0, 1),
                        model.meshes().stream()
                                .map(WorldExteriorObjects.Mesh::groupOrdinal)
                                .toList());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
            }
            try (var set = f.run()) {
                assertNull(set.models().getFirst().portals());
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
            }
        }
    }

    /**
     * Issue #383: a MOGI-interior group read in the interior pass still gives the root's portal set
     * its header, so the set is carried whole; when the interiors do not fit the tile's reads the
     * root draws its exterior alone, without a set, as without interiors.
     */
    @Test
    void deferredInteriorsStillCarryTheRootPortalSet() {
        var mogiPortals =
                root(
                        2,
                        new long[] {GROUP, INTERIOR},
                        List.of(new Material(0, IMAGE, 0)),
                        mogi(EXT, INT),
                        portalChunks(1, true));
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(ROOT, mogiPortals);
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            f.files.sources.put(INTERIOR, portalRange(group(INT), 1, 1));
            try (var set = f.runOpenWorld()) {
                var model = set.models().getFirst();
                assertNotNull(model.portals());
                assertEquals(
                        List.of(
                                new WmoPortalTraversal.Group(EXT, -1, -1, -1, 2, 2, 2, 0, 1),
                                new WmoPortalTraversal.Group(INT, -1, -1, -1, 2, 2, 2, 1, 1)),
                        model.portals().groups());
                assertEquals(2, model.meshes().size());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
            }
        }
        long big = 840;
        try (var f = new Fixture(List.of(Modf.of(big, 1, 0x8).sized(90), Modf.of(ROOT, 2, 0x8)))) {
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(big, mogiRoot(repeat(GROUP + 50, 255), repeatFlags(EXT, 255)));
            f.files.sources.put(ROOT, mogiPortals);
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            f.files.sources.put(INTERIOR, portalRange(group(INT), 1, 1));
            try (var set = f.runOpenWorld()) {
                var model = set.models().stream().filter(m -> m.rootId() == ROOT).findFirst().get();
                assertNull(model.portals());
                assertEquals(1, model.meshes().size());
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
            }
        }
    }

    /**
     * A set that cannot be carried whole is dropped and counted, and its root still draws: here a
     * group whose file is missing (no header), and portals the root did not retain (a reference to
     * a group outside MOHD).
     */
    @Test
    void portalsWithoutEveryGroupHeaderOrNotRetainedAreDropped() {
        long missing = 260;
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            assertNull(f.files.sources.get(missing));
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, missing},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(1, true)));
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            try (var set = f.runInstance()) {
                assertNull(set.models().getFirst().portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            // A single-group root whose door names group 1.
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(1, true)));
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            try (var set = f.runInstance()) {
                assertNull(set.models().getFirst().portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
            }
        }
    }

    /**
     * A portal vertex past the objects file's value bound (WmoPortalSet.MAX_VALUE) drops only the
     * root's set: the root and the tile's other objects still draw.
     */
    @Test
    void aPortalValuePastTheFileBoundDropsOnlyTheSet() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            var chunks = portalChunks(1, true);
            int mopv = indexOf(chunks, "MOPV") + 8;
            ByteBuffer.wrap(chunks).order(ByteOrder.LITTLE_ENDIAN).putFloat(mopv + 8 * 4, 2e6f);
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, INTERIOR},
                            List.of(new Material(0, IMAGE, 0)),
                            chunks));
            f.files.sources.put(GROUP, portalRange(group(EXT), 0, 1));
            f.files.sources.put(INTERIOR, portalRange(group(INT), 1, 1));
            try (var set = f.runInstance()) {
                assertNull(set.models().getFirst().portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
            }
        }
    }

    /**
     * The carried sets share the tile's portal tables (WmoPortalSet.MAX_TILE_PORTALS): two roots of
     * 1,100 portals each, the second no longer fits and is dropped while it still draws.
     */
    @Test
    void portalSetsPastTheTileBoundsAreDropped() {
        long secondRoot = 150, secondGroup = 250;
        assertTrue(2 * 1100 > WmoPortalSet.MAX_TILE_PORTALS);
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8), Modf.of(secondRoot, 2, 0x8)))) {
            for (long[] ids : new long[][] {{ROOT, GROUP}, {secondRoot, secondGroup}}) {
                f.files.sources.put(
                        ids[0],
                        root(
                                1,
                                new long[] {ids[1]},
                                List.of(new Material(0, IMAGE, 0)),
                                portalChunks(1100, false)));
                f.files.sources.put(ids[1], group(EXT));
            }
            try (var set = f.runInstance()) {
                assertEquals(2, set.models().size());
                assertNotNull(set.models().get(0).portals());
                assertEquals(1100, set.models().get(0).portals().portals().size());
                assertNull(set.models().get(1).portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
            }
        }
    }

    /**
     * Issue #375: each copied triangle's winding is compared with its vertex normals. Vertex 2 is
     * moved off the line so both triangles have area; with normals along +z, 0-1-2 winds
     * counter-clockwise (agrees) and 0-2-1 clockwise (opposes).
     */
    @Test
    void triangleWindingIsCountedAgainstVertexNormals() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            var bytes = group(EXT, 3, 2, List.of(new Batch(0, 6, 0, 0)));
            var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int movt = indexOf(bytes, "MOVT") + 8, movi = indexOf(bytes, "MOVI") + 8;
            data.putFloat(movt + 2 * 12, 0).putFloat(movt + 2 * 12 + 4, 1);
            data.putShort(movi + 8, (short) 2).putShort(movi + 10, (short) 1);
            f.files.sources.put(GROUP, bytes);
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_AGREES));
                assertEquals(1, set.count(WorldExteriorObjects.Count.TRIANGLE_WINDING_OPPOSES));
            }
        }
    }

    /**
     * Issue #375: MOMT flags other than F_UNLIT and F_UNCULLED (here F_UNFOGGED 0x2, F_EXTLIGHT
     * 0x8, F_SIDN 0x10, F_WINDOW 0x20 and an undocumented high bit) change nothing and are counted
     * once per admitted batch.
     */
    @Test
    void unappliedMaterialFlagsAreIgnoredAndCounted() {
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            byte[] root =
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(
                                    new Material(0, IMAGE, 0),
                                    new Material(0, IMAGE, 0),
                                    new Material(0, IMAGE, 0),
                                    new Material(0, IMAGE, 0)));
            int momt = indexOf(root, "MOMT") + 8;
            var data = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(momt + 64, 0x4); // F_UNCULLED alone: applied, not counted
            data.putInt(momt + 2 * 64, 0x2 | 0x8 | 0x10 | 0x20);
            data.putInt(momt + 3 * 64, 0x1 | 0x10000);
            f.files.sources.put(ROOT, root);
            var batches = new ArrayList<Batch>();
            for (int material = 0; material < 4; material++) {
                batches.add(new Batch(material * 3, 3, 0, material));
            }
            f.files.sources.put(GROUP, group(EXT, 3, 4, batches));
            try (var set = f.run()) {
                var ranges = set.models().getFirst().meshes().getFirst().ranges();
                assertEquals(
                        List.of(
                                WorldExteriorObjects.OPAQUE,
                                WorldExteriorObjects.OPAQUE | WorldExteriorObjects.TWO_SIDED,
                                WorldExteriorObjects.OPAQUE,
                                WorldExteriorObjects.OPAQUE | WorldExteriorObjects.UNLIT),
                        ranges.stream().map(WorldExteriorObjects.DrawRange::blend).toList());
                assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_FLAGS_IGNORED));
                assertEquals(4, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
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
        // Twenty-nine 16384-vertex groups exceed the 98,304 building vertices and the large
        // allowance it falls back to (458,752 with the interior allowance); the root is omitted
        // whole.
        try (var f = new Fixture(List.of(Modf.of(700, 1, 0x8), Modf.of(ROOT, 2, 0x8)))) {
            f.files.sources.put(700L, root(repeat(701, 29)));
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
    void imageReadCapCountsCappedImagesDeterministically() {
        // WmoGroupSpans admits at most 64 batches per group, so the MAX_IMAGES + 2 materials are
        // spread over groups of 64, one image each.
        int images = WorldExteriorObjects.MAX_IMAGES + 2;
        var materials = new ArrayList<Material>();
        int groups = (images + 63) / 64;
        var batches = new ArrayList<List<Batch>>();
        for (int g = 0; g < groups; g++) {
            batches.add(new ArrayList<>());
        }
        for (int i = 0; i < images; i++) {
            materials.add(new Material(0, 3000 + i, 0));
            batches.get(i / 64).add(new Batch(i % 64 * 3, 3, 0, i));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            long[] gfid = new long[groups];
            for (int g = 0; g < groups; g++) {
                gfid[g] = GROUP + 10 + g;
                f.files.sources.put(gfid[g], group(EXT, 3, batches.get(g).size(), batches.get(g)));
            }
            f.files.sources.put(ROOT, root(groups, gfid, materials));
            for (int i = 0; i < images; i++) {
                f.files.sources.put(3000L + i, blp(4, 4, 1, i));
            }
            try (var set = f.run()) {
                int cap = WorldExteriorObjects.MAX_IMAGES;
                assertEquals(cap, set.images().size());
                assertEquals(cap, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                // Over the image count cap: a budget, reported as such (#280 review).
                assertEquals(2, set.count(WorldExteriorObjects.Count.BATCH_IMAGE_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                assertEquals(2, set.count(WorldExteriorObjects.Count.IMAGE_CAPPED));
                assertEquals(null, f.files.opens.get(3000L + cap));
                assertEquals(3000L + cap - 1, set.images().getLast().id());
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
        // Twenty-nine groups of 16,384 triangles (49,152 indices each) exceed the 294,912
        // building indices and the 1,376,256 of the large allowance with the interior one.
        try (var f = new Fixture(List.of(Modf.of(710, 1, 0x8), Modf.of(ROOT, 2, 0x8)))) {
            f.files.sources.put(710L, root(29, repeat(711, 29), List.of(new Material(0, 712, 0))));
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
        // Twenty-five groups of 64 batches exceed the 1,024 building draw ranges and the 1,536 of
        // the large allowance with the interior one.
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 64; i++) {
            batches.add(new Batch(i * 3, 3, 0, 0));
        }
        try (var f = new Fixture(List.of(Modf.of(720, 1, 0x8)))) {
            f.files.sources.put(720L, root(repeat(721, 25)));
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

    /** Files whose second and later opens of {@code target} read nothing (a refused re-read). */
    private static WorldTerrainTextureSourceTest.Files refusingSecondRead(long target) {
        return refusingReads(target, 2);
    }

    /** Files whose opens of {@code target} from the {@code from}-th on read nothing. */
    private static WorldTerrainTextureSourceTest.Files refusingReads(long target, int from) {
        return new WorldTerrainTextureSourceTest.Files() {
            @Override
            com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                var actual = super.open(id, cancel);
                if (id != target || opens.get(id).get() < from) {
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

    /** A sampling run retaining at most {@code limit} source bytes. */
    private static WorldExteriorObjects.TileObjects sampling(Fixture f, long limit, int formats) {
        return WorldExteriorObjects.prepare(
                f.engine,
                f.scope,
                TILE,
                Cancellation.NONE,
                WorldExteriorObjects.ModelReader.STANDARD,
                WorldExteriorObjects.ImageDetail.STANDARD,
                limit,
                false,
                formats);
    }

    @Test
    void aRefusedSecondReadOfACutoutImageKeepsItsRegisteredLevel() {
        // The cutout image registers at 128 (level 1) and is allotted 256 (level 0); its second
        // read is refused at SOURCE_INPUT. The tile keeps the registered level instead of failing.
        var files = refusingSecondRead(IMAGE2);
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
                    // An opaque BC1 file recording its whole chain is carried as its own blocks
                    // (#374): only the registration and the allotted level are decoded.
                    assertEquals(
                            materialClass == 0 ? 2 : 9, set.levelDecodes, "class " + materialClass);
                    assertEquals(
                            materialClass == 0 ? 1 : 0,
                            set.count(WorldExteriorObjects.Count.IMAGE_BLOCKS));
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
    void imagesReadAgainForEveryUseStayWithinTheReadProfile() {
        // With nothing retained, every later use of an image reads its file again: here the
        // registration decode and the file's blocks for its chain, at the building image cap.
        // Every read stays within the profile (IMAGE_READS_PER_SLOT per slot), none is left
        // unread for the profile, and the tile draws every image.
        int images = WorldExteriorObjects.MAX_IMAGES;
        var materials = new ArrayList<Material>();
        int groups = images / 64;
        long[] gfid = new long[groups];
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            for (int g = 0; g < groups; g++) {
                var batches = new ArrayList<Batch>();
                for (int b = 0; b < 64; b++) {
                    int i = g * 64 + b;
                    materials.add(new Material(0, 5000 + i, 0));
                    batches.add(new Batch(3 * b, 3, 0, i));
                    f.files.sources.put(5000L + i, blp(256, 256, 9, 0x0841 * (i % 30 + 1)));
                }
                gfid[g] = GROUP + 10 + g;
                f.files.sources.put(gfid[g], group(EXT, 3, 64, batches));
            }
            f.files.sources.put(ROOT, root(groups, gfid, materials));
            // A session sampling BC1 blocks also reads each file's blocks for its chain.
            int bc1 = 1 << com.vibeminer.rendering.world.ObjectTextureFormats.BC1;
            try (var set = prepareWith(f, WorldExteriorObjects.Interiors.NONE, 0, bc1)) {
                assertEquals(images, set.images().size());
                assertEquals(0, set.count(WorldExteriorObjects.Count.SOURCE_READ_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
            }
            int reads = 0;
            for (int i = 0; i < images; i++) {
                reads += f.files.opens(5000L + i);
            }
            assertEquals(2 * images, reads, "each image read for each of its two uses");
            assertTrue(
                    reads
                            <= WorldStreamScopes.IMAGE_READS_PER_SLOT
                                    * WorldStreamScopes.WMO_IMAGE_SLOTS);
        }
    }

    @Test
    void aSpentImageReadProfileLeavesOnlyThoseImagesUnread() {
        // Building image reads lowered to two: the first two images are read and drawn, the
        // other two are left unread (SOURCE_READ_CAPPED), counted unavailable and not drawn, and
        // never read again; the building and its other surfaces still draw.
        var materials = new ArrayList<Material>();
        var batches = new ArrayList<Batch>();
        for (int i = 0; i < 4; i++) {
            materials.add(new Material(0, 6000 + i, 0));
            batches.add(new Batch(3 * i, 3, 0, i));
            // A second surface of the same image: a capped source is not read again for it.
            batches.add(new Batch(3 * (4 + i), 3, 0, i));
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            for (int i = 0; i < 4; i++) {
                f.files.sources.put(6000L + i, blp(4, 4, 1, 0x0841 * (i + 1)));
            }
            f.files.sources.put(ROOT, root(1, new long[] {GROUP}, materials));
            f.files.sources.put(GROUP, group(EXT, 3, 8, batches));
            f.engine.lowerDerivedReadsForTest(WorldStreamScopes.DerivedKind.WMO_IMAGE, 2);
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(2, set.images().size());
                assertEquals(2, set.count(WorldExteriorObjects.Count.SOURCE_READ_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.IMAGE_UNAVAILABLE));
                assertEquals(4, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(4, set.count(WorldExteriorObjects.Count.BATCH_MISSING_IMAGE));
                assertEquals(1, set.placements().size());
            }
            assertEquals(1, f.files.opens(6000L));
            assertEquals(1, f.files.opens(6001L));
            assertEquals(0, f.files.opens(6002L), "a capped source is never read");
            assertEquals(0, f.files.opens(6003L));
        }
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
    @org.junit.jupiter.api.Timeout(30)
    void aLargeGroupOfOneUniqueIdIsDecidedOnce() {
        // 16,000 MDDF records of one unique ID, then one other doodad. Identical: the first
        // draws and every other is a shared duplicate. With one record of the group differing,
        // the whole group conflicts and the other doodad still draws. Each group is compared with
        // its first record once, not once per record.
        int size = 16_000;
        var identical = new ArrayList<Mddf>();
        for (int i = 0; i < size; i++) {
            identical.add(Mddf.of(MODEL, 5, 0x40));
        }
        identical.add(Mddf.of(MODEL, 6, 0x40));
        var conflicting = new ArrayList<>(identical);
        conflicting.set(size / 2, new Mddf(MODEL, 5, 0x40, 1, 1, 1, 0, 0, 0, 1024));
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            try (var set = run(f, obj0(List.of(), identical))) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(
                        size - 1, set.count(WorldExteriorObjects.Count.DOODAD_SHARED_DUPLICATE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.DOODAD_CONFLICTING));
                assertEquals(5, set.placements().getFirst().uniqueId());
            }
            try (var set = run(f, obj0(List.of(), conflicting))) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(size, set.count(WorldExteriorObjects.Count.DOODAD_CONFLICTING));
                assertEquals(6, set.placements().getFirst().uniqueId());
            }
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
    void doodadsPastThePlacementCapAreCappedAndTheTileIsStillDrawn() {
        // An obj0 may record more MDDF doodads than a tile draws (Gilneas City's 34,29 records
        // 4,165 on build 70862): the excess is capped in source order and the building and the
        // doodads before the cap are still drawn.
        int over = 69;
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_DOODADS + over; i++) {
            doodads.add(Mddf.of(MODEL, i + 1, 0x40));
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT, root(1, new long[] {GROUP}, List.of(new Material(0, IMAGE, 0))));
            try (var set = run(f, obj0(List.of(Modf.of(ROOT, 1, 0x8)), doodads, 0))) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(
                        WorldExteriorObjects.MAX_DOODADS,
                        set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(over, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertEquals(over, set.count(WorldExteriorObjects.Count.DOODAD_CAP_PLACEMENTS));
                var last = set.placements().getLast();
                assertEquals(WorldExteriorObjects.Kind.DOODAD, last.kind());
                assertEquals(WorldExteriorObjects.MAX_DOODADS - 1, last.ordinal());
            }
        }
    }

    @Test
    void doodadPlacementAndModelCapsKeepSourceOrder() {
        // MDDF doodads fill the placement cap first; the drawn building's three set doodads come
        // after and are capped.
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_DOODADS; i++) {
            doodads.add(Mddf.of(MODEL, i + 1, 0x40));
        }
        var three = new ArrayList<Modd>();
        for (int i = 0; i < 3; i++) {
            three.add(new Modd(0, i, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            doodadChunks(new int[][] {{0, 3}}, new long[] {MODEL}, three)));
            try (var set = run(f, obj0(List.of(Modf.of(ROOT, 1, 0x8)), doodads, 0))) {
                assertEquals(
                        WorldExteriorObjects.MAX_DOODADS,
                        set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(3, set.count(WorldExteriorObjects.Count.SET_DOODAD_CAPPED));
                assertEquals(3, set.count(WorldExteriorObjects.Count.DOODAD_CAP_PLACEMENTS));
                var last = set.placements().getLast();
                assertEquals(WorldExteriorObjects.Kind.DOODAD, last.kind());
                assertEquals(WorldExteriorObjects.MAX_DOODADS - 1, last.ordinal());
            }
        }
        // Model IDs clear of every other fixture ID (MODEL_IMAGE, SKIN), whatever the cap.
        long first = 10_000;
        var distinct = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_MODELS + 2; i++) {
            distinct.add(Mddf.of(first + i, i + 1, 0x40));
        }
        try (var f = new Fixture(List.of())) {
            for (int i = 0; i < WorldExteriorObjects.MAX_MODELS + 2; i++) {
                putModel(f, first + i, model());
            }
            try (var set = run(f, obj0(List.of(), distinct))) {
                assertEquals(
                        WorldExteriorObjects.MAX_MODELS,
                        set.count(WorldExteriorObjects.Count.MODEL_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertEquals(WorldExteriorObjects.MAX_MODELS, set.models().size());
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_CAP_MODELS));
            }
            // Nor does the read-ahead ever fetch a model past the cap.
            assertEquals(0, f.files.opens(first + WorldExteriorObjects.MAX_MODELS));
            assertEquals(0, f.files.opens(first + WorldExteriorObjects.MAX_MODELS + 1));
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
        // Hostile input: eight groups each with one opaque triangle and 65,535 cutout triangles
        // hold more candidate indices than the large allowance (with the interior one) and the
        // cutout budget together; the root is capped whole instead of keeping every copy.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            8,
                            new long[] {
                                GROUP, GROUP + 1, GROUP + 2, GROUP + 3, GROUP + 4, GROUP + 5,
                                GROUP + 6, GROUP + 7
                            },
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            for (int g = 0; g < 8; g++) {
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
        // Two groups of 64 opaque batches and a group with one cutout batch: 64 placements cost
        // exactly the 8,192-draw budget under the opaque-only profile, 64 more with the cutout.
        int per = WorldExteriorObjects.MAX_DRAWS / 64;
        assertEquals(128, per);
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
                            3,
                            new long[] {GROUP, GROUP + 2, GROUP + 1},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 64, batches));
            f.files.sources.put(GROUP + 2, group(EXT, 3, 64, batches));
            f.files.sources.put(GROUP + 1, cutout);
            putModel(f, MODEL, model());
            f.files.sources.put(4L, obj0(records, List.of(Mddf.of(MODEL, 900, 0x40))));
            try (var set = f.run()) {
                assertEquals(64, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(
                        1,
                        set.count(WorldExteriorObjects.Count.BATCH_CAPPED),
                        counts(set).toString());
                assertEquals(per, set.count(WorldExteriorObjects.Count.BATCH_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                // The cutout-only group is dropped whole; the opaque groups are untouched.
                assertEquals(2, set.models().getFirst().meshes().size());
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                for (var mesh : set.models().getFirst().meshes()) {
                    assertEquals(64, mesh.ranges().size());
                    assertEquals(64 * 3, mesh.indices().remaining());
                    assertTrue(
                            mesh.ranges().stream()
                                    .allMatch(r -> r.blend() == WorldExteriorObjects.OPAQUE));
                }
                // The budget is full, so the doodad is capped, never a building.
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_CAP_DRAWS));
                // The dropped cutout range's image is not uploaded.
                assertTrue(set.images().stream().noneMatch(image -> image.id() == IMAGE2));
                assertImageSlotsValid(set);
            }
        }
        // One placement fewer: the cutout range fits (63 * (per + 1) draws) and is kept.
        try (var f = new Fixture(records.subList(0, 63))) {
            f.files.sources.put(
                    ROOT,
                    root(
                            3,
                            new long[] {GROUP, GROUP + 2, GROUP + 1},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(GROUP, group(EXT, 3, 64, batches));
            f.files.sources.put(GROUP + 2, group(EXT, 3, 64, batches));
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
        // A building with only three cutout batches, placed first, then a building with 130
        // opaque batches in two groups. The opaque-only profile left the first empty, so it
        // costs nothing at admission: 63 opaque placements (8,190 of the 8,192 draws) are all
        // drawn, and the cutout building, which no longer fits, returns to its opaque-only
        // outcome.
        assertEquals(8192, WorldExteriorObjects.MAX_DRAWS);
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
                // 64 + 64 opaque batches in two groups and two in a third: 130 opaque ranges.
                f.files.sources.put(
                        ROOT,
                        root(
                                3,
                                new long[] {GROUP, GROUP + 2, GROUP + 1},
                                List.of(new Material(0, IMAGE, 0))));
                f.files.sources.put(GROUP, group(EXT, 3, 64, opaque));
                f.files.sources.put(GROUP + 2, group(EXT, 3, 64, opaque));
                f.files.sources.put(
                        GROUP + 1,
                        group(EXT, 3, 2, List.of(new Batch(0, 3, 0, 0), new Batch(3, 3, 0, 0))));
                f.files.sources.put(
                        cutoutRoot,
                        root(1, new long[] {cutoutGroup}, List.of(new Material(1, IMAGE2, 0))));
                f.files.sources.put(
                        cutoutGroup,
                        group(
                                EXT,
                                3,
                                3,
                                List.of(
                                        new Batch(0, 3, 0, 0),
                                        new Batch(3, 3, 0, 0),
                                        new Batch(6, 3, 0, 0))));
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
                        assertEquals(3, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
                        assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
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
                        assertEquals(3, set.count(WorldExteriorObjects.Count.BATCH_CUTOUT));
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
    private static class SlowGroups extends WorldTerrainTextureSourceTest.Files {
        final AtomicInteger open = new AtomicInteger(), peak = new AtomicInteger();
        final AtomicInteger groupOpens = new AtomicInteger();
        volatile java.util.function.IntConsumer onGroupOpen = ignored -> {};
        // Sees each group open with the cancellation its read was given.
        volatile java.util.function.BiConsumer<Integer, Cancellation> onGroupOpenWith =
                (opened, cancel) -> {};
        volatile long delay = 20;
        // Which files are slow and counted; the group files unless a test says otherwise.
        volatile java.util.function.LongPredicate slow = id -> id >= GROUP + 10 && id < GROUP + 100;

        @Override
        com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
            var actual = super.open(id, cancel);
            if (!slow.test(id)) {
                return actual;
            }
            int opened = groupOpens.incrementAndGet();
            onGroupOpen.accept(opened);
            onGroupOpenWith.accept(opened, cancel);
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
            try {
                Thread.sleep(delay);
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
    void aRootsImagesAreReadAheadOnceEachAfterItsGroupsAndDrawTheSame() {
        int count = 12;
        long images = 50_000;
        List<Object> serial = null;
        long serialReads = -1;
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            files.slow =
                    id ->
                            id >= GROUP + 10 && id < GROUP + 100
                                    || id >= images && id < images + count;
            WorldStageTimings.reset();
            WorldStageTimings.enable(true);
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
                // Group i draws material i, whose image is its own; the last two groups repeat
                // the first image, which is read once.
                var gfid = new long[count];
                var materials = new ArrayList<Material>();
                for (int i = 0; i < count; i++) {
                    gfid[i] = GROUP + 10 + i;
                    int material = i < count - 2 ? i : 0;
                    files.sources.put(
                            gfid[i], group(EXT, 3, 1, List.of(new Batch(0, 3, 0, material))));
                    materials.add(new Material(0, images + i, 0));
                    files.sources.put(images + i, blp(4, 4, 1, 0x07e0 + i));
                }
                f.files.sources.put(ROOT, root(count, gfid, materials));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                var summary = new ArrayList<Object>();
                try (var set = f.run()) {
                    assertEquals(count - 2, set.count(WorldExteriorObjects.Count.IMAGE_DECODED));
                    summary.add(counts(set));
                    summary.add(set.vertices());
                    summary.add(set.placements());
                    summary.add(set.models().getFirst().meshes().size());
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                }
                for (int i = 0; i < count - 2; i++) {
                    assertEquals(1, f.files.opens(images + i), "each image is read once");
                }
                assertEquals(0, f.files.opens(images + count - 1), "an unused image is not read");
                assertEquals(0, files.open.get(), "every read is closed");
                var snapshot = WorldStageTimings.snapshot();
                var reads = snapshot.get("source.read.BASIC_EXTERIOR");
                var waits = snapshot.get("source.prefetchWait.BASIC_EXTERIOR");
                if (!ahead) {
                    assertEquals(1, files.peak.get(), "serial reads never overlap");
                    serial = summary;
                    serialReads = reads.count();
                } else {
                    assertTrue(
                            files.peak.get() <= WorldStreamScopes.Prefetcher.READS + 1,
                            "peak " + files.peak.get());
                    assertEquals(serial, summary, "read-ahead changes no output");
                    long hits = waits == null ? 0 : waits.count();
                    long misses = reads == null ? 0 : reads.count();
                    assertEquals(serialReads, misses + hits, "the same inputs are consumed");
                    // Groups and images: the images follow the groups that name them.
                    assertTrue(hits > count, "read-ahead hits " + hits);
                }
            } finally {
                WorldStageTimings.enable(false);
                WorldStageTimings.reset();
            }
        }
    }

    @Test
    void aPrefetchedReadIsTimedAsAWaitAndNotAsAFullSourceRead() {
        long serialReads = -1;
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            WorldStageTimings.reset();
            WorldStageTimings.enable(true);
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
                f.files.sources.put(ROOT, root(manyGroups(files, 12)));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                try (var set = f.run()) {
                    assertEquals(1, set.models().size());
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                }
                var snapshot = WorldStageTimings.snapshot();
                var reads = snapshot.get("source.read.BASIC_EXTERIOR");
                var waits = snapshot.get("source.prefetchWait.BASIC_EXTERIOR");
                var bytes = snapshot.get("source.readBytes.BASIC_EXTERIOR");
                assertEquals(reads.count(), bytes.count(), "bytes counted per full read only");
                if (!ahead) {
                    assertNull(waits, "no read-ahead, no wait series");
                    serialReads = reads.count();
                } else {
                    assertTrue(waits != null && waits.count() > 0, "read-ahead hits are timed");
                    assertEquals(
                            serialReads,
                            reads.count() + waits.count(),
                            "every consumed input is either a full read or a read-ahead hit");
                    assertTrue(reads.count() < serialReads, "hits leave source.read.*");
                }
            } finally {
                WorldStageTimings.enable(false);
                WorldStageTimings.reset();
            }
        }
    }

    @Test
    void cancelingTheOperationInterruptsAReadAheadItIsWaitingOnAsFastAsASerialRead() {
        long[] elapsed = new long[2];
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            var token = new Cancellation();
            var canceler = new java.util.concurrent.atomic.AtomicReference<Thread>();
            // The first group's open stalls until its own read is canceled (3 s safety cap); the
            // operation is canceled 200 ms after that open starts.
            files.onGroupOpenWith =
                    (opened, cancel) -> {
                        if (opened != 1) {
                            return;
                        }
                        canceler.set(
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        Thread.sleep(200);
                                                    } catch (InterruptedException interrupted) {
                                                        return;
                                                    }
                                                    token.cancel();
                                                }));
                        long cap = System.nanoTime() + 3_000_000_000L;
                        while (!cancel.isCanceled() && System.nanoTime() < cap) {
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    };
            try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
                f.files.sources.put(ROOT, root(manyGroups(files, 12)));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                long started = System.nanoTime();
                try {
                    assertThrows(
                            CancellationException.class,
                            () -> WorldExteriorObjects.prepare(f.engine, f.scope, TILE, token));
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                }
                elapsed[ahead ? 1 : 0] = (System.nanoTime() - started) / 1_000_000;
                assertEquals(0, files.open.get(), "no read is left open");
            } finally {
                var thread = canceler.get();
                if (thread != null) {
                    thread.interrupt();
                }
            }
        }
        // Without the link a taken read-ahead waits out its stalled open (the 3 s cap).
        assertTrue(elapsed[0] < 1500, "serial cancel took " + elapsed[0] + " ms");
        assertTrue(elapsed[1] < 1500, "read-ahead cancel took " + elapsed[1] + " ms");
    }

    @Test
    void cancellationDrainsAndClosesEveryReadAheadBeforeThePreparationReturns() {
        var files = new SlowGroups();
        var token = new Cancellation();
        files.onGroupOpen =
                opened -> {
                    if (opened == 2) { // this open's own count, not a racy re-read
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

    @Test
    void readAheadFasterThanParsingStillReadsEveryGroupOnce() {
        // No delay: reads finish long before parsing reaches them, more groups than the window.
        var files = new SlowGroups();
        files.delay = 0;
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            f.files.sources.put(
                    ROOT, root(manyGroups(files, 3 * WorldStreamScopes.Prefetcher.WINDOW)));
            try (var set = f.run()) {
                assertEquals(
                        3 * WorldStreamScopes.Prefetcher.WINDOW,
                        set.models().getFirst().meshes().size());
            }
            assertEquals(
                    3 * WorldStreamScopes.Prefetcher.WINDOW,
                    files.groupOpens.get(),
                    "the read the consumer needs next is never dropped and read again");
            assertEquals(0, files.open.get());
            assertTrue(f.engine.prefetchPeakHeldBytes.get() > 0, "read-ahead held bytes");
            assertTrue(
                    f.engine.prefetchPeakHeldBytes.get()
                            <= WorldStreamScopes.Prefetcher.HELD_BYTES
                                    + (long) WorldStreamScopes.Prefetcher.READS
                                            * f.files.sources.get(GROUP + 10).length,
                    "held bytes stay within the budget plus the reads in flight");
        }
    }

    @Test
    void aRootCappedMidwayWithdrawsItsReadAheadAndFetchesNoFurtherGroup() {
        // Sixty 16384-vertex groups exceed the building pool at the seventh group and the
        // large allowance it falls back to (458,752 vertices with the interior one) at the
        // twenty-ninth: the first root caps there. A second root then keeps the operation
        // reading, so capped hints left queued would still be fetched ahead of its groups.
        var files = new SlowGroups();
        files.delay = 30;
        long second = 800;
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8), Modf.of(second, 2, 0x8)), files)) {
            var capped = manyGroups(files, 60);
            for (long id : capped) {
                files.sources.put(id, group(EXT, 16384, 1, List.of(new Batch(0, 3, 0, 0))));
            }
            f.files.sources.put(ROOT, root(capped));
            var kept = new long[] {GROUP + 90, GROUP + 91, GROUP + 92, GROUP + 93};
            for (long id : kept) {
                files.sources.put(id, group(EXT));
            }
            f.files.sources.put(second, root(kept));
            try (var set = f.run()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.models().size());
                assertEquals(second, set.models().getFirst().rootId());
            }
            int cappedOpens = 0;
            for (long id : capped) {
                cappedOpens += files.opens(id);
            }
            // Twenty-nine consumed (seven while sizing it for the building pool, twenty-two more
            // from the large allowance), plus two read-ahead windows. When the root leaves the
            // building pool at its seventh group, every read started and not yet consumed is
            // withdrawn: at most WINDOW of groups 8 to 15, each opened again when the large
            // allowance reads it. When it caps at its twenty-ninth, at most WINDOW reads past it
            // have started. Each group is otherwise opened once.
            assertTrue(
                    cappedOpens <= 29 + 2 * WorldStreamScopes.Prefetcher.WINDOW,
                    "capped root groups opened: " + cappedOpens);
            for (long id : kept) {
                assertEquals(1, files.opens(id), "kept group " + id);
            }
            assertEquals(0, files.open.get(), "withdrawn reads are closed");
        }
    }

    @Test
    @SuppressWarnings("try")
    void withdrawnReadAheadStartsNoFurtherReadAndClosesWhatRan() throws Exception {
        // Directly on one operation: hint twelve groups, withdraw once four reads are running.
        // Each read stays in its open until its own read is canceled, so none finishes and frees
        // a slot before the withdrawal however the threads are scheduled: exactly READS have
        // started when it comes, and the withdrawal is what releases them.
        var files = new SlowGroups();
        files.delay = 0;
        var running = new java.util.concurrent.CountDownLatch(WorldStreamScopes.Prefetcher.READS);
        files.onGroupOpenWith =
                (opened, cancel) -> {
                    var withdrawn = new java.util.concurrent.CountDownLatch(1);
                    try (var ignored = cancel.onCancel(withdrawn::countDown)) {
                        running.countDown();
                        await(withdrawn);
                    }
                };
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            var ids = manyGroups(files, 12);
            var observed = new int[2];
            try (var ignored =
                    f.engine.withRecordedTile(
                            f.scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                            Cancellation.NONE,
                            admission -> {
                                for (long id : ids) {
                                    admission.prefetch(id, WorldStreamScopes.DerivedKind.WMO_GROUP);
                                }
                                await(running);
                                admission.cancelPrefetch();
                                observed[0] = files.groupOpens.get();
                                observed[1] = files.open.get();
                                // A withdrawn hint has nothing left to start: the queue was
                                // emptied under the read-ahead's lock before the reads were
                                // released. This pause can only miss a late read, never invent
                                // one.
                                try {
                                    Thread.sleep(200);
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                                return () -> {};
                            })) {
                assertEquals(WorldStreamScopes.Prefetcher.READS, observed[0]);
                assertEquals(0, observed[1], "withdrawal waits for and closes the running reads");
                assertEquals(
                        observed[0],
                        files.groupOpens.get(),
                        "no withdrawn hint starts later, even while the operation goes on");
            }
        }
    }

    @Test
    void aReadAheadThatFailsIsOnlyAMissAndTheConsumersOwnReadDecides() {
        // The first open of one group fails (store queue pressure, say); that open is the
        // read-ahead's, so the consumer reads it again and the whole set still draws.
        long flaky = GROUP + 10;
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
        var files =
                new SlowGroups() {
                    @Override
                    com.vibeminer.files.FileReadStream open(long id, Cancellation cancel) {
                        if (id == flaky && failedOnce.compareAndSet(false, true)) {
                            throw new IllegalStateException("authored transient store failure");
                        }
                        return super.open(id, cancel);
                    }
                };
        files.delay = 0;
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)), files)) {
            f.files.sources.put(ROOT, root(manyGroups(files, 6)));
            try (var set = f.run()) {
                assertEquals(6, set.models().getFirst().meshes().size());
            }
            assertTrue(failedOnce.get(), "the read-ahead met the failure");
            assertEquals(1, files.opens(flaky), "one successful open: the consumer's own");
        }
    }

    // ---- very large placements drawn by group -----------------------------------------------

    private static final long BIG = 950, NORMAL = 960;

    /**
     * A placement across the border of the authored tile (7,19) and its neighbour (8,19): raw
     * position (10400, 0, 4200) is native (12866.67, 6666.67) in tile 7,19, and the recorded bounds
     * reach native x 12566.67, in tile 8. Heading 0: native x = 12866.67 - local x, native y =
     * 6666.67 - local y, so a group whose MOGI box centre has local x 200 belongs to 8,19.
     */
    private static Modf largePlacement() {
        return new Modf(BIG, 77, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                .bounded(10300, -10, 4100, 10500, 10, 4500);
    }

    /** MOGI with flags and a 2-unit box around each local (x, 0, 0) centre. */
    private static byte[] mogiBoxes(int[] flags, float[] centres) {
        var b = buffer(32 * flags.length);
        for (int i = 0; i < flags.length; i++) {
            b.putInt(i * 32, flags[i]);
            b.putFloat(i * 32 + 4, centres[i] - 1).putFloat(i * 32 + 8, -1);
            b.putFloat(i * 32 + 12, -1).putFloat(i * 32 + 16, centres[i] + 1);
            b.putFloat(i * 32 + 20, 1).putFloat(i * 32 + 24, 1);
        }
        return chunk("MOGI", b.array());
    }

    /** One exterior group of {@code vertices} vertices drawing one triangle. */
    private static byte[] bigGroup(int flags, int vertices) {
        return group(flags, vertices, 1, List.of(new Batch(0, 3, 0, 0)));
    }

    /**
     * Groups {@code BIG + 1 + g} with the given flags, vertex counts and MOGI centres, under root
     * {@code BIG}, with {@code extras} chunks.
     */
    private static void putBig(
            Fixture f, int[] flags, int[] vertices, float[] centres, byte[]... extras) {
        var gfid = new long[flags.length];
        for (int g = 0; g < flags.length; g++) {
            gfid[g] = BIG + 1 + g;
            f.files.sources.put(gfid[g], bigGroup(flags[g], vertices[g]));
        }
        var chunks = new ArrayList<byte[]>();
        chunks.add(mogiBoxes(flags, centres));
        chunks.addAll(List.of(extras));
        f.files.sources.put(
                BIG,
                root(
                        flags.length,
                        gfid,
                        List.of(new Material(0, IMAGE, 0)),
                        chunks.toArray(byte[][]::new)));
    }

    /** Files whose WDT also records tile ({@code x}, {@code y}) with the same obj0. */
    private static WorldFormats.Tile alsoRecord(
            WorldTerrainTextureSourceTest.Files files, int x, int y) {
        var wdt = ByteBuffer.wrap(files.sources.get(1L)).order(ByteOrder.LITTLE_ENDIAN);
        int ordinal = x * 64 + y;
        wdt.putInt(12 + 40 + 8 + ordinal * 8, 1);
        int at = 12 + 40 + 8 + 32768 + 8 + ordinal * 32;
        wdt.putInt(at, 2).putInt(at + 4, 4).putInt(at + 12, 3);
        return new WorldFormats.Tile(x, y, 2, 0, 0, 4, 0, 3, 0, 0);
    }

    private static WorldExteriorObjects.TileObjects openWorldOn(Fixture f, WorldFormats.Tile tile) {
        return WorldExteriorObjects.prepare(
                f.engine,
                f.scope,
                tile,
                Cancellation.NONE,
                WorldExteriorObjects.ModelReader.STANDARD,
                WorldExteriorObjects.ImageDetail.STANDARD,
                WorldExteriorObjects.RETAINED_SOURCE_BYTES,
                WorldExteriorObjects.Interiors.OPEN_WORLD,
                0);
    }

    /** The sorted group ordinals the set draws of {@code root}; empty when not drawn. */
    private static List<Integer> groupsOf(WorldExteriorObjects.TileObjects set, long root) {
        return set.models().stream()
                .filter(m -> !m.doodad() && m.rootId() == root)
                .flatMap(m -> m.meshes().stream())
                .map(WorldExteriorObjects.Mesh::groupOrdinal)
                .sorted()
                .toList();
    }

    @Test
    void ownersFollowTheGroupBoxCentresWithinTheRecordedBounds() {
        var record = declared(largePlacement());
        var heading = WorldExteriorObjects.Rotation.heading(1, 0);
        double x = 51200.0 / 3 - 4200, y = 51200.0 / 3 - 10400;
        var here = WorldExteriorObjects.Owner.of(record, x, y, 1, heading, 7, 19);
        var there = WorldExteriorObjects.Owner.of(record, x, y, 1, heading, 8, 19);
        assertTrue(here.owns(x, y));
        assertFalse(there.owns(x, y));
        assertFalse(here.owns(x - 200, y));
        assertTrue(there.owns(x - 200, y));
        // A point past the recorded bounds belongs to the nearest tile they cover, so a group
        // never belongs to a tile that does not list the placement.
        assertTrue(there.owns(x - 5000, y));
        assertTrue(here.owns(x + 5000, y + 5000));
        // Bounds within one tile: never drawn by group.
        var small =
                declared(
                        new Modf(BIG, 77, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                                .bounded(10300, -10, 4150, 10500, 10, 4250));
        assertEquals(null, WorldExteriorObjects.Owner.of(small, x, y, 1, heading, 7, 19));
        assertEquals(7, WorldExteriorObjects.Owner.tile(12_866.67));
        assertEquals(8, WorldExteriorObjects.Owner.tile(12_666.67));
        assertEquals(0, WorldExteriorObjects.Owner.tile(1e9));
        assertEquals(63, WorldExteriorObjects.Owner.tile(-1e9));
    }

    @Test
    void aVeryLargePlacementIsDrawnByGroupEachGroupByOneTile() {
        // Two 60,000-vertex exterior groups are over an empty tile's 98,304 building vertices, so
        // no tile could draw the root whole. Each tile that lists it draws the group whose MOGI
        // box centre it holds, from the large allowance: together exactly every group, once.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 200});
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0), groupsOf(here, BIG));
                assertEquals(List.of(1), groupsOf(there, BIG));
                for (var set : List.of(here, there)) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                    assertPlacementsAccounted(set, 1);
                    assertEquals(60_000, set.vertices());
                }
                // Repeating a tile gives the same set: the rule reads only the placement and root.
                try (var again = f.runOpenWorld()) {
                    assertEquals(counts(here), counts(again));
                    assertEquals(groupsOf(here, BIG), groupsOf(again, BIG));
                }
            }
        }
    }

    @Test
    void aPlacementInOneTileOrOneThatFitsATileIsNeverDrawnByGroup() {
        // The same oversized root with recorded bounds inside the one tile is never split: over
        // an empty tile's 98,304 building vertices, it is drawn whole from the large allowance,
        // every group by this tile.
        var inside =
                new Modf(BIG, 77, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4150, 10500, 10, 4250);
        try (var f = new Fixture(List.of(inside))) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 200});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertPlacementsAccounted(set, 1);
                assertEquals(120_000, set.vertices());
            }
        }
        // A second placement of that root in the tile draws the same model.
        var again =
                new Modf(BIG, 78, 0x8, 10420, 0, 4220, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4150, 10500, 10, 4250);
        try (var f = new Fixture(List.of(inside, again))) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 200});
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.models().size());
                assertPlacementsAccounted(set, 2);
                assertEquals(120_000, set.vertices());
            }
        }
        // A placement across the border whose root fits an empty tile is drawn whole by every
        // tile that lists it, exactly as before: only a root no tile could draw is split.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putBig(f, new int[] {EXT, EXT}, new int[] {3, 3}, new float[] {0, 200});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
            }
        }
    }

    @Test
    void aMultiTileRootThatFitsAnEmptyTileButNotWhatIsLeftIsDrawnWholeFromTheLargeAllowance() {
        // Sized alone, the 60,000-vertex border root fits an empty tile, so it is never split;
        // a larger single-tile building admitted first leaves too little for it, so it is drawn
        // whole from the large allowance. The neighbour that also lists it, with nothing else,
        // draws it whole from its building pool: both tiles decide whole from the root alone,
        // neither draws it by group, and both draw the same groups and set doodads (the copies
        // coincide).
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        var normal = Modf.of(NORMAL, 2, 0x8).sized(5000);
        try (var f = new Fixture(List.of(largePlacement(), normal), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {30_000, 30_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            f.files.sources.put(NORMAL + 1, bigGroup(EXT, 50_000));
            f.files.sources.put(NORMAL, root(NORMAL + 1));
            try (var set = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0), groupsOf(set, NORMAL));
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertPlacementsAccounted(set, 2);
                assertEquals(List.of(0, 1), groupsOf(there, BIG));
                assertEquals(0, there.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(0, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, there.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                var bigHere = new java.util.TreeSet<String>();
                for (var key : drawnGroupKeys(set)) {
                    if (key.startsWith("77:")) {
                        bigHere.add(key);
                    }
                }
                assertEquals(drawnGroupKeys(there), bigHere);
                assertEquals(2, setDoodadKeys(there).size());
                assertEquals(setDoodadKeys(there), setDoodadKeys(set));
            }
        }
    }

    @Test
    void theLargeAllowanceNeverCrowdsOutTheTilesOwnBuildings() {
        // A 90,000-vertex building fills most of the tile's 98,304 building vertices; the large
        // placement's 60,000-vertex share is drawn beside it from the large allowance.
        var normal = Modf.of(NORMAL, 2, 0x8);
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 200});
            f.files.sources.put(NORMAL + 1, bigGroup(EXT, 45_000));
            f.files.sources.put(NORMAL, root(NORMAL + 1, NORMAL + 1));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1), groupsOf(set, NORMAL));
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(150_000, set.vertices());
            }
        }
        // A share over the large allowance with the interior one (eight owned groups, 480,000
        // vertices of 458,752) caps only the large placement on this tile; the tile's building
        // is drawn as before.
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putBig(
                    f,
                    new int[] {EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                    new int[] {
                        60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000
                    },
                    new float[] {0, 200, 10, 20, 30, 40, 50, 60, 65});
            f.files.sources.put(NORMAL + 1, bigGroup(EXT, 45_000));
            f.files.sources.put(NORMAL, root(NORMAL + 1, NORMAL + 1));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1), groupsOf(set, NORMAL));
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertPlacementsAccounted(set, 2);
            }
        }
    }

    @Test
    void aSplitShareOverTheLargeAllowanceAloneDrawsFromThePooledAllowance() {
        // The share here (exteriors 0, 2 and 3, 150,000 vertices) is over the 131,072 large
        // vertices alone, but within the large and interior allowances together (458,752): it is
        // drawn, its exteriors admitted against both.
        assertTrue(150_000 > WorldExteriorObjects.MAX_LARGE_VERTICES);
        try (var f = new Fixture(List.of(largePlacement()))) {
            putBig(
                    f,
                    new int[] {EXT, EXT, EXT, EXT},
                    new int[] {50_000, 60_000, 50_000, 50_000},
                    new float[] {0, 200, 10, 20});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 2, 3), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertEquals(150_000, set.vertices());
            }
        }
    }

    @Test
    void aRootDrawnWholeFromTheLargeAllowanceOnlyTakesWhatSplitExteriorsLeft() {
        // A single-tile root of two 60,000-vertex groups is over an empty tile's 98,304 building
        // vertices, so it falls back to the large allowance. It is admitted first on the tile
        // (larger recorded bounds), yet the split root's exteriors here come first from the
        // allowance: with six of them (360,000 vertices) the fallback no longer fits (480,000 of
        // 458,752) and is capped whole; with five (300,000) both draw.
        var tall =
                new Modf(NORMAL, 78, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4150, 10500, 600, 4250);
        for (int here : new int[] {6, 5}) {
            try (var f = new Fixture(List.of(largePlacement(), tall))) {
                int[] flags = new int[here + 1];
                int[] sizes = new int[here + 1];
                float[] centres = new float[here + 1];
                for (int g = 0; g <= here; g++) {
                    flags[g] = EXT;
                    sizes[g] = 60_000;
                    centres[g] = g < here ? g : 200;
                }
                putBig(f, flags, sizes, centres);
                putRoot(
                        f,
                        NORMAL,
                        new int[] {EXT, EXT},
                        new int[] {60_000, 60_000},
                        new float[] {0, 1});
                try (var set = f.runOpenWorld()) {
                    var owned = new ArrayList<Integer>();
                    for (int g = 0; g < here; g++) {
                        owned.add(g);
                    }
                    assertEquals(owned, groupsOf(set, BIG));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    if (here == 6) {
                        assertEquals(List.of(), groupsOf(set, NORMAL));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                        assertEquals(here * 60_000, set.vertices());
                    } else {
                        assertEquals(List.of(0, 1), groupsOf(set, NORMAL));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                        assertEquals(here * 60_000 + 120_000, set.vertices());
                    }
                    assertPlacementsAccounted(set, 2);
                }
            }
        }
    }

    /** Files whose WDT records tile ({@code x}, {@code y}) with its own obj0, file {@code obj0}. */
    private static WorldFormats.Tile alsoRecordOwn(
            WorldTerrainTextureSourceTest.Files files,
            int x,
            int y,
            long obj0,
            List<Modf> records) {
        var wdt = ByteBuffer.wrap(files.sources.get(1L)).order(ByteOrder.LITTLE_ENDIAN);
        int ordinal = x * 64 + y;
        wdt.putInt(12 + 40 + 8 + ordinal * 8, 1);
        int at = 12 + 40 + 8 + 32768 + 8 + ordinal * 32;
        wdt.putInt(at, 2).putInt(at + 4, (int) obj0).putInt(at + 12, 3);
        files.sources.put(obj0, obj0(records, 0));
        return new WorldFormats.Tile(x, y, 2, 0, 0, (int) obj0, 0, 3, 0, 0);
    }

    /** Every drawn (building unique ID, LOD0 group) pair of a tile. */
    private static java.util.Set<String> drawnGroupKeys(WorldExteriorObjects.TileObjects set) {
        var keys = new java.util.TreeSet<String>();
        for (var p : set.placements()) {
            if (p.kind() == WorldExteriorObjects.Kind.BUILDING && p.model() >= 0) {
                for (var mesh : set.models().get(p.model()).meshes()) {
                    keys.add(p.uniqueId() + ":" + mesh.groupOrdinal());
                }
            }
        }
        return keys;
    }

    /** Every drawn set doodad of a tile, by building unique ID, doodad set and MODD ordinal. */
    private static java.util.Set<String> setDoodadKeys(WorldExteriorObjects.TileObjects set) {
        var keys = new java.util.TreeSet<String>();
        for (var p : set.placements()) {
            if (p.kind() == WorldExteriorObjects.Kind.SET_DOODAD) {
                keys.add(p.uniqueId() + ":" + p.doodadSet() + ":" + p.ordinal());
            }
        }
        return keys;
    }

    /**
     * Placement {@code uid}'s groups and set doodads over two tiles: each of {@code groups} drawn
     * by exactly one of them, and {@code doodads} set doodads placed, none by both.
     */
    private static void assertDrawnOnce(
            WorldExteriorObjects.TileObjects here,
            WorldExteriorObjects.TileObjects there,
            long uid,
            List<Integer> groups,
            int doodads) {
        String prefix = uid + ":";
        var mine = new java.util.TreeSet<String>();
        var theirs = new java.util.TreeSet<String>();
        drawnGroupKeys(here).stream().filter(k -> k.startsWith(prefix)).forEach(mine::add);
        drawnGroupKeys(there).stream().filter(k -> k.startsWith(prefix)).forEach(theirs::add);
        var both = new java.util.TreeSet<>(mine);
        both.retainAll(theirs);
        assertEquals(java.util.Set.of(), both, "no group drawn on both tiles");
        var all = new java.util.TreeSet<>(mine);
        all.addAll(theirs);
        var wanted = new java.util.TreeSet<String>();
        groups.forEach(g -> wanted.add(prefix + g));
        assertEquals(wanted, all, "every group drawn once");
        var mineDoodads = new java.util.TreeSet<String>();
        var theirDoodads = new java.util.TreeSet<String>();
        setDoodadKeys(here).stream().filter(k -> k.startsWith(prefix)).forEach(mineDoodads::add);
        setDoodadKeys(there).stream().filter(k -> k.startsWith(prefix)).forEach(theirDoodads::add);
        var bothDoodads = new java.util.TreeSet<>(mineDoodads);
        bothDoodads.retainAll(theirDoodads);
        assertEquals(java.util.Set.of(), bothDoodads, "no set doodad placed on both tiles");
        assertEquals(doodads, mineDoodads.size() + theirDoodads.size(), "every set doodad once");
    }

    @Test
    void aRootDrawnWholeHereDrawsOnlyItsShareOfAPlacementANeighbourSplits() {
        // Root BIG (two 60,000-vertex groups) is over an empty tile's building pool. On 7,19 its
        // placement A lies in the tile and decides first (larger recorded bounds): drawn whole
        // from the large allowance. Its placement C (77) crosses into 8,19, where it decides alone
        // and is split, 8,19 drawing group 1 and the doodad standing there. 7,19 draws C's share
        // under the same rule, group 0 and the doodad standing in 7,19: each of C's groups and set
        // doodads is drawn exactly once over the two tiles.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f = new Fixture(List.of(inTile, largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(0, here.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertDrawnOnce(here, there, 77, List.of(0, 1), 2);
                assertEquals(java.util.Set.of("79:0", "79:1", "77:0"), drawnGroupKeys(here));
                assertPlacementsAccounted(here, 2);
            }
        }
    }

    @Test
    void aRootDrawnWholeByAnInTilePlacementDrawsOnlyItsShareOfAPlacementANeighbourSplits() {
        // Root BIG: two 3-vertex exteriors (local x 0 here, 200 there) and two 60,000-vertex MOGI
        // interiors (5 here, 205 there). Its exteriors fit any tile, but with its interiors it is
        // over an empty tile's 98,304 building vertices, so the owner rule splits it (#652). On
        // 7,19 its placement A lies in the tile and decides first: drawn whole from the pool. Its
        // placement C (77) crosses into 8,19, which decides C alone and splits it, drawing
        // exterior 1, interior 3 and the doodad standing there. 7,19 draws C's share, exterior 0,
        // interior 2 and the doodad standing in 7,19, so each is drawn exactly once.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f = new Fixture(List.of(inTile, largePlacement()), files)) {
            putModel(f, MODEL, model());
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000},
                    new float[] {0, 200, 5, 205},
                    splitPortals(),
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(0, here.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(List.of(1, 3), groupsOf(there, BIG));
                assertDrawnOnce(here, there, 77, List.of(0, 1, 2, 3), 2);
            }
        }
    }

    @Test
    void everyTileDrawsItsShareWhicheverPlacementDecidedTheRoot() {
        // Root BIG (two 60,000-vertex groups, over an empty tile's pool) has an in-tile placement
        // on each side, A (79) on 7,19 and B (81) on 8,19, each deciding the root on its tile
        // (larger recorded bounds) and drawn whole from the large allowance there, and the
        // border-crossing placement C (77). Neither tile decides C, yet each draws its share of
        // it, so C's groups and set doodads are each drawn exactly once.
        var a =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var b =
                new Modf(BIG, 81, 0x8, 10400, 0, 4600, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4570, 10500, 600, 4630);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(b, largePlacement()));
        try (var f = new Fixture(List.of(a, largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, there.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(1, there.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertDrawnOnce(here, there, 77, List.of(0, 1), 2);
                assertTrue(drawnGroupKeys(here).containsAll(List.of("79:0", "79:1")));
                assertTrue(drawnGroupKeys(there).containsAll(List.of("81:0", "81:1")));
            }
        }
    }

    private static WorldExteriorObjects.TileObjects instanceOn(Fixture f, WorldFormats.Tile tile) {
        return WorldExteriorObjects.prepare(
                f.engine,
                f.scope,
                tile,
                Cancellation.NONE,
                WorldExteriorObjects.ModelReader.STANDARD,
                WorldExteriorObjects.ImageDetail.STANDARD,
                WorldExteriorObjects.RETAINED_SOURCE_BYTES,
                WorldExteriorObjects.Interiors.INSTANCE,
                0);
    }

    /** A building inside 7,19 whose recorded bounds are taller than A's and sort before it. */
    private static Modf busyBuilding(long id, long uid, float tall) {
        return new Modf(id, uid, 0x8, 10420, 0, 4160, 0, 0, 0, 1024)
                .bounded(10300, -tall, 4120, 10500, tall, 4180);
    }

    /** Placement {@code uid}'s drawn (unique ID, group) keys on a tile. */
    private static java.util.Set<String> keysOf(WorldExteriorObjects.TileObjects set, long uid) {
        var keys = new java.util.TreeSet<String>();
        for (var key : drawnGroupKeys(set)) {
            if (key.startsWith(uid + ":")) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Test
    void aRootThatFitsAnEmptyTileIsDrawnWholeOnBothTiles() {
        // A busy building (50,000 vertices) on 7,19 leaves too little for root BIG (two
        // 30,000-vertex groups), decided there by its in-tile placement A: drawn whole from the
        // large allowance. Sized as the owner rule sizes it, BIG fits an empty tile, so its
        // border-crossing placement C (77) is drawn whole here, as 8,19, deciding C alone, draws
        // it whole from its pool: the two copies coincide, and neither tile splits C.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f =
                new Fixture(
                        List.of(busyBuilding(NORMAL, 81, 2000), inTile, largePlacement()), files)) {
            putRoot(f, NORMAL, new int[] {EXT}, new int[] {50_000}, new float[] {0});
            putBig(f, new int[] {EXT, EXT}, new int[] {30_000, 30_000}, new float[] {0, 200});
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(0, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(java.util.Set.of("77:0", "77:1"), keysOf(here, 77));
                assertEquals(0, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(java.util.Set.of("77:0", "77:1"), keysOf(there, 77));
            }
        }
    }

    @Test
    void onInstanceMapsTheOwnerRuleSizingLeavesOutGroupsMogpCallsInterior() {
        // On an instance map nothing is deferred. Root BIG: exteriors 0 and 1 (30,000 vertices
        // each) and group 2 (50,000), MOGI exterior but MOGP interior. The owner rule holds group
        // 2 as an interior while sizing, so BIG fits an empty tile (60,000) and 8,19 draws C whole.
        // 7,19, busy, decides BIG by its in-tile placement and goes over at exterior 1; sizing the
        // groups it has not read, it must leave group 2 out too, so it also draws C whole rather
        // than as a share of a split it would wrongly assume.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f =
                new Fixture(
                        List.of(busyBuilding(NORMAL, 81, 2000), inTile, largePlacement()), files)) {
            putRoot(f, NORMAL, new int[] {EXT}, new int[] {50_000}, new float[] {0});
            long[] gfid = {BIG + 1, BIG + 2, BIG + 3};
            f.files.sources.put(gfid[0], bigGroup(EXT, 30_000));
            f.files.sources.put(gfid[1], bigGroup(EXT, 30_000));
            f.files.sources.put(gfid[2], bigGroup(INT, 50_000));
            f.files.sources.put(
                    BIG,
                    root(
                            3,
                            gfid,
                            List.of(new Material(0, IMAGE, 0)),
                            mogiBoxes(new int[] {EXT, EXT, EXT}, new float[] {0, 200, 5})));
            try (var here = f.runInstance();
                    var there = instanceOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(0, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(0, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertTrue(keysOf(here, 77).containsAll(List.of("77:0", "77:1")));
                // Both draw C whole. 7,19 draws it from the large allowance, which leaves out group
                // 2 (MOGP interior without a MOGI interior); 8,19 draws it from its pool and draws
                // group 2 as an interior, from the tile interior allowance.
                assertEquals(java.util.Set.of("77:0", "77:1"), keysOf(here, 77));
                assertEquals(java.util.Set.of("77:0", "77:1", "77:2"), keysOf(there, 77));
            }
        }
    }

    @Test
    void aShareWithoutRoomInTheLargeAllowanceIsCappedAndKeepsItsLocalDoodads() {
        // Split root 970 owns seven 60,000-vertex exteriors here (420,000 of the 458,752 pooled
        // vertices). BIG is decided whole by its in-tile placement A and split by the owner rule.
        // With its exterior 0 at 60,000 vertices, C's share here does not fit what is left even
        // without its interior: it is capped (PLACEMENT_CAPPED alone, no group counts), and the
        // doodad standing here is still placed. With exterior 0 at 3 vertices the share does not
        // fit with interior 2 (60,000) but does without it: it draws its exterior only.
        long other = 970;
        var split =
                new Modf(other, 90, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -900, 4100, 10500, 900, 4500);
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        for (int exterior : new int[] {60_000, 3}) {
            try (var f = new Fixture(List.of(split, inTile, largePlacement()))) {
                putModel(f, MODEL, model());
                putRoot(
                        f,
                        other,
                        new int[] {EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                        new int[] {60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000},
                        new float[] {0, 1, 2, 3, 4, 5, 6, 200});
                putRoot(
                        f,
                        BIG,
                        new int[] {EXT, EXT, ROOM, ROOM},
                        new int[] {exterior, 3, 60_000, 60_000},
                        new float[] {0, 200, 5, 205},
                        splitPortals(),
                        doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
                try (var set = f.runOpenWorld()) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                    assertTrue(setDoodadKeys(set).stream().anyMatch(k -> k.startsWith("77:")));
                    if (exterior == 60_000) {
                        assertEquals(
                                0, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                        assertEquals(java.util.Set.of(), keysOf(set, 77));
                        assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                        assertEquals(
                                0, set.count(WorldExteriorObjects.Count.SHARE_INTERIOR_DROPPED));
                    } else {
                        assertEquals(
                                1, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                        assertEquals(java.util.Set.of("77:0"), keysOf(set, 77));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                        // Interior 2, the share's only interior here, is left out.
                        assertEquals(
                                1, set.count(WorldExteriorObjects.Count.SHARE_INTERIOR_DROPPED));
                    }
                }
            }
        }
    }

    @Test
    void aShareOfARootNotDrawnHereStillPlacesItsLocalDoodads() {
        // Split root 970 takes 420,000 of the pooled vertices here, so BIG (two 60,000-vertex
        // groups, decided by its in-tile placement A and over the building pool) is capped in
        // the large allowance. C's share has no drawn model to come from: it is capped, and the
        // doodad standing here is still placed; A's root places none without its building.
        long other = 970;
        var split =
                new Modf(other, 90, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -900, 4100, 10500, 900, 4500);
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        try (var f = new Fixture(List.of(split, inTile, largePlacement()))) {
            putModel(f, MODEL, model());
            putRoot(
                    f,
                    other,
                    new int[] {EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                    new int[] {60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000},
                    new float[] {0, 1, 2, 3, 4, 5, 6, 200});
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertTrue(setDoodadKeys(set).stream().anyMatch(k -> k.startsWith("77:")));
                assertTrue(setDoodadKeys(set).stream().noneMatch(k -> k.startsWith("79:")));
            }
        }
    }

    @Test
    void aCrossingPlacementPastSixtyFourOthersIsStillSizedForItsShare() {
        // Seventy placements of a missing root sort before everything else and take no building
        // slot. BIG is decided whole by its in-tile placement A and split by the owner rule; its
        // crossing placement C (77) comes after all seventy, yet it is still recognised as
        // crossing and draws only its share, each of its groups and doodads exactly once.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var records = new ArrayList<Modf>();
        for (int i = 0; i < 70; i++) {
            records.add(busyBuilding(9_000, 1000 + i, 900));
        }
        records.add(inTile);
        records.add(largePlacement());
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f = new Fixture(records, files)) {
            putModel(f, MODEL, model());
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000},
                    new float[] {0, 200, 5, 205},
                    splitPortals(),
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertDrawnOnce(here, there, 77, List.of(0, 1, 2, 3), 2);
            }
        }
    }

    @Test
    void aSharesPortalSetIsCountedOnceEvenWhenUnusedImagesAreDropped() {
        // Sixty placements of building 980 (one opaque and 191 cutout batches) are over the draw
        // budget with their cutouts, so the cutouts are dropped and their image goes unused: every
        // model is rebuilt without it. BIG is drawn whole with its portal set and C (77) draws its
        // share with the same set: the set is counted once (ROOT_PORTALS), share or not.
        long busy = 980;
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var records = new ArrayList<Modf>(List.of(inTile, largePlacement()));
        for (int i = 0; i < 60; i++) {
            records.add(Modf.of(busy, 2000 + i, 0x8));
        }
        var first = new ArrayList<Batch>(List.of(new Batch(0, 3, 0, 0)));
        var cutouts = new ArrayList<Batch>();
        for (int b = 0; b < 63; b++) {
            first.add(new Batch(3 + b * 3, 3, 0, 1));
        }
        for (int b = 0; b < 64; b++) {
            cutouts.add(new Batch(b * 3, 3, 0, 1));
        }
        try (var f = new Fixture(records)) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000},
                    new float[] {0, 200, 5, 205},
                    splitPortals());
            f.files.sources.put(
                    busy,
                    root(
                            3,
                            new long[] {busy + 1, busy + 2, busy + 3},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE2, 0))));
            f.files.sources.put(busy + 1, group(EXT, 3, 64, first));
            f.files.sources.put(busy + 2, group(EXT, 3, 64, cutouts));
            f.files.sources.put(busy + 3, group(EXT, 3, 64, cutouts));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertTrue(set.count(WorldExteriorObjects.Count.BATCH_CAPPED) > 0);
                assertTrue(set.images().stream().noneMatch(i -> i.id() == IMAGE2));
                assertEquals(
                        2,
                        set.models().stream()
                                .filter(m -> m.rootId() == BIG && m.portals() != null)
                                .count());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
            }
        }
    }

    @Test
    void aShareNeedsAFreeBuildingModelRow() {
        // The share of C (77) is a model of its own. With the root drawn whole beside 23 other
        // roots, the tile's 24 building model rows are full: the share is capped, and the set
        // doodad standing in this tile is still placed, as for a capped split root.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var records = new ArrayList<Modf>(List.of(inTile, largePlacement()));
        for (int i = 0; i < WorldExteriorObjects.MAX_ROOTS - 1; i++) {
            records.add(Modf.of(3000 + i, 100 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            for (int i = 0; i < WorldExteriorObjects.MAX_ROOTS - 1; i++) {
                f.files.sources.put(3000L + i, root(GROUP));
            }
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var set = f.runOpenWorld()) {
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(
                        WorldExteriorObjects.MAX_ROOTS,
                        set.models().stream().filter(m -> !m.doodad()).count());
                assertTrue(setDoodadKeys(set).stream().anyMatch(k -> k.startsWith("77:")));
                assertPlacementsAccounted(set, records.size());
            }
        }
    }

    @Test
    void aCrossingRecordThatCannotDrawTakesNoSizingReads() {
        // The root's border-crossing records conflict (same unique ID, different declarations),
        // so neither is ever placed: the in-tile placement's decision does not size the root for
        // them, and each interior group is read once, by the interior pass.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var conflicting =
                new Modf(BIG, 77, 0x8, 10410, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4100, 10500, 10, 4500);
        for (boolean conflict : new boolean[] {false, true}) {
            var records = new ArrayList<Modf>(List.of(inTile, largePlacement()));
            if (conflict) {
                records.add(conflicting);
            }
            try (var f = new Fixture(records)) {
                putRoot(
                        f,
                        BIG,
                        new int[] {EXT, EXT, ROOM, ROOM},
                        new int[] {3, 3, 60_000, 60_000},
                        new float[] {0, 200, 5, 205},
                        splitPortals());
                try (var set = f.runOpenWorld()) {
                    if (conflict) {
                        assertEquals(
                                2, set.count(WorldExteriorObjects.Count.PLACEMENT_CONFLICTING));
                        assertEquals(1, f.files.opens(BIG + 3), "read once, by the interior pass");
                    } else {
                        assertEquals(
                                1, set.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                        assertEquals(2, f.files.opens(BIG + 3), "sized for the share, then drawn");
                    }
                }
            }
        }
    }

    @Test
    void aFallbacksExteriorsComeBeforeSplitInteriorsAndSplitInteriorsBeforeItsInteriors() {
        // First, BIG has five 60,000-vertex exteriors here: with NORMAL's the exteriors take
        // 420,000 of the 458,752 pooled vertices, so BIG's 60,000-vertex interior and NORMAL's
        // 50,000-vertex one no longer fit (NORMAL's 3-vertex one does). Had split interiors come
        // before fallback exteriors, BIG's interior would have drawn (360,000) and NORMAL's
        // exteriors would not have fit (480,000).
        var first =
                new Modf(NORMAL, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        try (var f = new Fixture(List.of(largePlacement(), first))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, EXT, EXT, EXT, EXT},
                    new int[] {60_000, 60_000, 60_000, 3, 60_000, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 1, 2, 3, 4},
                    splitPortals());
            putRoot(
                    f,
                    NORMAL,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {60_000, 60_000, 50_000, 3},
                    new float[] {0, 1, 5, 6},
                    splitPortals());
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1, 3), groupsOf(set, NORMAL));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(List.of(0, 4, 5, 6, 7), groupsOf(set, BIG));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
            }
        }
        // Then: split root BIG: four 60,000-vertex exteriors here (240,000) and a 60,000-vertex
        // interior here. Fallback root NORMAL, in this tile: two 60,000-vertex exteriors (over the
        // building
        // pool), a 50,000-vertex interior and a 3-vertex one. Of the 458,752 pooled vertices the
        // exteriors take 360,000, BIG's interior 60,000 (420,000), and NORMAL's 50,000-vertex
        // interior no longer fits; its 3-vertex one does. Had the interiors come first NORMAL's
        // exteriors would not have fit; had NORMAL's interiors come before BIG's, BIG's would not.
        var inTile =
                new Modf(NORMAL, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        try (var f = new Fixture(List.of(largePlacement(), inTile))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, EXT, EXT, EXT},
                    new int[] {60_000, 60_000, 60_000, 3, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 1, 2, 3},
                    splitPortals());
            putRoot(
                    f,
                    NORMAL,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {60_000, 60_000, 50_000, 3},
                    new float[] {0, 1, 5, 6},
                    splitPortals());
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 2, 4, 5, 6), groupsOf(set, BIG));
                assertEquals(List.of(0, 1, 3), groupsOf(set, NORMAL));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
            }
        }
    }

    @Test
    void aCappedFallbackPlacesNoSetDoodadAndCapsItsFurtherPlacements() {
        // Nine 60,000-vertex groups in one tile are over the building pool and the pooled large
        // allowance: the root is capped whole, its two placements capped, and none of its set
        // doodads is placed without it.
        var a =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        var b =
                new Modf(BIG, 80, 0x8, 10420, 0, 4160, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        try (var f = new Fixture(List.of(a, b))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                    new int[] {
                        60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000
                    },
                    new float[] {0, 1, 2, 3, 4, 5, 6, 7, 8},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 1)));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(java.util.Set.of(), setDoodadKeys(set));
                assertPlacementsAccounted(set, 2);
            }
        }
        // With the tile's 64 building slots full once the root is drawn, its further placement
        // is capped.
        var records = new ArrayList<Modf>();
        records.add(a);
        records.add(b);
        for (int i = 0; i < 63; i++) {
            records.add(Modf.of(ROOT, 100 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 1});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(
                        1,
                        set.placements().stream()
                                .filter(
                                        p ->
                                                p.kind() == WorldExteriorObjects.Kind.BUILDING
                                                        && p.rootId() == BIG)
                                .count());
                assertPlacementsAccounted(set, records.size());
            }
        }
    }

    @Test
    void aFallbacksSetDoodadsAreTiedToItsDrawnInteriors() {
        // The split root with portals, placed in one tile: over the building pool, it is drawn
        // whole from the large allowance, every interior with it, and the doodad inside interior
        // 2 that only interior 2 lists is tied to that group (#649).
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        try (var f = new Fixture(List.of(inTile))) {
            putModel(f, MODEL, model());
            putSplitWithPortals(
                    f, doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(5, -50)));
            f.files.sources.put(BIG + 3, withDoodadRefs(bigGroup(ROOM, 3), 0));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1, 2, 3, 4), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertNotNull(modelOf(set, BIG).portals());
                assertEquals(Map.of(0, 2, 1, -1), portalGroups(set));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_IN_GROUP));
            }
        }
    }

    @Test
    void onInstanceMapsAFallbacksInteriorsNeedItsPortalSet() {
        // On an instance map a single-tile root over the building pool is drawn whole from the
        // large allowance too, as roots drawn by group: its interiors draw with the root's portal
        // set, and without one they are left out (GROUP_INTERIOR_NO_PORTALS) rather than drawn
        // unculled from the allowance.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4120, 10500, 10, 4180);
        try (var f = new Fixture(List.of(inTile))) {
            putSplitWithPortals(f);
            try (var set = f.runInstance()) {
                assertEquals(List.of(0, 1, 2, 3, 4), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertNotNull(modelOf(set, BIG).portals());
            }
        }
        try (var f = new Fixture(List.of(inTile))) {
            putBig(
                    f,
                    new int[] {EXT, EXT, ROOM},
                    new int[] {60_000, 60_000, 3},
                    new float[] {0, 1, 5});
            try (var set = f.runInstance()) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_WHOLE_LARGE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
            }
        }
    }

    @Test
    void splitRootsDrawNoInteriorsYet() {
        // A root drawn by group carries no portal set (the tile lacks the headers of the groups
        // other tiles own), so its interiors would draw unculled: none is drawn. The owned
        // MOGI-interior group is counted without being read, an owned group MOGI calls exterior
        // but MOGP interior is dropped after its read, and the neighbour's are other-tile. The
        // tile's own building still draws its interior.
        var normal = Modf.of(NORMAL, 2, 0x8);
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putBig(
                    f,
                    new int[] {EXT, EXT, INT, INT, EXT},
                    new int[] {60_000, 60_000, 3, 3, 3},
                    new float[] {0, 200, 5, 205, 10});
            f.files.sources.put(BIG + 5, group(INT));
            f.files.sources.put(NORMAL + 1, group(EXT));
            f.files.sources.put(NORMAL + 2, group(INT));
            f.files.sources.put(
                    NORMAL, mogiRoot(new long[] {NORMAL + 1, NORMAL + 2}, new int[] {EXT, INT}));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(List.of(0, 1), groupsOf(set, NORMAL));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(null, f.files.opens.get(BIG + 3), "the owned interior is not read");
                assertEquals(null, f.files.opens.get(BIG + 4), "the neighbour's interior");
                var big =
                        set.models().stream()
                                .filter(m -> !m.doodad() && m.rootId() == BIG)
                                .findFirst()
                                .orElseThrow();
                assertEquals(null, big.portals());
            }
        }
    }

    @Test
    void aTileOwningNoGroupOfALargePlacementDrawsNoneOfIt() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {200, 250});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(
                        Map.of(
                                WorldExteriorObjects.Count.PLACEMENT_OTHER_TILE, 1,
                                WorldExteriorObjects.Count.ROOT_OTHER_TILE, 1,
                                WorldExteriorObjects.Count.GROUP_OTHER_TILE, 2),
                        counts(set));
                assertPlacementsAccounted(set, 1);
            }
            try (var set = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0, 1), groupsOf(set, BIG));
            }
        }
    }

    @Test
    void withoutMogiBoundsTheWholeRootBelongsToTheTileAtTheBoundsCentre() {
        // The recorded bounds' centre, native x 12766.67, lies in tile 8,19: without MOGI boxes
        // that tile draws every group (120,000 vertices fit the large allowance), 7,19 none.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            f.files.sources.put(BIG + 1, bigGroup(EXT, 60_000));
            f.files.sources.put(BIG + 2, bigGroup(EXT, 60_000));
            f.files.sources.put(BIG, root(BIG + 1, BIG + 2));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(), groupsOf(here, BIG));
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_OTHER_TILE));
                assertEquals(List.of(0, 1), groupsOf(there, BIG));
                assertEquals(1, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
            }
        }
    }

    @Test
    void setDoodadsOfALargePlacementArePlacedByTheTileTheyStandIn() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        var defs =
                List.of(
                        new Modd(0, 0, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 200, 0, 0, new double[] {0, 0, 0, 1}, 1),
                        new Modd(0, 210, 0, 0, new double[] {0, 0, 0, 1}, 1));
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 3}}, new long[] {MODEL}, defs));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(1, here.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(2, here.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
                assertEquals(2, there.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(1, there.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
                var placed =
                        here.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .toList();
                assertEquals(0, placed.getFirst().ordinal());
            }
        }
    }

    @Test
    void aByGroupRootTakesItsOpaqueImagesFromItsOwnSlots() {
        // The tile's building registers all 64 building images; the large placement's image
        // still draws, from the slots kept for roots drawn by group.
        // A group holds at most 64 batches, so the images are spread over groups of 64.
        var normal = Modf.of(NORMAL, 2, 0x8);
        var materials = new ArrayList<Material>();
        int groups = WorldExteriorObjects.MAX_IMAGES / 64;
        long[] gfid = new long[groups];
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putBig(f, new int[] {EXT, EXT}, new int[] {60_000, 60_000}, new float[] {0, 200});
            for (int g = 0; g < groups; g++) {
                var batches = new ArrayList<Batch>();
                for (int b = 0; b < 64; b++) {
                    int i = g * 64 + b;
                    materials.add(new Material(0, 1000 + i, 0));
                    batches.add(new Batch(3 * b, 3, 0, i));
                    f.files.sources.put(1000L + i, blp(4, 4, 1, 0x0841 * (i % 30 + 1)));
                }
                gfid[g] = NORMAL + 1 + g;
                f.files.sources.put(gfid[g], group(EXT, 3, 64, batches));
            }
            f.files.sources.put(NORMAL, root(groups, gfid, materials));
            var expected = new ArrayList<Integer>();
            for (int g = 0; g < groups; g++) {
                expected.add(g);
            }
            try (var set = f.runOpenWorld()) {
                assertEquals(expected, groupsOf(set, NORMAL));
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(0, set.count(WorldExteriorObjects.Count.BATCH_IMAGE_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.IMAGE_CAPPED));
                assertEquals(WorldExteriorObjects.MAX_IMAGES + 1, set.images().size());
            }
        }
    }

    private static List<Modd> standing(float... localX) {
        var defs = new ArrayList<Modd>();
        for (float x : localX) {
            defs.add(new Modd(0, x, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        return defs;
    }

    @Test
    void aTileDrawingNoGroupStillPlacesTheSetDoodadsStandingInIt() {
        // Both groups belong to 8,19, so 7,19 draws none of the building, but the doodad at local
        // x 0 stands in 7,19: only this tile can place it. The same holds where the tile's share
        // is over the large allowance and the root is capped here.
        var defs = standing(0, 200);
        try (var f = new Fixture(List.of(largePlacement()))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {200, 250},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, defs));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_OTHER_TILE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
                assertPlacementsAccounted(set, 1);
            }
        }
        try (var f = new Fixture(List.of(largePlacement()))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                    new int[] {
                        60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000
                    },
                    new float[] {0, 200, 10, 20, 30, 40, 50, 60, 65},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, defs));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
            }
        }
    }

    @Test
    void setDoodadsInsideAnUndrawnInteriorOfASplitRootAreNotPlaced() {
        // Group 2 is a MOGI interior around local x 5: its doodad would float without walls.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, INT},
                    new int[] {60_000, 60_000, 3},
                    new float[] {0, 200, 5},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(5, -50)));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_INTERIOR));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
            }
        }
    }

    @Test
    void aSplitRootComesAfterEverythingTheTileDrawsOfItsOwn() {
        // A busy tile: a larger building (admitted first, 90,000 of the 98,304 building
        // vertices, with a set doodad and an open-world interior) and MDDF doodads. The split
        // root takes nothing from them, its set doodad follows the tile's own, and the group the
        // sizing pass read is not read again.
        var normal = Modf.of(NORMAL, 2, 0x8).sized(5000);
        var doodads = List.of(Mddf.of(MODEL, 11, 0x40), Mddf.of(MODEL, 12, 0x40));
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200},
                    doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, standing(0)));
            f.files.sources.put(NORMAL + 1, bigGroup(EXT, 45_000));
            f.files.sources.put(NORMAL + 2, group(INT));
            f.files.sources.put(
                    NORMAL,
                    root(
                            3,
                            new long[] {NORMAL + 1, NORMAL + 1, NORMAL + 2},
                            List.of(new Material(0, IMAGE, 0)),
                            mogi(EXT, EXT, INT),
                            doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, standing(1))));
            f.files.sources.put(4L, obj0(List.of(largePlacement(), normal), doodads));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 1, 2), groupsOf(set, NORMAL));
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, f.files.opens.get(BIG + 1).get(), "read once, while sizing");
                var sets =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .map(WorldExteriorObjects.Placement::uniqueId)
                                .toList();
                assertEquals(List.of(2L, 77L), sets, "the tile's own set doodads first");
                // Buildings stay first, in obj0 order.
                assertEquals(WorldExteriorObjects.Kind.BUILDING, set.placements().get(0).kind());
                assertEquals(BIG, set.placements().get(0).rootId());
                assertEquals(NORMAL, set.placements().get(1).rootId());
            }
        }
    }

    @Test
    void withoutInteriorsASplitRootsInteriorGroupsCountAsInteriors() {
        try (var f = new Fixture(List.of(largePlacement()))) {
            putBig(
                    f,
                    new int[] {EXT, EXT, INT, EXT},
                    new int[] {60_000, 60_000, 3, 3},
                    new float[] {0, 200, 5, 10});
            f.files.sources.put(BIG + 4, group(INT));
            try (var set = f.run()) {
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(2, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
            }
        }
    }

    @Test
    void ownersFollowRotatedScaledPlacementsAndSplitBorderPointsOnce() {
        var record = declared(largePlacement());
        double x = 51200.0 / 3 - 4200, y = 51200.0 / 3 - 10400;
        // Heading 90 and scale 2: native x = x + 2 * local y, native y = y - 2 * local x.
        var heading = WorldExteriorObjects.Rotation.heading(0, 1);
        var here = WorldExteriorObjects.Owner.of(record, x, y, 2, heading, 7, 19);
        var there = WorldExteriorObjects.Owner.of(record, x, y, 2, heading, 8, 19);
        var root =
                WmoFormats.root(
                        root(
                                3,
                                new long[] {GROUP, GROUP, GROUP},
                                List.of(new Material(0, IMAGE, 0)),
                                mogiBoxesAt(new float[][] {{0, 0, 0}, {0, -150, 0}, {0, 0, 150}})),
                        () -> false);
        // Local y -150 at scale 2 is native x - 300: tile 8,19.
        assertArrayEquals(new boolean[] {true, false, true}, here.owned(root));
        assertArrayEquals(new boolean[] {false, true, false}, there.owned(root));
        // Pitch 90 turns local up into the heading's forward axis: local z 150 at scale 2 lies
        // 300 native units off the origin, in 8,19 (heading 0: native x = x - local x').
        var pitched = WorldExteriorObjects.Rotation.mddf(1, 0, 90, 0);
        var pitchedHere = WorldExteriorObjects.Owner.of(record, x, y, 2, pitched, 7, 19);
        var pitchedThere = WorldExteriorObjects.Owner.of(record, x, y, 2, pitched, 8, 19);
        assertArrayEquals(new boolean[] {true, true, false}, pitchedHere.owned(root));
        assertArrayEquals(new boolean[] {false, false, true}, pitchedThere.owned(root));
        // A point exactly on the 7/8 border (native x 12,800) belongs to exactly one tile.
        double border = 24 * WorldFormats.TILE_SIZE;
        assertTrue(here.owns(border, y) != there.owns(border, y));
    }

    /** MOGI exterior groups with a 2-unit box around each local centre. */
    private static byte[] mogiBoxesAt(float[][] centres) {
        var b = buffer(32 * centres.length);
        for (int i = 0; i < centres.length; i++) {
            b.putInt(i * 32, EXT);
            for (int axis = 0; axis < 3; axis++) {
                b.putFloat(i * 32 + 4 + axis * 4, centres[i][axis] - 1);
                b.putFloat(i * 32 + 16 + axis * 4, centres[i][axis] + 1);
            }
        }
        return chunk("MOGI", b.array());
    }

    @Test
    void anotherPlacementOfASplitRootInsideOneTileIsCappedNotFailed() {
        // The larger placement decides the root (drawn by 8,19 alone); a second placement of the
        // same root with bounds inside this tile has no owner rule and is capped, while the first
        // still places the set doodad standing here.
        var inside =
                new Modf(BIG, 78, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4150, 10500, 10, 4250);
        try (var f = new Fixture(List.of(largePlacement(), inside))) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {200, 250},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(0, 200)));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_OTHER_TILE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertPlacementsAccounted(set, 2);
            }
        }
    }

    // ---- interiors of roots drawn by group, culled by the root's portal set -------------------

    /** Interior without the exterior bit, as real interior groups are: never a start outdoors. */
    private static final int ROOM = 0x2001;

    /**
     * Portals of the split-root fixture, in MOGI-local axes: portal 0 in the plane x = 3 between
     * exterior group 0 (x -1..1) and interior group 2 (x 4..6), portal 1 in the plane x = 100
     * between interior groups 2 and 3 (x 204..206). MOPR lists each group's references together:
     * group 0 to 2, group 2 to 0 and to 3, group 3 to 2. A MOGP header never says which group a
     * reference leads from; the set recovers it from the pairs.
     */
    private static byte[] splitPortals() {
        var v = buffer(8 * 12);
        for (float x : new float[] {3, 100}) {
            v.put(floats(x, -1, -1, x, 1, -1, x, 1, 1, x, -1, 1));
        }
        var t = buffer(2 * 20);
        t.putShort((short) 0).putShort((short) 4).put(floats(1, 0, 0, -3));
        t.putShort((short) 4).putShort((short) 4).put(floats(1, 0, 0, -100));
        var r = buffer(4 * 8);
        int[][] refs = {{0, 2, -1}, {0, 0, 1}, {1, 3, -1}, {1, 2, 1}};
        for (int[] ref : refs) {
            r.putShort((short) ref[0]).putShort((short) ref[1]).putShort((short) ref[2]);
            r.putShort((short) 0);
        }
        return join(chunk("MOPV", v.array()), chunk("MOPT", t.array()), chunk("MOPR", r.array()));
    }

    /**
     * The split root: exteriors 0 (here, 7,19) and 1 (there, 8,19) over an empty tile's building
     * pool, interiors 2 (here) and 3 (there) joined by portals, and interior 4 (there) whose box
     * reaches one unit into 7,19.
     */
    private static void putSplitWithPortals(Fixture f, byte[]... extras) {
        var chunks = new ArrayList<byte[]>();
        chunks.add(splitPortals());
        chunks.addAll(List.of(extras));
        putBig(
                f,
                new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                new int[] {60_000, 60_000, 3, 3, 3},
                new float[] {0, 200, 5, 205, 67.5f},
                chunks.toArray(byte[][]::new));
    }

    private static WorldExteriorObjects.Model modelOf(
            WorldExteriorObjects.TileObjects set, long root) {
        return set.models().stream()
                .filter(m -> !m.doodad() && m.rootId() == root)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void splitRootsDrawTheirInteriorsWithTheWholePortalSet() {
        // Each tile draws its own interiors after its exteriors, and carries the root's portal
        // set over all five groups, built from the root alone: no tile reads another's groups.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putSplitWithPortals(f);
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0, 2), groupsOf(here, BIG));
                assertEquals(List.of(1, 3, 4), groupsOf(there, BIG));
                var set = modelOf(here, BIG).portals();
                assertNotNull(set);
                var theirs = modelOf(there, BIG).portals();
                assertEquals(set.groups(), theirs.groups());
                assertEquals(set.refs(), theirs.refs());
                assertEquals(2, theirs.portals().size());
                assertEquals(
                        List.of(
                                new WmoPortalTraversal.Ref(0, 2, -1),
                                new WmoPortalTraversal.Ref(0, 0, 1),
                                new WmoPortalTraversal.Ref(1, 3, -1),
                                new WmoPortalTraversal.Ref(1, 2, 1)),
                        set.refs());
                assertEquals(
                        List.of(
                                new WmoPortalTraversal.Group(EXT, -1, -1, -1, 1, 1, 1, 0, 1),
                                new WmoPortalTraversal.Group(EXT, 199, -1, -1, 201, 1, 1, 0, 0),
                                new WmoPortalTraversal.Group(ROOM, 4, -1, -1, 6, 1, 1, 1, 2),
                                new WmoPortalTraversal.Group(ROOM, 204, -1, -1, 206, 1, 1, 3, 1),
                                new WmoPortalTraversal.Group(ROOM, 66.5, -1, -1, 68.5, 1, 1, 0, 0)),
                        set.groups());
                for (var tile : List.of(here, there)) {
                    assertEquals(1, tile.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                    assertEquals(1, tile.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(0, tile.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                    assertEquals(
                            0, tile.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
                    assertEquals(0, tile.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                }
                assertEquals(2, here.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(3, there.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                // Each tile sizes the root from its exteriors; each interior is read once, by
                // the tile owning it.
                for (long g = BIG + 3; g <= BIG + 5; g++) {
                    assertEquals(1, f.files.opens.get(g).get(), "group " + g + " read once");
                }
            }
        }
    }

    @Test
    void theWalkCrossesTileOwnershipFromAStartGroupAnotherTileOwns() {
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putSplitWithPortals(f);
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                var walk = modelOf(here, BIG).portals().traversal();
                // In interior 3, which 8,19 draws: the walk starts there and reaches interior 2
                // through portal 1, so 7,19 draws its interior 2 although it owns no start group,
                // and through portal 0 behind it 7,19's exterior 0; never 8,19's exterior 1.
                var inside = walk.visible(205, 0, 0, List.of());
                assertTrue(inside.inside());
                assertEquals(java.util.BitSet.valueOf(new long[] {0b01101}), inside.visible());
                // Outside, the walk starts from both exteriors, one of each tile, and reaches
                // interior 2 through portal 0; looking off to the side, portal 1 is outside the
                // view through portal 0, so neither tile draws interior 3 or 4.
                var outside = walk.visible(-10, 50, 0, List.of());
                assertFalse(outside.inside());
                assertEquals(java.util.BitSet.valueOf(new long[] {0b00111}), outside.visible());
                // Looking straight through both portals, 8,19's interior 3 is reached from 7,19's
                // interior 2: the walk crosses from one tile's groups to the other's.
                var through = walk.visible(-10, 0, 0, List.of());
                assertEquals(java.util.BitSet.valueOf(new long[] {0b01111}), through.visible());
                // Each tile's meshes name their groups in the shared set.
                for (var tile : List.of(here, there)) {
                    for (var mesh : modelOf(tile, BIG).meshes()) {
                        assertTrue(mesh.groupOrdinal() < walk.groupCount());
                    }
                }
            }
        }
    }

    @Test
    void setDoodadsOfSplitRootInteriorsFollowTheTileDrawingTheirGroup() {
        // Doodads at local x 5 (in interior 2, here), 66.6 (in interior 4, which 8,19 owns,
        // though the doodad stands in 7,19), -50 (outdoors, in 7,19) and 205 (in interior 3,
        // there): each is placed by exactly one tile, the one drawing the walls around it.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putSplitWithPortals(
                    f,
                    doodadChunks(
                            new int[][] {{0, 4}},
                            new long[] {MODEL},
                            standing(5, 66.6f, -50, 205)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                for (var tile : List.of(here, there)) {
                    assertEquals(2, tile.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                    assertEquals(2, tile.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
                    assertEquals(0, tile.count(WorldExteriorObjects.Count.SET_DOODAD_INTERIOR));
                }
            }
        }
        // Without a portal set (portals not declared) no interior is drawn, and the doodads
        // inside them are left out by the tile owning their group.
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, INT, INT, INT},
                    new int[] {60_000, 60_000, 3, 3, 3},
                    new float[] {0, 200, 5, 205, 67.5f},
                    doodadChunks(
                            new int[][] {{0, 4}},
                            new long[] {MODEL},
                            standing(5, 66.6f, -50, 205)));
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0), groupsOf(here, BIG));
                assertEquals(List.of(1), groupsOf(there, BIG));
                assertNull(modelOf(here, BIG).portals());
                assertEquals(1, here.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(1, here.count(WorldExteriorObjects.Count.SET_DOODAD_INTERIOR));
                assertEquals(0, there.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(2, there.count(WorldExteriorObjects.Count.SET_DOODAD_INTERIOR));
                assertEquals(1, here.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
                assertEquals(2, there.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
            }
        }
    }

    @Test
    void aSplitRootsSetPastTheTilePortalTablesDrawsNoInterior() {
        // The tile's own building carries 2,047 portals first; the split root's two no longer fit
        // MAX_TILE_PORTALS, so its interiors are not read and its exterior draws alone.
        var normal = Modf.of(NORMAL, 2, 0x8);
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putSplitWithPortals(f);
            f.files.sources.put(NORMAL + 1, group(EXT));
            f.files.sources.put(
                    NORMAL,
                    root(
                            1,
                            new long[] {NORMAL + 1},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(WmoPortalSet.MAX_TILE_PORTALS - 1, false)));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertNull(modelOf(set, BIG).portals());
                assertNotNull(modelOf(set, NORMAL).portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
                assertEquals(null, f.files.opens.get(BIG + 3), "the interior is not read");
            }
        }
        // References that do not recover per-group ranges (every reference from the same side of
        // its portal, so none is paired) drop the set the same way.
        try (var f = new Fixture(List.of(largePlacement()))) {
            var chunks = splitPortals();
            int mopr = indexOf(chunks, "MOPR") + 8;
            ByteBuffer.wrap(chunks)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(mopr + 4, (short) 1)
                    .putShort(mopr + 20, (short) 1);
            putBig(
                    f,
                    new int[] {EXT, EXT, INT, INT, INT},
                    new int[] {60_000, 60_000, 3, 3, 3},
                    new float[] {0, 200, 5, 205, 67.5f},
                    chunks);
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS_DROPPED));
            }
        }
    }

    /** Root {@code root} with groups {@code root + 1 + g} and MOGI boxes, as putBig. */
    private static void putRoot(
            Fixture f, long root, int[] flags, int[] sizes, float[] centres, byte[]... extras) {
        var gfid = new long[flags.length];
        for (int g = 0; g < flags.length; g++) {
            gfid[g] = root + 1 + g;
            f.files.sources.put(gfid[g], bigGroup(flags[g], sizes[g]));
        }
        var chunks = new ArrayList<byte[]>();
        chunks.add(mogiBoxes(flags, centres));
        chunks.addAll(List.of(extras));
        f.files.sources.put(
                root,
                root(
                        flags.length,
                        gfid,
                        List.of(new Material(0, IMAGE, 0)),
                        chunks.toArray(byte[][]::new)));
    }

    @Test
    void splitRootInteriorsComeFromWhatItsExteriorsLeftAndTheInteriorAllowance() {
        // Here the exteriors take 120,000 of the 458,752 vertices of the large and interior
        // allowances together. The interiors have what they left: 338,752 vertices. Interiors 2
        // to 6 (60,000 each, 300,000) draw; interior 7 (another 60,000) no longer fits and is
        // capped, but interior 8 after it (3 vertices) still fits and draws.
        int left = WorldExteriorObjects.MAX_LARGE_VERTICES - 120_000;
        assertTrue(5 * 60_000 <= left + WorldExteriorObjects.MAX_LARGE_INTERIOR_VERTICES);
        assertTrue(6 * 60_000 > left + WorldExteriorObjects.MAX_LARGE_INTERIOR_VERTICES);
        try (var f = new Fixture(List.of(largePlacement()))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, ROOM, ROOM, ROOM, ROOM, ROOM, EXT},
                    new int[] {
                        60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 3, 60_000
                    },
                    new float[] {0, 200, 5, 6, 7, 8, 9, 10, 11, 1},
                    splitPortals());
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 2, 3, 4, 5, 6, 8, 9), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertNotNull(modelOf(set, BIG).portals());
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                long vertices =
                        modelOf(set, BIG).meshes().stream()
                                .mapToLong(WorldExteriorObjects.Mesh::vertexCount)
                                .sum();
                assertEquals(120_000 + 300_003, vertices);
                assertTrue(
                        vertices
                                <= WorldExteriorObjects.MAX_LARGE_VERTICES
                                        + WorldExteriorObjects.MAX_LARGE_INTERIOR_VERTICES);
            }
        }
        // With the only interior here unreadable, nothing is drawn of the interiors and no set is
        // carried.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, EXT},
                    new int[] {60_000, 60_000, 3, 3, 60_000},
                    new float[] {0, 200, 5, 205, 1},
                    splitPortals());
            f.files.sources.remove(BIG + 3);
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 4), groupsOf(set, BIG));
                assertNull(modelOf(set, BIG).portals());
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
            }
        }
    }

    @Test
    void splitRootImagesShareTheLargeAndInteriorSlotsExteriorsFirst() {
        // The large and interior image slots are one pool (64 + 96). Exteriors 0 and 4 here name
        // 100 distinct images, past the 64 large slots alone, and all draw. Interiors 2 and 5
        // name 61 others after them: 60 fill the pool and the 61st (on interior 5) is capped. A
        // group holds at most 64 batches, so the last image is on a second group, beside an image
        // already registered.
        int slots =
                WorldExteriorObjects.MAX_LARGE_IMAGES
                        + WorldExteriorObjects.MAX_LARGE_INTERIOR_IMAGES;
        assertEquals(160, slots);
        var materials = new ArrayList<Material>();
        for (int i = 0; i <= slots; i++) {
            materials.add(new Material(0, 3000 + i, 0));
        }
        var outside = new ArrayList<Batch>();
        var outsideMore = new ArrayList<Batch>();
        var inside = new ArrayList<Batch>();
        for (int i = 0; i < 64; i++) {
            outside.add(new Batch(3 * i, 3, 0, i));
        }
        for (int i = 0; i < 36; i++) {
            outsideMore.add(new Batch(3 * i, 3, 0, 64 + i));
        }
        for (int i = 0; i < 60; i++) {
            inside.add(new Batch(3 * i, 3, 0, 100 + i));
        }
        int[] flags = {EXT, EXT, ROOM, ROOM, EXT, ROOM};
        try (var f = new Fixture(List.of(largePlacement()))) {
            for (int i = 0; i < materials.size(); i++) {
                f.files.sources.put(3000L + i, blp(4, 4, 1, 0x0841 * (i % 30 + 1)));
            }
            long[] gfid = new long[flags.length];
            for (int g = 0; g < flags.length; g++) {
                gfid[g] = BIG + 1 + g;
            }
            f.files.sources.put(gfid[0], group(EXT, 60_000, 64, outside));
            f.files.sources.put(gfid[1], bigGroup(EXT, 60_000));
            f.files.sources.put(gfid[2], group(ROOM, 3, 60, inside));
            f.files.sources.put(gfid[3], bigGroup(ROOM, 3));
            f.files.sources.put(gfid[4], group(EXT, 3, 36, outsideMore));
            f.files.sources.put(
                    gfid[5],
                    group(ROOM, 3, 2, List.of(new Batch(0, 3, 0, 100), new Batch(3, 3, 0, slots))));
            f.files.sources.put(
                    BIG,
                    root(
                            flags.length,
                            gfid,
                            materials,
                            mogiBoxes(flags, new float[] {0, 200, 5, 205, 1, 6}),
                            splitPortals()));
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 2, 4, 5), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.IMAGE_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_IMAGE_CAPPED));
                assertEquals(slots, set.images().size());
                assertNotNull(modelOf(set, BIG).portals());
            }
        }
    }

    @Test
    void oneSplitRootsInteriorsNeverPushOutAnothersExteriors() {
        // Two split roots on one tile. The first (larger) has four 60,000-vertex exteriors and a
        // 50,000-vertex interior here; the second three 60,000-vertex exteriors here. Every
        // exterior comes first: both roots' exteriors draw (420,000 of the 458,752 vertices of
        // the large and interior allowances together). Had the first root's interior come before
        // the second root's exteriors, those would no longer have fit (470,000). The interior
        // then takes what is left, too little here, and is capped.
        long second = 970;
        var smaller =
                new Modf(second, 78, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4100, 10500, 10, 4400);
        try (var f = new Fixture(List.of(largePlacement(), smaller))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, EXT, EXT, EXT},
                    new int[] {60_000, 60_000, 50_000, 3, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 1, 2, 3},
                    splitPortals());
            putRoot(
                    f,
                    second,
                    new int[] {EXT, EXT, EXT, EXT},
                    new int[] {60_000, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 1, 2});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(0, 4, 5, 6), groupsOf(set, BIG));
                assertEquals(List.of(0, 2, 3), groupsOf(set, second));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
            }
        }
    }

    @Test
    void onInstanceMapsInteriorsSizingNeverReachedStillDraw() {
        // On an instance map interiors are read with the exteriors while sizing; sizing stops at
        // exterior 1, before interiors 2 to 4. Those still wait for the interior pass and draw
        // with the set, rather than being read as exteriors and dropped.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putSplitWithPortals(f);
            try (var set = f.runInstance()) {
                assertEquals(List.of(0, 2), groupsOf(set, BIG));
                assertNotNull(modelOf(set, BIG).portals());
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_NO_PORTALS));
            }
        }
    }

    @Test
    void onInstanceMapsASplitRootsHeldInteriorsNeverCrowdOutLaterRoots() {
        // Sizing the split root holds its 65,000-vertex interior. It leaves the shared bound on
        // held copies (131,072 vertices) once the root is split, so the later building can still
        // hold its two interiors (70,000 vertices).
        var normal = Modf.of(NORMAL, 2, 0x8);
        try (var f = new Fixture(List.of(largePlacement(), normal))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {ROOM, EXT, EXT},
                    new int[] {65_000, 60_000, 60_000},
                    new float[] {5, 0, 200});
            f.files.sources.put(NORMAL + 1, group(EXT));
            f.files.sources.put(NORMAL + 2, bigGroup(INT, 40_000));
            f.files.sources.put(NORMAL + 3, bigGroup(INT, 30_000));
            f.files.sources.put(NORMAL, root(NORMAL + 1, NORMAL + 2, NORMAL + 3));
            try (var set = f.runInstance()) {
                assertEquals(List.of(1), groupsOf(set, BIG));
                assertEquals(List.of(0, 1, 2), groupsOf(set, NORMAL));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
            }
        }
    }

    /**
     * A border-crossing root with a small exterior and large interiors: exteriors 0 (here) and 1
     * (there) of 3 vertices, interiors 2 (here) and 3 (there) of 60,000 vertices joined by portals,
     * and interior 4 (there) of 3 vertices.
     */
    private static void putSmallExteriorLargeInteriors(Fixture f, int interiorVertices) {
        putBig(
                f,
                new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                new int[] {3, 3, interiorVertices, interiorVertices, 3},
                new float[] {0, 200, 5, 205, 67.5f},
                splitPortals());
    }

    @Test
    void aBorderRootWhoseInteriorsAreOverAnEmptyTileIsDrawnByGroup() {
        // Its exteriors alone fit any tile, but with its interiors (120,006 vertices) the root is
        // over an empty tile's 98,304 building vertices: drawn whole, every tile listing it would
        // cap an interior. Sized with its interiors, it is drawn by group, and each tile draws
        // its own share whole, every interior with the root's portal set.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putSmallExteriorLargeInteriors(f, 60_000);
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(0, 2), groupsOf(here, BIG));
                assertEquals(List.of(1, 3, 4), groupsOf(there, BIG));
                for (var set : List.of(here, there)) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                    assertNotNull(modelOf(set, BIG).portals());
                    assertPlacementsAccounted(set, 1);
                }
                assertEquals(3, here.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(2, there.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(60_003, here.vertices());
                assertEquals(60_006, there.vertices());
                // Repeating a tile gives the same set: sizing reads only the placement and root.
                try (var again = f.runOpenWorld()) {
                    assertEquals(counts(here), counts(again));
                    assertEquals(groupsOf(here, BIG), groupsOf(again, BIG));
                }
            }
        }
    }

    @Test
    void aBorderRootWhoseInteriorsFitAnEmptyTileIsStillDrawnWhole() {
        // The same root with 3-vertex interiors fits an empty tile with them: every tile listing
        // it draws it whole, its interiors after every exterior, exactly as before.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putSmallExteriorLargeInteriors(f, 3);
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                for (var set : List.of(here, there)) {
                    assertEquals(List.of(0, 1, 2, 3, 4), groupsOf(set, BIG));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                    assertNotNull(modelOf(set, BIG).portals());
                }
            }
        }
    }

    @Test
    void sizingABorderRootsInteriorsNeverSpendsTheTileGroupReads() {
        // The border root (admitted first, as the largest) has 3 exteriors and 40 small
        // interiors, which fit an empty tile with them; a later root has 220 exterior groups.
        // Sizing reads the interiors without charging the tile's 256 group reads, so the later
        // root is still read whole and drawn (3 + 220), and the border root's interiors are
        // capped whole in the interior pass (40 > 33 left), exactly as when they were never sized.
        long later = 821;
        var flags = new int[43];
        var vertices = new int[43];
        var centres = new float[43];
        for (int g = 0; g < 43; g++) {
            flags[g] = g < 3 ? EXT : INT;
            vertices[g] = 3;
            centres[g] = g < 3 ? 0 : 5;
        }
        WorldStageTimings.reset();
        WorldStageTimings.enable(true);
        try (var f = new Fixture(List.of(largePlacement(), Modf.of(later, 2, 0x8).sized(10)))) {
            putBig(f, flags, vertices, centres);
            f.files.sources.put(GROUP + 50, group(EXT));
            f.files.sources.put(later, mogiRoot(repeat(GROUP + 50, 220), repeatFlags(EXT, 220)));
            try (var set = f.runOpenWorld()) {
                // Sizing ran: all 40 interiors were read to size the border root.
                assertEquals(
                        40,
                        WorldStageTimings.snapshot().get("objects.groups.sizingReadPerTile").sum());
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(2, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(40, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(List.of(0, 1, 2), groupsOf(set, BIG));
                assertEquals(220, groupsOf(set, later).size());
            }
        } finally {
            WorldStageTimings.enable(false);
            WorldStageTimings.reset();
        }
    }

    @Test
    void theSizingDecisionNeverDependsOnOtherRootsTheTileSized() {
        // A larger border root admitted first has 255 small interiors, which it reads to size
        // itself and still fits. The split root sized after it reaches the same decision as on a
        // tile without it: sizing reads only the root, never what earlier roots spent.
        long first = 830;
        var firstGroups = new long[256];
        var firstFlags = new int[256];
        for (int g = 0; g < 256; g++) {
            firstGroups[g] = g == 0 ? GROUP : INTERIOR;
            firstFlags[g] = g == 0 ? EXT : INT;
        }
        var wide =
                new Modf(first, 78, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10200, -20, 4000, 10600, 20, 4600);
        List<Integer> alone;
        try (var f = new Fixture(List.of(largePlacement()))) {
            putSmallExteriorLargeInteriors(f, 60_000);
            try (var set = f.runOpenWorld()) {
                alone = groupsOf(set, BIG);
            }
        }
        assertEquals(List.of(0, 2), alone);
        WorldStageTimings.reset();
        WorldStageTimings.enable(true);
        try (var f = new Fixture(List.of(wide, largePlacement()))) {
            putSmallExteriorLargeInteriors(f, 60_000);
            f.files.sources.put(first, mogiRoot(firstGroups, firstFlags));
            try (var set = f.runOpenWorld()) {
                assertTrue(
                        WorldStageTimings.snapshot().get("objects.groups.sizingReadPerTile").sum()
                                >= 255,
                        "the first root was sized with its interiors");
                assertEquals(alone, groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, groupsOf(set, first).size(), "the first root is drawn whole");
            }
        } finally {
            WorldStageTimings.enable(false);
            WorldStageTimings.reset();
        }
    }

    @Test
    void anInteriorNotReadableNowFailsTheTileWhileSizing() {
        // Sizing the border root, its first large interior is refused at SOURCE_INPUT (not
        // readable now): leaving it out would make this tile draw the root whole while a tile
        // that read it splits it, so the tile fails and is prepared again later.
        try (var f = new Fixture(List.of(largePlacement()), refusingReads(BIG + 3, 1))) {
            putSmallExteriorLargeInteriors(f, 60_000);
            var refusal = assertThrows(WorldTerrainStreaming.Refusal.class, f::runOpenWorld);
            assertEquals("SOURCE_INPUT", refusal.site());
        }
    }

    @Test
    void aMissingInteriorIsLeftOutOfTheSizeOnEveryTile() {
        // A missing interior (SOURCE_UNAVAILABLE, a property of the source) is left out of the
        // size on every tile alike: with only one 60,000-vertex interior left, the root fits an
        // empty tile and is drawn whole, the missing group counted unavailable.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putSmallExteriorLargeInteriors(f, 60_000);
            f.files.sources.remove(BIG + 3);
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(List.of(0, 1, 3, 4), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_UNAVAILABLE));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
            }
        }
    }

    @Test
    void onInstanceMapsABorderRootWhoseInteriorsAreOverAnEmptyTileIsDrawnByGroup() {
        // The exteriors alone fit any tile, but with its interiors (180,006 vertices) the root is
        // over what an empty tile draws a whole root's interiors from: its 98,304 building
        // vertices and its 65,536-vertex interior allowance. Drawn whole, every tile listing it
        // would cap an interior; drawn by group, each tile draws its own share, every group and
        // every set doodad exactly once across the two tiles.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 67.5f},
                    splitPortals(),
                    doodadChunks(
                            new int[][] {{0, 4}},
                            new long[] {MODEL},
                            standing(5, 66.6f, -50, 205)));
            try (var here = f.runInstance();
                    var there = instanceOn(f, neighbour)) {
                assertEquals(List.of(0, 2), groupsOf(here, BIG));
                assertEquals(List.of(1, 3, 4), groupsOf(there, BIG));
                for (var set : List.of(here, there)) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                    assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                    assertNotNull(modelOf(set, BIG).portals());
                    assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                    assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_OTHER_TILE));
                }
                assertEquals(3, here.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(2, there.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                var drawn = new java.util.TreeSet<String>(drawnGroupKeys(here));
                for (var key : drawnGroupKeys(there)) {
                    assertTrue(drawn.add(key), "group " + key + " drawn by both tiles");
                }
                assertEquals(5, drawn.size());
                // Repeating a tile gives the same set: sizing reads only the placement and root.
                try (var again = f.runInstance()) {
                    assertEquals(counts(here), counts(again));
                    assertEquals(groupsOf(here, BIG), groupsOf(again, BIG));
                }
            }
        }
    }

    @Test
    void onInstanceMapsABorderRootWhoseInteriorsFitAnEmptyTileIsStillDrawnWhole() {
        // With 3-vertex interiors the root fits an empty tile: drawn whole by every tile listing
        // it, its interiors held for the interior pass, exactly as before. With 60,000-vertex
        // ones (120,003) it still fits an empty tile's building pool and interior allowance
        // together (163,840), from which a tile drawing it whole draws its interiors.
        for (int interior : new int[] {3, 60_000}) {
            var files = new WorldTerrainTextureSourceTest.Files();
            var neighbour = alsoRecord(files, 8, 19);
            try (var f = new Fixture(List.of(largePlacement()), files)) {
                putSmallExteriorLargeInteriors(f, interior);
                try (var here = f.runInstance();
                        var there = instanceOn(f, neighbour)) {
                    for (var set : List.of(here, there)) {
                        assertEquals(List.of(0, 1, 2, 3, 4), groupsOf(set, BIG));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                        assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                        assertEquals(
                                0, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                    }
                }
            }
        }
    }

    @Test
    void onInstanceMapsTilesAgreeOnABorderRootWhenOneDroppedItsInteriorCopies() {
        // 7,19 first reads a building whose interiors (189,000 vertices) fill most of the bound on
        // held interior copies (196,608), so it drops the border root's 60,000-vertex interior
        // copies; 8,19 lists only the border root and holds them. Sized from every interior's
        // size (180,006 vertices, over an empty tile), both draw the root by group, and 7,19
        // reads its dropped interior 2 again to draw it: every group is drawn exactly once across
        // the tiles.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f =
                new Fixture(List.of(busyBuilding(NORMAL, 81, 2000), largePlacement()), files)) {
            f.files.sources.put(NORMAL + 1, group(EXT));
            for (int g = 2; g <= 4; g++) {
                f.files.sources.put(NORMAL + g, bigGroup(INT, 63_000));
            }
            f.files.sources.put(NORMAL, root(NORMAL + 1, NORMAL + 2, NORMAL + 3, NORMAL + 4));
            putBig(
                    f,
                    new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                    new int[] {3, 3, 60_000, 60_000, 60_000},
                    new float[] {0, 200, 5, 205, 67.5f},
                    splitPortals());
            try (var here = f.runInstance();
                    var there = instanceOn(f, neighbour)) {
                assertEquals(List.of(0, 2), groupsOf(here, BIG));
                assertEquals(List.of(1, 3, 4), groupsOf(there, BIG));
                for (var set : List.of(here, there)) {
                    assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                }
                var drawn = new java.util.TreeSet<String>(drawnGroupKeys(here));
                for (var key : drawnGroupKeys(there)) {
                    assertTrue(drawn.add(key), "group " + key + " drawn by both tiles");
                }
                assertTrue(
                        drawn.containsAll(List.of("77:0", "77:1", "77:2", "77:3", "77:4")),
                        drawn.toString());
            }
        }
    }

    @Test
    void onInstanceMapsAFailedReadOfADroppedInteriorIsCappedOnceAndTheRootStillDraws() {
        // As above, 7,19 drops the border root's interior copies and reads its own interior 2
        // again to draw it. When that second read fails, unavailable or refused at SOURCE_INPUT,
        // interior 2 alone is GROUP_INTERIOR_CAPPED, once: every group keeps one bucket, the set
        // does not fail, and the root still draws its other group here.
        long dropped = BIG + 3;
        int[] base = null;
        for (String variant : List.of("read", "unavailable", "input")) {
            var files =
                    variant.equals("input")
                            ? refusingReads(dropped, 2)
                            : new WorldTerrainTextureSourceTest.Files();
            // Opens asked for, including one that fails before its stream exists.
            var seen = new AtomicInteger();
            files.onOpenRead =
                    id -> {
                        if (id == dropped
                                && seen.incrementAndGet() == 2
                                && variant.equals("unavailable")) {
                            files.failedOpens.put(
                                    id,
                                    new com.vibeminer.files.FileTypes.FileOpen(
                                            com.vibeminer.files.FileTypes.FileReadState.PROTECTED,
                                            null,
                                            null,
                                            null,
                                            null));
                        }
                    };
            alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
            try (var f =
                    new Fixture(List.of(busyBuilding(NORMAL, 81, 2000), largePlacement()), files)) {
                f.files.sources.put(NORMAL + 1, group(EXT));
                for (int g = 2; g <= 4; g++) {
                    f.files.sources.put(NORMAL + g, bigGroup(INT, 63_000));
                }
                f.files.sources.put(NORMAL, root(NORMAL + 1, NORMAL + 2, NORMAL + 3, NORMAL + 4));
                putBig(
                        f,
                        new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                        new int[] {3, 3, 60_000, 60_000, 60_000},
                        new float[] {0, 200, 5, 205, 67.5f},
                        splitPortals());
                try (var here = f.runInstance()) {
                    assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                    assertTrue(seen.get() >= 2, variant + ": read again");
                    int[] counts = new int[2];
                    for (var bucket : WorldExteriorObjects.GROUP_OUTCOMES) {
                        counts[0] += here.count(bucket);
                    }
                    counts[1] = here.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED);
                    if (base == null) {
                        assertEquals(List.of(0, 2), groupsOf(here, BIG));
                        base = counts;
                    } else {
                        assertEquals(List.of(0), groupsOf(here, BIG), variant);
                        assertEquals(base[0], counts[0], variant + ": one bucket per group");
                        assertEquals(base[1] + 1, counts[1], variant + ": capped once");
                    }
                }
            }
        }
    }

    @Test
    void onInstanceMapsAnInTileDecisionSizesTheInteriorsAsTheOwnerRuleDoes() {
        // Root BIG: exteriors 0 and 1 (30,000 vertices each), MOGI interiors 2 and 3 (60,000
        // each) and a small one. 8,19 lists only the crossing placement C: sized with its
        // interiors (180,003) it is over an empty tile, so drawn by group. 7,19, busy, decides BIG
        // by its
        // in-tile placement and goes over at exterior 1; sizing as the owner rule does, interiors
        // included, it finds the same split and draws C's share, so each group of C is drawn
        // exactly once across the two tiles.
        var inTile =
                new Modf(BIG, 79, 0x8, 10400, 0, 4150, 0, 0, 0, 1024)
                        .bounded(10300, -600, 4120, 10500, 600, 4180);
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecordOwn(files, 8, 19, 5, List.of(largePlacement()));
        try (var f =
                new Fixture(
                        List.of(busyBuilding(NORMAL, 81, 2000), inTile, largePlacement()), files)) {
            putRoot(f, NORMAL, new int[] {EXT}, new int[] {50_000}, new float[] {0});
            putBig(
                    f,
                    new int[] {EXT, EXT, ROOM, ROOM, ROOM},
                    new int[] {30_000, 30_000, 60_000, 60_000, 3},
                    new float[] {0, 200, 5, 205, 67.5f},
                    splitPortals());
            try (var here = f.runInstance();
                    var there = instanceOn(f, neighbour)) {
                assertEquals(1, there.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_SPLIT_SHARE));
                var mine = keysOf(here, 77);
                var theirs = keysOf(there, 77);
                for (var key : mine) {
                    assertFalse(theirs.contains(key), "group " + key + " drawn by both tiles");
                }
                var all = new java.util.TreeSet<String>(mine);
                all.addAll(theirs);
                assertEquals(java.util.Set.of("77:0", "77:1", "77:2", "77:3", "77:4"), all);
            }
        }
    }

    @Test
    void outdoorSetDoodadsOfASplitRootComeBeforeThoseInItsInteriors() {
        // The doodad inside interior 2 is recorded first; the outdoor one is still placed first.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putModel(f, MODEL, model());
            putSplitWithPortals(
                    f, doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(5, -50)));
            try (var set = f.runOpenWorld()) {
                var placed =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .map(WorldExteriorObjects.Placement::x)
                                .toList();
                assertEquals(2, placed.size());
                // Native x = 12,866.67 - local x: the outdoor doodad (local -50) first.
                assertTrue(placed.get(0) > placed.get(1), placed.toString());
            }
        }
    }

    @Test
    void aBorderRootsCutoutVerticesNeverMakeItSplit() {
        // 90,000 opaque vertices fit an empty tile's 98,304; the 45,000-vertex cutout-only group
        // is over the 32,768 material vertices and is capped when resolved. Sized alone, it must
        // not count toward the opaque decision: the root is drawn whole, interior included, as
        // before roots were drawn by group.
        try (var f = new Fixture(List.of(largePlacement()))) {
            long a = BIG + 1, b = BIG + 2, cut = BIG + 3, room = BIG + 4;
            f.files.sources.put(a, bigGroup(EXT, 45_000));
            f.files.sources.put(b, bigGroup(EXT, 45_000));
            f.files.sources.put(cut, group(EXT, 45_000, 1, List.of(new Batch(0, 3, 0, 1))));
            f.files.sources.put(room, group(INT));
            f.files.sources.put(
                    BIG,
                    root(
                            4,
                            new long[] {a, b, cut, room},
                            List.of(new Material(0, IMAGE, 0), new Material(1, IMAGE, 0)),
                            mogiBoxes(new int[] {EXT, EXT, EXT, INT}, new float[] {0, 0, 0, 200})));
            try (var set = f.runOpenWorld()) {
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(0, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(List.of(0, 1, 3), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.BATCH_CAPPED));
            }
        }
    }

    /** Every group bucket of {@code set} together. */
    private static int groupOutcomes(WorldExteriorObjects.TileObjects set) {
        int sum = 0;
        for (var bucket : WorldExteriorObjects.GROUP_OUTCOMES) {
            sum += set.count(bucket);
        }
        return sum;
    }

    @Test
    void aTileWhoseShareIsOnlyAnInteriorDrawsItWithTheWholeSet() {
        // Both exteriors (60,000 vertices each) and interior 3 belong to 8,19; 7,19 owns only
        // interior 2. Its exteriors give it nothing to place, so the root is added with that
        // interior alone, in a free building slot, and carries the whole portal set.
        try (var f = new Fixture(List.of(largePlacement()))) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {60_000, 60_000, 3, 3},
                    new float[] {200, 250, 5, 205},
                    splitPortals());
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(2), groupsOf(set, BIG));
                var model = modelOf(set, BIG);
                assertNotNull(model.portals());
                assertEquals(4, model.portals().groups().size());
                var buildings =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.BUILDING)
                                .toList();
                assertEquals(1, buildings.size());
                assertEquals(BIG, buildings.getFirst().rootId());
                assertEquals(model, set.models().get(buildings.getFirst().model()));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_DRAWN));
                assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(4, groupOutcomes(set));
                assertPlacementsAccounted(set, 1);
                assertEquals(1, f.files.opens.get(BIG + 3).get(), "the owned interior");
                assertNull(f.files.opens.get(BIG + 4), "the neighbour's interior");
            }
        }
    }

    @Test
    void anInteriorOnlyShareNeedsAFreeBuildingSlot() {
        // The larger split root's share here is only interior 2; the smaller one's is a 60,000-
        // vertex exterior. 63 buildings leave one slot, which the smaller root's exterior takes
        // first (every split root's exteriors come before any interior). The interior-only share
        // must not make a 65th building: its interior is capped and not read, nothing of the root
        // is placed, and the set doodad standing outdoors in this tile is still placed.
        long second = 970;
        var smaller =
                new Modf(second, 78, 0x8, 10400, 0, 4200, 0, 0, 0, 1024)
                        .bounded(10300, -10, 4100, 10500, 10, 4400);
        var records = new ArrayList<Modf>();
        records.add(largePlacement());
        records.add(smaller);
        for (int i = 0; i < 63; i++) {
            records.add(Modf.of(ROOT, 1 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            putModel(f, MODEL, model());
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM},
                    new int[] {60_000, 60_000, 3, 3},
                    new float[] {200, 250, 5, 205},
                    splitPortals(),
                    doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, standing(-50)));
            putRoot(
                    f,
                    second,
                    new int[] {EXT, EXT},
                    new int[] {60_000, 60_000},
                    new float[] {0, 200});
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(List.of(0), groupsOf(set, second));
                assertTrue(set.models().stream().noneMatch(m -> m.rootId() == BIG));
                assertTrue(
                        set.placements().stream()
                                .noneMatch(
                                        p ->
                                                p.kind() == WorldExteriorObjects.Kind.BUILDING
                                                        && p.rootId() == BIG));
                assertEquals(64, set.count(WorldExteriorObjects.Count.PLACEMENT_DRAWN));
                assertEquals(1, set.count(WorldExteriorObjects.Count.PLACEMENT_EMPTY));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_EMPTY));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_BY_GROUP));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(0, set.count(WorldExteriorObjects.Count.ROOT_PORTALS));
                assertPlacementsAccounted(set, records.size());
                assertNull(f.files.opens.get(BIG + 3), "the capped interior is not read");
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(
                        1,
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .count());
            }
        }
    }

    @Test
    void aCappedSplitRootStillCountsEveryGroupOnce() {
        // Here the root's share (exteriors 0 and 4 to 10, 480,000 vertices) is over the large and
        // interior allowances together (458,752), so it is capped. Its groups still land in one
        // bucket each: its exteriors GROUP_CAPPED, its interior 2 GROUP_INTERIOR_CAPPED, and the
        // neighbour's two other-tile, as on 8,19, which draws exterior 1 and interior 3.
        var files = new WorldTerrainTextureSourceTest.Files();
        var neighbour = alsoRecord(files, 8, 19);
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putRoot(
                    f,
                    BIG,
                    new int[] {EXT, EXT, ROOM, ROOM, EXT, EXT, EXT, EXT, EXT, EXT, EXT},
                    new int[] {
                        60_000, 60_000, 3, 3, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000, 60_000
                    },
                    new float[] {0, 200, 5, 205, 10, 20, 30, 40, 50, 60, 65},
                    splitPortals());
            try (var here = f.runOpenWorld();
                    var there = openWorldOn(f, neighbour)) {
                assertEquals(List.of(), groupsOf(here, BIG));
                assertEquals(1, here.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, here.count(WorldExteriorObjects.Count.PLACEMENT_CAPPED));
                assertEquals(8, here.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertEquals(1, here.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(2, here.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(11, groupOutcomes(here));
                assertEquals(List.of(1, 3), groupsOf(there, BIG));
                assertEquals(9, there.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(11, groupOutcomes(there));
            }
        }
        // Capped before any read, with every building slot taken by the tile's own 64.
        var records = new ArrayList<Modf>();
        records.add(largePlacement());
        for (int i = 0; i < 64; i++) {
            records.add(Modf.of(ROOT, 1 + i, 0x8));
        }
        try (var f = new Fixture(records)) {
            putSplitWithPortals(f);
            try (var set = f.runOpenWorld()) {
                assertEquals(List.of(), groupsOf(set, BIG));
                assertEquals(1, set.count(WorldExteriorObjects.Count.ROOT_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_CAPPED));
                assertEquals(1, set.count(WorldExteriorObjects.Count.GROUP_INTERIOR_CAPPED));
                assertEquals(3, set.count(WorldExteriorObjects.Count.GROUP_OTHER_TILE));
                assertEquals(5 + groupsOf(set, ROOT).size(), groupOutcomes(set));
                assertPlacementsAccounted(set, records.size());
            }
        }
    }

    // ---- set doodads tied to one interior group, culled with it ----------------------------

    /** {@code group} (MOGP its last chunk) with a MODR listing {@code refs} appended to MOGP. */
    private static byte[] withDoodadRefs(byte[] group, int... refs) {
        int body = indexOf(group, "MOGP") + 8;
        var r = buffer(refs.length * 2);
        for (int ref : refs) {
            r.putShort((short) ref);
        }
        return join(
                version(17),
                chunk(
                        "MOGP",
                        join(
                                Arrays.copyOfRange(group, body, group.length),
                                chunk("MODR", r.array()))));
    }

    /** Each drawn set doodad's MODD ordinal and portal group, in placement order. */
    private static Map<Integer, Integer> portalGroups(WorldExteriorObjects.TileObjects set) {
        var groups = new java.util.TreeMap<Integer, Integer>();
        for (var p : set.placements()) {
            if (p.kind() == WorldExteriorObjects.Kind.SET_DOODAD) {
                groups.put(p.ordinal(), p.portalGroup());
            }
        }
        return groups;
    }

    @Test
    void setDoodadsOfASplitRootAreTiedToTheOneDrawnInteriorListingThem() {
        // In interior 2 (x 4..6, drawn here): doodad 0 is listed by interior 2's MODR alone,
        // doodad 1 by interior 2 and exterior 0, doodad 2 by no group; doodad 3 stands outdoors
        // and exterior 0 lists it. Doodad 4 stands outdoors too, but only interior 2 lists it:
        // another tile's group, unread here, might list it as well, so a split root ties a
        // doodad only to the interior whose box placed it. Only doodad 0 is tied, to group 2.
        var files = new WorldTerrainTextureSourceTest.Files();
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putSplitWithPortals(
                    f,
                    doodadChunks(
                            new int[][] {{0, 5}},
                            new long[] {MODEL},
                            standing(5, 5.5f, 5.2f, -50, -40)));
            f.files.sources.put(BIG + 1, withDoodadRefs(bigGroup(EXT, 60_000), 1, 3));
            // References past the root's five MODD entries are dropped.
            f.files.sources.put(BIG + 3, withDoodadRefs(bigGroup(ROOM, 3), 0, 1, 4, 5, 65535));
            try (var here = f.runOpenWorld()) {
                assertEquals(List.of(0, 2), groupsOf(here, BIG));
                assertEquals(Map.of(0, 2, 1, -1, 2, -1, 3, -1, 4, -1), portalGroups(here));
                assertEquals(1, here.count(WorldExteriorObjects.Count.SET_DOODAD_IN_GROUP));
                assertEquals(1, here.count(WorldExteriorObjects.Count.SET_DOODAD_GROUPS_SHARED));
                assertEquals(5, here.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                // The tied doodad's building is the placement with its unique ID.
                var tied =
                        here.placements().stream()
                                .filter(p -> p.portalGroup() >= 0)
                                .findFirst()
                                .orElseThrow();
                assertEquals(
                        1,
                        here.placements().stream()
                                .filter(
                                        p ->
                                                p.kind() == WorldExteriorObjects.Kind.BUILDING
                                                        && p.uniqueId() == tied.uniqueId())
                                .count());
            }
        }
        // Without a portal set nothing is tied, though the MODR is the same.
        try (var f = new Fixture(List.of(largePlacement()), files)) {
            putModel(f, MODEL, model());
            putBig(
                    f,
                    new int[] {EXT, EXT, INT, INT, INT},
                    new int[] {60_000, 60_000, 3, 3, 3},
                    new float[] {0, 200, 5, 205, 67.5f},
                    doodadChunks(new int[][] {{0, 2}}, new long[] {MODEL}, standing(-50, -40)));
            f.files.sources.put(BIG + 1, withDoodadRefs(bigGroup(EXT, 60_000), 0, 1));
            try (var here = f.runOpenWorld()) {
                assertEquals(Map.of(0, -1, 1, -1), portalGroups(here));
                assertEquals(0, here.count(WorldExteriorObjects.Count.SET_DOODAD_IN_GROUP));
            }
        }
    }

    /** An instance root: exterior GROUP and interior INTERIOR joined by one portal. */
    private static void putRootWithPortals(Fixture f, byte[] doodads, int[] outside, int[] inside) {
        f.files.sources.put(
                ROOT,
                root(
                        2,
                        new long[] {GROUP, INTERIOR},
                        List.of(new Material(0, IMAGE, 0)),
                        portalChunks(1, true),
                        doodads));
        f.files.sources.put(GROUP, withDoodadRefs(portalRange(group(EXT), 0, 1), outside));
        f.files.sources.put(INTERIOR, withDoodadRefs(portalRange(group(INT), 1, 1), inside));
    }

    @Test
    void setDoodadsOfARootWithPortalsAreTiedToTheirInteriorGroup() {
        // Doodad 0 is listed by the interior, 1 by the exterior, 2 by both, 3 by neither.
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            putModel(f, MODEL, model());
            putRootWithPortals(
                    f,
                    doodadChunks(new int[][] {{0, 4}}, new long[] {MODEL}, standing(0, 0, 0, 0)),
                    new int[] {1, 2},
                    new int[] {0, 2});
            try (var set = f.runInstance()) {
                assertNotNull(set.models().getFirst().portals());
                assertEquals(Map.of(0, 1, 1, -1, 2, -1, 3, -1), portalGroups(set));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_IN_GROUP));
                assertEquals(1, set.count(WorldExteriorObjects.Count.SET_DOODAD_GROUPS_SHARED));
            }
            // Without interiors there is no portal set and no tie.
            try (var set = f.run()) {
                assertNull(set.models().getFirst().portals());
                assertEquals(Map.of(0, -1, 1, -1, 2, -1, 3, -1), portalGroups(set));
            }
        }
    }

    /** The set doodads' lights by MODD ordinal (null for the World light). */
    private static Map<Integer, WorldExteriorObjects.DoodadLight> lights(
            WorldExteriorObjects.TileObjects set) {
        var lights = new java.util.TreeMap<Integer, WorldExteriorObjects.DoodadLight>();
        for (var p : set.placements()) {
            if (p.kind() == WorldExteriorObjects.Kind.SET_DOODAD) {
                lights.put(p.ordinal(), p.light());
            }
        }
        return lights;
    }

    @Test
    void setDoodadsAnInteriorGroupListsTakeItsInteriorLight() {
        // MODD 0: plain; 1: interior lighting (0x2); 2: 0x4 (a MOLT light, not read); 3: listed by
        // the exterior group only. The interior group is a pure interior (0x2000 without 0x8).
        int[] flags = {0, 0x2, 0x4, 0};
        var defs = new ArrayList<Modd>();
        for (int flag : flags) {
            defs.add(new Modd(flag << 24, 0, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        byte[] doodads = doodadChunks(new int[][] {{0, 4}}, new long[] {MODEL}, defs);
        // MODD colours as stored (B, G, R, A): R 0x30, G 0x20, B 0x10, alpha 255.
        int colour = 0xff301f10;
        int modd = indexOf(doodads, "MODD") + 8;
        for (int i = 0; i < flags.length; i++) {
            ByteBuffer.wrap(doodads)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(modd + i * 40 + 36, colour);
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, INTERIOR},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(1, true),
                            doodads));
            f.files.sources.put(GROUP, withDoodadRefs(portalRange(group(EXT), 0, 1), 3));
            f.files.sources.put(INTERIOR, withDoodadRefs(portalRange(group(ROOM), 1, 1), 0, 1, 2));
            try (var set = f.runInstance()) {
                var lights = lights(set);
                assertEquals(4, lights.size());
                assertEquals(
                        WorldExteriorObjects.doodadLight(0, colour, FIXTURE_AMBIENT, null),
                        lights.get(0));
                var interior = lights.get(1);
                assertNotNull(interior);
                // Ambient the building's (0x0b), direct the MODD colour.
                assertEquals(0x0b / 255f, interior.ambientR(), 1e-6);
                assertEquals(0x30 / 255f, interior.directR(), 1e-6);
                assertNull(lights.get(2), "MODD 0x4 keeps the World light");
                assertNull(lights.get(3), "an exterior group's doodad keeps the World light");
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_INTERIOR_LIGHT));
                assertEquals(1, set.count(WorldExteriorObjects.Count.DOODAD_LIGHT_UNSUPPORTED));
            }
            // An interior group also flagged exterior lights its doodads as outdoors.
            f.files.sources.put(
                    INTERIOR, withDoodadRefs(portalRange(group(ROOM | 0x8), 1, 1), 0, 1, 2));
            try (var set = f.runInstance()) {
                assertTrue(lights(set).values().stream().allMatch(java.util.Objects::isNull));
            }
        }
    }

    @Test
    void anInteriorLightingDoodadIsLitTowardItsGroupCentreThroughTheBuildingTransform() {
        // A building turned 37 degrees, pitched 5 and scaled 1.5 (MODF 0x4, 1536 / 1024), with a
        // MODD 0x2 doodad off its origin: the light points from the doodad's placed origin to its
        // group box centre placed by the same building transform.
        var defs = List.of(new Modd(0x2 << 24, 1.5f, -2, 0.5f, new double[] {0, 0, 0, 1}, 1));
        byte[] doodads = doodadChunks(new int[][] {{0, 1}}, new long[] {MODEL}, defs);
        int modd = indexOf(doodads, "MODD") + 8;
        ByteBuffer.wrap(doodads).order(ByteOrder.LITTLE_ENDIAN).putInt(modd + 36, 0xff302010);
        var building = new Modf(ROOT, 1, 0x8 | 0x4, 100, 20, 300, 5, 37, 0, 1536);
        try (var f = new Fixture(List.of(building))) {
            putModel(f, MODEL, model());
            f.files.sources.put(
                    ROOT,
                    root(
                            2,
                            new long[] {GROUP, INTERIOR},
                            List.of(new Material(0, IMAGE, 0)),
                            portalChunks(1, true),
                            doodads));
            f.files.sources.put(GROUP, withDoodadRefs(portalRange(group(EXT), 0, 1)));
            f.files.sources.put(INTERIOR, withDoodadRefs(portalRange(group(ROOM), 1, 1), 0));
            try (var set = f.runInstance()) {
                var b =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.BUILDING)
                                .findFirst()
                                .orElseThrow();
                var d =
                        set.placements().stream()
                                .filter(p -> p.kind() == WorldExteriorObjects.Kind.SET_DOODAD)
                                .findFirst()
                                .orElseThrow();
                var g = set.models().get(b.model()).portals().groups().get(1);
                double[] m = b.rotation().rowMajor();
                double cx = (g.minX() + g.maxX()) / 2, cy = (g.minY() + g.maxY()) / 2;
                double cz = (g.minZ() + g.maxZ()) / 2, s = b.scale();
                double[] centre = {
                    b.x() + s * (m[0] * cx + m[1] * cy + m[2] * cz),
                    b.y() + s * (m[3] * cx + m[4] * cy + m[5] * cz),
                    b.z() + s * (m[6] * cx + m[7] * cy + m[8] * cz)
                };
                double dx = centre[0] - d.x(), dy = centre[1] - d.y(), dz = centre[2] - d.z();
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                assertTrue(length > 0.1, "the doodad stands off the group centre");
                var light = d.light();
                assertNotNull(light);
                assertEquals(dx / length, light.towardX(), 1e-5);
                assertEquals(dy / length, light.towardY(), 1e-5);
                assertEquals(dz / length, light.towardZ(), 1e-5);
                assertEquals(1.5, s, 1e-9);
                assertTrue(Math.abs(m[1]) > 0.5, "the building is turned");
            }
        }
    }

    @Test
    void theReadProfileCoversEveryImageSlotReadTwice() {
        var profile = WorldStreamScopes.UseProfile.BASIC_EXTERIOR;
        // Building images: the tile's, the split roots' shared large and interior slots, and the
        // cutout and blended ones, each read at most twice.
        int wmoSlots =
                WorldExteriorObjects.MAX_IMAGES
                        + WorldExteriorObjects.MAX_LARGE_IMAGES
                        + WorldExteriorObjects.MAX_LARGE_INTERIOR_IMAGES
                        + WorldExteriorObjects.MAX_MATERIAL_IMAGES;
        assertEquals(128 + 64 + 96 + 32, wmoSlots);
        assertEquals(wmoSlots, WorldStreamScopes.WMO_IMAGE_SLOTS);
        assertEquals(4, WorldStreamScopes.IMAGE_READS_PER_SLOT);
        assertEquals(
                WorldStreamScopes.IMAGE_READS_PER_SLOT * wmoSlots,
                profile.derivedReads(WorldStreamScopes.DerivedKind.WMO_IMAGE));
        int slots =
                com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_IMAGES
                        + com.vibeminer.rendering.world.ExteriorObjectLimits.DOODAD_INTERIOR_IMAGES;
        int models = WorldExteriorObjects.MAX_MODELS + WorldExteriorObjects.MAX_INTERIOR_MODELS;
        // An image may be read at registration and again for each later use (source(Demand)).
        assertEquals(
                WorldStreamScopes.IMAGE_READS_PER_SLOT * slots,
                profile.derivedReads(WorldStreamScopes.DerivedKind.M2_IMAGE));
        assertEquals(840 + 80, slots);
        assertEquals(672 + 64, models);
        // The read-ahead queue holds every model hint of a placement pass, each one's skin and
        // every doodad image slot.
        assertEquals(2 * models + slots, WorldStreamScopes.Prefetcher.QUEUE);
        assertEquals(models, profile.derivedReads(WorldStreamScopes.DerivedKind.M2_MODEL));
        assertEquals(models, profile.derivedReads(WorldStreamScopes.DerivedKind.M2_SKIN));
        // Every distinct source a tile may bind: WDT, OBJ0, 24 roots, 256 groups, 256 groups read
        // only for sizing for each of the 24 roots, the WMO image slots, and each doodad model,
        // skin and image slot.
        int sizing = 24 * 256;
        assertEquals(
                WorldExteriorObjects.MAX_ROOTS * WorldExteriorObjects.MAX_SIZING_READS, sizing);
        assertEquals(
                2 + 24 + 256 + sizing + wmoSlots + models + models + slots, profile.bindingLimit());
        // The tile's group reads, the worst case of sizing border-crossing roots, and one second
        // read of each group the tile read (an instance interior whose held copy was dropped).
        assertEquals(256, WorldStreamScopes.REREAD_GROUP_READS);
        assertEquals(
                WorldExteriorObjects.MAX_GROUPS + sizing + WorldStreamScopes.REREAD_GROUP_READS,
                profile.derivedReads(WorldStreamScopes.DerivedKind.WMO_GROUP));
    }

    @Test
    void tiedSetDoodadsMayReadModelsFromTheInteriorAllowance() {
        // MDDF doodads read all MAX_MODELS models first. Then the root's set doodads each name a
        // new model: those the interior lists are tied and read from the interior allowance
        // until it is spent; the one the exterior lists is capped as before.
        int base = WorldExteriorObjects.MAX_MODELS,
                allowance = WorldExteriorObjects.MAX_INTERIOR_MODELS;
        long first = 10_000; // clear of every other fixture ID
        var mddf = new ArrayList<Mddf>();
        for (int i = 0; i < base; i++) {
            mddf.add(Mddf.of(first + i, i + 1, 0x40));
        }
        int tied = allowance + 1;
        var ids = new long[tied + 1];
        var defs = new ArrayList<Modd>();
        var inside = new int[tied];
        for (int i = 0; i <= tied; i++) {
            ids[i] = first + base + i;
            defs.add(new Modd(i, 0, 0, 0, new double[] {0, 0, 0, 1}, 1));
            if (i < tied) {
                inside[i] = i;
            }
        }
        try (var f = new Fixture(List.of(Modf.of(ROOT, 1, 0x8)))) {
            for (int i = 0; i < base + tied + 1; i++) {
                putModel(f, first + i, model());
            }
            putRootWithPortals(
                    f,
                    doodadChunks(new int[][] {{0, tied + 1}}, ids, defs),
                    new int[] {tied},
                    inside);
            f.files.sources.put(4L, obj0(List.of(Modf.of(ROOT, 1, 0x8)), mddf));
            try (var set = f.runInstance()) {
                assertEquals(base, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                assertEquals(
                        allowance, set.count(WorldExteriorObjects.Count.MODEL_INTERIOR_ALLOWANCE));
                assertEquals(allowance, set.count(WorldExteriorObjects.Count.SET_DOODAD_DRAWN));
                assertEquals(allowance, set.count(WorldExteriorObjects.Count.SET_DOODAD_IN_GROUP));
                // One tied doodad past the allowance and the untied one.
                assertEquals(2, set.count(WorldExteriorObjects.Count.SET_DOODAD_CAPPED));
                assertEquals(2, set.count(WorldExteriorObjects.Count.DOODAD_CAP_MODELS));
                assertEquals(
                        base + allowance,
                        set.models().stream().filter(WorldExteriorObjects.Model::doodad).count());
                var groups = portalGroups(set);
                assertEquals(allowance, groups.size());
                assertTrue(groups.values().stream().allMatch(g -> g == 1));
            }
            // The read-ahead fetches neither capped model, the tied one past the allowance or
            // the untied one; every model read is read once.
            assertEquals(0, f.files.opens(ids[tied - 1]));
            assertEquals(0, f.files.opens(ids[tied]));
            for (int i = 0; i < base + allowance; i++) {
                assertEquals(1, f.files.opens(first + i), "model " + (first + i));
            }
        }
    }

    /** The authored model naming {@code skin} as its first SKIN (SFID's first entry). */
    private static byte[] model(long skin) {
        var bytes = model();
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, (int) skin);
        return bytes;
    }

    /**
     * {@code count} MDDF doodads, each with its own model (20,000 on), SKIN (30,000 on) and image
     * (40,000 on); the models and images are slow.
     */
    private static List<Mddf> distinctModels(Fixture f, SlowGroups files, int count) {
        long first = 20_000, skins = 30_000, images = 40_000; // clear of every other fixture ID
        files.slow = id -> id >= first && id < first + count || id >= images && id < images + count;
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < count; i++) {
            var model = model(skins + i);
            // The fixture's TXID chunk holds its one texture's FileDataID at byte 24.
            ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN).putInt(24, (int) (images + i));
            f.files.sources.put(first + i, model);
            f.files.sources.put(skins + i, skin());
            f.files.sources.put(images + i, blp(4, 4, 1, 0x07e0 + i));
            doodads.add(Mddf.of(first + i, i + 1, 0x40));
        }
        return doodads;
    }

    /** What a set places and draws, comparable across runs. */
    private static List<Object> placed(WorldExteriorObjects.TileObjects set) {
        var summary = new ArrayList<Object>();
        summary.add(counts(set));
        summary.add(set.vertices());
        for (var p : set.placements()) {
            summary.add(
                    List.of(
                            p.kind(),
                            p.ordinal(),
                            p.uniqueId(),
                            p.rootId(),
                            p.model(),
                            p.x(),
                            p.y(),
                            p.z(),
                            p.portalGroup()));
        }
        for (var m : set.models()) {
            summary.add(List.of(m.rootId(), m.doodad(), m.meshes().size()));
        }
        return summary;
    }

    @Test
    void doodadModelsTheirSkinsAndImagesAreReadAheadAndPlaceTheSame() {
        int count = 12;
        List<Object> serial = null;
        long serialReads = -1;
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            WorldStageTimings.reset();
            WorldStageTimings.enable(true);
            try (var f = new Fixture(List.of(), files)) {
                var doodads = distinctModels(f, files, count);
                f.files.sources.put(4L, obj0(List.of(), doodads));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                List<Object> summary;
                try (var set = f.run()) {
                    assertEquals(count, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                    summary = placed(set);
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                }
                for (int i = 0; i < count; i++) {
                    assertEquals(1, f.files.opens(20_000 + i), "each model is read once");
                    assertEquals(1, f.files.opens(30_000 + i), "each skin is read once");
                    assertEquals(1, f.files.opens(40_000 + i), "each image is read once");
                }
                assertEquals(0, files.open.get(), "every read is closed");
                var snapshot = WorldStageTimings.snapshot();
                var reads = snapshot.get("source.read.BASIC_EXTERIOR");
                var waits = snapshot.get("source.prefetchWait.BASIC_EXTERIOR");
                if (!ahead) {
                    assertEquals(1, files.peak.get(), "serial reads never overlap");
                    assertNull(waits);
                    serial = summary;
                    serialReads = reads.count();
                } else {
                    assertTrue(files.peak.get() >= 2, "models were read concurrently");
                    assertTrue(
                            files.peak.get() <= WorldStreamScopes.Prefetcher.READS + 1,
                            "peak " + files.peak.get());
                    assertEquals(serial, summary, "read-ahead changes no output");
                    long hits = waits == null ? 0 : waits.count();
                    long misses = reads == null ? 0 : reads.count();
                    assertEquals(serialReads, misses + hits, "the same inputs are consumed");
                    // Every model, skins and images too: a skin is named by its model's bytes and
                    // an image by its model's and skin's.
                    assertTrue(hits > 2 * count, "read-ahead hits " + hits);
                }
            } finally {
                WorldStageTimings.enable(false);
                WorldStageTimings.reset();
            }
        }
    }

    @Test
    void theReadAheadNeverFetchesADoodadImageTheReadProfileLeavesUnread() {
        int count = 6;
        var files = new SlowGroups();
        files.delay = 5;
        try (var f = new Fixture(List.of(), files)) {
            var doodads = distinctModels(f, files, count);
            f.files.sources.put(4L, obj0(List.of(), doodads));
            f.engine.lowerDerivedReadsForTest(WorldStreamScopes.DerivedKind.M2_IMAGE, 2);
            try (var set = f.run()) {
                assertEquals(2, set.count(WorldExteriorObjects.Count.MODEL_IMAGE_DECODED));
                assertEquals(count - 2, set.count(WorldExteriorObjects.Count.SOURCE_READ_CAPPED));
            }
            assertEquals(1, f.files.opens(40_000));
            assertEquals(1, f.files.opens(40_001));
            for (int i = 2; i < count; i++) {
                assertEquals(0, f.files.opens(40_000 + i), "a capped source is never read");
            }
        }
    }

    @Test
    void modelsSharingOneSkinAreEachReadOnceWhateverOrderTheirReadsFinishIn() {
        // Every model names the same SKIN, and every skin the same image: a later model's read
        // can finish first and name them before an earlier one does. Taking them for the earlier
        // model must not drop the reads of the models in between.
        for (int run = 0; run < 5; run++) {
            var files = new SlowGroups();
            files.delay = 0;
            try (var f = new Fixture(List.of(), files)) {
                var doodads = new ArrayList<Mddf>();
                for (int i = 0; i < 40; i++) {
                    f.files.sources.put(20_000L + i, model());
                    doodads.add(Mddf.of(20_000L + i, i + 1, 0x40));
                }
                putModel(f, MODEL, model());
                f.files.sources.put(4L, obj0(List.of(), doodads));
                try (var set = f.run()) {
                    assertEquals(40, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
                }
                for (int i = 0; i < 40; i++) {
                    assertEquals(1, f.files.opens(20_000L + i), "model " + (20_000 + i));
                }
                assertEquals(40, f.files.opens(SKIN), "each model reads its skin once");
                // Every skin names the same image: read once, wherever a skin named it first.
                assertEquals(1, f.files.opens(MODEL_IMAGE), "the shared image is read once");
            }
        }
    }

    @Test
    void theReadAheadNeverFetchesAModelThePlacementCapLeavesUnread() {
        // MDDF doodads fill the placement cap; the building's set doodads name a model of their
        // own, which is never read, neither by the walk nor ahead of it.
        var doodads = new ArrayList<Mddf>();
        for (int i = 0; i < WorldExteriorObjects.MAX_DOODADS; i++) {
            doodads.add(Mddf.of(MODEL, i + 1, 0x40));
        }
        long other = MODEL + 1;
        var three = new ArrayList<Modd>();
        for (int i = 0; i < 3; i++) {
            three.add(new Modd(0, i, 0, 0, new double[] {0, 0, 0, 1}, 1));
        }
        try (var f = new Fixture(List.of())) {
            putModel(f, MODEL, model());
            putModel(f, other, model());
            f.files.sources.put(
                    ROOT,
                    root(
                            1,
                            new long[] {GROUP},
                            List.of(new Material(0, IMAGE, 0)),
                            doodadChunks(new int[][] {{0, 3}}, new long[] {other}, three)));
            try (var set = run(f, obj0(List.of(Modf.of(ROOT, 1, 0x8)), doodads, 0))) {
                assertEquals(3, set.count(WorldExteriorObjects.Count.DOODAD_CAP_PLACEMENTS));
            }
            assertEquals(1, f.files.opens(MODEL));
            assertEquals(0, f.files.opens(other));
        }
    }

    @Test
    void cancellationDrainsAndClosesEveryDoodadReadAheadBeforeThePreparationReturns() {
        var files = new SlowGroups();
        var token = new Cancellation();
        files.onGroupOpen =
                opened -> {
                    if (opened == 2) {
                        token.cancel();
                    }
                };
        try (var f = new Fixture(List.of(), files)) {
            var doodads = distinctModels(f, files, 12);
            f.files.sources.put(4L, obj0(List.of(), doodads));
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
            try (var set = f.run()) {
                assertEquals(12, set.count(WorldExteriorObjects.Count.DOODAD_DRAWN));
            }
        }
    }

    @Test
    @SuppressWarnings("try")
    void anImageTwoModelsNameIsReadOnceWhenTheEarlierModelSkipsIt() throws Exception {
        // Models a and b both name image x. a's SKIN is slow, so b names x first; x is read in
        // a's place. a then skips x (as a model capped after its read would), and taking b passes
        // a's place: x moves to b's place instead of being dropped, and b takes it alone.
        long a = 20_000, b = 20_001, skinA = 30_000, skinB = 30_001, x = 40_000;
        byte[] serial = null;
        for (boolean ahead : new boolean[] {false, true}) {
            var files = new SlowGroups();
            files.slow = id -> id == skinA || id == x;
            files.delay = 60;
            // skinA opens first; x, second, only once both chains have named it.
            var xOpened = new java.util.concurrent.CountDownLatch(1);
            files.onGroupOpen =
                    opened -> {
                        if (opened == 2) {
                            xOpened.countDown();
                        }
                    };
            try (var f = new Fixture(List.of(), files)) {
                f.files.sources.put(a, model(skinA));
                f.files.sources.put(b, model(skinB));
                f.files.sources.put(skinA, skin());
                f.files.sources.put(skinB, skin());
                f.files.sources.put(x, blp(4, 4, 1, 0x07e0));
                WorldStreamScopes.Prefetcher.enabled = ahead;
                WorldStageTimings.reset();
                WorldStageTimings.enable(true);
                var image = new byte[1][];
                try (var ignored =
                        f.engine.withRecordedTile(
                                f.scope,
                                TILE,
                                WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                                Cancellation.NONE,
                                admission -> {
                                    for (long[] chain : new long[][] {{a, skinA}, {b, skinB}}) {
                                        admission.prefetch(
                                                chain[0],
                                                WorldStreamScopes.DerivedKind.M2_MODEL,
                                                WorldStreamScopes.DerivedKind.M2_SKIN,
                                                (bytes, cancel) -> chain[1],
                                                WorldStreamScopes.DerivedKind.M2_IMAGE,
                                                (bytes, cancel) -> new long[] {x},
                                                10);
                                    }
                                    // Read ahead, x starts only once a's chain has named it,
                                    // and b's, named first, has been released after it.
                                    if (ahead) {
                                        await(xOpened);
                                    }
                                    for (var read :
                                            List.of(
                                                    new Object[] {
                                                        a, WorldStreamScopes.DerivedKind.M2_MODEL
                                                    },
                                                    new Object[] {
                                                        skinA, WorldStreamScopes.DerivedKind.M2_SKIN
                                                    },
                                                    new Object[] {
                                                        b, WorldStreamScopes.DerivedKind.M2_MODEL
                                                    },
                                                    new Object[] {
                                                        skinB, WorldStreamScopes.DerivedKind.M2_SKIN
                                                    })) {
                                        admission
                                                .readDerivedCandidate(
                                                        (long) read[0],
                                                        (WorldStreamScopes.DerivedKind) read[1])
                                                .close();
                                    }
                                    try (var input =
                                            admission.readDerivedCandidate(
                                                    x, WorldStreamScopes.DerivedKind.M2_IMAGE)) {
                                        image[0] = input.bytes().clone();
                                    }
                                    return () -> {};
                                })) {
                    var snapshot = WorldStageTimings.snapshot();
                    var waits = snapshot.get("source.prefetchWait.BASIC_EXTERIOR");
                    if (ahead) {
                        assertEquals(
                                5, waits.count(), "every read, x too, came from the read-ahead");
                        assertNull(
                                snapshot.get("source.read.BASIC_EXTERIOR"), "no read of its own");
                    }
                } finally {
                    WorldStreamScopes.Prefetcher.enabled = true;
                    WorldStageTimings.enable(false);
                    WorldStageTimings.reset();
                }
                assertEquals(1, f.files.opens(x), "x is read once");
                assertEquals(1, f.files.opens(b), "b's model is not dropped and read again");
                assertEquals(1, f.files.opens(skinB));
                if (!ahead) {
                    serial = image[0];
                } else {
                    assertArrayEquals(serial, image[0], "the same bytes as a serial read");
                }
            }
        }
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            // Bounded only so a broken read-ahead fails the test instead of hanging it.
            assertTrue(
                    latch.await(60, java.util.concurrent.TimeUnit.SECONDS),
                    "the awaited read never happened");
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @SuppressWarnings("try")
    void anImageALaterModelNamesBehindAnUnresolvedOneIsKeptWhenTheEarlierModelSkipsIt()
            throws Exception {
        // Models a, c and b in that order; a and b name image x, c names none. c's SKIN is slow,
        // so b's names wait behind it while x is read in a's place. a skips x and taking c passes
        // a's place: x moves to b's waiting place instead of being dropped, and b takes it. With
        // c skipped too (its reads capped), taking b itself passes a's place: x moves to b's own
        // place.
        long a = 20_000, c = 20_002, b = 20_001;
        long skinA = 30_000, skinC = 30_002, skinB = 30_001, x = 40_000;
        for (boolean skipC : new boolean[] {false, true}) {
            byte[] serial = null;
            for (boolean ahead : new boolean[] {false, true}) {
                var files = new SlowGroups();
                files.slow = id -> id == skinC;
                files.delay = 0;
                var release = new java.util.concurrent.CountDownLatch(1);
                var xHeld = new java.util.concurrent.CountDownLatch(1);
                // c's SKIN is held until the test has seen b's names held back behind it.
                files.onGroupOpen = opened -> await(release);
                WorldStreamScopes.Prefetcher.heldBackForTest =
                        id -> {
                            if (id == x) {
                                xHeld.countDown();
                            }
                        };
                try (var f = new Fixture(List.of(), files)) {
                    f.files.sources.put(a, model(skinA));
                    f.files.sources.put(c, model(skinC));
                    f.files.sources.put(b, model(skinB));
                    f.files.sources.put(skinA, skin());
                    f.files.sources.put(skinC, skin());
                    f.files.sources.put(skinB, skin());
                    f.files.sources.put(x, blp(4, 4, 1, 0x07e0));
                    WorldStreamScopes.Prefetcher.enabled = ahead;
                    if (!ahead) {
                        release.countDown();
                    }
                    var image = new byte[1][];
                    try (var ignored =
                            f.engine.withRecordedTile(
                                    f.scope,
                                    TILE,
                                    WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                                    Cancellation.NONE,
                                    admission -> {
                                        for (long[] chain :
                                                new long[][] {
                                                    {a, skinA, x}, {c, skinC, 0}, {b, skinB, x}
                                                }) {
                                            admission.prefetch(
                                                    chain[0],
                                                    WorldStreamScopes.DerivedKind.M2_MODEL,
                                                    WorldStreamScopes.DerivedKind.M2_SKIN,
                                                    (bytes, cancel) -> chain[1],
                                                    WorldStreamScopes.DerivedKind.M2_IMAGE,
                                                    (bytes, cancel) ->
                                                            chain[2] == 0
                                                                    ? new long[0]
                                                                    : new long[] {chain[2]},
                                                    10);
                                        }
                                        if (ahead) {
                                            await(xHeld);
                                        }
                                        read(admission, a, WorldStreamScopes.DerivedKind.M2_MODEL);
                                        read(
                                                admission,
                                                skinA,
                                                WorldStreamScopes.DerivedKind.M2_SKIN);
                                        // a skips x; taking c, or b when c is skipped, passes a's
                                        // place.
                                        if (!skipC) {
                                            read(
                                                    admission,
                                                    c,
                                                    WorldStreamScopes.DerivedKind.M2_MODEL);
                                        }
                                        release.countDown();
                                        if (!skipC) {
                                            read(
                                                    admission,
                                                    skinC,
                                                    WorldStreamScopes.DerivedKind.M2_SKIN);
                                        }
                                        read(admission, b, WorldStreamScopes.DerivedKind.M2_MODEL);
                                        read(
                                                admission,
                                                skinB,
                                                WorldStreamScopes.DerivedKind.M2_SKIN);
                                        try (var input =
                                                admission.readDerivedCandidate(
                                                        x,
                                                        WorldStreamScopes.DerivedKind.M2_IMAGE)) {
                                            image[0] = input.bytes().clone();
                                        }
                                        return () -> {};
                                    })) {
                        // Every read made; nothing further to check inside the tile.
                    } finally {
                        WorldStreamScopes.Prefetcher.enabled = true;
                        WorldStreamScopes.Prefetcher.heldBackForTest = null;
                        release.countDown();
                    }
                    assertEquals(1, f.files.opens(x), "x is read once");
                    assertEquals(1, f.files.opens(b), "b's model is read once");
                    assertEquals(1, f.files.opens(skinB));
                    if (!ahead) {
                        serial = image[0];
                    } else {
                        assertArrayEquals(serial, image[0], "the same bytes as a serial read");
                    }
                }
            }
        }
    }

    private static void read(
            WorldStreamScopes.TileAdmission admission,
            long id,
            WorldStreamScopes.DerivedKind kind) {
        admission.readDerivedCandidate(id, kind).close();
    }

    @Test
    @SuppressWarnings("try")
    void aFollowUpReadIsTakenFromTheReadAheadAndNeverFetchedForAWithdrawnRead() throws Exception {
        long model = 20_000, skin = 30_000;
        var files = new SlowGroups();
        files.slow = id -> id == model || id == skin;
        files.delay = 0;
        try (var f = new Fixture(List.of(), files)) {
            f.files.sources.put(model, model(skin));
            f.files.sources.put(skin, skin());
            // Consumed: the model's follow-up is read ahead and taken, never read again.
            WorldStageTimings.reset();
            WorldStageTimings.enable(true);
            try (var ignored =
                    f.engine.withRecordedTile(
                            f.scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                            Cancellation.NONE,
                            admission -> {
                                admission.prefetch(
                                        model,
                                        WorldStreamScopes.DerivedKind.M2_MODEL,
                                        WorldStreamScopes.DerivedKind.M2_SKIN,
                                        (bytes, cancel) -> skin);
                                admission
                                        .readDerivedCandidate(
                                                model, WorldStreamScopes.DerivedKind.M2_MODEL)
                                        .close();
                                admission
                                        .readDerivedCandidate(
                                                skin, WorldStreamScopes.DerivedKind.M2_SKIN)
                                        .close();
                                return () -> {};
                            })) {
                var snapshot = WorldStageTimings.snapshot();
                assertEquals(2, snapshot.get("source.prefetchWait.BASIC_EXTERIOR").count());
                assertNull(snapshot.get("source.read.BASIC_EXTERIOR"), "no read of its own");
            } finally {
                WorldStageTimings.enable(false);
                WorldStageTimings.reset();
            }
            assertEquals(1, f.files.opens(skin));
            // Withdrawn while its follow-up is being named: the follow-up is never fetched.
            try (var ignored =
                    f.engine.withRecordedTile(
                            f.scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                            Cancellation.NONE,
                            admission -> {
                                var naming = new java.util.concurrent.CountDownLatch(1);
                                admission.prefetch(
                                        model,
                                        WorldStreamScopes.DerivedKind.M2_MODEL,
                                        WorldStreamScopes.DerivedKind.M2_SKIN,
                                        (bytes, cancel) -> {
                                            naming.countDown();
                                            long cap = System.nanoTime() + 3_000_000_000L;
                                            while (!cancel.isCanceled()
                                                    && System.nanoTime() < cap) {
                                                Thread.onSpinWait();
                                            }
                                            return skin;
                                        });
                                try {
                                    naming.await();
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                                admission.cancelPrefetch();
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                                return () -> {};
                            })) {
                assertEquals(1, f.files.opens(skin), "the withdrawn read's follow-up is not read");
            }
            // A follow that fails leaves the next read to the consumer.
            try (var ignored =
                    f.engine.withRecordedTile(
                            f.scope,
                            TILE,
                            WorldStreamScopes.UseProfile.BASIC_EXTERIOR,
                            Cancellation.NONE,
                            admission -> {
                                admission.prefetch(
                                        model,
                                        WorldStreamScopes.DerivedKind.M2_MODEL,
                                        WorldStreamScopes.DerivedKind.M2_SKIN,
                                        (bytes, cancel) -> {
                                            throw new IllegalStateException("authored");
                                        });
                                admission
                                        .readDerivedCandidate(
                                                model, WorldStreamScopes.DerivedKind.M2_MODEL)
                                        .close();
                                admission
                                        .readDerivedCandidate(
                                                skin, WorldStreamScopes.DerivedKind.M2_SKIN)
                                        .close();
                                return () -> {};
                            })) {
                assertEquals(2, f.files.opens(skin), "read once, by the consumer");
            }
        }
    }
}
