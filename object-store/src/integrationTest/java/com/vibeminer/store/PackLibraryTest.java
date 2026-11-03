package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackLibraryTest {
    @TempDir Path folder;

    @Test
    void mixedObjectsAndOwnershipCommitTogetherAndCachedReadsAcquireAnotherOwner()
            throws Exception {
        ObjectStore store = ObjectStore.openLibrary(folder).join();
        ObjectKey original = key(new byte[] {1, 2, 3});
        ObjectKey generated = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            var first =
                    store.forOwner(
                            "build-a",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            var second =
                    store.forOwner(
                            "build-b",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            var derived =
                    store.forOwner(
                            "build-a",
                            LibraryCatalog.Scope.BUILD,
                            "derived",
                            LibraryCatalog.Role.DERIVED);
            catalog.expect(
                            "build-a",
                            LibraryCatalog.Scope.BUILD,
                            "encoding",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            catalog.expect(
                            "build-b",
                            LibraryCatalog.Scope.BUILD,
                            "encoding",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            var empty = (LibraryCatalog.Inventory) (limit, token) -> List.of();
            catalog.replace("build-a", "encoding", "v1", empty, Cancellation.NONE).join();
            catalog.replace("build-b", "encoding", "v1", empty, Cancellation.NONE).join();
            assertEquals(
                    StoreTypes.PutOutcome.STORED,
                    first.put(List.of(new StoreTypes.PutObject(original, new byte[] {1, 2, 3})))
                            .join()
                            .getFirst()
                            .outcome());
            assertEquals(
                    3,
                    catalog.figures("build-a", Cancellation.NONE).join().original().storedBytes());
            assertArrayEquals(new byte[] {1, 2, 3}, second.read(original).join().bytes());
            assertEquals(
                    3,
                    catalog.figures("build-a", Cancellation.NONE)
                            .join()
                            .original()
                            .sharedWithBuildBytes());
            assertEquals(
                    3,
                    catalog.figures("build-b", Cancellation.NONE).join().original().storedBytes());
            derived.put(List.of(new StoreTypes.PutObject(generated, new byte[] {9, 8, 7, 6})))
                    .join();
            assertEquals(
                    4,
                    catalog.figures("build-a", Cancellation.NONE).join().derived().storedBytes());
            assertEquals(2, store.stats().join().objects());
            var unownedStore = store;
            assertThrows(
                    CompletionException.class,
                    () ->
                            unownedStore
                                    .put(
                                            List.of(
                                                    new StoreTypes.PutObject(
                                                            original, new byte[] {1, 2, 3})))
                                    .join());
            try (var db = new NativeSqlite(store.databasePath(), true)) {
                assertEquals(
                        "view",
                        db.query("SELECT type FROM sqlite_master WHERE name='storage_objects'")
                                .getFirst()[0]);
                assertEquals(
                        0L,
                        db.scalar(
                                "SELECT count(*) FROM sqlite_master WHERE"
                                        + " name='storage_payloads'"));
            }
        } finally {
            store.closeAsync().join();
        }
        store = ObjectStore.openLibrary(folder).join();
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            assertEquals(
                    3,
                    catalog.figures("build-b", Cancellation.NONE).join().original().storedBytes());
            assertArrayEquals(new byte[] {9, 8, 7, 6}, store.read(generated).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void stagedEncodedObjectsPublishWithCapturedOwnerAndDuplicatesAddOwnership() {
        byte[] bytes = new byte[100_003];
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        ObjectStore store = ObjectStore.openLibrary(folder).join();
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            var a =
                    store.forOwner(
                            "a",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            var b =
                    store.forOwner(
                            "b",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            ObjectStaging stage =
                    a.stage(key, bytes.length, StoreTypes.Origin.CDN, Cancellation.NONE).join();
            stage.append(bytes, Cancellation.NONE).join();
            assertFalse(store.has(key).join());
            assertEquals(
                    StoreTypes.PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertEquals(
                    bytes.length,
                    catalog.figures("a", Cancellation.NONE).join().original().storedBytes());
            assertEquals(
                    StoreTypes.PutOutcome.ALREADY_PRESENT,
                    b.put(List.of(new StoreTypes.PutObject(key, bytes)))
                            .join()
                            .getFirst()
                            .outcome());
            assertEquals(
                    bytes.length,
                    catalog.figures("b", Cancellation.NONE).join().original().storedBytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void indexCorruptionDoesNotDeleteAuthoritativeCatalogOrOtherTables() throws Exception {
        var store = ObjectStore.openLibrary(folder).join();
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            catalog.retainOwner("saved", LibraryCatalog.Scope.BUILD).join();
        } finally {
            store.closeAsync().join();
        }
        Path db = folder.resolve("objects.sqlite3");
        try (var connection = new NativeSqlite(db, false)) {
            connection.exec("CREATE TABLE builds_fixture(id TEXT PRIMARY KEY)");
            connection.exec("INSERT INTO builds_fixture VALUES('saved')");
            connection.exec("UPDATE store SET row_check=row_check+1");
            connection.exec("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        assertThrows(CompletionException.class, () -> ObjectStore.openLibrary(folder).join());
        try (var connection = new NativeSqlite(db, true)) {
            assertEquals(1L, connection.scalar("SELECT count(*) FROM builds_fixture"));
            assertEquals(
                    1L, connection.scalar("SELECT count(*) FROM catalog_owners WHERE id='saved'"));
        }
    }

    @Test
    void ownerFailureRollsBackPhysicalPublication() {
        var store = ObjectStore.openLibrary(folder).join();
        ObjectKey key = key(new byte[] {1});
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            catalog.retainOwner("same", LibraryCatalog.Scope.GLOBAL).join();
            var wrong =
                    store.forOwner(
                            "same",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            assertThrows(
                    CompletionException.class,
                    () -> wrong.put(List.of(new StoreTypes.PutObject(key, new byte[] {1}))).join());
            assertFalse(store.has(key).join());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void stagedPublicationCompletionCanCloseSharedStoreWithoutBlockingItsWriter() throws Exception {
        ObjectStore store = ObjectStore.openLibrary(folder).join();
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var catalog = LibraryCatalog.open(store.databasePath()).join()) {
            catalog.retainOwner("build", LibraryCatalog.Scope.BUILD).join();
            byte[] bytes = new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'};
            ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
            var view =
                    store.forOwner(
                            "build",
                            LibraryCatalog.Scope.BUILD,
                            "metadata",
                            LibraryCatalog.Role.ORIGINAL);
            ObjectStaging stage =
                    view.stage(key, bytes.length, StoreTypes.Origin.CDN, Cancellation.NONE).join();
            stage.append(bytes, Cancellation.NONE).join();
            store.writeProbe =
                    (point, ignored) -> {
                        if (point == WritePoint.PACK_SYNCED) {
                            reached.countDown();
                            try {
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Publication was not released");
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(error);
                            }
                        }
                    };
            var published = stage.commit(Cancellation.NONE);
            assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var closed = published.thenRun(() -> store.closeAsync().join());
            release.countDown();
            closed.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            store.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static ObjectKey key(byte[] bytes) {
        return new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
    }
}
