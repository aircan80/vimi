package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldTextureFormats;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Pure bounded BASIC_COVERAGE_PREVIEW, deliberately excluding client nonlinear sharpening. MCCV is
 * interpolated on the normalized local 8x8 source fan, a coarse preview approximation; it does not
 * pretend that rounded native float anchors form a perfectly affine tile grid. Exact original
 * per-chunk anchors are retained for a separately qualified renderer UV mapping.
 */
public final class TerrainAtlasComposer {
    private TerrainAtlasComposer() {}

    public enum MipProfile {
        STRICT,
        DECLARED_HANDMADE_V1
    }

    /** A declared 4x4 RGBA mip with descriptive provenance, not an authenticated file receipt. */
    public static final class DiffuseMip {
        private final long fileId;
        private final String contentKey;
        private final MipProfile profile;
        private final int originalSide, level;
        private final byte[] pixels;

        public DiffuseMip(
                long fileId,
                String contentKey,
                MipProfile profile,
                int originalSide,
                int level,
                byte[] pixels) {
            Objects.requireNonNull(contentKey);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(pixels);
            if (fileId <= 0
                    || fileId > 0xffff_ffffL
                    || contentKey.length() != 32
                    || !contentKey.matches("[0-9a-f]{32}")
                    || level < 0
                    || level > 12
                    || originalSide != (4 << level)
                    || pixels.length != 64) {
                throw new IllegalArgumentException("Invalid declared diffuse mip");
            }
            this.fileId = fileId;
            this.contentKey = contentKey;
            this.profile = profile;
            this.originalSide = originalSide;
            this.level = level;
            this.pixels = pixels.clone();
        }

        public long fileId() {
            return fileId;
        }

        public String contentKey() {
            return contentKey;
        }

        public MipProfile profile() {
            return profile;
        }

        public int originalSide() {
            return originalSide;
        }

        public int level() {
            return level;
        }

        public ByteBuffer pixels() {
            return ByteBuffer.wrap(pixels).asReadOnlyBuffer();
        }
    }

    public enum Reason {
        TEXTURE_METADATA,
        MASK_DATA,
        MISSING_DIFFUSE,
        COLOR_SHAPE,
        COLOR_ALPHA
    }

    public record Diagnostic(int ordinal, Reason reason, WorldTextureFormats.ChunkIssue maskIssue) {
        public Diagnostic {
            if (ordinal < 0 || ordinal >= 256) {
                throw new IllegalArgumentException("Invalid diagnostic ordinal");
            }
            Objects.requireNonNull(reason);
            Objects.requireNonNull(maskIssue);
        }
    }

    /** {@code splat} is null unless requested; it covers exactly the atlas's supported chunks. */
    public record Composition(
            TerrainTileAtlas atlas, List<Diagnostic> diagnostics, TerrainTileSplat splat) {
        public Composition(TerrainTileAtlas atlas, List<Diagnostic> diagnostics) {
            this(atlas, diagnostics, null);
        }

        public Composition {
            Objects.requireNonNull(atlas);
            Objects.requireNonNull(diagnostics);
            if (diagnostics.size() > 256) {
                throw new IllegalArgumentException("Too many chunk diagnostics");
            }
            diagnostics = List.copyOf(diagnostics);
        }

        public int supportedChunks() {
            return atlas.supportedChunks();
        }

        public int totalChunks() {
            return 256;
        }
    }

    public static Composition compose(
            WorldFormats.Tile tile,
            TerrainGrid grid,
            WorldTextureFormats.TextureData textures,
            Map<Long, DiffuseMip> diffuse,
            BooleanSupplier canceled) {
        return compose(tile, grid, textures, diffuse, false, canceled);
    }

    /**
     * Also returns the shader-side splatting inputs when {@code splat} is set (issue #174 step 2):
     * the same supported chunks, their layer diffuse ids and masks, and MCCV brightness.
     */
    public static Composition compose(
            WorldFormats.Tile tile,
            TerrainGrid grid,
            WorldTextureFormats.TextureData textures,
            Map<Long, DiffuseMip> diffuse,
            boolean splat,
            BooleanSupplier canceled) {
        Objects.requireNonNull(tile);
        Objects.requireNonNull(grid);
        Objects.requireNonNull(textures);
        Objects.requireNonNull(diffuse);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (tile.rootId() <= 0
                || tile.rootId() > 0xffff_ffffL
                || tile.tex0Id() <= 0
                || grid.chunks().size() != 256
                || textures.chunks().size() != 256
                || diffuse.size() > 64) {
            throw new IllegalArgumentException("Invalid tile composition bounds");
        }
        var sources = Map.copyOf(diffuse);
        for (var entry : sources.entrySet()) {
            if (entry.getKey() != entry.getValue().fileId()) {
                throw new IllegalArgumentException("Diffuse map identity mismatch");
            }
        }
        byte[] pixels = new byte[TerrainTileAtlas.PIXEL_BYTES];
        long[] coverage = new long[4];
        var mappings = new ArrayList<TerrainTileAtlas.Mapping>(256);
        var diagnostics = new ArrayList<Diagnostic>();
        var masks = ByteBuffer.allocate(WorldTextureFormats.MAX_MASK_SCRATCH);
        var splatIds = new ArrayList<Long>();
        byte[] splatLayers = null, splatAlpha = null;
        if (splat) {
            splatLayers = new byte[TerrainTileSplat.LAYER_BYTES];
            java.util.Arrays.fill(splatLayers, (byte) TerrainTileSplat.NONE);
            splatAlpha = new byte[TerrainTileSplat.ALPHA_BYTES];
        }
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            check(canceled);
            var chunk = grid.chunks().get(ordinal);
            var metadata = textures.chunks().get(ordinal);
            if (metadata.ordinal() != ordinal || chunk.ordinal() != ordinal) {
                throw new IllegalArgumentException("Mismatched source ordinal");
            }
            mappings.add(
                    new TerrainTileAtlas.Mapping(
                            ordinal,
                            chunk.x(),
                            chunk.y(),
                            chunk.originX(),
                            chunk.originY(),
                            chunk.originZ()));
            var none = WorldTextureFormats.ChunkIssue.NONE;
            if (metadata.issue() != none) {
                diagnostics.add(new Diagnostic(ordinal, Reason.TEXTURE_METADATA, metadata.issue()));
                continue;
            }
            if (chunk.colorState() == TerrainGrid.ColorState.UNSUPPORTED) {
                diagnostics.add(new Diagnostic(ordinal, Reason.COLOR_SHAPE, none));
                continue;
            }
            var colors = chunk.colors();
            boolean opaque = true;
            for (int at = 3; at < colors.limit(); at += 4) {
                opaque &= (colors.get(at) & 255) == 255;
            }
            if (!opaque) {
                diagnostics.add(new Diagnostic(ordinal, Reason.COLOR_ALPHA, none));
                continue;
            }
            var layers = metadata.layers();
            var mips = new DiffuseMip[layers.size()];
            boolean complete = true;
            for (int i = 0; i < layers.size(); i++) {
                long id = textures.diffuseIds().get((int) layers.get(i).textureIndex());
                mips[i] = sources.get(id);
                complete &= mips[i] != null;
            }
            if (!complete) {
                diagnostics.add(new Diagnostic(ordinal, Reason.MISSING_DIFFUSE, none));
                continue;
            }
            masks.clear();
            var decoded = textures.decodeAlphas(ordinal, masks, canceled);
            if (!decoded.admitted()) {
                diagnostics.add(new Diagnostic(ordinal, Reason.MASK_DATA, decoded.issue()));
                continue;
            }
            renderChunk(chunk, mips, masks, colors, pixels, canceled);
            // Splatting draws at most four layers (three masks); wider chunks keep the atlas.
            if (splat && layers.size() <= 4) {
                int base = (chunk.y() * 16 + chunk.x()) * 4;
                for (int i = 0; i < layers.size(); i++) {
                    long id = textures.diffuseIds().get((int) layers.get(i).textureIndex());
                    int index = splatIds.indexOf(id);
                    if (index < 0) {
                        index = splatIds.size();
                        splatIds.add(id);
                    }
                    splatLayers[base + i] = (byte) index;
                }
                splatChunk(chunk, layers.size(), masks, colors, splatAlpha, canceled);
            }
            coverage[ordinal / 64] |= 1L << (ordinal % 64);
        }
        check(canceled);
        var outputSplat =
                splat
                        ? new TerrainTileSplat(
                                splatIds.stream().mapToLong(Long::longValue).toArray(),
                                splatLayers,
                                splatAlpha)
                        : null;
        return new Composition(
                new TerrainTileAtlas(tile, pixels, coverage, mappings, outputSplat),
                diagnostics,
                outputSplat);
    }

    private static void renderChunk(
            TerrainGrid.Chunk chunk,
            DiffuseMip[] mips,
            ByteBuffer masks,
            ByteBuffer colors,
            byte[] pixels,
            BooleanSupplier canceled) {
        for (int py = 0; py < 32; py++) {
            check(canceled);
            for (int px = 0; px < 32; px++) {
                int maskPixel = (2 * py + 1) * 64 + 2 * px + 1;
                int sum = 0;
                for (int layer = 1; layer < mips.length; layer++) {
                    sum += masks.get((layer - 1) * 4096 + maskPixel) & 255;
                }
                // Eight repeats * four texels / 32 atlas pixels: exactly px/py mod4.
                int texel = ((py % 4) * 4 + px % 4) * 4;
                int output = ((chunk.y() * 32 + py) * 512 + chunk.x() * 32 + px) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    int weighted = (mips[0].pixels[texel + channel] & 255) * (255 - sum);
                    for (int layer = 1; layer < mips.length; layer++) {
                        weighted +=
                                (mips[layer].pixels[texel + channel] & 255)
                                        * (masks.get((layer - 1) * 4096 + maskPixel) & 255);
                    }
                    double tint = colors.hasRemaining() ? tint(colors, px, py, channel) : 1;
                    pixels[output + channel] =
                            (byte) Math.max(0, Math.min(255, Math.round(weighted / 255.0 * tint)));
                }
                pixels[output + 3] = (byte) 255;
            }
        }
    }

    /** Layer 1 to 3 masks at the atlas's sampling points, and the MCCV brightness, per texel. */
    private static void splatChunk(
            TerrainGrid.Chunk chunk,
            int layers,
            ByteBuffer masks,
            ByteBuffer colors,
            byte[] alpha,
            BooleanSupplier canceled) {
        for (int py = 0; py < 32; py++) {
            check(canceled);
            for (int px = 0; px < 32; px++) {
                int maskPixel = (2 * py + 1) * 64 + 2 * px + 1;
                int output = ((chunk.y() * 32 + py) * 512 + chunk.x() * 32 + px) * 4;
                for (int layer = 1; layer < 4; layer++) {
                    alpha[output + layer - 1] =
                            layer < layers ? masks.get((layer - 1) * 4096 + maskPixel) : 0;
                }
                double brightness = 1;
                if (colors.hasRemaining()) {
                    brightness =
                            (tint(colors, px, py, 0)
                                            + tint(colors, px, py, 1)
                                            + tint(colors, px, py, 2))
                                    / 3;
                }
                alpha[output + 3] = (byte) Math.max(0, Math.min(255, Math.round(brightness * 128)));
            }
        }
    }

    // Fine source fan triangles a-m-b, b-m-e, e-m-d, d-m-a; holes do not erase texture facts.
    private static double tint(ByteBuffer colors, int px, int py, int channel) {
        double x = (px + .5) / 4, y = (py + .5) / 4;
        int col = (int) x, row = (int) y;
        double dx = x - col, dy = y - row;
        int a = row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
        int first, second;
        double w1, w2, wm;
        if (dy <= dx && dy <= 1 - dx) {
            first = a;
            second = b;
            w1 = 1 - dx - dy;
            w2 = dx - dy;
            wm = 2 * dy;
        } else if (dx >= dy && dx >= 1 - dy) {
            first = b;
            second = e;
            w1 = dx - dy;
            w2 = dx + dy - 1;
            wm = 2 * (1 - dx);
        } else if (dy >= dx && dy >= 1 - dx) {
            first = e;
            second = d;
            w1 = dx + dy - 1;
            w2 = dy - dx;
            wm = 2 * (1 - dy);
        } else {
            first = d;
            second = a;
            w1 = dy - dx;
            w2 = 1 - dx - dy;
            wm = 2 * dx;
        }
        int bgra = 2 - channel;
        return 2.0
                / 255
                * ((colors.get(first * 4 + bgra) & 255) * w1
                        + (colors.get(second * 4 + bgra) & 255) * w2
                        + (colors.get(m * 4 + bgra) & 255) * wm);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Terrain atlas composition canceled");
        }
    }
}
