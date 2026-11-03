package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Lazy per-display preview component borrowing one build's tables, files and generated store. */
public interface BuildDisplayModels extends AutoCloseable {
    CompletableFuture<DisplayModelTypes.Preview> preview(long displayId, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
