package com.vimi.gui;

import java.nio.IntBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Display feedback for the existing BASIC terrain sky shader; grants no source authority. */
final class WorldStreamBackground {
    private WorldStreamBackground() {}

    /**
     * Samples every {@code STEP}th row and column plus the last row and column: terrain at least
     * {@code STEP} pixels across, or any band along a frame edge, is always seen, at about 1/16
     * of a full scan's cost.
     */
    static final int STEP = 4;

    /** The sampled coordinate after {@code i}: steps of {@code STEP}, always ending at n-1. */
    static int nextSample(int i, int n) {
        return i == n - 1 ? n : Math.min(i + STEP, n - 1);
    }

    /** Off FX, no pixel copy. Tolerance covers one-byte shader float/UNORM rounding. */
    static boolean matchesBasicSky(IntBuffer pixels, int width, int height,
            BooleanSupplier stopping) {
        Objects.requireNonNull(pixels);
        Objects.requireNonNull(stopping);
        if (width < 1 || width > 800 || height < 1 || height > 500
                || pixels.remaining() != width * height) {
            throw new IllegalArgumentException("World background pixel bounds");
        }
        int start = pixels.position();
        for (int y = 0; y < height; y = nextSample(y, height)) {
            if (stopping.getAsBoolean()) throw new CancellationException();
            float t = (float) y / height;
            int red = (int) Math.floor(91f + 80f * t);
            int green = (int) Math.floor(141f + 60f * t);
            int blue = (int) Math.floor(177f + 37f * t);
            for (int x = 0; x < width; x = nextSample(x, width)) {
                int pixel = pixels.get(start + y * width + x);
                if (pixel >>> 24 != 255
                        || Math.abs(((pixel >>> 16) & 255) - red) > 1
                        || Math.abs(((pixel >>> 8) & 255) - green) > 1
                        || Math.abs((pixel & 255) - blue) > 1) return false;
            }
        }
        return true;
    }
}
