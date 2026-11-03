package com.vibeminer.polestar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.Cancellation;
import com.vibeminer.network.NetworkTypes.PolestarReply;
import com.vibeminer.network.PolestarClient;
import com.vibeminer.network.SourceUrls;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PolestarRepositoryTest {
    @TempDir Path directory;

    private static final class Client implements PolestarClient {
        final Map<String, byte[]> files = PolestarFixtures.files();
        final AtomicInteger calls = new AtomicInteger();
        boolean offline;
        String revision = "a".repeat(40);

        public CompletableFuture<PolestarReply> fetchPolestarHead(Cancellation token) {
            calls.incrementAndGet();
            token.check();
            return offline
                    ? CompletableFuture.failedFuture(new IOException("Offline fixture"))
                    : CompletableFuture.completedFuture(
                            new PolestarReply(
                                    SourceUrls.POLESTAR_HEAD,
                                    Instant.EPOCH,
                                    Json.bytes(Map.of("object", Map.of("sha", revision)))));
        }

        public CompletableFuture<PolestarReply> fetchPolestarFile(
                String revision, String path, Cancellation token) {
            calls.incrementAndGet();
            token.check();
            return offline
                    ? CompletableFuture.failedFuture(new IOException("Offline fixture"))
                    : CompletableFuture.completedFuture(
                            new PolestarReply(
                                    SourceUrls.polestar(revision, path),
                                    Instant.EPOCH,
                                    files.get(path)));
        }
    }

    @Test
    void downloadsValidatesPreparesQueriesAndReopensOfflineWithoutNetwork() throws Exception {
        var client = new Client();
        String id;
        try (var repository = new PolestarRepository(directory, client)) {
            assertTrue(repository.catalog(Cancellation.NONE).join().isEmpty());
            var catalog = repository.check(Cancellation.NONE).join();
            var downloaded = repository.download(catalog, p -> {}, Cancellation.NONE).join();
            id = downloaded.id();
            assertEquals(null, downloaded.preparedAt());
            assertEquals(0, downloaded.records());
            var prepared = repository.prepare(id, p -> {}, Cancellation.NONE).join();
            assertEquals(15, prepared.records());
            var found =
                    repository.lookup(id, PolestarTypes.Table.NPC, "1", Cancellation.NONE).join();
            assertEquals(1, found.size());
            assertEquals("Authored record", found.getFirst().name());
            assertEquals("GPL-3.0-only", found.getFirst().license());
            assertEquals("1.60.1.70532", found.getFirst().clientBuild());
            assertEquals(
                    1,
                    repository
                            .search(id, PolestarTypes.Table.NPC, "record", 0, 10, Cancellation.NONE)
                            .join()
                            .total());
            assertEquals(
                    0,
                    repository
                            .search(id, PolestarTypes.Table.NPC, "%", 0, 10, Cancellation.NONE)
                            .join()
                            .total());
        }
        client.offline = true;
        int calls = client.calls.get();
        try (var repository = new PolestarRepository(directory, client)) {
            assertEquals(1, repository.snapshots(Cancellation.NONE).join().size());
            assertFalse(
                    repository
                            .lookup(id, PolestarTypes.Table.QUEST, "1", Cancellation.NONE)
                            .join()
                            .isEmpty());
            repository.prepare(id, p -> {}, Cancellation.NONE).join();
            assertEquals(calls, client.calls.get());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> repository.check(Cancellation.NONE).join());
            assertTrue(repository.catalog(Cancellation.NONE).join().isPresent());
        }
    }

    @Test
    void pagedKeyLookupRetainsMoreThanFiveHundredSpawnRecordsOffline() throws Exception {
        var client = new Client();
        var data = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            data.append("{\"id\":1,\"name\":\"Authored spawn ").append(i).append("\"}\n");
        }
        byte[] bytes = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        client.files.put("data/spawn.jsonl", bytes);
        var index = Json.object(Json.parse(client.files.get("index.json"), 1 << 20));
        Json.object(index.get("counts")).put("spawn", 501L);
        client.files.put("index.json", Json.bytes(index));
        var pack = Json.object(Json.parse(client.files.get("datapackage.json"), 1 << 20));
        for (var value : Json.array(pack.get("resources"))) {
            var row = Json.object(value);
            if (row.get("name").equals("spawn")) {
                row.put("bytes", (long) bytes.length);
                row.put("hash", "sha256:" + PolestarRepository.hash(bytes));
            }
        }
        client.files.put("datapackage.json", Json.bytes(pack));
        try (var repository = new PolestarRepository(directory, client)) {
            var catalog = repository.check(Cancellation.NONE).join();
            var snapshot = repository.download(catalog, p -> {}, Cancellation.NONE).join();
            repository.prepare(snapshot.id(), p -> {}, Cancellation.NONE).join();
            client.offline = true;
            int calls = client.calls.get();
            var first =
                    repository
                            .lookupPage(
                                    snapshot.id(),
                                    PolestarTypes.Table.SPAWN,
                                    "1",
                                    0,
                                    500,
                                    Cancellation.NONE)
                            .join();
            var second =
                    repository
                            .lookupPage(
                                    snapshot.id(),
                                    PolestarTypes.Table.SPAWN,
                                    "1",
                                    500,
                                    500,
                                    Cancellation.NONE)
                            .join();
            assertEquals(501, first.total());
            assertEquals(500, first.records().size());
            assertEquals(501, second.total());
            assertEquals(1, second.records().size());
            assertEquals("Authored spawn 500", second.records().getFirst().name());
            assertEquals(calls, client.calls.get());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            repository.lookupPage(
                                    snapshot.id(),
                                    PolestarTypes.Table.SPAWN,
                                    "1",
                                    -1,
                                    500,
                                    Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            repository.lookupPage(
                                    snapshot.id(),
                                    PolestarTypes.Table.SPAWN,
                                    "1",
                                    0,
                                    501,
                                    Cancellation.NONE));
        }
    }

    @Test
    void checksumFailureAndCancellationNeverPublishPartialDownload() throws Exception {
        var client = new Client();
        try (var repository = new PolestarRepository(directory, client)) {
            var catalog = repository.check(Cancellation.NONE).join();
            client.files.put("data/npc.jsonl", new byte[3]);
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> repository.download(catalog, p -> {}, Cancellation.NONE).join());
            assertTrue(repository.snapshots(Cancellation.NONE).join().isEmpty());
            var canceled = new Cancellation();
            canceled.cancel();
            var canceledFailure =
                    assertThrows(
                            java.util.concurrent.CompletionException.class,
                            () -> repository.download(catalog, p -> {}, canceled).join());
            assertTrue(
                    canceledFailure.getCause()
                            instanceof java.util.concurrent.CancellationException);
            try (var paths = Files.list(directory.resolve("sources"))) {
                assertEquals(0, paths.count());
            }
        }
    }

    @Test
    void retainedCorruptionOrCountMismatchDoesNotPublishPreparedDatabase() throws Exception {
        var client = new Client();
        try (var repository = new PolestarRepository(directory, client)) {
            var source =
                    repository
                            .download(
                                    repository.check(Cancellation.NONE).join(),
                                    p -> {},
                                    Cancellation.NONE)
                            .join();
            Files.writeString(
                    directory.resolve("sources").resolve(source.id()).resolve("npc.jsonl"), "{}");
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> repository.prepare(source.id(), p -> {}, Cancellation.NONE).join());
            assertFalse(
                    Files.exists(directory.resolve("derived").resolve(source.id() + ".sqlite")));
        }
    }

    @Test
    void syncReusesVerifiedDataAndRepairsCorruptCopiesAndDerivedDatabase() throws Exception {
        var client = new Client();
        try (var repository = new PolestarRepository(directory, client)) {
            var first = repository.sync(p -> {}, Cancellation.NONE).join();
            int before = client.calls.get();
            repository.sync(p -> {}, Cancellation.NONE).join();
            assertEquals(1, client.calls.get() - before);
            var databaseFile = directory.resolve("derived").resolve(first.id() + ".sqlite");
            var modified = Files.getLastModifiedTime(databaseFile);
            client.revision = "c".repeat(40);
            before = client.calls.get();
            var unchanged = repository.sync(p -> {}, Cancellation.NONE).join();
            assertEquals(3, client.calls.get() - before);
            assertEquals(first.id(), unchanged.id());
            assertEquals(first.preparedAt(), unchanged.preparedAt());
            assertEquals(modified, Files.getLastModifiedTime(databaseFile));
            Path npc = directory.resolve("sources").resolve(first.id()).resolve("npc.jsonl");
            Files.writeString(npc, Files.readString(npc).replace("Authored", "Changed!"));
            var repaired = repository.sync(p -> {}, Cancellation.NONE).join();
            assertEquals(15, repaired.records());
            Path repairedNpc =
                    directory.resolve("sources").resolve(repaired.id()).resolve("npc.jsonl");
            assertTrue(Files.readString(repairedNpc).contains("Authored"));
            Path database = directory.resolve("derived").resolve(repaired.id() + ".sqlite");
            Files.writeString(database, "invalid database");
            assertTrue(
                    repository.snapshots(Cancellation.NONE).join().getFirst().issues().size() > 0);
            assertEquals(
                    15,
                    repository.prepare(repaired.id(), p -> {}, Cancellation.NONE).join().records());
        }
    }

    @Test
    void cancellationDuringPreparationRetainsOriginalsAndAllowsRetry() throws Exception {
        var client = new Client();
        try (var repository = new PolestarRepository(directory, client)) {
            var downloaded =
                    repository
                            .download(
                                    repository.check(Cancellation.NONE).join(),
                                    p -> {},
                                    Cancellation.NONE)
                            .join();
            var token = new Cancellation();
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> repository.prepare(downloaded.id(), p -> token.cancel(), token).join());
            assertEquals(
                    null, repository.snapshots(Cancellation.NONE).join().getFirst().preparedAt());
            assertEquals(
                    15,
                    repository
                            .prepare(downloaded.id(), p -> {}, Cancellation.NONE)
                            .join()
                            .records());
        }
    }

    @Test
    void changedPublicationIsDownloadedAndPreparedWithoutRelabelingThePreviousSnapshot()
            throws Exception {
        var client = new Client();
        try (var repository = new PolestarRepository(directory, client)) {
            var previous = repository.sync(p -> {}, Cancellation.NONE).join();
            client.revision = "c".repeat(40);
            var index = Json.object(Json.parse(client.files.get("index.json"), 1 << 20));
            var pack = Json.object(Json.parse(client.files.get("datapackage.json"), 1 << 20));
            index.put("version", "2026.11.2");
            pack.put("version", "2026.11.2");
            byte[] changed =
                    new String(
                                    client.files.get("data/npc.jsonl"),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .replace("Authored", "Changed!")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            client.files.put("data/npc.jsonl", changed);
            for (var value : Json.array(pack.get("resources"))) {
                var resource = Json.object(value);
                if ("npc".equals(resource.get("name"))) {
                    resource.put("hash", "sha256:" + PolestarRepository.hash(changed));
                }
            }
            client.files.put("index.json", Json.bytes(index));
            client.files.put("datapackage.json", Json.bytes(pack));
            var latest = repository.sync(p -> {}, Cancellation.NONE).join();
            assertEquals("2026.11.2", latest.catalog().version());
            assertEquals(client.revision, latest.id());
            assertEquals(
                    "Changed! record",
                    repository
                            .lookup(latest.id(), PolestarTypes.Table.NPC, "1", Cancellation.NONE)
                            .join()
                            .getFirst()
                            .name());
            assertEquals(
                    "Authored record",
                    repository
                            .lookup(previous.id(), PolestarTypes.Table.NPC, "1", Cancellation.NONE)
                            .join()
                            .getFirst()
                            .name());
        }
    }
}
