package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class CatalogPersistenceTest {
    @TempDir Path folder;

    private static String versions() {
        return "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\nus|"
                + "a".repeat(32)
                + "|"
                + "b".repeat(32)
                + "|one\n";
    }

    @Test
    void projectionSurvivesReopenAndCanRebuildAfterCacheLoss() throws Exception {
        CatalogSource source = new CatalogSource();
        var entry = source.append(1, "wow", "us", Instant.EPOCH, versions());
        Path path = folder.resolve("catalog.sqlite3");
        Catalog catalog = Catalog.open(source, path).join();
        try {
            assertEquals(1, catalog.listBuilds("wow").join().builds().size());
        } finally {
            catalog.closeAsync().join();
        }
        source.lose(entry.document());
        int before = source.gets;
        catalog = Catalog.open(source, path).join();
        try {
            assertEquals(1, catalog.listBuilds("wow").join().builds().size());
            assertEquals(before, source.gets);
        } finally {
            catalog.closeAsync().join();
        }
        Files.delete(path);
        source.restore(entry.document(), versions().getBytes(StandardCharsets.UTF_8));
        catalog = Catalog.open(source, path).join();
        try {
            assertEquals(1, catalog.listBuilds("wow").join().builds().size());
            assertTrue(source.gets > before);
        } finally {
            catalog.closeAsync().join();
        }
    }

    @Test
    void foreignAndNewerFilesAreRefusedWithoutModification() throws Exception {
        Path bad = folder.resolve("foreign");
        byte[] original = {1, 2, 3, 4};
        Files.write(bad, original);
        CompletionException failure =
                assertThrows(
                        CompletionException.class,
                        () -> Catalog.open(new CatalogSource(), bad).join());
        assertEquals("CATALOG_UNREADABLE", ((CatalogStoreException) failure.getCause()).code());
        assertArrayEquals(original, Files.readAllBytes(bad));
        Path newer = folder.resolve("newer.sqlite3");
        Catalog catalog = Catalog.open(new CatalogSource(), newer).join();
        catalog.closeAsync().join();
        try (NativeSqlite db = new NativeSqlite(newer, false)) {
            db.exec("PRAGMA user_version=999");
        }
        byte[] bytes = Files.readAllBytes(newer);
        failure =
                assertThrows(
                        CompletionException.class,
                        () -> Catalog.open(new CatalogSource(), newer).join());
        assertEquals("CATALOG_NEWER", ((CatalogStoreException) failure.getCause()).code());
        assertArrayEquals(bytes, Files.readAllBytes(newer));
    }

    @Test
    void nativeProgressCancellationFinalizesStatementAndConnectionRemainsUsable() throws Exception {
        try (NativeSqlite db = new NativeSqlite(folder.resolve("probe.sqlite3"), false)) {
            Cancellation token = new Cancellation();
            db.progressProbe = token::cancel;
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            db.withCancellation(
                                    token,
                                    () ->
                                            db.scalar(
                                                    "WITH RECURSIVE x(n) AS (VALUES(0) UNION ALL SELECT n+1 FROM x WHERE n<1000000) SELECT sum(n) FROM x")));
            db.progressProbe = null;
            assertEquals(42, db.scalar("SELECT 42"));
        }
    }

    @Test
    void uncertainCommittedProjectionRequiresReopenAndKeepsTheDurableEntry() throws Exception {
        CatalogSource source = new CatalogSource();
        var entry = source.append(1, "wow", "us", Instant.EPOCH, versions());
        Path path = folder.resolve("uncertain.sqlite3");
        try (CatalogStore store = CatalogStore.open(path, Cancellation.NONE)) {
            var field = CatalogStore.class.getDeclaredField("db");
            field.setAccessible(true);
            NativeSqlite db = (NativeSqlite) field.get(store);
            db.afterStatementProbe =
                    sql -> {
                        if (sql.equals("COMMIT")) {
                            throw new IllegalStateException("commit result lost");
                        }
                    };
            assertThrows(
                    CatalogStoreException.class,
                    () ->
                            store.add(
                                    entry,
                                    VersionsReply.read(
                                            entry, versions().getBytes(StandardCharsets.UTF_8)),
                                    Cancellation.NONE));
            db.afterStatementProbe = null;
            assertThrows(
                    CatalogStoreException.class,
                    () -> store.has(entry.record(), Cancellation.NONE));
        }
        try (CatalogStore reopened = CatalogStore.open(path, Cancellation.NONE)) {
            assertTrue(reopened.has(entry.record(), Cancellation.NONE));
            assertEquals(1, reopened.list("wow", Cancellation.NONE).builds().size());
        }
    }

    @Test
    void queuedCancellationAndCloseDoNotRunOrDropAcceptedWork() throws Exception {
        CatalogSource source = new CatalogSource();
        source.append(1, "wow", "us", Instant.EPOCH, versions());
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<com.vibeminer.access.SourceTypes.ObjectResult> gate =
                new CompletableFuture<>();
        source.getOverride =
                request -> {
                    entered.countDown();
                    return gate;
                };
        Catalog catalog = Catalog.open(source, folder.resolve("catalog.sqlite3")).join();
        try {
            var first = catalog.listBuilds("wow");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Cancellation token = new Cancellation();
            var queued = catalog.listBuilds("wow", token);
            token.cancel();
            assertTrue(
                    queued.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .get(5, TimeUnit.SECONDS));
            var close = catalog.closeAsync();
            assertFalse(close.isDone());
            gate.complete(
                    new com.vibeminer.access.SourceTypes.ObjectResult(
                            new com.vibeminer.store.ObjectKey(5, new byte[16]),
                            com.vibeminer.access.SourceTypes.ObjectState.UNAVAILABLE,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
            close.join();
            assertTrue(first.isDone());
            assertThrows(CompletionException.class, () -> catalog.listBuilds("wow").join());
        } finally {
            gate.completeExceptionally(new IllegalStateException("cleanup"));
            catalog.closeAsync().join();
        }
    }
}
