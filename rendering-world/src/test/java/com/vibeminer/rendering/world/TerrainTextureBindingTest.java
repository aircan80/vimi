package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerrainTextureBindingTest {
    private static final TerrainTextureBinding.Sources SOURCES =
            new TerrainTextureBinding.Sources(1, "a".repeat(32), 2, "b".repeat(32));

    private record Fixture(TerrainScene.Tile geometry, TerrainTileAtlas atlas, float[] vertices) {}

    @Test
    void pointClampAxesAndBorrowedViewsUseDetachedMetadataWithoutPixelCopies() {
        var f = fixture(0, 0, false, false);
        var meta = qualify(f);
        var input = f.atlas.pixels();
        var binding = TerrainTextureBinding.borrow(meta, input);
        assertEquals(
                0xff7b791f,
                binding.sample(-1600.0 / 3 * ((377 + .5) / 512), -1600.0 / 3 * ((123 + .5) / 512)));
        assertEquals(0xff00001f, binding.sample(1e6, 1e6));
        assertEquals(0xffffff1f, binding.sample(-1e6, -1e6));
        assertEquals(0, input.position());
        assertThrows(ReadOnlyBufferException.class, () -> binding.pixels().put(0, (byte) 0));
        binding.pixels().position(7);
        assertEquals(0, binding.pixels().position());
        assertThrows(ReadOnlyBufferException.class, () -> meta.rectangleCoverage().put(0, 0));
        assertEquals(0, meta.tileOrdinal());
        assertEquals(7, meta.tileX());
        assertEquals(19, meta.tileY());
        assertTrue(meta.maxSourceUvErrorTexels() <= 1.0 / 32);
        assertThrows(IllegalArgumentException.class, () -> binding.sample(Double.NaN, 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.borrow(
                                meta, ByteBuffer.allocate(TerrainTextureBinding.PIXEL_BYTES)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.borrow(
                                meta, ByteBuffer.allocate(4).asReadOnlyBuffer()));
    }

    @Test
    void shuffledSourceOrdinalsBecomeHeaderRectangleCoverage() {
        var f = fixture(0, 0, true, true);
        var meta = qualify(f);
        assertTrue(meta.coveredHeaderRect(1, 0));
        assertFalse(meta.coveredHeaderRect(0, 0));
        assertEquals(2, meta.rectangleCoverage().get(0));
        var binding = TerrainTextureBinding.borrow(meta, f.atlas.pixels());
        assertEquals(0, binding.sample(-1, -1) >>> 24);
        assertEquals(255, binding.sample(-1, -40) >>> 24);
        var decoded =
                TerrainTextureBinding.fromSnapshot(
                        0,
                        f.geometry,
                        meta.nativeMaxX(),
                        meta.nativeMaxY(),
                        new long[] {2, 0, 0, 0},
                        SOURCES,
                        () -> false);
        assertEquals(meta.rootGeometrySha256(), decoded.rootGeometrySha256());
        TerrainTextureBinding.validatePixels(
                TerrainTextureBinding.borrow(decoded, f.atlas.pixels()), () -> false);
    }

    @Test
    void exactGeometryAndAllOriginalVertexProfileCannotBeReplacedByReferencedOnlyChecks() {
        var f = fixture(0, 0, false, false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.qualify(
                                0, f.geometry, f.atlas, "0".repeat(64), SOURCES, () -> false));
        var changed = f.vertices.clone();
        changed[changed.length - 3] += .1f;
        var tile = TerrainScene.Tile.copyOf(7, 19, changed, new int[] {0, 1, 2}, 256);
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                TerrainTextureBinding.qualify(
                                        0,
                                        tile,
                                        f.atlas,
                                        TerrainGeometryFingerprint.sha256(tile),
                                        SOURCES,
                                        () -> false));
        assertTrue(
                failure.getMessage().contains("1/32"),
                "unreferenced final vertex still participates");
        var incomplete =
                TerrainScene.Tile.copyOf(
                        7, 19, new float[] {0, 0, 0, 0, 1, 0, 1, 0, 0}, new int[] {0, 1, 2}, 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.fromSnapshot(
                                0,
                                incomplete,
                                0,
                                0,
                                new long[] {1, 0, 0, 0},
                                SOURCES,
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.fromSnapshot(
                                0,
                                f.geometry,
                                Float.NaN,
                                0,
                                new long[] {1, 0, 0, 0},
                                SOURCES,
                                () -> false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.fromSnapshot(
                                0, f.geometry, 0, 0, new long[4], SOURCES, () -> false));
    }

    @Test
    void borrowIsShapeOnlyWhileOneTimePixelValidationRejectsCoverageContradictions() {
        var f = fixture(0, 0, false, false);
        var meta = qualify(f);
        var bytes = ByteBuffer.allocate(TerrainTextureBinding.PIXEL_BYTES).asReadOnlyBuffer();
        var wrong = TerrainTextureBinding.borrow(meta, bytes);
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainTextureBinding.validatePixels(wrong, () -> false));
        var sparse = fixture(0, 0, true, true);
        var sparseMeta = qualify(sparse);
        var raw = new byte[TerrainTextureBinding.PIXEL_BYTES];
        sparse.atlas.pixels().get(raw);
        raw[0] = 1;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainTextureBinding.validatePixels(
                                TerrainTextureBinding.borrow(
                                        sparseMeta, ByteBuffer.wrap(raw).asReadOnlyBuffer()),
                                () -> false));
    }

    @Test
    void sourceAndRebasedFloatGuardsAreSeparateAndCancellationDoesNotConsumeBorrow() {
        var f = fixture(100000, -100000, false, false);
        var meta = qualify(f);
        var scene = new TerrainScene(List.of(f.geometry));
        assertTrue(
                TerrainTextureBinding.floatUvError(meta, f.geometry, scene.origin(), () -> false)
                        <= 1.0 / 32);
        var coarse = fixture(999400f, -999400f, false, false);
        assertThrows(IllegalArgumentException.class, () -> qualify(coarse));
        var polls = new AtomicInteger();
        assertThrows(
                CancellationException.class,
                () ->
                        TerrainTextureBinding.qualify(
                                0,
                                f.geometry,
                                f.atlas,
                                TerrainGeometryFingerprint.sha256(f.geometry),
                                SOURCES,
                                () -> polls.incrementAndGet() > 20));
        var binding = TerrainTextureBinding.borrow(meta, f.atlas.pixels());
        assertThrows(
                CancellationException.class,
                () -> TerrainTextureBinding.validatePixels(binding, () -> true));
        TerrainTextureBinding.validatePixels(binding, () -> false);
    }

    private static TerrainTextureBinding.Metadata qualify(Fixture f) {
        return TerrainTextureBinding.qualify(
                0,
                f.geometry,
                f.atlas,
                TerrainGeometryFingerprint.sha256(f.geometry),
                SOURCES,
                () -> false);
    }

    private static Fixture fixture(float maxX, float maxY, boolean shuffled, boolean sparse) {
        float[] vertices = new float[256 * 145 * 3];
        var mappings = new ArrayList<TerrainTileAtlas.Mapping>();
        int at = 0;
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            int header = (ordinal + (shuffled ? 1 : 0)) % 256, x = header % 16, y = header / 16;
            float anchorX = (float) (maxX - y * (WorldFormats.TILE_SIZE / 16)),
                    anchorY = (float) (maxY - x * (WorldFormats.TILE_SIZE / 16));
            mappings.add(new TerrainTileAtlas.Mapping(ordinal, x, y, anchorX, anchorY, 50));
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (anchorX - row * (WorldFormats.TILE_SIZE / 256));
                    vertices[at++] =
                            (float)
                                    (anchorY
                                            - col * (WorldFormats.TILE_SIZE / 128)
                                            - (row % 2) * (WorldFormats.TILE_SIZE / 256));
                    vertices[at++] = 50;
                }
            }
        }
        var key = new WorldFormats.Tile(7, 19, 1, 0, 0);
        byte[] pixels = new byte[TerrainTileAtlas.PIXEL_BYTES];
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                if (!sparse || x / 32 == 1 && y / 32 == 0) {
                    int p = (y * 512 + x) * 4;
                    pixels[p] = (byte) x;
                    pixels[p + 1] = (byte) y;
                    pixels[p + 2] = 31;
                    pixels[p + 3] = (byte) 255;
                }
            }
        }
        long[] coverage = sparse ? new long[] {1, 0, 0, 0} : new long[] {-1, -1, -1, -1};
        var atlas = new TerrainTileAtlas(key, pixels, coverage, mappings);
        return new Fixture(
                TerrainScene.Tile.copyOf(7, 19, vertices, new int[] {0, 1, 2}, 256),
                atlas,
                vertices);
    }
}
