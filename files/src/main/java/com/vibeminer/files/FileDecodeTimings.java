package com.vibeminer.files;

import java.util.concurrent.atomic.LongAdder;

/**
 * Opt-in totals of BLTE zlib inflation, for World streaming measurement. Off by default; the same
 * {@code -Dvimi.world.stageTimings=true} property that enables the World stage timings turns it on.
 * While off, each decoded chunk costs one volatile read.
 */
public final class FileDecodeTimings {
    /** System property that enables recording at startup. */
    public static final String PROPERTY = "vimi.world.stageTimings";

    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    private static final LongAdder CHUNKS = new LongAdder();
    private static final LongAdder BYTES = new LongAdder();
    private static final LongAdder NANOS = new LongAdder();

    private FileDecodeTimings() {}

    /** Totals at one moment. */
    public record Totals(long zlibChunks, long zlibBytes, long zlibNanos) {}

    /** Whether recording is on. */
    public static boolean enabled() {
        return enabled;
    }

    /** Turns recording on or off for this process. */
    public static void enable(boolean on) {
        enabled = on;
    }

    /** Clears every total. */
    public static void reset() {
        CHUNKS.reset();
        BYTES.reset();
        NANOS.reset();
    }

    /** The current totals. */
    public static Totals snapshot() {
        return new Totals(CHUNKS.sum(), BYTES.sum(), NANOS.sum());
    }

    static long start() {
        return enabled ? Math.max(1L, System.nanoTime()) : 0L;
    }

    static void inflated(long started, int bytes) {
        if (started == 0L || !enabled) {
            return;
        }
        NANOS.add(Math.max(0L, System.nanoTime() - started));
        CHUNKS.increment();
        BYTES.add(bytes);
    }
}
