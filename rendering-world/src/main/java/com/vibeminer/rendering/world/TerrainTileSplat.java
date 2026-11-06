package com.vibeminer.rendering.world;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Shader-side splatting inputs of one tile (issue #174 step 2), laid out like the atlas: chunk
 * header (x, y) owns atlas rectangle (32x, 32y). Immutable; arrays are copied in and out.
 *
 * <ul>
 *   <li>{@code diffuseIds}: the tile's distinct layer diffuse file ids, at most {@link
 *       #MAX_DIFFUSES}.
 *   <li>{@code layers}: 256 chunks x 4 bytes, row-major by header (y, x); each byte indexes {@code
 *       diffuseIds}, {@link #NONE} for an absent layer or an unsupported chunk.
 *   <li>{@code alpha}: 512 x 512 RGBA8; R, G, B are the layer 1 to 3 masks sampled like the atlas
 *       (every other mask texel), A is the MCCV brightness (128 = 1.0, 255 = 2.0) of that texel.
 * </ul>
 */
public final class TerrainTileSplat {
    public static final int SIDE = 512, CHUNKS = 256, MAX_DIFFUSES = 64;
    public static final int LAYER_BYTES = CHUNKS * 4, ALPHA_BYTES = SIDE * SIDE * 4;
    public static final int NONE = 255;

    private final long[] diffuseIds;
    private final byte[] layers, alpha;

    public TerrainTileSplat(long[] diffuseIds, byte[] layers, byte[] alpha) {
        if (diffuseIds == null
                || layers == null
                || alpha == null
                || diffuseIds.length > MAX_DIFFUSES
                || layers.length != LAYER_BYTES
                || alpha.length != ALPHA_BYTES) {
            throw new IllegalArgumentException("Invalid terrain splat shape");
        }
        for (long id : diffuseIds) {
            if (id <= 0 || id > 0xffff_ffffL) {
                throw new IllegalArgumentException("Invalid splat diffuse id");
            }
        }
        if (Arrays.stream(diffuseIds).distinct().count() != diffuseIds.length) {
            throw new IllegalArgumentException("Duplicate splat diffuse id");
        }
        for (byte layer : layers) {
            int index = layer & 255;
            if (index != NONE && index >= diffuseIds.length) {
                throw new IllegalArgumentException("Splat layer outside its diffuse list");
            }
        }
        this.diffuseIds = diffuseIds.clone();
        this.layers = layers.clone();
        this.alpha = alpha.clone();
    }

    public long[] diffuseIds() {
        return diffuseIds.clone();
    }

    public int diffuseCount() {
        return diffuseIds.length;
    }

    /** Diffuse list index of chunk (x, y) layer 0 to 3, or {@link #NONE}. */
    public int layer(int x, int y, int layer) {
        return layers[((y * 16 + x) * 4) + layer] & 255;
    }

    public ByteBuffer layers() {
        return ByteBuffer.wrap(layers).asReadOnlyBuffer();
    }

    public ByteBuffer alpha() {
        return ByteBuffer.wrap(alpha).asReadOnlyBuffer();
    }

    public long retainedBytes() {
        return 64L + diffuseIds.length * 8L + LAYER_BYTES + ALPHA_BYTES;
    }
}
