package com.vimi.gui;

import com.vibeminer.access.CachingObjectSource;
import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes;
import com.vibeminer.network.CdnClient;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless source-policy contracts: supplied CDN routes cannot escape into prepared table reads.
 */
public final class LocalMetadataSourceTest {
    private LocalMetadataSourceTest() {}

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var key = ObjectKey.parse(ObjectKey.ENCODED, "a".repeat(32));
        var request = new SourceTypes.ObjectRequest(key, 32L);
        var context =
                new SourceTypes.CdnContext(new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));
        var token = new Cancellation();
        var calls = new AtomicInteger();
        ObjectSource delegate =
                (ObjectSource)
                        Proxy.newProxyInstance(
                                ObjectSource.class.getClassLoader(),
                                new Class<?>[] {ObjectSource.class},
                                (proxy, method, values) -> {
                                    calls.incrementAndGet();
                                    require(
                                            values[values.length - 1] == token,
                                            "Preserve explicit cancellation");
                                    return switch (method.getName()) {
                                        case "getKey" -> {
                                            require(
                                                    values[0] == request && values[1] == null,
                                                    "Strip getKey context only");
                                            yield CompletableFuture.completedFuture(null);
                                        }
                                        case "getKeys" -> {
                                            require(values[1] == null, "Strip batch context");
                                            yield CompletableFuture.completedFuture(List.of());
                                        }
                                        case "getRange" -> {
                                            require(
                                                    values[0] == request
                                                            && values[1].equals(5L)
                                                            && values[2].equals(7)
                                                            && values[3] == null,
                                                    "Preserve bounded range but strip CDN context");
                                            yield CompletableFuture.completedFuture(null);
                                        }
                                        case "countPresent" ->
                                                CompletableFuture.completedFuture(0L);
                                        case "hasKeys", "readHistory" ->
                                                CompletableFuture.completedFuture(List.of());
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected delegation: "
                                                                + method.getName());
                                    };
                                });
        var local = new LocalMetadataSource(delegate);
        local.getKey(request, context, SourceTypes.FetchPriority.INTERACTIVE, token).join();
        local.getKeys(List.of(request), context, SourceTypes.FetchPriority.BACKGROUND, null, token)
                .join();
        local.getRange(request, 5, 7, context, SourceTypes.FetchPriority.INTERACTIVE, token).join();
        local.countPresent(List.of(key), token).join();
        local.hasKeys(List.of(key), token).join();
        local.readHistory(new History.Query(), token).join();
        require(calls.get() == 6, "Read and presence/history calls delegate exactly once");
        require(
                local.observe(
                                new SourceTypes.ObservationRequest(
                                        SourceTypes.ObservationKind.VERSIONS, "wow", "us"),
                                token)
                        .isCompletedExceptionally(),
                "Observation fetch prohibited");
        require(
                local.record(
                                new SourceTypes.Observation(
                                        SourceTypes.ObservationKind.VERSIONS,
                                        "wow",
                                        "us",
                                        URI.create("https://example.invalid"),
                                        Instant.EPOCH,
                                        new byte[] {1}),
                                token)
                        .isCompletedExceptionally(),
                "Observation mutation prohibited");
        require(calls.get() == 6, "Prohibited operations never reach original source");

        var cdnCalls = new AtomicInteger();
        CdnClient cdn =
                new CdnClient() {
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request wanted,
                            com.vibeminer.network.Cancellation cancellation) {
                        cdnCalls.incrementAndGet();
                        throw new AssertionError("Prepared data attempted CDN fallback");
                    }

                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute route,
                            NetworkTypes.Request wanted,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation cancellation) {
                        cdnCalls.incrementAndGet();
                        throw new AssertionError("Prepared data attempted streaming CDN fallback");
                    }
                };
        var real = CachingObjectSource.cdnOnly(cdn, List.of());
        try {
            var guarded = new LocalMetadataSource(real);
            require(
                    guarded.getKey(request, context, SourceTypes.FetchPriority.INTERACTIVE, token)
                                    .join()
                                    .state()
                            == SourceTypes.ObjectState.UNAVAILABLE,
                    "Missing prepared object remains an explicit local miss");
            require(
                    guarded.getKeys(
                                            List.of(request),
                                            context,
                                            SourceTypes.FetchPriority.INTERACTIVE,
                                            null,
                                            token)
                                    .join()
                                    .getFirst()
                                    .state()
                            == SourceTypes.ObjectState.UNAVAILABLE,
                    "Missing batch cannot fetch");
            require(
                    guarded.getRange(
                                            request,
                                            0,
                                            8,
                                            context,
                                            SourceTypes.FetchPriority.INTERACTIVE,
                                            token)
                                    .join()
                                    .state()
                            == SourceTypes.ObjectState.UNAVAILABLE,
                    "Missing range cannot fetch");
            require(cdnCalls.get() == 0, "No CDN calls across all object read paths");
        } finally {
            real.closeAsync().join();
        }
        separateClaimMapsDoNotJoinOnlineTransfers(request, context);
        System.out.println(
                "Passed local-only prepared metadata policy, observation refusal and no-CDN fallback contracts");
    }

    private static void separateClaimMapsDoNotJoinOnlineTransfers(
            SourceTypes.ObjectRequest request, SourceTypes.CdnContext context) {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var transfer = new CompletableFuture<NetworkTypes.Reply>();
        var fetches = new AtomicInteger();
        CdnClient slow =
                new CdnClient() {
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request wanted,
                            com.vibeminer.network.Cancellation cancellation) {
                        fetches.incrementAndGet();
                        entered.countDown();
                        return transfer;
                    }

                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute route,
                            NetworkTypes.Request wanted,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation cancellation) {
                        fetches.incrementAndGet();
                        entered.countDown();
                        return transfer;
                    }
                };
        var online = CachingObjectSource.cdnOnly(slow, List.of());
        var reader =
                (com.vibeminer.store.ObjectReader)
                        Proxy.newProxyInstance(
                                com.vibeminer.store.ObjectReader.class.getClassLoader(),
                                new Class<?>[] {com.vibeminer.store.ObjectReader.class},
                                (proxy, method, values) ->
                                        method.getName().equals("has")
                                                ? CompletableFuture.completedFuture(false)
                                                : CompletableFuture.completedFuture(
                                                        new com.vibeminer.store.StoreTypes
                                                                .ObjectRead(
                                                                com.vibeminer.store.StoreTypes
                                                                        .ReadState.ABSENT,
                                                                null,
                                                                null,
                                                                null)));
        var localSource =
                CachingObjectSource.localOnly(
                        reader,
                        (query, cancellation) -> CompletableFuture.completedFuture(List.of()));
        try {
            var pending =
                    online.getKey(
                            request,
                            context,
                            SourceTypes.FetchPriority.INTERACTIVE,
                            Cancellation.NONE);
            require(
                    entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "Online transfer must be active");
            var result =
                    new LocalMetadataSource(localSource)
                            .getKey(
                                    request,
                                    context,
                                    SourceTypes.FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .get(2, java.util.concurrent.TimeUnit.SECONDS);
            require(
                    result.state() != SourceTypes.ObjectState.PRESENT,
                    "Missing local artifact must remain unavailable");
            require(!pending.isDone(), "Local result must not wait for unrelated online claim");
            require(fetches.get() == 1, "Local request must not start an extra CDN transfer");
        } catch (Exception error) {
            throw new AssertionError("Isolated local claim test", error);
        } finally {
            transfer.completeExceptionally(new java.util.concurrent.CancellationException());
            localSource.closeAsync().join();
            online.closeAsync().join();
        }
    }
}
