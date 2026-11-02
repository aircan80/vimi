package com.vibeminer.concepts.effects;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.tables.TableTypes.Priority;
import java.util.concurrent.CompletableFuture;

/**
 * One build and locale's effects. Calls may run concurrently; close rejects later calls and drains
 * accepted work.
 */
public interface BuildItemEffects extends AutoCloseable {
    BuildRef build();

    String locale();

    CompletableFuture<EffectsTypes.Page> get(
            long itemId, EffectsTypes.Query query, Priority priority, Cancellation cancellation);

    default CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
            Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Warmup not supported"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
