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
        COLOR_SHAPE
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

    public record Composition(TerrainTileAtlas atlas, List<Diagnostic> diagnostics) {
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
        long[] coverage = new long[4], empty = new long[4];
        var mappings = new ArrayList<TerrainTileAtlas.Mapping>(256);
        var diagnostics = new ArrayList<Diagnostic>();
        var masks = ByteBuffer.allocate(WorldTextureFormats.MAX_MASK_SCRATCH);
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
            if (metadata.issue() == WorldTextureFormats.ChunkIssue.NO_LAYERS) {
                empty[ordinal / 64] |= 1L << (ordinal % 64);
            }
            if (metadata.issue() != none) {
                diagnostics.add(new Diagnostic(ordinal, Reason.TEXTURE_METADATA, metadata.issue()));
                continue;
            }
            if (chunk.colorState() == TerrainGrid.ColorState.UNSUPPORTED) {
                diagnostics.add(new Diagnostic(ordinal, Reason.COLOR_SHAPE, none));
                continue;
            }
            // MCCV alpha is not a coverage input; only its RGB tint is applied.
            var colors = chunk.colors();
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
            var decoded = textures.decodeAlphas(ordinal, chunk.flags(), masks, canceled);
            if (!decoded.admitted()) {
                diagnostics.add(new Diagnostic(ordinal, Reason.MASK_DATA, decoded.issue()));
                continue;
            }
            renderChunk(chunk, mips, masks, colors, pixels, canceled);
            coverage[ordinal / 64] |= 1L << (ordinal % 64);
        }
        check(canceled);
        return new Composition(
                new TerrainTileAtlas(tile, pixels, coverage, empty, mappings), diagnostics);
    }

    private static void renderChunk(
            TerrainGrid.Chunk chunk,
            DiffuseMip[] mips,
            ByteBuffer masks,
            ByteBuffer colors,
            byte[] pixels,
            BooleanSupplier canceled) {
        int[] weights = new int[mips.length];
        for (int py = 0; py < 32; py++) {
            check(canceled);
            for (int px = 0; px < 32; px++) {
                // Each atlas pixel covers a 2x2 block of the 64x64 masks; average it.
                int maskPixel = 2 * py * 64 + 2 * px;
                for (int layer = 1; layer < mips.length; layer++) {
                    int at = (layer - 1) * 4096 + maskPixel;
                    weights[layer] =
                            ((masks.get(at) & 255)
                                            + (masks.get(at + 1) & 255)
                                            + (masks.get(at + 64) & 255)
                                            + (masks.get(at + 65) & 255)
                                            + 2)
                                    / 4;
                }
                int sum = 0;
                for (int layer = 1; layer < mips.length; layer++) {
                    sum += weights[layer];
                }
                // Base layer takes what the overlays leave, clamped at zero. Overlays summing past
                // 255 are normalized so the blend stays a weighted average.
                weights[0] = Math.max(0, 255 - sum);
                int total = Math.max(255, weights[0] + sum);
                // Eight repeats * four texels / 32 atlas pixels: exactly px/py mod4.
                int texel = ((py % 4) * 4 + px % 4) * 4;
                int output = ((chunk.y() * 32 + py) * 512 + chunk.x() * 32 + px) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    int weighted = 0;
                    for (int layer = 0; layer < mips.length; layer++) {
                        weighted += (mips[layer].pixels[texel + channel] & 255) * weights[layer];
                    }
                    double tint = colors.hasRemaining() ? tint(colors, px, py, channel) : 1;
                    pixels[output + channel] =
                            (byte)
                                    Math.max(
                                            0,
                                            Math.min(
                                                    255,
                                                    Math.round((double) weighted / total * tint)));
                }
                pixels[output + 3] = (byte) 255;
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
