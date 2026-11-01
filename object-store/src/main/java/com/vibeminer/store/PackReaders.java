package com.vibeminer.store;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded retained handles. Leases keep retired/overflow handles alive until their last release.
 */
final class PackReaders<H extends Closeable> implements Closeable {
    @FunctionalInterface
    interface Opener<H> {
        H open(int pack) throws IOException;
    }

    record State(int cached, int openHandles, int leases) {}

    private final int capacity;
    private final Opener<H> opener;
    private final Map<Integer, Entry> cached = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;
    private int openHandles;
    private int leases;

    private final class Entry {
        final H handle;
        final Object identity = new Object();
        int leases = 1;
        boolean retired;
        boolean closed;

        Entry(H handle) {
            this.handle = handle;
        }
    }

    final class Lease implements Closeable {
        private final Entry entry;
        private boolean released;

        private Lease(Entry entry) {
            this.entry = entry;
        }

        H handle() {
            synchronized (PackReaders.this) {
                if (released) {
                    throw new IllegalStateException("Pack lease is released");
                }
                return entry.handle;
            }
        }

        Object identity() {
            synchronized (PackReaders.this) {
                if (released) {
                    throw new IllegalStateException("Pack lease is released");
                }
                return entry.identity;
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (PackReaders.this) {
                if (released) {
                    return;
                }
                released = true;
                leases--;
                if (--entry.leases == 0 && (entry.retired || closed)) {
                    closeEntry(entry);
                }
            }
        }
    }

    PackReaders(int capacity, Opener<H> opener) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Pack handle capacity must be positive");
        }
        this.capacity = capacity;
        this.opener = Objects.requireNonNull(opener);
    }

    synchronized Lease acquire(int pack) throws IOException {
        if (closed) {
            throw ObjectStore.closed();
        }
        Entry entry = cached.get(pack);
        if (entry != null) {
            entry.leases++;
            leases++;
            return new Lease(entry);
        }
        H handle;
        try {
            handle = opener.open(pack);
        } catch (NoSuchFileException missing) {
            return null;
        }
        entry = new Entry(handle);
        openHandles++;
        try {
            if (cached.size() < capacity || evictIdle()) {
                cached.put(pack, entry);
            } else {
                // All cached entries are pinned. Do not wait or enlarge the retained cache.
                entry.retired = true;
            }
        } catch (IOException | RuntimeException | Error error) {
            try {
                closeEntry(entry);
            } catch (IOException | RuntimeException | Error cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
        leases++;
        return new Lease(entry);
    }

    private boolean evictIdle() throws IOException {
        Iterator<Entry> entries = cached.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (entry.leases == 0) {
                entries.remove();
                entry.retired = true;
                closeEntry(entry);
                return true;
            }
        }
        return false;
    }

    synchronized void invalidate(int pack) throws IOException {
        Entry entry = cached.remove(pack);
        if (entry == null) {
            return;
        }
        entry.retired = true;
        if (entry.leases == 0) {
            closeEntry(entry);
        }
    }

    private void closeEntry(Entry entry) throws IOException {
        if (entry.closed) {
            return;
        }
        entry.closed = true;
        try {
            entry.handle.close();
        } finally {
            openHandles--;
        }
    }

    synchronized State state() {
        return new State(cached.size(), openHandles, leases);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        for (Entry entry : cached.values()) {
            entry.retired = true;
            if (entry.leases == 0) {
                try {
                    closeEntry(entry);
                } catch (IOException | RuntimeException | Error error) {
                    if (failure == null) {
                        failure = error;
                    } else {
                        failure.addSuppressed(error);
                    }
                }
            }
        }
        cached.clear();
        if (failure instanceof IOException error) {
            throw error;
        }
        if (failure instanceof RuntimeException error) {
            throw error;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
