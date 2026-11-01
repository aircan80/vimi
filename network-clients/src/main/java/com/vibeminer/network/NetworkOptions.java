package com.vibeminer.network;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Explicit bounds. Smaller limits and timeouts are useful for isolated tests. */
public record NetworkOptions(
        int concurrentFetches,
        int queuedFetches,
        long maxInMemoryBytes,
        long maxBytes,
        Clock clock,
        String userAgent,
        Timeouts timeouts) {
    public record Timeouts(
            Duration connect,
            Duration headers,
            Duration idle,
            Duration externalHeaders,
            Duration definitions,
            Duration listfile) {
        public Timeouts {
            for (Duration d :
                    new Duration[] {
                        connect, headers, idle, externalHeaders, definitions, listfile
                    }) {
                if (d == null
                        || d.isNegative()
                        || d.isZero()
                        || d.toMillis() < 1
                        || d.toMillis() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Timeouts must be 1ms to 2^31-1ms");
                }
            }
        }

        public Timeouts() {
            this(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5));
        }
    }

    public NetworkOptions {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(userAgent);
        Objects.requireNonNull(timeouts);
        if (concurrentFetches < 1
                || concurrentFetches > 32
                || queuedFetches < 1
                || queuedFetches > 4096
                || maxInMemoryBytes < 1
                || maxInMemoryBytes > (64L << 20)
                || maxBytes < maxInMemoryBytes
                || maxBytes > (512L << 20)
                || userAgent.isEmpty()
                || userAgent.length() > 256
                || userAgent.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalArgumentException("Invalid network bounds");
        }
    }

    public NetworkOptions() {
        this(8, 64, 64L << 20, 512L << 20, Clock.systemUTC(), "VibeMiner/0.1", new Timeouts());
    }
}
