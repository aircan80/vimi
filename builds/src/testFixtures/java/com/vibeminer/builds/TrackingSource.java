package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.access.SourceTypes.ServedFrom;
import com.vibeminer.access.StoreCommitFeed;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/** Layer 2 double exposing deterministic barriers and presence changes for layer 3 consumers. */
public final class TrackingSource implements ObjectSource {
    public final StoreCommitFeed commits = new StoreCommitFeed();
    public final Set<ObjectKey> stored = ConcurrentHashMap.newKeySet();
    public final Map<ObjectKey, ObjectState> outcomes = new ConcurrentHashMap<>();
    public final Map<ObjectKey, String> codes = new ConcurrentHashMap<>();
    public final List<ObjectKey> requested = new CopyOnWriteArrayList<>();
    public final AtomicInteger hasCalls = new AtomicInteger(), fetchCalls = new AtomicInteger();
    public volatile Runnable afterFetch;
    public volatile Function<List<ObjectKey>, CompletableFuture<List<Boolean>>> hasOverride;
    public volatile Function<List<ObjectRequest>, CompletableFuture<List<ObjectResult>>>
            fetchOverride;

    public static ObjectKey key(int id) {
        return new ObjectKey(ObjectKey.ENCODED, TrackingBuild.key(id).bytes());
    }

    @Override
    public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
        token.check();
        hasCalls.incrementAndGet();
        var custom = hasOverride;
        return custom == null
                ? CompletableFuture.completedFuture(keys.stream().map(stored::contains).toList())
                : custom.apply(keys);
    }

    @Override
    public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
        return hasKeys(keys, token)
                .thenApply(h -> h.stream().filter(Boolean::booleanValue).count());
    }

    @Override
    public CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext cdn,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        token.check();
        fetchCalls.incrementAndGet();
        requested.addAll(requests.stream().map(ObjectRequest::key).toList());
        var custom = fetchOverride;
        if (custom != null) {
            return custom.apply(requests);
        }
        List<ObjectResult> results = new ArrayList<>();
        long cdnBytes = 0;
        for (ObjectRequest request : requests) {
            token.check();
            ObjectState state = outcomes.getOrDefault(request.key(), ObjectState.PRESENT);
            if (state == ObjectState.PRESENT) {
                stored.add(request.key());
                commits.publish(List.of(request.key()), List.of());
                cdnBytes += request.declaredLength() == null ? 0 : request.declaredLength();
            }
            results.add(
                    new ObjectResult(
                            request.key(),
                            state,
                            ServedFrom.CDN,
                            Origin.CDN,
                            request.declaredLength(),
                            null,
                            codes.get(request.key()),
                            null,
                            null));
            if (progress != null) {
                progress.accept(new ObjectProgress(results.size(), requests.size(), cdnBytes));
            }
        }
        Runnable after = afterFetch;
        if (after != null) {
            after.run();
        }
        return CompletableFuture.completedFuture(List.copyOf(results));
    }

    @Override
    public CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext cdn, FetchPriority priority, Cancellation token) {
        return getKeys(List.of(request), cdn, priority, null, token).thenApply(List::getFirst);
    }

    @Override
    public CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext cdn,
            FetchPriority priority,
            Cancellation token) {
        return getKey(request, cdn, priority, token);
    }

    @Override
    public CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return CompletableFuture.completedFuture(List.of());
    }
}
