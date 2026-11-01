package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.access.SourceTypes.ObjectState;
import com.vibeminer.access.SourceTypes.Observation;
import com.vibeminer.access.SourceTypes.ObservationKind;
import com.vibeminer.store.History;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** One behavior suite for the independent memory fake and durable SQLite catalog. */
@Timeout(30)
public abstract class CatalogContract {
    protected static final String A = "a".repeat(32),
            B = "b".repeat(32),
            C = "c".repeat(32),
            D = "d".repeat(32);
    protected static final String HEADER =
            "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0";
    protected static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    protected CatalogSource source;
    protected BuildCatalog catalog;

    protected abstract BuildCatalog create(CatalogSource source);

    protected abstract void closeCatalog();

    @BeforeEach
    final void setup() {
        source = new CatalogSource();
        catalog = create(source);
    }

    @AfterEach
    final void cleanup() {
        closeCatalog();
    }

    protected static String versions(String seq, String build, String cdn, String name) {
        return HEADER + "\n## seqn = " + seq + "\nus|" + build + "|" + cdn + "|" + name + "\n";
    }

    @Test
    void listingIsOfflineAndPreservesExactFieldsAcrossRegions() {
        String text = versions("1", A, B, "1.0.1") + "eu|" + A + "|" + B + "|1.0.1\n";
        History.Entry entry = source.append(1, "wow", "us", NOW, text);
        var list = catalog.listBuilds("wow").join();
        assertEquals(1, list.builds().size());
        var build = list.builds().getFirst();
        assertEquals("1.0.1", build.versionsName());
        assertEquals(A, build.buildConfig());
        assertEquals(List.of("eu", "us"), build.regions().stream().map(r -> r.region()).toList());
        assertTrue(build.current());
        assertEquals(entry.record(), build.regions().getFirst().lastRecord());
        assertEquals(NOW, list.lastObservedAt());
        assertTrue(source.asked().isEmpty());
        assertEquals(
                List.of("BuildConfig", "VersionsName"), new ArrayList<>(build.fields().keySet()));
    }

    @Test
    void republishedCdnConfigsDoNotChangeBuildIdentity() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "1"));
        source.append(1, "wow", "us", NOW.plusSeconds(60), versions("2", A, C, "1"));
        var build = catalog.listBuilds("wow").join().builds().getFirst();
        assertEquals(2, build.regions().size());
        assertEquals(1, build.regions().stream().filter(r -> r.current()).count());
        assertEquals(
                C,
                build.regions().stream()
                        .filter(r -> r.current())
                        .findFirst()
                        .orElseThrow()
                        .cdnConfig());
    }

    @Test
    void higherSequenceWinsOverLaterTimeAndHostsKeepIndependentLastWords() {
        source.append(1, "wow", "us", NOW, versions("100", A, B, "old"));
        source.append(1, "wow", "us", NOW.plusSeconds(60), versions("99", C, B, "new"));
        source.append(1, "wow", "eu", NOW.plusSeconds(90), versions("1", D, B, "eu"));
        var builds = catalog.listBuilds("wow").join().builds();
        assertEquals(3, builds.size());
        assertTrue(
                builds.stream()
                        .filter(b -> b.buildConfig().equals(A))
                        .findFirst()
                        .orElseThrow()
                        .current());
        assertFalse(
                builds.stream()
                        .filter(b -> b.buildConfig().equals(C))
                        .findFirst()
                        .orElseThrow()
                        .current());
        assertTrue(
                builds.stream()
                        .filter(b -> b.buildConfig().equals(D))
                        .findFirst()
                        .orElseThrow()
                        .current());
    }

    @Test
    void unknownSequenceNumbersSortBelowNumericSequences() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "numeric"));
        source.append(
                1,
                "wow",
                "us",
                NOW.plusSeconds(60),
                versions("9999999999999999999999999999", C, B, "too-big"));
        var builds = catalog.listBuilds("wow").join().builds();
        assertTrue(
                builds.stream()
                        .filter(b -> b.buildConfig().equals(A))
                        .findFirst()
                        .orElseThrow()
                        .current());
        assertFalse(
                builds.stream()
                        .filter(b -> b.buildConfig().equals(C))
                        .findFirst()
                        .orElseThrow()
                        .current());
    }

    @Test
    void malformedWholeReplyDoesNotEraseCurrentBuildButValidRowsSurvivePeerErrors() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "old"));
        source.append(1, "wow", "us", NOW.plusSeconds(60), "not a header");
        var listed = catalog.listBuilds("wow").join();
        assertTrue(listed.builds().getFirst().current());
        assertEquals("VERSIONS_UNREADABLE", listed.problems().getFirst().code());
        source.append(
                1,
                "wow",
                "us",
                NOW.plusSeconds(90),
                versions("2", C, B, "new") + "bad-region|" + A + "|" + B + "|bad\n");
        listed = catalog.listBuilds("wow").join();
        assertEquals(2, listed.builds().size());
        assertTrue(
                listed.problems().stream().anyMatch(p -> p.code().equals("VERSIONS_ROW_INVALID")));
    }

    @Test
    void duplicateRegionsAreAmbiguousAndEveryConflictingRowIsExcluded() {
        source.append(
                1,
                "wow",
                "us",
                NOW,
                versions("1", A, B, "one")
                        + "us|"
                        + C
                        + "|"
                        + B
                        + "|two\neu|"
                        + D
                        + "|"
                        + B
                        + "|three\n");
        var list = catalog.listBuilds("wow").join();
        assertEquals(1, list.builds().size());
        assertEquals(D, list.builds().getFirst().buildConfig());
        assertEquals("VERSIONS_REGION_AMBIGUOUS", list.problems().getFirst().code());
    }

    @Test
    void unavailableDocumentsAreReportedAndRetriedByKey() {
        String text = versions("1", A, B, "1");
        History.Entry entry = source.append(1, "wow", "us", NOW, text);
        source.lose(entry.document());
        var first = catalog.listBuilds("wow").join();
        assertTrue(first.builds().isEmpty());
        assertEquals("CATALOG_DOCUMENT_UNAVAILABLE", first.problems().getFirst().code());
        source.restore(entry.document(), text.getBytes(StandardCharsets.UTF_8));
        var second = catalog.listBuilds("wow").join();
        assertEquals(1, second.builds().size());
        assertTrue(second.problems().isEmpty());
    }

    @Test
    void laterHistoryWithinResumeLagIsPickedUpAndProductsStayIsolated() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "one"));
        catalog.listBuilds("wow").join();
        source.append(1, "wow", "us", NOW.minusSeconds(30), versions("2", C, B, "two"));
        source.append(1, "wowdev2", "us", NOW, versions("1", D, B, "dev"));
        assertEquals(2, catalog.listBuilds("wow").join().builds().size());
        assertEquals(1, catalog.listBuilds("wowdev2").join().builds().size());
        assertThrows(IllegalArgumentException.class, () -> catalog.listBuilds("agent"));
    }

    @Test
    void emptyProductConfigAndMissingProductConfigAreDistinctIdentities() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "same"));
        String withEmpty =
                (HEADER + "|ProductConfig!HEX:16\n## seqn = 2\nus|" + A + "|" + B + "|same|\n");
        source.append(1, "wow", "us", NOW.plusSeconds(1), withEmpty);
        var list = catalog.listBuilds("wow").join();
        assertEquals(2, list.builds().size());
        assertNotEquals(list.builds().get(0).id(), list.builds().get(1).id());
        assertTrue(list.builds().stream().allMatch(b -> b.productConfig() == null));
    }

    @Test
    void explicitRefreshObservesBothDocumentsAndFailedRefreshStillListsStoredHistory() {
        byte[] versions = versions("1", A, B, "fresh").getBytes(StandardCharsets.UTF_8);
        source.observation(
                new Observation(
                        ObservationKind.VERSIONS,
                        "wow",
                        "us",
                        URI.create("https://us.version.battle.net/v2/products/wow/versions"),
                        NOW,
                        versions));
        source.observation(
                new Observation(
                        ObservationKind.CDNS,
                        "wow",
                        "us",
                        URI.create("https://us.version.battle.net/v2/products/wow/cdns"),
                        NOW,
                        "Name!STRING:0\nus\n".getBytes(StandardCharsets.UTF_8)));
        var refresh = catalog.refresh("wow", Cancellation.NONE).join();
        assertEquals(ObjectState.PRESENT, refresh.versions().state());
        assertEquals(ObjectState.PRESENT, refresh.cdns().state());
        assertEquals(1, refresh.list().builds().size());
        assertEquals(2, source.asked().size());
    }

    @Test
    void summaryDiscoversUnreviewedWowProductsAndFlagsWithoutVersions() {
        byte[] bytes =
                "Product!STRING:0|Seqn!DEC:4|Flags!STRING:0\n## seqn = 22\nwow|1|\nwowdev10|2|cdn\nwowdev2|3|\nagent|4|\n"
                        .getBytes(StandardCharsets.UTF_8);
        source.observation(
                new Observation(
                        ObservationKind.SUMMARY,
                        "",
                        "us",
                        URI.create("https://us.version.battle.net/v2/summary"),
                        NOW,
                        bytes));
        var summary = catalog.refreshSummary(Cancellation.NONE).join();
        assertEquals(List.of("wowdev10", "wowdev2"), summary.newProducts());
        assertEquals(List.of("wowdev10"), summary.withoutVersions());
        assertEquals("22", summary.sequence());
        assertEquals(4, summary.listed().size());
    }

    @Test
    void cancellationAndConcurrentListsDoNotPoisonTheCatalog() {
        source.append(1, "wow", "us", NOW, versions("1", A, B, "1"));
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        assertTrue(catalog.listBuilds("wow", canceled).isCompletedExceptionally());
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            calls.add(
                    catalog.listBuilds("wow")
                            .thenAccept(list -> assertEquals(1, list.builds().size())));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
    }
}
