package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.StoreCommit;
import com.vibeminer.access.StoreCommitSubscription;
import com.vibeminer.access.StoreCommits;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.store.ObjectKey;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Bounded per-build tally; commits win over measurements already in flight. */
public final class DownloadedFigures {
    public record Figure(long storedObjects, long totalObjects, long storedBytes, long totalBytes) {
        public double share() {
            return totalBytes == 0 ? 0 : (double) storedBytes / totalBytes;
        }
    }

    public record Options(int batchKeys, int maxBuilds, int queueCapacity, int maxMeasurements) {
        public Options {
            if (batchKeys < 1
                    || batchKeys > 1000000
                    || maxBuilds < 1
                    || queueCapacity < 1
                    || maxMeasurements < 1
                    || maxMeasurements > 32) {
                throw new IllegalArgumentException("Positive bounded downloaded options required");
            }
        }

        public Options() {
            this(4096, 4, 1024, 2);
        }
    }

    private final Object gate = new Object();
    private final ObjectSource source;
    private final Options options;
    private final CatalogTasks measuring;
    private final Map<BuildRef, Tracked> tracked = new HashMap<>();
    private final Set<Measurement> live = new HashSet<>();
    private final CopyOnWriteArrayList<BiConsumer<BuildRef, Figure>> listeners =
            new CopyOnWriteArrayList<>();
    private final StoreCommitSubscription subscription;
    private final Thread pump;
    private long clock, sequence;
    private boolean disposed;
    private CompletableFuture<Void> closing;

    public DownloadedFigures(ObjectSource source) {
        this(source, null, new Options());
    }

    public DownloadedFigures(ObjectSource source, StoreCommits commits, Options options) {
        this.source = java.util.Objects.requireNonNull(source);
        this.options = options;
        measuring = new CatalogTasks("downloaded", options.maxMeasurements(), 64, () -> {});
        subscription = commits == null ? null : commits.subscribe(options.queueCapacity());
        pump =
                subscription == null
                        ? null
                        : Thread.ofPlatform().daemon().name("downloaded-commits").start(this::pump);
    }

    volatile Runnable commitProbe; // Per-instance deterministic integration seam.

    public AutoCloseable onChanged(BiConsumer<BuildRef, Figure> listener) {
        listeners.add(java.util.Objects.requireNonNull(listener));
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<Figure> get(Build build, Cancellation token) {
        return measure(build, false, token);
    }

    public CompletableFuture<Figure> refresh(Build build, Cancellation token) {
        return measure(build, true, token);
    }

    private CompletableFuture<Figure> measure(Build build, boolean refresh, Cancellation token) {
        token.check();
        Measurement measurement;
        synchronized (gate) {
            Tracked t = track(build);
            if (!refresh && t.measured) {
                return CompletableFuture.completedFuture(t.figure());
            }
            measurement = refresh ? waiting(t) : t.shared;
            if (measurement == null) {
                measurement = start(t, false);
            }
            measurement.waiters++;
        }
        return waitFor(measurement, token);
    }

    private Tracked track(Build build) {
        java.util.Objects.requireNonNull(build);
        if (disposed) {
            throw new IllegalStateException("Downloaded figures are closed");
        }
        if (!(build instanceof LedgerSource ledger)) {
            throw new IllegalArgumentException("Only opened builds have an object ledger");
        }
        Tracked t = tracked.get(build.build());
        if (t == null || t.build != build) {
            if (t != null) {
                drop(t);
            } else if (tracked.size() >= options.maxBuilds()) {
                drop(
                        tracked.values().stream()
                                .min(java.util.Comparator.comparingLong(x -> x.lastUse))
                                .orElseThrow());
            }
            t = new Tracked(build, ledger);
            tracked.put(build.build(), t);
        }
        t.lastUse = ++clock;
        return t;
    }

    private void drop(Tracked t) {
        tracked.remove(t.build.build());
        for (Measurement m : t.active) {
            if (m.pinned && m.waiters == 0) {
                cancelLater(m);
            }
        }
    }

    private static Measurement waiting(Tracked t) {
        return t.active.stream()
                .filter(m -> m.touched == null && !m.abandoned)
                .findFirst()
                .orElse(null);
    }

    private Measurement start(Tracked t, boolean pinned) {
        Measurement m = new Measurement(t, ++sequence, pinned);
        t.active.add(m);
        live.add(m);
        t.shared = m;
        m.result =
                measuring
                        .submit(m.token, () -> run(m))
                        .whenComplete(
                                (value, error) -> {
                                    synchronized (gate) {
                                        if (!m.done) {
                                            m.done = true;
                                            t.active.remove(m);
                                            live.remove(m);
                                            if (t.shared == m) {
                                                t.shared = null;
                                            }
                                        }
                                    }
                                    // Completion publication occurs off the bounded measurement
                                    // worker. A subscriber
                                    // may synchronously request a refresh without retaining that
                                    // worker's only slot.
                                    if (error == null) {
                                        raise(t.build.build(), value);
                                    }
                                });
        return m;
    }

    private CompletableFuture<Figure> waitFor(Measurement m, Cancellation token) {
        CompletableFuture<Figure> result = new CompletableFuture<>();
        AtomicBoolean done = new AtomicBoolean();
        Cancellation.Registration registration =
                token.onCancel(
                        () -> {
                            if (!done.compareAndSet(false, true)) {
                                return;
                            }
                            synchronized (gate) {
                                if (--m.waiters == 0
                                        && !m.done
                                        && (!m.pinned
                                                || tracked.get(m.owner.build.build()) != m.owner)) {
                                    m.abandoned = true;
                                    if (m.owner.shared == m) {
                                        m.owner.shared = null;
                                    }
                                    cancelLater(m);
                                }
                            }
                            result.completeExceptionally(new CancellationException());
                        });
        m.result.whenComplete(
                (figure, error) -> {
                    registration.close();
                    if (done.compareAndSet(false, true)) {
                        synchronized (gate) {
                            m.waiters--;
                        }
                        if (error == null) {
                            result.complete(figure);
                        } else {
                            result.completeExceptionally(CatalogTasks.unwrap(error));
                        }
                    }
                });
        return result;
    }

    private static void cancelLater(Measurement m) {
        Thread.startVirtualThread(m.token::cancel);
    }

    private Figure run(Measurement m) {
        Tracked t = m.owner;
        Figure figure;
        try {
            m.token.check();
            ObjectLedger ledger = t.source.ledger();
            synchronized (gate) {
                if (t.ledger == null) {
                    t.ledger = ledger;
                    t.stored = new BitSet(ledger.count());
                }
                m.touched = new BitSet(ledger.count());
            }
            BitSet fresh = new BitSet(ledger.count());
            for (int start = 0; start < ledger.count(); start += options.batchKeys()) {
                m.token.check();
                List<ObjectKey> keys = new ArrayList<>();
                for (int i = start;
                        i < Math.min(start + options.batchKeys(), ledger.count());
                        i++) {
                    keys.add(new ObjectKey(ObjectKey.ENCODED, ledger.key(i).bytes()));
                }
                List<Boolean> has = await(source.hasKeys(keys, m.token), m.token);
                if (has.size() != keys.size()) {
                    throw new IllegalStateException("Source answered a different key count");
                }
                for (int i = 0; i < has.size(); i++) {
                    if (has.get(i)) {
                        fresh.set(start + i);
                    }
                }
            }
            synchronized (gate) {
                if (m.sequence > t.applied) {
                    for (int row = 0; row < ledger.count(); row++) {
                        if (!m.touched.get(row)) {
                            t.markRaw(row, fresh.get(row));
                        }
                    }
                    t.applied = m.sequence;
                    t.measured = true;
                }
                figure = t.figure();
            }
        } finally {
            synchronized (gate) {
                m.done = true;
                t.active.remove(m);
                live.remove(m);
                if (t.shared == m) {
                    t.shared = null;
                }
            }
        }
        return figure;
    }

    @SuppressWarnings("try")
    static <T> T await(CompletableFuture<T> future, Cancellation token) {
        CompletableFuture<T> canceled = new CompletableFuture<>();
        try (Cancellation.Registration ignored =
                token.onCancel(() -> canceled.completeExceptionally(new CancellationException()))) {
            CompletableFuture.anyOf(future, canceled).join();
            token.check();
            return future.join();
        }
    }

    private void pump() {
        try {
            while (true) {
                synchronized (gate) {
                    if (disposed) {
                        return;
                    }
                }
                StoreCommit commit = subscription.poll(250, TimeUnit.MILLISECONDS);
                if (commit != null) {
                    apply(commit);
                    while ((commit = subscription.poll()) != null) {
                        apply(commit);
                    }
                }
                if (subscription.takeMissed()) {
                    synchronized (gate) {
                        if (!disposed) {
                            for (Tracked t : tracked.values()) {
                                if (t.measured || !t.active.isEmpty()) {
                                    Measurement waiting = waiting(t);
                                    if (waiting != null) {
                                        waiting.pinned = true;
                                    } else {
                                        start(t, true);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void apply(StoreCommit commit) {
        List<Map.Entry<BuildRef, Figure>> changed = new ArrayList<>();
        synchronized (gate) {
            if (disposed) {
                return;
            }
            for (Tracked t : tracked.values()) {
                if (t.ledger == null) {
                    continue;
                }
                boolean moved = false;
                for (ObjectKey key : commit.stored()) {
                    moved |= t.mark(t.ledger.indexOf(key), true);
                }
                for (ObjectKey key : commit.removed()) {
                    moved |= t.mark(t.ledger.indexOf(key), false);
                }
                if (moved && t.measured) {
                    changed.add(Map.entry(t.build.build(), t.figure()));
                }
            }
        }
        changed.forEach(change -> raise(change.getKey(), change.getValue()));
        Runnable probe = commitProbe;
        if (probe != null) {
            probe.run();
        }
    }

    private void raise(BuildRef build, Figure figure) {
        for (var listener : listeners) {
            synchronized (gate) {
                if (disposed) {
                    return;
                }
            }
            try {
                listener.accept(build, figure);
            } catch (Throwable ignored) {
                /* Subscriber failures never stop accounting. */
            }
        }
    }

    public CompletableFuture<Void> closeAsync() {
        List<Measurement> running;
        synchronized (gate) {
            if (closing != null) {
                return closing;
            }
            disposed = true;
            closing = new CompletableFuture<>();
            running = new ArrayList<>(live);
            tracked.clear();
        }
        if (subscription != null) {
            subscription.close();
            pump.interrupt();
        }
        running.forEach(DownloadedFigures::cancelLater);
        CompletableFuture<Void> closed = closing;
        Thread.startVirtualThread(
                () -> {
                    try {
                        if (pump != null) {
                            pump.join();
                        }
                        measuring.closeAsync().join();
                        closed.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closed.completeExceptionally(e);
                    } catch (Throwable e) {
                        closed.completeExceptionally(CatalogTasks.unwrap(e));
                    }
                });
        return closed;
    }

    private static final class Measurement {
        final Tracked owner;
        final long sequence;
        final Cancellation token = new Cancellation();
        boolean pinned, abandoned, done;
        int waiters;
        BitSet touched;
        CompletableFuture<Figure> result;

        Measurement(Tracked owner, long sequence, boolean pinned) {
            this.owner = owner;
            this.sequence = sequence;
            this.pinned = pinned;
        }
    }

    private static final class Tracked {
        final Build build;
        final LedgerSource source;
        final List<Measurement> active = new ArrayList<>();
        Measurement shared;
        long lastUse, applied, storedObjects, storedBytes;
        boolean measured;
        ObjectLedger ledger;
        BitSet stored;

        Tracked(Build build, LedgerSource source) {
            this.build = build;
            this.source = source;
        }

        Figure figure() {
            return new Figure(
                    storedObjects,
                    ledger == null ? 0 : ledger.count(),
                    storedBytes,
                    ledger == null ? 0 : ledger.totalBytes());
        }

        boolean mark(int row, boolean present) {
            if (row < 0) {
                return false;
            }
            for (Measurement m : active) {
                if (m.touched != null) {
                    m.touched.set(row);
                }
            }
            return markRaw(row, present);
        }

        boolean markRaw(int row, boolean present) {
            if (stored.get(row) == present) {
                return false;
            }
            stored.set(row, present);
            storedObjects += present ? 1 : -1;
            storedBytes += (present ? 1 : -1) * ledger.size(row);
            return true;
        }
    }
}
