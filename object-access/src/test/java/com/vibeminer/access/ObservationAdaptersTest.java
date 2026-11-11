package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.network.CdnException;
import com.vibeminer.network.ListfileClient;
import com.vibeminer.network.NetworkTypes.Failure;
import com.vibeminer.network.NetworkTypes.ListfilePart;
import com.vibeminer.network.NetworkTypes.ListfileReply;
import com.vibeminer.network.NetworkTypes.VersionReply;
import com.vibeminer.network.SourceUrls;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ObservationAdaptersTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void versionKindsMapExplicitlyAndPreserveRawObservationIdentity() {
        AtomicInteger calls = new AtomicInteger();
        ObservationSource source =
                ObservationAdapters.versions(
                        (product, region, kind, token) -> {
                            calls.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    new VersionReply(
                                            product,
                                            region,
                                            kind,
                                            SourceUrls.version(product, region, kind),
                                            NOW,
                                            new byte[] {1, 2},
                                            Duration.ZERO));
                        });
        assertEquals(
                Set.of(
                        ObservationKind.VERSIONS,
                        ObservationKind.CDNS,
                        ObservationKind.BGDL,
                        ObservationKind.SUMMARY),
                source.kinds());
        for (ObservationKind kind : source.kinds()) {
            String product = kind == ObservationKind.SUMMARY ? "" : "wow";
            var observation =
                    source.fetch(new ObservationRequest(kind, product, "us"), Cancellation.NONE)
                            .join();
            assertEquals(kind, observation.kind());
            assertEquals(product, observation.product());
            assertEquals("us", observation.region());
            assertEquals(NOW, observation.observedAt());
            assertArrayEquals(new byte[] {1, 2}, observation.bytes());
        }
        assertEquals(4, calls.get());
        assertThrows(
                CompletionException.class,
                () ->
                        source.fetch(
                                        new ObservationRequest(
                                                ObservationKind.DEFINITION,
                                                "12345678",
                                                "wowdbdefs",
                                                "Item",
                                                true),
                                        Cancellation.NONE)
                                .join());
        assertEquals(4, calls.get());
    }

    private static ListfileClient client(ListfilePart part, AtomicInteger calls) {
        return new ListfileClient() {
            @Override
            public CompletableFuture<ListfileReply> fetchRelease(
                    com.vibeminer.network.Cancellation token) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new ListfileReply(SourceUrls.LATEST_RELEASE, NOW, new byte[] {4}));
            }

            @Override
            public CompletableFuture<ListfilePart> fetchPart(
                    String tag, int index, com.vibeminer.network.Cancellation token) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(part);
            }
        };
    }

    @Test
    void releaseAndPartAdaptersPreserveCanonicalProvenance() {
        AtomicInteger calls = new AtomicInteger();
        URI asset = SourceUrls.asset("release-1");
        ObservationSource source =
                ObservationAdapters.listfile(
                        client(
                                new ListfilePart(
                                        asset,
                                        NOW,
                                        new byte[] {1, 2},
                                        SourceUrls.PART_BYTES,
                                        SourceUrls.PART_BYTES + 2L),
                                calls));
        var release =
                source.fetch(
                                new ObservationRequest(
                                        ObservationKind.LISTFILE_RELEASE, "wowlistfile", "latest"),
                                Cancellation.NONE)
                        .join();
        assertEquals(SourceUrls.LATEST_RELEASE, release.url());
        var part =
                source.fetch(
                                new ObservationRequest(
                                        ObservationKind.LISTFILE_PART, "release-1", "p1"),
                                Cancellation.NONE)
                        .join();
        assertEquals(asset, part.url());
        assertArrayEquals(new byte[] {1, 2}, part.bytes());
        assertEquals(2, calls.get());
    }

    @Test
    void partAdaptersRejectMismatchedAddressesOffsetsTotalsAndByteCounts() {
        URI asset = SourceUrls.asset("release-1");
        for (ListfilePart part :
                List.of(
                        new ListfilePart(
                                URI.create("https://objects.githubusercontent.com/signed?secret=x"),
                                NOW,
                                new byte[] {1},
                                0,
                                1),
                        new ListfilePart(asset, NOW, new byte[] {1}, 1, 2),
                        new ListfilePart(
                                asset, NOW, new byte[] {1}, 0, SourceUrls.MAX_ASSET_BYTES + 1),
                        new ListfilePart(asset, NOW, new byte[] {1}, 0, 0),
                        new ListfilePart(asset, NOW, new byte[] {1}, 0, 2))) {
            ObservationSource source =
                    ObservationAdapters.listfile(client(part, new AtomicInteger()));
            CdnException error =
                    (CdnException)
                            assertThrows(
                                            CompletionException.class,
                                            () ->
                                                    source.fetch(
                                                                    new ObservationRequest(
                                                                            ObservationKind
                                                                                    .LISTFILE_PART,
                                                                            "release-1",
                                                                            "p0"),
                                                                    Cancellation.NONE)
                                                            .join())
                                    .getCause();
            assertEquals(Failure.PROTOCOL, error.overall());
            assertEquals(asset, error.attempts().getFirst().url());
            assertTrue(!error.toString().contains("secret"));
        }
    }

    @Test
    void malformedPartRequestsNeverCallTheClient() {
        AtomicInteger calls = new AtomicInteger();
        ObservationSource source = ObservationAdapters.listfile(client(null, calls));
        for (String region : List.of("p64", "p01", "p-1", "p")) {
            assertThrows(
                    CompletionException.class,
                    () ->
                            source.fetch(
                                            new ObservationRequest(
                                                    ObservationKind.LISTFILE_PART,
                                                    "release-1",
                                                    region),
                                            Cancellation.NONE)
                                    .join());
        }
        assertThrows(
                CompletionException.class,
                () ->
                        source.fetch(
                                        new ObservationRequest(
                                                ObservationKind.LISTFILE_DELTA,
                                                "release-1",
                                                "delta"),
                                        Cancellation.NONE)
                                .join());
        assertEquals(0, calls.get());
    }

    @Test
    void cancellationIsForwardedAndNetworkFailuresAreNotReclassified() {
        Cancellation token = new Cancellation();
        ObservationSource source =
                ObservationAdapters.versions(
                        (p, r, k, t) -> {
                            token.cancel();
                            assertTrue(t.isCanceled());
                            return CompletableFuture.failedFuture(
                                    new java.util.concurrent.CancellationException());
                        });
        assertTrue(
                source.fetch(new ObservationRequest(ObservationKind.VERSIONS, "wow", "us"), token)
                        .handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                        .join());
    }
}
