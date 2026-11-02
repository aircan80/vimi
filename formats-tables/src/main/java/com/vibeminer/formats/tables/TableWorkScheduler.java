package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate bounded lanes; queued interactive work blocks new background admission. */
public final class TableWorkScheduler {
    public static final int DEFAULT_MAX_INTERACTIVE = 4,
            DEFAULT_MAX_BACKGROUND = 2,
            DEFAULT_MAX_QUEUED = 256;

    public static final class BusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final TableTypes.Priority priority;

        BusyException(TableTypes.Priority priority) {
            super("Table priority queue is full");
            this.priority = priority;
        }

        public TableTypes.Priority priority() {
            return priority;
        }
    }

    public final class Slot implements AutoCloseable {
        private final int lane;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Slot(int lane) {
            this.lane = lane;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(lane);
            }
        }
    }

    private final Object gate = new Object();
    private final int[] maximum, running = {0, 0};
    private final int maxQueued;
    private final ArrayDeque<Waiter> interactive = new ArrayDeque<>(),
            background = new ArrayDeque<>();

    private static final class Waiter {
        final CompletableFuture<Slot> result = new CompletableFuture<>();
    }

    public TableWorkScheduler() {
        this(4, 2, 256);
    }

    public TableWorkScheduler(int interactive, int background, int queued) {
        if (interactive < 1 || background < 1 || queued < 0) {
            throw new IllegalArgumentException("Invalid table lane bounds");
        }
        maximum = new int[] {interactive, background};
        maxQueued = queued;
    }

    public int maxInteractive() {
        return maximum[0];
    }

    public int maxBackground() {
        return maximum[1];
    }

    public int maxQueued() {
        return maxQueued;
    }

    public int running(TableTypes.Priority priority) {
        synchronized (gate) {
            return running[priority.ordinal()];
        }
    }

    public int queued(TableTypes.Priority priority) {
        synchronized (gate) {
            return queue(priority.ordinal()).size();
        }
    }

    public CompletableFuture<Slot> enter(TableTypes.Priority priority, Cancellation token) {
        token.check();
        int lane = priority.ordinal();
        var waiter = new Waiter();
        synchronized (gate) {
            var q = queue(lane);
            if (q.isEmpty()
                    && running[lane] < maximum[lane]
                    && (lane == 0 || interactive.isEmpty())) {
                running[lane]++;
                return CompletableFuture.completedFuture(new Slot(lane));
            }
            if (q.size() >= maxQueued) {
                return CompletableFuture.failedFuture(new BusyException(priority));
            }
            q.addLast(waiter);
        }
        var registration =
                token.onCancel(
                        () -> {
                            boolean removed;
                            synchronized (gate) {
                                removed = queue(lane).remove(waiter);
                            }
                            if (removed) {
                                waiter.result.cancel(false);
                                pump();
                            }
                        });
        waiter.result.whenComplete((v, e) -> registration.close());
        return waiter.result;
    }

    private ArrayDeque<Waiter> queue(int lane) {
        return lane == 0 ? interactive : background;
    }

    private void release(int lane) {
        synchronized (gate) {
            running[lane]--;
        }
        pump();
    }

    private void pump() {
        var granted = new ArrayList<Runnable>();
        synchronized (gate) {
            for (int lane = 0; lane < 2; lane++) {
                var q = queue(lane);
                if (lane == 1 && !interactive.isEmpty()) {
                    break;
                }
                while (running[lane] < maximum[lane] && !q.isEmpty()) {
                    var w = q.removeFirst();
                    running[lane]++;
                    var slot = new Slot(lane);
                    granted.add(
                            () -> {
                                if (!w.result.complete(slot)) {
                                    slot.close();
                                }
                            });
                }
            }
        }
        for (var grant : granted) {
            CompletableFuture.runAsync(grant);
        }
    }
}
