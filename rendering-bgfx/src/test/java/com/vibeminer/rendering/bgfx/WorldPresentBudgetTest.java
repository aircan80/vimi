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
        assertEquals(0, presentation.paceNanos(t), "Nothing presented yet: no wait");
        presentation.presented(t);
        assertEquals(0, presentation.paceNanos(t + 16_600_000), "Vsync-paced: no wait");
        surface.size = new int[] {0, 0};
        assertEquals(null, presentation.frameSize(), "Minimised: nothing to draw, no reset");
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS - 1_000_000,
                presentation.paceNanos(t + 1_000_000),
                "No area: presents a whole frame apart");
        presentation.close();
        presentation.close();
        assertEquals(1, surface.closes, "Close is idempotent");
    }

    @Test
    void aCoveredWindowDrawingFastIsPacedNotSpun() {
        // Fake clock: a covered window presents at once and a frame costs 5 ms. The loop waits
        // paceNanos before each present, so presents are at least 3/4 of a frame apart.
        var presentation = new WorldWindowPresentation(new FakeSurface());
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            presentation.configure(org.lwjgl.bgfx.BGFXInit.calloc(stack));
        }
        presentation.frameSize();
        long t = 1_000_000_000L, previous = 0;
        int presents = 0;
        while (t < 2_000_000_000L) {
            t += 5_000_000; // the frame's work
            t += presentation.paceNanos(t); // wait, then present (returns at once)
            presentation.presented(t);
            if (previous != 0) {
                assertTrue(
                        t - previous >= WorldWindowPresentation.MIN_PRESENT_NANOS,
                        "Presents at least 3/4 of a frame apart: " + (t - previous));
            }
            previous = t;
            presents++;
        }
        assertTrue(presents <= 81, "At most 80 presents a second when unpaced: " + presents);
        presentation.close();
    }

    @Test
    void paceIsTheRemainderOfThreeQuartersOrAWholeFrame() {
        assertEquals(0, WorldWindowPresentation.paceNanos(16_000_000, true), "Paced by vsync");
        assertEquals(0, WorldWindowPresentation.paceNanos(13_000_000, true));
        assertEquals(
                WorldWindowPresentation.MIN_PRESENT_NANOS - 5_000_000,
                WorldWindowPresentation.paceNanos(5_000_000, true),
                "Covered: topped up");
        assertEquals(
                WorldWindowPresentation.IDLE_FRAME_NANOS - 5_000_000,
                WorldWindowPresentation.paceNanos(5_000_000, false),
                "No area: a whole frame");
        assertEquals(0, WorldWindowPresentation.paceNanos(-1, true));
    }

    @Test
    void childWindowStartsHiddenAndHasAreaOnlyOnceShown() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS);
        long parent = WorldStreamWindowProof.parentWindow();
        try (var child = WorldChildWindow.create(parent)) {
            assertFalse(
                    org.lwjgl.system.windows.User32.IsWindowVisible(child.hwnd()),
                    "Created hidden: it never covers the parent before the GUI places it");
            assertEquals(0, child.clientSize()[0], "Hidden: no area, so nothing is presented");
            long shown = WorldStreamWindowProof.showChild(parent, 320, 200);
            assertEquals(child.hwnd(), shown, "The GUI finds it by its class");
            long until = System.nanoTime() + 2_000_000_000L;
            while (child.clientSize()[0] == 0 && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            assertEquals(320, child.clientSize()[0], "Shown: the placed width");
            assertEquals(200, child.clientSize()[1], "Shown: the placed height");
        } finally {
            org.lwjgl.system.windows.User32.PostMessage(
                    null, parent, org.lwjgl.system.windows.User32.WM_CLOSE, 0, 0);
        }
    }

    @Test
    void successiveChildrenShareOneLiveWindowProcedure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS);
        // The class keeps the procedure it was registered with: a child created after an earlier
        // one closed must not call a procedure freed with that one (an access violation).
        long parent = WorldStreamWindowProof.parentWindow();
        try {
            for (int i = 0; i < 4; i++) {
                var child = WorldChildWindow.create(parent);
                WorldStreamWindowProof.showChild(parent, 64 + i, 48);
                long until = System.nanoTime() + 2_000_000_000L;
                while (child.clientSize()[0] == 0 && System.nanoTime() < until) {
                    Thread.sleep(5);
                }
                assertEquals(64 + i, child.clientSize()[0], "Child " + i + " is placed and shown");
                child.close();
                assertFalse(child.pumping(), "Child " + i + " closed");
            }
        } finally {
            org.lwjgl.system.windows.User32.PostMessage(
                    null, parent, org.lwjgl.system.windows.User32.WM_CLOSE, 0, 0);
        }
    }

    @Test
    void childWindowThreadEndsWhenTheParentIsDestroyed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.lwjgl.system.Platform.get() == org.lwjgl.system.Platform.WINDOWS);
        long parent = WorldStreamWindowProof.parentWindow();
        var child = WorldChildWindow.create(parent);
        assertTrue(child.pumping(), "The child's message thread runs");
        // Destroying the parent sends WM_NCDESTROY to the child (on its own thread).
        org.lwjgl.system.windows.User32.PostMessage(
                null, parent, org.lwjgl.system.windows.User32.WM_CLOSE, 0, 0);
        long until = System.nanoTime() + 2_000_000_000L;
        while (child.pumping() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertFalse(child.pumping(), "WM_NCDESTROY ends the message loop (PostQuitMessage)");
        assertEquals(0, child.clientSize()[0], "A destroyed child reports no area");
        long start = System.nanoTime();
        child.close();
        assertTrue(System.nanoTime() - start < 200_000_000L, "Close does not wait on a dead loop");
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
