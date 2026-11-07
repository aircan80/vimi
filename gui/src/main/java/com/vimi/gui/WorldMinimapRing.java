package com.vimi.gui;

/**
 * The minimap rim's tile streaming ring: the fraction of desired tiles prepared, shown while
 * tiles are still coming and for {@link #HOLD_NANOS} after the last one, then faded out over
 * {@link #FADE_NANOS}. A view that is already complete when first seen shows no ring. Times are
 * {@link System#nanoTime} values; no JavaFX.
 */
final class WorldMinimapRing {
    static final long HOLD_NANOS = 1_000_000_000L, FADE_NANOS = 400_000_000L;

    private double fraction;
    private boolean full = true;
    private long fullSince = Long.MIN_VALUE;

    /** A new stream snapshot; {@code desired <= 0} means there is nothing to show. */
    void update(int prepared, int desired, long now) {
        double next = desired <= 0 ? 1 : Math.clamp((double) prepared / desired, 0, 1);
        boolean complete = next >= 1;
        if (complete && !full) fullSince = now;
        full = complete;
        fraction = next;
    }

    /** Hides the ring at once, e.g. when the minimap or its stream goes away. */
    void reset() {
        fraction = 0;
        full = true;
        fullSince = Long.MIN_VALUE;
    }

    /** Prepared over desired, 0 to 1. */
    double fraction() {
        return fraction;
    }

    /** 1 while filling and holding, falling to 0 over the fade, then 0. */
    double opacity(long now) {
        if (!full) return 1;
        if (fullSince == Long.MIN_VALUE) return 0;
        long since = now - fullSince;
        if (since < HOLD_NANOS) return 1;
        if (since >= HOLD_NANOS + FADE_NANOS) return 0;
        return 1 - (double) (since - HOLD_NANOS) / FADE_NANOS;
    }

    /** True while the ring is still visible but will change without a new snapshot. */
    boolean animating(long now) {
        return full && opacity(now) > 0;
    }
}
