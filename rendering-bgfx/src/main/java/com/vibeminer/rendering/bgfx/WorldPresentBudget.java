package com.vibeminer.rendering.bgfx;

/**
 * #381 spike: upload pacing for the native-presentation loop. There, {@code bgfx_frame} waits for
 * the display (vsync), so the loop runs once per refresh instead of about every millisecond. To
 * keep tile streaming as fast as the readback loop, each frame runs several upload steps (each
 * still at most {@link BgfxWorldStreamRenderer#STEP_BOUND}, unchanged) until either the frame's
 * byte budget or its time budget is spent. The readback loop does not use this class.
 */
final class WorldPresentBudget {
    /**
     * Bytes uploaded per presented frame: 4 MiB, which at 60 Hz matches the readback loop's
     * throughput of about one {@code STEP_BOUND} step per millisecond (~240 MiB/s).
     */
    static final long FRAME_BYTES = 4L << 20;

    /** Upload work per frame never runs past this, so a frame still presents on time. */
    static final long FRAME_NANOS = 8_000_000;

    private long bytes, started;
    private int steps;

    /** Starts a frame's budget at {@code now} (System.nanoTime). */
    void begin(long now) {
        bytes = 0;
        steps = 0;
        started = now;
    }

    /** True while another step may run: bytes and time left, and the last step made progress. */
    boolean another(long now) {
        return bytes < FRAME_BYTES && now - started < FRAME_NANOS;
    }

    /**
     * Records a step's uploaded bytes. A step that uploaded nothing ends the frame's uploads (the
     * upload is waiting on something else), so the loop never spins.
     */
    void stepped(int uploaded) {
        if (uploaded < 0) {
            throw new IllegalArgumentException("Negative upload");
        }
        steps++;
        bytes = uploaded == 0 ? FRAME_BYTES : bytes + uploaded;
    }

    long bytes() {
        return bytes;
    }

    int steps() {
        return steps;
    }
}
