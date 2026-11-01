package com.vibeminer.store;

import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.StoreTypes.ObjectRead;

public interface ObjectReader {
    CompletableFuture<Boolean> has(ObjectKey key, Cancellation cancellation);
    CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation cancellation);
    /** Checks all intersecting blocks before returning any bytes. */
    CompletableFuture<ObjectRead> readRange(ObjectKey key, long offset, int length, Cancellation cancellation);
    default CompletableFuture<Boolean> has(ObjectKey key) { return has(key, Cancellation.NONE); }
    default CompletableFuture<ObjectRead> read(ObjectKey key) { return read(key, Cancellation.NONE); }
    default CompletableFuture<ObjectRead> readRange(ObjectKey key, long offset, int length) {
        return readRange(key, offset, length, Cancellation.NONE);
    }
}
