package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.files.FileTypes;
import com.vibeminer.files.keys.TactKeys;
import com.vibeminer.network.*;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real SQLite/pack/BLTE local composition; every network capability fails the test if invoked. */
public final class NativeComparisonTest {
    private NativeComparisonTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-native-comparison-");
        try {
            var fixture = FullPreparationLibraryIntegrationTest.seed(directory);
            var library = ObjectStore.openLibrary(directory.resolve("library")).join();
            try (var catalog = LibraryCatalog.open(library.databasePath()).join();
                    var registry = BuildRegistry.open(library.databasePath()).join()) {
                var forbidden = new AtomicInteger();
                CdnClient cdn =
                        new CdnClient() {
                            public CompletableFuture<NetworkTypes.Reply> fetch(
                                    CdnRoute route,
                                    NetworkTypes.Request request,
                                    com.vibeminer.network.Cancellation token) {
                                forbidden.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new AssertionError("Forbidden CDN access"));
                            }

                            public CompletableFuture<NetworkTypes.Reply> fetchTo(
                                    CdnRoute route,
                                    NetworkTypes.Request request,
                                    NetworkTypes.Sink sink,
                                    com.vibeminer.network.Cancellation token) {
                                forbidden.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new AssertionError("Forbidden CDN access"));
                            }
                        };
                ListfileClient names =
                        new ListfileClient() {
                            public CompletableFuture<NetworkTypes.ListfileReply> fetchRelease(
                                    com.vibeminer.network.Cancellation token) {
                                forbidden.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new AssertionError("Forbidden name refresh"));
                            }

                            public CompletableFuture<NetworkTypes.ListfilePart> fetchPart(
                                    String tag,
                                    int index,
                                    com.vibeminer.network.Cancellation token) {
                                forbidden.incrementAndGet();
                                return CompletableFuture.failedFuture(
                                        new AssertionError("Forbidden name refresh"));
                            }
                        };
                var history = PreparationHistory.forLibrary(library.databasePath());
                history.load();
                var contexts =
                        new NativePreparationContexts(
                                library,
                                catalog,
                                registry,
                                cdn,
                                names,
                                null,
                                history,
                                null,
                                directory.resolve("grids"),
                                ignored -> {});
                try {
                    String cdnConfig = new SyntheticBuild().ref.cdnConfig();
                    var source = new BuildInfo.RegionEvidence("us", cdnConfig, true, null, null);
                    var info =
                            new BuildInfo(
                                    "wow",
                                    "Retail",
                                    "1.2.3.12345",
                                    "us",
                                    fixture.buildConfig().hex(),
                                    BuildInfo.BuildSource.CURRENT,
                                    true,
                                    null,
                                    0,
                                    "wow:" + fixture.buildConfig().hex(),
                                    null,
                                    null,
                                    List.of(source));
                    var browsing =
                            contexts.create(info, source, "enUS").withSourcePolicy(false, true);
                    try {
                        browsing.metadata(new Cancellation());
                        browsing.files(ignored -> {}, new Cancellation());
                        browsing.keys(new Cancellation());
                        var prepared = browsing.tables(ignored -> {}, new Cancellation());
                        require(
                                prepared.outcome()
                                        == com.vibeminer.formats.tables.TableTypes.PreloadOutcome
                                                .COMPLETED,
                                "Known table outcomes prepared from authored cached bytes");
                        history.record(
                                info,
                                new com.vibeminer.builds.BuildTypes.BuildRef(
                                        info.product(), info.config(), cdnConfig, "us"),
                                "enUS",
                                BuildPreparation.Ready.METADATA_READY);
                        var previous =
                                registry.get(info.identity(), com.vibeminer.store.Cancellation.NONE)
                                        .join();
                        var manager =
                                new ComparisonCoordinator(
                                        new LocalComparisonFactory(contexts, null, directory.resolve("work")),
                                        directory.resolve("work"));
                        try {
                            for (String area : List.of("Files", "Tables")) {
                                var session =
                                        manager.start(
                                                        new BuildComparisons.Request(
                                                                info, "enUS", info, "enUS", area),
                                                        new Cancellation())
                                                .get(20, TimeUnit.SECONDS);
                                terminal(session);
                                require(
                                        session.progress().state()
                                                == ComparisonTypes.RunState.COMPLETED,
                                        "Local inventory completes: "
                                                + session.progress().message());
                                var findings =
                                        session.page(0, 100).get(10, TimeUnit.SECONDS).rows();
                                if (area.equals("Files")) {
                                    require(
                                            findings.isEmpty(),
                                            "Known equal file facts yield no findings");
                                } else {
                                    require(
                                            findings.stream()
                                                    .allMatch(
                                                            row ->
                                                                    row.membership()
                                                                                    == ComparisonTypes
                                                                                            .Membership
                                                                                            .BOTH
                                                                            && row.fields().stream()
                                                                                    .noneMatch(
                                                                                            field ->
                                                                                                    field
                                                                                                                    .difference()
                                                                                                            == ComparisonTypes
                                                                                                                    .Difference
                                                                                                                    .CHANGED)),
                                            "Unreadable equal table receipts retain uncertainty"
                                                + " without fabricating changes");
                                    require(
                                            session.progress().observed()
                                                    == prepared.tables().size(),
                                            "The exact known-definition receipt is fully"
                                                + " enumerated");
                                }
                                require(
                                        area.equals("Files")
                                                ? session.progress().a().completeness()
                                                        == ComparisonTypes.Completeness.UNKNOWN
                                                : session.progress().a().completeness()
                                                        == ComparisonTypes.Completeness.COMPLETE,
                                        "Membership scope remains explicit for " + area);
                                session.closeAsync().get(20, TimeUnit.SECONDS);
                            }
                            require(
                                    previous.equals(
                                            registry.get(
                                                            info.identity(),
                                                            com.vibeminer.store.Cancellation.NONE)
                                                    .join()),
                                    "Comparison opening does not overwrite the browsed build's load"
                                            + " status");
                            var missing =
                                    new BuildInfo(
                                            "wow",
                                            "Retail",
                                            "1.2.3.54321",
                                            "us",
                                            "f".repeat(32),
                                            BuildInfo.BuildSource.CURRENT,
                                            false,
                                            null,
                                            0,
                                            "wow:" + "f".repeat(32),
                                            null,
                                            null,
                                            List.of(source));
                            expectFailure(
                                    manager.start(
                                            new BuildComparisons.Request(
                                                    missing, "enUS", info, "enUS", "Files"),
                                            new Cancellation()));
                            require(
                                    registry.get(
                                                            missing.identity(),
                                                            com.vibeminer.store.Cancellation.NONE)
                                                    .join()
                                            == null,
                                    "Unavailable local metadata does not persist a global failed"
                                            + " load");
                            var differentKeys = new TactKeys(Map.of(77L, new byte[16]));
                            var uncached =
                                    contexts.createLocalComparison(
                                            info, source, "enUS", true, differentKeys);
                            try {
                                uncached.metadata(new Cancellation());
                                uncached.files(ignored -> {}, new Cancellation());
                                uncached.keys(new Cancellation());
                                require(
                                        uncached.cachedTableInventory(new Cancellation()) == null,
                                        "Mismatched key policy never reuses or prepares a table"
                                                + " receipt");
                            } finally {
                                uncached.close();
                            }
                            try (var cursor =
                                    browsing.filesHandle()
                                            .listMetadata(
                                                    FileTypes.FileFilter.ALL, new Cancellation())) {
                                require(
                                        cursor.next(10, new Cancellation()).join().size() == 2,
                                        "The separately browsed file context stays usable");
                            }
                            require(
                                    forbidden.get() == 0,
                                    "No CDN, listfile or source-refresh request is permitted,"
                                            + " including missing metadata");
                        } finally {
                            manager.closeAsync().get(20, TimeUnit.SECONDS);
                        }
                    } finally {
                        browsing.close();
                    }
                } finally {
                    contexts.closeAsync().get(20, TimeUnit.SECONDS);
                }
            } finally {
                library.closeAsync().join();
            }
            System.out.println(
                    "Native local comparison: exact cached receipts, forbidden acquisition, scoped"
                        + " coverage, failed opening isolation and independent workspace lifetime"
                        + " passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(CompletableFuture<?> value) throws Exception {
        try {
            value.get(20, TimeUnit.SECONDS);
            throw new AssertionError("Expected unavailable local data");
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.CancellationException expected) {
        }
    }

    private static void terminal(BuildComparisons.Session session) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (session.progress().state() == ComparisonTypes.RunState.RUNNING) {
            if (System.nanoTime() > end) throw new AssertionError("Comparison did not complete");
            Thread.sleep(10);
        }
    }
}
