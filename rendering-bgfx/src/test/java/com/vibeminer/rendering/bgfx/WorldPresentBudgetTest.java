package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldPresentBudgetTest {
    @Test
    void theFrameBudgetMatchesTheReadbackLoopsThroughputAtSixtyHertz() {
        // The readback loop runs about one STEP_BOUND step per millisecond: ~16.7 per 60 Hz frame.
        double readbackPerFrame = BgfxWorldStreamRenderer.STEP_BOUND * (1000.0 / 60);
        assertEquals(4L << 20, WorldPresentBudget.FRAME_BYTES);
        assertTrue(
                Math.abs(WorldPresentBudget.FRAME_BYTES / readbackPerFrame - 1) < .05,
                "4 MiB per frame is within 5% of the readback loop's per-frame upload volume");
        assertEquals(
                16,
                WorldPresentBudget.FRAME_BYTES / BgfxWorldStreamRenderer.STEP_BOUND,
                "Sixteen full steps fit one frame");
        assertEquals(
                8_000_000,
                WorldPresentBudget.FRAME_NANOS,
                "Upload work stays under half a 60 Hz frame");
    }

    @Test
    void fullStepsStopAtTheByteBudget() {
        var budget = new WorldPresentBudget();
        budget.begin(0);
        int steps = 0;
        while (budget.another(0)) {
            budget.stepped(BgfxWorldStreamRenderer.STEP_BOUND);
            steps++;
        }
        assertEquals(16, steps);
        assertEquals(WorldPresentBudget.FRAME_BYTES, budget.bytes());
        budget.begin(1);
        assertTrue(budget.another(1), "A new frame starts a fresh budget");
    }

    @Test
    void slowStepsStopAtTheTimeBudget() {
        var budget = new WorldPresentBudget();
        budget.begin(1_000);
        budget.stepped(1024);
        assertTrue(budget.another(1_000 + WorldPresentBudget.FRAME_NANOS - 1));
        assertFalse(
                budget.another(1_000 + WorldPresentBudget.FRAME_NANOS),
                "8 ms of upload work ends the frame's uploads");
        assertEquals(1, budget.steps());
    }

    @Test
    void aStepWithoutProgressEndsTheFramesUploads() {
        var budget = new WorldPresentBudget();
        budget.begin(0);
        budget.stepped(4096);
        budget.stepped(0);
        assertFalse(budget.another(0), "An upload that is waiting is not spun on");
        assertThrows(IllegalArgumentException.class, () -> budget.stepped(-1));
    }

    /** A window size source the test controls; never touches a real window. */
    static final class FakeSurface implements WorldWindowPresentation.Surface {
        int[] size = {800, 600};
        int closes;

        @Override
        public long hwnd() {
            return 42;
        }

        @Override
        public int[] clientSize() {
            return size.clone();
        }

        @Override
        public void close() {
            closes++;
        }
    }

    @Test
    void sizeChangesClassifyEmptyAsNoDrawAndNoReset() {
        assertEquals(
                WorldWindowPresentation.SizeChange.NONE,
                WorldWindowPresentation.sizeChange(800, 600, new int[] {800, 600}));
        assertEquals(
                WorldWindowPresentation.SizeChange.RESIZE,
                WorldWindowPresentation.sizeChange(800, 600, new int[] {801, 600}));
        assertEquals(
                WorldWindowPresentation.SizeChange.EMPTY,
                WorldWindowPresentation.sizeChange(800, 600, new int[] {0, 0}));
        assertEquals(
                WorldWindowPresentation.SizeChange.EMPTY,
                WorldWindowPresentation.sizeChange(800, 600, new int[] {800, 0}));
    }

    @Test
    void aMinimisedWindowNeitherDrawsNorSpins() {
        var surface = new FakeSurface();
        var presentation = new WorldWindowPresentation(surface);
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            presentation.configure(org.lwjgl.bgfx.BGFXInit.calloc(stack));
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new int[] {800, 600}, presentation.frameSize(), "Unchanged size: draw, no reset");
        long t = 1_000_000_000L;
        presentation.presented(t);
        presentation.presented(t + 16_600_000);
        assertEquals(0, presentation.park(), "Vsync-paced presents never park");
        surface.size = new int[] {0, 0};
        assertEquals(null, presentation.frameSize(), "Minimised: nothing to draw, no reset");
        presentation.presented(t + 16_700_000);
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS,
                presentation.park(),
                "No area: idle a whole frame per loop");
        surface.size = new int[] {800, 600};
        presentation.frameSize();
        presentation.presented(t + 16_800_000);
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS - 100_000,
                presentation.park(),
                "Occluded (present at once): idle to one frame");
        presentation.close();
        presentation.close();
        assertEquals(1, surface.closes, "Close is idempotent");
    }

    @Test
    void idleIsAWholeFrameWithoutAreaElseOnlyWhenUnpaced() {
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS,
                WorldWindowPresentation.idleNanos(16_000_000, false));
        assertEquals(0, WorldWindowPresentation.idleNanos(16_000_000, true));
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS - 1_000_000,
                WorldWindowPresentation.idleNanos(1_000_000, true));
        assertEquals(0, WorldWindowPresentation.idleNanos(Long.MAX_VALUE, true), "First present");
    }

    @Test
    void anUnpacedPresentIdlesAboutAFrame() {
        assertEquals(0, WorldWindowPresentation.unpacedPark(16_600_000), "Vsync-paced: no park");
        assertEquals(
                0,
                WorldWindowPresentation.unpacedPark(4_000_000),
                "A 240 Hz display is still paced");
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS - 300_000,
                WorldWindowPresentation.unpacedPark(300_000),
                "Occluded: idle to one 60 Hz frame");
    }

    @Test
    void terrainSamplesAreNeitherBlackNorTheSkyAtTheirRow() {
        int h = 640;
        assertEquals(0x5b8db1, WorldWindowPresentation.skyAt(0, h), "Sky top 91,141,177");
        assertFalse(
                WorldWindowPresentation.terrainPixel(WorldWindowPresentation.skyAt(500, h), 500, h),
                "The sky at its own row is not terrain");
        assertFalse(
                WorldWindowPresentation.terrainPixel(0, 500, h), "Cleared black is not terrain");
        assertTrue(
                WorldWindowPresentation.terrainPixel(0x2c4729, 500, h), "Ground green is terrain");
        int nearSky = WorldWindowPresentation.skyAt(500, h) + 0x050505;
        assertFalse(
                WorldWindowPresentation.terrainPixel(nearSky, 500, h),
                "Within 12 of the sky is sky");
    }

    @Test
    void theFirstStepAlwaysRuns() {
        var budget = new WorldPresentBudget();
        budget.begin(5);
        assertTrue(budget.another(5));
    }
}
