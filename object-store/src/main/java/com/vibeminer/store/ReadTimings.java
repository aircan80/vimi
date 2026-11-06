package com.vibeminer.store;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Scratch profiling tallies (count, total nanos, max nanos) by name. */
public final class ReadTimings {
    public static volatile boolean enabled = Boolean.getBoolean("vimi.readTimings");

    private static final class Tally {
        final LongAdder count = new LongAdder(), nanos = new LongAdder();
        volatile long max;
    }

    private static final ConcurrentHashMap<String, Tally> TALLIES = new ConcurrentHashMap<>();

    private ReadTimings() {}

    public static long start() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void stop(String name, long started) {
        if (started == 0L) {
            return;
        }
        long n = System.nanoTime() - started;
        var t = TALLIES.computeIfAbsent(name, k -> new Tally());
        t.count.increment();
        t.nanos.add(n);
        if (n > t.max) {
            t.max = n;
        }
    }

    public static void reset() {
        TALLIES.clear();
    }

    public static Map<String, long[]> snapshot() {
        var out = new TreeMap<String, long[]>();
        TALLIES.forEach((k, t) -> out.put(k, new long[] {t.count.sum(), t.nanos.sum(), t.max}));
        return out;
    }

    public static String report() {
        var b = new StringBuilder();
        snapshot()
                .forEach(
                        (k, v) ->
                                b.append(
                                        String.format(
                                                java.util.Locale.ROOT,
                                                "%-44s n=%7d total=%9.1f ms mean=%8.3f ms max=%8.2f ms%n",
                                                k,
                                                v[0],
                                                v[1] / 1e6,
                                                v[0] == 0 ? 0 : v[1] / 1e6 / v[0],
                                                v[2] / 1e6)));
        return b.toString();
    }
}
