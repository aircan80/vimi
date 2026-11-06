package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorldStreamSessionCadenceTest {
    private static final long INTERVAL = WorldStreamSession.DRAW_INTERVAL;

    @Test
    void childDrawCapIsTheSixtyHertzDisplayRate() {
        assertEquals(TimeUnit.SECONDS.toNanos(1) / 60, INTERVAL);
        assertEquals(30, WorldStreamTransport.FRAME_POLL_MILLIS, 3, "fallback poll only");
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
    void idleGapRestartsThePhaseInsteadOfBursting() {
        long base = 5_000_000_000L;
        long now = base + 10 * INTERVAL;
        assertEquals(now, WorldStreamSession.nextDrawBase(base, now));
        assertEquals(now, WorldStreamSession.nextDrawBase(0, now));
    }
}
