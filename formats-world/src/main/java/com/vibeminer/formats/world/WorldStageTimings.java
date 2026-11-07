package com.vibeminer.formats.world;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Opt-in, process-wide stage timings and frame statistics for World streaming measurement.
 *
 * <p>Off by default. {@code -Dvimi.world.stageTimings=true} (or {@link #enable}) turns it on. While
 * off, {@link #start()} returns 0 and every recording call returns after one volatile read, so call
 * sites may stay in production paths. Series are keyed by name; each keeps an exact count, sum and
 * maximum plus a bounded uniform reservoir of samples for percentiles. Stages can nest or overlap
 * (a source read inside a material preparation), so series are reported side by side and never
 * summed by this class.
 */
public final class WorldStageTimings {
    /** System property that enables recording at startup. */
    public static final String PROPERTY = "vimi.world.stageTimings";

    /** Samples kept per series for percentiles. */
    public static final int RESERVOIR = 4096;

    /** What one series records. */
    public enum Kind {
        /** Durations in nanoseconds; formatted as milliseconds. */
        NANOS,
        /** Per-sample values such as draws or triangles per frame. */
        VALUE,
        /** Running totals such as bytes; no percentiles. */
        COUNTER
    }

    private static volatile boolean enabled = Boolean.getBoolean(PROPERTY);
    private static final ConcurrentHashMap<String, Accumulator> SERIES = new ConcurrentHashMap<>();

    private WorldStageTimings() {}

    /** Whether recording is on. */
    public static boolean enabled() {
        return enabled;
    }

    /** Turns recording on or off for this process; existing series are kept. */
    public static void enable(boolean on) {
        enabled = on;
    }

    /** A start stamp for {@link #stop}, or 0 while recording is off. */
    public static long start() {
        return enabled ? Math.max(1L, System.nanoTime()) : 0L;
    }

    /** Records the time since {@code started}; ignored for a 0 stamp or while off. */
    public static void stop(String stage, long started) {
        if (started == 0L || !enabled) {
            return;
        }
        record(stage, System.nanoTime() - started);
    }

    /** Records one duration in nanoseconds. */
    public static void record(String stage, long nanos) {
        if (enabled) {
            series(stage, Kind.NANOS).sample(Math.max(0L, nanos));
        }
    }

    /** Records one per-sample value, for example the draws of one frame. */
    public static void value(String metric, long value) {
        if (enabled) {
            series(metric, Kind.VALUE).sample(Math.max(0L, value));
        }
    }

    /** Adds to a running total, for example bytes read. */
    public static void add(String counter, long delta) {
        if (enabled) {
            series(counter, Kind.COUNTER).add(delta);
        }
    }

    /** Drops every series. */
    public static void reset() {
        SERIES.clear();
    }

    /** An immutable copy of every series, in name order. */
    public static Snapshot snapshot() {
        var copy = new TreeMap<String, Series>();
        SERIES.forEach((name, accumulator) -> copy.put(name, accumulator.copy(name)));
        return new Snapshot(Collections.unmodifiableMap(copy));
    }

    private static Accumulator series(String name, Kind kind) {
        Objects.requireNonNull(name);
        var accumulator = SERIES.computeIfAbsent(name, ignored -> new Accumulator(kind));
        if (accumulator.kind != kind) {
            throw new IllegalArgumentException("Series " + name + " is " + accumulator.kind);
        }
        return accumulator;
    }

    /** One series at snapshot time. {@code samples} is sorted ascending. */
    public record Series(String name, Kind kind, long count, long sum, long max, long[] samples) {
        public Series {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            samples = samples.clone();
        }

        @Override
        public long[] samples() {
            return samples.clone();
        }

        /** Arithmetic mean over every recorded sample, or 0 when empty. */
        public double mean() {
            return count == 0 ? 0 : (double) sum / count;
        }

        /** Nearest-rank percentile over the reservoir, or 0 when empty. */
        public long percentile(double p) {
            if (samples.length == 0) {
                return 0;
            }
            int rank = (int) Math.ceil(p / 100.0 * samples.length);
            return samples[Math.min(samples.length - 1, Math.max(0, rank - 1))];
        }
    }

    /** Every series at one moment. */
    public record Snapshot(Map<String, Series> series) {
        public Snapshot {
            Objects.requireNonNull(series);
        }

        /** The named series, or null. */
        public Series get(String name) {
            return series.get(name);
        }

        /** A fixed-width text table: durations in milliseconds, values and counters as numbers. */
        public String format() {
            var out = new StringBuilder();
            out.append(
                    String.format(
                            Locale.ROOT,
                            "%-44s %6s %9s %10s %10s %10s %10s %12s%n",
                            "series",
                            "unit",
                            "count",
                            "mean",
                            "p50",
                            "p95",
                            "max",
                            "total"));
            for (var s : series.values()) {
                switch (s.kind()) {
                    case NANOS ->
                            out.append(
                                    String.format(
                                            Locale.ROOT,
                                            "%-44s %6s %9d %10.3f %10.3f %10.3f %10.3f %12.1f%n",
                                            s.name(),
                                            "ms",
                                            s.count(),
                                            s.mean() / 1e6,
                                            s.percentile(50) / 1e6,
                                            s.percentile(95) / 1e6,
                                            s.max() / 1e6,
                                            s.sum() / 1e6));
                    case VALUE ->
                            out.append(
                                    String.format(
                                            Locale.ROOT,
                                            "%-44s %6s %9d %10.1f %10d %10d %10d %12d%n",
                                            s.name(),
                                            "value",
                                            s.count(),
                                            s.mean(),
                                            s.percentile(50),
                                            s.percentile(95),
                                            s.max(),
                                            s.sum()));
                    case COUNTER ->
                            out.append(
                                    String.format(
                                            Locale.ROOT,
                                            "%-44s %6s %9d %10s %10s %10s %10s %12d%n",
                                            s.name(),
                                            "total",
                                            s.count(),
                                            "-",
                                            "-",
                                            "-",
                                            "-",
                                            s.sum()));
                }
            }
            return out.toString();
        }
    }

    private static final class Accumulator {
        final Kind kind;
        private long count, sum, max;
        private final long[] reservoir;
        private int kept;

        Accumulator(Kind kind) {
            this.kind = kind;
            reservoir = kind == Kind.COUNTER ? new long[0] : new long[RESERVOIR];
        }

        synchronized void sample(long value) {
            count++;
            sum += value;
            max = Math.max(max, value);
            if (kept < reservoir.length) {
                reservoir[kept++] = value;
            } else {
                long slot = ThreadLocalRandom.current().nextLong(count);
                if (slot < reservoir.length) {
                    reservoir[(int) slot] = value;
                }
            }
        }

        synchronized void add(long delta) {
            count++;
            sum += delta;
            max = Math.max(max, sum);
        }

        synchronized Series copy(String name) {
            var samples = Arrays.copyOf(reservoir, kept);
            Arrays.sort(samples);
            return new Series(name, kind, count, sum, max, samples);
        }
    }
}
