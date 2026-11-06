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
    private final List<Mapping> mappings;
    private final int supportedChunks;

    // Package-private ownership transfer: only the pure composer allocates these arrays.
    TerrainTileAtlas(
            WorldFormats.Tile tile, byte[] pixels, long[] coverage, List<Mapping> mappings) {
        this.tile = tile;
        this.pixels = pixels;
        this.coverage = coverage;
        this.mappings = List.copyOf(mappings);
        int supported = 0;
        for (long bits : coverage) {
            supported += Long.bitCount(bits);
        }
        supportedChunks = supported;
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

    public List<Mapping> mappings() {
        return mappings;
    }

    public long retainedBytes() {
        return PIXEL_BYTES + 32L + mappings.size() * 64L + 256;
    }
}
