package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.activity.NetworkActivity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class HttpActivityTest {
    private static final CdnRoute ROUTE = new CdnRoute(List.of("us.cdn.blizzard.com"), "tpr/wow");
    private static final NetworkTypes.Request REQUEST =
            NetworkTypes.Request.whole(
                    NetworkTypes.ObjectKind.DATA, CdnKey.parse("0123456789abcdef0123456789abcdef"));

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void headersWaitRealBodyBytesAndEofAreVisible() throws Exception {
        var meter = new NetworkActivity();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request)
                            throws InterruptedException {
                        entered.countDown();
                        release.await(5, TimeUnit.SECONDS);
                        return new MockResponse()
                                .setBody("actual-body")
                                .addHeader("Content-Type", "application/octet-stream");
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), dispatcher, false, meter)) {
            var result = fixture.clients().fetch(ROUTE, REQUEST);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, meter.snapshot().waitingResponse());
            assertEquals(0, meter.snapshot().receivedBytes());
            release.countDown();
            result.join();
            assertEquals(11, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sinkBackpressureIsHandoffAndCancellationKeepsPartialBytes() throws Exception {
        var meter = new NetworkActivity();
        var sinkEntered = new CountDownLatch(1);
        var sinkRelease = new CompletableFuture<Void>();
        var token = new Cancellation();
        var dispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody("x".repeat(100000))
                                .addHeader("Content-Type", "application/octet-stream");
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), dispatcher, false, meter)) {
            var result =
                    fixture.clients()
                            .fetchTo(
                                    ROUTE,
                                    REQUEST,
                                    (bytes, cancel) -> {
                                        sinkEntered.countDown();
                                        return sinkRelease;
                                    },
                                    token);
            assertTrue(sinkEntered.await(5, TimeUnit.SECONDS));
            assertEquals(1, meter.snapshot().handoff());
            assertTrue(meter.snapshot().receivedBytes() > 0);
            long partial = meter.snapshot().receivedBytes();
            token.cancel();
            sinkRelease.complete(null);
            assertThrows(java.util.concurrent.CancellationException.class, result::join);
            assertEquals(partial, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
        } finally {
            sinkRelease.complete(null);
        }
    }

    @Test
    void stalledBodyCancelAndRejectedUnreadBodyBalance() throws Exception {
        var meter = new NetworkActivity();
        var dispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody("x")
                                .setSocketPolicy(SocketPolicy.NO_RESPONSE);
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), dispatcher, false, meter)) {
            var token = new Cancellation();
            var result = fixture.clients().fetch(ROUTE, REQUEST, token);
            await(() -> meter.snapshot().active() == 1);
            token.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, result::join);
            assertEquals(0, meter.snapshot().active());
            assertEquals(0, meter.snapshot().receivedBytes());
        }
        var rejected =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody("not-read")
                                .addHeader("Content-Type", "text/html");
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), rejected, false, meter)) {
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> fixture.clients().fetch(ROUTE, REQUEST).join());
            assertEquals(0, meter.snapshot().active());
            assertEquals(0, meter.snapshot().receivedBytes());
        }
    }

    @Test
    void everyClientFamilyLabelsActualMetadataBodyBytes() throws Exception {
        var meter = new NetworkActivity();
        String commit = "a".repeat(40);
        String commits = "[{\"sha\":\"" + commit + "\"}]";
        var dispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody(request.getPath().contains("/commits?") ? commits : "abc");
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), dispatcher, false, meter)) {
            var clients = fixture.clients();
            clients.fetch("wow", "us", NetworkTypes.DocumentKind.VERSIONS, Cancellation.NONE)
                    .join();
            clients.fetch("Item", Cancellation.NONE).join();
            clients.fetchTactKeys(Cancellation.NONE).join();
            clients.fetchEgLinkHead(Cancellation.NONE).join();
            clients.fetchEgLinkFile(commit, "data/index.json", Cancellation.NONE).join();
            clients.fetchPolestarHead(Cancellation.NONE).join();
            clients.fetchPolestarFile(commit, "index.json", Cancellation.NONE).join();
            clients.fetchRelease(Cancellation.NONE).join();
            assertEquals(0, meter.snapshot().active());
            for (var category : meter.snapshot().categories()) {
                long expected =
                        switch (category.kind()) {
                            case BUILD_METADATA, TACT_KEYS, LISTFILE -> 3;
                            case DEFINITIONS -> commits.length() + 3L;
                            case EG_LINK, POLESTAR -> 6;
                            case CDN, QUESTIE -> 0;
                        };
                assertEquals(expected, category.receivedBytes(), category.kind().name());
            }
        }
    }

    @Test
    void partialBodyFailureKeepsOnlyReceivedBytes() throws Exception {
        var meter = new NetworkActivity();
        var dispatcher =
                new Dispatcher() {
                    @Override
                    public MockResponse dispatch(RecordedRequest request) {
                        return new MockResponse()
                                .setBody("x".repeat(65536))
                                .addHeader("Content-Type", "application/octet-stream")
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
                    }
                };
        try (var fixture = new LoopbackSources(new NetworkOptions(), dispatcher, false, meter)) {
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> fixture.clients().fetch(ROUTE, REQUEST).join());
            assertEquals(0, meter.snapshot().active());
            assertTrue(meter.snapshot().receivedBytes() > 0);
            assertTrue(meter.snapshot().receivedBytes() < 65536);
        }
    }
}
