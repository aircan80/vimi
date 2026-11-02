package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Prepared table reads may use stored objects, never CDN fallback or fresh observations. */
final class LocalMetadataSource implements ObjectSource {
    private final ObjectSource source;

    LocalMetadataSource(ObjectSource source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext ignored, FetchPriority priority, Cancellation token) {
        return source.getKey(request, null, priority, token);
    }

    @Override
    public CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext ignored,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return source.getKeys(requests, null, priority, progress, token);
    }

    @Override
    public CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext ignored,
            FetchPriority priority,
            Cancellation token) {
        return source.getRange(request, offset, length, null, priority, token);
    }

    @Override
    public CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        token.check();
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Prepared metadata does not refresh observations"));
    }

    @Override
    public CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        token.check();
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException(
                        "Prepared metadata does not record observations"));
    }

    @Override
    public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
        return source.countPresent(keys, token);
    }

    @Override
    public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
        return source.hasKeys(keys, token);
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return source.readHistory(query, token);
    }
}
