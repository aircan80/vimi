package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GpuTerrainTextureAnnexTest {
    @TempDir Path scratch;
    private static final TerrainTextureBinding.Sources SOURCES =
            new TerrainTextureBinding.Sources(0xffff_ffffL, "ab".repeat(16), 2, "cd".repeat(16));

    @Test
    void boundGeometryHashesFinalBytesInSameReadWithoutChangingLegacyEncoding() throws Exception {
        var scene = scene(1, 10);
        Path ordinary = scratch.resolve("ordinary"), bound = scratch.resolve("bound");
        GpuTerrainSceneFile.write(ordinary, scene);
        var receipt = GpuTerrainSceneFile.writeBound(bound, scene, () -> false);
        byte[] bytes = Files.readAllBytes(bound);
        assertArrayEquals(Files.readAllBytes(ordinary), bytes);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(bytes);
        assertArrayEquals(expected, receipt.sha256());
        assertFalse(Arrays.equals(Arrays.copyOfRange(bytes, 32, 64), expected));
        assertEquals(bytes.length, receipt.length());
        receipt.sha256()[0] ^= 1;
        assertArrayEquals(expected, receipt.sha256());
        var decoded = GpuTerrainSceneFile.readBound(bound, () -> false);
        assertArrayEquals(expected, decoded.file().sha256());
        assertEquals(receipt.sha256Hex(), decoded.file().sha256Hex());
    }

    @Test
    void nineAtlasesRoundTripOrderedKeysUnsignedIdsCoverageAndIndependentReadonlyPixels()
            throws Exception {
        var scene = scene(9, 10);
        var bindings = bindings(scene);
        Path geometry = scratch.resolve("geometry"), annex = scratch.resolve("annex");
        var receipt = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        GpuTerrainTextureAnnex.write(annex, receipt, scene, bindings, () -> false);
        assertEquals(GpuTerrainTextureAnnex.MAX_VALID_BYTES, Files.size(annex));
        var decoded =
                GpuTerrainTextureAnnex.read(
                        annex, GpuTerrainSceneFile.readBound(geometry, () -> false), () -> false);
        assertEquals(9, decoded.bindings().size());
        for (int i = 0; i < 9; i++) {
            var actual = decoded.bindings().get(i);
            assertEquals(i, actual.metadata().tileOrdinal());
            assertEquals(SOURCES, actual.metadata().sources());
            assertEquals(0xff112233, actual.sample(-1, -1));
            assertEquals(0, bindings.get(i).pixels().position());
            assertThrows(ReadOnlyBufferException.class, () -> actual.pixels().put(0, (byte) 1));
        }
        assertThrows(UnsupportedOperationException.class, () -> decoded.bindings().clear());
    }

    @Test
    void sameShapeDifferentHeightCannotExchangeGeometryReceiptsOrAnnexes() throws Exception {
        var original = scene(1, 10);
        var changed = scene(1, 11);
        Path a = scratch.resolve("a"), b = scratch.resolve("b"), annex = scratch.resolve("annex");
        var receipt = GpuTerrainSceneFile.writeBound(a, original, () -> false);
        GpuTerrainSceneFile.writeBound(b, changed, () -> false);
        assertThrows(
                IOException.class,
                () ->
                        GpuTerrainTextureAnnex.write(
                                annex, receipt, changed, bindings(changed), () -> false));
        assertFalse(Files.exists(annex));
        GpuTerrainTextureAnnex.write(annex, receipt, original, bindings(original), () -> false);
        assertThrows(
                IOException.class,
                () ->
                        GpuTerrainTextureAnnex.read(
                                annex, GpuTerrainSceneFile.readBound(b, () -> false), () -> false));
    }

    @Test
    void authenticatedBadDeclarationsAndPixelsAreRejectedBeyondChecksumValidation()
            throws Exception {
        var scene = scene(2, 10);
        Path geometry = scratch.resolve("geometry"), annex = scratch.resolve("annex");
        var receipt = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        GpuTerrainTextureAnnex.write(annex, receipt, scene, bindings(scene), () -> false);
        var decoded = GpuTerrainSceneFile.readBound(geometry, () -> false);
        byte[] clean = Files.readAllBytes(annex);
        // Header profile/reserved fields; descriptor
        // tile/flags/origin/rational/coverage/offset/IDs;
        // second ordinal duplicate. Every mutation is re-signed to test the schema boundary.
        int[] offsets = {
            8, 12, 88, 92, 96, 100, 104, 108, 112, 132, 140, 144, 152, 160, 192, 200, 240, 248, 256
        };
        for (int offset : offsets) {
            byte[] bad = clean.clone();
            bad[offset] ^= 1;
            if (offset == 144) {
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(144, 0x7fc00000);
            }
            if (offset == 240) {
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(240, 0);
            }
            if (offset == 256) {
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(256, 0);
            }
            resign(bad);
            Path file = scratch.resolve("bad-" + offset);
            Files.write(file, bad);
            assertThrows(
                    IOException.class,
                    () -> GpuTerrainTextureAnnex.read(file, decoded, () -> false),
                    "authenticated mutation at " + offset);
        }
        byte[] badAlpha = clean.clone();
        badAlpha[128 + 2 * 128 + 3] = 127;
        resign(badAlpha);
        Path invalid = scratch.resolve("alpha");
        Files.write(invalid, badAlpha);
        assertThrows(
                IOException.class,
                () -> GpuTerrainTextureAnnex.read(invalid, decoded, () -> false));
        byte[] badChecksum = clean.clone();
        badChecksum[badChecksum.length - 1] ^= 1;
        Files.write(invalid, badChecksum);
        assertThrows(
                IOException.class,
                () -> GpuTerrainTextureAnnex.read(invalid, decoded, () -> false));
        Files.write(invalid, Arrays.copyOf(clean, clean.length - 1));
        assertThrows(
                IOException.class,
                () -> GpuTerrainTextureAnnex.read(invalid, decoded, () -> false));
    }

    @Test
    void cancellationDeletesOnlyOwnedPartialWritesAndReadCanBeRetried() throws Exception {
        var scene = scene(1, 10);
        Path geometry = scratch.resolve("geometry"), annex = scratch.resolve("annex");
        var receipt = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        var bindings = bindings(scene);
        assertThrows(
                CancellationException.class,
                () ->
                        GpuTerrainTextureAnnex.write(
                                annex, receipt, scene, bindings, () -> Files.exists(annex)));
        assertFalse(Files.exists(annex));
        GpuTerrainTextureAnnex.write(annex, receipt, scene, bindings, () -> false);
        byte[] saved = Files.readAllBytes(annex);
        assertThrows(
                IOException.class,
                () -> GpuTerrainTextureAnnex.write(annex, receipt, scene, bindings, () -> false));
        assertArrayEquals(saved, Files.readAllBytes(annex));
        var decoded = GpuTerrainSceneFile.readBound(geometry, () -> false);
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        GpuTerrainTextureAnnex.read(
                                annex, decoded, () -> polls.incrementAndGet() > 300));
        assertEquals(1, GpuTerrainTextureAnnex.read(annex, decoded, () -> false).bindings().size());
        Path link = scratch.resolve("link");
        Files.createSymbolicLink(link, annex);
        assertThrows(
                IOException.class, () -> GpuTerrainTextureAnnex.read(link, decoded, () -> false));
    }

    @Test
    void partialCoverageStaysTransparentAndRejectsHiddenRgbOrOpaqueUncoveredPixels()
            throws Exception {
        var scene = scene(2, 10);
        var meta =
                TerrainTextureBinding.fromSnapshot(
                        1,
                        scene.tiles().get(1),
                        0,
                        0,
                        new long[] {2, 0, 0, 0},
                        SOURCES,
                        () -> false);
        byte[] pixels = new byte[TerrainTextureBinding.PIXEL_BYTES];
        for (int y = 0; y < 32; y++) {
            for (int x = 32; x < 64; x++) {
                int p = (y * 512 + x) * 4;
                pixels[p] = 71;
                pixels[p + 3] = (byte) 255;
            }
        }
        var binding =
                TerrainTextureBinding.borrow(meta, ByteBuffer.wrap(pixels).asReadOnlyBuffer());
        Path geometry = scratch.resolve("geometry"), annex = scratch.resolve("annex");
        var receipt = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        GpuTerrainTextureAnnex.write(annex, receipt, scene, List.of(binding), () -> false);
        var decodedGeometry = GpuTerrainSceneFile.readBound(geometry, () -> false);
        var restored =
                GpuTerrainTextureAnnex.read(annex, decodedGeometry, () -> false)
                        .bindings()
                        .getFirst();
        assertEquals(1, restored.metadata().tileOrdinal());
        assertEquals(0, restored.sample(-1, -1));
        assertEquals(0xff470000, restored.sample(-1, -40));
        byte[] clean = Files.readAllBytes(annex);
        // Uncovered header rectangle: zero alpha does not permit concealed nonzero RGB.
        for (int channel : new int[] {0, 3}) {
            byte[] bad = clean.clone();
            bad[256 + channel] = 1;
            resign(bad);
            Path file = scratch.resolve("uncovered-" + channel);
            Files.write(file, bad);
            assertThrows(
                    IOException.class,
                    () -> GpuTerrainTextureAnnex.read(file, decodedGeometry, () -> false));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuTerrainTextureAnnex.write(
                                scratch.resolve("duplicates"),
                                receipt,
                                scene,
                                List.of(binding, binding),
                                () -> false));
    }

    @Test
    void boundGeometryCancellationAndAnnexLengthCapsDoNotPublishPartialResults() throws Exception {
        var scene = scene(1, 10);
        Path geometry = scratch.resolve("geometry");
        assertThrows(
                CancellationException.class,
                () ->
                        GpuTerrainSceneFile.writeBound(
                                geometry, scene, () -> Files.exists(geometry)));
        assertFalse(Files.exists(geometry));
        GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        var decoded = GpuTerrainSceneFile.readBound(geometry, () -> false);
        Path oversized = scratch.resolve("oversized");
        try (var file =
                java.nio.channels.FileChannel.open(
                        oversized,
                        java.nio.file.StandardOpenOption.CREATE_NEW,
                        java.nio.file.StandardOpenOption.WRITE)) {
            file.position(GpuTerrainTextureAnnex.MAX_BYTES);
            file.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertThrows(
                IOException.class,
                () -> GpuTerrainTextureAnnex.read(oversized, decoded, () -> false));
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(
                CancellationException.class,
                () -> GpuTerrainSceneFile.readBound(geometry, () -> polls.incrementAndGet() > 5));
        assertEquals(
                1, GpuTerrainSceneFile.readBound(geometry, () -> false).scene().tiles().size());
    }

    private static void resign(byte[] bytes) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        sha.update(bytes, 0, 56);
        sha.update(bytes, 88, bytes.length - 88);
        System.arraycopy(sha.digest(), 0, bytes, 56, 32);
    }

    private static List<TerrainTextureBinding> bindings(TerrainScene scene) {
        var result = new ArrayList<TerrainTextureBinding>();
        for (int i = 0; i < scene.tiles().size(); i++) {
            var meta =
                    TerrainTextureBinding.fromSnapshot(
                            i,
                            scene.tiles().get(i),
                            0,
                            0,
                            new long[] {-1, -1, -1, -1},
                            SOURCES,
                            () -> false);
            byte[] pixels = new byte[TerrainTextureBinding.PIXEL_BYTES];
            for (int p = 0; p < pixels.length; p += 4) {
                pixels[p] = 17;
                pixels[p + 1] = 34;
                pixels[p + 2] = 51;
                pixels[p + 3] = (byte) 255;
            }
            result.add(
                    TerrainTextureBinding.borrow(meta, ByteBuffer.wrap(pixels).asReadOnlyBuffer()));
        }
        return result;
    }

    private static TerrainScene scene(int count, float height) {
        float[] vertices = new float[256 * 145 * 3];
        int at = 0;
        for (int chunk = 0; chunk < 256; chunk++) {
            double ax = -(chunk / 16) * (1600.0 / 3 / 16), ay = -(chunk % 16) * (1600.0 / 3 / 16);
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (ax - row * (1600.0 / 3 / 256));
                    vertices[at++] = (float) (ay - (col * 2 + row % 2) * (1600.0 / 3 / 256));
                    vertices[at++] = height;
                }
            }
        }
        var tiles = new ArrayList<TerrainScene.Tile>();
        for (int i = 0; i < count; i++) {
            tiles.add(TerrainScene.Tile.copyOf(i, 0, vertices, new int[] {0, 1, 2}, 256));
        }
        return new TerrainScene(tiles);
    }
}
