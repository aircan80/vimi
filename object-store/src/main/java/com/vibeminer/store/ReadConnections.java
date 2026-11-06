package com.vibeminer.store;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded idle pool of read-only index connections. A new connection passes the store's open probe
 * before it first serves a read. A connection is pooled again only after a read that finished
 * normally and left no transaction open, only in the access mode it was opened in, and only if the
 * pool was not retired or closed meanwhile. Closing the pool closes every idle connection and makes
 * later returns close theirs.
 */
final class ReadConnections {
    /** A connection with the access mode and pool generation it was opened in. */
    record Pooled(NativeSqlite db, boolean immutable, long generation) {}

    private final int capacity;
    private final ArrayDeque<Pooled> idle = new ArrayDeque<>();
    private long generation;
    private boolean closed;

    ReadConnections(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Read connection capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * An idle connection opened in {@code immutable} mode, or null. Idle connections opened in the
     * other mode are closed: none outlives a switch of access mode.
     */
    Pooled take(boolean immutable) {
        List<Pooled> stale = new ArrayList<>();
        Pooled found = null;
        synchronized (this) {
            while (!closed && found == null && !idle.isEmpty()) {
                Pooled next = idle.pollFirst();
                if (next.immutable() == immutable) {
                    found = next;
                } else {
                    stale.add(next);
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
     * the current {@code immutable} mode and generation, the pool is open and has room; otherwise
     * it is closed.
     */
    void give(Pooled connection, boolean reusable, boolean immutable) {
        synchronized (this) {
            if (reusable
                    && !closed
                    && connection.immutable() == immutable
                    && connection.generation() == generation
                    && idle.size() < capacity) {
                idle.addFirst(connection);
                return;
            }
        }
        connection.db().close();
    }

    synchronized int idle() {
        return idle.size();
    }

    /**
     * Closes every idle connection and starts a new generation, so connections lent out now are
     * closed when they return. Used after maintenance that rewrites the index in place.
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

    private static void closeAll(List<Pooled> connections) {
        RuntimeException failure = null;
        for (Pooled pooled : connections) {
            try {
                pooled.db().close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
