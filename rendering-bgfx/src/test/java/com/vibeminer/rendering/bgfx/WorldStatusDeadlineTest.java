package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldStatusDeadlineTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void idleNeverTimesOutAndFirstPendingWriteStartsItsOwnDeadline() {
        var clock = new WorldStatusDeadline();
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(0, false));
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(100 * SECOND, false));
        assertTrue(clock.enqueue(100 * SECOND));
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(105 * SECOND - 1, false));
        assertEquals(WorldStatusDeadline.Action.REQUEST_CLOSE, clock.poll(105 * SECOND, false));
    }

    @Test
    void enqueueDoesNotPostponeBlockedWriterAndTerminateHasSeparateDrainDeadline() {
        var clock = new WorldStatusDeadline();
        assertTrue(clock.enqueue(0));
        assertTrue(clock.enqueue(4 * SECOND));
        assertEquals(WorldStatusDeadline.Action.REQUEST_CLOSE, clock.poll(5 * SECOND, false));
        assertFalse(clock.enqueue(6 * SECOND));
        clock.written(7 * SECOND);
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(10 * SECOND - 1, false));
        assertEquals(WorldStatusDeadline.Action.TERMINATE, clock.poll(10 * SECOND, false));
        assertEquals(WorldStatusDeadline.Action.TERMINATE, clock.poll(11 * SECOND, false));
    }

    @Test
    void completedWritesRefreshProgressAndEmptyQueueDisarmsStallDetection() {
        var clock = new WorldStatusDeadline();
        clock.enqueue(0);
        clock.enqueue(0);
        clock.written(4 * SECOND);
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(5 * SECOND, false));
        clock.written(8 * SECOND);
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(100 * SECOND, false));
        clock.enqueue(101 * SECOND);
        assertEquals(WorldStatusDeadline.Action.REQUEST_CLOSE, clock.poll(106 * SECOND, false));
    }

    @Test
    void reliableCapacityIncludesInFlightAndDrainedOwnershipPreventsTermination() {
        var clock = new WorldStatusDeadline();
        for (int i = 0; i < 16; i++) {
            assertTrue(clock.enqueue(0));
        }
        assertFalse(clock.enqueue(0));
        assertEquals(16, clock.outstanding());
        clock.written(0);
        assertTrue(clock.enqueue(0));
        assertEquals(WorldStatusDeadline.Action.REQUEST_CLOSE, clock.poll(5 * SECOND, false));
        assertEquals(WorldStatusDeadline.Action.DRAINED, clock.poll(6 * SECOND, true));
        assertEquals(WorldStatusDeadline.Action.DRAINED, clock.poll(100 * SECOND, false));
        assertEquals(0, clock.outstanding());
        assertFalse(clock.enqueue(100 * SECOND));
    }

    @Test
    void monotonicWrapWorksAndRegressionOrUnownedCompletionRefuses() {
        var clock = new WorldStatusDeadline();
        long start = Long.MAX_VALUE - SECOND;
        clock.enqueue(start);
        assertEquals(WorldStatusDeadline.Action.NONE, clock.poll(start + SECOND * 4, false));
        assertEquals(
                WorldStatusDeadline.Action.REQUEST_CLOSE, clock.poll(start + SECOND * 5, false));
        assertThrows(IllegalArgumentException.class, () -> clock.poll(start, false));
        var empty = new WorldStatusDeadline();
        assertThrows(IllegalStateException.class, () -> empty.written(0));
    }
}
