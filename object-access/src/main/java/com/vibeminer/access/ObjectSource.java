package com.vibeminer.access;

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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The only layer that chooses stored/shared/network objects; implementations are swappable. */
public interface ObjectSource {
    CompletableFuture<ObjectResult> getKey(
            ObjectRequest request,
            CdnContext cdn,
            FetchPriority priority,
            Cancellation cancellation);

    CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext cdn,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation cancellation);

    CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext cdn,
            FetchPriority priority,
            Cancellation cancellation);

    CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation cancellation);

    CompletableFuture<ObservationResult> record(Observation observation, Cancellation cancellation);

    CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation cancellation);

    CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation cancellation);

    CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation cancellation);
}
