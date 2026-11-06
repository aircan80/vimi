package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorldStreamSessionCadenceTest {
    private static final long INTERVAL = WorldStreamSession.DRAW_INTERVAL;

    @Test
    void childDrawCapIsTheSixtyHertzDisplayRate() {
        assertEquals(TimeUnit.SECONDS.toNanos(1) / 60, INTERVAL);
        assertEquals(33, WorldStreamTransport.FRAME_POLL_MILLIS, "fallback poll only");
    }

    @Test
    void continuousDrawingKeepsPhaseDespiteLoopOvershoot() {
        long base = 1_000_000_000L;
        for (int i = 0; i < 600; i++) {
            long now = base + INTERVAL + 1_100_000; // ~1 ms park overshoot each time
            base = WorldStreamSession.nextDrawBase(base, now);
        }
        // 600 draws keep 600 intervals of phase: no cumulative overshoot.
        assertEquals(1_000_000_000L + 600 * INTERVAL, base);
    }

    @Test
    void aStallBeyondAQuarterIntervalRestartsThePhaseWithoutACatchUpDraw() {
        long base = 3_000_000_000L;
        for (long stall : new long[] {25_000_000L, 30_000_000L}) {
            long now = base + stall;
            assertEquals(now, WorldStreamSession.nextDrawBase(base, now), stall + " ns stall");
        }
        // Up to 1.25 intervals late keeps the phase (ordinary loop overshoot).
        long late = base + WorldStreamSession.CATCH_UP_LIMIT - 1;
        assertEquals(base + INTERVAL, WorldStreamSession.nextDrawBase(base, late));
        assertEquals(INTERVAL * 5 / 4, WorldStreamSession.CATCH_UP_LIMIT);
    }

    @Test
    void idleGapRestartsThePhaseInsteadOfBursting() {
        long base = 5_000_000_000L;
        long now = base + 10 * INTERVAL;
        assertEquals(now, WorldStreamSession.nextDrawBase(base, now));
        assertEquals(now, WorldStreamSession.nextDrawBase(0, now));
    }
}
