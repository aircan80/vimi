package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import java.io.IOException;
import java.util.Objects;

/** Shared, nonblocking reservations for one comparison's live buffers and scratch files. */
public final class TempBudget implements AutoCloseable {
    private static final int MAX_RESERVATIONS = 1024;
    private final long memoryLimit;
    private final long diskLimit;
    private long memory, disk, peakMemory, peakDisk;
    private int reservations;
    private boolean closed;

    public TempBudget() {
        this(ComparisonService.BUFFER_BYTES, ComparisonService.STAGING_BYTES);
    }

    public TempBudget(long memoryLimit, long diskLimit) {
        if (memoryLimit < 1
                || memoryLimit > ComparisonService.BUFFER_BYTES
                || diskLimit < 1
                || diskLimit > ComparisonService.STAGING_BYTES) {
            throw new IllegalArgumentException("Invalid comparison resource limits");
        }
        this.memoryLimit = memoryLimit;
        this.diskLimit = diskLimit;
    }

    public record Snapshot(
            long memory, long disk, long peakMemory, long peakDisk, int reservations) {}

    /** Quota failure is an incomplete-policy outcome, rather than corrupt source data. */
    public static final class Limit extends IOException {
        private static final long serialVersionUID = 1L;

        private Limit(String message) {
            super(message);
        }
    }

    public Reservation reserveMemory(String owner, long bytes, Cancellation token) throws Limit {
        return reserve(owner, bytes, true, token);
    }

    public Reservation reserveDisk(String owner, long bytes, Cancellation token) throws Limit {
        return reserve(owner, bytes, false, token);
    }

    private synchronized Reservation reserve(
            String owner, long bytes, boolean inMemory, Cancellation token) throws Limit {
        checkOwner(owner);
        checkAmount(bytes);
        Objects.requireNonNull(token).check();
        checkOpen();
        if (reservations >= MAX_RESERVATIONS) {
            throw new Limit("Comparison reservation count limit reached");
        }
        charge(bytes, inMemory);
        reservations++;
        return new Reservation(owner, bytes, inMemory);
    }

    private void charge(long bytes, boolean inMemory) throws Limit {
        long used = inMemory ? memory : disk;
        long limit = inMemory ? memoryLimit : diskLimit;
        // Subtraction avoids overflow even when a hostile requested amount is Long.MAX_VALUE.
        if (bytes > limit - used) {
            throw new Limit(
                    inMemory
                            ? "Shared comparison buffer limit reached"
                            : "Shared comparison temporary-file limit reached");
        }
        if (inMemory) {
            memory += bytes;
            peakMemory = Math.max(peakMemory, memory);
        } else {
            disk += bytes;
            peakDisk = Math.max(peakDisk, disk);
        }
    }

    private void uncharge(long bytes, boolean inMemory) {
        if (inMemory) {
            memory -= bytes;
        } else {
            disk -= bytes;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(memory, disk, peakMemory, peakDisk, reservations);
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Comparison resource budget closed");
        }
    }

    private static void checkAmount(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Negative resource amount");
        }
    }

    private static void checkOwner(String owner) {
        if (owner == null || !owner.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("Use a bounded resource-stage label");
        }
    }

    /**
     * Detects outstanding resources instead of resetting their accounting. A failed close leaves
     * the budget open so owners can finish cleanup and retry. Never persists session reservations.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (reservations != 0 || memory != 0 || disk != 0) {
            throw new IllegalStateException("Comparison resources have not drained");
        }
        closed = true;
    }

    /**
     * Single owned reservation. Reserve before allocating/writing; for disk, shrink/close only
     * after successful truncation/deletion. Closing a reservation does not remove a file.
     */
    public final class Reservation implements AutoCloseable {
        private String owner;
        private long bytes;
        private final boolean inMemory;
        private boolean released;

        private Reservation(String owner, long bytes, boolean inMemory) {
            this.owner = owner;
            this.bytes = bytes;
            this.inMemory = inMemory;
        }

        public long size() {
            synchronized (TempBudget.this) {
                return bytes;
            }
        }

        public String owner() {
            synchronized (TempBudget.this) {
                return owner;
            }
        }

        public void grow(long additionalBytes, Cancellation token) throws Limit {
            synchronized (TempBudget.this) {
                checkAmount(additionalBytes);
                Objects.requireNonNull(token).check();
                requireOwned();
                charge(additionalBytes, inMemory);
                bytes += additionalBytes;
            }
        }

        public void shrink(long releasedBytes) {
            synchronized (TempBudget.this) {
                checkAmount(releasedBytes);
                requireOwned();
                if (releasedBytes > bytes) {
                    throw new IllegalArgumentException("Excess release");
                }
                uncharge(releasedBytes, inMemory);
                bytes -= releasedBytes;
            }
        }

        /** Transfers stage ownership without a release/reacquire gap or a second reservation. */
        public Reservation transferTo(String nextOwner) {
            synchronized (TempBudget.this) {
                checkOwner(nextOwner);
                requireOwned();
                owner = nextOwner;
                return this;
            }
        }

        private void requireOwned() {
            checkOpen();
            if (released) {
                throw new IllegalStateException("Comparison reservation released");
            }
        }

        @Override
        public void close() {
            synchronized (TempBudget.this) {
                if (released) {
                    return;
                }
                uncharge(bytes, inMemory);
                bytes = 0;
                reservations--;
                released = true;
            }
        }
    }
}
