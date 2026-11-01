package com.vibeminer.generated;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Derived bytes only: no downloads or knowledge of builds, tables or game concepts. */
public interface GeneratedStore {
    CompletableFuture<Boolean> has(GeneratedKey key, Cancellation cancellation);

    CompletableFuture<Read> read(GeneratedKey key, Cancellation cancellation);

    CompletableFuture<Read> readRange(
            GeneratedKey key, long offset, int length, Cancellation cancellation);

    CompletableFuture<List<PutOutcome>> put(List<Put> batch, Cancellation cancellation);

    CompletableFuture<Void> delete(List<GeneratedKey> keys, Cancellation cancellation);

    CompletableFuture<CompactReport> compact(
            double minDeadFraction, int maxPacks, Cancellation cancellation);

    default CompletableFuture<Boolean> has(GeneratedKey key) {
        return has(key, Cancellation.NONE);
    }

    default CompletableFuture<Read> read(GeneratedKey key) {
        return read(key, Cancellation.NONE);
    }

    default CompletableFuture<Read> readRange(GeneratedKey key, long offset, int length) {
        return readRange(key, offset, length, Cancellation.NONE);
    }

    default CompletableFuture<List<PutOutcome>> put(List<Put> batch) {
        return put(batch, Cancellation.NONE);
    }

    default CompletableFuture<Void> delete(List<GeneratedKey> keys) {
        return delete(keys, Cancellation.NONE);
    }
}
