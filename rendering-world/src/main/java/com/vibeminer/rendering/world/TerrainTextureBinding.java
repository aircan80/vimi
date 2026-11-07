package com.vibeminer.rendering.world;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Scoped immutable-pixel borrow. This value neither owns a pool lease nor authenticates sources.
 */
public final class TerrainTextureBinding {
    public static final double MAX_UV_ERROR_TEXELS = 1.0 / 32;
    public static final int SIDE = 512, PIXEL_BYTES = SIDE * SIDE * 4;

    public record Sources(
            long rootFileDataId,
            String rootContentKey,
            long tex0FileDataId,
            String tex0ContentKey) {
        public Sources {
            if (rootFileDataId <= 0
                    || rootFileDataId > 0xffff_ffffL
                    || tex0FileDataId <= 0
                    || tex0FileDataId > 0xffff_ffffL
                    || rootContentKey == null
                    || !rootContentKey.matches("[0-9a-fA-F]{32}")
                    || tex0ContentKey == null
                    || !tex0ContentKey.matches("[0-9a-fA-F]{32}")) {
                throw new IllegalArgumentException("Invalid descriptive terrain source identity");
            }
        }
    }

    /** Detached fixed metadata; retains no atlas, geometry or pixel view. */
    public static final class Metadata {
        private final int ordinal, x, y;
        private final float maxX, maxY;
        private final long[] coverage;
        private final int emptyRectangles;
        private final String geometry;
        private final Sources sources;
        private final double error;

        private Metadata(
                int ordinal,
                TerrainScene.Tile tile,
                float maxX,
                float maxY,
                long[] coverage,
                int emptyRectangles,
                String geometry,
                Sources sources,
                double error) {
            int covered = 0;
            for (long bits : coverage) {
                covered += Long.bitCount(bits);
            }
            if (emptyRectangles < 0 || covered + emptyRectangles > 256) {
                throw new IllegalArgumentException("Invalid empty atlas rectangle count");
            }
            this.emptyRectangles = emptyRectangles;
            this.ordinal = ordinal;
            this.x = tile.x();
            this.y = tile.y();
            this.maxX = maxX;
            this.maxY = maxY;
            this.coverage = coverage.clone();
            this.geometry = geometry;
            this.sources = Objects.requireNonNull(sources);
            this.error = error;
        }

        public int tileOrdinal() {
            return ordinal;
        }

        public int tileX() {
            return x;
        }

        public int tileY() {
            return y;
        }

        public float nativeMaxX() {
            return maxX;
        }

        public float nativeMaxY() {
            return maxY;
        }

        public String rootGeometrySha256() {
            return geometry;
        }

        public Sources sources() {
            return sources;
        }

        public double maxSourceUvErrorTexels() {
            return error;
        }

        public LongBuffer rectangleCoverage() {
            return LongBuffer.wrap(coverage).asReadOnlyBuffer();
        }

        /** Rectangles whose chunk has no texture layers: nothing to draw, never covered. */
        public int emptyRectangles() {
            return emptyRectangles;
        }

        public boolean coveredHeaderRect(int x, int y) {
            if (x < 0 || x > 15 || y < 0 || y > 15) {
                throw new IllegalArgumentException("Invalid atlas rectangle");
            }
            int index = y * 16 + x;
            return (coverage[index / 64] & 1L << (index % 64)) != 0;
        }
    }

    private final Metadata metadata;
    private final ByteBuffer pixels;

    private TerrainTextureBinding(Metadata metadata, ByteBuffer pixels) {
        this.metadata = metadata;
        this.pixels = pixels.slice().asReadOnlyBuffer();
    }

    /**
     * Cheap shape-only borrow. Pixels MUST come from the SAME immutable qualified atlas. Caller
     * pins its lease/borrow through synchronous consumption and retains no view afterward. Readonly
     * buffers cannot enforce that lifetime or establish a new authenticated capability.
     */
    public static TerrainTextureBinding borrow(Metadata metadata, ByteBuffer rgba) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(rgba);
        if (!rgba.isReadOnly() || rgba.remaining() != PIXEL_BYTES) {
            throw new IllegalArgumentException(
                    "Borrow requires one readonly 512-square RGBA atlas");
        }
        return new TerrainTextureBinding(metadata, rgba);
    }

    public Metadata metadata() {
        return metadata;
    }

    public ByteBuffer pixels() {
        return pixels.asReadOnlyBuffer();
    }

    /** Point/clamp sampling while the caller's synchronous immutable borrow remains pinned. */
    public int sample(double nativeX, double nativeY) {
        if (!Double.isFinite(nativeX) || !Double.isFinite(nativeY)) {
            throw new IllegalArgumentException("Nonfinite terrain texture sample");
        }
        int x =
                Math.clamp(
                        (int) Math.floor((metadata.maxY - nativeY) * (3.0 / 1600.0) * SIDE),
                        0,
                        SIDE - 1);
        int y =
                Math.clamp(
                        (int) Math.floor((metadata.maxX - nativeX) * (3.0 / 1600.0) * SIDE),
                        0,
                        SIDE - 1);
        int at = (y * SIDE + x) * 4;
        return (pixels.get(at + 3) & 255) << 24
                | (pixels.get(at) & 255) << 16
                | (pixels.get(at + 1) & 255) << 8
                | pixels.get(at + 2) & 255;
    }

    /**
     * Worker-only qualification against exact geometry and the atlas's original source mappings.
     */
    public static Metadata qualify(
            int ordinal,
            TerrainScene.Tile tile,
            TerrainTileAtlas atlas,
            String qualifiedRootGeometrySha256,
            Sources sources,
            BooleanSupplier canceled) {
        Objects.requireNonNull(atlas);
        shape(ordinal, tile, canceled);
        String actual = TerrainGeometryFingerprint.sha256(tile);
        check(canceled);
        if (!actual.equals(qualifiedRootGeometrySha256)
                || atlas.tile().x() != tile.x()
                || atlas.tile().y() != tile.y()
                || atlas.mappings().size() != 256
                || atlas.supportedChunks() == 0) {
            throw new IllegalArgumentException("Atlas/source geometry binding differs");
        }
        boolean[] ordinals = new boolean[256], headers = new boolean[256];
        long[] coverage = new long[4];
        float maxX = Float.NaN, maxY = Float.NaN;
        var vertices = tile.vertices();
        for (var mapping : atlas.mappings()) {
            check(canceled);
            int source = mapping.ordinal(), x = mapping.x(), y = mapping.y();
            if (source < 0
                    || source > 255
                    || x < 0
                    || x > 15
                    || y < 0
                    || y > 15
                    || ordinals[source]
                    || headers[y * 16 + x]) {
                throw new IllegalArgumentException(
                        "Atlas mapping is not a complete unique source grid");
            }
            ordinals[source] = true;
            headers[y * 16 + x] = true;
            int base = source * 145 * 3;
            if (Float.floatToRawIntBits(mapping.nativeX())
                            != Float.floatToRawIntBits(vertices.get(base))
                    || Float.floatToRawIntBits(mapping.nativeY())
                            != Float.floatToRawIntBits(vertices.get(base + 1))) {
                throw new IllegalArgumentException("Atlas raw native anchor differs from geometry");
            }
            if (x == 0 && y == 0) {
                maxX = mapping.nativeX();
                maxY = mapping.nativeY();
            }
            if (atlas.covered(source)) {
                coverage[(y * 16 + x) / 64] |= 1L << ((y * 16 + x) % 64);
            }
        }
        double error = 0;
        for (var mapping : atlas.mappings()) {
            check(canceled);
            error =
                    Math.max(
                            error,
                            vertexError(
                                    tile,
                                    mapping.ordinal(),
                                    mapping.x(),
                                    mapping.y(),
                                    maxX,
                                    maxY,
                                    null));
        }
        requireError(error);
        return new Metadata(
                ordinal, tile, maxX, maxY, coverage, atlas.emptyChunks(), actual, sources, error);
    }

    /**
     * Defensive owned-snapshot validation; descriptive fields do not authenticate source assets.
     */
    public static Metadata fromSnapshot(
            int ordinal,
            TerrainScene.Tile tile,
            float maxX,
            float maxY,
            long[] coverage,
            Sources sources,
            BooleanSupplier canceled) {
        return fromSnapshot(ordinal, tile, maxX, maxY, coverage, 0, sources, canceled);
    }

    /** As above, with the snapshot's count of rectangles that have nothing to draw. */
    public static Metadata fromSnapshot(
            int ordinal,
            TerrainScene.Tile tile,
            float maxX,
            float maxY,
            long[] coverage,
            int emptyRectangles,
            Sources sources,
            BooleanSupplier canceled) {
        shape(ordinal, tile, canceled);
        Objects.requireNonNull(coverage);
        if (coverage.length != 4
                || !Float.isFinite(maxX)
                || !Float.isFinite(maxY)
                || (coverage[0] | coverage[1] | coverage[2] | coverage[3]) == 0) {
            throw new IllegalArgumentException("Invalid atlas snapshot mapping/coverage");
        }
        double error = inferredError(tile, maxX, maxY, null, canceled);
        requireError(error);
        String actual = TerrainGeometryFingerprint.sha256(tile);
        check(canceled);
        return new Metadata(
                ordinal, tile, maxX, maxY, coverage, emptyRectangles, actual, sources, error);
    }

    /** Separate emulation of rebased float vertex UV arithmetic; not a hardware precision claim. */
    public static double floatUvError(
            Metadata metadata,
            TerrainScene.Tile tile,
            TerrainScene.Origin origin,
            BooleanSupplier canceled) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(origin);
        shape(metadata.ordinal, tile, canceled);
        if (metadata.x != tile.x()
                || metadata.y != tile.y()
                || !metadata.geometry.equals(TerrainGeometryFingerprint.sha256(tile))) {
            throw new IllegalArgumentException("Texture metadata differs from geometry");
        }
        double error = inferredError(tile, metadata.maxX, metadata.maxY, origin, canceled);
        requireError(error);
        return error;
    }

    /** One-time codec/qualification scan, never part of borrow or per-pixel rendering admission. */
    public static void validatePixels(TerrainTextureBinding binding, BooleanSupplier canceled) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(canceled);
        var pixels = binding.pixels;
        for (int y = 0; y < SIDE; y++) {
            check(canceled);
            for (int x = 0; x < SIDE; x++) {
                int at = (y * SIDE + x) * 4;
                boolean covered = binding.metadata.coveredHeaderRect(x / 32, y / 32);
                if ((pixels.get(at + 3) & 255) != (covered ? 255 : 0)
                        || !covered
                                && (pixels.get(at) != 0
                                        || pixels.get(at + 1) != 0
                                        || pixels.get(at + 2) != 0)) {
                    throw new IllegalArgumentException(
                            "Atlas alpha/RGB differs from header rectangle coverage");
                }
            }
        }
    }

    private static void shape(int ordinal, TerrainScene.Tile tile, BooleanSupplier canceled) {
        Objects.requireNonNull(tile);
        Objects.requireNonNull(canceled);
        check(canceled);
        if (ordinal < 0
                || ordinal >= 9
                || tile.chunks() != 256
                || tile.vertexCount() != 256 * 145) {
            throw new IllegalArgumentException(
                    "Basic atlas requires a complete 256-chunk geometry tile");
        }
    }

    private static double inferredError(
            TerrainScene.Tile tile,
            float maxX,
            float maxY,
            TerrainScene.Origin origin,
            BooleanSupplier canceled) {
        boolean[] seen = new boolean[256];
        double error = 0;
        var vertices = tile.vertices();
        for (int ordinal = 0; ordinal < 256; ordinal++) {
            check(canceled);
            int base = ordinal * 145 * 3;
            int y = (int) Math.rint((maxX - (double) vertices.get(base)) * (3.0 / 100.0));
            int x = (int) Math.rint((maxY - (double) vertices.get(base + 1)) * (3.0 / 100.0));
            if (x < 0 || x > 15 || y < 0 || y > 15 || seen[y * 16 + x]) {
                throw new IllegalArgumentException("Geometry does not form a unique atlas grid");
            }
            seen[y * 16 + x] = true;
            error = Math.max(error, vertexError(tile, ordinal, x, y, maxX, maxY, origin));
        }
        return error;
    }

    private static double vertexError(
            TerrainScene.Tile tile,
            int ordinal,
            int x,
            int y,
            float maxX,
            float maxY,
            TerrainScene.Origin origin) {
        var vertices = tile.vertices();
        int at = ordinal * 145 * 3;
        double error = 0;
        for (int row = 0; row < 17; row++) {
            for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++, at += 3) {
                double u, v;
                if (origin == null) {
                    u = (maxY - (double) vertices.get(at + 1)) * (3.0 / 1600.0) * SIDE;
                    v = (maxX - (double) vertices.get(at)) * (3.0 / 1600.0) * SIDE;
                } else {
                    float px = (float) (vertices.get(at) - origin.x()),
                            py = (float) (vertices.get(at + 1) - origin.y());
                    float ox = (float) (maxX - origin.x()), oy = (float) (maxY - origin.y());
                    u = (oy - py) * (float) (3.0 / 1600.0) * SIDE;
                    v = (ox - px) * (float) (3.0 / 1600.0) * SIDE;
                }
                error =
                        Math.max(
                                error,
                                Math.max(
                                        Math.abs(u - (32 * x + 4 * (col + .5 * (row & 1)))),
                                        Math.abs(v - (32 * y + 2 * row))));
            }
        }
        return error;
    }

    private static void requireError(double error) {
        if (!Double.isFinite(error) || error > MAX_UV_ERROR_TEXELS) {
            throw new IllegalArgumentException("Atlas mapping exceeds 1/32 texel profile");
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException("Terrain texture qualification canceled");
        }
    }
}
