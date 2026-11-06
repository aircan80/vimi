package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.OpenReport;
import com.vibeminer.store.StoreTypes.ScrubReport;
import com.vibeminer.store.StoreTypes.Stats;
import com.vibeminer.store.StoreTypes.VerifyReport;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Deliberately exposes no writer, staging, scan or compaction API. */
public final class ReadOnlyStore implements ObjectReader, HistoryReader {
    private final ObjectStore inner;

    ReadOnlyStore(ObjectStore inner) {
        this.inner = inner;
    }

    // Same-package tests inspect the wrapped store.
    ObjectStore inner() {
        return inner;
    }

    public OpenReport openReport() {
        return inner.openReport();
    }

    @Override
    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation c) {
        return inner.has(key, c);
    }

    @Override
    public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation c) {
        return inner.read(key, c);
    }

    @Override
    public CompletableFuture<ObjectRead> readRange(
            ObjectKey key, long offset, int length, Cancellation c) {
        return inner.readRange(key, offset, length, c);
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(History.Query q, Cancellation c) {
        return inner.readHistory(q, c);
    }

    public CompletableFuture<Stats> stats() {
        return inner.stats();
    }

    public CompletableFuture<VerifyReport> verify(boolean full, Cancellation c) {
        return inner.verify(full, c);
    }

    public CompletableFuture<ScrubReport> scrub(boolean full, Cancellation c) {
        return inner.scrub(full, c);
    }

    public CompletableFuture<Void> closeAsync() {
        return inner.closeAsync();
    }
}
