package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.files.AsyncCursor;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Concurrent table reads for one build and locale. Close rejects new work and drains accepted
 * calls.
 */
public interface BuildTables extends AutoCloseable {
    BuildRef build();

    String locale();

    AsyncCursor<TableTypes.Entry> list(
            TableTypes.ListFilter filter, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.Entry> check(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.SchemaResult> schema(
            TableTypes.Ref table, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.Page> page(
            TableTypes.PageRequest request, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.RowResult> get(
            TableTypes.Ref table, long id, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.FollowResult> follow(
            TableTypes.FollowRequest request, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.ReferencesResult> referencedBy(
            TableTypes.ReferencesRequest request, TableTypes.Priority priority, Cancellation token);

    CompletableFuture<TableTypes.PreloadResult> preload(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token);

    /**
     * Processes the frozen known-table inventory. Unlike preload, tables contains every known
     * outcome (including absent entries); COMPLETED means assessed, not universally readable.
     */
    default CompletableFuture<TableTypes.PreloadResult> prepareKnown(
            Consumer<TableTypes.PreloadProgress> progress, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Known-table preparation is not supported"));
    }

    /** Existing local generated graph only; never creates a table or opens encoded payloads. */
    default CompletableFuture<java.util.List<com.vibeminer.generated.GeneratedKey>> savedArtifacts(
            TableTypes.Ref table, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Saved artifact inventory unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
