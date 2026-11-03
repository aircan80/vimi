package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public interface GeneratedTableStore {
    CompletableFuture<GeneratedTable> open(
            GeneratedTableSource source, KeyStatus keys, Cancellation token);

    /** Existing graph only; corrupt cache data must not trigger deletion or rebuilding. */
    default CompletableFuture<GeneratedTable> openPreparedLocal(
            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Nonrepair generated graph opening unsupported"));
    }

    CompletableFuture<GeneratedTable> store(
            GeneratedTableSource source,
            KeyStatus keys,
            GeneratedTableContent content,
            Cancellation token);

    CompletableFuture<GeneratedTable> getOrBuild(
            GeneratedTableSource source,
            KeyStatus keys,
            Function<Cancellation, CompletableFuture<GeneratedTableContent>> build,
            Cancellation token);

    /**
     * Enumerate a saved graph through nodes/manifests without reading row segments or repairing it.
     */
    default CompletableFuture<List<com.vibeminer.generated.GeneratedKey>> savedArtifacts(
            GeneratedTableSource source, KeyStatus keys, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Saved graph inventory unavailable"));
    }

    CompletableFuture<String> layoutOf(String contentKey, Cancellation token);

    default CompletableFuture<String> savedLayoutOf(String contentKey, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Read-only layout inventory unavailable"));
    }

    String identityOf(
            GeneratedTableSource source, KeyStatus keys, List<String> used, boolean encrypted);
}
