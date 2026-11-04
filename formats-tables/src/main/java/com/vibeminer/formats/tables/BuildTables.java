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

    /**
     * Existing local generated graph only; never repairs, decodes missing tables or acquires data.
     */
    default CompletableFuture<CapturedRowsTypes.Capture> capturePreparedLocal(
            TableTypes.Ref table, Cancellation token) {
        return CompletableFuture.completedFuture(CapturedRowsTypes.Capture.unsupported());
    }

    /** Explicit one-table decode/preparation; configured source access may acquire data. */
    default CompletableFuture<NativePreparationTypes.Result> prepareNativeEvidence(
            TableTypes.Ref table,
            TableTypes.Priority priority,
            NativePreparationTypes.SourceAccess access,
            Consumer<NativePreparationTypes.Progress> progress,
            Cancellation token) {
        return CompletableFuture.completedFuture(
                new NativePreparationTypes.Result(
                        NativePreparationTypes.State.UNSUPPORTED, access, null, null));
    }

    /** Last compiled effective-view metadata only; never decodes/prepares/acquires a table. */
    default CompletableFuture<java.util.Optional<HotfixViewInfo>> hotfixInfo(
            TableTypes.Ref table, Long rowId, TableTypes.Priority priority, Cancellation token) {
        token.check();
        return CompletableFuture.completedFuture(java.util.Optional.empty());
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
