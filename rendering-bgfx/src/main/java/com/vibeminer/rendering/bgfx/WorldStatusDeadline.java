package com.vibeminer.rendering.bgfx;

import java.time.Duration;

/** Metadata-only status-write deadline oracle. An independent runtime watchdog must drive it. */
final class WorldStatusDeadline {
    static final int MAX_RELIABLE = 16;
    static final long TIMEOUT_NANOS = Duration.ofSeconds(5).toNanos();

    enum Action {
        NONE,
        REQUEST_CLOSE,
        TERMINATE,
        DRAINED
    }

    private int outstanding;
    private long observed, progress, closeRequested;
    private boolean observedTime, closing, terminated, drained;

    /** Runtime clock is sampled AFTER acquiring the oracle monitor. */
    synchronized boolean enqueue() {
        return enqueue(System.nanoTime());
    }

    synchronized void written() {
        written(System.nanoTime());
    }

    synchronized Action poll(boolean ownershipDrained) {
        return poll(System.nanoTime(), ownershipDrained);
    }

    /** Reserve before enqueueing; the bound includes a reliable status already being written. */
    synchronized boolean enqueue(long now) {
        time(now);
        if (closing || drained || outstanding == MAX_RELIABLE) {
            return false;
        }
        if (outstanding == 0) {
            progress = now;
        }
        outstanding++;
        return true;
    }

    /** Only a completed reliable write advances progress; enqueueing or partial writes do not. */
    synchronized void written(long now) {
        time(now);
        if (drained || outstanding == 0) {
            throw new IllegalStateException("No pending reliable status");
        }
        outstanding--;
        progress = now;
    }

    /** Called by a watchdog that does not join or write to the potentially blocked pipe. */
    synchronized Action poll(long now, boolean ownershipDrained) {
        time(now);
        if (ownershipDrained) {
            drained = true;
            outstanding = 0;
            return Action.DRAINED;
        }
        if (drained) {
            return Action.DRAINED;
        }
        if (terminated) {
            return Action.TERMINATE;
        }
        if (closing) {
            if (now - closeRequested >= TIMEOUT_NANOS) {
                terminated = true;
                return Action.TERMINATE;
            }
            return Action.NONE;
        }
        if (outstanding != 0 && now - progress >= TIMEOUT_NANOS) {
            closing = true;
            closeRequested = now;
            return Action.REQUEST_CLOSE;
        }
        return Action.NONE;
    }

    synchronized int outstanding() {
        return outstanding;
    }

    private void time(long now) {
        // Subtraction, rather than signed ordering, permits System.nanoTime's signed wrap.
        if (observedTime && now - observed < 0) {
            throw new IllegalArgumentException("Monotonic clock regressed");
        }
        observed = now;
        observedTime = true;
    }
}
