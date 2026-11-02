package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.tables.TableTypes.Priority;
import java.util.concurrent.CompletableFuture;

/** Concurrent item list/detail handle. Problems remain typed states, never fabricated absence. */
public interface BuildItems extends AutoCloseable {
    BuildRef build();

    String locale();

    CompletableFuture<ItemsPage> list(ItemQuery query, Priority priority, Cancellation token);

    CompletableFuture<ItemResult> get(long id, Priority priority, Cancellation token);

    /** Table facts and declared references only; never initiates asset I/O. */
    default CompletableFuture<ItemResult> getData(long id, Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Data-only detail not supported"));
    }

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
