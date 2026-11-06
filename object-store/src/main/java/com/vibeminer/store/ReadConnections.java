package com.vibeminer.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded idle pool of WAL-mode read-only index connections.
 *
 * <ul>
 *   <li>A new connection passes the store's open probe before it first serves a read.
 *   <li>A connection is pooled again only after a read that finished normally and left no
 *       transaction open, and only if the pool was not retired or closed meanwhile.
 *   <li>Idle connections close after {@link #idleTtlNanos}, so an idle reader does not keep the
 *       index files open. On Windows an open handle blocks another process's index rebuild from
 *       deleting or replacing them.
 *   <li>Where the file system reports a file identity (POSIX inode), a connection opened on an
 *       index file that has since been replaced is never reused.
 * </ul>
 *
 * Immutable-mode connections are never pooled: they keep their page cache and would not see later
 * changes. Closing the pool closes every idle connection and makes later returns close theirs.
 */
final class ReadConnections {
    /** Default idle lifetime of a pooled connection. */
    static final long DEFAULT_IDLE_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** A connection with the pool generation and index file identity it was opened in. */
    static final class Pooled {
        final NativeSqlite db;
        final long generation;
        final Object identity;
        long idleSince;

        Pooled(NativeSqlite db, long generation, Object identity) {
            this.db = Objects.requireNonNull(db);
            this.generation = generation;
            this.identity = identity;
        }
    }

    private final int capacity;
    private final ArrayDeque<Pooled> idle = new ArrayDeque<>();
    private final LongSupplier clock;
    // Package-private so tests can shorten it.
    volatile long idleTtlNanos = DEFAULT_IDLE_TTL_NANOS;
    private long generation;
    private boolean closed, sweeping;

    ReadConnections(int capacity) {
        this(capacity, System::nanoTime);
    }

    ReadConnections(int capacity, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Read connection capacity must be positive");
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * The most recently returned idle connection on the index file with {@code identity}, or null.
     * Expired connections, and every idle connection when the file identity changed, are closed. A
     * null identity means the file system reports none; it is not compared.
     */
    Pooled take(Object identity) {
        List<Pooled> stale = new ArrayList<>();
        Pooled found = null;
        synchronized (this) {
            long now = clock.getAsLong();
            while (!closed && found == null && !idle.isEmpty()) {
                Pooled next = idle.pollFirst();
                if (identity != null && !identity.equals(next.identity)) {
                    stale.add(next); // The index file was replaced: none of these is current.
                    stale.addAll(idle);
                    idle.clear();
                } else if (now - next.idleSince >= idleTtlNanos) {
                    stale.add(next);
                } else {
                    found = next;
                }
            }
        }
        closeAll(stale);
        return found;
    }

    /** The generation a connection opened now belongs to. */
    synchronized long generation() {
        return generation;
    }

    /**
     * Returns {@code connection} after a read. It is pooled only when {@code reusable}, still in
     * the current generation, the pool is open and has room; otherwise it is closed.
     */
    void give(Pooled connection, boolean reusable) {
        synchronized (this) {
            if (reusable
                    && !closed
                    && connection.generation == generation
                    && idle.size() < capacity) {
                connection.idleSince = clock.getAsLong();
                idle.addFirst(connection);
                if (!sweeping) {
                    sweeping = true;
                    sweepLater(idleTtlNanos);
                }
                return;
            }
        }
        closeAll(List.of(connection));
    }

    private void sweepLater(long delay) {
        CompletableFuture.delayedExecutor(
                        Math.max(1, delay),
                        TimeUnit.NANOSECONDS,
                        command -> Thread.ofVirtual().name("vimi-store-read-sweep").start(command))
                .execute(this::sweep);
    }

    /** Closes idle connections past their lifetime; reschedules while any stay idle. */
    void sweep() {
        List<Pooled> expired = new ArrayList<>();
        synchronized (this) {
            long now = clock.getAsLong(), ttl = idleTtlNanos, next = Long.MAX_VALUE;
            for (Iterator<Pooled> it = idle.iterator(); it.hasNext(); ) {
                Pooled pooled = it.next();
                long age = now - pooled.idleSince;
                if (age >= ttl) {
                    expired.add(pooled);
                    it.remove();
                } else {
                    next = Math.min(next, ttl - age);
                }
            }
            if (closed || idle.isEmpty()) {
                sweeping = false;
            } else {
                sweepLater(next);
            }
        }
        closeAll(expired);
    }

    synchronized int idle() {
        return idle.size();
    }

    /**
     * Closes every idle connection and starts a new generation, so connections lent out now are
     * closed when they return.
     */
    void retire() {
        List<Pooled> all;
        synchronized (this) {
            generation++;
            all = new ArrayList<>(idle);
            idle.clear();
        }
        closeAll(all);
    }

    /** Closes every idle connection; later returns are closed instead of pooled. Idempotent. */
    void close() {
        synchronized (this) {
            closed = true;
        }
        retire();
    }

    /**
     * Closes read-only connections. A failure to close one has no durability consequence, so it is
     * logged rather than thrown: it must never skip a store's clean-close mark or the closing of
     * its writer connection and lock.
     */
    private static void closeAll(List<Pooled> connections) {
        for (Pooled pooled : connections) {
            try {
                pooled.db.close();
            } catch (RuntimeException error) {
                System.getLogger(ReadConnections.class.getName())
                        .log(System.Logger.Level.WARNING, "Read connection close failed", error);
            }
        }
    }
}
