package com.vibeminer.rendering.world;

import java.nio.ByteBuffer;

/**
 * Storage formats of World object texture mip chains (issue #374): RGBA8 texels, or the BC1, BC2
 * and BC3 blocks a BLP file carries, uploaded untouched where the GPU samples them.
 *
 * <p>Every format stores a full chain, top level down to 1 x 1 with sides {@code max(1, side >>
 * k)}, tightly packed in level order, as {@link ObjectTextureMips} does for RGBA8. A compressed
 * level is {@code ceil(w / 4) x ceil(h / 4)} blocks of 8 (BC1) or 16 (BC2, BC3) bytes in row-major
 * order, which is the layout bgfx takes for a compressed texture.
 *
 * <p>{@link #decodeLevel} follows GPU sampling, so the RGBA8 fallback, picking and the GPU agree:
 * BC1 in its three-colour mode ({@code c0 <= c1}) decodes index 3 as transparent black, and BC2 and
 * BC3 always use four colours.
 */
public final class ObjectTextureFormats {
    /** Format codes, as the World objects file stores them. */
    public static final int RGBA8 = 0, BC1 = 1, BC2 = 2, BC3 = 3;

    private ObjectTextureFormats() {}

    public static boolean valid(int format) {
        return format >= RGBA8 && format <= BC3;
    }

    public static boolean compressed(int format) {
        return format != RGBA8;
    }

    /** Bytes of one 4 x 4 block; for RGBA8, bytes of one texel. */
    public static int unitBytes(int format) {
        return switch (check(format)) {
            case RGBA8 -> 4;
            case BC1 -> 8;
            default -> 16;
        };
    }

    /** Texel rows one row of upload units spans: 1 for RGBA8, 4 for a block row. */
    public static int unitRows(int format) {
        return compressed(check(format)) ? 4 : 1;
    }

    /** Bytes of one row of units of a level {@code levelWidth} texels wide. */
    public static int pitch(int format, int levelWidth) {
        int units = compressed(check(format)) ? (levelWidth + 3) / 4 : levelWidth;
        return units * unitBytes(format);
    }

    /** Rows of units of a level {@code levelHeight} texels high. */
    public static int unitRowCount(int format, int levelHeight) {
        return (levelHeight + unitRows(format) - 1) / unitRows(format);
    }

    public static int levelBytes(int format, int width, int height, int level) {
        int w = ObjectTextureMips.levelWidth(width, level);
        int h = ObjectTextureMips.levelWidth(height, level);
        return pitch(format, w) * unitRowCount(format, h);
    }

    /** Byte offset of {@code level} in a packed chain. */
    public static int levelOffset(int format, int width, int height, int level) {
        int offset = 0;
        for (int k = 0; k < level; k++) {
            offset += levelBytes(format, width, height, k);
        }
        return offset;
    }

    /** Bytes of the whole chain. */
    public static int chainBytes(int format, int width, int height) {
        return levelOffset(format, width, height, ObjectTextureMips.levels(width, height));
    }

    /**
     * RGBA8 texels of one level: {@code width x height} (the level's own sides) read from {@code
     * source} at absolute byte {@code offset}.
     */
    public static byte[] decodeLevel(
            int format, int width, int height, ByteBuffer source, int offset) {
        check(format);
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Object texture level size");
        }
        byte[] rgba = new byte[width * height * 4];
        if (format == RGBA8) {
            source.get(offset, rgba);
            return rgba;
        }
        int blockBytes = unitBytes(format), wide = (width + 3) / 4, high = (height + 3) / 4;
        int[] colors = new int[16], alphas = new int[8];
        for (int by = 0; by < high; by++) {
            for (int bx = 0; bx < wide; bx++) {
                int at = offset + (by * wide + bx) * blockBytes;
                int colorAt = format == BC1 ? at : at + 8;
                int c0 = ushort(source, colorAt), c1 = ushort(source, colorAt + 2);
                color565(c0, colors, 0);
                color565(c1, colors, 4);
                boolean four = format != BC1 || c0 > c1;
                for (int ch = 0; ch < 3; ch++) {
                    colors[8 + ch] =
                            four
                                    ? (2 * colors[ch] + colors[4 + ch] + 1) / 3
                                    : (colors[ch] + colors[4 + ch] + 1) / 2;
                    colors[12 + ch] = four ? (colors[ch] + 2 * colors[4 + ch] + 1) / 3 : 0;
                }
                colors[11] = 255;
                colors[15] = four ? 255 : 0;
                long selectors =
                        ushort(source, colorAt + 4) | (long) ushort(source, colorAt + 6) << 16;
                long alphaSelectors = 0;
                if (format == BC3) {
                    alphas[0] = source.get(at) & 255;
                    alphas[1] = source.get(at + 1) & 255;
                    if (alphas[0] > alphas[1]) {
                        for (int i = 2; i < 8; i++) {
                            alphas[i] = ((8 - i) * alphas[0] + (i - 1) * alphas[1] + 3) / 7;
                        }
                    } else {
                        for (int i = 2; i < 6; i++) {
                            alphas[i] = ((6 - i) * alphas[0] + (i - 1) * alphas[1] + 2) / 5;
                        }
                        alphas[6] = 0;
                        alphas[7] = 255;
                    }
                    for (int i = 0; i < 6; i++) {
                        alphaSelectors |= (source.get(at + 2 + i) & 255L) << (8 * i);
                    }
                }
                for (int py = 0; py < 4 && by * 4 + py < height; py++) {
                    for (int px = 0; px < 4 && bx * 4 + px < width; px++) {
                        int pixel = py * 4 + px;
                        int choice = (int) ((selectors >>> (pixel * 2)) & 3);
                        int target = ((by * 4 + py) * width + bx * 4 + px) * 4;
                        for (int ch = 0; ch < 4; ch++) {
                            rgba[target + ch] = (byte) colors[choice * 4 + ch];
                        }
                        if (format == BC2) {
                            rgba[target + 3] =
                                    (byte)
                                            (((source.get(at + pixel / 2) >>> (pixel % 2 * 4)) & 15)
                                                    * 17);
                        } else if (format == BC3) {
                            rgba[target + 3] =
                                    (byte) alphas[(int) ((alphaSelectors >>> (pixel * 3)) & 7)];
                        }
                    }
                }
            }
        }
        return rgba;
    }

    /**
     * The whole chain as RGBA8 in the {@link ObjectTextureMips} layout, decoded from the chain at
     * absolute byte {@code offset} of {@code source}.
     */
    public static byte[] decodeChain(
            int format, int width, int height, ByteBuffer source, int offset) {
        byte[] chain = new byte[ObjectTextureMips.chainPixels(width, height) * 4];
        int levels = ObjectTextureMips.levels(width, height);
        for (int k = 0; k < levels; k++) {
            int w = ObjectTextureMips.levelWidth(width, k),
                    h = ObjectTextureMips.levelWidth(height, k);
            byte[] level =
                    decodeLevel(
                            format, w, h, source, offset + levelOffset(format, width, height, k));
            System.arraycopy(
                    level, 0, chain, ObjectTextureMips.levelOffset(width, height, k), level.length);
        }
        return chain;
    }

    private static int ushort(ByteBuffer source, int at) {
        return (source.get(at) & 255) | (source.get(at + 1) & 255) << 8;
    }

    private static void color565(int value, int[] colors, int at) {
        int r = (value >>> 11) & 31, g = (value >>> 5) & 63, b = value & 31;
        colors[at] = (r << 3) | (r >>> 2);
        colors[at + 1] = (g << 2) | (g >>> 4);
        colors[at + 2] = (b << 3) | (b >>> 2);
        colors[at + 3] = 255;
    }

    private static int check(int format) {
        if (!valid(format)) {
            throw new IllegalArgumentException("Object texture format");
        }
        return format;
    }
}
