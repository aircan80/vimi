package com.vimi.gui;

import com.vibeminer.builds.Build;
import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.BuildTypes;
import com.vibeminer.network.CdnKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Native registry/presentation contracts with synthetic metadata and no table/item work. */
public final class BuildLoadStateTest {
    private static final BuildInfo.RegionEvidence US =
            new BuildInfo.RegionEvidence("us", "b".repeat(32), true, Instant.EPOCH, Instant.EPOCH);
    private static final BuildInfo INFO =
            new BuildInfo(
                    "wow",
                    "Retail",
                    "1.2.3",
                    "us",
                    "a".repeat(32),
                    BuildInfo.BuildSource.CURRENT,
                    false,
                    null,
                    0,
                    "test-canonical-build",
                    Instant.EPOCH,
                    Instant.EPOCH,
                    List.of(US));

    private BuildLoadStateTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-build-load-state-");
        try {
            loadedStatusPersistsWithoutWaitingForAccounting(directory);
            workspaceChangeQueuesOneFreshAccountingQuery(directory.resolve("refresh"));
            failuresRemainDistinctFromLoaded(directory.resolve("failure"));
            duplicateCanonicalIndexReleasesOnlyDuplicateContext(directory.resolve("duplicate"));
            openedContextsReleaseOnCancellationOrReportingFailure(directory.resolve("release"));
            recordedRegionHasAnAcceptedRouteNotTheFirstInSortOrder(directory.resolve("region"));
            System.out.println(
                    "BuildLoadState: persisted status, async accounting and metadata-only load"
                            + " passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void workspaceChangeQueuesOneFreshAccountingQuery(Path directory)
            throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        BuildRegistry registry = BuildRegistry.open(library.databasePath()).join();
        var first = new CompletableFuture<LibraryCatalog.Figures>();
        var started = new java.util.concurrent.CountDownLatch(1);
        var repeated = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var state =
                new BuildLoadState(
                        registry,
                        (info, build) -> CompletableFuture.completedFuture(null),
                        identity -> {
                            if (calls.incrementAndGet() == 1) {
                                started.countDown();
                                return first;
                            }
                            repeated.countDown();
                            return CompletableFuture.completedFuture(figures());
                        });
        try {
            var ref =
                    new BuildTypes.BuildRef(
                            INFO.product(), INFO.config(), US.cdnConfig(), US.region());
            var attempt =
                    registry.beginLoad(
                                    INFO.identity(), ref, INFO.version(), "enUS", Cancellation.NONE)
                            .join();
            registry.loaded(attempt, Cancellation.NONE).join();
            state.refresh(INFO);
            require(started.await(5, TimeUnit.SECONDS), "First accounting query starts");
            state.invalidate(INFO);
            state.invalidate(INFO);
            state.invalidate(INFO);
            first.complete(figures());
            require(
                    repeated.await(5, TimeUnit.SECONDS),
                    "Workspace change is measured after stale query completes");
            state.closeAsync().get(5, TimeUnit.SECONDS);
            require(calls.get() == 2, "Repeated changes coalesce into one final refresh");
        } finally {
            first.complete(figures());
            state.closeAsync().join();
            registry.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private static void recordedRegionHasAnAcceptedRouteNotTheFirstInSortOrder(Path directory)
            throws Exception {
        String config = "b".repeat(32);
        var cn = new BuildInfo.RegionEvidence("cn", config, true, Instant.EPOCH, Instant.EPOCH);
        var eu = new BuildInfo.RegionEvidence("eu", config, true, Instant.EPOCH, Instant.EPOCH);
        var us = new BuildInfo.RegionEvidence("us", config, true, Instant.EPOCH, Instant.EPOCH);
        var info =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "1.2.3",
                        "cn, eu, us",
                        "a".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0,
                        "test-region-build",
                        Instant.EPOCH,
                        Instant.EPOCH,
                        List.of(cn, eu, us));
        require(
                BuildLoadState.recorded(info, null, null).equals(us),
                "Without route facts the record prefers US over the first region in sort order");
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        BuildRegistry registry = BuildRegistry.open(library.databasePath()).join();
        var state =
                new BuildLoadState(
                                registry,
                                (build, opened) -> CompletableFuture.completedFuture(null),
                                identity -> CompletableFuture.completedFuture(figures()))
                        .withRoutedRegions(product -> java.util.Set.of("eu", "us"));
        var opening =
                new BuildOpening(
                        (build, ref, token) -> CompletableFuture.completedFuture(success(ref)),
                        state);
        try {
            // Builds saved before the fix recorded the first observation, cn.
            registry.observe(
                            info.identity(),
                            new BuildTypes.BuildRef("wow", info.config(), config, "cn"),
                            info.version(),
                            "enUS",
                            Cancellation.NONE)
                    .join();
            state.observe(List.of(info));
            require(
                    registry.get(info.identity(), Cancellation.NONE)
                            .join()
                            .reference()
                            .region()
                            .equals("us"),
                    "A recorded region without an accepted route is replaced by US");
            opening.open(info, eu).get(5, TimeUnit.SECONDS);
            state.observe(List.of(info));
            require(
                    registry.get(info.identity(), Cancellation.NONE)
                            .join()
                            .reference()
                            .region()
                            .equals("eu"),
                    "The user's chosen routed region is kept");
            require(
                    BuildLoadState.recorded(
                                    info,
                                    registry.get(info.identity(), Cancellation.NONE).join(),
                                    java.util.Set.of("us"))
                            .equals(us),
                    "A chosen region that loses its route falls back to a routed one");
        } finally {
            opening.closeAsync().join();
            state.closeAsync().join();
            registry.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private static void loadedStatusPersistsWithoutWaitingForAccounting(Path directory)
            throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        BuildRegistry registry = BuildRegistry.open(library.databasePath()).join();
        var inventory = new CompletableFuture<Void>();
        var state =
                new BuildLoadState(
                        registry,
                        (info, build) -> inventory,
                        identity -> CompletableFuture.completedFuture(figures()));
        var opening =
                new BuildOpening(
                        (info, ref, token) -> CompletableFuture.completedFuture(success(ref)),
                        state);
        try {
            state.observe(List.of(INFO));
            require(
                    state.snapshot(INFO).status().equals("Not loaded"),
                    "New status comes from DISCOVERED table row");
            var result = opening.open(INFO, US).get(5, TimeUnit.SECONDS);
            require(
                    result.state() == BuildTypes.BuildOpenState.OPEN,
                    "Metadata opens independently");
            require(
                    !inventory.isDone(),
                    "Metadata load does not await expensive accounting inventory");
            require(
                    registry.get(INFO.identity(), Cancellation.NONE).join().status()
                            == BuildRegistry.Status.LOADED,
                    "Successful metadata load commits LOADED status");
            require(state.snapshot(INFO).measuring(), "In-flight inventory is explicit");
            inventory.complete(null);
            var snapshot = state.refresh(INFO).get(5, TimeUnit.SECONDS);
            require(
                    snapshot.usage().original().storedBytes() == 3,
                    "Read actual asynchronous accounting snapshot");
            require(
                    snapshot.status().equals("Loaded"),
                    "Status stays separate from download accounting");
        } finally {
            inventory.complete(null);
            opening.closeAsync().join();
            state.closeAsync().join();
            registry.closeAsync().join();
        }
        registry = BuildRegistry.open(library.databasePath()).join();
        try {
            var restored =
                    new BuildLoadState(
                            registry,
                            (info, build) -> CompletableFuture.completedFuture(null),
                            identity -> CompletableFuture.completedFuture(figures()));
            restored.observe(List.of(INFO));
            require(
                    restored.snapshot(INFO).status().equals("Loaded"),
                    "Reopen reads stored status without opening metadata");
            restored.closeAsync().join();
        } finally {
            registry.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private static void failuresRemainDistinctFromLoaded(Path directory) throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        var registry = BuildRegistry.open(library.databasePath()).join();
        var state =
                new BuildLoadState(
                        registry,
                        (info, build) -> {
                            throw new AssertionError(
                                    "Failed load must not prepare any inventory or domain data");
                        },
                        identity -> CompletableFuture.completedFuture(figures()));
        var opening =
                new BuildOpening(
                        (info, ref, token) ->
                                CompletableFuture.completedFuture(
                                        new BuildTypes.BuildOpenResult(
                                                ref,
                                                BuildTypes.BuildOpenState.MISSING,
                                                null,
                                                BuildTypes.BuildPart.ROOT,
                                                "ROOT_ABSENT",
                                                "Root unavailable",
                                                Instant.EPOCH)),
                        state);
        try {
            state.observe(List.of(INFO));
            opening.open(INFO, US).get(5, TimeUnit.SECONDS);
            var row = registry.get(INFO.identity(), Cancellation.NONE).join();
            require(
                    row.status() == BuildRegistry.Status.FAILED
                            && row.errorCode().equals("ROOT_ABSENT"),
                    "Typed load failure persists without claiming loaded or zero-sized");
            require(state.snapshot(INFO).usage() == null, "Unknown size is not fabricated zero");
        } finally {
            opening.closeAsync().join();
            state.closeAsync().join();
            registry.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private static void duplicateCanonicalIndexReleasesOnlyDuplicateContext(Path directory)
            throws Exception {
        var library = ObjectStore.openLibrary(directory.resolve("library")).join();
        var registry = BuildRegistry.open(library.databasePath()).join();
        var inventory = new CompletableFuture<Void>();
        var releaseDone = new CompletableFuture<Void>();
        var released = new java.util.concurrent.atomic.AtomicReference<Build>();
        var primary = new java.util.concurrent.atomic.AtomicReference<Build>();
        var primaryReleaseCalls = new java.util.concurrent.atomic.AtomicInteger();
        var duplicateReleaseCalls = new java.util.concurrent.atomic.AtomicInteger();
        var releaseStarted = new java.util.concurrent.CountDownLatch(1);
        var indexCalls = new java.util.concurrent.atomic.AtomicInteger();
        var state =
                new BuildLoadState(
                        registry,
                        (info, build) -> {
                            indexCalls.incrementAndGet();
                            return inventory;
                        },
                        identity -> CompletableFuture.completedFuture(figures()),
                        build -> {
                            if (build == primary.get()) {
                                primaryReleaseCalls.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            }
                            duplicateReleaseCalls.incrementAndGet();
                            released.set(build);
                            releaseStarted.countDown();
                            return releaseDone;
                        });
        var opening =
                new BuildOpening(
                        (info, ref, token) -> CompletableFuture.completedFuture(success(ref)),
                        state);
        try {
            state.observe(List.of(INFO));
            var first = opening.open(INFO, US).get(5, TimeUnit.SECONDS);
            primary.set(first.opened());
            var duplicate = opening.open(INFO, US).get(5, TimeUnit.SECONDS);
            require(
                    first.opened() != duplicate.opened(),
                    "Separate loads own separate metadata contexts");
            require(indexCalls.get() == 1, "Canonical owner inventory imports coalesce");
            require(
                    released.get() == null,
                    "Duplicate release waits for the existing index consumer");
            inventory.complete(null);
            require(
                    releaseStarted.await(5, TimeUnit.SECONDS),
                    "Duplicate context is eventually released");
            require(
                    released.get() == duplicate.opened(),
                    "Duplicate cleanup targets the duplicate model rather than the primary");
            var closing = state.closeAsync();
            require(!closing.isDone(), "State drain includes duplicate context release");
            releaseDone.complete(null);
            closing.get(5, TimeUnit.SECONDS);
            require(
                    primaryReleaseCalls.get() == 1 && duplicateReleaseCalls.get() == 1,
                    "Primary and duplicate contexts each release exactly once");
        } finally {
            inventory.complete(null);
            releaseDone.complete(null);
            opening.closeAsync().join();
            state.closeAsync().join();
            registry.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private static void openedContextsReleaseOnCancellationOrReportingFailure(Path directory)
            throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var library = ObjectStore.openLibrary(directory.resolve("library-" + mode)).join();
            var registry = BuildRegistry.open(library.databasePath()).join();
            var released = new java.util.concurrent.atomic.AtomicReference<Build>();
            var releases = new java.util.concurrent.atomic.AtomicInteger();
            var indexes = new java.util.concurrent.atomic.AtomicInteger();
            var model = new java.util.concurrent.atomic.AtomicReference<Build>();
            var state =
                    new BuildLoadState(
                            registry,
                            (info, build) -> {
                                indexes.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            },
                            identity -> CompletableFuture.completedFuture(figures()),
                            build -> {
                                released.set(build);
                                releases.incrementAndGet();
                                return CompletableFuture.completedFuture(null);
                            });
            int scenario = mode;
            var opening =
                    new BuildOpening(
                            (info, ref, token) -> {
                                var result = success(ref);
                                model.set(result.opened());
                                if (scenario == 0) token.cancel();
                                if (scenario == 1) registry.closeAsync().join();
                                if (scenario == 2) state.closeAsync().join();
                                return CompletableFuture.completedFuture(result);
                            },
                            state);
            try {
                state.observe(List.of(INFO));
                try {
                    opening.open(INFO, US).get(5, TimeUnit.SECONDS);
                    require(
                            scenario == 2,
                            "Canceled/failed status publication must not report load success");
                } catch (java.util.concurrent.ExecutionException
                        | java.util.concurrent.CancellationException expected) {
                    require(
                            scenario != 2,
                            "Closing index state should safely release a successfully loaded"
                                    + " model");
                }
                require(
                        released.get() == model.get() && releases.get() == 1,
                        "Opened context releases exactly once after cancellation/reporting"
                                + " failure/closing index");
                require(
                        indexes.get() == 0,
                        "Failed or closing load never transfers metadata to an inventory consumer");
                if (scenario == 0)
                    require(
                            registry.get(INFO.identity(), Cancellation.NONE).join().status()
                                    == BuildRegistry.Status.INTERRUPTED,
                            "Cancellation persists INTERRUPTED rather than LOADED");
            } finally {
                opening.closeAsync().join();
                state.closeAsync().join();
                registry.closeAsync().join();
                library.closeAsync().join();
            }
        }
    }

    private static BuildTypes.BuildOpenResult success(BuildTypes.BuildRef ref) {
        Build build =
                (Build)
                        Proxy.newProxyInstance(
                                Build.class.getClassLoader(),
                                new Class<?>[] {Build.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "build" -> ref;
                                            case "metadata" ->
                                                    new BuildTypes.BuildMetadata(
                                                            "Actual build",
                                                            CdnKey.parse("1".repeat(32)),
                                                            CdnKey.parse("2".repeat(32)),
                                                            CdnKey.parse("3".repeat(32)),
                                                            0,
                                                            3,
                                                            List.of("enUS"));
                                            default ->
                                                    throw new AssertionError(
                                                            "Metadata load requested domain work: "
                                                                    + method.getName());
                                        });
        return new BuildTypes.BuildOpenResult(
                ref, BuildTypes.BuildOpenState.OPEN, build, null, null, null, Instant.EPOCH);
    }

    private static LibraryCatalog.Figures figures() {
        var original = new LibraryCatalog.Usage(1, 100, 1, 3, 3, 0, 0, true, true);
        var derived = new LibraryCatalog.Usage(0, 0, 0, 0, 0, 0, 0, true, true);
        return new LibraryCatalog.Figures(original, derived);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
