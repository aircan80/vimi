package com.vibeminer.generated;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Collects one writer's puts so they reach the lower store as one put on {@link #commit}, which is
 * one durable commit (one pack sync and one index transaction) when the batch fits a single store
 * commit.
 *
 * <p>Objects reach the lower store in the order they were first put. A lower store that splits a
 * batch, a cancellation, or a crash keeps at most an ordered prefix of it, never a later object
 * without an earlier one. So the caller puts the object that references the others last. When the
 * collected bytes would exceed {@code maxBytes} (or {@code maxObjects}), the collected objects are
 * put first, as their own commit, and collection continues.
 *
 * <p>A lower store must not commit an object after one it refused in the same put: the pack stores
 * use {@code putUnlessRefused}, which refuses the whole put, and put their parts one at a time,
 * each only after the previous part committed with nothing refused.
 *
 * <p>Collected bytes are bounded twice: per group by {@code maxBytes}, and across groups by an
 * optional shared {@link Budget}. When adding an object would pass either bound, the objects
 * collected so far are put first. So many concurrent groups cost more commits, never more memory
 * than the budget plus one object per group.
 *
 * <p>Reads see collected objects, so a writer can read back what it is about to commit. A collected
 * object the lower store already holds reads from the lower store, because that copy is the one
 * that remains after the commit (the lower store reports it {@code ALREADY_PRESENT} and keeps it).
 * This is decided at read time: if another writer commits the same key between this read and our
 * commit, the stored copy is that writer's. Keys are content hashes or deterministic, so both
 * copies hold the same bytes. Deletes apply to both.
 *
 * <p>One writer uses a group at a time; after {@link #commit} the group reads and writes through to
 * the lower store. After any failure (of a put, an early flush or the commit) the group's outcome
 * is unknown: the writer abandons it and calls {@link #close}, which drops what is still collected.
 * A prefix may already be in the lower store.
 */
public final class GeneratedCommitGroup implements GeneratedStore, AutoCloseable {
    /** The largest single commit of the pack-backed stores, and their per-commit object bound. */
    public static final long MAX_BYTES = 16L << 20;

    public static final int MAX_OBJECTS = 128;

    /** Collected bytes shared by several groups, such as all tables one store writes at once. */
    public static final class Budget {
        private final long limit;
        private long used;

        public Budget(long limit) {
            if (limit < 1) {
                throw new IllegalArgumentException("Budget must be positive");
            }
            this.limit = limit;
        }

        /** Bytes currently collected by groups using this budget. */
        public synchronized long used() {
            return used;
        }

        synchronized boolean fits(long length) {
            return used + length <= limit;
        }

        synchronized void add(long length) {
            used += length;
        }
    }

    private final GeneratedStore lower;
    private final long maxBytes;
    private final int maxObjects;
    private final Budget budget;
    private final Map<GeneratedKey, Put> pending = new LinkedHashMap<>();
    private long bytes;

    public GeneratedCommitGroup(GeneratedStore lower) {
        this(lower, MAX_BYTES, MAX_OBJECTS, null);
    }

    public GeneratedCommitGroup(GeneratedStore lower, long maxBytes, int maxObjects) {
        this(lower, maxBytes, maxObjects, null);
    }

    public GeneratedCommitGroup(
            GeneratedStore lower, long maxBytes, int maxObjects, Budget budget) {
        this.lower = Objects.requireNonNull(lower);
        if (maxBytes < 1 || maxObjects < 1 || maxObjects > GeneratedTypes.MAX_BATCH) {
            throw new IllegalArgumentException("Invalid commit group bounds");
        }
        this.maxBytes = maxBytes;
        this.maxObjects = maxObjects;
        this.budget = budget;
    }

    /** Takes the collected objects and returns their bytes to the budget. Caller holds the lock. */
    private List<Put> drain() {
        List<Put> batch = List.copyOf(pending.values());
        pending.clear();
        if (budget != null) {
            budget.add(-bytes);
        }
        bytes = 0;
        return batch;
    }

    /** Drops what is still collected, after a failure or when the group is no longer needed. */
    @Override
    public synchronized void close() {
        drain();
    }

    /** Objects collected and not yet put to the lower store. */
    public synchronized int pending() {
        return pending.size();
    }

    /** Puts every collected object to the lower store as one batch, in collection order. */
    public CompletableFuture<List<PutOutcome>> commit(Cancellation cancellation) {
        List<Put> batch;
        synchronized (this) {
            batch = drain();
        }
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return lower.put(batch, cancellation);
    }

    /**
     * Collects the batch. A collected object reports {@code STORED}; the lower store decides on
     * commit whether it was already present. An object collected twice reports {@code
     * ALREADY_PRESENT} and keeps its first position.
     */
    @Override
    public CompletableFuture<List<PutOutcome>> put(List<Put> batch, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    List<Put> owned = List.copyOf(batch);
                    if (owned.size() > GeneratedTypes.MAX_BATCH) {
                        throw new GeneratedStoreException(
                                GeneratedTypes.LIMIT, "Too many objects in one put");
                    }
                    for (Put put : owned) {
                        if (put.data().length == 0
                                || put.data().length > GeneratedTypes.MAX_OBJECT_BYTES) {
                            throw new GeneratedStoreException(
                                    GeneratedTypes.LIMIT, "An object is empty or over 64 MiB");
                        }
                    }
                    var outcomes = new ArrayList<PutOutcome>();
                    for (Put put : owned) {
                        outcomes.add(collect(put, cancellation));
                    }
                    return CompletableFuture.completedFuture(List.copyOf(outcomes));
                });
    }

    private PutOutcome collect(Put put, Cancellation cancellation) {
        List<Put> full = null;
        synchronized (this) {
            if (pending.containsKey(put.key())) {
                return PutOutcome.ALREADY_PRESENT;
            }
            long length = put.data().length;
            if (!pending.isEmpty()
                    && (bytes + length > maxBytes
                            || pending.size() >= maxObjects
                            || budget != null && !budget.fits(length))) {
                full = drain();
            }
            pending.put(put.key(), put);
            bytes += length;
            if (budget != null) {
                budget.add(length);
            }
        }
        if (full != null) {
            // Earlier objects commit first, so the order (and the prefix property) is kept. Its
            // outcomes can only be STORED or ALREADY_PRESENT; a refusal or failure throws.
            lower.put(full, cancellation).join();
        }
        return PutOutcome.STORED;
    }

    private synchronized Put collected(GeneratedKey key) {
        return pending.get(key);
    }

    @Override
    public CompletableFuture<Boolean> has(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return collected(key) != null
                            ? CompletableFuture.completedFuture(true)
                            : lower.has(key, cancellation);
                });
    }

    @Override
    public CompletableFuture<Read> read(GeneratedKey key, Cancellation cancellation) {
        return readCore(key, 0, -1, cancellation);
    }

    @Override
    public CompletableFuture<Read> readRange(
            GeneratedKey key, long offset, int length, Cancellation cancellation) {
        if (offset < 0 || length < 0 || length > GeneratedTypes.MAX_OBJECT_BYTES) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Range is past the object's end"));
        }
        return readCore(key, offset, length, cancellation);
    }

    private CompletableFuture<Read> readCore(
            GeneratedKey key, long offset, int length, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    Put put = collected(key);
                    if (put == null || lower.has(key, cancellation).join()) {
                        return length < 0
                                ? lower.read(key, cancellation)
                                : lower.readRange(key, offset, length, cancellation);
                    }
                    byte[] data = put.data();
                    if (length < 0) {
                        return CompletableFuture.completedFuture(
                                new Read(ReadState.PRESENT, data.length, data, null));
                    }
                    if (offset > data.length || length > data.length - offset) {
                        throw new IllegalArgumentException("Range is past the object's end");
                    }
                    return CompletableFuture.completedFuture(
                            new Read(
                                    ReadState.PRESENT,
                                    data.length,
                                    Arrays.copyOfRange(data, (int) offset, (int) offset + length),
                                    null));
                });
    }

    @Override
    public CompletableFuture<Void> delete(List<GeneratedKey> keys, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    List<GeneratedKey> owned = List.copyOf(keys);
                    synchronized (this) {
                        for (GeneratedKey key : owned) {
                            Put removed = pending.remove(key);
                            if (removed != null) {
                                bytes -= removed.data().length;
                                if (budget != null) {
                                    budget.add(-removed.data().length);
                                }
                            }
                        }
                    }
                    return lower.delete(owned, cancellation);
                });
    }

    @Override
    public CompletableFuture<CompactReport> compact(
            double minDeadFraction, int maxPacks, Cancellation cancellation) {
        return lower.compact(minDeadFraction, maxPacks, cancellation);
    }

    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(
                    e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                            ? e.getCause()
                            : e);
        }
    }
}
