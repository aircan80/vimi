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
}
