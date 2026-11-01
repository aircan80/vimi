package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.access.SourceTypes.ServedFrom;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes;
import com.vibeminer.store.Verification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Independent in-memory layer 2 fake for catalog/build consumers; no store or HTTP implementation.
 */
public final class MemoryObjectSource implements ObjectSource, StoreCommits {
    private final Map<ObjectKey, byte[]> local = new LinkedHashMap<>(),
            remote = new HashMap<>(),
            shared = new HashMap<>();
    private final Map<ObjectKey, StoreTypes.Origin> origins = new HashMap<>();
    private final Map<ObjectKey, ObjectResult> scripted = new HashMap<>();
    private final List<History.Entry> history = new ArrayList<>();
    private final Map<ObservationKind, ObservationSource> observations = new HashMap<>();
    private final StoreCommitFeed feed = new StoreCommitFeed();
    private int transfers;

    public MemoryObjectSource() {
        this(List.of());
    }

    public MemoryObjectSource(List<ObservationSource> sources) {
        for (var source : sources) {
            for (var kind : source.kinds()) {
                if (observations.putIfAbsent(kind, source) != null) {
                    throw new IllegalArgumentException("Duplicate observation source");
                }
            }
        }
    }

    /** Seed locally held data, publishing a committed presence change. */
    public synchronized void put(ObjectKey key, byte[] bytes) {
        local.put(key, bytes.clone());
        origins.put(key, StoreTypes.Origin.UNKNOWN);
        feed.publish(List.of(key), List.of());
    }

    public synchronized void remote(ObjectKey key, byte[] bytes) {
        remote.put(key, bytes.clone());
    }

    public synchronized void shared(ObjectKey key, byte[] bytes) {
        shared.put(key, bytes.clone());
    }

    public synchronized void remove(ObjectKey key) {
        if (local.remove(key) != null) {
            origins.remove(key);
            feed.publish(List.of(), List.of(key));
        }
    }

    public synchronized void script(ObjectResult result) {
        scripted.put(result.key(), result);
    }

    public synchronized int transfers() {
        return transfers;
    }

    @Override
    public StoreCommitSubscription subscribe(int capacity) {
        return feed.subscribe(capacity);
    }

    @Override
    public synchronized CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext context, FetchPriority priority, Cancellation token) {
        return call(() -> get(request, context, token));
    }

    private ObjectResult get(ObjectRequest request, CdnContext context, Cancellation token) {
        token.check();
        ObjectKey key = request.key();
        if (local.containsKey(key)) {
            byte[] bytes = local.get(key);
            return present(key, ServedFrom.STORE, origins.get(key), bytes);
        }
        if (scripted.containsKey(key)) {
            return scripted.get(key);
        }
        byte[] bytes = shared.get(key);
        ServedFrom from = ServedFrom.SHARED_STORE;
        StoreTypes.Origin origin = StoreTypes.Origin.SHARED_STORE;
        if (bytes == null) {
            if (context == null) {
                return failure(key, ObjectState.UNAVAILABLE, "OBJECT_ACCESS_NO_CDN_CONTEXT");
            }
            bytes = remote.get(key);
            from = ServedFrom.CDN;
            origin = StoreTypes.Origin.CDN;
            transfers++;
        }
        if (bytes == null) {
            return new ObjectResult(
                    key,
                    ObjectState.NOT_FOUND,
                    null,
                    null,
                    null,
                    null,
                    "CDN_NOT_FOUND",
                    "Missing synthetic object",
                    Instant.EPOCH);
        }
        var refusal =
                Verification.CDN.check(
                        key,
                        bytes,
                        request.declaredLength() == null ? bytes.length : request.declaredLength());
        if (refusal != null) {
            return failure(key, ObjectState.REFUSED, refusal.code());
        }
        local.put(key, bytes.clone());
        origins.put(key, origin);
        feed.publish(List.of(key), List.of());
        return present(key, from, origin, bytes);
    }

    @Override
    public synchronized CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext context,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return call(
                () -> {
                    Map<ObjectKey, ObjectResult> values = new HashMap<>();
                    for (ObjectRequest request : List.copyOf(requests)) {
                        values.computeIfAbsent(
                                request.key(), ignored -> get(request, context, token));
                    }
                    if (progress != null) {
                        progress.accept(new ObjectProgress(values.size(), values.size(), 0));
                    }
                    return requests.stream().map(r -> values.get(r.key()).withoutBytes()).toList();
                });
    }

    @Override
    public synchronized CompletableFuture<ObjectResult> getRange(
            ObjectRequest request,
            long offset,
            int length,
            CdnContext context,
            FetchPriority priority,
            Cancellation token) {
        return call(
                () -> {
                    if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length) {
                        throw new IllegalArgumentException("Invalid range");
                    }
                    ObjectResult result = get(request, context, token);
                    if (!result.isPresent()) {
                        return result;
                    }
                    byte[] bytes = result.bytes();
                    if (offset > bytes.length - length) {
                        throw new IllegalArgumentException("Range past end");
                    }
                    return new ObjectResult(
                            result.key(),
                            result.state(),
                            result.servedFrom(),
                            result.origin(),
                            result.length(),
                            Arrays.copyOfRange(bytes, (int) offset, (int) offset + length),
                            null,
                            null,
                            null);
                });
    }

    @Override
    public CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        if (request.problem() != null) {
            return CompletableFuture.completedFuture(invalid(request.problem()));
        }
        ObservationSource source = observations.get(request.kind());
        if (source == null) {
            return CompletableFuture.completedFuture(
                    new ObservationResult(
                            ObjectState.REFUSED,
                            null,
                            false,
                            "OBSERVATION_SOURCE_MISSING",
                            "No scripted observation source",
                            null));
        }
        try {
            token.check();
            return source.fetch(request, token)
                    .thenApply(
                            value -> {
                                synchronized (this) {
                                    return request.record()
                                            ? append(value)
                                            : new ObservationResult(
                                                    ObjectState.PRESENT,
                                                    value,
                                                    false,
                                                    null,
                                                    null,
                                                    null);
                                }
                            })
                    .exceptionally(
                            error -> {
                                Throwable cause = error;
                                while (cause instanceof java.util.concurrent.CompletionException
                                        && cause.getCause() != null) {
                                    cause = cause.getCause();
                                }
                                if (cause instanceof com.vibeminer.network.CdnException failure) {
                                    var state =
                                            switch (failure.overall()) {
                                                case NOT_FOUND -> ObjectState.NOT_FOUND;
                                                case SIZE_CONFLICT,
                                                        VERIFICATION_FAILED,
                                                        PROTOCOL,
                                                        TOO_LARGE ->
                                                        ObjectState.REFUSED;
                                                default -> ObjectState.UNAVAILABLE;
                                            };
                                    return new ObservationResult(
                                            state,
                                            null,
                                            false,
                                            failure.code() == null
                                                    ? "CDN_" + failure.overall()
                                                    : failure.code(),
                                            failure.getMessage(),
                                            failure.attempts().getLast().observedAt());
                                }
                                throw new java.util.concurrent.CompletionException(cause);
                            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        return call(
                () -> {
                    String problem = ObservationRequest.recordProblem(observation);
                    if (problem != null) {
                        return invalid(problem);
                    }
                    token.check();
                    return append(observation);
                });
    }

    private ObservationResult append(Observation observation) {
        var objects =
                History.objects(
                        new History.Observation(
                                observation.kind().historyKind(),
                                observation.product(),
                                observation.region(),
                                observation.observedAt(),
                                observation.url().toString(),
                                observation.bytes()));
        List<ObjectKey> stored = new ArrayList<>();
        for (var object : objects) {
            if (local.putIfAbsent(object.key(), object.bytes()) == null) {
                stored.add(object.key());
                origins.put(object.key(), StoreTypes.Origin.CDN);
            }
        }
        History.Entry entry =
                new History.Entry(
                        observation.kind().historyKind(),
                        observation.product(),
                        observation.region(),
                        observation.observedAt(),
                        observation.url().toString(),
                        objects.get(0).key(),
                        objects.get(1).key());
        if (history.stream().noneMatch(e -> e.record().equals(entry.record()))) {
            history.add(entry);
        }
        feed.publish(stored, List.of());
        return new ObservationResult(ObjectState.PRESENT, observation, true, null, null, null);
    }

    private static ObservationResult invalid(String problem) {
        return new ObservationResult(
                ObjectState.REFUSED, null, false, "OBSERVATION_REQUEST_INVALID", problem, null);
    }

    @Override
    public synchronized CompletableFuture<List<Boolean>> hasKeys(
            List<ObjectKey> keys, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    return keys.stream().map(local::containsKey).toList();
                });
    }

    @Override
    public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
        return hasKeys(keys, token)
                .thenApply(values -> values.stream().filter(Boolean::booleanValue).count());
    }

    @Override
    public synchronized CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return call(
                () -> {
                    token.check();
                    return history.stream()
                            .filter(
                                    e ->
                                            query.product() == null
                                                    || query.product().equals(e.product()))
                            .filter(
                                    e ->
                                            query.region() == null
                                                    || query.region().equals(e.region()))
                            .filter(e -> query.kind() == null || query.kind() == e.kind())
                            .sorted(
                                    Comparator.comparing(History.Entry::observedAt)
                                            .thenComparing(History.Entry::record))
                            .filter(e -> query.after() == null || compare(e, query.after()) > 0)
                            .limit(query.limit())
                            .toList();
                });
    }

    private static int compare(History.Entry a, History.Entry b) {
        int order = a.observedAt().compareTo(b.observedAt());
        return order != 0 ? order : a.record().compareTo(b.record());
    }

    private static ObjectResult present(
            ObjectKey key, ServedFrom from, StoreTypes.Origin origin, byte[] bytes) {
        return new ObjectResult(
                key,
                ObjectState.PRESENT,
                from,
                origin,
                (long) bytes.length,
                bytes,
                null,
                null,
                null);
    }

    private static ObjectResult failure(ObjectKey key, ObjectState state, String code) {
        return new ObjectResult(key, state, null, null, null, null, code, code, null);
    }

    private static <T> CompletableFuture<T> call(Supplier<T> action) {
        try {
            return CompletableFuture.completedFuture(action.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
