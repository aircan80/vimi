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
 *   <li>{@code alpha}: a square RGBA8 mask map of {@link #STANDARD_MASK} (32 texels per chunk,
 *       every other alpha-map texel, like the atlas) or {@link #HIGH_MASK} (the native 64 x 64
 *       alpha map per chunk) texels per side, chosen by the texture quality tier (issue #174 step
 *       2b-2); R, G, B are the layer 1 to 3 masks, A is the MCCV brightness (128 = 1.0, 255 = 2.0)
 *       of that texel.
 * </ul>
 */
public final class TerrainTileSplat {
    public static final int SIDE = 512, CHUNKS = 256, MAX_DIFFUSES = 64;

    /** Mask map sides: STANDARD every other alpha-map texel, HIGH the native 64 per chunk. */
    public static final int STANDARD_MASK = SIDE, HIGH_MASK = 1024;

    public static final int LAYER_BYTES = CHUNKS * 4, ALPHA_BYTES = SIDE * SIDE * 4;

    /** Bytes of the largest (HIGH) mask map. */
    public static final int MAX_ALPHA_BYTES = HIGH_MASK * HIGH_MASK * 4;

    public static final int NONE = 255;

    /**
     * The mask side a tile gets (issue #174 step 2b-2, lead decision): HIGH (1024) only on the HIGH
     * texture tier and inside the NEAR radius; FAR's outer ring uses STANDARD (512) even on HIGH,
     * which keeps FAR + HIGH near 53 + 20 MiB of masks instead of 139 MiB.
     */
    public static int maskSide(boolean high, boolean outerRing) {
        return high && !outerRing ? HIGH_MASK : STANDARD_MASK;
    }

    /**
     * Whether a tile at offset ({@code dx}, {@code dy}) from the camera's tile lies past the square
     * {@code nearRadius} (1 for NEAR's 3 x 3): FAR's outer ring.
     */
    public static boolean outerRing(int dx, int dy, int nearRadius) {
        if (nearRadius < 0) {
            throw new IllegalArgumentException("Invalid near radius");
        }
        return Math.max(Math.abs(dx), Math.abs(dy)) > nearRadius;
    }

    /**
     * GPU bytes of one slot's mask texture with its full mip chain: 1,398,100 at 512, 5,592,404 at
     * 1024. Charged per slot with terrain, like the atlas, not against the objects budget.
     */
    public static long maskChainBytes(int side) {
        alphaBytes(side);
        return ObjectTextureMips.chainPixels(side, side) * 4L;
    }

    /** RGBA8 bytes of a mask map of {@code side}; only the two mask sides are valid. */
    public static int alphaBytes(int side) {
        if (side != STANDARD_MASK && side != HIGH_MASK) {
            throw new IllegalArgumentException("Invalid splat mask side");
        }
        return side * side * 4;
    }

    private final long[] diffuseIds;
    private final byte[] layers, alpha;
    private final int maskSide;

    public TerrainTileSplat(long[] diffuseIds, byte[] layers, byte[] alpha) {
        if (diffuseIds == null
                || layers == null
                || alpha == null
                || diffuseIds.length > MAX_DIFFUSES
                || layers.length != LAYER_BYTES
                || alpha.length != ALPHA_BYTES && alpha.length != MAX_ALPHA_BYTES) {
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
        this.maskSide = alpha.length == ALPHA_BYTES ? STANDARD_MASK : HIGH_MASK;
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

    /** Side of the mask map: {@link #STANDARD_MASK} or {@link #HIGH_MASK}. */
    public int maskSide() {
        return maskSide;
    }

    public ByteBuffer alpha() {
        return ByteBuffer.wrap(alpha).asReadOnlyBuffer();
    }

    public long retainedBytes() {
        return 64L + diffuseIds.length * 8L + LAYER_BYTES + alpha.length;
    }
}
