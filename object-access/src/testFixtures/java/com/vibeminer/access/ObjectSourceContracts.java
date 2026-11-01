package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ServedFrom;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.network.SourceUrls;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.StoreTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Public source behavior, run against an independent fake and real memory/disk-backed
 * implementations.
 */
@Timeout(30)
public abstract class ObjectSourceContracts {
    private AccessScenario fixture;

    protected abstract AccessScenario create();

    private static final CdnContext CONTEXT =
            new CdnContext(new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow"));

    @BeforeEach
    final void start() {
        fixture = create();
    }

    @AfterEach
    final void stop() {
        fixture.close();
    }

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static ObjectKey key(byte[] bytes) {
        try {
            return new ObjectKey(ObjectKey.CONFIG, MessageDigest.getInstance("MD5").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void downloadedObjectsAreVerifiedCommittedAndThenReadOffline() {
        byte[] bytes = bytes("one config");
        ObjectKey key = key(bytes);
        fixture.remote(key, bytes);
        assertEquals(
                List.of(false), fixture.source.hasKeys(List.of(key), Cancellation.NONE).join());
        var fetched =
                fixture.source
                        .getKey(
                                new ObjectRequest(key),
                                CONTEXT,
                                FetchPriority.BACKGROUND,
                                Cancellation.NONE)
                        .join();
        assertEquals(ObjectState.PRESENT, fetched.state());
        assertEquals(ServedFrom.CDN, fetched.servedFrom());
        assertArrayEquals(bytes, fetched.bytes());
        int network = fixture.transfers();
        var local =
                fixture.source
                        .getKey(
                                new ObjectRequest(key),
                                null,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertEquals(ServedFrom.STORE, local.servedFrom());
        assertArrayEquals(bytes, local.bytes());
        assertEquals(network, fixture.transfers());
        assertEquals(1L, fixture.source.countPresent(List.of(key), Cancellation.NONE).join());
    }

    @Test
    void ownStoreWinsOverSharedAndRemoteAndHasKeysCountsOnlyOwn() {
        byte[] one = bytes("stored"), two = bytes("shared");
        ObjectKey first = key(one), second = key(two);
        fixture.local(first, one);
        fixture.shared(second, two);
        assertEquals(
                List.of(true, false, false),
                fixture.source
                        .hasKeys(List.of(first, second, key(bytes("missing"))), Cancellation.NONE)
                        .join());
        var result =
                fixture.source
                        .getKey(
                                new ObjectRequest(second),
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertEquals(ServedFrom.SHARED_STORE, result.servedFrom());
        assertEquals(StoreTypes.Origin.SHARED_STORE, result.origin());
        assertArrayEquals(two, result.bytes());
        assertEquals(0, fixture.transfers());
        assertEquals(
                List.of(true, true),
                fixture.source.hasKeys(List.of(first, second), Cancellation.NONE).join());
    }

    @Test
    void duplicateBatchRequestsReturnOrderedMetadataWithoutPayloads() {
        byte[] first = bytes("first"), second = bytes("second");
        var a = new ObjectRequest(key(first));
        var b = new ObjectRequest(key(second));
        fixture.remote(a.key(), first);
        fixture.remote(b.key(), second);
        var result =
                fixture.source
                        .getKeys(
                                List.of(a, b, a),
                                CONTEXT,
                                FetchPriority.BACKGROUND,
                                null,
                                Cancellation.NONE)
                        .join();
        assertEquals(
                List.of(a.key(), b.key(), a.key()),
                result.stream().map(SourceTypes.ObjectResult::key).toList());
        assertTrue(result.stream().allMatch(SourceTypes.ObjectResult::isPresent));
        assertTrue(result.stream().allMatch(r -> r.bytes() == null));
        assertEquals(2, fixture.transfers());
        assertArrayEquals(
                first,
                fixture.source
                        .getKey(a, null, FetchPriority.INTERACTIVE, Cancellation.NONE)
                        .join()
                        .bytes());
    }

    @Test
    void badKeyBytesAreRefusedWithoutWritingAndOneBadObjectDoesNotFailAnother() {
        byte[] good = bytes("good"), bad = bytes("bad");
        ObjectKey wrong = key(bytes("expected"));
        fixture.remote(wrong, bad);
        fixture.remote(key(good), good);
        var results =
                fixture.source
                        .getKeys(
                                List.of(new ObjectRequest(wrong), new ObjectRequest(key(good))),
                                CONTEXT,
                                FetchPriority.BACKGROUND,
                                null,
                                Cancellation.NONE)
                        .join();
        assertEquals(ObjectState.REFUSED, results.get(0).state());
        assertEquals("OBJECT_KEY_MISMATCH", results.get(0).code());
        assertEquals(ObjectState.PRESENT, results.get(1).state());
        assertEquals(
                List.of(false, true),
                fixture.source.hasKeys(List.of(wrong, key(good)), Cancellation.NONE).join());
    }

    @Test
    void missingContextAndMissingHostsAreDifferentRetryableStates() {
        var request = new ObjectRequest(key(bytes("absent")));
        var noContext =
                fixture.source
                        .getKey(request, null, FetchPriority.INTERACTIVE, Cancellation.NONE)
                        .join();
        assertEquals(ObjectState.UNAVAILABLE, noContext.state());
        assertTrue(noContext.retryable());
        var missing =
                fixture.source
                        .getKey(request, CONTEXT, FetchPriority.INTERACTIVE, Cancellation.NONE)
                        .join();
        assertEquals(ObjectState.NOT_FOUND, missing.state());
        assertTrue(missing.retryable());
        assertTrue(missing.observedAt() != null);
    }

    @Test
    void rangeReadsReturnOnlyTheirVerifiedBytesAndEnforceBounds() {
        byte[] bytes = bytes("0123456789");
        ObjectKey key = key(bytes);
        fixture.remote(key, bytes);
        ObjectRequest request = new ObjectRequest(key, 10L);
        var result =
                fixture.source
                        .getRange(
                                request,
                                3,
                                4,
                                CONTEXT,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join();
        assertArrayEquals(bytes("3456"), result.bytes());
        assertEquals(10L, result.length());
        assertArrayEquals(
                new byte[0],
                fixture.source
                        .getRange(
                                request, 10, 0, null, FetchPriority.INTERACTIVE, Cancellation.NONE)
                        .join()
                        .bytes());
        assertThrows(
                CompletionException.class,
                () ->
                        fixture.source
                                .getRange(
                                        request,
                                        9,
                                        2,
                                        CONTEXT,
                                        FetchPriority.INTERACTIVE,
                                        Cancellation.NONE)
                                .join());
    }

    @Test
    void observationsCanBeDeferredOrRecordedAndHistoryDocumentsAreReadable() {
        byte[] document = bytes("Region!STRING:0|BuildId!DEC:4\nus|123\n");
        fixture.version("wow", "us", NetworkTypes.DocumentKind.VERSIONS, document);
        var deferred =
                fixture.source
                        .observe(
                                new ObservationRequest(
                                        ObservationKind.VERSIONS, "wow", "us", null, false),
                                Cancellation.NONE)
                        .join();
        assertFalse(deferred.recorded());
        assertArrayEquals(document, deferred.observation().bytes());
        assertTrue(
                fixture.source
                        .readHistory(new History.Query(), Cancellation.NONE)
                        .join()
                        .isEmpty());
        var recorded =
                fixture.source
                        .observe(
                                new ObservationRequest(ObservationKind.VERSIONS, "wow", "us"),
                                Cancellation.NONE)
                        .join();
        assertTrue(recorded.recorded());
        var entries =
                fixture.source
                        .readHistory(new History.Query("wow", "us", 1, null, 10), Cancellation.NONE)
                        .join();
        assertEquals(1, entries.size());
        assertArrayEquals(
                document,
                fixture.source
                        .getKey(
                                new ObjectRequest(entries.getFirst().document()),
                                null,
                                FetchPriority.INTERACTIVE,
                                Cancellation.NONE)
                        .join()
                        .bytes());
        assertEquals(
                "OBSERVATION_REQUEST_INVALID",
                fixture.source.record(deferred.observation(), Cancellation.NONE).join().code());
    }

    @Test
    void heldListfilePartsAndDeltasRequireCanonicalIdentityBeforeRecording() {
        Observation part =
                new Observation(
                        ObservationKind.LISTFILE_PART,
                        "release-1",
                        "p0",
                        SourceUrls.asset("release-1"),
                        Instant.EPOCH,
                        bytes("csv"));
        assertTrue(fixture.source.record(part, Cancellation.NONE).join().recorded());
        Observation bad =
                new Observation(
                        ObservationKind.LISTFILE_PART,
                        "release-1",
                        "p01",
                        part.url(),
                        part.observedAt(),
                        part.bytes());
        assertEquals(
                ObjectState.REFUSED, fixture.source.record(bad, Cancellation.NONE).join().state());
        Observation delta =
                new Observation(
                        ObservationKind.LISTFILE_DELTA,
                        "release-1",
                        "delta",
                        part.url(),
                        Instant.EPOCH,
                        bytes("delta"));
        assertTrue(fixture.source.record(delta, Cancellation.NONE).join().recorded());
        assertEquals(
                2,
                fixture.source.readHistory(new History.Query(), Cancellation.NONE).join().size());
    }

    @Test
    void committedNotificationsAgreeWithPresenceAndSlowReadersSeeMissedFlag() {
        try (var subscription = fixture.commits.subscribe(1)) {
            byte[] first = bytes("a"), second = bytes("b");
            fixture.remote(key(first), first);
            fixture.remote(key(second), second);
            fixture.source
                    .getKey(
                            new ObjectRequest(key(first)),
                            CONTEXT,
                            FetchPriority.INTERACTIVE,
                            Cancellation.NONE)
                    .join();
            fixture.source
                    .getKey(
                            new ObjectRequest(key(second)),
                            CONTEXT,
                            FetchPriority.INTERACTIVE,
                            Cancellation.NONE)
                    .join();
            var commit = subscription.poll();
            assertTrue(commit != null);
            assertEquals(List.of(key(first)), commit.stored());
            assertEquals(
                    List.of(true),
                    fixture.source.hasKeys(commit.stored(), Cancellation.NONE).join());
            assertTrue(subscription.takeMissed());
            assertFalse(subscription.takeMissed());
            assertNull(subscription.poll());
        }
    }

    @Test
    void cancellationDoesNotTurnIntoAnObjectFailureOrWriteBytes() {
        Cancellation token = new Cancellation();
        token.cancel();
        byte[] bytes = bytes("cancel");
        ObjectKey key = key(bytes);
        fixture.remote(key, bytes);
        var result =
                fixture.source.getKey(
                        new ObjectRequest(key), CONTEXT, FetchPriority.INTERACTIVE, token);
        assertTrue(
                result.handle(
                                (value, error) ->
                                        error instanceof java.util.concurrent.CancellationException
                                                || error instanceof CompletionException
                                                        && error.getCause()
                                                                instanceof
                                                                java.util.concurrent
                                                                        .CancellationException)
                        .join());
        assertEquals(
                List.of(false), fixture.source.hasKeys(List.of(key), Cancellation.NONE).join());
    }
}
