package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.NetworkTypes.DocumentKind;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ObjectKind;
import com.vibeminer.network.NetworkTypes.Request;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
final class HttpProtocolTest {
    private static final CdnKey KEY = CdnKey.parse("0123456789abcdef0123456789abcdef");
    private static final CdnRoute ONE = new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow");
    private static final CdnRoute TWO =
            new CdnRoute(List.of("level3.blizzard.com", "us.cdn.blizzard.com"), "tpr/wow");

    private static Request whole() {
        return Request.whole(ObjectKind.DATA, KEY);
    }

    private static Request range() {
        return Request.range(ObjectKind.DATA, KEY, 10, 10, 100L, null);
    }

    private static CdnException failed(java.util.function.Supplier<CompletableFuture<?>> action) {
        return (CdnException)
                assertThrows(CompletionException.class, () -> action.get().join()).getCause();
    }

    private static Dispatcher answer(
            java.util.function.Function<RecordedRequest, MockResponse> response) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return response.apply(request);
            }
        };
    }

    private static NetworkOptions fast(long header, long idle, long total) {
        var times =
                new NetworkOptions.Timeouts(
                        Duration.ofSeconds(1),
                        Duration.ofMillis(header),
                        Duration.ofMillis(idle),
                        Duration.ofMillis(header),
                        Duration.ofMillis(total),
                        Duration.ofMillis(total));
        return new NetworkOptions(
                2, 4, 64L << 20, 512L << 20, Clock.systemUTC(), "vimi-test", times);
    }

    @Test
    void redirectsEncodingsAndUnexpectedMediaAreRefusedWithoutFollowingOrDecoding()
            throws Exception {
        for (MockResponse response :
                List.of(
                        new MockResponse()
                                .setResponseCode(302)
                                .addHeader("Location", "https://evil.test/x"),
                        new MockResponse().setBody("x").addHeader("Content-Encoding", "gzip"),
                        new MockResponse().setBody("x").addHeader("Content-Type", "text/html"))) {
            try (LoopbackSources fixture =
                    new LoopbackSources(new NetworkOptions(), answer(r -> response))) {
                assertEquals(
                        Failure.PROTOCOL,
                        failed(() -> fixture.clients().fetch(ONE, whole())).overall());
                assertEquals(1, fixture.requestCount());
                assertEquals("identity", fixture.takeRequest().getHeader("Accept-Encoding"));
            }
        }
    }

    @Test
    void ignoredRangeStopsAtHeadersEvenWhenTheWholeBodyNeverArrives() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        fast(1000, 1000, 2000),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setBody("whole-body")
                                                .setBodyDelay(2, TimeUnit.SECONDS)))) {
            assertEquals(
                    Failure.RANGE_IGNORED,
                    failed(() -> fixture.clients().fetch(ONE, range())).overall());
            assertEquals(1, fixture.requestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bytes 11-20/100",
                "bytes 10-19/*",
                "bytes 10-19/19",
                "bytes 10-19/999999999999999999999",
                "items 10-19/100"
            })
    void wrongRangeHeadersAreRefused(String header) throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setResponseCode(206)
                                                .setBody("0123456789")
                                                .addHeader("Content-Range", header)))) {
            assertEquals(
                    Failure.RANGE_MISMATCH,
                    failed(() -> fixture.clients().fetch(ONE, range())).overall());
        }
    }

    @Test
    void whole206AndWholeContentRangeAreRefused() throws Exception {
        for (int status : new int[] {200, 206}) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            new NetworkOptions(),
                            answer(
                                    r ->
                                            new MockResponse()
                                                    .setResponseCode(status)
                                                    .setBody("abc")
                                                    .addHeader("Content-Range", "bytes 0-2/3")))) {
                assertEquals(
                        Failure.RANGE_MISMATCH,
                        failed(() -> fixture.clients().fetch(ONE, whole())).overall());
            }
        }
    }

    @Test
    void declaredAndChunkedRepliesEnforceLimitsAndRejectEmptyBodies() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            new NetworkOptions(),
                            answer(
                                    r ->
                                            chunked
                                                    ? new MockResponse().setChunkedBody("abcdef", 2)
                                                    : new MockResponse().setBody("abcdef")))) {
                assertEquals(
                        Failure.TOO_LARGE,
                        failed(
                                        () ->
                                                fixture.clients()
                                                        .fetch(
                                                                ONE,
                                                                Request.whole(
                                                                        ObjectKind.DATA,
                                                                        KEY,
                                                                        null,
                                                                        5L,
                                                                        null)))
                                .overall());
            }
        }
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(), answer(r -> new MockResponse().setBody("")))) {
            assertEquals(
                    Failure.SIZE_CONFLICT,
                    failed(() -> fixture.clients().fetch(ONE, whole())).overall());
        }
    }

    @Test
    void expectedLengthWithoutContentLengthMustStillMatch() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(r -> new MockResponse().setChunkedBody("abc", 2)))) {
            assertEquals(
                    Failure.SIZE_CONFLICT,
                    failed(
                                    () ->
                                            fixture.clients()
                                                    .fetch(
                                                            ONE,
                                                            Request.whole(
                                                                    ObjectKind.DATA,
                                                                    KEY,
                                                                    4L,
                                                                    null,
                                                                    null)))
                            .overall());
        }
    }

    @Test
    void truncatedDeclaredBodiesAreNetworkFailuresNotAbsenceOrSizeConflicts() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setBody("0123456789")
                                                .setSocketPolicy(
                                                        SocketPolicy
                                                                .DISCONNECT_DURING_RESPONSE_BODY)))) {
            assertEquals(
                    Failure.NETWORK, failed(() -> fixture.clients().fetch(ONE, whole())).overall());
        }
    }

    @Test
    void streamingDoesNotFailOverAfterAnyByteReachesTheSink() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setBody("x".repeat(200000))
                                                .setSocketPolicy(
                                                        SocketPolicy
                                                                .DISCONNECT_DURING_RESPONSE_BODY)))) {
            java.util.concurrent.atomic.AtomicInteger received =
                    new java.util.concurrent.atomic.AtomicInteger();
            CdnException error =
                    failed(
                            () ->
                                    fixture.clients()
                                            .fetchTo(
                                                    TWO,
                                                    whole(),
                                                    (b, t) -> {
                                                        received.addAndGet(b.length);
                                                        return CompletableFuture.completedFuture(
                                                                null);
                                                    },
                                                    Cancellation.NONE));
            assertEquals(Failure.NETWORK, error.overall());
            assertTrue(received.get() > 0);
            assertEquals(1, error.attempts().size());
            assertEquals(1, fixture.requestCount());
        }
    }

    @Test
    void retryAfterZeroDoesNotTriggerAnAutomaticSecondRequest() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setResponseCode(503)
                                                .addHeader("Retry-After", "0")))) {
            assertEquals(
                    Failure.HTTP_STATUS,
                    failed(() -> fixture.clients().fetch(ONE, whole())).overall());
            assertEquals(1, fixture.requestCount());
        }
    }

    @Test
    void headerAndIdleTimeoutsRemainDistinctFromCallerCancellation() throws Exception {
        for (boolean headers : new boolean[] {true, false}) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            fast(250, 250, 5000),
                            answer(
                                    r -> {
                                        MockResponse response =
                                                new MockResponse().setBody("abcdef");
                                        return headers
                                                ? response.setHeadersDelay(2, TimeUnit.SECONDS)
                                                : response.setBodyDelay(2, TimeUnit.SECONDS);
                                    }))) {
                assertEquals(
                        Failure.TIMEOUT,
                        failed(() -> fixture.clients().fetch(ONE, whole())).overall());
            }
        }
    }

    @Test
    void activeCancellationAbortsHeadersAndPendingSinkBackpressure() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setSocketPolicy(SocketPolicy.NO_RESPONSE)))) {
            Cancellation token = new Cancellation();
            var fetch = fixture.clients().fetch(ONE, whole(), token);
            assertTrue(fixture.takeRequest() != null);
            token.cancel();
            assertTrue(
                    fetch.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .get(5, TimeUnit.SECONDS));
        }
        CountDownLatch entered = new CountDownLatch(1);
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(), answer(r -> new MockResponse().setBody("data")))) {
            Cancellation token = new Cancellation();
            var fetch =
                    fixture.clients()
                            .fetchTo(
                                    ONE,
                                    whole(),
                                    (b, t) -> {
                                        entered.countDown();
                                        return new CompletableFuture<>();
                                    },
                                    token);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            token.cancel();
            assertTrue(
                    fetch.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void tactKeysUseFixedBoundedPublicSourceAndRefuseRedirects() throws Exception {
        String body = "0123456789ABCDEF 00112233445566778899AABBCCDDEEFF\n";
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(request -> new MockResponse().setBody(body)))) {
            var reply = fixture.clients().fetchTactKeys(Cancellation.NONE).join();
            assertEquals(SourceUrls.TACT_KEYS, reply.url());
            assertEquals(
                    body, new String(reply.bytes(), java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(1, fixture.requestCount());
        }
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                request ->
                                        new MockResponse()
                                                .setResponseCode(302)
                                                .addHeader(
                                                        "Location", "https://example.com/keys")))) {
            assertEquals(
                    Failure.PROTOCOL,
                    failed(() -> fixture.clients().fetchTactKeys(Cancellation.NONE)).overall());
            assertEquals(1, fixture.requestCount());
        }
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                request ->
                                        new MockResponse()
                                                .setBody("x")
                                                .setHeader(
                                                        "Content-Length",
                                                        SourceUrls.TACT_KEY_BYTES + 1)))) {
            assertEquals(
                    Failure.TOO_LARGE,
                    failed(() -> fixture.clients().fetchTactKeys(Cancellation.NONE)).overall());
        }
    }

    @Test
    void definitionsRequireAStrictCommitResponseAndNeverFetchAnUntrustedRevision()
            throws Exception {
        for (String json :
                List.of(
                        "{}",
                        "[{\"sha\":\"bad\"}]",
                        "[{\"sha\":1}]",
                        "[{\"sha\":\"" + "A".repeat(40) + "\"}]",
                        "[{\"sha\":\"" + SourceScenario.REVISION + "\"}] {}",
                        "[".repeat(65) + "]".repeat(65))) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            new NetworkOptions(), answer(r -> new MockResponse().setBody(json)))) {
                assertEquals(
                        Failure.PROTOCOL,
                        failed(() -> fixture.clients().fetch("Item", Cancellation.NONE)).overall());
                assertEquals(1, fixture.requestCount());
            }
        }
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(), answer(r -> new MockResponse().setBody("[]")))) {
            assertEquals(
                    Failure.NOT_FOUND,
                    failed(() -> fixture.clients().fetch("Item", Cancellation.NONE)).overall());
        }
    }

    @Test
    void definitionsShareOneTotalBudgetAcrossCommitAndRawFile() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        fast(2000, 2000, 300),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setBody(
                                                        r.getPath().contains("api.github.com")
                                                                ? "[{\"sha\":\""
                                                                        + SourceScenario.REVISION
                                                                        + "\"}]"
                                                                : "definition")
                                                .setHeadersDelay(200, TimeUnit.MILLISECONDS)))) {
            assertEquals(
                    Failure.TIMEOUT,
                    failed(() -> fixture.clients().fetch("Item", Cancellation.NONE)).overall());
        }
    }

    @Test
    void sourceSpecificByteLimitsAndStatusFailuresArePreserved() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setBody("x")
                                                .setHeader("Content-Length", (1 << 20) + 1)))) {
            assertEquals(
                    Failure.TOO_LARGE,
                    failed(
                                    () ->
                                            fixture.clients()
                                                    .fetch(
                                                            "wow",
                                                            "us",
                                                            DocumentKind.VERSIONS,
                                                            Cancellation.NONE))
                            .overall());
            assertEquals(
                    Failure.PROTOCOL,
                    failed(() -> fixture.clients().fetchRelease(Cancellation.NONE)).overall());
            assertEquals(
                    Failure.PROTOCOL,
                    failed(() -> fixture.clients().fetch("Item", Cancellation.NONE)).overall());
        }
        for (int status : new int[] {403, 404, 429, 500}) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            new NetworkOptions(),
                            answer(r -> new MockResponse().setResponseCode(status)))) {
                assertEquals(
                        status == 404 ? Failure.NOT_FOUND : Failure.HTTP_STATUS,
                        failed(() -> fixture.clients().fetchRelease(Cancellation.NONE)).overall());
            }
        }
    }

    @Test
    void listfileFollowsExactlyOneApprovedRedirectAndReturnsTheCanonicalUrl() throws Exception {
        String signed =
                "https://release-assets.githubusercontent.com/path/asset?signature=private-token";
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        r.getPath().startsWith("/github.com/")
                                                ? new MockResponse()
                                                        .setResponseCode(302)
                                                        .addHeader("Location", signed)
                                                : new MockResponse()
                                                        .setResponseCode(206)
                                                        .setBody("abc")
                                                        .addHeader(
                                                                "Content-Range", "bytes 0-2/3")))) {
            var part = fixture.clients().fetchPart("release-1", 0, Cancellation.NONE).join();
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), part.bytes());
            assertEquals(SourceUrls.asset("release-1"), part.url());
            assertEquals("bytes=0-8388607", fixture.takeRequest().getHeader("Range"));
            assertEquals("bytes=0-8388607", fixture.takeRequest().getHeader("Range"));
        }
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        r.getPath().startsWith("/github.com/")
                                                ? new MockResponse()
                                                        .setResponseCode(302)
                                                        .addHeader("Location", signed)
                                                : new MockResponse().setResponseCode(429)))) {
            CdnException failure =
                    failed(() -> fixture.clients().fetchPart("release-1", 0, Cancellation.NONE));
            assertFalse(failure.toString().contains("private-token"));
            assertFalse(failure.attempts().toString().contains("private-token"));
            assertTrue(failure.attempts().getFirst().url().getRawQuery() == null);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://release-assets.githubusercontent.com/a",
                "https://evil.test/a",
                "https://user@objects.githubusercontent.com/a",
                "https://objects.githubusercontent.com:444/a",
                "/relative",
                "https:/missing-host",
                "https://github.com/a"
            })
    void listfileRejectsUnapprovedRedirectsBeforeFollowing(String location) throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setResponseCode(302)
                                                .addHeader("Location", location)))) {
            assertEquals(
                    Failure.PROTOCOL,
                    failed(() -> fixture.clients().fetchPart("release-1", 0, Cancellation.NONE))
                            .overall());
            assertEquals(1, fixture.requestCount());
        }
    }

    @Test
    void listfileRejectsSecondRedirectOversizedAssetAndWrongPartLength() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setResponseCode(302)
                                                .addHeader(
                                                        "Location",
                                                        "https://objects.githubusercontent.com/a")))) {
            assertEquals(
                    Failure.PROTOCOL,
                    failed(() -> fixture.clients().fetchPart("release-1", 0, Cancellation.NONE))
                            .overall());
            assertEquals(2, fixture.requestCount());
        }
        for (String header : List.of("bytes 0-2/536870913", "bytes 1-3/4", "bytes 0-3/4")) {
            try (LoopbackSources fixture =
                    new LoopbackSources(
                            new NetworkOptions(),
                            answer(
                                    r ->
                                            new MockResponse()
                                                    .setResponseCode(206)
                                                    .setBody("abc")
                                                    .addHeader("Content-Range", header)))) {
                Failure expected =
                        header.endsWith("536870913")
                                ? Failure.TOO_LARGE
                                : header.equals("bytes 0-3/4")
                                        ? Failure.SIZE_CONFLICT
                                        : Failure.RANGE_MISMATCH;
                assertEquals(
                        expected,
                        failed(() -> fixture.clients().fetchPart("release-1", 0, Cancellation.NONE))
                                .overall());
            }
        }
    }

    @Test
    void streamingCanExceedTheMemoryBoundAndVerifierErrorsRemainTheCallers() throws Exception {
        var options =
                new NetworkOptions(
                        2, 4, 4, 32, Clock.systemUTC(), "vimi-test", new NetworkOptions.Timeouts());
        try (LoopbackSources fixture =
                new LoopbackSources(
                        options, answer(r -> new MockResponse().setBody("abcdefghijklmnop")))) {
            assertEquals(
                    Failure.TOO_LARGE,
                    failed(() -> fixture.clients().fetch(ONE, whole())).overall());
            java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
            fixture.clients()
                    .fetchTo(
                            ONE,
                            whole(),
                            (b, t) -> {
                                received.writeBytes(b);
                                return CompletableFuture.completedFuture(null);
                            },
                            Cancellation.NONE)
                    .join();
            assertEquals(16, received.size());
        }
        IllegalStateException expected = new IllegalStateException("verifier");
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(), answer(r -> new MockResponse().setBody("data")))) {
            CompletionException failure =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    fixture.clients()
                                            .fetch(
                                                    ONE,
                                                    Request.whole(
                                                            ObjectKind.DATA,
                                                            KEY,
                                                            null,
                                                            null,
                                                            b -> {
                                                                throw expected;
                                                            }))
                                            .join());
            assertTrue(failure.getCause() == expected);
        }
    }

    @Test
    void brokenConnectionsAreNotSilentlyRetriedAndDnsFailuresRemainOffline() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        new MockResponse()
                                                .setSocketPolicy(
                                                        SocketPolicy.DISCONNECT_AFTER_REQUEST)))) {
            failed(() -> fixture.clients().fetch(ONE, whole()));
            assertEquals(1, fixture.requestCount());
        }
        NetworkOptions options = new NetworkOptions();
        var transport =
                new okhttp3.OkHttpClient.Builder()
                        .proxy(java.net.Proxy.NO_PROXY)
                        .dns(
                                host -> {
                                    throw new java.net.UnknownHostException("synthetic");
                                })
                        .build();
        HttpEngine engine =
                new HttpEngine(
                        options, "offline", 1, transport, java.util.function.Function.identity());
        NetworkClients clients = new NetworkClients(options, engine, engine, engine);
        try {
            assertEquals(Failure.OFFLINE, failed(() -> clients.fetch(ONE, whole())).overall());
        } finally {
            clients.closeAsync().join();
        }
    }

    @Test
    void sourceConcurrencyAndCloseDrainAcceptedFetches() throws Exception {
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger
                active = new java.util.concurrent.atomic.AtomicInteger(),
                maximum = new java.util.concurrent.atomic.AtomicInteger();
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r -> {
                                    int running = active.incrementAndGet();
                                    maximum.accumulateAndGet(running, Math::max);
                                    entered.countDown();
                                    try {
                                        if (!release.await(5, TimeUnit.SECONDS)) {
                                            throw new AssertionError("Timed out");
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError(e);
                                    } finally {
                                        active.decrementAndGet();
                                    }
                                    return new MockResponse()
                                            .setBody(
                                                    r.getPath().contains("api.github.com")
                                                            ? "[{\"sha\":\""
                                                                    + SourceScenario.REVISION
                                                                    + "\"}]"
                                                            : "definition");
                                }))) {
            java.util.ArrayList<CompletableFuture<?>> tasks = new java.util.ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    tasks.add(fixture.clients().fetch("Item" + i, Cancellation.NONE));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(2, fixture.requestCount());
                CompletableFuture<Void> closing = fixture.clients().closeAsync();
                assertFalse(closing.isDone());
                release.countDown();
                closing.join();
                assertTrue(tasks.stream().allMatch(CompletableFuture::isDone));
                CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
                assertEquals(2, maximum.get());
                CompletionException error =
                        assertThrows(
                                CompletionException.class,
                                () -> fixture.clients().fetchRelease(Cancellation.NONE).join());
                assertEquals("NETWORK_CLOSED", ((NetworkException) error.getCause()).code());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void tlsHttp2UsesTheSameWholeAndRangeChecks() throws Exception {
        try (LoopbackSources fixture =
                new LoopbackSources(
                        new NetworkOptions(),
                        answer(
                                r ->
                                        r.getHeader("Range") == null
                                                ? new MockResponse().setBody("http2")
                                                : new MockResponse()
                                                        .setResponseCode(206)
                                                        .setBody("ttp")
                                                        .addHeader("Content-Range", "bytes 1-3/5")),
                        true)) {
            NetworkTypes.Reply reply;
            try {
                reply = fixture.clients().fetch(ONE, whole()).join();
            } catch (CompletionException error) {
                throw new AssertionError(
                        "TLS fixture failure: " + fixture.transportFailures(), error);
            }
            assertEquals("h2", reply.httpVersion());
            var part =
                    fixture.clients()
                            .fetch(ONE, Request.range(ObjectKind.DATA, KEY, 1, 3, 5L, null))
                            .join();
            assertEquals("h2", part.httpVersion());
            assertArrayEquals("ttp".getBytes(StandardCharsets.UTF_8), part.bytes());
            assertArrayEquals("http2".getBytes(StandardCharsets.UTF_8), reply.bytes());
        }
    }
}
