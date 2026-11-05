package com.vibeminer.formats.tables;

import java.lang.ref.Cleaner;

/**
 * Shared charge for decoded hotfix overlay views. One accountant spans every table handle that uses
 * it, so a workspace reopen (published, pending and preparation handles at once) stays within one
 * ceiling instead of one per handle. A table larger than {@link #tableBytes()} or one that cannot
 * be retained reads base rows only, with a visible reason.
 *
 * <p>Handles release their charge on close. A handle that is never closed releases it when it
 * becomes unreachable, so a leak cannot hold the shared ceiling forever.
 */
public final class HotfixMemory {
    /** Default process-wide accountant. */
    public static final HotfixMemory PROCESS = new HotfixMemory(64L << 20, 256L << 20);

    private static final Cleaner CLEANER = Cleaner.create();

    private final long tableBytes, totalBytes;
    // Private monitor: outside code that locks the public PROCESS instance cannot contend with or
    // deadlock against the accountant.
    private final Object lock = new Object();
    private long reserved, generation;

    public HotfixMemory(long tableBytes, long totalBytes) {
        if (tableBytes < 1 << 20 || totalBytes < tableBytes) {
            throw new IllegalArgumentException("Hotfix memory bounds");
        }
        this.tableBytes = tableBytes;
        this.totalBytes = totalBytes;
    }

    /** Largest decoded charge for one table view. */
    public long tableBytes() {
        return tableBytes;
    }

    /** Ceiling across every retained view charged to this accountant. */
    public long totalBytes() {
        return totalBytes;
    }

    /** Bytes currently charged by retained views. */
    public long reserved() {
        synchronized (lock) {
            return reserved;
        }
    }

    long available() {
        synchronized (lock) {
            return totalBytes - reserved;
        }
    }

    /** The monitor guarding this accountant and every handle charge drawn from it. */
    Object lock() {
        return lock;
    }

    /** Advances on every release, so a refused view retries only once memory was freed. */
    long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    boolean reserve(long bytes) {
        synchronized (lock) {
            if (bytes < 0 || bytes > totalBytes - reserved) {
                return false;
            }
            reserved += bytes;
            return true;
        }
    }

    void release(long bytes) {
        synchronized (lock) {
            if (bytes < 0 || bytes > reserved) {
                throw new IllegalStateException("Hotfix memory release exceeds reservation");
            }
            reserved -= bytes;
            if (bytes > 0) {
                generation++;
            }
        }
    }

    /** One handle's share; whatever it still holds is released on close or when unreachable. */
    Charge charge(Object owner) {
        var charge = new Charge(this);
        CLEANER.register(owner, charge::releaseAll);
        return charge;
    }

    /**
     * Guarded by the accountant's private monitor only, so no lock order exists between the two.
     */
    static final class Charge {
        private final HotfixMemory memory;
        private long held;

        private Charge(HotfixMemory memory) {
            this.memory = memory;
        }

        boolean reserve(long bytes) {
            synchronized (memory.lock) {
                if (!memory.reserve(bytes)) {
                    return false;
                }
                held += bytes;
                return true;
            }
        }

        void release(long bytes) {
            synchronized (memory.lock) {
                if (bytes > held) {
                    throw new IllegalStateException("Hotfix handle release exceeds its charge");
                }
                held -= bytes;
                memory.release(bytes);
            }
        }

        void releaseAll() {
            synchronized (memory.lock) {
                long all = held;
                held = 0;
                memory.release(all);
            }
        }
    }
}
