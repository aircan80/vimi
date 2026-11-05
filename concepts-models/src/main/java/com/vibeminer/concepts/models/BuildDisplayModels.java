package com.vibeminer.concepts.models;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Lazy per-display preview component borrowing one build's tables, files and generated store. */
public interface BuildDisplayModels extends AutoCloseable {
    CompletableFuture<DisplayModelTypes.Preview> preview(long displayId, Cancellation token);

    /**
     * Lazily qualifies the display's idle (Stand) sequence against the same rest surface that
     * {@link #preview} returns. Implementations without animation support report UNAVAILABLE.
     */
    default CompletableFuture<DisplayModelTypes.Idle> idle(long displayId, Cancellation token) {
        return CompletableFuture.completedFuture(
                DisplayModelTypes.Idle.unavailable(
                        "Idle animation is not provided by this source."));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
