package com.vibeminer.formats.tables;

/**
 * Shared charge for decoded hotfix overlay views. One accountant spans every table handle that uses
 * it, so a workspace reopen (published, pending and preparation handles at once) stays within one
 * ceiling instead of one per handle. A table larger than {@link #tableBytes()} or one that cannot
 * be retained reads base rows only, with a visible reason.
 */
public final class HotfixMemory {
    /** Default process-wide accountant. */
    public static final HotfixMemory PROCESS = new HotfixMemory(64L << 20, 256L << 20);

    private final long tableBytes, totalBytes;
    private long reserved;

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
    public synchronized long reserved() {
        return reserved;
    }

    synchronized boolean reserve(long bytes) {
        if (bytes < 0 || bytes > totalBytes - reserved) {
            return false;
        }
        reserved += bytes;
        return true;
    }

    synchronized void release(long bytes) {
        if (bytes < 0 || bytes > reserved) {
            throw new IllegalStateException("Hotfix memory release exceeds reservation");
        }
        reserved -= bytes;
    }
}
