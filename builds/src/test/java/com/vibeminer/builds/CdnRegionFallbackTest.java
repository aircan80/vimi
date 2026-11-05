package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.ObjectSource;
import com.vibeminer.access.SourceTypes.CdnContext;
import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.ObjectProgress;
import com.vibeminer.access.SourceTypes.ObjectRequest;
import com.vibeminer.access.SourceTypes.ObjectResult;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationRequest;
import com.vibeminer.access.SourceTypes.ObservationResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.network.CdnRoute;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectKey;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** CN's only advertised host is not an official CDN host; routing must not accept it. */
@Timeout(20)
final class CdnRegionFallbackTest {
    private static final String CN_HOST = "blzdist-wow.necdn.leihuo.netease.com";
    private static final String OTHER_CDN_CONFIG = "0123456789abcdef0123456789abcdef";

    private static String cdns() {
        return "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n## seqn = 7\n"
                + "cn|tpr/wow|"
                + CN_HOST
                + "\neu|tpr/wow|eu.cdn.blizzard.com\nus|tpr/wow|us.cdn.blizzard.com"
                + " level3.blizzard.com\n";
    }

    private static String versions(String buildConfig, String cnConfig, String othersConfig) {
        return "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                + "## seqn = 9\n"
                + "cn|"
                + buildConfig
                + "|"
                + cnConfig
                + "|1.0.0.1\neu|"
                + buildConfig
                + "|"
                + othersConfig
                + "|1.0.0.1\nus|"
                + buildConfig
                + "|"
                + othersConfig
                + "|1.0.0.1\n";
    }

    private static List<VersionsTable> tables(String text) {
        return List.of(VersionsTable.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void rejectedHostStaysRejected() {
        assertFalse(CdnRoute.isOfficialHost(CN_HOST));
        assertNull(CdnsRoutes.route(tables(cdns()), "cn"));
        assertEquals(Set.of("eu", "us"), CdnsRoutes.routedRegions(tables(cdns())));
    }

    @Test
    void cnFallsBackToUsWhenUsServesTheSameCdnConfig() {
        var resolution = CdnsRoutes.resolve(tables(cdns()), "cn", Set.of("cn", "eu", "us"));
        assertTrue(resolution.fallback());
        assertEquals("us", resolution.served());
        assertEquals(
                List.of("us.cdn.blizzard.com", "level3.blizzard.com"), resolution.route().hosts());
        assertFalse(resolution.route().hosts().contains(CN_HOST));
        assertTrue(
                resolution.note().contains("Region CN has no accepted CDN host"),
                resolution.note());
        assertTrue(resolution.note().contains("US"), resolution.note());
    }

    @Test
    void fallbackIsRefusedWhenNoOtherRegionServesTheSameCdnConfig() {
        var versions =
                tables(
                        versions(
                                "11111111111111111111111111111111",
                                "aa".repeat(16),
                                "bb".repeat(16)));
        Set<String> serving =
                CdnsRoutes.regionsServing(
                        versions, "11111111111111111111111111111111", "aa".repeat(16));
        assertEquals(Set.of("cn"), serving);
        var resolution = CdnsRoutes.resolve(tables(cdns()), "cn", serving);
        assertNull(resolution.route());
        assertFalse(resolution.fallback());
        assertTrue(
                resolution.note().startsWith("No accepted CDN host for region CN"),
                resolution.note());
    }

    @Test
    void nothingQualifiesNamesTheCause() {
        var resolution = CdnsRoutes.resolve(tables(cdns()), "cn", Set.of());
        assertNull(resolution.route());
        assertTrue(
                resolution.note().startsWith("No accepted CDN host for region CN"),
                resolution.note());
        var direct = CdnsRoutes.resolve(tables(cdns()), "us", Set.of());
        assertFalse(direct.fallback());
        assertNull(direct.note());
    }

    @Test
    void savedCnBuildOpensWithAnAcceptedCdnRouteServingItsConfig() {
        var routed = new RoutedSource();
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH,
                versions(
                        routed.build.ref.buildConfig(),
                        routed.build.ref.cdnConfig(),
                        routed.build.ref.cdnConfig()));
        // A registry entry written before the fix records the first observed region, cn.
        var saved =
                new BuildRef(
                        "wow", routed.build.ref.buildConfig(), routed.build.ref.cdnConfig(), "cn");
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(saved).join();
            assertEquals(BuildOpenState.OPEN, result.state(), result.message());
            CdnContext cdn = result.opened().cdn();
            assertNotNull(cdn);
            assertEquals(
                    List.of("us.cdn.blizzard.com", "level3.blizzard.com"), cdn.route().hosts());
            assertEquals("cn", result.opened().build().region());
            assertTrue(result.opened().cdnNote().contains("US"), result.opened().cdnNote());
            assertTrue(
                    routed.routes.stream()
                            .allMatch(r -> r.hosts().contains("us.cdn.blizzard.com")));
            assertFalse(routed.routes.isEmpty());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void cnBuildWithADifferentConfigElsewhereFailsWithTheRealCause() {
        var routed = new RoutedSource();
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH,
                versions(
                        routed.build.ref.buildConfig(),
                        routed.build.ref.cdnConfig(),
                        OTHER_CDN_CONFIG));
        var saved =
                new BuildRef(
                        "wow", routed.build.ref.buildConfig(), routed.build.ref.cdnConfig(), "cn");
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(saved).join();
            assertEquals(BuildOpenState.UNAVAILABLE, result.state());
            assertEquals("BUILD_NO_ACCEPTED_CDN_HOST", result.code());
            assertTrue(
                    result.message().startsWith("No accepted CDN host for region CN"),
                    result.message());
            assertTrue(
                    routed.routes.isEmpty(),
                    "No request may borrow a route serving another config");
        } finally {
            opener.closeAsync().join();
        }
    }

    private static String versionsRows(String... rows) {
        return "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                + String.join("\n", rows)
                + "\n";
    }

    private static BuildRef savedCn(RoutedSource routed) {
        return new BuildRef(
                "wow", routed.build.ref.buildConfig(), routed.build.ref.cdnConfig(), "cn");
    }

    @Test
    void sameConfigUsRowOlderThanThirtyTwoNewerRepliesStillBorrows() {
        var routed = new RoutedSource();
        String build = routed.build.ref.buildConfig(), cdn = routed.build.ref.cdnConfig();
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH,
                versionsRows(
                        "cn|" + build + "|" + cdn + "|1.0.0.1",
                        "us|" + build + "|" + cdn + "|1.0.0.1"));
        for (int i = 1; i <= CdnsRoutes.MAX_REPLIES + 8; i++) {
            String newer = String.format("%032x", i);
            routed.history.append(
                    1,
                    "wow",
                    "us",
                    Instant.EPOCH.plusSeconds(i),
                    "## seqn = "
                            + i
                            + "\n"
                            + versionsRows(
                                    "cn|" + newer + "|" + newer + "|1.0.0." + i,
                                    "us|" + newer + "|" + newer + "|1.0.0." + i));
        }
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(savedCn(routed)).join();
            assertEquals(BuildOpenState.OPEN, result.state(), result.message());
            assertEquals(
                    List.of("us.cdn.blizzard.com", "level3.blizzard.com"),
                    result.opened().cdn().route().hosts());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void newestUsRowDiffersButAnOlderUsRowMatchesSoTheRouteIsBorrowed() {
        var routed = new RoutedSource();
        String build = routed.build.ref.buildConfig(), cdn = routed.build.ref.cdnConfig();
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH,
                versionsRows(
                        "cn|" + build + "|" + cdn + "|1.0.0.1",
                        "us|" + build + "|" + cdn + "|1.0.0.1"));
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH.plusSeconds(100),
                versionsRows(
                        "cn|" + build + "|" + cdn + "|1.0.0.1",
                        "us|" + build + "|" + OTHER_CDN_CONFIG + "|1.0.0.1"));
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(savedCn(routed)).join();
            assertEquals(BuildOpenState.OPEN, result.state(), result.message());
            assertTrue(result.opened().cdnNote().contains("region US"), result.opened().cdnNote());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void sameCdnConfigUnderAnotherBuildConfigIsNotBorrowed() {
        var routed = new RoutedSource();
        String build = routed.build.ref.buildConfig(), cdn = routed.build.ref.cdnConfig();
        routed.history.append(
                1,
                "wow",
                "us",
                Instant.EPOCH,
                versionsRows(
                        "cn|" + build + "|" + cdn + "|1.0.0.1",
                        "us|" + "22".repeat(16) + "|" + cdn + "|1.0.0.2"));
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(savedCn(routed)).join();
            assertEquals(BuildOpenState.UNAVAILABLE, result.state());
            assertEquals(CdnsRoutes.NO_ACCEPTED_HOST, result.code());
            assertTrue(result.message().contains("Choose another region"), result.message());
            assertTrue(routed.routes.isEmpty());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void noStoredHostListHasItsOwnCode() {
        var routed = new RoutedSource(false);
        BuildOpener opener = new BuildOpener(routed);
        try {
            var result = opener.open(savedCn(routed)).join();
            assertEquals(BuildOpenState.UNAVAILABLE, result.state());
            assertEquals(CdnsRoutes.NO_HOST_LIST, result.code());
            assertTrue(result.message().contains("refresh the catalog"), result.message());
        } finally {
            opener.closeAsync().join();
        }
    }

    @Test
    void downloadAllReportsTheSameCodeAndCause() {
        var routed = new RoutedSource();
        routed.gated = false;
        BuildOpener opener = new BuildOpener(routed);
        var jobs =
                new DownloadAllJobs(
                        routed,
                        t -> CompletableFuture.completedFuture(1L << 30),
                        new DownloadAllJobs.Options(2, 16, 2, 10, java.time.Duration.ZERO));
        try {
            var opened = opener.open(savedCn(routed)).join();
            assertEquals(BuildOpenState.OPEN, opened.state(), opened.message());
            assertNull(opened.opened().cdn());
            assertEquals(CdnsRoutes.NO_ACCEPTED_HOST, opened.opened().cdnProblem());
            var result = jobs.start(opened.opened(), null).completion().join();
            assertEquals(CdnsRoutes.NO_ACCEPTED_HOST, result.code());
            assertTrue(
                    result.message().startsWith("No accepted CDN host for region CN"),
                    result.message());
        } finally {
            jobs.closeAsync().join();
            opener.closeAsync().join();
        }
    }

    /** Stored history plus a synthetic build whose ENCODING is only reachable through a CDN. */
    private static final class RoutedSource implements ObjectSource {
        final SyntheticBuild build = new SyntheticBuild();
        final CatalogSource history = new CatalogSource();
        final List<CdnRoute> routes = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean gated = true;

        RoutedSource() {
            this(true);
        }

        RoutedSource(boolean hostList) {
            if (hostList) {
                history.append(2, "wow", "us", Instant.EPOCH, cdns());
            }
        }

        private ObjectResult gate(ObjectRequest request, CdnContext cdn) {
            if (!gated || !request.key().equals(build.encodingKey)) {
                return null;
            }
            if (cdn == null) {
                return new ObjectResult(
                        request.key(),
                        ObjectState.UNAVAILABLE,
                        null,
                        null,
                        null,
                        null,
                        "OBJECT_ACCESS_NO_CDN_CONTEXT",
                        "No CDN context was supplied",
                        null);
            }
            routes.add(cdn.route());
            return null;
        }

        @Override
        public CompletableFuture<ObjectResult> getKey(
                ObjectRequest request, CdnContext cdn, FetchPriority priority, Cancellation token) {
            if (request.key().type() == ObjectKey.HISTORY_DOCUMENT) {
                return history.getKey(request, cdn, priority, token);
            }
            ObjectResult refused = gate(request, cdn);
            return refused != null
                    ? CompletableFuture.completedFuture(refused)
                    : build.getKey(request, cdn, priority, token);
        }

        @Override
        public CompletableFuture<List<ObjectResult>> getKeys(
                List<ObjectRequest> requests,
                CdnContext cdn,
                FetchPriority priority,
                Consumer<ObjectProgress> progress,
                Cancellation token) {
            return build.getKeys(requests, cdn, priority, progress, token);
        }

        @Override
        public CompletableFuture<ObjectResult> getRange(
                ObjectRequest request,
                long offset,
                int length,
                CdnContext cdn,
                FetchPriority priority,
                Cancellation token) {
            ObjectResult refused = gate(request, cdn);
            return refused != null
                    ? CompletableFuture.completedFuture(refused)
                    : build.getRange(request, offset, length, cdn, priority, token);
        }

        @Override
        public CompletableFuture<ObservationResult> observe(
                ObservationRequest request, Cancellation token) {
            return build.observe(request, token);
        }

        @Override
        public CompletableFuture<ObservationResult> record(
                Observation observation, Cancellation token) {
            return build.record(observation, token);
        }

        @Override
        public CompletableFuture<Long> countPresent(List<ObjectKey> keys, Cancellation token) {
            return build.countPresent(keys, token);
        }

        @Override
        public CompletableFuture<List<Boolean>> hasKeys(List<ObjectKey> keys, Cancellation token) {
            return build.hasKeys(keys, token);
        }

        @Override
        public CompletableFuture<List<History.Entry>> readHistory(
                History.Query query, Cancellation token) {
            return history.readHistory(query, token);
        }
    }
}
