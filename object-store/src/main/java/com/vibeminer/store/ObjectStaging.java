package com.vibeminer.store;

import java.util.concurrent.CompletableFuture;
import com.vibeminer.store.StoreTypes.PutResult;

public interface ObjectStaging {
    ObjectKey key();
    long declaredLength();
    long length();
    CompletableFuture<Void> append(byte[] bytes, Cancellation cancellation);
    CompletableFuture<PutResult> commit(Cancellation cancellation);
    CompletableFuture<Void> abandon();
    /** Releases this handle and keeps its file for resume. */
    CompletableFuture<Void> closeAsync();
}
