package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Options;
import com.vibeminer.network.CdnClient;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkException;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.store.MemoryStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import com.vibeminer.store.Verification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class AccessConcurrencyTest {
    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));

    private static Options options() {
        return new Options(
                1,
                2,
                1,
                8,
                64L << 20,
                64L << 20,
                64L << 20,
                64L << 20,
                Duration.ofSeconds(30),
                Clock.systemUTC());
    }

    private static NetworkTypes.Reply reply(
            CdnRoute route, NetworkTypes.Request request, byte[] bytes) {
        String host = route.hosts().getFirst();
        return new NetworkTypes.Reply(
                host,
                route.urlFor(host, request.kind(), request.key()),
                "h2",
                bytes.length,
                null,
                bytes,
                Instant.EPOCH,
                Duration.ZERO,
                Duration.ZERO,
                List.of());
    }

    @Test
    void concurrentSameKeySharesTransferAndCancelingOneWaiterDoesNotCancelOthers()
            throws Exception {
        byte[] bytes = ObjectSourceContracts.bytes("coalesced");
        ObjectKey key = ObjectSourceContracts.key(bytes);
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<com.vibeminer.network.Cancellation> transferToken = new AtomicReference<>();
        CompletableFuture<NetworkTypes.Reply> gate = new CompletableFuture<>();
        CdnClient cdn =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        transferToken.set(token);
                        calls.incrementAndGet();
                        entered.countDown();
                        return gate;
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            NetworkTypes.Sink s,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError();
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options());
        try {
            Cancellation first = new Cancellation();
            var a = source.getKey(new ObjectRequest(key), CONTEXT, FetchPriority.BACKGROUND, first);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var b =
                    source.getKey(
                            new ObjectRequest(key),
                            CONTEXT,
                            FetchPriority.INTERACTIVE,
                            Cancellation.NONE);
            first.cancel();
            assertTrue(
                    a.handle(
                                    (v, e) ->
                                            e instanceof java.util.concurrent.CancellationException
                                                    || e.getCause()
                                                            instanceof
                                                            java.util.concurrent
                                                                    .CancellationException)
                            .join());
            assertFalse(transferToken.get().isCanceled());
            gate.complete(
                    reply(
                            CONTEXT.route(),
                            NetworkTypes.Request.whole(
                                    NetworkTypes.ObjectKind.CONFIG,
                                    new com.vibeminer.network.CdnKey(key.bytes())),
                            bytes));
            assertArrayEquals(bytes, b.join().bytes());
            assertEquals(1, calls.get());
        } finally {
            gate.completeExceptionally(new java.util.concurrent.CancellationException());
            source.closeAsync().join();
        }
    }

    @Test
    void lastCanceledWaiterAbortsTheTransferTokenAndLeavesNoBytes() throws Exception {
        byte[] bytes = ObjectSourceContracts.bytes("cancel-last");
        ObjectKey key = ObjectSourceContracts.key(bytes);
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<com.vibeminer.network.Cancellation> actual = new AtomicReference<>();
        CompletableFuture<NetworkTypes.Reply> gate = new CompletableFuture<>();
        CdnClient cdn =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            com.vibeminer.network.Cancellation token) {
                        actual.set(token);
                        entered.countDown();
                        return gate;
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            NetworkTypes.Sink s,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError();
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options());
        try {
            Cancellation token = new Cancellation();
            var answer =
                    source.getKey(
                            new ObjectRequest(key), CONTEXT, FetchPriority.INTERACTIVE, token);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            assertTrue(answer.handle((v, e) -> e != null).join());
            assertTrue(actual.get().isCanceled());
            gate.completeExceptionally(new java.util.concurrent.CancellationException());
            assertFalse(store.has(key).join());
        } finally {
            gate.completeExceptionally(new java.util.concurrent.CancellationException());
            source.closeAsync().join();
        }
    }

    @Test
    void aNewWaiterAfterLastCancellationWaitsForOldCleanupThenStartsAFreshTransfer()
            throws Exception {
        byte[] bytes = ObjectSourceContracts.bytes("retry-after-cancel");
        ObjectKey key = ObjectSourceContracts.key(bytes);
        MemoryStore store = new MemoryStore();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<NetworkTypes.Reply> old = new CompletableFuture<>();
        CdnClient cdn =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        if (calls.incrementAndGet() == 1) {
                            entered.countDown();
                            return old;
                        }
                        return CompletableFuture.completedFuture(reply(route, request, bytes));
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError();
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options());
        try {
            Cancellation token = new Cancellation();
            var first =
                    source.getKey(new ObjectRequest(key), CONTEXT, FetchPriority.BACKGROUND, token);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            assertTrue(first.handle((v, e) -> e != null).join());
            var retry =
                    source.getKey(
                            new ObjectRequest(key),
                            CONTEXT,
                            FetchPriority.INTERACTIVE,
                            Cancellation.NONE);
            assertFalse(retry.isDone());
            old.completeExceptionally(new java.util.concurrent.CancellationException());
            assertArrayEquals(bytes, retry.get(5, TimeUnit.SECONDS).bytes());
            assertEquals(2, calls.get());
        } finally {
            old.completeExceptionally(new java.util.concurrent.CancellationException());
            source.closeAsync().join();
        }
    }

    @Test
    void readerAdmissionLimitNeverManufacturesPresentForAnAbsentKey() {
        ObjectReader reader =
                new ObjectReader() {
                    @Override
                    public CompletableFuture<Boolean> has(
                            ObjectKey key, com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.completedFuture(false);
                    }

                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> read(
                            ObjectKey key, com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.failedFuture(
                                new StoreException("OBJECT_STORE_LIMIT", "reader queue full"));
                    }

                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> readRange(
                            ObjectKey key,
                            long offset,
                            int length,
                            com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new StoreTypes.ObjectRead(
                                        StoreTypes.ReadState.ABSENT, null, null, null));
                    }
                };
        CachingObjectSource source = CachingObjectSource.localOnly(reader, null);
        try {
            assertEquals(
                    ObjectState.NEEDS_NETWORK,
                    source.getKey(
                                    new ObjectRequest(ObjectSourceContracts.key(new byte[] {1})),
                                    null,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void networkAdmissionFailureIsAPredictablePerObjectState() {
        MemoryStore store = new MemoryStore();
        CdnClient cdn =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            com.vibeminer.network.Cancellation token) {
                        return CompletableFuture.failedFuture(
                                new NetworkException("NETWORK_LIMIT", "full"));
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            NetworkTypes.Sink s,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError();
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(store, store, store, null, cdn, List.of());
        try {
            var result =
                    source.getKey(
                                    new ObjectRequest(ObjectSourceContracts.key(new byte[] {1})),
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(ObjectState.UNAVAILABLE, result.state());
            assertEquals("NETWORK_LIMIT", result.code());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void mixedHostFailuresKeepThePassingFailureCodeRatherThanAnUnrelatedRefusalCode() {
        byte[] bytes = ObjectSourceContracts.bytes("mixed-failure");
        ObjectKey key = ObjectSourceContracts.key(bytes);
        var url =
                CONTEXT.route()
                        .urlFor(
                                CONTEXT.route().hosts().getFirst(),
                                NetworkTypes.ObjectKind.CONFIG,
                                new com.vibeminer.network.CdnKey(key.bytes()));
        var error =
                new CdnException(
                        List.of(
                                new NetworkTypes.Attempt(
                                        url.getHost(),
                                        url,
                                        NetworkTypes.Failure.TIMEOUT,
                                        null,
                                        Instant.EPOCH,
                                        "timeout",
                                        null),
                                new NetworkTypes.Attempt(
                                        url.getHost(),
                                        url,
                                        NetworkTypes.Failure.VERIFICATION_FAILED,
                                        null,
                                        Instant.EPOCH,
                                        "bad bytes",
                                        "OBJECT_KEY_MISMATCH")));
        var result = CachingObjectSource.fromCdn(key, error);
        assertEquals(ObjectState.UNAVAILABLE, result.state());
        assertEquals("CDN_TIMEOUT", result.code());
    }

    @Test
    void localAndCdnOnlyModesDoNotWriteAndReportPresenceHonestly() {
        MemoryStore store = new MemoryStore();
        byte[] bytes = ObjectSourceContracts.bytes("local");
        ObjectKey key = ObjectSourceContracts.key(bytes);
        store.put(List.of(new StoreTypes.PutObject(key, bytes))).join();
        CachingObjectSource local = CachingObjectSource.localOnly(store, store);
        try {
            assertArrayEquals(
                    bytes,
                    local.getKey(
                                    new ObjectRequest(key),
                                    null,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .bytes());
            assertEquals(
                    ObjectState.NEEDS_NETWORK,
                    local.getKey(
                                    new ObjectRequest(ObjectSourceContracts.key(new byte[] {2})),
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .state());
        } finally {
            local.closeAsync().join();
        }
        com.vibeminer.network.MemorySources network = new com.vibeminer.network.MemorySources();
        network.add(
                NetworkTypes.ObjectKind.CONFIG,
                new com.vibeminer.network.CdnKey(key.bytes()),
                bytes);
        CachingObjectSource cdn = CachingObjectSource.cdnOnly(network, List.of());
        try {
            assertArrayEquals(
                    bytes,
                    cdn.getKey(
                                    new ObjectRequest(key),
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join()
                            .bytes());
            assertEquals(List.of(false), cdn.hasKeys(List.of(key), Cancellation.NONE).join());
            assertTrue(
                    cdn.readHistory(new com.vibeminer.store.History.Query(), Cancellation.NONE)
                            .join()
                            .isEmpty());
        } finally {
            cdn.closeAsync().join();
        }
    }
}
