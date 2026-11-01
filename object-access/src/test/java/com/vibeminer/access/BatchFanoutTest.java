package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.Options;
import com.vibeminer.network.CdnClient;
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.store.MemoryStore;
import com.vibeminer.store.Verification;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class BatchFanoutTest {
    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));

    private static Options options(int streams, int queued) {
        return new Options(
                streams,
                streams,
                1,
                queued,
                64L << 20,
                64L << 20,
                64L << 20,
                64L << 20,
                Duration.ofSeconds(30),
                Clock.systemUTC());
    }

    private static final class ControlledCdn implements CdnClient {
        final Map<CdnKey, byte[]> bytes = new ConcurrentHashMap<>();
        final Map<CdnKey, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
        final Map<CdnKey, com.vibeminer.network.Cancellation> tokens = new ConcurrentHashMap<>();
        final AtomicInteger active = new AtomicInteger(),
                maximum = new AtomicInteger(),
                calls = new AtomicInteger();
        final CountDownLatch firstTwo = new CountDownLatch(2);
        final boolean held;

        ControlledCdn(boolean held) {
            this.held = held;
        }

        ObjectRequest add(int n, int order) {
            byte[] value = ObjectSourceContracts.bytes("object-" + n);
            var key = ObjectSourceContracts.key(value);
            var cdnKey = new CdnKey(key.bytes());
            bytes.put(cdnKey, value);
            gates.put(cdnKey, new CompletableFuture<>());
            return new ObjectRequest(key, (long) value.length, List.of(), order);
        }

        void release(ObjectRequest request) {
            gates.get(new CdnKey(request.key().bytes())).complete(null);
        }

        void releaseAll() {
            gates.values().forEach(g -> g.complete(null));
        }

        @Override
        public CompletableFuture<NetworkTypes.Reply> fetch(
                CdnRoute route,
                NetworkTypes.Request request,
                com.vibeminer.network.Cancellation token) {
            calls.incrementAndGet();
            tokens.put(request.key(), token);
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            firstTwo.countDown();
            CompletableFuture<Void> gate =
                    held ? gates.get(request.key()) : CompletableFuture.completedFuture(null);
            return gate.thenApply(
                    ignored -> {
                        byte[] value = bytes.get(request.key());
                        active.decrementAndGet();
                        String host = route.hosts().getFirst();
                        return new NetworkTypes.Reply(
                                host,
                                route.urlFor(host, request.kind(), request.key()),
                                "h2",
                                value.length,
                                null,
                                value,
                                Instant.EPOCH,
                                Duration.ZERO,
                                Duration.ZERO,
                                List.of());
                    });
        }

        @Override
        public CompletableFuture<NetworkTypes.Reply> fetchTo(
                CdnRoute r,
                NetworkTypes.Request q,
                NetworkTypes.Sink sink,
                com.vibeminer.network.Cancellation t) {
            throw new AssertionError();
        }
    }

    @Test
    void oneBatchFansOutConcurrentLooseTransfersWithinStreamBounds() throws Exception {
        ControlledCdn cdn = new ControlledCdn(true);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(2, 8));
        List<ObjectRequest> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            requests.add(cdn.add(i, 0));
        }
        try {
            var result =
                    source.getKeys(
                            requests, CONTEXT, FetchPriority.BACKGROUND, null, Cancellation.NONE);
            assertTrue(cdn.firstTwo.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertEquals(2, cdn.calls.get());
            cdn.releaseAll();
            assertTrue(
                    result.get(5, TimeUnit.SECONDS).stream()
                            .allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(5, cdn.calls.get());
            assertEquals(2, cdn.maximum.get());
        } finally {
            cdn.releaseAll();
            source.closeAsync().join();
        }
    }

    @Test
    void aSingleBackgroundStreamAndTinyQueueCannotDeadlockOrLoseAPlan() throws Exception {
        ControlledCdn cdn = new ControlledCdn(false);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(1, 1));
        List<ObjectRequest> requests = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            requests.add(cdn.add(i, 0));
        }
        try {
            var result =
                    source.getKeys(
                                    requests,
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .get(10, TimeUnit.SECONDS);
            assertEquals(40, result.size());
            assertTrue(result.stream().allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(40, cdn.calls.get());
            assertEquals(1, cdn.maximum.get());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void closeDrainsNotYetSubmittedChildrenAndFutureCancellationDoesNotDropThem() throws Exception {
        ControlledCdn cdn = new ControlledCdn(true);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(1, 1));
        List<ObjectRequest> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(cdn.add(i, 0));
        }
        try {
            var result =
                    source.getKeys(
                            requests, CONTEXT, FetchPriority.BACKGROUND, null, Cancellation.NONE);
            // A marker continuation cannot block the source worker; wait for the first request
            // explicitly.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cdn.calls.get() == 0 && System.nanoTime() < until) {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
            assertEquals(1, cdn.calls.get());
            result.cancel(false);
            var closing = source.closeAsync();
            assertFalse(closing.isDone());
            cdn.releaseAll();
            closing.get(10, TimeUnit.SECONDS);
            assertEquals(20, cdn.calls.get());
            assertEquals(20L, requests.stream().filter(r -> store.has(r.key()).join()).count());
        } finally {
            cdn.releaseAll();
            source.closeAsync().join();
        }
    }

    @Test
    void lowerOrderPhaseCompletesBeforeHigherOrderTransfersBegin() throws Exception {
        ControlledCdn cdn = new ControlledCdn(true);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(2, 8));
        var first = cdn.add(1, 0);
        var second = cdn.add(2, 0);
        var later = cdn.add(3, 1);
        try {
            var result =
                    source.getKeys(
                            List.of(later, first, second),
                            CONTEXT,
                            FetchPriority.BACKGROUND,
                            null,
                            Cancellation.NONE);
            assertTrue(cdn.firstTwo.await(5, TimeUnit.SECONDS));
            assertEquals(2, cdn.calls.get());
            cdn.release(first);
            assertFalse(result.isDone());
            assertEquals(2, cdn.calls.get());
            cdn.release(second);
            cdn.release(later);
            assertTrue(
                    result.get(5, TimeUnit.SECONDS).stream()
                            .allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(3, cdn.calls.get());
        } finally {
            cdn.releaseAll();
            source.closeAsync().join();
        }
    }

    @Test
    void cancelingOneBatchKeepsAnIndependentlyJoinedMemberAlive() throws Exception {
        ControlledCdn cdn = new ControlledCdn(true);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(2, 8));
        var a = cdn.add(1, 0);
        var b = cdn.add(2, 0);
        Cancellation batchToken = new Cancellation();
        try {
            var batch =
                    source.getKeys(
                            List.of(a, b), CONTEXT, FetchPriority.BACKGROUND, null, batchToken);
            assertTrue(cdn.firstTwo.await(5, TimeUnit.SECONDS));
            var independent =
                    source.getKey(b, CONTEXT, FetchPriority.INTERACTIVE, Cancellation.NONE);
            batchToken.cancel();
            assertTrue(batch.handle((value, error) -> error != null).join());
            assertTrue(cdn.tokens.get(new CdnKey(a.key().bytes())).isCanceled());
            assertFalse(cdn.tokens.get(new CdnKey(b.key().bytes())).isCanceled());
            cdn.gates
                    .get(new CdnKey(a.key().bytes()))
                    .completeExceptionally(new java.util.concurrent.CancellationException());
            cdn.release(b);
            assertTrue(independent.get(5, TimeUnit.SECONDS).isPresent());
            assertFalse(store.has(a.key()).join());
            assertTrue(store.has(b.key()).join());
        } finally {
            cdn.releaseAll();
            source.closeAsync().join();
        }
    }

    @Test
    void progressAssertionErrorCannotSuppressLaterSnapshotsOrFailDurableWork() throws Exception {
        ControlledCdn cdn = new ControlledCdn(true);
        MemoryStore store = new MemoryStore();
        var source =
                new CachingObjectSource(
                        store, store, store, null, cdn, List.of(), Verification.CDN, options(2, 8));
        var a = cdn.add(1, 0);
        var b = cdn.add(2, 0);
        var c = cdn.add(3, 0);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1), later = new CountDownLatch(1);
        try {
            var result =
                    source.getKeys(
                            List.of(a, b, c),
                            CONTEXT,
                            FetchPriority.BACKGROUND,
                            event -> {
                                if (callbacks.incrementAndGet() == 1) {
                                    first.countDown();
                                    throw new AssertionError("observer");
                                }
                                later.countDown();
                            },
                            Cancellation.NONE);
            assertTrue(cdn.firstTwo.await(5, TimeUnit.SECONDS));
            cdn.release(a);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            cdn.release(b);
            assertTrue(later.await(5, TimeUnit.SECONDS));
            cdn.release(c);
            assertTrue(
                    result.get(5, TimeUnit.SECONDS).stream()
                            .allMatch(SourceTypes.ObjectResult::isPresent));
        } finally {
            cdn.releaseAll();
            source.closeAsync().join();
        }
    }
}
