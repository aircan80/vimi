package com.vimi.gui;

import java.nio.IntBuffer;
import java.util.concurrent.CancellationException;

/** Authored shader-row checks, not a geometry/source certificate or native GPU proof. */
public final class WorldStreamBackgroundTest {
    public static void main(String[] args) {
        int width = 17, height = 29;
        int[] rgba = new int[width * height + 5];
        for (int y = 0; y < height; y++) {
            double t = (double) y / height;
            int color = 0xff000000 | (int) (91 + 80 * t) << 16
                    | (int) (141 + 60 * t) << 8 | (int) (177 + 37 * t);
            java.util.Arrays.fill(rgba, 3 + y * width, 3 + (y + 1) * width, color);
        }
        var pixels = IntBuffer.wrap(rgba).position(3).limit(3 + width * height).asReadOnlyBuffer();
        require(WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> false),
                "Exact shader sky has no visible terrain changes");
        require(pixels.position() == 3 && pixels.limit() == 3 + width * height,
                "Background scan preserves caller cursor");
        rgba[3 + 10 * width] ^= 0x00000001;
        require(WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> false),
                "One-byte quantization tolerance");
        int sky = rgba[3 + 8 * width];
        rgba[3 + 8 * width] = 0xff25651a;
        require(!WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> false),
                "A sampled foreground difference is not discarded as sky");
        rgba[3 + 8 * width] = sky;
        int step = WorldStreamBackground.STEP;
        int[] clean = rgba.clone();
        rgba[3 + 10 * width + 1] = 0xff25651a;
        require(WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> false),
                "A lone pixel between samples is below the subsampled resolution");
        // Any foreground block STEP pixels across, at every placement, is seen.
        for (int top = 0; top + step <= height; top++) {
            for (int left = 0; left + step <= width; left++) {
                System.arraycopy(clean, 0, rgba, 0, clean.length);
                for (int y = top; y < top + step; y++) {
                    java.util.Arrays.fill(rgba, 3 + y * width + left, 3 + y * width + left + step,
                            0xff25651a);
                }
                require(!WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> false),
                        "Foreground block at " + left + "," + top + " is not discarded as sky");
            }
        }
        System.arraycopy(clean, 0, rgba, 0, clean.length);
        try {
            WorldStreamBackground.matchesBasicSky(pixels, width, height, () -> true);
            throw new AssertionError("Stopped scan must cancel");
        } catch (CancellationException expected) { }
        try {
            WorldStreamBackground.matchesBasicSky(pixels, Integer.MAX_VALUE, height, () -> false);
            throw new AssertionError("Dimensions must reject before pixel access");
        } catch (IllegalArgumentException expected) { }
        System.out.println("World background feedback: shader rows, foreground, cursor and cancellation passed");
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private WorldStreamBackgroundTest() {}
}
