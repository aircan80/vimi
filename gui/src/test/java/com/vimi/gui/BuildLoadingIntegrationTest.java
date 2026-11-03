package com.vimi.gui;

import com.vibeminer.builds.BuildRegistry;
import com.vibeminer.builds.SyntheticBuild;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.History;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Actual application composition: legacy cached metadata -> owned pack library, no research work.
 */
public final class BuildLoadingIntegrationTest {
    private BuildLoadingIntegrationTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-native-build-loading-");
        SyntheticBuild fixture = new SyntheticBuild();
        try {
            var old = ObjectStore.open(directory.resolve("objects")).join();
            try {
                var inputs =
                        fixture.objects.entrySet().stream()
                                .filter(
                                        entry ->
                                                !entry.getKey().equals(fixture.archiveIndexKey)
                                                        && !entry.getKey()
                                                                .equals(fixture.fileIndexKey))
                                .map(
                                        entry ->
                                                new StoreTypes.PutObject(
                                                        entry.getKey(), entry.getValue()))
                                .toList();
                require(
                        old.put(inputs).join().stream()
                                .allMatch(
                                        result -> result.outcome() == StoreTypes.PutOutcome.STORED),
                        "Fixture metadata is authentic and stored");
                String versions =
                        "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n"
                            + "us|"
                                + fixture.ref.buildConfig()
                                + "|"
                                + fixture.ref.cdnConfig()
                                + "|1.2.3.45678\n";
                old.appendHistory(
                                new History.Observation(
                                        1,
                                        "wow",
                                        "us",
                                        Instant.EPOCH,
                                        "https://example.invalid/versions",
                                        versions.getBytes(StandardCharsets.UTF_8)),
                                Cancellation.NONE)
                        .join();
            } finally {
                old.closeAsync().join();
            }
            String identity;
            var service = BuildApplication.open(directory, OfflineSourceFixtures::publicKeys).get(15, TimeUnit.SECONDS);
            try {
                var build =
                        service.listBuilds().get(15, TimeUnit.SECONDS).stream()
                                .filter(value -> value.config().equals(fixture.ref.buildConfig()))
                                .findFirst()
                                .orElseThrow();
                identity = build.identity();
                require(
                        service.preparation() != null,
                        "Full preparation is available while metadata-only opening remains independent");
                var opened =
                        service.opening()
                                .open(build, build.observations().getFirst())
                                .get(15, TimeUnit.SECONDS);
                require(
                        opened.state() == com.vibeminer.builds.BuildTypes.BuildOpenState.OPEN,
                        "ROOT/ENCODING metadata opens without archive indexes or research data: "
                                + opened.state());
                require(
                        service.loadSnapshot(build).entry().status() == BuildRegistry.Status.LOADED,
                        "Loaded status commits to registry before the successful result returns");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                BuildLoadSnapshot snapshot;
                do {
                    snapshot = service.refreshLoadSnapshot(build).get(5, TimeUnit.SECONDS);
                    if (snapshot.usage() != null && snapshot.usage().original().inventoryComplete())
                        break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                require(
                        snapshot.usage() != null && snapshot.usage().original().inventoryComplete(),
                        "Published EN ownership inventory becomes visible independently");
                require(
                        snapshot.usage().original().storedBytes() > 0,
                        "Metadata read from legacy fallback is copied with build ownership and"
                                + " counted");
                var missing =
                        new BuildInfo(
                                "wow",
                                "Retail",
                                "Missing metadata",
                                "us",
                                "e".repeat(32),
                                BuildInfo.BuildSource.SAVED,
                                false,
                                null,
                                0,
                                "missing-config-native-fixture",
                                Instant.EPOCH,
                                Instant.EPOCH,
                                List.of(build.observations().getFirst()));
                var unavailable =
                        service.opening()
                                .open(missing, missing.observations().getFirst())
                                .get(5, TimeUnit.SECONDS);
                require(
                        unavailable.state() != com.vibeminer.builds.BuildTypes.BuildOpenState.OPEN,
                        "Absent metadata does not create a loaded build");
                var incomplete = service.refreshLoadSnapshot(missing).get(5, TimeUnit.SECONDS);
                require(
                        incomplete.entry().status() == BuildRegistry.Status.FAILED
                                && incomplete.usage() != null
                                && !incomplete.usage().original().inventoryComplete(),
                        "A failed-before-first-read load retains explicit incomplete ownership"
                            + " intent");
                try (var database =
                        DerivedSqliteConnection.openReadOnly(
                                directory.resolve("library/objects.sqlite3"))) {
                    long indexRows =
                            database.run(
                                    Cancellation.NONE,
                                    false,
                                    session ->
                                            ((Number)
                                                            session.query(
                                                                            "SELECT count(*) FROM"
                                                                                + " storage_objects"
                                                                                + " WHERE k IN"
                                                                                + " (?,?)",
                                                                            fixture.archiveIndexKey
                                                                                    .indexBytes(),
                                                                            fixture.fileIndexKey
                                                                                    .indexBytes())
                                                                    .getFirst()[0])
                                                    .longValue());
                    require(
                            indexRows == 0,
                            "Metadata-only build load never requests archive/file indexes");
                }
            } finally {
                service.closeAsync().get(15, TimeUnit.SECONDS);
            }
            var reopened = BuildApplication.open(directory, OfflineSourceFixtures::publicKeys).get(15, TimeUnit.SECONDS);
            try {
                var build =
                        reopened.listBuilds().join().stream()
                                .filter(value -> value.identity().equals(identity))
                                .findFirst()
                                .orElseThrow();
                require(
                        reopened.loadSnapshot(build).entry().status()
                                == BuildRegistry.Status.LOADED,
                        "Loaded status survives application restart without reloading a workspace");
                require(
                        reopened.refreshLoadSnapshot(build)
                                        .get(5, TimeUnit.SECONDS)
                                        .usage()
                                        .original()
                                        .storedBytes()
                                > 0,
                        "Build-size accounting survives restart");
            } finally {
                reopened.closeAsync().join();
            }
            require(
                    Files.isRegularFile(directory.resolve("objects/objects.sqlite3")),
                    "Legacy source store remains available");
            require(
                    !Files.exists(directory.resolve("derived/grids")),
                    "No item/query preparation files created");
            System.out.println(
                    "BuildLoadingIntegration: owned metadata load, persisted status, accounting and"
                            + " restart passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
