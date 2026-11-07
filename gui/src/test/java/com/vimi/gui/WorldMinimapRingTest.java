package com.vimi.gui;

/** Minimap streaming ring: fraction, hold after full, fade, refill; no JavaFX. */
public final class WorldMinimapRingTest {
    private WorldMinimapRingTest() {}

    private static final long S = 1_000_000_000L;

    public static void main(String[] args) {
        var ring = new WorldMinimapRing();
        require(ring.opacity(0) == 0 && !ring.animating(0), "A new ring is hidden");
        ring.update(9, 9, 0);
        require(ring.opacity(0) == 0, "A view already complete when first seen shows no ring");
        ring.update(3, 9, S);
        require(Math.abs(ring.fraction() - 1 / 3.0) < 1e-12 && ring.opacity(S) == 1 && !ring.animating(S),
                "Filling: fraction prepared/desired, fully visible, no animation needed");
        ring.update(12, 9, 2 * S);
        require(ring.fraction() == 1, "More prepared than desired clamps to full");
        require(ring.opacity(2 * S) == 1 && ring.opacity(3 * S - 1) == 1 && ring.animating(2 * S),
                "Full: held for one second");
        double mid = ring.opacity(3 * S + WorldMinimapRing.FADE_NANOS / 2);
        require(Math.abs(mid - .5) < 1e-9, "Then fades: " + mid);
        long gone = 3 * S + WorldMinimapRing.FADE_NANOS;
        require(ring.opacity(gone) == 0 && !ring.animating(gone), "Then hidden");
        ring.update(9, 9, 10 * S);
        require(ring.opacity(10 * S) == 0, "Staying full does not show it again");
        ring.update(8, 10, 11 * S);
        require(ring.opacity(11 * S) == 1 && ring.fraction() == .8, "New desired tiles show it again");
        ring.update(10, 10, 12 * S);
        require(ring.opacity(12 * S + S / 2) == 1, "And the hold restarts from the new full time");
        ring.update(0, 0, 20 * S);
        require(ring.fraction() == 1, "Nothing desired counts as full");
        ring.update(1, 4, 21 * S);
        ring.reset();
        require(ring.opacity(21 * S) == 0 && ring.fraction() == 0, "Reset hides at once");
        ring.update(-5, 4, 22 * S);
        require(ring.fraction() == 0, "Negative counts clamp");
        System.out.println("World minimap ring: fraction, clamp, hold, fade, refill and reset passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
