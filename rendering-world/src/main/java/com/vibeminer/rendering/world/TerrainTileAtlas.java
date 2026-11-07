package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/** Immutable coarse basic-coverage preview; this value does not authenticate source files. */
public final class TerrainTileAtlas {
    public static final int SIDE = 512;
    public static final int PIXEL_BYTES = SIDE * SIDE * 4;
    public static final String RECIPE = "basic-coverage-preview-512-v2";

    /** Header coordinates select atlas rectangles; native anchors retain original source floats. */
    public record Mapping(int ordinal, int x, int y, float nativeX, float nativeY, float nativeZ) {}

    private final WorldFormats.Tile tile;
    private final byte[] pixels;
    private final long[] coverage;
    private final long[] empty;
    private final List<Mapping> mappings;
    private final int supportedChunks, emptyChunks;
    private final TerrainTileSplat splat;

    // Package-private ownership transfer: only the pure composer allocates these arrays.
    TerrainTileAtlas(
            WorldFormats.Tile tile, byte[] pixels, long[] coverage, List<Mapping> mappings) {
        this(tile, pixels, coverage, new long[4], mappings, null);
    }

    TerrainTileAtlas(
            WorldFormats.Tile tile,
            byte[] pixels,
            long[] coverage,
            long[] empty,
            List<Mapping> mappings,
            TerrainTileSplat splat) {
        if (coverage.length != 4 || empty.length != 4) {
            throw new IllegalArgumentException("Invalid atlas chunk masks");
        }
        int supported = 0, nothing = 0;
        for (int i = 0; i < 4; i++) {
            if ((coverage[i] & empty[i]) != 0) {
                throw new IllegalArgumentException("A chunk cannot be both drawn and empty");
            }
            supported += Long.bitCount(coverage[i]);
            nothing += Long.bitCount(empty[i]);
        }
        this.splat = splat;
        this.tile = tile;
        this.pixels = pixels;
        this.coverage = coverage;
        this.empty = empty;
        this.mappings = List.copyOf(mappings);
        supportedChunks = supported;
        emptyChunks = nothing;
    }

    public WorldFormats.Tile tile() {
        return tile;
    }

    public ByteBuffer pixels() {
        return ByteBuffer.wrap(pixels).asReadOnlyBuffer();
    }

    /** Bits are keyed by source ordinal, independently of header X/Y and holes. */
    public LongBuffer coverage() {
        return LongBuffer.wrap(coverage).asReadOnlyBuffer();
    }

    public boolean covered(int ordinal) {
        if (ordinal < 0 || ordinal >= 256) {
            throw new IllegalArgumentException("Invalid source ordinal");
        }
        return (coverage[ordinal / 64] & (1L << (ordinal % 64))) != 0;
    }

    public int supportedChunks() {
        return supportedChunks;
    }

    /**
     * Chunks that declare no texture layers (an empty MCLY). They have nothing to draw, so they are
     * never covered, but they don't make a tile incomplete either.
     */
    public int emptyChunks() {
        return emptyChunks;
    }

    public boolean empty(int ordinal) {
        if (ordinal < 0 || ordinal >= 256) {
            throw new IllegalArgumentException("Invalid source ordinal");
        }
        return (empty[ordinal / 64] & (1L << (ordinal % 64))) != 0;
    }

    /** Every chunk is either drawn or has nothing to draw. */
    public boolean complete() {
        return supportedChunks + emptyChunks == 256;
    }

    public List<Mapping> mappings() {
        return mappings;
    }

    /** Shader-side splatting inputs when the composition requested them (issue #174 step 2). */
    public java.util.Optional<TerrainTileSplat> splat() {
        return java.util.Optional.ofNullable(splat);
    }

    public long retainedBytes() {
        return PIXEL_BYTES
                + 32L
                + mappings.size() * 64L
                + 256
                + (splat == null ? 0 : splat.retainedBytes());
    }

    /** The largest {@link #retainedBytes()} with a full splat: 256 mappings, 64 diffuses. */
    public static final long MAX_RETAINED_BYTES = maxRetainedBytes(TerrainTileSplat.HIGH_MASK);

    /**
     * The largest {@link #retainedBytes()} of an atlas whose splat mask has {@code maskSide}
     * ({@link TerrainTileSplat#STANDARD_MASK} or {@link TerrainTileSplat#HIGH_MASK}), or of one
     * without splatting inputs when {@code maskSide} is 0.
     */
    public static long maxRetainedBytes(int maskSide) {
        long atlas = PIXEL_BYTES + 32L + 256 * 64L + 256;
        return maskSide == 0
                ? atlas
                : atlas
                        + 64L
                        + TerrainTileSplat.MAX_DIFFUSES * 8L
                        + TerrainTileSplat.LAYER_BYTES
                        + TerrainTileSplat.alphaBytes(maskSide);
    }
}
