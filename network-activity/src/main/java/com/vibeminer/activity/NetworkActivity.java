package com.vibeminer.activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded, thread-safe session counters. No polling threads or per-chunk callbacks. */
public final class NetworkActivity implements TransferObserver {
    public enum Phase {
        WAITING_RESPONSE,
        WAITING_BODY,
        HANDOFF
    }

    public record Category(Kind kind, long receivedBytes, int active) {}

    public record Snapshot(
            long nanoTime,
            long receivedBytes,
            int active,
            int waitingResponse,
            int waitingBody,
            int handoff,
            List<Category> categories) {
        public Snapshot {
            categories = List.copyOf(categories);
        }
    }

    private final LongSupplier clock;
    private final long[] bytes = new long[Kind.values().length];
    private final int[] active = new int[Kind.values().length];
    private final int[] phases = new int[Phase.values().length];
    private long total;

    public NetworkActivity() {
        this(System::nanoTime);
    }

    public NetworkActivity(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public synchronized Attempt begin(Kind kind) {
        Objects.requireNonNull(kind);
        active[kind.ordinal()]++;
        phases[Phase.WAITING_RESPONSE.ordinal()]++;
        return new Scope(kind);
    }

    public synchronized Snapshot snapshot() {
        var categories = new ArrayList<Category>(Kind.values().length);
        int count = 0;
        for (Kind kind : Kind.values()) {
            count += active[kind.ordinal()];
            categories.add(new Category(kind, bytes[kind.ordinal()], active[kind.ordinal()]));
        }
        return new Snapshot(
                clock.getAsLong(), total, count, phases[0], phases[1], phases[2], categories);
    }

    private static long add(long current, int count) {
        return Long.MAX_VALUE - current < count ? Long.MAX_VALUE : current + count;
    }

    private final class Scope implements Attempt {
        private final Kind kind;
        private Phase phase = Phase.WAITING_RESPONSE;
        private boolean ended;

        Scope(Kind kind) {
            this.kind = kind;
        }

        private void phase(Phase next) {
            if (ended) {
                return;
            }
            phases[phase.ordinal()]--;
            phases[next.ordinal()]++;
            phase = next;
        }

        @Override
        public void headersReceived() {
            synchronized (NetworkActivity.this) {
                phase(Phase.HANDOFF);
            }
        }

        @Override
        public void readStarted() {
            synchronized (NetworkActivity.this) {
                phase(Phase.WAITING_BODY);
            }
        }

        @Override
        public void readFinished(int count) {
            if (count < -1) {
                throw new IllegalArgumentException("Invalid body read count");
            }
            synchronized (NetworkActivity.this) {
                if (count > 0) {
                    total = add(total, count);
                    bytes[kind.ordinal()] = add(bytes[kind.ordinal()], count);
                }
                if (count == -1) {
                    close();
                } else {
                    phase(Phase.HANDOFF);
                }
            }
        }

        @Override
        public void close() {
            synchronized (NetworkActivity.this) {
                if (ended) {
                    return;
                }
                ended = true;
                active[kind.ordinal()]--;
                phases[phase.ordinal()]--;
            }
        }
    }
}
