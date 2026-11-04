package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.BasicWmoData;
import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainGeometryFingerprint;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuWorldWmoAnnexTest {
    @TempDir Path scratch;

    private static TerrainScene scene(int count, boolean changeOther) {
        var tiles = new ArrayList<TerrainScene.Tile>();
        for (int i = 0; i < count; i++) {
            float z = 0;
            float secondX = i * 10 + (changeOther && i == 1 ? 3 : 4);
            tiles.add(
                    TerrainScene.Tile.copyOf(
                            7 + i,
                            19,
                            new float[] {i * 10, 0, z, secondX, 0, 0, i * 10, 6, 2},
                            new int[] {0, 1, 2},
                            1));
        }
        return new TerrainScene(tiles);
    }

    private static final class Fixture implements BasicWmoData.View {
        final BasicWmoData.Metadata metadata;
        final float[] p, n, uv;
        final int[] indices;
        final List<BasicWmoData.Batch> batches = new ArrayList<>();
        final List<BasicWmoData.Source> sources = new ArrayList<>();
        final List<BasicWmoData.Image> images = new ArrayList<>();
        final int materialCount;

        Fixture(TerrainScene scene, boolean maximum) {
            this(
                    scene,
                    maximum,
                    new BasicWmoData.Placement(100, 20, 300, -0f, 90, 0, 2048, 12, 0, 0));
        }

        Fixture(TerrainScene scene, boolean maximum, BasicWmoData.Placement placement) {
            metadata =
                    new BasicWmoData.Metadata(
                            0xffffffffL,
                            0,
                            7,
                            19,
                            2,
                            0xffffffffL,
                            -99,
                            1,
                            "ab".repeat(32),
                            TerrainGeometryFingerprint.sha256(scene.tiles().getFirst()),
                            0xff0b0b0bL,
                            scene.origin(),
                            placement);
            int vertices = maximum ? 512 : 3, count = maximum ? 1536 : 3;
            p = new float[vertices * 3];
            n = new float[vertices * 3];
            uv = new float[vertices * 2];
            indices = new int[count];
            for (int i = 0; i < vertices; i++) {
                p[i * 3] = i % 3;
                p[i * 3 + 1] = i % 2;
                n[i * 3 + 2] = 1;
                uv[i * 2] = i % 2;
            }
            for (int i = 0; i < count; i++) {
                indices[i] = i % 3;
            }
            materialCount = maximum ? 6 : 1;
            int first = 0;
            for (int i = 0; i < materialCount; i++) {
                int span = maximum ? (i < 5 ? 255 : 261) : 3;
                batches.add(new BasicWmoData.Batch(first, span, i));
                first += span;
                int side = maximum ? 256 : 4;
                byte[] rgba = new byte[side * side * 4];
                for (int at = 0; at < rgba.length; at += 4) {
                    rgba[at] = (byte) (11 + i);
                    rgba[at + 1] = 33;
                    rgba[at + 2] = 77;
                    rgba[at + 3] = (byte) 255;
                }
                images.add(new BasicWmoData.Image(10 + i, side, side, ByteBuffer.wrap(rgba)));
            }
            int[] limits = {1 << 20, 2 << 20, 8 << 20, 1024, 32768};
            long[] ids = {1, 2, 4, 5, 6};
            for (int i = 0; i < 5; i++) {
                sources.add(
                        new BasicWmoData.Source(
                                ids[i], i, "cd".repeat(16), 100, i == 3 ? null : 100L, limits[i]));
            }
            for (var image : images) {
                sources.add(
                        new BasicWmoData.Source(image.id(), 5, "ef".repeat(16), 200, null, 65536));
            }
        }

        public BasicWmoData.Metadata metadata() {
            return metadata;
        }

        public FloatBuffer positions() {
            return FloatBuffer.wrap(p).asReadOnlyBuffer();
        }

        public FloatBuffer normals() {
            return FloatBuffer.wrap(n).asReadOnlyBuffer();
        }

        public FloatBuffer uv() {
            return FloatBuffer.wrap(uv).asReadOnlyBuffer();
        }

        public IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        public List<BasicWmoData.Batch> batches() {
            return batches;
        }

        public List<BasicWmoData.Source> sources() {
            return sources;
        }

        public int materialCount() {
            return materialCount;
        }

        public long materialWord(int material, int word) {
            return word == 3 ? 10 + material : word == 4 ? 0xff000000L : word == 8 ? 10 : 0;
        }

        public int imageCount() {
            return images.size();
        }

        public BasicWmoData.Image image(int index) {
            return images.get(index);
        }
    }

    private static void rehash(byte[] bytes) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        sha.update(bytes, 0, 64);
        sha.update(bytes, 96, bytes.length - 96);
        System.arraycopy(sha.digest(), 0, bytes, 64, 32);
    }

    @Test
    void maximumRoundTripHasExactBoundedLayoutAndReadonlyIndependentData() throws Exception {
        var scene = scene(9, false);
        var fixture = new Fixture(scene, true);
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        GpuWorldWmoAnnex.write(packet, base, scene, fixture, () -> false);
        assertEquals(1594400, Files.size(packet));
        assertTrue(Files.size(packet) < GpuWorldWmoAnnex.MAX_BYTES);
        var decoded =
                GpuWorldWmoAnnex.read(
                        packet, GpuTerrainSceneFile.readBound(basePath, () -> false), () -> false);
        assertEquals(fixture.metadata, decoded.metadata());
        assertEquals(512 * 3, decoded.positions().remaining());
        assertEquals(1536, decoded.indices().remaining());
        assertEquals(6, decoded.batches().size());
        assertEquals(11, decoded.sources().size());
        assertEquals(null, decoded.sources().get(3).declaredLength());
        assertEquals(0xff000000L, decoded.materialWord(0, 4));
        assertEquals(10, decoded.materialWord(0, 8));
        assertEquals(
                Float.floatToRawIntBits(-0f),
                Float.floatToRawIntBits(decoded.metadata().placement().rx()));
        for (int i = 0; i < 6; i++) {
            assertEquals(11 + i, decoded.image(i).rgba().get(0));
        }
        assertThrows(ReadOnlyBufferException.class, () -> decoded.positions().put(0, 999));
        assertThrows(ReadOnlyBufferException.class, () -> decoded.image(0).rgba().put(0, (byte) 1));
        assertThrows(UnsupportedOperationException.class, () -> decoded.sources().clear());
        fixture.p[0] = 999;
        assertEquals(0, decoded.positions().get(0));
        assertEquals(
                22528,
                decoded.positions().remaining() * 4
                        + decoded.normals().remaining() * 4
                        + decoded.uv().remaining() * 4
                        + decoded.indices().remaining() * 4);
    }

    @Test
    void sameSelectedTileCannotAuthorizeAnotherWholeNineTileBase() throws Exception {
        var a = scene(9, false);
        var b = scene(9, true);
        var fixture = new Fixture(a, false);
        var pa = scratch.resolve("a");
        var pb = scratch.resolve("b");
        var annex = scratch.resolve("annex");
        var da = GpuTerrainSceneFile.writeBound(pa, a, () -> false);
        GpuTerrainSceneFile.writeBound(pb, b, () -> false);
        assertEquals(
                TerrainGeometryFingerprint.sha256(a.tiles().getFirst()),
                TerrainGeometryFingerprint.sha256(b.tiles().getFirst()));
        GpuWorldWmoAnnex.write(annex, da, a, fixture, () -> false);
        assertThrows(
                IOException.class,
                () ->
                        GpuWorldWmoAnnex.read(
                                annex,
                                GpuTerrainSceneFile.readBound(pb, () -> false),
                                () -> false));
        assertThrows(
                IOException.class,
                () ->
                        GpuWorldWmoAnnex.write(
                                scratch.resolve("wrong"), da, b, fixture, () -> false));
        assertFalse(Files.exists(scratch.resolve("wrong")));
    }

    @Test
    void canonicalHeaderTamperingFailsEvenWithRecomputedWholeChecksum() throws Exception {
        var scene = scene(1, false);
        var f = new Fixture(scene, false);
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false);
        var decodedBase = GpuTerrainSceneFile.readBound(basePath, () -> false);
        byte[] original = Files.readAllBytes(packet);
        int[] fields = {
            0, 8, 12, 16, 24, 32, 128, 160, 164, 168, 172, 176, 180, 192, 200, 208, 212, 216, 220,
            224, 228, 236, 240, 276, 288, 292, 296, 300, 304, 312, 320, 328, 336, 344, 352, 360,
            368, 376, 384, 392, 400, 408, 412, 416, 511
        };
        // Descriptive owner/generation/ordinal changes remain valid if in domain; force their
        // invalid zero/bound.
        for (int field : fields) {
            byte[] bytes = original.clone();
            var h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (field == 180) {
                h.putInt(field, 4096);
            } else if (field == 192 || field == 200) {
                h.putLong(field, 0);
            } else if (field == 276) {
                h.putFloat(field, 1);
            } else {
                bytes[field] ^= 1;
            }
            rehash(bytes);
            Path target = scratch.resolve("mutated-" + field);
            Files.write(target, bytes);
            assertThrows(
                    IOException.class,
                    () -> GpuWorldWmoAnnex.read(target, decodedBase, () -> false),
                    "field " + field);
        }
    }

    @Test
    void reservedSourceMaterialBatchImageMeshAndPixelSemanticsAreCheckedAfterRehash()
            throws Exception {
        var scene = scene(1, false);
        var f = new Fixture(scene, false);
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false);
        var decodedBase = GpuTerrainSceneFile.readBound(basePath, () -> false);
        byte[] original = Files.readAllBytes(packet);
        var h = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int sources = (int) h.getLong(352),
                materials = (int) h.getLong(360),
                batches = (int) h.getLong(368),
                images = (int) h.getLong(376),
                vertices = (int) h.getLong(384),
                indices = (int) h.getLong(392),
                pixels = (int) h.getLong(400);
        int[] fields = {
            sources,
            sources + 4,
            sources + 32,
            sources + 40,
            sources + 44,
            materials + 4,
            materials + 12,
            materials + 24,
            materials + 44,
            batches,
            batches + 4,
            batches + 8,
            batches + 12,
            images,
            images + 4,
            images + 12,
            images + 16,
            images + 24,
            images + 56,
            vertices,
            vertices + 12,
            vertices + 24,
            indices,
            pixels,
            pixels + 3
        };
        for (int field : fields) {
            byte[] bytes = original.clone();
            var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (field == vertices || field == vertices + 12 || field == vertices + 24) {
                b.putInt(field, 0x7fc00000);
            } else if (field == sources) {
                b.putInt(field, 0);
            } else if (field == indices) {
                b.putShort(field, (short) 512);
            } else {
                bytes[field] ^= 1;
            }
            rehash(bytes);
            Path target = scratch.resolve("body-mutated-" + field);
            Files.write(target, bytes);
            assertThrows(
                    IOException.class,
                    () -> GpuWorldWmoAnnex.read(target, decodedBase, () -> false),
                    "field " + field);
        }
    }

    @Test
    void truncationTrailingBytesOversizedCountsAndChecksumRejectBeforeReturningData()
            throws Exception {
        var scene = scene(1, false);
        var f = new Fixture(scene, false);
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false);
        var decodedBase = GpuTerrainSceneFile.readBound(basePath, () -> false);
        byte[] original = Files.readAllBytes(packet);
        for (int size :
                new int[] {0, 511, original.length - 1, original.length + 1, (2 << 20) + 1}) {
            Path target = scratch.resolve("size-" + size);
            Files.write(target, Arrays.copyOf(original, size));
            assertThrows(
                    IOException.class,
                    () -> GpuWorldWmoAnnex.read(target, decodedBase, () -> false));
        }
        byte[] bytes = original.clone();
        bytes[64] ^= 1;
        Path wrongHash = scratch.resolve("hash");
        Files.write(wrongHash, bytes);
        assertThrows(
                IOException.class,
                () -> GpuWorldWmoAnnex.read(wrongHash, decodedBase, () -> false));
        for (int offset : new int[] {208, 212, 216, 220, 224, 228}) {
            bytes = original.clone();
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, Integer.MAX_VALUE);
            rehash(bytes);
            Path target = scratch.resolve("count-" + offset);
            Files.write(target, bytes);
            assertThrows(
                    IOException.class,
                    () -> GpuWorldWmoAnnex.read(target, decodedBase, () -> false));
        }
    }

    @Test
    void canceledPartialWriteDeletesOnlyItsOwnedNewFileAndReadCancellationIsExplicit()
            throws Exception {
        var scene = scene(1, false);
        var f = new Fixture(scene, true);
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        assertThrows(
                CancellationException.class,
                () -> GpuWorldWmoAnnex.write(packet, base, scene, f, () -> Files.exists(packet)));
        assertFalse(Files.exists(packet));
        GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false);
        byte[] original = Files.readAllBytes(packet);
        assertThrows(
                IOException.class,
                () -> GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false));
        assertArrayEquals(original, Files.readAllBytes(packet));
        var calls = new AtomicInteger();
        var decodedBase = GpuTerrainSceneFile.readBound(basePath, () -> false);
        assertThrows(
                CancellationException.class,
                () ->
                        GpuWorldWmoAnnex.read(
                                packet, decodedBase, () -> calls.incrementAndGet() > 5));
    }

    @Test
    void sharedOriginLiteralMatrixMatchesIndependentWorldAndEyeOracles() {
        var scene = scene(9, false);
        var f = new Fixture(scene, false);
        var m = GpuWorldWmoAnnex.modelToOrigin(f.metadata);
        float[] matrix = new float[16];
        m.get(matrix);
        double[] point = {1, 2, 3};
        double[] relative = new double[3];
        for (int row = 0; row < 3; row++) {
            relative[row] =
                    matrix[row] * point[0]
                            + matrix[row + 4] * point[1]
                            + matrix[row + 8] * point[2]
                            + matrix[row + 12];
        }
        double c = 51200.0 / 3.0;
        double[] world = {c - 300 + 4, c - 100 - 2, 26};
        assertEquals(world[0] - scene.origin().x(), relative[0], .001);
        assertEquals(world[1] - scene.origin().y(), relative[1], .001);
        assertEquals(world[2] - scene.origin().z(), relative[2], .001);
        var camera = new TerrainCamera(c - 300 - 100, c - 100 - 80, 40, .3, .2);
        var eye = camera.modelToEye(scene.origin());
        double[] transformed = new double[3];
        for (int row = 0; row < 3; row++) {
            transformed[row] =
                    eye.get(row) * relative[0]
                            + eye.get(row + 4) * relative[1]
                            + eye.get(row + 8) * relative[2]
                            + eye.get(row + 12);
        }
        var expected = camera.eye(world[0], world[1], world[2]);
        assertEquals(expected.right(), transformed[0], .003);
        assertEquals(expected.up(), transformed[1], .003);
        assertEquals(-expected.depth(), transformed[2], .003);
    }

    @Test
    void zeroInterpolatedNormalIsAmbientOnlyAndFragmentFogHasPinnedDepthRule() {
        assertEquals(1.0 / 3, BasicWmoData.intensity(0, 0, 0), 0);
        assertEquals(1.0 / 3, BasicWmoData.intensity(1e-7, 0, 0), 0);
        assertEquals(1.0 / 3, BasicWmoData.intensity(-1e-6, 0, 0), 0);
        assertTrue(BasicWmoData.intensity(-1.01e-6, 0, 0) > 1.0 / 3);
        assertEquals(1, BasicWmoData.intensity(-1, -1, 2), 1e-15);
        assertFalse(BasicWmoData.fragmentVisible(.499999));
        assertTrue(BasicWmoData.fragmentVisible(.5));
        assertTrue(BasicWmoData.fragmentVisible(2200));
        assertFalse(BasicWmoData.fragmentVisible(Math.nextUp(2200.0)));
        assertFalse(BasicWmoData.fragmentVisible(Double.NaN));
        assertEquals(85, BasicWmoData.shadeByte(255, 153, 1.0 / 3, 600));
        assertEquals(119, BasicWmoData.shadeByte(255, 153, 1.0 / 3, 1400));
        assertEquals(142, BasicWmoData.shadeByte(255, 153, 1.0 / 3, 1960));
        assertEquals(0, BasicWmoData.fog(.5), 0);
        assertEquals(0, BasicWmoData.fog(600), 0);
        assertEquals(.5, BasicWmoData.fog(1400), 0);
        assertEquals(.85, BasicWmoData.fog(1960), 0);
        assertEquals(.85, BasicWmoData.fog(2200), 0);
    }

    @Test
    void cardinalUniformMatricesUseOneSharedOriginAndExplicitPositiveScales() {
        var scene = scene(9, false);
        var f = new Fixture(scene, false);
        var a = f.metadata;
        int[] headings = {0, 90, 180, -90};
        double[][] cosSin = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int i = 0; i < headings.length; i++) {
            for (int scale : new int[] {512, 2048, 65535}) {
                var p =
                        new BasicWmoData.Placement(
                                100, 20, 300, -0f, headings[i], -0f, scale, 12, 0, 0);
                var meta =
                        new BasicWmoData.Metadata(
                                a.mapId(),
                                a.tileOrdinal(),
                                a.tileX(),
                                a.tileY(),
                                a.modfOrdinal(),
                                a.uniqueId(),
                                a.owner(),
                                a.generation(),
                                a.scopeDigest(),
                                a.geometryDigest(),
                                a.ambientColor(),
                                a.origin(),
                                p);
                var m = GpuWorldWmoAnnex.modelToOrigin(meta);
                double co = cosSin[i][0], si = cosSin[i][1], s = scale / 1024.0;
                double expectedX = 51200.0 / 3.0 - 300 + s * (-co + si * 2) - scene.origin().x();
                double expectedY = 51200.0 / 3.0 - 100 + s * (-si - co * 2) - scene.origin().y();
                assertEquals(
                        expectedX,
                        (double) m.get(0)
                                + (double) m.get(4) * 2
                                + (double) m.get(8) * 3
                                + m.get(12),
                        .001);
                assertEquals(
                        expectedY,
                        (double) m.get(1)
                                + (double) m.get(5) * 2
                                + (double) m.get(9) * 3
                                + m.get(13),
                        .001);
                assertEquals(
                        20 + s * 3 - scene.origin().z(),
                        m.get(2) + m.get(6) * 2 + m.get(10) * 3 + m.get(14),
                        .00001);
            }
        }
    }

    @Test
    void doubleCancellationCannotHideOutOfBoundsFloatModelCoordinates() throws Exception {
        var scene = scene(1, false);
        var placement =
                new BasicWmoData.Placement(
                        -77596276555776f, 0, -71291759493120f, 0, 5, 0, 1024, 12, 0, 0);
        var f = new Fixture(scene, false, placement);
        float x = 77783434788864f, y = 71087513665536f;
        for (int v = 0; v < 3; v++) {
            f.p[v * 3] = x;
            f.p[v * 3 + 1] = y;
        }
        double qx = placement.tx() + (-placement.cosine() * x + placement.sine() * y);
        double qy = placement.ty() + (-placement.sine() * x - placement.cosine() * y);
        assertTrue(
                Math.abs(qx) < TerrainScene.MAX_COORDINATE
                        && Math.abs(qy) < TerrainScene.MAX_COORDINATE);
        var model = GpuWorldWmoAnnex.modelToOrigin(f.metadata);
        float actual = model.get(0) * x + model.get(4) * y + model.get(12);
        assertTrue(
                Math.abs(actual) > 2 * TerrainScene.MAX_COORDINATE,
                "Float multiplication has lost the double cancellation");
        var basePath = scratch.resolve("base");
        var packet = scratch.resolve("packet");
        var base = GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        assertThrows(
                IOException.class,
                () -> GpuWorldWmoAnnex.write(packet, base, scene, f, () -> false));
        assertFalse(Files.exists(packet));
    }
}
