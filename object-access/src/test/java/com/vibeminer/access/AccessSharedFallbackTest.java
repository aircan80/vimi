package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import com.vibeminer.network.MemorySources;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.store.EncodedFixture;
import com.vibeminer.store.MemoryStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.ObjectStaging;
import com.vibeminer.store.ObjectWriter;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import com.vibeminer.store.Verification;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Independent reference cases for shared-store fallback and damaged local ranges. */
@Timeout(30)
final class AccessSharedFallbackTest {
    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of("level3.blizzard.com"), "tpr/wow"));

    private static Options options(long memory, long range) {
        return new Options(
                1,
                2,
                1,
                16,
                64L << 20,
                64L << 20,
                memory,
                range,
                Duration.ofSeconds(30),
                Clock.systemUTC());
    }

    private static EncodedFixture fixture(int payload) {
        byte[] bytes = new byte[payload];
        bytes[0] = 'N';
        return EncodedFixture.chunks(bytes);
    }

    private static void put(MemoryStore store, EncodedFixture fixture) {
        var result =
                store.put(
                                List.of(
                                        new StoreTypes.PutObject(
                                                fixture.key(),
                                                fixture.bytes().length,
                                                fixture.bytes(),
                                                StoreTypes.Origin.CDN)))
                        .join();
        assertEquals(StoreTypes.PutOutcome.STORED, result.getFirst().outcome());
    }

    private static MemorySources network(EncodedFixture fixture) {
        MemorySources result = new MemorySources();
        result.add(
                NetworkTypes.ObjectKind.DATA, new CdnKey(fixture.key().bytes()), fixture.bytes());
        return result;
    }

    @Test
    void sharedWholeReadLimitFallsBackToCdnWithoutFailingOtherBatchKeys() {
        var a = fixture(64);
        var b = fixture(65);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        put(shared, a);
        MemorySources cdn = network(a);
        cdn.add(NetworkTypes.ObjectKind.DATA, new CdnKey(b.key().bytes()), b.bytes());
        ObjectReader limited =
                new Reader(shared) {
                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> read(
                            ObjectKey key, com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.failedFuture(
                                new StoreException("OBJECT_STORE_LIMIT", "scripted"));
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(own, own, own, limited, cdn, List.of());
        try {
            var results =
                    source.getKeys(
                                    List.of(new ObjectRequest(a.key()), new ObjectRequest(b.key())),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertTrue(results.stream().allMatch(SourceTypes.ObjectResult::isPresent));
            assertArrayEquals(a.bytes(), own.read(a.key()).join().bytes());
            assertArrayEquals(b.bytes(), own.read(b.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    private static StoreException queueFull() {
        return new StoreException("OBJECT_STORE_QUEUE_FULL", "scripted full queue");
    }

    /** A full shared-store queue is an error, never a miss that downloads from the CDN. */
    @Test
    void sharedQueueFullIsUnavailableNotACdnDownload() {
        var fixture = fixture(64);
        for (boolean failHas : new boolean[] {true, false}) {
            MemoryStore own = new MemoryStore(), shared = new MemoryStore();
            put(shared, fixture);
            ObjectReader full =
                    new Reader(shared) {
                        @Override
                        public CompletableFuture<Boolean> has(
                                ObjectKey key, com.vibeminer.store.Cancellation token) {
                            return failHas
                                    ? CompletableFuture.failedFuture(queueFull())
                                    : super.has(key, token);
                        }

                        @Override
                        public CompletableFuture<StoreTypes.ObjectRead> read(
                                ObjectKey key, com.vibeminer.store.Cancellation token) {
                            return CompletableFuture.failedFuture(queueFull());
                        }
                    };
            MemorySources cdn = network(fixture);
            CachingObjectSource source =
                    new CachingObjectSource(own, own, own, full, cdn, List.of());
            try {
                var result =
                        source.getKey(
                                        new ObjectRequest(fixture.key()),
                                        CONTEXT,
                                        FetchPriority.INTERACTIVE,
                                        Cancellation.NONE)
                                .join();
                assertEquals(SourceTypes.ObjectState.UNAVAILABLE, result.state(), "has=" + failHas);
                assertEquals("OBJECT_STORE_QUEUE_FULL", result.code());
                assertFalse(own.has(fixture.key()).join(), "the CDN copy was stored");
                assertTrue(cdn.requested().isEmpty(), cdn.requested().toString());
            } finally {
                source.closeAsync().join();
            }
        }
    }

    /** A large object staged from the shared store: a full queue mid-copy is not a miss. */
    @Test
    void sharedQueueFullWhileStagingIsUnavailableNotACdnDownload() {
        var fixture = fixture((4 << 20) + 100);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        put(shared, fixture);
        AtomicInteger reads = new AtomicInteger();
        ObjectReader full =
                new Reader(shared) {
                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> readRange(
                            ObjectKey key,
                            long offset,
                            int length,
                            com.vibeminer.store.Cancellation token) {
                        if (reads.incrementAndGet() > 1) {
                            return CompletableFuture.failedFuture(queueFull());
                        }
                        return super.readRange(key, offset, length, token);
                    }
                };
        Writer writer = new Writer(own, false);
        MemorySources cdn = network(fixture);
        CachingObjectSource source =
                new CachingObjectSource(
                        own,
                        writer,
                        own,
                        full,
                        cdn,
                        List.of(),
                        Verification.CDN,
                        options(128, 64L << 20));
        try {
            var result =
                    source.getKey(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(SourceTypes.ObjectState.UNAVAILABLE, result.state());
            assertEquals("OBJECT_STORE_QUEUE_FULL", result.code());
            assertTrue(writer.abandoned.get(), "the partial shared copy was kept");
            assertTrue(cdn.requested().isEmpty(), cdn.requested().toString());
        } finally {
            source.closeAsync().join();
        }
    }

    /** A range read whose shared-store presence check hits a full queue is not a CDN range. */
    @Test
    void rangeWithSharedHasQueueFullIsUnavailableNotACdnRange() {
        var fixture = fixture(4096);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        put(shared, fixture);
        ObjectReader full =
                new Reader(shared) {
                    @Override
                    public CompletableFuture<Boolean> has(
                            ObjectKey key, com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.failedFuture(queueFull());
                    }
                };
        MemorySources cdn = network(fixture);
        CachingObjectSource source =
                new CachingObjectSource(
                        own,
                        own,
                        own,
                        full,
                        cdn,
                        List.of(),
                        Verification.CDN,
                        options(64L << 20, 1));
        try {
            var range =
                    source.getRange(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    0,
                                    16,
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(SourceTypes.ObjectState.UNAVAILABLE, range.state());
            assertEquals("OBJECT_STORE_QUEUE_FULL", range.code());
            assertTrue(cdn.requested().isEmpty(), cdn.requested().toString());
        } finally {
            source.closeAsync().join();
        }
    }

    /** A full local-store queue fails the read as unavailable instead of downloading. */
    @Test
    void localQueueFullIsUnavailableNotACdnDownload() {
        var fixture = fixture(64);
        MemoryStore own = new MemoryStore();
        ObjectReader full =
                new Reader(own) {
                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> read(
                            ObjectKey key, com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.failedFuture(queueFull());
                    }

                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> readRange(
                            ObjectKey key,
                            long offset,
                            int length,
                            com.vibeminer.store.Cancellation token) {
                        return CompletableFuture.failedFuture(queueFull());
                    }
                };
        MemorySources cdn = network(fixture);
        CachingObjectSource source = new CachingObjectSource(full, own, own, null, cdn, List.of());
        try {
            var whole =
                    source.getKey(
                                    new ObjectRequest(fixture.key()),
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(SourceTypes.ObjectState.UNAVAILABLE, whole.state());
            assertEquals("OBJECT_STORE_QUEUE_FULL", whole.code());
            var range =
                    source.getRange(
                                    new ObjectRequest(fixture.key()),
                                    0,
                                    4,
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(SourceTypes.ObjectState.UNAVAILABLE, range.state());
            assertEquals("OBJECT_STORE_QUEUE_FULL", range.code());
            assertFalse(own.has(fixture.key()).join(), "the CDN copy was stored");
            assertTrue(cdn.requested().isEmpty(), cdn.requested().toString());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void failedLargeSharedRangeAbandonsPrefixBeforeCdnFallback() {
        var fixture = fixture((4 << 20) + 100);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        put(shared, fixture);
        AtomicInteger reads = new AtomicInteger();
        ObjectReader broken =
                new Reader(shared) {
                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> readRange(
                            ObjectKey key,
                            long offset,
                            int length,
                            com.vibeminer.store.Cancellation token) {
                        if (reads.incrementAndGet() > 1) {
                            return CompletableFuture.failedFuture(
                                    new StoreException("OBJECT_STORE_IO", "shared failed"));
                        }
                        return super.readRange(key, offset, length, token);
                    }
                };
        Writer writer = new Writer(own, false);
        MemorySources cdn = network(fixture);
        CachingObjectSource source =
                new CachingObjectSource(
                        own,
                        writer,
                        own,
                        broken,
                        cdn,
                        List.of(),
                        Verification.CDN,
                        options(128, 64L << 20));
        try {
            var result =
                    source.getKey(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    Cancellation.NONE)
                            .join();
            assertTrue(result.isPresent());
            assertTrue(writer.abandoned.get(), "unreadable shared prefix must be removed");
            assertTrue(reads.get() > 1);
            assertFalse(cdn.requested().isEmpty());
            assertArrayEquals(fixture.bytes(), own.read(fixture.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void refusedLargeSharedCommitFallsBackToCdn() {
        var fixture = fixture(256);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        put(shared, fixture);
        Writer writer = new Writer(own, true);
        MemorySources cdn = network(fixture);
        CachingObjectSource source =
                new CachingObjectSource(
                        own,
                        writer,
                        own,
                        shared,
                        cdn,
                        List.of(),
                        Verification.CDN,
                        options(128, 64L << 20));
        try {
            var result =
                    source.getKey(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    Cancellation.NONE)
                            .join();
            assertTrue(result.isPresent());
            assertTrue(writer.refused.get());
            assertFalse(cdn.requested().isEmpty());
            assertArrayEquals(fixture.bytes(), own.read(fixture.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void damagedLargeLocalRangeIsDroppedAndRepaired() {
        var fixture = fixture(256);
        MemoryStore own = new MemoryStore();
        put(own, fixture);
        Writer writer = new Writer(own, false);
        ObjectReader damaged =
                new Reader(own) {
                    @Override
                    public CompletableFuture<StoreTypes.ObjectRead> readRange(
                            ObjectKey key,
                            long offset,
                            int length,
                            com.vibeminer.store.Cancellation token) {
                        if (!writer.deleted.get()) {
                            return CompletableFuture.completedFuture(
                                    new StoreTypes.ObjectRead(
                                            StoreTypes.ReadState.CORRUPT,
                                            null,
                                            null,
                                            "scripted damage"));
                        }
                        return super.readRange(key, offset, length, token);
                    }
                };
        MemorySources cdn = network(fixture);
        CachingObjectSource source =
                new CachingObjectSource(
                        damaged,
                        writer,
                        own,
                        null,
                        cdn,
                        List.of(),
                        Verification.CDN,
                        options(128, 64L << 20));
        try {
            var result =
                    source.getRange(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    42,
                                    8,
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertTrue(result.isPresent());
            assertTrue(writer.deleted.get());
            assertFalse(cdn.requested().isEmpty());
            assertArrayEquals(Arrays.copyOfRange(fixture.bytes(), 42, 50), result.bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void absentSharedKeyStillUsesProvedRangeRatherThanWholeFetch() {
        var fixture = fixture(256);
        MemoryStore own = new MemoryStore(), shared = new MemoryStore();
        MemorySources cdn = network(fixture);
        AtomicInteger ranges = new AtomicInteger();
        CdnClient onlyRanges =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        assertTrue(request.offset() != null, "D340 must fetch proved ranges");
                        ranges.incrementAndGet();
                        return cdn.fetch(route, request, token);
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation token) {
                        throw new AssertionError("No staging/full fetch for an absent shared key");
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(
                        own,
                        own,
                        own,
                        shared,
                        onlyRanges,
                        List.of(),
                        Verification.CDN,
                        options(1024, 64));
        try {
            var result =
                    source.getRange(
                                    new ObjectRequest(fixture.key(), (long) fixture.bytes().length),
                                    42,
                                    8,
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertTrue(result.isPresent());
            assertTrue(ranges.get() > 0);
            assertFalse(own.has(fixture.key()).join());
            assertArrayEquals(Arrays.copyOfRange(fixture.bytes(), 42, 50), result.bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    private static class Reader implements ObjectReader {
        private final MemoryStore store;

        Reader(MemoryStore store) {
            this.store = store;
        }

        @Override
        public CompletableFuture<Boolean> has(
                ObjectKey key, com.vibeminer.store.Cancellation token) {
            return store.has(key, token);
        }

        @Override
        public CompletableFuture<StoreTypes.ObjectRead> read(
                ObjectKey key, com.vibeminer.store.Cancellation token) {
            return store.read(key, token);
        }

        @Override
        public CompletableFuture<StoreTypes.ObjectRead> readRange(
                ObjectKey key, long offset, int length, com.vibeminer.store.Cancellation token) {
            return store.readRange(key, offset, length, token);
        }
    }

    private static final class Writer implements ObjectWriter {
        final MemoryStore store;
        final boolean refuseShared;
        final AtomicBoolean abandoned = new AtomicBoolean(),
                refused = new AtomicBoolean(),
                deleted = new AtomicBoolean();

        Writer(MemoryStore store, boolean refuseShared) {
            this.store = store;
            this.refuseShared = refuseShared;
        }

        @Override
        public CompletableFuture<List<StoreTypes.PutResult>> put(
                List<StoreTypes.PutObject> objects,
                boolean commit,
                com.vibeminer.store.Cancellation token) {
            return store.put(objects, commit, token);
        }

        @Override
        public CompletableFuture<List<StoreTypes.DeleteResult>> delete(
                List<ObjectKey> keys, com.vibeminer.store.Cancellation token) {
            deleted.set(true);
            return store.delete(keys, token);
        }

        @Override
        public CompletableFuture<ObjectStaging> stage(
                ObjectKey key,
                long length,
                StoreTypes.Origin origin,
                com.vibeminer.store.Cancellation token) {
            return store.stage(key, length, origin, token)
                    .thenApply(
                            stage ->
                                    new ObjectStaging() {
                                        @Override
                                        public ObjectKey key() {
                                            return stage.key();
                                        }

                                        @Override
                                        public long declaredLength() {
                                            return stage.declaredLength();
                                        }

                                        @Override
                                        public long length() {
                                            return stage.length();
                                        }

                                        @Override
                                        public CompletableFuture<Void> append(
                                                byte[] bytes,
                                                com.vibeminer.store.Cancellation cancel) {
                                            return stage.append(bytes, cancel);
                                        }

                                        @Override
                                        public CompletableFuture<StoreTypes.PutResult> commit(
                                                com.vibeminer.store.Cancellation cancel) {
                                            if (refuseShared
                                                    && origin == StoreTypes.Origin.SHARED_STORE) {
                                                refused.set(true);
                                                return stage.abandon()
                                                        .thenApply(
                                                                ignored ->
                                                                        new StoreTypes.PutResult(
                                                                                key,
                                                                                StoreTypes
                                                                                        .PutOutcome
                                                                                        .REFUSED,
                                                                                "SCRIPTED_REFUSAL"));
                                            }
                                            return stage.commit(cancel);
                                        }

                                        @Override
                                        public CompletableFuture<Void> abandon() {
                                            abandoned.set(true);
                                            return stage.abandon();
                                        }

                                        @Override
                                        public CompletableFuture<Void> closeAsync() {
                                            return stage.closeAsync();
                                        }
                                    });
        }
    }
}
