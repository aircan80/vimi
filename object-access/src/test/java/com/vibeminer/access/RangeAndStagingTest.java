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
import com.vibeminer.network.CdnKey;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.MemorySources;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.network.RangePlanner;
import com.vibeminer.store.EncodedFixture;
import com.vibeminer.store.MemoryStore;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.Verification;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class RangeAndStagingTest {
    private static final String FIRST = "level3.blizzard.com", SECOND = "us.cdn.blizzard.com";
    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of(FIRST, SECOND), "tpr/wow"));

    private static Options options(long memory, long range) {
        return new Options(
                1,
                2,
                1,
                8,
                64L << 20,
                64L << 20,
                memory,
                range,
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
                request.expectedTotal(),
                bytes,
                Instant.EPOCH,
                Duration.ZERO,
                Duration.ZERO,
                List.of());
    }

    private static CdnException error(
            CdnRoute route, NetworkTypes.Request request, NetworkTypes.Failure failure) {
        String host = route.hosts().getFirst();
        return new CdnException(
                List.of(
                        new NetworkTypes.Attempt(
                                host,
                                route.urlFor(host, request.kind(), request.key()),
                                failure,
                                null,
                                Instant.EPOCH,
                                "scripted",
                                null)));
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void archivedRequestsMergeThenVerifyAndPersistEachMember() {
        var a = EncodedFixture.chunks(new byte[] {'N', 1, 2});
        var b = EncodedFixture.chunks(new byte[] {'N', 3, 4});
        byte[] archive = new byte[3 + a.bytes().length + 5 + b.bytes().length];
        System.arraycopy(a.bytes(), 0, archive, 3, a.bytes().length);
        int second = 3 + a.bytes().length + 5;
        System.arraycopy(b.bytes(), 0, archive, second, b.bytes().length);
        CdnKey archiveKey = new CdnKey(new byte[16]);
        MemorySources network = new MemorySources();
        network.add(NetworkTypes.ObjectKind.DATA, archiveKey, archive);
        MemoryStore store = new MemoryStore();
        CachingObjectSource source =
                new CachingObjectSource(store, store, store, null, network, List.of());
        try {
            var results =
                    source.getKeys(
                                    List.of(
                                            new ObjectRequest(
                                                    a.key(),
                                                    (long) a.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    3,
                                                                    a.bytes().length)),
                                                    0),
                                            new ObjectRequest(
                                                    b.key(),
                                                    (long) b.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    second,
                                                                    b.bytes().length)),
                                                    0)),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertTrue(results.stream().allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(1, network.requested().size());
            assertArrayEquals(a.bytes(), store.read(a.key()).join().bytes());
            assertArrayEquals(b.bytes(), store.read(b.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void corruptMergedMemberRetriesAloneOnOtherHostWhileGoodMemberStaysCommitted() {
        var a = EncodedFixture.chunks(new byte[] {'N', 1, 2});
        var b = EncodedFixture.chunks(new byte[] {'N', 3, 4});
        byte[] archive = new byte[a.bytes().length + b.bytes().length];
        System.arraycopy(a.bytes(), 0, archive, 0, a.bytes().length);
        System.arraycopy(b.bytes(), 0, archive, a.bytes().length, b.bytes().length);
        CdnKey archiveKey = new CdnKey(new byte[16]);
        AtomicInteger calls = new AtomicInteger();
        MemoryStore store = new MemoryStore();
        CdnClient client =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        int call = calls.incrementAndGet();
                        byte[] bytes =
                                Arrays.copyOfRange(
                                        archive,
                                        request.offset().intValue(),
                                        (int) (request.offset() + request.length()));
                        if (call == 1) {
                            bytes[bytes.length - 1] ^= 1;
                        } else {
                            assertEquals(List.of(SECOND), route.hosts());
                            assertTrue(store.has(a.key()).join());
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
                new CachingObjectSource(store, store, store, null, client, List.of());
        try {
            var results =
                    source.getKeys(
                                    List.of(
                                            new ObjectRequest(
                                                    a.key(),
                                                    (long) a.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    0,
                                                                    a.bytes().length)),
                                                    0),
                                            new ObjectRequest(
                                                    b.key(),
                                                    (long) b.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    a.bytes().length,
                                                                    b.bytes().length)),
                                                    0)),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertTrue(results.stream().allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(2, calls.get());
            assertArrayEquals(b.bytes(), store.read(b.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void passingMergedFailureRetriesEachMemberAsItsOwnRange() {
        var a = EncodedFixture.chunks(new byte[] {'N', 1, 2});
        var b = EncodedFixture.chunks(new byte[] {'N', 3, 4});
        byte[] archive = new byte[a.bytes().length + b.bytes().length];
        System.arraycopy(a.bytes(), 0, archive, 0, a.bytes().length);
        System.arraycopy(b.bytes(), 0, archive, a.bytes().length, b.bytes().length);
        CdnKey archiveKey = new CdnKey(new byte[16]);
        AtomicInteger calls = new AtomicInteger();
        MemoryStore store = new MemoryStore();
        CdnClient client =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        if (calls.incrementAndGet() == 1) {
                            return CompletableFuture.failedFuture(
                                    error(route, request, NetworkTypes.Failure.RANGE_MISMATCH));
                        }
                        return CompletableFuture.completedFuture(
                                reply(
                                        route,
                                        request,
                                        Arrays.copyOfRange(
                                                archive,
                                                request.offset().intValue(),
                                                (int) (request.offset() + request.length()))));
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
                new CachingObjectSource(store, store, store, null, client, List.of());
        try {
            var results =
                    source.getKeys(
                                    List.of(
                                            new ObjectRequest(
                                                    a.key(),
                                                    (long) a.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    0,
                                                                    a.bytes().length)),
                                                    0),
                                            new ObjectRequest(
                                                    b.key(),
                                                    (long) b.bytes().length,
                                                    List.of(
                                                            new RangePlanner.Location(
                                                                    archiveKey,
                                                                    a.bytes().length,
                                                                    b.bytes().length)),
                                                    0)),
                                    CONTEXT,
                                    FetchPriority.BACKGROUND,
                                    null,
                                    Cancellation.NONE)
                            .join();
            assertTrue(results.stream().allMatch(SourceTypes.ObjectResult::isPresent));
            assertEquals(3, calls.get());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void largeObjectsStreamIntoStagingAndResumeOnlyProvenPrefixOnAnotherHost() {
        var fixture = EncodedFixture.chunks(new byte[700], new byte[900]);
        MemoryStore store = new MemoryStore();
        AtomicInteger streams = new AtomicInteger();
        List<Long> offsets = new ArrayList<>();
        CdnClient client =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError("Large object was buffered");
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation token) {
                        offsets.add(request.offset() == null ? 0 : request.offset());
                        if (streams.incrementAndGet() == 1) {
                            byte[] prefix =
                                    Arrays.copyOf(fixture.bytes(), fixture.firstChunkEnd() + 100);
                            return sink.accept(prefix, token)
                                    .thenCompose(
                                            ignored ->
                                                    CompletableFuture.failedFuture(
                                                            error(
                                                                    route,
                                                                    request,
                                                                    NetworkTypes.Failure.NETWORK)));
                        }
                        assertEquals((long) fixture.firstChunkEnd(), request.offset());
                        byte[] tail =
                                Arrays.copyOfRange(
                                        fixture.bytes(),
                                        request.offset().intValue(),
                                        fixture.bytes().length);
                        return sink.accept(tail, token)
                                .thenApply(ignored -> reply(route, request, tail));
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(
                        store,
                        store,
                        store,
                        null,
                        client,
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
            assertEquals(ObjectState.PRESENT, result.state());
            assertTrue(result.bytes() == null);
            assertEquals(List.of(0L, (long) fixture.firstChunkEnd()), offsets);
            assertArrayEquals(fixture.bytes(), store.read(fixture.key()).join().bytes());
        } finally {
            source.closeAsync().join();
        }
    }

    @Test
    void authenticatedLargeLooseRangeStoresNothingAndCanExceedTheStoredPayloadLimit() {
        int count = 600, chunkLength = 1 << 20, headerLength = 12 + 24 * count;
        byte[] chunk = new byte[chunkLength], digest = hash(chunk);
        ByteBuffer header = ByteBuffer.allocate(headerLength);
        header.put(new byte[] {'B', 'L', 'T', 'E'})
                .putInt(headerLength)
                .put((byte) 15)
                .put((byte) (count >>> 16))
                .put((byte) (count >>> 8))
                .put((byte) count);
        for (int i = 0; i < count; i++) {
            header.putInt(chunkLength).putInt(0).put(digest);
        }
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, hash(header.array()));
        long declared = (long) headerLength + (long) count * chunkLength;
        AtomicInteger requests = new AtomicInteger();
        MemoryStore store = new MemoryStore();
        CdnClient client =
                new CdnClient() {
                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetch(
                            CdnRoute route,
                            NetworkTypes.Request request,
                            com.vibeminer.network.Cancellation token) {
                        requests.incrementAndGet();
                        int start = request.offset().intValue(),
                                length = request.length().intValue();
                        byte[] bytes = new byte[length];
                        for (int i = 0; i < length; i++) {
                            int at = start + i;
                            bytes[i] = at < headerLength ? header.array()[at] : 0;
                        }
                        String refused =
                                request.check() == null ? null : request.check().refusal(bytes);
                        assertTrue(refused == null);
                        return CompletableFuture.completedFuture(reply(route, request, bytes));
                    }

                    @Override
                    public CompletableFuture<NetworkTypes.Reply> fetchTo(
                            CdnRoute r,
                            NetworkTypes.Request q,
                            NetworkTypes.Sink sink,
                            com.vibeminer.network.Cancellation t) {
                        throw new AssertionError("Unstored range tried to stage whole object");
                    }
                };
        CachingObjectSource source =
                new CachingObjectSource(store, store, store, null, client, List.of());
        try {
            assertTrue(declared > (512L << 20));
            var result =
                    source.getRange(
                                    new ObjectRequest(key, declared),
                                    headerLength + 123,
                                    8,
                                    CONTEXT,
                                    FetchPriority.INTERACTIVE,
                                    Cancellation.NONE)
                            .join();
            assertEquals(ObjectState.PRESENT, result.state());
            assertArrayEquals(new byte[8], result.bytes());
            assertEquals(2, requests.get());
            assertFalse(store.has(key).join());
        } finally {
            source.closeAsync().join();
        }
    }
}
