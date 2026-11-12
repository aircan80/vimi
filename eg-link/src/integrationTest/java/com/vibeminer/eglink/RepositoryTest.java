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
    void creatureDisplaysAreReadInBatchesAndRemembered() throws Exception {
        var source = new Source();
        try (var repository = new EgLinkRepository(folder, source)) {
            var build = repository.check(Cancellation.NONE).join().builds().getFirst();
            String id = repository.download(build, p -> {}, Cancellation.NONE).join().id();
            repository.prepare(id, p -> {}, Cancellation.NONE).join();
            // More IDs than one query reads: every one is answered, recorded or not.
            var ids = new java.util.ArrayList<Long>();
            for (long i = 0; i < 1200; i++) {
                ids.add(i);
            }
            var found = repository.creatureDisplays(id, "enUS", ids, Cancellation.NONE).join();
            assertEquals(1200, found.size());
            assertTrue(found.get(197L).isPresent());
            assertEquals(java.util.List.of(), found.get(197L).get().displays());
            assertEquals("2026-11-01", found.get(197L).get().collectedOn());
            assertTrue(found.get(5L).isEmpty());
            // The Imp's display lacks its probability: recorded but unreadable, and the batch
            // still answers every other creature.
            assertTrue(found.get(416L).get().malformed());
            assertEquals(
                    "Creature display lacks its ID or probability",
                    found.get(416L).get().problem());
            // Asked for alone, the malformed record is an error, so the NPC page says so.
            var alone =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    repository
                                            .creatureDisplays(id, "enUS", 416, Cancellation.NONE)
                                            .join());
            assertTrue(alone.getCause() instanceof java.io.IOException, String.valueOf(alone));
            // A remembered answer returns at once, without a task.
            var again = repository.creatureDisplays(id, "enUS", 197, Cancellation.NONE);
            assertTrue(again.isDone());
            assertEquals(found.get(197L), again.join());
            // A locale the snapshot does not record answers empty.
            assertTrue(
                    repository
                            .creatureDisplays(id, "deDE", 197, Cancellation.NONE)
                            .join()
                            .isEmpty());
            assertEquals(1, repository.openSnapshotCount().join());
            // Re-preparing the snapshot closes its handle and forgets its answers.
            repository.prepare(id, p -> {}, Cancellation.NONE).join();
            assertEquals(0, repository.openSnapshotCount().join());
            assertTrue(
                    repository
                            .creatureDisplays(id, "enUS", 197, Cancellation.NONE)
                            .join()
                            .isPresent());
            assertEquals(1, repository.openSnapshotCount().join());
            repository.closeAsync().join();
            // Closed: even a remembered answer is refused, like every other call.
            assertThrows(
                    CompletionException.class,
                    () -> repository.creatureDisplays(id, "enUS", 197, Cancellation.NONE).join());
        }
    }

    /** A source advertising {@code count} wowf builds, each with the same creature records. */
    static Source builds(int count) throws Exception {
        var source = new Source();
        var products = new HashMap<String, Object>();
        byte[] details = source.files.get("data/wowf/" + VERSION + "/creature_details.enUS.json");
        for (int i = 0; i < count; i++) {
            String version = "1.60.1." + (70700 + i);
            products.put(
                    version,
                    Map.of(
                            "creature_details.enUS",
                            Map.of("records", 2, "sources", 1, "lastCollectedOn", "2026-11-01")));
            var parsed = (Map<?, ?>) EgJson.parse(details, 1 << 20);
            var meta = new HashMap<String, Object>();
            ((Map<?, ?>) parsed.get("meta")).forEach((k, v) -> meta.put((String) k, v));
            meta.put("clientBuild", version);
            source.files.put(
                    "data/wowf/" + version + "/creature_details.enUS.json",
                    EgJson.bytes(Map.of("meta", meta, "creatures", parsed.get("creatures"))));
        }
        source.files.put(
                "data/index.json",
                EgJson.bytes(
                        Map.of(
                                "license",
                                EgLinkTypes.LICENSE,
                                "attribution",
                                EgLinkTypes.ATTRIBUTION,
                                "products",
                                Map.of("wowf", products))));
        return source;
    }

    @Test
    void atMostFourSnapshotDatabasesStayOpen() throws Exception {
        var source = builds(5);
        try (var repository = new EgLinkRepository(folder, source)) {
            var ids = new java.util.ArrayList<String>();
            for (var build : repository.check(Cancellation.NONE).join().builds()) {
                String id = repository.download(build, p -> {}, Cancellation.NONE).join().id();
                repository.prepare(id, p -> {}, Cancellation.NONE).join();
                ids.add(id);
            }
            assertEquals(5, ids.size());
            for (String id : ids) {
                assertTrue(
                        repository
                                .creatureDisplays(id, "enUS", 197, Cancellation.NONE)
                                .join()
                                .isPresent());
            }
            assertEquals(4, repository.openSnapshotCount().join());
            // The evicted one opens again when asked for something new.
            assertTrue(
                    repository
                            .creatureDisplays(ids.getFirst(), "enUS", 5, Cancellation.NONE)
                            .join()
                            .isEmpty());
            assertEquals(4, repository.openSnapshotCount().join());
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

    /** Three builds downloaded and prepared at one commit; the repository retains two. */
    private EgLinkRepository crowded(Source source, java.util.Map<String, Object> builds)
            throws Exception {
        byte[] current = source.files.get("data/wowf/" + VERSION + "/creature_details.enUS.json");
        for (String version : java.util.List.of("1.60.1.70100", "1.60.1.70200", "1.60.1.70300")) {
            var dataset = EgJson.object(EgJson.parse(current, 1 << 20));
            EgJson.object(dataset.get("meta")).put("clientBuild", version);
            source.files.put(
                    "data/wowf/" + version + "/creature_details.enUS.json", EgJson.bytes(dataset));
            builds.put(version, collected("2026-11-01"));
        }
        index(source, builds);
        var repository = new EgLinkRepository(folder, source, 2);
        for (var build : repository.check(Cancellation.NONE).join().builds()) {
            var held = repository.download(build, progress -> {}, Cancellation.NONE).join();
            repository.prepare(held.id(), progress -> {}, Cancellation.NONE).join();
        }
        return repository;
    }

    private static Map<String, Object> collected(String date) {
        return Map.of(
                "creature_details.enUS",
                Map.of("records", 2, "sources", 1, "lastCollectedOn", date));
    }

    private static String idOf(java.util.List<EgLinkTypes.Snapshot> held, String version) {
        return held.stream()
                .filter(value -> value.build().version().equals(version))
                .map(EgLinkTypes.Snapshot::id)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aSnapshotABuildStillReadsIsNeverPruned() throws Exception {
        var source = new Source();
        var builds = new HashMap<String, Object>();
        try (var repository = crowded(source, builds)) {
            var synced =
                    repository
                            .sync(
                                    progress -> {},
                                    held -> java.util.Set.of(idOf(held, "1.60.1.70100")),
                                    Cancellation.NONE)
                            .join();
            assertTrue(synced.issues().isEmpty(), synced.issues().toString());
            var versions =
                    synced.snapshots().stream().map(value -> value.build().version()).toList();
            // Two retained (70300, 70200) plus the kept 70100, which would otherwise go.
            assertEquals(3, versions.size(), versions.toString());
            assertTrue(versions.contains("1.60.1.70100"));
            var plain = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(2, plain.snapshots().size(), "without the keep, it goes");
        }
    }

    @Test
    void aSnapshotThatCannotBeReleasedIsAnIssueAndStaysListed() throws Exception {
        var source = new Source();
        var builds = new HashMap<String, Object>();
        var released = new java.util.ArrayList<String>();
        try (var repository = crowded(source, builds)) {
            EgLinkRepository.releaseForTest =
                    id -> {
                        released.add(id);
                        // As Windows refuses to delete a database a handle still holds open.
                        throw new IllegalStateException("file is in use");
                    };
            var synced = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertEquals(1, released.size(), "released before anything is deleted");
            assertEquals(1, synced.issues().size(), synced.issues().toString());
            assertTrue(synced.issues().getFirst().contains("file is in use"));
            assertEquals(3, synced.snapshots().size(), "the snapshot stays, complete");
            assertEquals(3, synced.reused(), "the rest of the sync still counts");
            EgLinkRepository.releaseForTest = released::add;
            var retried = repository.sync(progress -> {}, Cancellation.NONE).join();
            assertTrue(retried.issues().isEmpty(), retried.issues().toString());
            assertEquals(2, retried.snapshots().size(), "removed at the next sync");
        } finally {
            EgLinkRepository.releaseForTest = null;
        }
    }

    @Test
    void aRemovalInterruptedAfterTheTrashRenameLeavesNoHalfSnapshot() throws Exception {
        var source = new Source();
        var builds = new HashMap<String, Object>();
        try (var repository = crowded(source, builds)) {
            EgLinkRepository.trashedForTest =
                    id -> {
                        throw new IllegalStateException("crash after the rename");
                    };
            var synced = repository.sync(progress -> {}, Cancellation.NONE).join();
            EgLinkRepository.trashedForTest = null;
            assertEquals(1, synced.issues().size(), synced.issues().toString());
            assertEquals(2, synced.snapshots().size(), "the trashed snapshot is not listed");
            try (var listed = Files.list(folder.resolve("sources"))) {
                for (Path path : listed.toList()) {
                    String name = path.getFileName().toString();
                    if (name.matches("[0-9a-f]{64}")) {
                        assertTrue(
                                Files.exists(path.resolve("source.json")),
                                "every listed folder keeps its manifest");
                    }
                }
            }
            try (var listed = Files.list(folder.resolve("sources"))) {
                assertTrue(
                        listed.anyMatch(
                                path -> path.getFileName().toString().startsWith(".trash-")),
                        "the interrupted removal left its trash");
            }
            repository.sync(progress -> {}, Cancellation.NONE).join();
            try (var listed = Files.list(folder.resolve("sources"))) {
                assertFalse(
                        listed.anyMatch(
                                path -> path.getFileName().toString().startsWith(".trash-")),
                        "the next sync purges it");
            }
        } finally {
            EgLinkRepository.trashedForTest = null;
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
