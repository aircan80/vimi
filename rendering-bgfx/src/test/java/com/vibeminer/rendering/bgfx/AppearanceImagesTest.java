package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppearanceImagesTest {
    private static int[] flat(int count, int argb) {
        int[] pixels = new int[count];
        java.util.Arrays.fill(pixels, argb);
        return pixels;
    }

    @Test
    void identicalFramesMatchAndSmallNoiseStaysWithinTheTolerance() {
        int[] reference = flat(1000, 0xff336699);
        var same = AppearanceImages.compare(reference.clone(), reference, 8);
        assertEquals(0, same.meanDelta());
        assertTrue(same.matches(AppearanceImages.Tolerance.STANDARD));
        // Every pixel off by 2 in one channel: mean 0.5, no outliers.
        int[] noisy = flat(1000, 0xff336699 + 2);
        var noise = AppearanceImages.compare(noisy, reference, 8);
        assertEquals(0.5, noise.meanDelta(), 1e-9);
        assertEquals(0, noise.outlierShare());
        assertTrue(noise.matches(AppearanceImages.Tolerance.STANDARD));
    }

    @Test
    void aWrongRegionOrALostTextureFails() {
        int[] reference = flat(1000, 0xff336699);
        // Ten pixels (1 %) of a different colour: an outlier share above 0.5 %.
        int[] region = reference.clone();
        for (int i = 0; i < 10; i++) {
            region[i] = 0xffff0000;
        }
        var wrong = AppearanceImages.compare(region, reference, 8);
        assertEquals(0.01, wrong.outlierShare(), 1e-9);
        assertFalse(wrong.matches(AppearanceImages.Tolerance.STANDARD));
        // A uniformly darker frame (lost texture or light): a large mean.
        var darker = AppearanceImages.compare(flat(1000, 0xff112244), reference, 8);
        assertFalse(darker.matches(AppearanceImages.Tolerance.STANDARD));
        // Different sizes never match.
        assertFalse(
                AppearanceImages.compare(flat(999, 0xff336699), reference, 8)
                        .matches(AppearanceImages.Tolerance.STANDARD));
    }

    @Test
    void referencesRoundTripLosslessly(@TempDir Path directory) throws Exception {
        int[] pixels = {0xff010203, 0x80fffefd, 0x00000000, 0xff7f8081, 0xffabcdef, 0x12345678};
        var file = directory.resolve("vulkan/scene.png");
        assertNull(AppearanceImages.read(file, 3, 2), "no reference yet");
        AppearanceImages.write(file, 3, 2, pixels);
        assertArrayEquals(pixels, AppearanceImages.read(file, 3, 2));
        assertEquals(0, AppearanceImages.read(file, 2, 3).length, "a size mismatch reads empty");
        int[] diff = AppearanceImages.diff(new int[] {0xff000010}, new int[] {0xff000000});
        assertEquals(0xff000080, diff[0], "differences are scaled up to be visible");
    }

    /** A frame of a dark rectangle on a light ground, its left edge at {@code edge}. */
    private static int[] rectangle(int width, int height, int edge) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean inside = x >= edge && x < width - 40 && y >= 40 && y < height - 40;
                pixels[y * width + x] = inside ? 0xff102030 : 0xffa0b0c0;
            }
        }
        return pixels;
    }

    @Test
    void anEdgeAPixelApartIsWithinTheNearToleranceButAMissingRegionIsNot() {
        int width = 480, height = 300;
        // Many edges (stripes 8 pixels wide) that another GPU rasterizes one pixel to the right:
        // an eighth of the pixels are outliers over the pixel tolerance, but each still matches
        // the reference a pixel away.
        int[] stripes = new int[width * height], moved = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                stripes[y * width + x] = (x / 8) % 2 == 0 ? 0xff102030 : 0xffa0b0c0;
                moved[y * width + x] = (Math.max(0, x - 1) / 8) % 2 == 0 ? 0xff102030 : 0xffa0b0c0;
            }
        }
        assertFalse(
                AppearanceImages.compare(moved, stripes, 8)
                        .matches(AppearanceImages.Tolerance.STANDARD));
        assertTrue(AppearanceImages.matches(moved, stripes, width, height));
        int[] reference = rectangle(width, height, 41);
        int[] shifted = rectangle(width, height, 42);
        assertTrue(AppearanceImages.matches(shifted, reference, width, height));
        // A missing region (the rectangle not drawn at all) fails both ways.
        int[] missing = rectangle(width, height, width);
        assertFalse(AppearanceImages.matches(missing, reference, width, height));
        // The whole rectangle a pixel lower too: every edge moves, still within a pixel.
        int[] lower = new int[reference.length];
        System.arraycopy(shifted, 0, lower, width, shifted.length - width);
        System.arraycopy(shifted, 0, lower, 0, width);
        assertTrue(AppearanceImages.matches(lower, reference, width, height));
        // A gradient changed by 10 in one channel everywhere (a different sky) fails.
        int[] sky = reference.clone();
        for (int i = 0; i < sky.length; i++) {
            sky[i] += 10;
        }
        assertFalse(AppearanceImages.matches(sky, reference, width, height));
        // A uniformly darker frame (a wrong light) fails both ways.
        int[] darker = reference.clone();
        for (int i = 0; i < darker.length; i++) {
            darker[i] -= 0x00101010;
        }
        assertFalse(AppearanceImages.matches(darker, reference, width, height));
        // A region of 40 x 40 pixels drawn differently (a lost texture on a small quad).
        int[] patch = reference.clone();
        for (int y = 100; y < 140; y++) {
            for (int x = 200; x < 240; x++) {
                patch[y * width + x] = 0xffff0000;
            }
        }
        assertFalse(AppearanceImages.matches(patch, reference, width, height));
        // Sizes that differ never match.
        assertFalse(AppearanceImages.matches(new int[10], reference, width, height));
    }

    @Test
    void aOnePixelLineThatAppearsOrDisappearsFailsAndAWholeShiftPasses() {
        int width = 480, height = 300;
        int[] ground = new int[width * height];
        java.util.Arrays.fill(ground, 0xffa0b0c0);
        int[] line = ground.clone();
        for (int x = 0; x < width; x++) {
            line[150 * width + x] = 0xff102030;
        }
        // Within the old pixel tolerance (0.33 % of the pixels), but a feature: it must fail.
        assertTrue(
                AppearanceImages.compare(line, ground, 8)
                        .matches(AppearanceImages.Tolerance.STANDARD));
        assertFalse(AppearanceImages.matches(line, ground, width, height), "a line appears");
        assertFalse(AppearanceImages.matches(ground, line, width, height), "a line disappears");
        // One way alone misses the disappearing line: every background pixel matches beside it.
        assertEquals(
                0, AppearanceImages.compareNear(ground, line, width, height, 8).outlierShare());
        // The same line one pixel lower: an edge a pixel apart, which passes.
        int[] lower = ground.clone();
        for (int x = 0; x < width; x++) {
            lower[151 * width + x] = 0xff102030;
        }
        assertTrue(AppearanceImages.matches(lower, line, width, height));
    }

    @Test
    void anEdgeBlendedWithOtherCoverageMatches() {
        // Vertical edges, black to white, every 16 columns, each with one blended column: 25 %
        // white in the reference, 50 % in the frame (another GPU's MSAA coverage).
        int width = 480, height = 300;
        int[] reference = new int[width * height], actual = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int phase = x % 16;
                int quarter = phase == 8 ? 64 : phase > 8 ? 255 : 0;
                int half = phase == 8 ? 128 : phase > 8 ? 255 : 0;
                reference[y * width + x] = 0xff000000 | quarter * 0x010101;
                actual[y * width + x] = 0xff000000 | half * 0x010101;
            }
        }
        // Each blended pixel is 64 from every single reference pixel near it: a whole column of
        // outliers per edge, far over the share, if blends had to match one neighbour.
        assertFalse(
                AppearanceImages.compare(actual, reference, 8)
                        .matches(AppearanceImages.Tolerance.NEAR));
        assertTrue(AppearanceImages.matches(actual, reference, width, height));
        assertTrue(AppearanceImages.matches(reference, actual, width, height));
    }
}
