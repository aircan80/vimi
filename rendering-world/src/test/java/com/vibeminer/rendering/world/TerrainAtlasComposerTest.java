package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldTextureFormats;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TerrainAtlasComposerTest {
    private static final WorldFormats.Tile TILE =
            new WorldFormats.Tile(7, 19, 100, 0, 0, 0, 0, 200, 0, 0);
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static byte[] ints(int... values) {
        var b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) {
            b.putInt(value);
        }
        return b.array();
    }

    private static byte[] part(String tag, byte[] bytes) {
        var out = new ByteArrayOutputStream();
        int code = 0;
        for (char c : tag.toCharArray()) {
            code = (code << 8) | c;
        }
        out.writeBytes(ints(code, bytes.length));
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static WorldTextureFormats.TextureData textures(int[] weights, boolean maskVariation) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(part("MVER", ints(18)));
        out.writeBytes(part("MAMP", ints(0)));
        int[] ids = new int[weights.length + 1];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = i + 1;
        }
        out.writeBytes(part("MDID", ints(ids)));
        out.writeBytes(part("MHID", ints(new int[ids.length])));
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            var layers = ByteBuffer.allocate(ids.length * 16).order(ByteOrder.LITTLE_ENDIAN);
            var masks = new byte[weights.length * 4096];
            for (int layer = 0; layer < ids.length; layer++) {
                layers.putInt(layer)
                        .putInt(layer == 0 ? 0 : 0x100)
                        .putInt(layer == 0 ? 0 : (layer - 1) * 4096)
                        .putInt(0);
                if (layer > 0) {
                    for (int pixel = 0; pixel < 4096; pixel++) {
                        masks[(layer - 1) * 4096 + pixel] =
                                (byte)
                                        (maskVariation
                                                ? (pixel / 64 % 2 == 1 && pixel % 64 % 2 == 1
                                                        ? 0
                                                        : weights[layer - 1])
                                                : weights[layer - 1]);
                    }
                }
            }
            var chunk = new ByteArrayOutputStream();
            chunk.writeBytes(part("MCLY", layers.array()));
            chunk.writeBytes(part("MCAL", masks));
            out.writeBytes(part("MCNK", chunk.toByteArray()));
        }
        return WorldTextureFormats.read(out.toByteArray(), () -> false);
    }

    private static TerrainGrid grid(byte[] firstColors, boolean holes, boolean reverseX) {
        var chunks = new ArrayList<TerrainGrid.Chunk>();
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            int x = reverseX ? 15 - ordinal % 16 : ordinal % 16, y = ordinal / 16;
            byte[] colors = ordinal == 0 ? firstColors : new byte[0];
            var state =
                    colors.length == 0
                            ? TerrainGrid.ColorState.ABSENT
                            : TerrainGrid.ColorState.BGRA8;
            chunks.add(
                    new TerrainGrid.Chunk(
                            ordinal,
                            x,
                            y,
                            0,
                            (float) (999000 - y * WorldFormats.TILE_SIZE / 16),
                            (float) (-999000 - x * WorldFormats.TILE_SIZE / 16),
                            12,
                            new float[145],
                            holes ? -1L : 0,
                            state,
                            colors));
        }
        return new TerrainGrid(chunks);
    }

    private static TerrainAtlasComposer.DiffuseMip mip(long id, int r, int g, int b) {
        var bytes = new byte[64];
        for (int pixel = 0; pixel < 16; pixel++) {
            bytes[pixel * 4] = (byte) r;
            bytes[pixel * 4 + 1] = (byte) g;
            bytes[pixel * 4 + 2] = (byte) b;
            // Source alpha deliberately zero: diffuse coverage comes from checked MCAL.
        }
        return new TerrainAtlasComposer.DiffuseMip(
                id, KEY, TerrainAtlasComposer.MipProfile.DECLARED_HANDMADE_V1, 256, 6, bytes);
    }

    private static int channel(TerrainTileAtlas atlas, int x, int y, int channel) {
        return atlas.pixels().get((y * 512 + x) * 4 + channel) & 255;
    }

    private static void rgb(TerrainTileAtlas atlas, int x, int y, int r, int g, int b) {
        assertEquals(r, channel(atlas, x, y, 0));
        assertEquals(g, channel(atlas, x, y, 1));
        assertEquals(b, channel(atlas, x, y, 2));
        assertEquals(255, channel(atlas, x, y, 3));
    }

    @Test
    void directCoverageIsNotSequentialAndRetainsSourceOrdinalMapping() {
        try (var textures = textures(new int[] {128, 64}, false)) {
            var result =
                    TerrainAtlasComposer.compose(
                            TILE,
                            grid(new byte[0], true, true),
                            textures,
                            Map.of(
                                    1L,
                                    mip(1, 255, 0, 0),
                                    2L,
                                    mip(2, 0, 255, 0),
                                    3L,
                                    mip(3, 0, 0, 255)),
                            () -> false);
            assertEquals(256, result.supportedChunks());
            assertTrue(result.diagnostics().isEmpty());
            rgb(result.atlas(), 480, 0, 63, 128, 64);
            assertEquals(15, result.atlas().mappings().getFirst().x());
            assertEquals(999000f, result.atlas().mappings().getFirst().nativeX());
            assertEquals(
                    (float) (-999000 - 15 * WorldFormats.TILE_SIZE / 16),
                    result.atlas().mappings().getFirst().nativeY());
            assertTrue(result.atlas().covered(0));
            assertTrue(result.atlas().covered(255));
            assertThrows(
                    ReadOnlyBufferException.class, () -> result.atlas().pixels().put(0, (byte) 2));
            assertThrows(ReadOnlyBufferException.class, () -> result.atlas().coverage().put(0, 0));
            assertThrows(
                    UnsupportedOperationException.class, () -> result.atlas().mappings().clear());
            assertFalse(result.atlas().pixels().hasArray());
            assertTrue(result.atlas().retainedBytes() < (2 << 20));
        }
    }

    @Test
    void splatCarriesLayerIdsMasksAndBrightnessForTheSameChunks() {
        try (var textures = textures(new int[] {128, 64}, false)) {
            var mips = Map.of(1L, mip(1, 255, 0, 0), 2L, mip(2, 0, 255, 0), 3L, mip(3, 0, 0, 255));
            var plain =
                    TerrainAtlasComposer.compose(
                            TILE, grid(new byte[0], true, true), textures, mips, () -> false);
            assertEquals(null, plain.splat(), "Splat is opt-in");
            var result =
                    TerrainAtlasComposer.compose(
                            TILE, grid(new byte[0], true, true), textures, mips, true, () -> false);
            var splat = result.splat();
            assertArrayEquals(new long[] {1, 2, 3}, splat.diffuseIds());
            // Every chunk: layers 0, 1, 2 name diffuses 1, 2, 3; the fourth is absent.
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(0, splat.layer(x, y, 0));
                    assertEquals(1, splat.layer(x, y, 1));
                    assertEquals(2, splat.layer(x, y, 2));
                    assertEquals(TerrainTileSplat.NONE, splat.layer(x, y, 3));
                }
            }
            var alpha = splat.alpha();
            for (int texel : new int[] {0, 511, 512 * 300 + 77, 512 * 512 - 1}) {
                assertEquals(128, alpha.get(texel * 4) & 255, "layer 1 mask");
                assertEquals(64, alpha.get(texel * 4 + 1) & 255, "layer 2 mask");
                assertEquals(0, alpha.get(texel * 4 + 2) & 255, "no layer 3");
                assertEquals(128, alpha.get(texel * 4 + 3) & 255, "no MCCV: brightness 1.0");
            }
            assertThrows(ReadOnlyBufferException.class, () -> splat.alpha().put(0, (byte) 1));
            // The atlas is unchanged by requesting the splat.
            assertEquals(plain.atlas().pixels(), result.atlas().pixels());
        }
    }

    @Test
    void splatRefusesInvalidShapes() {
        var layers = new byte[TerrainTileSplat.LAYER_BYTES];
        var alpha = new byte[TerrainTileSplat.ALPHA_BYTES];
        new TerrainTileSplat(new long[] {1}, layers, alpha);
        layers[7] = 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainTileSplat(new long[] {1}, layers, alpha),
                "A layer index outside the diffuse list");
        layers[7] = (byte) TerrainTileSplat.NONE;
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainTileSplat(new long[] {1, 1}, layers, alpha));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainTileSplat(new long[] {1}, new byte[10], alpha));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainTileSplat(new long[65], layers, alpha));
    }

    @Test
    void repeatsPixelCentersAndMaskCentersHaveExplicitOrientation() {
        byte[] pattern = new byte[64];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                pattern[(y * 4 + x) * 4] = (byte) (10 + x * 20);
                pattern[(y * 4 + x) * 4 + 1] = (byte) (30 + y * 30);
            }
        }
        var base =
                new TerrainAtlasComposer.DiffuseMip(
                        1, KEY, TerrainAtlasComposer.MipProfile.STRICT, 256, 6, pattern);
        pattern[0] = 100;
        assertEquals(10, base.pixels().get(0));
        assertThrows(ReadOnlyBufferException.class, () -> base.pixels().put(0, (byte) 1));
        try (var textures = textures(new int[] {255}, true)) {
            var atlas =
                    TerrainAtlasComposer.compose(
                                    TILE,
                                    grid(new byte[0], false, false),
                                    textures,
                                    Map.of(1L, base, 2L, mip(2, 255, 255, 255)),
                                    () -> false)
                            .atlas();
            // Both sampled mask coordinates are odd: that exact lattice selects base. A wrong
            // edge/center
            // convention can select the overlay, and a transpose swaps this asymmetric pattern.
            rgb(atlas, 2, 3, 50, 120, 0);
            rgb(atlas, 6, 7, 50, 120, 0);
            rgb(atlas, 3, 2, 70, 90, 0);
        }
    }

    @Test
    void fullEightLayersAndAllMaskPixelsAreValidatedBeforePainting() {
        int[] weights = {1, 2, 4, 8, 16, 32, 64};
        var mips = new HashMap<Long, TerrainAtlasComposer.DiffuseMip>();
        for (long id = 1; id <= 8; id++) {
            mips.put(id, mip(id, (int) id * 10, 0, 0));
        }
        try (var textures = textures(weights, false)) {
            var result =
                    TerrainAtlasComposer.compose(
                            TILE, grid(new byte[0], false, false), textures, mips, () -> false);
            // (128*10+1*20+2*30+4*40+8*50+16*60+32*70+64*80)/255 =>40.
            rgb(result.atlas(), 31, 31, 40, 0, 0);
            assertEquals(256, result.supportedChunks());
            mips.remove(8L);
            var missing =
                    TerrainAtlasComposer.compose(
                            TILE, grid(new byte[0], false, false), textures, mips, () -> false);
            assertEquals(0, missing.supportedChunks());
            assertEquals(256, missing.diagnostics().size());
            assertEquals(
                    TerrainAtlasComposer.Reason.MISSING_DIFFUSE,
                    missing.diagnostics().getFirst().reason());
            for (int at = 0; at < TerrainTileAtlas.PIXEL_BYTES; at++) {
                assertEquals(0, missing.atlas().pixels().get(at));
            }
        }
        // This invalid sum is detected even where a chosen atlas sample might miss it.
        try (var textures = textures(new int[] {255, 1}, true)) {
            var result =
                    TerrainAtlasComposer.compose(
                            TILE,
                            grid(new byte[0], false, false),
                            textures,
                            Map.of(1L, mip(1, 1, 1, 1), 2L, mip(2, 2, 2, 2), 3L, mip(3, 3, 3, 3)),
                            () -> false);
            assertEquals(0, result.supportedChunks());
            assertEquals(
                    WorldTextureFormats.ChunkIssue.MASK_SUM,
                    result.diagnostics().getFirst().maskIssue());
        }
    }

    private static byte[] colors() {
        byte[] colors = new byte[580];
        for (int index = 0; index < 145; index++) {
            colors[index * 4 + 3] = (byte) 255;
        }
        return colors;
    }

    private static void color(byte[] colors, int index, int r, int g, int b) {
        colors[index * 4] = (byte) b;
        colors[index * 4 + 1] = (byte) g;
        colors[index * 4 + 2] = (byte) r;
    }

    @Test
    void normalizedLocalFineFanTintUsesBgraAndAllFourTriangleRegions() {
        byte[] colors = colors();
        for (int[] entry : new int[][] {{0, 10}, {1, 30}, {18, 50}, {17, 70}, {9, 90}}) {
            color(colors, entry[0], entry[1], entry[1], entry[1]);
        }
        try (var textures = textures(new int[0], false)) {
            var atlas =
                    TerrainAtlasComposer.compose(
                                    TILE,
                                    grid(colors, false, false),
                                    textures,
                                    Map.of(1L, mip(1, 255, 255, 255)),
                                    () -> false)
                            .atlas();
            rgb(atlas, 1, 0, 70, 70, 70);
            rgb(atlas, 3, 1, 100, 100, 100);
            rgb(atlas, 1, 3, 140, 140, 140);
            rgb(atlas, 0, 1, 90, 90, 90);
            // Exact center diagonal has the same interpolation from both touching fan triangles.
            rgb(atlas, 0, 0, 60, 60, 60);
            byte[] constant = colors();
            for (int index = 0; index < 145; index++) {
                color(constant, index, 32, 64, 96);
            }
            var swizzled =
                    TerrainAtlasComposer.compose(
                                    TILE,
                                    grid(constant, true, false),
                                    textures,
                                    Map.of(1L, mip(1, 255, 255, 255)),
                                    () -> false)
                            .atlas();
            rgb(swizzled, 0, 0, 64, 128, 192);
            constant[3] = 0;
            var declined =
                    TerrainAtlasComposer.compose(
                            TILE,
                            grid(constant, false, false),
                            textures,
                            Map.of(1L, mip(1, 255, 255, 255)),
                            () -> false);
            assertEquals(255, declined.supportedChunks());
            assertFalse(declined.atlas().covered(0));
            assertEquals(
                    TerrainAtlasComposer.Reason.COLOR_ALPHA,
                    declined.diagnostics().getFirst().reason());
            assertEquals(0, channel(declined.atlas(), 0, 0, 3));
        }
    }

    @Test
    void tintRoundsOnlyAfterWeightedCompositionAndDeclinesUnsupportedColorShape() {
        byte[] colors = colors();
        for (int index = 0; index < 145; index++) {
            color(colors, index, 192, 192, 192);
        }
        var grid = grid(colors, false, false);
        try (var textures = textures(new int[] {128}, false)) {
            var atlas =
                    TerrainAtlasComposer.compose(
                                    TILE,
                                    grid,
                                    textures,
                                    Map.of(1L, mip(1, 1, 1, 200), 2L, mip(2, 2, 2, 200)),
                                    () -> false)
                            .atlas();
            // ~1.502 * (384/255) rounds to2; premature blend rounding would produce3.
            rgb(atlas, 0, 0, 2, 2, 255);
            var chunks = new ArrayList<>(grid.chunks());
            var first = chunks.getFirst();
            chunks.set(
                    0,
                    new TerrainGrid.Chunk(
                            0,
                            first.x(),
                            first.y(),
                            0,
                            first.originX(),
                            first.originY(),
                            first.originZ(),
                            new float[145],
                            0,
                            TerrainGrid.ColorState.UNSUPPORTED,
                            new byte[0]));
            var partial =
                    TerrainAtlasComposer.compose(
                            TILE,
                            new TerrainGrid(chunks),
                            textures,
                            Map.of(1L, mip(1, 1, 1, 200), 2L, mip(2, 2, 2, 200)),
                            () -> false);
            assertEquals(255, partial.supportedChunks());
            assertEquals(
                    TerrainAtlasComposer.Reason.COLOR_SHAPE,
                    partial.diagnostics().getFirst().reason());
            assertEquals(0, channel(partial.atlas(), 0, 0, 3));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TerrainAtlasComposer.compose(
                                    TILE,
                                    new TerrainGrid(chunks.subList(0, 255)),
                                    textures,
                                    Map.of(1L, mip(1, 1, 1, 200), 2L, mip(2, 2, 2, 200)),
                                    () -> false));
        }
    }

    @Test
    void canceledOrUnboundedRequestsProduceNoAtlasAndDoNotOwnTextureSource() {
        try (var textures = textures(new int[0], false)) {
            var grid = grid(new byte[0], false, false);
            var calls = new AtomicInteger();
            assertThrows(
                    CancellationException.class,
                    () ->
                            TerrainAtlasComposer.compose(
                                    TILE,
                                    grid,
                                    textures,
                                    Map.of(1L, mip(1, 1, 2, 3)),
                                    () -> calls.incrementAndGet() > 80));
            assertEquals(
                    256,
                    TerrainAtlasComposer.compose(
                                    TILE, grid, textures, Map.of(1L, mip(1, 1, 2, 3)), () -> false)
                            .supportedChunks());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            TerrainAtlasComposer.compose(
                                    TILE,
                                    grid,
                                    textures,
                                    Map.of(2L, mip(1, 1, 2, 3)),
                                    () -> false));
            var tooMany = new HashMap<Long, TerrainAtlasComposer.DiffuseMip>();
            for (long id = 1; id <= 65; id++) {
                tooMany.put(id, mip(id, 1, 2, 3));
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TerrainAtlasComposer.compose(TILE, grid, textures, tooMany, () -> false));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new TerrainAtlasComposer.DiffuseMip(
                                    1,
                                    KEY,
                                    TerrainAtlasComposer.MipProfile.STRICT,
                                    256,
                                    5,
                                    new byte[64]));
        }
    }
}
