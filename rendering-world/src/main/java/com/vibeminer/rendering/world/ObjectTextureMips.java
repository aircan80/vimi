package com.vibeminer.rendering.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Full RGBA8 mip chains for World object textures (issue #265): buildings, cutout and blended
 * surfaces, doodads and models.
 *
 * <p>A chain runs from the drawn top level down to 1 x 1, with sides {@code max(1, side >> k)},
 * tightly packed in level order, which is the layout {@code bgfx_create_texture_2d} takes for a
 * mipmapped texture. Levels the source file carries are used as authored; only the levels it lacks
 * (or that fail to decode) are generated, by an alpha-weighted 2 x 2 box filter so transparent
 * texels never darken their neighbours.
 *
 * <p>Cutout surfaces are alpha-tested at {@link #CUTOUT_REFERENCE}. Averaging alpha shrinks the
 * texels that pass the test at every level, so leaves and grates thin out and vanish at distance.
 * For cutout chains every level below the top is therefore rescaled so the fraction of texels at or
 * above the reference matches the top level ("coverage-preserving" mips). Opaque chains are forced
 * to alpha 255; blended chains keep their averaged alpha, which is what blending expects.
 */
public final class ObjectTextureMips {
    /** Alpha-test reference of cutout object surfaces; the object pass uses the same value. */
    public static final float CUTOUT_REFERENCE = 0.5f;

    /** Cutout passes at {@code alpha >= 128}: the 8-bit value the shader's 0.5 test accepts. */
    static final int CUTOUT_ALPHA = 128;

    /** Material classes, matching the World objects file. */
    public static final int OPAQUE = 0, CUTOUT = 1, BLENDED = 2;

    /** Where a chain's levels came from. */
    public enum Source {
        /** Every level below the top was decoded from the file's own mips. */
        FILE,
        /** The file had some lower mips; the rest were generated. */
        PARTIAL,
        /** The file had no usable lower mips; all were generated. */
        GENERATED,
        /** The top level is already 1 x 1. */
        SINGLE
    }

    /** A packed chain and where its lower levels came from. */
    public record Chain(int width, int height, byte[] rgba, Source source) {
        public Chain {
            Objects.requireNonNull(rgba);
            Objects.requireNonNull(source);
            if (rgba.length != (long) chainPixels(width, height) * 4) {
                throw new IllegalArgumentException("Mip chain size");
            }
        }

        public int levels() {
            return ObjectTextureMips.levels(width, height);
        }
    }

    /** Supplies the file's level {@code k} below the top, or null when it has none or it fails. */
    @FunctionalInterface
    public interface FileLevel {
        byte[] rgba(int below, int width, int height);
    }

    private ObjectTextureMips() {}

    /** Levels from {@code width x height} down to 1 x 1. */
    public static int levels(int width, int height) {
        check(width, height);
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    public static int levelWidth(int width, int level) {
        return Math.max(1, width >> level);
    }

    /** Pixels in the whole chain: at most about 4/3 of the top level. */
    public static int chainPixels(int width, int height) {
        int levels = levels(width, height), total = 0;
        for (int k = 0; k < levels; k++) {
            total += levelWidth(width, k) * levelWidth(height, k);
        }
        return total;
    }

    /** Byte offset of {@code level} in a packed chain. */
    public static int levelOffset(int width, int height, int level) {
        int offset = 0;
        for (int k = 0; k < level; k++) {
            offset += levelWidth(width, k) * levelWidth(height, k) * 4;
        }
        return offset;
    }

    /**
     * Builds the chain below {@code top} (which this method copies, not adopts). Lower levels come
     * from {@code file} while it supplies correctly sized data, then are generated; the material
     * class then fixes alpha as described on the class.
     */
    public static Chain build(
            int width,
            int height,
            byte[] top,
            int materialClass,
            FileLevel file,
            BooleanSupplier canceled) {
        int levels = levels(width, height);
        Objects.requireNonNull(top);
        Objects.requireNonNull(file);
        if (top.length != width * height * 4 || materialClass < OPAQUE || materialClass > BLENDED) {
            throw new IllegalArgumentException("Mip chain input");
        }
        byte[] chain = new byte[chainPixels(width, height) * 4];
        System.arraycopy(top, 0, chain, 0, top.length);
        int fromFile = 0;
        boolean fileOpen = true;
        for (int k = 1; k < levels; k++) {
            stop(canceled);
            int w = levelWidth(width, k), h = levelWidth(height, k);
            int at = levelOffset(width, height, k);
            byte[] authored = fileOpen ? file.rgba(k, w, h) : null;
            if (authored != null && authored.length == w * h * 4) {
                System.arraycopy(authored, 0, chain, at, authored.length);
                fromFile++;
            } else {
                // Once a file level is missing or wrong, the rest derive from what is here.
                fileOpen = false;
                downsample(
                        chain,
                        levelOffset(width, height, k - 1),
                        levelWidth(width, k - 1),
                        levelWidth(height, k - 1),
                        at,
                        w,
                        h);
            }
        }
        switch (materialClass) {
            case OPAQUE -> {
                for (int i = 3; i < chain.length; i += 4) {
                    chain[i] = (byte) 255;
                }
            }
            case CUTOUT -> preserveCoverage(chain, width, height, canceled);
            default -> {}
        }
        Source source =
                levels == 1
                        ? Source.SINGLE
                        : fromFile == levels - 1
                                ? Source.FILE
                                : fromFile == 0 ? Source.GENERATED : Source.PARTIAL;
        return new Chain(width, height, chain, source);
    }

    /** Alpha-weighted 2 x 2 (or 2 x 1 / 1 x 2 at a one-texel side) box filter of one level. */
    static void downsample(
            byte[] chain, int from, int fromWidth, int fromHeight, int to, int width, int height) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int weight = 0, count = 0, r = 0, g = 0, b = 0, plainR = 0, plainG = 0, plainB = 0;
                int alpha = 0;
                for (int dy = 0; dy < 2; dy++) {
                    int sy = Math.min(fromHeight - 1, y * 2 + dy);
                    if (dy == 1 && fromHeight == 1) {
                        break;
                    }
                    for (int dx = 0; dx < 2; dx++) {
                        if (dx == 1 && fromWidth == 1) {
                            break;
                        }
                        int sx = Math.min(fromWidth - 1, x * 2 + dx);
                        int at = from + (sy * fromWidth + sx) * 4;
                        int a = chain[at + 3] & 255;
                        int cr = chain[at] & 255,
                                cg = chain[at + 1] & 255,
                                cb = chain[at + 2] & 255;
                        weight += a;
                        r += cr * a;
                        g += cg * a;
                        b += cb * a;
                        plainR += cr;
                        plainG += cg;
                        plainB += cb;
                        alpha += a;
                        count++;
                    }
                }
                int out = to + (y * width + x) * 4;
                if (weight > 0) {
                    chain[out] = (byte) ((r + weight / 2) / weight);
                    chain[out + 1] = (byte) ((g + weight / 2) / weight);
                    chain[out + 2] = (byte) ((b + weight / 2) / weight);
                } else {
                    chain[out] = (byte) ((plainR + count / 2) / count);
                    chain[out + 1] = (byte) ((plainG + count / 2) / count);
                    chain[out + 2] = (byte) ((plainB + count / 2) / count);
                }
                chain[out + 3] = (byte) ((alpha + count / 2) / count);
            }
        }
    }

    /**
     * Rescales the alpha of every level below the top so the fraction of texels that pass the
     * cutout test matches the top level's, within one texel's worth of ties.
     */
    static void preserveCoverage(byte[] chain, int width, int height, BooleanSupplier canceled) {
        int topPixels = width * height, passing = 0;
        for (int i = 3; i < topPixels * 4; i += 4) {
            if ((chain[i] & 255) >= CUTOUT_ALPHA) {
                passing++;
            }
        }
        double coverage = (double) passing / topPixels;
        int levels = levels(width, height);
        for (int k = 1; k < levels; k++) {
            stop(canceled);
            int at = levelOffset(width, height, k);
            int pixels = levelWidth(width, k) * levelWidth(height, k);
            matchCoverage(chain, at, pixels, coverage);
        }
    }

    /**
     * Fraction of {@code pixels} RGBA texels from {@code at} whose alpha passes the cutout test.
     */
    public static double coverage(byte[] rgba, int at, int pixels) {
        int passing = 0;
        for (int i = 0; i < pixels; i++) {
            if ((rgba[at + i * 4 + 3] & 255) >= CUTOUT_ALPHA) {
                passing++;
            }
        }
        return pixels == 0 ? 0 : (double) passing / pixels;
    }

    /**
     * Rescales the alpha of one level ({@code pixels} RGBA texels from {@code at}) so the fraction
     * passing the cutout test is {@code coverage}, within one texel's worth of ties. Used for the
     * chain's lower levels and by callers that reduce a larger source to a cutout top level.
     */
    public static void matchCoverage(byte[] rgba, int at, int pixels, double coverage) {
        if (!(coverage >= 0 && coverage <= 1)
                || at < 0
                || pixels < 0
                || (long) at + (long) pixels * 4 > rgba.length) {
            throw new IllegalArgumentException("Coverage input");
        }
        int[] histogram = new int[256];
        for (int i = 0; i < pixels; i++) {
            histogram[rgba[at + i * 4 + 3] & 255]++;
        }
        int wanted = (int) Math.round(coverage * pixels);
        if (wanted == 0) {
            // Nothing passes: keep every texel below the reference.
            scale(rgba, at, pixels, 256);
            return;
        }
        // The alpha of the wanted-th most opaque texel becomes the new reference. Texels that tie
        // on it all pass or all fail, whichever lands nearer the wanted count.
        int seen = 0, threshold = 0;
        for (int a = 255; a >= 0; a--) {
            seen += histogram[a];
            if (seen >= wanted) {
                int above = seen - histogram[a];
                threshold = wanted - above < seen - wanted ? a + 1 : a;
                break;
            }
        }
        scale(rgba, at, pixels, threshold);
    }

    /**
     * Maps alpha {@code threshold} to the cutout reference: at or above passes, below fails. A zero
     * threshold means every texel passes; a threshold of 256 (none) means none does.
     */
    private static void scale(byte[] chain, int at, int pixels, int threshold) {
        for (int i = 0; i < pixels; i++) {
            int index = at + i * 4 + 3;
            int a = chain[index] & 255;
            int scaled;
            if (threshold == 0) {
                // Everything was wanted, including fully transparent texels: all pass.
                scaled = Math.max(a, CUTOUT_ALPHA);
            } else if (a >= threshold) {
                scaled = Math.min(255, Math.max(CUTOUT_ALPHA, a * CUTOUT_ALPHA / threshold));
            } else {
                scaled = Math.min(CUTOUT_ALPHA - 1, a * CUTOUT_ALPHA / threshold);
            }
            chain[index] = (byte) scaled;
        }
    }

    /** The chain's levels as separate arrays, top first; for tests and diagnostics. */
    public static List<byte[]> split(Chain chain) {
        var out = new ArrayList<byte[]>();
        for (int k = 0; k < chain.levels(); k++) {
            int at = levelOffset(chain.width(), chain.height(), k);
            int bytes = levelWidth(chain.width(), k) * levelWidth(chain.height(), k) * 4;
            out.add(java.util.Arrays.copyOfRange(chain.rgba(), at, at + bytes));
        }
        return out;
    }

    private static void check(int width, int height) {
        if (width < 1 || height < 1 || width > 1 << 14 || height > 1 << 14) {
            throw new IllegalArgumentException("Mip chain side");
        }
    }

    private static void stop(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
