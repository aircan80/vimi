package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.PutResult;
import java.util.concurrent.CompletableFuture;

/**
 * Resumable encoded-object handle. Close keeps proven bytes; abandon removes them. Failed chunk
 * validation requires closing and starting a fresh handle to resume.
 */
public interface ObjectStaging {
    ObjectKey key();

    long declaredLength();

    long length();

    /** Rejects excess bytes as an argument error; cancellation leaves this append unapplied. */
    CompletableFuture<Void> append(byte[] bytes, Cancellation cancellation);

    /**
     * Requires complete, unrefused bytes; incomplete commit leaves the handle usable. A canceled
     * complete commit keeps the file for resume and releases this handle. Cancellation while
     * waiting for admission leaves this handle usable and cancels the future. Source-file
     * validation failures return a refusal for this object only.
     */
    CompletableFuture<PutResult> commit(Cancellation cancellation);

    CompletableFuture<Void> abandon();

    /** Releases this handle and keeps its file for resume. */
    CompletableFuture<Void> closeAsync();
}
