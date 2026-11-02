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

    default CompletableFuture<ItemGridPage> grid(
            ItemGridQuery query, Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Derived item grid unavailable"));
    }

    default CompletableFuture<ItemClassLabels> classifications(
            ItemSummary item, Priority priority, Cancellation token) {
        return getData(item.id(), priority, token)
                .thenApply(result -> result.detail() == null ? null : result.detail().labels());
    }

    CompletableFuture<ItemResult> get(long id, Priority priority, Cancellation token);

    /** Table facts and declared references only; never initiates asset I/O. */
    default CompletableFuture<ItemResult> getData(long id, Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Data-only detail not supported"));
    }

    default CompletableFuture<ItemInspection> inspect(
            long id, Priority priority, Cancellation token) {
        return getData(id, priority, token)
                .thenApply(
                        result ->
                                new ItemInspection(
                                        result,
                                        result.detail() == null
                                                ? new ItemAttributes(
                                                        ItemAttributes.State.UNAVAILABLE,
                                                        java.util.List.of(),
                                                        java.util.List.of(
                                                                "Item details unavailable"))
                                                : result.detail().attributes(),
                                        "Direct base source values"));
    }

    default CompletableFuture<java.util.List<String>> prepareAttributes(
            Priority priority, Cancellation token) {
        return CompletableFuture.completedFuture(java.util.List.of());
    }

    default CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
            Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Warmup not supported"));
    }

    /** Current derived database files only; empty means the projection has not been prepared. */
    default java.util.List<java.nio.file.Path> savedDatabaseFiles() {
        return java.util.List.of();
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
