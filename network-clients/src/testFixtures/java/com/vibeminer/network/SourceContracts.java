package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import com.vibeminer.network.NetworkTypes.Request;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Behaviors promised to layer 2, shared by the independent fake and real HTTP clients. */
@Timeout(30)
public abstract class SourceContracts {
    protected SourceScenario scenario;

    protected abstract boolean http();

    protected static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    protected static final CdnRoute ROUTE =
            new CdnRoute(List.of(SourceScenario.FIRST, SourceScenario.SECOND), "tpr/wow");

    protected static CdnKey key(int n) {
        return CdnKey.parse(String.format(java.util.Locale.ROOT, "%032x", n));
    }

    protected static byte[] bytes(int n) {
        byte[] bytes = new byte[n];
        new java.util.Random(42).nextBytes(bytes);
        return bytes;
    }

    @BeforeEach
    final void setup() throws Exception {
        scenario =
                new SourceScenario(
                        http(),
                        new NetworkOptions(
                                4,
                                64,
                                64L << 20,
                                512L << 20,
                                Clock.fixed(NOW, ZoneOffset.UTC),
                                "vimi-test",
                                new NetworkOptions.Timeouts()));
    }

    @AfterEach
    final void cleanup() throws Exception {
        scenario.close();
    }

    private CdnException failure(Runnable work) {
        return (CdnException) assertThrows(CompletionException.class, work::run).getCause();
    }

    @Test
    void wholeAndExactRangeReturnOwnedBytesAndDatedMetadata() {
        byte[] body = bytes(200000);
        scenario.add(ObjectKind.DATA, key(1), body);
        var whole = scenario.cdn().fetch(ROUTE, Request.whole(ObjectKind.DATA, key(1))).join();
        assertArrayEquals(body, whole.bytes());
        assertEquals(NOW, whole.observedAt());
        assertEquals(SourceScenario.FIRST, whole.host());
        assertTrue(whole.failed().isEmpty());
        var range =
                scenario.cdn()
                        .fetch(
                                ROUTE,
                                Request.range(
                                        ObjectKind.DATA,
                                        key(1),
                                        65530,
                                        20,
                                        (long) body.length,
                                        null))
                        .join();
        assertArrayEquals(Arrays.copyOfRange(body, 65530, 65550), range.bytes());
        assertEquals((long) body.length, range.total());
        byte[] copy = whole.bytes();
        copy[0] ^= 1;
        assertArrayEquals(body, whole.bytes());
    }

    @Test
    void absentKeysAreDatedRetryableObservations() {
        CdnException error =
                failure(
                        () ->
                                scenario.cdn()
                                        .fetch(ROUTE, Request.whole(ObjectKind.CONFIG, key(9)))
                                        .join());
        assertEquals(Failure.NOT_FOUND, error.overall());
        assertTrue(error.retryable());
        assertEquals(2, error.attempts().size());
        assertEquals(NOW, error.attempts().getFirst().observedAt());
    }

    @Test
    void missingAndUnhealthyHostsFallBackInAdvertisedOrder() {
        byte[] body = bytes(1000);
        scenario.add(ObjectKind.CONFIG, key(1), body);
        for (Failure f : List.of(Failure.NOT_FOUND, Failure.HTTP_STATUS)) {
            scenario.fail(SourceScenario.FIRST, f);
            var reply =
                    scenario.cdn().fetch(ROUTE, Request.whole(ObjectKind.CONFIG, key(1))).join();
            assertArrayEquals(body, reply.bytes());
            assertEquals(SourceScenario.SECOND, reply.host());
            assertEquals(f, reply.failed().getFirst().failure());
        }
    }

    @Test
    void ignoredRangesFallBackWithoutReturningWholeObjects() {
        byte[] body = bytes(1000);
        scenario.add(ObjectKind.DATA, key(1), body);
        scenario.fail(SourceScenario.FIRST, Failure.RANGE_IGNORED);
        var reply =
                scenario.cdn()
                        .fetch(ROUTE, Request.range(ObjectKind.DATA, key(1), 10, 10, null, null))
                        .join();
        assertArrayEquals(Arrays.copyOfRange(body, 10, 20), reply.bytes());
        assertEquals(Failure.RANGE_IGNORED, reply.failed().getFirst().failure());
    }

    @Test
    void keyChecksRejectBadRepliesAndPreserveRefusalCodes() {
        byte[] body = bytes(1000);
        scenario.add(ObjectKind.CONFIG, key(1), body);
        Request request =
                Request.whole(
                        ObjectKind.CONFIG,
                        key(1),
                        null,
                        null,
                        b -> Arrays.equals(b, body) ? null : "OBJECT_KEY_MISMATCH");
        assertArrayEquals(body, scenario.cdn().fetch(ROUTE, request).join().bytes());
        CdnException error =
                failure(
                        () ->
                                scenario.cdn()
                                        .fetch(
                                                ROUTE,
                                                Request.whole(
                                                        ObjectKind.CONFIG,
                                                        key(1),
                                                        null,
                                                        null,
                                                        b -> "REFUSED"))
                                        .join());
        assertEquals(Failure.VERIFICATION_FAILED, error.overall());
        assertEquals("REFUSED", error.code());
    }

    @Test
    void sizeExpectationsAndCallerLimitsAreEnforced() {
        scenario.add(ObjectKind.DATA, key(1), bytes(100));
        assertEquals(
                Failure.SIZE_CONFLICT,
                failure(
                                () ->
                                        scenario.cdn()
                                                .fetch(
                                                        ROUTE,
                                                        Request.whole(
                                                                ObjectKind.DATA,
                                                                key(1),
                                                                99L,
                                                                null,
                                                                null))
                                                .join())
                        .overall());
        assertEquals(
                Failure.TOO_LARGE,
                failure(
                                () ->
                                        scenario.cdn()
                                                .fetch(
                                                        ROUTE,
                                                        Request.whole(
                                                                ObjectKind.DATA,
                                                                key(1),
                                                                null,
                                                                99L,
                                                                null))
                                                .join())
                        .overall());
        assertEquals(
                Failure.SIZE_CONFLICT,
                failure(
                                () ->
                                        scenario.cdn()
                                                .fetch(
                                                        ROUTE,
                                                        Request.range(
                                                                ObjectKind.DATA,
                                                                key(1),
                                                                0,
                                                                10,
                                                                101L,
                                                                null))
                                                .join())
                        .overall());
    }

    @Test
    void streamingDeliversOrderedChunksAndDoesNotRunWholeChecks() {
        byte[] body = bytes(200000);
        scenario.add(ObjectKind.DATA, key(1), body);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        var reply =
                scenario.cdn()
                        .fetchTo(
                                ROUTE,
                                Request.whole(
                                        ObjectKind.DATA,
                                        key(1),
                                        null,
                                        null,
                                        b -> {
                                            throw new AssertionError(
                                                    "Streaming cannot run whole checks");
                                        }),
                                (chunk, token) -> {
                                    assertTrue(chunk.length <= 65536);
                                    received.writeBytes(chunk);
                                    return CompletableFuture.completedFuture(null);
                                },
                                Cancellation.NONE)
                        .join();
        assertArrayEquals(body, received.toByteArray());
        assertNull(reply.bytes());
        assertEquals(body.length, reply.length());
    }

    @Test
    void sinkFailuresArePreservedAndDoNotTryAnotherHost() {
        scenario.add(ObjectKind.DATA, key(1), bytes(100));
        IllegalStateException expected = new IllegalStateException("sink");
        CompletionException error =
                assertThrows(
                        CompletionException.class,
                        () ->
                                scenario.cdn()
                                        .fetchTo(
                                                ROUTE,
                                                Request.whole(ObjectKind.DATA, key(1)),
                                                (b, t) -> CompletableFuture.failedFuture(expected),
                                                Cancellation.NONE)
                                        .join());
        assertTrue(error.getCause() == expected);
    }

    @Test
    void versionsDefinitionsAndListfileRepliesPreserveRawBytesAndSourceIdentity() {
        byte[] document =
                "Name!STRING:0|BuildId!DEC:4\nus|12345\n".getBytes(StandardCharsets.UTF_8);
        scenario.version("wow", "us", DocumentKind.VERSIONS, document);
        var version =
                scenario.versions()
                        .fetch("wow", "us", DocumentKind.VERSIONS, Cancellation.NONE)
                        .join();
        assertArrayEquals(document, version.bytes());
        assertEquals(SourceUrls.version("wow", "us", DocumentKind.VERSIONS), version.url());
        assertEquals(NOW, version.observedAt());
        byte[] definition = "COLUMNS\nint ID\n".getBytes(StandardCharsets.UTF_8);
        scenario.definition("Item", definition);
        var def = scenario.definitions().fetch("Item", Cancellation.NONE).join();
        assertArrayEquals(definition, def.bytes());
        assertEquals(SourceScenario.REVISION, def.revision());
        assertEquals(SourceUrls.definition(SourceScenario.REVISION, "Item"), def.url());
        byte[] release = "{\"tag_name\":\"2026-11-01\"}".getBytes(StandardCharsets.UTF_8);
        scenario.release(release);
        assertArrayEquals(
                release, scenario.listfile().fetchRelease(Cancellation.NONE).join().bytes());
    }

    @Test
    void listfilePartUsesItsCanonicalUrlAndExactOffset() {
        byte[] asset = bytes(SourceUrls.PART_BYTES + 123);
        scenario.asset("release-1", asset);
        var part = scenario.listfile().fetchPart("release-1", 1, Cancellation.NONE).join();
        assertEquals(SourceUrls.PART_BYTES, part.offset());
        assertEquals(asset.length, part.totalLength());
        assertArrayEquals(
                Arrays.copyOfRange(asset, SourceUrls.PART_BYTES, asset.length), part.bytes());
        assertEquals(SourceUrls.asset("release-1"), part.url());
    }

    @Test
    void sourceByteBoundsMatchForFakeAndHttpClients() {
        byte[] oversized = bytes(SourceUrls.DOCUMENT_BYTES + 1);
        scenario.version("wow", "us", DocumentKind.VERSIONS, oversized);
        assertEquals(
                Failure.TOO_LARGE,
                failure(
                                () ->
                                        scenario.versions()
                                                .fetch(
                                                        "wow",
                                                        "us",
                                                        DocumentKind.VERSIONS,
                                                        Cancellation.NONE)
                                                .join())
                        .overall());
        scenario.definition("Item", oversized);
        assertEquals(
                Failure.TOO_LARGE,
                failure(() -> scenario.definitions().fetch("Item", Cancellation.NONE).join())
                        .overall());
        scenario.release(oversized);
        assertEquals(
                Failure.PROTOCOL,
                failure(() -> scenario.listfile().fetchRelease(Cancellation.NONE).join())
                        .overall());
    }

    @Test
    void emptyVersionIsRefusedWhileRawDefinitionAndReleaseCanBeEmpty() {
        scenario.version("wow", "us", DocumentKind.VERSIONS, new byte[0]);
        assertEquals(
                Failure.SIZE_CONFLICT,
                failure(
                                () ->
                                        scenario.versions()
                                                .fetch(
                                                        "wow",
                                                        "us",
                                                        DocumentKind.VERSIONS,
                                                        Cancellation.NONE)
                                                .join())
                        .overall());
        scenario.definition("Item", new byte[0]);
        assertArrayEquals(
                new byte[0],
                scenario.definitions().fetch("Item", Cancellation.NONE).join().bytes());
        scenario.release(new byte[0]);
        assertArrayEquals(
                new byte[0], scenario.listfile().fetchRelease(Cancellation.NONE).join().bytes());
    }

    @Test
    void explicitCancellationRemainsCancellationForEveryBoundary() {
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        List<CompletableFuture<?>> futures =
                List.of(
                        scenario.cdn()
                                .fetch(ROUTE, Request.whole(ObjectKind.DATA, key(1)), canceled),
                        scenario.versions().fetch("wow", "us", DocumentKind.VERSIONS, canceled),
                        scenario.definitions().fetch("Item", canceled),
                        scenario.listfile().fetchRelease(canceled),
                        scenario.listfile().fetchPart("release-1", 0, canceled));
        for (var future : futures) {
            assertTrue(
                    future.handle(
                                    (v, e) ->
                                            e instanceof java.util.concurrent.CancellationException
                                                    || e instanceof CompletionException
                                                            && e.getCause()
                                                                    instanceof
                                                                    java.util.concurrent
                                                                            .CancellationException)
                            .join());
        }
    }
}
