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
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes.Origin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Deterministic layer 2 history/observation double; no layer 1 I/O is used. */
public final class CatalogSource implements ObjectSource {
    private final Map<ObjectKey, byte[]> documents = new HashMap<>();
    private final List<History.Entry> history = new ArrayList<>();
    private final Map<String, Observation> observations = new HashMap<>();
    private final List<ObservationRequest> asked = new ArrayList<>();
    public int gets, historyReads;
    public java.util.function.Function<ObjectRequest, CompletableFuture<ObjectResult>> getOverride;

    public synchronized History.Entry append(
            int kind, String product, String host, Instant time, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ObjectKey document = new ObjectKey(ObjectKey.HISTORY_DOCUMENT, md5(bytes));
        String url =
                "https://"
                        + host
                        + ".version.battle.net/v2/"
                        + (kind == 4
                                ? "summary"
                                : "products/" + product + "/" + (kind == 1 ? "versions" : "cdns"));
        ObjectKey record =
                new ObjectKey(
                        ObjectKey.HISTORY_RECORD,
                        md5(
                                (kind
                                                + "|"
                                                + product
                                                + "|"
                                                + host
                                                + "|"
                                                + time
                                                + "|"
                                                + document.hex())
                                        .getBytes(StandardCharsets.UTF_8)));
        History.Entry entry = new History.Entry(kind, product, host, time, url, document, record);
        documents.put(document, bytes);
        if (history.stream().noneMatch(e -> e.record().equals(record))) {
            history.add(entry);
        }
        return entry;
    }

    public synchronized void lose(ObjectKey document) {
        documents.remove(document);
    }

    public synchronized void restore(ObjectKey document, byte[] bytes) {
        documents.put(document, bytes.clone());
    }

    public synchronized void observation(Observation observation) {
        observations.put(
                observation.kind() + "|" + observation.product() + "|" + observation.region(),
                observation);
    }

    public synchronized List<ObservationRequest> asked() {
        return List.copyOf(asked);
    }

    @Override
    public synchronized CompletableFuture<ObjectResult> getKey(
            ObjectRequest request, CdnContext cdn, FetchPriority priority, Cancellation token) {
        token.check();
        gets++;
        if (getOverride != null) {
            return getOverride.apply(request);
        }
        byte[] bytes = documents.get(request.key());
        return CompletableFuture.completedFuture(
                new ObjectResult(
                        request.key(),
                        bytes == null ? ObjectState.UNAVAILABLE : ObjectState.PRESENT,
                        ServedFrom.STORE,
                        Origin.UNKNOWN,
                        bytes == null ? null : (long) bytes.length,
                        bytes,
                        null,
                        null,
                        null));
    }

    @Override
    public CompletableFuture<List<ObjectResult>> getKeys(
            List<ObjectRequest> requests,
            CdnContext cdn,
            FetchPriority priority,
            Consumer<ObjectProgress> progress,
            Cancellation token) {
        return CompletableFuture.completedFuture(
                requests.stream().map(r -> getKey(r, cdn, priority, token).join()).toList());
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
    public synchronized CompletableFuture<ObservationResult> observe(
            ObservationRequest request, Cancellation token) {
        token.check();
        asked.add(request);
        Observation observation =
                observations.get(request.kind() + "|" + request.product() + "|" + request.region());
        if (observation == null) {
            return CompletableFuture.completedFuture(
                    new ObservationResult(
                            ObjectState.UNAVAILABLE,
                            null,
                            false,
                            "OFFLINE",
                            "No scripted observation",
                            null));
        }
        append(
                request.kind().historyKind(),
                request.product(),
                request.region(),
                observation.observedAt(),
                new String(observation.bytes(), StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(
                new ObservationResult(
                        ObjectState.PRESENT,
                        observation,
                        true,
                        null,
                        null,
                        observation.observedAt()));
    }

    @Override
    public CompletableFuture<ObservationResult> record(
            Observation observation, Cancellation token) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized CompletableFuture<Long> countPresent(
            List<ObjectKey> keys, Cancellation token) {
        return CompletableFuture.completedFuture(
                keys.stream().filter(documents::containsKey).count());
    }

    @Override
    public synchronized CompletableFuture<List<Boolean>> hasKeys(
            List<ObjectKey> keys, Cancellation token) {
        token.check();
        return CompletableFuture.completedFuture(
                keys.stream().map(documents::containsKey).toList());
    }

    @Override
    public synchronized CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        token.check();
        historyReads++;
        Comparator<History.Entry> order =
                Comparator.comparing(History.Entry::observedAt)
                        .thenComparing(History.Entry::record);
        return CompletableFuture.completedFuture(
                history.stream()
                        .filter(e -> query.kind() == null || query.kind() == e.kind())
                        .filter(e -> query.product() == null || query.product().equals(e.product()))
                        .filter(e -> query.region() == null || query.region().equals(e.region()))
                        .filter(e -> query.after() == null || order.compare(e, query.after()) > 0)
                        .sorted(order)
                        .limit(query.limit())
                        .toList());
    }

    private static byte[] md5(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
