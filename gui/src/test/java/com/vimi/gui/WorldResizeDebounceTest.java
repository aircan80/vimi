package com.vimi.gui;

/** Streamed frame size debounce across authored pulses; no JavaFX toolkit. */
public final class WorldResizeDebounceTest {
    private WorldResizeDebounceTest() {}

    public static void main(String[] args) {
        var resize = new WorldResizeDebounce();
        require(resize.width() == 0 && resize.height() == 0 && !resize.pulse(), "Nothing before an offer");
        resize.offer(800, 500);
        require(size(resize, 800, 500), "The first size applies at once");
        require(!resize.pulse(), "No change owed");

        // A window drag: a new size on every layout step, with pulses in between.
        for (int step = 1; step <= 30; step++) {
            resize.offer(800 - step, 500 - step / 2);
            require(!resize.pulse(), "A size that changed during the pulse never applies: step " + step);
            require(size(resize, 800, 500), "The old size keeps being requested while dragging");
        }
        // The drag stops: the last size (offered before the last pulse above) survives one whole
        // pulse, then applies exactly once.
        require(resize.pulse() && size(resize, 770, 485), "The settled size applies");
        require(!resize.pulse(), "Applied once");

        // Re-offering the pending size does not restart the wait; the applied size cancels it.
        resize.offer(640, 400);
        require(!resize.pulse(), "Quiet pulse");
        resize.offer(640, 400);
        require(resize.pulse() && size(resize, 640, 400), "The same pending size keeps its wait");
        resize.offer(320, 200);
        resize.offer(640, 400);
        require(!resize.pulse() && !resize.pulse() && size(resize, 640, 400),
                "Returning to the applied size cancels the pending change");

        resize.reset();
        require(resize.width() == 0 && !resize.pulse(), "Reset forgets every size");
        resize.offer(256, 256);
        require(size(resize, 256, 256), "After a reset the first size applies at once");
        System.out.println("World resize debounce: first size, drag hold, one quiet pulse, cancel and reset passed");
    }

    private static boolean size(WorldResizeDebounce resize, int width, int height) {
        return resize.width() == width && resize.height() == height;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
