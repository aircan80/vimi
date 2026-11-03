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

        public CompletableFuture<PolestarReply> fetchPolestarHead(Cancellation token) {
            calls.incrementAndGet();
            token.check();
            return offline
                    ? CompletableFuture.failedFuture(new IOException("Offline fixture"))
                    : CompletableFuture.completedFuture(
                            new PolestarReply(
                                    SourceUrls.POLESTAR_HEAD,
                                    Instant.EPOCH,
                                    Json.bytes(Map.of("object", Map.of("sha", "a".repeat(40))))));
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
            assertEquals(3, client.calls.get() - before);
            Path npc = directory.resolve("sources").resolve(first.id()).resolve("npc.jsonl");
            Files.writeString(npc, Files.readString(npc).replace("Authored", "Changed!"));
            var repaired = repository.sync(p -> {}, Cancellation.NONE).join();
            assertEquals(15, repaired.records());
            assertTrue(Files.readString(npc).contains("Authored"));
            Path database = directory.resolve("derived").resolve(first.id() + ".sqlite");
            Files.writeString(database, "invalid database");
            assertTrue(
                    repository.snapshots(Cancellation.NONE).join().getFirst().issues().size() > 0);
            assertEquals(
                    15,
                    repository.prepare(first.id(), p -> {}, Cancellation.NONE).join().records());
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
}
