package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.DeleteResult;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous writes use FIFO admission with bounded request and payload budgets. A canceled
 * admission waiter completes exceptionally with CancellationException; admitted operations apply
 * their per-operation cancellation semantics. Close drains accepted work and rejects waiters.
 */
public interface ObjectWriter {
    /**
     * Stored objects become visible and this future completes only after their groups commit.
     * commitNow closes the group after this request; idle queues also flush immediately.
     * Cancellation skips objects not yet appended; appended objects still commit.
     */
    CompletableFuture<List<PutResult>> put(
            List<PutObject> batch, boolean commitNow, Cancellation cancellation);

    CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation cancellation);

    CompletableFuture<ObjectStaging> stage(
            ObjectKey key, long declaredLength, Origin origin, Cancellation cancellation);

    default CompletableFuture<List<PutResult>> put(List<PutObject> batch) {
        return put(batch, false, Cancellation.NONE);
    }

    default CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys) {
        return delete(keys, Cancellation.NONE);
    }
}
