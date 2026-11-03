package com.vibeminer.store;

import java.nio.file.Path;

/** Excludes a legacy writer while a read-only source is borrowed during an explicit transition. */
public final class LegacyStoreLease implements AutoCloseable {
    private final WriterLock lock;

    private LegacyStoreLease(WriterLock lock) {
        this.lock = lock;
    }

    /**
     * Acquire on a background worker; the caller keeps the lease until its legacy reader closes.
     */
    public static LegacyStoreLease acquire(Path existingFolder) {
        if (!java.nio.file.Files.isDirectory(existingFolder)) {
            throw new IllegalArgumentException("Legacy store folder does not exist");
        }
        return new LegacyStoreLease(new WriterLock(existingFolder.resolve("writer.lock")));
    }

    @Override
    public void close() {
        lock.close();
    }
}
