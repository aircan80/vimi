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
 * its own; references are kept per backend). A frame matches when its mean absolute channel
 * difference and the share of pixels differing by more than {@link Tolerance#pixelDelta} in any
 * channel both stay within the tolerance. Pure Java: no GPU.
 */
final class AppearanceImages {
    private AppearanceImages() {}

    /**
     * {@code meanDelta}: largest mean absolute channel difference over all pixels and channels;
     * {@code pixelDelta}: a channel difference above this makes the pixel an outlier; {@code
     * outlierShare}: largest share of outlier pixels.
     */
    record Tolerance(double meanDelta, int pixelDelta, double outlierShare) {
        /** Driver rounding and edge rasterization differences between runs and GPUs. */
        static final Tolerance STANDARD = new Tolerance(1.5, 8, 0.005);

        /**
         * The same frame where each pixel may match its reference within one pixel of its place
         * ({@link #compareNear}): a GPU that places MSAA samples or rasterizes an edge a pixel
         * apart from the one that recorded the reference moves edges, not regions. A lost texture,
         * a wrong light, a changed gradient, a missing surface or a region drawn differently still
         * fails.
         */
        static final Tolerance NEAR = new Tolerance(1.5, 8, 0.005);
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
     * As {@link #compare}, except that a pixel is an outlier only when no reference pixel within
     * one pixel of its place (its 3 x 3 neighbourhood, clipped at the frame) is within {@code
     * pixelDelta} in every channel. The mean and maximum are over each pixel's best match.
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
                int i = y * width + x;
                // The reference pixel within one pixel that this one matches best.
                int bestWorst = Integer.MAX_VALUE, bestSum = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                            continue;
                        }
                        int other = reference[ny * width + nx], worst = 0, total = 0;
                        for (int shift = 0; shift < 32; shift += 8) {
                            int d = Math.abs((actual[i] >>> shift & 255) - (other >>> shift & 255));
                            worst = Math.max(worst, d);
                            total += d;
                        }
                        if (worst < bestWorst || worst == bestWorst && total < bestSum) {
                            bestWorst = worst;
                            bestSum = total;
                        }
                    }
                }
                sum += bestSum;
                max = Math.max(max, bestWorst);
                if (bestWorst > pixelDelta) {
                    outliers++;
                }
            }
        }
        return new Difference(
                sum / (actual.length * 4.0), outliers / (double) actual.length, max, true);
    }

    /**
     * Whether a frame matches its reference: within {@link Tolerance#STANDARD} pixel by pixel, or
     * within {@link Tolerance#NEAR} where each pixel may match within one pixel of its place.
     */
    static boolean matches(int[] actual, int[] reference, int width, int height) {
        return compare(actual, reference, Tolerance.STANDARD.pixelDelta())
                        .matches(Tolerance.STANDARD)
                || compareNear(actual, reference, width, height, Tolerance.NEAR.pixelDelta())
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
