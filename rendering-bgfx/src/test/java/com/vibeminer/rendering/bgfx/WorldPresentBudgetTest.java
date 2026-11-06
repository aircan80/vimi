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

    @Test
    void theFirstStepAlwaysRuns() {
        var budget = new WorldPresentBudget();
        budget.begin(5);
        assertTrue(budget.another(5));
    }
}
