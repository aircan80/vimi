package com.vibeminer.store;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.StoreTypes.*;

public interface ObjectWriter {
    CompletableFuture<List<PutResult>> put(List<PutObject> batch, boolean commitNow, Cancellation cancellation);
    CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation cancellation);
    CompletableFuture<ObjectStaging> stage(ObjectKey key, long declaredLength, Origin origin, Cancellation cancellation);
    default CompletableFuture<List<PutResult>> put(List<PutObject> batch) { return put(batch, false, Cancellation.NONE); }
    default CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys) { return delete(keys, Cancellation.NONE); }
}
