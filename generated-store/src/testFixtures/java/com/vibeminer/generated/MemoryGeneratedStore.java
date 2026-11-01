package com.vibeminer.generated;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Shared fake for consumers: scripted damage and counters, with the same contract as disk. */
public final class MemoryGeneratedStore implements GeneratedStore {
    private final Map<GeneratedKey, byte[]> objects = new HashMap<>();
    private final Set<GeneratedKey> corrupt = new HashSet<>();
    private int reads, rangeReads, puts;

    public synchronized int reads() {
        return reads;
    }

    public synchronized int rangeReads() {
        return rangeReads;
    }

    public synchronized int puts() {
        return puts;
    }

    public synchronized List<GeneratedKey> keys() {
        return List.copyOf(objects.keySet());
    }

    public synchronized void damage(GeneratedKey key) {
        corrupt.add(key);
    }

    public synchronized void clear() {
        objects.clear();
        corrupt.clear();
    }

    @Override
    public synchronized CompletableFuture<Boolean> has(
            GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return objects.containsKey(key);
                });
    }

    @Override
    public synchronized CompletableFuture<Read> read(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    reads++;
                    return readBytes(key, 0, -1);
                });
    }

    @Override
    public synchronized CompletableFuture<Read> readRange(
            GeneratedKey key, long offset, int length, Cancellation cancellation) {
        return call(
                () -> {
                    if (offset < 0 || length < 0 || length > GeneratedTypes.MAX_OBJECT_BYTES) {
                        throw new IllegalArgumentException("Invalid range");
                    }
                    cancellation.check();
                    rangeReads++;
                    return readBytes(key, offset, length);
                });
    }

    private Read readBytes(GeneratedKey key, long offset, int length) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            return new Read(ReadState.ABSENT, 0, null, null);
        }
        if (length >= 0 && (offset > bytes.length || length > bytes.length - offset)) {
            throw new IllegalArgumentException("Range past end");
        }
        if (corrupt.contains(key)) {
            return new Read(ReadState.CORRUPT, 0, null, "Scripted damage");
        }
        return new Read(
                ReadState.PRESENT,
                bytes.length,
                length < 0 ? bytes : Arrays.copyOfRange(bytes, (int) offset, (int) offset + length),
                null);
    }

    @Override
    public synchronized CompletableFuture<List<PutOutcome>> put(
            List<Put> batch, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    List<Put> copy = List.copyOf(batch);
                    if (copy.size() > GeneratedTypes.MAX_BATCH) {
                        throw new GeneratedStoreException(GeneratedTypes.LIMIT, "Too many objects");
                    }
                    for (Put put : copy) {
                        if (put.data().length == 0
                                || put.data().length > GeneratedTypes.MAX_OBJECT_BYTES) {
                            throw new GeneratedStoreException(
                                    GeneratedTypes.LIMIT, "Empty or oversized object");
                        }
                    }
                    puts++;
                    List<PutOutcome> result = new ArrayList<>();
                    for (Put put : copy) {
                        if (objects.containsKey(put.key())) {
                            result.add(PutOutcome.ALREADY_PRESENT);
                        } else {
                            objects.put(put.key(), put.data().clone());
                            result.add(PutOutcome.STORED);
                        }
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public synchronized CompletableFuture<Void> delete(
            List<GeneratedKey> keys, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    for (GeneratedKey key : List.copyOf(keys)) {
                        objects.remove(key);
                        corrupt.remove(key);
                    }
                    return null;
                });
    }

    @Override
    public CompletableFuture<CompactReport> compact(
            double fraction, int maxPacks, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    if (!(fraction >= 0 && fraction <= 1) || maxPacks < 0) {
                        throw new IllegalArgumentException("Invalid compaction bounds");
                    }
                    return new CompactReport(0, 0, 0, 0, 0);
                });
    }

    private static <T> CompletableFuture<T> call(Supplier<T> action) {
        try {
            return CompletableFuture.completedFuture(action.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
