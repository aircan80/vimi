package com.vibeminer.eglink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.Cancellation;
import com.vibeminer.network.EgLinkClient;
import com.vibeminer.network.NetworkTypes;
import com.vibeminer.network.SourceUrls;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RepositoryTest {
    @TempDir Path folder;
    static final String REVISION = "a".repeat(40), VERSION = "1.60.1.70707";

    static final class Source implements EgLinkClient {
        final Map<String, byte[]> files = new HashMap<>();
        int requests;
        String revision = REVISION;

        Source() throws Exception {
            files.put(
                    "data/index.json",
                    EgJson.bytes(
                            Map.of(
                                    "license",
                                    EgLinkTypes.LICENSE,
                                    "attribution",
                                    EgLinkTypes.ATTRIBUTION,
                                    "products",
                                    Map.of(
                                            "wowf",
                                            Map.of(
                                                    VERSION,
                                                    Map.of(
                                                            "creature_details.enUS",
                                                            Map.of(
                                                                    "records",
                                                                    2,
                                                                    "sources",
                                                                    1,
                                                                    "lastCollectedOn",
                                                                    "2026-11-01")))))));
            files.put(
                    "data/wowf/" + VERSION + "/creature_details.enUS.json",
                    EgJson.bytes(
                            Map.of(
                                    "meta",
                                    Map.of(
                                            "license",
                                            EgLinkTypes.LICENSE,
                                            "attribution",
                                            EgLinkTypes.ATTRIBUTION,
                                            "product",
                                            "wowf",
                                            "clientBuild",
                                            VERSION,
                                            "dataset",
                                            "creature_details",
                                            "locale",
                                            "enUS",
                                            "schemaVersion",
                                            1),
                                    "creatures",
                                    Map.of(
                                            "197",
                                            Map.of("name", "Marshal McBride", "sources", 1),
                                            "416",
                                            Map.of(
                                                    "name",
                                                    "Imp",
                                                    "displays",
                                                    java.util.List.of(
                                                            Map.of("displayId", 123)))))));
        }

        public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkHead(Cancellation token) {
            try {
                token.check();
                requests++;
                return CompletableFuture.completedFuture(
                        new NetworkTypes.EgLinkReply(
                                SourceUrls.EG_LINK_HEAD,
                                Instant.EPOCH,
                                EgJson.bytes(Map.of("object", Map.of("sha", revision)))));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        public CompletableFuture<NetworkTypes.EgLinkReply> fetchEgLinkFile(
                String revision, String path, Cancellation token) {
            token.check();
            requests++;
            return CompletableFuture.completedFuture(
                    new NetworkTypes.EgLinkReply(
                            SourceUrls.egLink(revision, path), Instant.EPOCH, files.get(path)));
        }
    }

    @Test
    void downloadPrepareQueryAndRestartNeedNoNetwork() throws Exception {
        var source = new Source();
        String id;
        try (var repository = new EgLinkRepository(folder, source)) {
            assertTrue(repository.catalog(Cancellation.NONE).join().builds().isEmpty());
            var catalog = repository.check(Cancellation.NONE).join();
            var snapshot =
                    repository
                            .download(catalog.builds().getFirst(), p -> {}, Cancellation.NONE)
                            .join();
            id = snapshot.id();
            assertEquals(3, source.requests);
            var prepared = repository.prepare(id, p -> {}, Cancellation.NONE).join();
            assertEquals(2, prepared.indexedRecords());
            assertEquals(
                    "Marshal McBride",
                    repository
                            .lookup(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "197",
                                    Cancellation.NONE)
                            .join()
                            .orElseThrow()
                            .name());
            assertEquals(
                    1,
                    repository
                            .search(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "Imp",
                                    0,
                                    10,
                                    Cancellation.NONE)
                            .join()
                            .records()
                            .size());
            assertTrue(
                    repository
                            .lookup(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "999",
                                    Cancellation.NONE)
                            .join()
                            .isEmpty());
        }
        var offline = new Source();
        offline.files.clear();
        try (var repository = new EgLinkRepository(folder, offline)) {
            assertEquals(1, repository.catalog(Cancellation.NONE).join().builds().size());
            assertEquals(
                    2, repository.snapshots(Cancellation.NONE).join().getFirst().indexedRecords());
            assertEquals(
                    2,
                    repository
                            .search(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "",
                                    0,
                                    10,
                                    Cancellation.NONE)
                            .join()
                            .records()
                            .size());
            assertEquals(0, offline.requests);
        }
    }

    @Test
    void invalidUpdateAndCanceledDownloadKeepPreviousState() throws Exception {
        var source = new Source();
        try (var repository = new EgLinkRepository(folder, source)) {
            var catalog = repository.check(Cancellation.NONE).join();
            source.revision = "b".repeat(40);
            source.files.put(
                    "data/index.json", "bad".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThrows(
                    CompletionException.class, () -> repository.check(Cancellation.NONE).join());
            assertEquals(catalog, repository.catalog(Cancellation.NONE).join());
            var token = new Cancellation();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            repository
                                    .download(
                                            catalog.builds().getFirst(), p -> token.cancel(), token)
                                    .join());
            assertTrue(repository.snapshots(Cancellation.NONE).join().isEmpty());
            try (var files = Files.list(folder.resolve("sources"))) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void damagedOriginalFailsPreparationAndCanceledRebuildKeepsPreparedData() throws Exception {
        var source = new Source();
        try (var repository = new EgLinkRepository(folder, source)) {
            var build = repository.check(Cancellation.NONE).join().builds().getFirst();
            String id = repository.download(build, p -> {}, Cancellation.NONE).join().id();
            repository.prepare(id, p -> {}, Cancellation.NONE).join();
            var token = new Cancellation();
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> repository.prepare(id, p -> token.cancel(), token).join());
            assertFalse(
                    repository
                            .lookup(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "197",
                                    Cancellation.NONE)
                            .join()
                            .isEmpty());
            Files.writeString(
                    folder.resolve("sources").resolve(id).resolve("creature_details.enUS.json"),
                    "{}");
            assertThrows(
                    CompletionException.class,
                    () -> repository.prepare(id, p -> {}, Cancellation.NONE).join());
            assertFalse(
                    repository
                            .lookup(
                                    id,
                                    "creature_details.enUS",
                                    "creatures",
                                    "197",
                                    Cancellation.NONE)
                            .join()
                            .isEmpty());
        }
    }

    @Test
    void futureCatalogIsReadOnlyAndMalformedDatasetDoesNotPublishSnapshot() throws Exception {
        var source = new Source();
        try (var repository = new EgLinkRepository(folder, source)) {
            var build = repository.check(Cancellation.NONE).join().builds().getFirst();
            source.files.put(
                    "data/wowf/" + VERSION + "/creature_details.enUS.json",
                    "{}".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThrows(
                    CompletionException.class,
                    () -> repository.download(build, p -> {}, Cancellation.NONE).join());
            assertTrue(repository.snapshots(Cancellation.NONE).join().isEmpty());
            byte[] future = EgJson.bytes(Map.of("format", "vimi-eg-link-catalog-v99"));
            Files.write(folder.resolve("catalog.json"), future);
            assertThrows(
                    CompletionException.class, () -> repository.check(Cancellation.NONE).join());
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    future, Files.readAllBytes(folder.resolve("catalog.json")));
        }
    }

    private static void index(Source source, Map<String, Object> builds) throws Exception {
        source.files.put(
                "data/index.json",
                EgJson.bytes(
                        Map.of(
                                "license",
                                EgLinkTypes.LICENSE,
                                "attribution",
                                EgLinkTypes.ATTRIBUTION,
                                "products",
                                Map.of("wowf", builds))));
    }

    @Test
    void eachProductKeepsItsNewestBuildsBeforeRepeatedCommitsAndPrunesTheRest() throws Exception {
        var source = new Source();
        byte[] current = source.files.get("data/wowf/" + VERSION + "/creature_details.enUS.json");
        var versions = java.util.List.of("1.60.1.70100", "1.60.1.70200", "1.60.1.70300");
        var builds = new java.util.HashMap<String, Object>();
        for (String version : versions) {
            var dataset = EgJson.object(EgJson.parse(current, 1 << 20));
            EgJson.object(dataset.get("meta")).put("clientBuild", version);
            source.files.put(
                    "data/wowf/" + version + "/creature_details.enUS.json", EgJson.bytes(dataset));
            builds.put(
                    version,
                    Map.of(
                            "creature_details.enUS",
                            Map.of("records", 2, "sources", 1, "lastCollectedOn", "2026-11-01")));
        }
        index(source, builds);
        try (var repository = new EgLinkRepository(folder, source, 3)) {
            var catalog = repository.check(Cancellation.NONE).join();
            for (var build : catalog.builds()) {
                repository.download(build, progress -> {}, Cancellation.NONE).join();
            }
            // The latest build changes on each of three more commits.
            for (int day = 2; day <= 4; day++) {
                builds.put(
                        "1.60.1.70300",
                        Map.of(
                                "creature_details.enUS",
                                Map.of(
                                        "records",
                                        2,
                                        "sources",
                                        1,
                                        "lastCollectedOn",
                                        "2026-11-0" + day)));
                index(source, builds);
                source.revision = Integer.toHexString(day).repeat(40);
                repository.sync(progress -> {}, Cancellation.NONE).join();
            }
            var kept = repository.snapshots(Cancellation.NONE).join();
            assertEquals(3, kept.size(), "each product keeps three snapshots");
            assertEquals(
                    java.util.Set.of("1.60.1.70100", "1.60.1.70200", "1.60.1.70300"),
                    kept.stream()
                            .map(value -> value.build().version())
                            .collect(java.util.stream.Collectors.toSet()),
                    "every build outlasts repeated commits of the latest one");
            var latest =
                    kept.stream()
                            .filter(value -> value.build().version().equals("1.60.1.70300"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("4".repeat(40), latest.build().revision(), "the newest commit is kept");
            try (var listed = java.nio.file.Files.list(folder.resolve("sources"))) {
                assertEquals(
                        3,
                        listed.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}"))
                                .count(),
                        "pruned snapshots are deleted");
            }
            try (var listed = java.nio.file.Files.list(folder.resolve("derived"))) {
                assertTrue(
                        listed.filter(path -> path.toString().endsWith(".sqlite")).count() <= 3,
                        "pruned databases are deleted");
            }
        }
    }

    @Test
    void syncUpdatesLatestAndInstalledBuildsAndReusesUnchangedPreparedData() throws Exception {
        var source = new Source();
        String old = "1.60.1.70000", middle = "1.60.1.70500";
        var spec = Map.of("records", 2, "sources", 1, "lastCollectedOn", "2026-11-01");
        source.files.put(
                "data/index.json",
                EgJson.bytes(
                        Map.of(
                                "license",
                                EgLinkTypes.LICENSE,
                                "attribution",
                                EgLinkTypes.ATTRIBUTION,
                                "products",
                                Map.of(
                                        "wowf",
                                        Map.of(
                                                old, Map.of("creature_details.enUS", spec),
                                                middle, Map.of("creature_details.enUS", spec),
                                                VERSION, Map.of("creature_details.enUS", spec))))));
        byte[] current = source.files.get("data/wowf/" + VERSION + "/creature_details.enUS.json");
        for (String version : java.util.List.of(old, middle)) {
            var dataset = EgJson.object(EgJson.parse(current, 1 << 20));
            EgJson.object(dataset.get("meta")).put("clientBuild", version);
            source.files.put(
                    "data/wowf/" + version + "/creature_details.enUS.json", EgJson.bytes(dataset));
        }
        try (var repository = new EgLinkRepository(folder, source)) {
            var catalog = repository.check(Cancellation.NONE).join();
            var oldBuild =
                    catalog.builds().stream()
                            .filter(build -> build.version().equals(old))
                            .findFirst()
                            .orElseThrow();
            repository.download(oldBuild, progress -> {}, Cancellation.NONE).join();
            var synced = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(1, synced.downloaded());
            assertEquals(2, synced.prepared());
            assertEquals(0, synced.reused());
            assertEquals(2, synced.snapshots().size());
            assertTrue(
                    synced.snapshots().stream()
                            .noneMatch(value -> value.build().version().equals(middle)));
            int requests = source.requests;
            var unchanged = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(0, unchanged.downloaded());
            assertEquals(0, unchanged.prepared());
            assertEquals(2, unchanged.reused());
            assertEquals(
                    requests + 1, source.requests, "Unchanged commit makes one head request only");
            source.revision = "b".repeat(40);
            var moved = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(0, moved.downloaded(), "A commit that leaves both builds alone");
            assertEquals(2, moved.reused());
            assertEquals(2, moved.snapshots().size());
            var collected = Map.of("records", 2, "sources", 1, "lastCollectedOn", "2026-11-02");
            index(
                    source,
                    Map.of(
                            old, Map.of("creature_details.enUS", collected),
                            middle, Map.of("creature_details.enUS", spec),
                            VERSION, Map.of("creature_details.enUS", collected)));
            source.revision = "c".repeat(40);
            var changed = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(2, changed.downloaded());
            assertEquals(2, changed.prepared());
            assertEquals(4, changed.snapshots().size(), "Earlier exact revisions remain available");
            source.revision = "d".repeat(40);
            var again = Map.of("records", 2, "sources", 1, "lastCollectedOn", "2026-11-03");
            index(
                    source,
                    Map.of(
                            old, Map.of("creature_details.enUS", again),
                            middle, Map.of("creature_details.enUS", spec),
                            VERSION, Map.of("creature_details.enUS", again)));
            source.files.put(
                    "data/wowf/" + old + "/creature_details.enUS.json", new byte[] {'{', '}'});
            var partial = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(1, partial.downloaded());
            assertEquals(1, partial.prepared());
            assertEquals(1, partial.issues().size());
            assertEquals(
                    5,
                    partial.snapshots().size(),
                    "A failed build does not discard earlier snapshots or block another build");
        }
    }
}
