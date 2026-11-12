package com.vibeminer.rendering.bgfx;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Reference-image comparison for the World appearance proofs (#379). Frames are compared pixel by
 * pixel, channel by channel, as the 32-bit values the helper returned (a backend's channel order is
 * its own; references are kept per backend). A frame matches ({@link #matches}) when, compared
 * within one pixel of each place in both directions, its mean absolute channel difference and the
 * share of pixels differing by more than {@link Tolerance#pixelDelta} in any channel both stay
 * within {@link Tolerance#NEAR}. Pure Java: no GPU.
 */
final class AppearanceImages {
    private AppearanceImages() {}

    /**
     * {@code meanDelta}: largest mean absolute channel difference over all pixels and channels;
     * {@code pixelDelta}: a channel difference above this makes the pixel an outlier; {@code
     * outlierShare}: largest share of outlier pixels.
     */
    record Tolerance(double meanDelta, int pixelDelta, double outlierShare) {
        /**
         * Pixel-by-pixel limits for driver rounding. The proof prints this measurement for
         * comparison only: its outlier share lets a line one pixel wide appear or disappear.
         */
        static final Tolerance STANDARD = new Tolerance(1.5, 8, 0.005);

        /**
         * The frame compared within a pixel both ways: every pixel of each frame must lie within
         * the colour range of the other's 3 x 3 neighbourhood around its place (see {@link
         * AppearanceImages#compareNearBothWays}), so a GPU that blends MSAA edges with other
         * coverage or rasterizes an edge a pixel apart passes, while a feature that appears or
         * disappears, even a line one pixel wide, does not. Its outlier share is a tenth of the
         * pixel tolerance's, so about 70 pixels of such a feature in a 480 x 300 frame already
         * fail.
         */
        static final Tolerance NEAR = new Tolerance(1.5, 8, 0.0005);
    }

    /** How a frame differs from its reference; {@code matches} applies the tolerance. */
    record Difference(double meanDelta, double outlierShare, int maxDelta, boolean sameSize) {
        boolean matches(Tolerance tolerance) {
            return sameSize
                    && meanDelta <= tolerance.meanDelta()
                    && outlierShare <= tolerance.outlierShare();
        }

        @Override
        public String toString() {
            return sameSize
                    ? String.format(
                            "mean %.3f, outliers %.4f%%, max %d",
                            meanDelta, outlierShare * 100, maxDelta)
                    : "size differs";
        }
    }

    static Difference compare(int[] actual, int[] reference, int pixelDelta) {
        if (actual.length != reference.length || actual.length == 0) {
            return new Difference(Double.MAX_VALUE, 1, 255, false);
        }
        long sum = 0;
        int outliers = 0, max = 0;
        for (int i = 0; i < actual.length; i++) {
            int worst = 0;
            for (int shift = 0; shift < 32; shift += 8) {
                int d = Math.abs((actual[i] >>> shift & 255) - (reference[i] >>> shift & 255));
                sum += d;
                worst = Math.max(worst, d);
            }
            max = Math.max(max, worst);
            if (worst > pixelDelta) {
                outliers++;
            }
        }
        return new Difference(
                sum / (actual.length * 4.0), outliers / (double) actual.length, max, true);
    }

    /**
     * As {@link #compare}, except that each pixel is measured against the envelope of the reference
     * within one pixel of its place: per channel, the lowest to the highest value of its 3 x 3
     * neighbourhood (clipped at the frame). A channel inside that range differs by 0, one outside
     * it by its distance to the nearer end; a pixel is an outlier when a channel lies more than
     * {@code pixelDelta} outside. An edge rasterized a pixel apart, or blended with a different
     * coverage (another GPU's MSAA sample pattern), stays between the colours on either side of the
     * edge and passes; a colour the reference has nowhere near does not.
     */
    static Difference compareNear(
            int[] actual, int[] reference, int width, int height, int pixelDelta) {
        if (actual.length != reference.length
                || actual.length == 0
                || actual.length != width * height) {
            return new Difference(Double.MAX_VALUE, 1, 255, false);
        }
        long sum = 0;
        int outliers = 0, max = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = actual[y * width + x], worst = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int low = 255, high = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                                continue;
                            }
                            int value = reference[ny * width + nx] >>> shift & 255;
                            low = Math.min(low, value);
                            high = Math.max(high, value);
                        }
                    }
                    int value = pixel >>> shift & 255;
                    int d = Math.max(0, Math.max(value - high, low - value));
                    sum += d;
                    worst = Math.max(worst, d);
                }
                max = Math.max(max, worst);
                if (worst > pixelDelta) {
                    outliers++;
                }
            }
        }
        return new Difference(
                sum / (actual.length * 4.0), outliers / (double) actual.length, max, true);
    }

    /**
     * {@link #compareNear} both ways, actual against reference and reference against actual: the
     * larger mean, outlier share and maximum. One way alone misses a thin feature that disappears
     * (each remaining pixel still finds a match beside it).
     */
    static Difference compareNearBothWays(
            int[] actual, int[] reference, int width, int height, int pixelDelta) {
        var forward = compareNear(actual, reference, width, height, pixelDelta);
        var backward = compareNear(reference, actual, width, height, pixelDelta);
        return new Difference(
                Math.max(forward.meanDelta(), backward.meanDelta()),
                Math.max(forward.outlierShare(), backward.outlierShare()),
                Math.max(forward.maxDelta(), backward.maxDelta()),
                forward.sameSize() && backward.sameSize());
    }

    /**
     * Whether a frame matches its reference: within {@link Tolerance#NEAR}, both ways ({@link
     * #compareNearBothWays}). Identical frames, driver rounding and edges a pixel apart pass; a
     * changed gradient, light or texture, and a feature that appears or disappears (down to a line
     * one pixel wide across the frame) fail.
     */
    static boolean matches(int[] actual, int[] reference, int width, int height) {
        return compareNearBothWays(actual, reference, width, height, Tolerance.NEAR.pixelDelta())
                .matches(Tolerance.NEAR);
    }

    static int[] pixels(IntBuffer frame) {
        int[] out = new int[frame.remaining()];
        frame.duplicate().get(out);
        return out;
    }

    /** Writes the frame's 32-bit values as an ARGB PNG (a lossless store, not a colour claim). */
    static void write(Path file, int width, int height, int[] pixels) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer");
        }
    }

    /** The stored values, or null when there is no reference yet. */
    static int[] read(Path file, int width, int height) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        var image = ImageIO.read(file.toFile());
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            return new int[0];
        }
        return image.getRGB(0, 0, width, height, null, 0, width);
    }

    /** Per-pixel absolute difference, scaled up so small differences are visible. */
    static int[] diff(int[] actual, int[] reference) {
        int[] out = new int[actual.length];
        for (int i = 0; i < actual.length && i < reference.length; i++) {
            int value = 0;
            for (int shift = 0; shift < 24; shift += 8) {
                int d = Math.abs((actual[i] >>> shift & 255) - (reference[i] >>> shift & 255));
                value |= Math.min(255, d * 8) << shift;
            }
            out[i] = 0xff000000 | value;
        }
        return out;
    }
}
