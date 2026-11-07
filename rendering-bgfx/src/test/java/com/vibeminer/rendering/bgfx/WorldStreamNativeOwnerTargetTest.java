package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldStreamNativeOwnerTargetTest {
    private static int[] target(int w, int h, int allocatedW, int allocatedH) {
        return WorldStreamNativeOwner.targetSize(w, h, allocatedW, allocatedH);
    }

    @Test
    void firstAllocationAndGrowthUseStepsWithinTheCap() {
        assertArrayEquals(new int[] {256, 256}, target(200, 100, 0, 0));
        assertArrayEquals(new int[] {1024, 512}, target(800, 500, 256, 256));
        assertArrayEquals(new int[] {1280, 768}, target(1025, 513, 1024, 512));
        assertArrayEquals(new int[] {2560, 1600}, target(2560, 1600, 1024, 512), "cap");
    }

    @Test
    void aDragWithinTheAllocationNeverReallocates() {
        for (int w = 1800; w <= 2048; w += 31) {
            for (int h = 1000; h <= 1280; h += 37) {
                assertArrayEquals(new int[] {2048, 1280}, target(w, h, 2048, 1280), w + "x" + h);
            }
        }
    }

    @Test
    void readbackOverReadStaysWithinTwiceTheFrameArea() {
        // Maximise then restore: 1440p allocation, back to 1080p (2048x1280 stepped).
        assertArrayEquals(new int[] {2560, 1536}, target(1920, 1080, 2560, 1536), "within 2x");
        // Back to 800x500 (1024x512 stepped): 7.5x the area, so the target shrinks.
        assertArrayEquals(new int[] {1024, 512}, target(800, 500, 2560, 1536));
        // The invariant over a sweep of frames after any allocation.
        int[][] allocations = {{2560, 1600}, {2048, 1280}, {1024, 512}, {256, 256}};
        for (int[] allocated : allocations) {
            for (int w = 1; w <= 2560; w += 97) {
                for (int h = 1; h <= 1600; h += 89) {
                    int[] next = target(w, h, allocated[0], allocated[1]);
                    long steppedArea =
                            (long) WorldStreamNativeOwner.stepped(w, 2560)
                                    * WorldStreamNativeOwner.stepped(h, 1600);
                    assertTrue(next[0] >= w && next[1] >= h, "fits");
                    assertTrue((long) next[0] * next[1] <= 2 * steppedArea, "over-read <= 2x");
                }
            }
        }
    }

    @Test
    void allocationFallsBackToTheExactFrame() {
        var attempts =
                WorldStreamNativeOwner.allocationAttempts(new int[] {2048, 1280}, 1920, 1080);
        assertEquals(2, attempts.size());
        assertArrayEquals(new int[] {2048, 1280}, attempts.get(0));
        assertArrayEquals(new int[] {1920, 1080}, attempts.get(1));
        assertEquals(
                1,
                WorldStreamNativeOwner.allocationAttempts(new int[] {512, 256}, 512, 256).size());
    }
}
