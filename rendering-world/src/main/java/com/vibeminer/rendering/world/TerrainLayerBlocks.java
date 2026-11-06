package com.vibeminer.rendering.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Terrain splatting layers as one fixed BC1 chain each (issue #174 step 2b, owner decision of
 * 2026-11-06): {@link #SIDE} x {@link #SIDE}, every level down to 1 x 1, {@link #CHAIN_BYTES}
 * bytes, so every layer fits one shared compressed texture array.
 *
 * <p>Terrain layers are opaque: the atlas composer blends their colour only. A BLP's own DXT blocks
 * are therefore carried as BC1 without loss. BC1 blocks are copied. The colour half of a BC2 or BC3
 * block, which always decodes in four-colour mode, is copied when its endpoints already order
 * {@code c0 > c1}; otherwise the endpoints are swapped and the indices remapped (or, for equal
 * endpoints, all set to 0), which decodes to the same colours as BC1. Sources without blocks at
 * this side are resampled and encoded by {@link #encode}.
 */
public final class TerrainLayerBlocks {
    public static final int SIDE = 256;

    public static final int LEVELS = 9;

    /** One layer: 43,704 bytes. */
    public static final int CHAIN_BYTES =
            ObjectTextureFormats.chainBytes(ObjectTextureFormats.BC1, SIDE, SIDE);

    private TerrainLayerBlocks() {}

    public static int side(int level) {
        return Math.max(1, SIDE >> level);
    }

    public static int levelOffset(int level) {
        return ObjectTextureFormats.levelOffset(ObjectTextureFormats.BC1, SIDE, SIDE, level);
    }

    public static int levelBytes(int level) {
        return ObjectTextureFormats.levelBytes(ObjectTextureFormats.BC1, SIDE, SIDE, level);
    }

    /**
     * A layer chain from a file's own blocks: {@code levels[k]} is level k (side {@code 256 >> k})
     * in {@code format} (BC1, BC2 or BC3), exactly that level's block bytes. Levels 0 to 6 (256
     * down to 4) are required; a missing 2 x 2 or 1 x 1 level is one block of the 4 x 4 level's
     * mean colour.
     */
    public static byte[] fromBlocks(int format, byte[][] levels) {
        Objects.requireNonNull(levels);
        if (!ObjectTextureFormats.compressed(format)
                || levels.length < 7
                || levels.length > LEVELS) {
            throw new IllegalArgumentException("Terrain layer blocks");
        }
        int sourceBlock = ObjectTextureFormats.unitBytes(format);
        byte[] chain = new byte[CHAIN_BYTES];
        for (int level = 0; level < LEVELS; level++) {
            int blocks = levelBytes(level) / 8, at = levelOffset(level);
            if (level < levels.length) {
                var source = Objects.requireNonNull(levels[level]);
                if (source.length != blocks * sourceBlock) {
                    throw new IllegalArgumentException("Terrain layer level " + level + " size");
                }
                for (int b = 0; b < blocks; b++) {
                    colourBlock(
                            source,
                            b * sourceBlock + (sourceBlock - 8),
                            format == ObjectTextureFormats.BC1,
                            chain,
                            at + b * 8);
                }
            } else {
                // The 4 x 4 level's mean colour as one solid block.
                var four =
                        ObjectTextureFormats.decodeLevel(
                                ObjectTextureFormats.BC1,
                                4,
                                4,
                                ByteBuffer.wrap(chain),
                                levelOffset(6));
                solid(mean(four, 0, 16), chain, at);
            }
        }
        return chain;
    }

    /**
     * A layer chain from square power-of-two RGBA8 of side 1 to {@link #SIDE}: enlarged to {@link
     * #SIDE} by wrapping bilinear filtering (a diffuse tiles across its chunk), box-filtered down
     * to 1 x 1, and each level encoded as BC1. Lossy; only for sources without usable blocks.
     */
    public static byte[] encode(ByteBuffer rgba, int side) {
        Objects.requireNonNull(rgba);
        if (side < 1
                || side > SIDE
                || Integer.bitCount(side) != 1
                || rgba.remaining() != side * side * 4) {
            throw new IllegalArgumentException("Terrain layer pixels");
        }
        int base = rgba.position();
        byte[] level = new byte[SIDE * SIDE * 4];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                double sx = (x + .5) * side / SIDE - .5, sy = (y + .5) * side / SIDE - .5;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                double fx = sx - x0, fy = sy - y0;
                for (int c = 0; c < 4; c++) {
                    double value = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int tx = Math.floorMod(x0 + dx, side),
                                    ty = Math.floorMod(y0 + dy, side);
                            double weight = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy);
                            value += weight * (rgba.get(base + (ty * side + tx) * 4 + c) & 255);
                        }
                    }
                    level[(y * SIDE + x) * 4 + c] = (byte) Math.round(value);
                }
            }
        }
        byte[] chain = new byte[CHAIN_BYTES];
        for (int k = 0; k < LEVELS; k++) {
            int s = side(k);
            encodeLevel(level, s, chain, levelOffset(k));
            if (k + 1 < LEVELS) {
                level = halve(level, s);
            }
        }
        return chain;
    }

    /** Encodes one square RGBA8 level of side {@code s}; sides under 4 repeat their texels. */
    static void encodeLevel(byte[] rgba, int s, byte[] chain, int at) {
        int blocks = (s + 3) / 4;
        int[] texels = new int[16 * 3];
        for (int by = 0; by < blocks; by++) {
            for (int bx = 0; bx < blocks; bx++) {
                for (int p = 0; p < 16; p++) {
                    int x = (bx * 4 + p % 4) % s, y = (by * 4 + p / 4) % s;
                    for (int c = 0; c < 3; c++) {
                        texels[p * 3 + c] = rgba[(y * s + x) * 4 + c] & 255;
                    }
                }
                encodeBlock(texels, chain, at + (by * blocks + bx) * 8);
            }
        }
    }

    /**
     * Range-fit BC1 in four-colour mode: endpoints are the quantized extremes along the block's
     * widest channel range, each texel takes the nearest of the four palette colours.
     */
    static void encodeBlock(int[] texels, byte[] out, int at) {
        int[] lo = {255, 255, 255}, hi = {0, 0, 0};
        for (int p = 0; p < 16; p++) {
            for (int c = 0; c < 3; c++) {
                lo[c] = Math.min(lo[c], texels[p * 3 + c]);
                hi[c] = Math.max(hi[c], texels[p * 3 + c]);
            }
        }
        int c0 = pack(hi), c1 = pack(lo);
        if (c0 == c1) {
            write(out, at, c0, c1, 0);
            return;
        }
        if (c0 < c1) {
            int swap = c0;
            c0 = c1;
            c1 = swap;
        }
        int[][] palette = palette(c0, c1);
        int selectors = 0;
        for (int p = 0; p < 16; p++) {
            int best = 0;
            long bestError = Long.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                long error = 0;
                for (int c = 0; c < 3; c++) {
                    long d = texels[p * 3 + c] - palette[i][c];
                    error += d * d;
                }
                if (error < bestError) {
                    bestError = error;
                    best = i;
                }
            }
            selectors |= best << (p * 2);
        }
        write(out, at, c0, c1, selectors);
    }

    /** The four four-colour palette entries of endpoints {@code c0 > c1}, as the decoder rounds. */
    private static int[][] palette(int c0, int c1) {
        int[] a = expand(c0), b = expand(c1);
        int[][] palette = new int[4][3];
        for (int c = 0; c < 3; c++) {
            palette[0][c] = a[c];
            palette[1][c] = b[c];
            palette[2][c] = (2 * a[c] + b[c] + 1) / 3;
            palette[3][c] = (a[c] + 2 * b[c] + 1) / 3;
        }
        return palette;
    }

    /** Copies (and if needed reorders) one colour block into BC1 four- or three-colour form. */
    private static void colourBlock(byte[] source, int from, boolean bc1, byte[] chain, int to) {
        var in = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        int c0 = in.getShort(from) & 0xffff, c1 = in.getShort(from + 2) & 0xffff;
        int selectors = in.getInt(from + 4);
        write(chain, to, c0, c1, selectors);
        if (c0 > c1 || bc1) {
            return;
        }
        // Four-colour source with c0 <= c1: as BC1 that would switch to three-colour mode.
        if (c0 == c1) {
            write(chain, to, c0, c1, 0);
            return;
        }
        int remapped = 0;
        for (int p = 0; p < 16; p++) {
            int index = (selectors >>> (p * 2)) & 3;
            remapped |= (index ^ 1) << (p * 2); // 0<->1, 2<->3
        }
        write(chain, to, c1, c0, remapped);
    }

    private static void solid(int[] rgb, byte[] out, int at) {
        int colour = pack(rgb);
        write(out, at, colour, colour, 0);
    }

    private static int[] mean(byte[] rgba, int from, int texels) {
        int[] sum = new int[3];
        for (int p = 0; p < texels; p++) {
            for (int c = 0; c < 3; c++) {
                sum[c] += rgba[(from + p) * 4 + c] & 255;
            }
        }
        return new int[] {
            (sum[0] + texels / 2) / texels,
            (sum[1] + texels / 2) / texels,
            (sum[2] + texels / 2) / texels
        };
    }

    private static byte[] halve(byte[] rgba, int s) {
        int h = Math.max(1, s / 2);
        byte[] out = new byte[h * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < h; x++) {
                for (int c = 0; c < 4; c++) {
                    int sum = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int sx = Math.min(s - 1, 2 * x + dx), sy = Math.min(s - 1, 2 * y + dy);
                            sum += rgba[(sy * s + sx) * 4 + c] & 255;
                        }
                    }
                    out[(y * h + x) * 4 + c] = (byte) ((sum + 2) / 4);
                }
            }
        }
        return out;
    }

    /** Nearest 565 colour of an RGB triple. */
    private static int pack(int[] rgb) {
        int r = (rgb[0] * 31 + 127) / 255,
                g = (rgb[1] * 63 + 127) / 255,
                b = (rgb[2] * 31 + 127) / 255;
        return r << 11 | g << 5 | b;
    }

    private static int[] expand(int colour) {
        int r = (colour >>> 11) & 31, g = (colour >>> 5) & 63, b = colour & 31;
        return new int[] {(r << 3) | (r >>> 2), (g << 2) | (g >>> 4), (b << 3) | (b >>> 2)};
    }

    private static void write(byte[] out, int at, int c0, int c1, int selectors) {
        ByteBuffer.wrap(out)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(at, (short) c0)
                .putShort(at + 2, (short) c1)
                .putInt(at + 4, selectors);
    }
}
