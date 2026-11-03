package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LibraryCatalogTest {
    @TempDir Path directory;

    @Test
    void deduplicatesSourcesAndSeparatesBuildSharingFromGlobalOverlap() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        var unique = original(100, 1);
        var shared = original(200, 2);
        var unavailable = original(300, 3);
        try (var catalog = LibraryCatalog.open(path).join()) {
            store.put(List.of(unique, shared)).join();
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "a", "metadata", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "b", "encoding", LibraryCatalog.Role.ORIGINAL);
            catalog.expect(
                            "provider",
                            LibraryCatalog.Scope.GLOBAL,
                            "archive",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            replace(catalog, "a", "encoding", "v1", unique, shared, unavailable);
            replace(catalog, "a", "metadata", "v1", unique);
            replace(catalog, "b", "encoding", "v1", shared);
            replace(catalog, "provider", "archive", "v1", unique);
            LibraryCatalog.Usage usage = catalog.figures("a", Cancellation.NONE).join().original();
            assertEquals(3, usage.referencedObjects());
            assertEquals(600, usage.declaredBytes());
            assertEquals(2, usage.storedObjects());
            assertEquals(300, usage.storedBytes());
            assertEquals(100, usage.uniqueToBuildBytes());
            assertEquals(200, usage.sharedWithBuildBytes());
            assertEquals(100, usage.sharedWithGlobalBytes());
            assertTrue(usage.inventoryComplete());
            assertTrue(usage.sharingComplete());
            // Presence changes are read directly from the unified payload catalog.
            store.put(List.of(unavailable)).join();
            assertEquals(
                    600, catalog.figures("a", Cancellation.NONE).join().original().storedBytes());
            store.delete(List.of(shared.key())).join();
            assertEquals(
                    400, catalog.figures("a", Cancellation.NONE).join().original().storedBytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void accountsDerivedObjectsSeparatelyAndDoesNotDeletePayloadsWhenForgotten() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        var derived =
                new StoreTypes.PutObject(
                        new ObjectKey(ObjectKey.GENERATED, new byte[16]), new byte[42]);
        try (var catalog = LibraryCatalog.open(path).join()) {
            store.put(List.of(derived)).join();
            expect(catalog, "a", "generated", LibraryCatalog.Role.DERIVED);
            expect(catalog, "b", "generated", LibraryCatalog.Role.DERIVED);
            replace(catalog, "a", "generated", "v1", derived);
            replace(catalog, "b", "generated", "v1", derived);
            var usage = catalog.figures("a", Cancellation.NONE).join().derived();
            assertEquals(42, usage.storedBytes());
            assertEquals(42, usage.sharedWithBuildBytes());
            assertEquals(0, usage.uniqueToBuildBytes());
            assertEquals(
                    0, catalog.figures("a", Cancellation.NONE).join().original().storedBytes());
            catalog.forget("b", Cancellation.NONE).join();
            assertEquals(
                    42,
                    catalog.figures("a", Cancellation.NONE).join().derived().uniqueToBuildBytes());
            catalog.reclaim(Cancellation.NONE).join();
            assertTrue(store.has(derived.key()).join());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void unknownRetainedBuildMakesUniqueFiguresProvisionalUntilItsInventoryCompletes() {
        Path path = initialized();
        var object = original(7, 1);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            replace(catalog, "a", "encoding", "v1", object);
            catalog.retainOwner("unknown", LibraryCatalog.Scope.BUILD).join();
            assertFalse(
                    catalog.figures("a", Cancellation.NONE).join().original().sharingComplete());
            expect(catalog, "unknown", "encoding", LibraryCatalog.Role.ORIGINAL);
            assertFalse(
                    catalog.figures("unknown", Cancellation.NONE)
                            .join()
                            .original()
                            .inventoryComplete());
            replace(catalog, "unknown", "encoding", "empty");
            assertTrue(catalog.figures("a", Cancellation.NONE).join().original().sharingComplete());
        }
    }

    @Test
    void replacementRemainsInvisibleAndProducerCanPerformOtherWritesBeforePublication() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        var old = original(50, 1);
        var next = original(100, 2);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            store.put(List.of(old)).join();
            replace(catalog, "a", "encoding", "old", old);
            AtomicInteger pages = new AtomicInteger();
            catalog.replace(
                            "a",
                            "encoding",
                            "next",
                            (limit, token) -> {
                                if (pages.getAndIncrement() == 0) {
                                    return List.of(reference(next));
                                }
                                assertEquals(
                                        50,
                                        catalog.figures("a", Cancellation.NONE)
                                                .join()
                                                .original()
                                                .storedBytes());
                                assertTrue(catalog.current("a", "encoding", "old").join());
                                // These awaited producer operations would deadlock/time out inside
                                // a writer txn.
                                catalog.retainOwner("other", LibraryCatalog.Scope.GLOBAL).join();
                                store.put(List.of(next)).join();
                                return List.of();
                            },
                            Cancellation.NONE)
                    .join();
            assertEquals(
                    100, catalog.figures("a", Cancellation.NONE).join().original().storedBytes());
            assertTrue(catalog.current("a", "encoding", "next").join());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void failedAndCanceledImportsPreservePreviousGenerationAndCanBeRetried() {
        Path path = initialized();
        var old = original(50, 1);
        var next = original(100, 2);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            replace(catalog, "a", "encoding", "old", old);
            AtomicInteger calls = new AtomicInteger();
            assertThrows(
                    CompletionException.class,
                    () ->
                            catalog.replace(
                                            "a",
                                            "encoding",
                                            "bad",
                                            (limit, token) -> {
                                                if (calls.getAndIncrement() == 0) {
                                                    return List.of(reference(next));
                                                }
                                                throw new IOException("Synthetic producer failure");
                                            },
                                            Cancellation.NONE)
                                    .join());
            assertTrue(catalog.current("a", "encoding", "old").join());
            assertEquals(
                    50, catalog.figures("a", Cancellation.NONE).join().original().declaredBytes());
            Cancellation cancellation = new Cancellation();
            calls.set(0);
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () ->
                            catalog.replace(
                                            "a",
                                            "encoding",
                                            "cancel",
                                            (limit, token) -> {
                                                if (calls.getAndIncrement() == 0) {
                                                    return List.of(reference(next));
                                                }
                                                cancellation.cancel();
                                                return List.of();
                                            },
                                            cancellation)
                                    .join());
            assertTrue(catalog.current("a", "encoding", "old").join());
            replace(catalog, "a", "encoding", "next", next);
            assertEquals(
                    100, catalog.figures("a", Cancellation.NONE).join().original().declaredBytes());
        }
    }

    @Test
    void reopensReceiptsAndRetainedOwnerIntentAndBoundsRetiredCleanup() {
        Path path = initialized();
        var object = original(10, 1);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            replace(catalog, "a", "encoding", "v1", object);
            catalog.retainOwner("unknown", LibraryCatalog.Scope.BUILD).join();
        }
        try (var catalog = LibraryCatalog.open(path).join()) {
            assertTrue(catalog.current("a", "encoding", "v1").join());
            assertEquals(
                    List.of(new LibraryCatalog.Owner("a", LibraryCatalog.Scope.BUILD)),
                    catalog.owners(null, 1, Cancellation.NONE).join());
            assertEquals(
                    List.of(new LibraryCatalog.Owner("unknown", LibraryCatalog.Scope.BUILD)),
                    catalog.owners("a", 1, Cancellation.NONE).join());
            assertFalse(
                    catalog.figures("a", Cancellation.NONE).join().original().sharingComplete());
            replace(catalog, "a", "encoding", "empty");
            assertEquals(1, catalog.reclaim(Cancellation.NONE).join());
            assertEquals(0, catalog.reclaim(Cancellation.NONE).join());
            assertTrue(catalog.current("a", "encoding", "empty").join());
        }
    }

    @Test
    void rejectsOversizedPagesAndConflictingDuplicateLengthsWithoutPublishing() {
        Path path = initialized();
        var object = original(10, 1);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            assertThrows(
                    CompletionException.class,
                    () ->
                            catalog.replace(
                                            "a",
                                            "encoding",
                                            "big",
                                            (limit, token) ->
                                                    java.util.Collections.nCopies(
                                                            limit + 1, reference(object)),
                                            Cancellation.NONE)
                                    .join());
            AtomicInteger calls = new AtomicInteger();
            assertThrows(
                    CompletionException.class,
                    () ->
                            catalog.replace(
                                            "a",
                                            "encoding",
                                            "conflict",
                                            (limit, token) -> {
                                                if (calls.getAndIncrement() == 0) {
                                                    return List.of(
                                                            reference(object),
                                                            new LibraryCatalog.Reference(
                                                                    object.key(), 11));
                                                }
                                                return List.of();
                                            },
                                            Cancellation.NONE)
                                    .join());
            assertFalse(
                    catalog.figures("a", Cancellation.NONE).join().original().inventoryComplete());
            replace(catalog, "a", "encoding", "good", object);
            assertEquals(
                    10, catalog.figures("a", Cancellation.NONE).join().original().declaredBytes());
        }
    }

    @Test
    void independentReadDoesNotReserveWriterAndSecondCatalogWriterIsExcluded() {
        Path path = initialized();
        try (var catalog = LibraryCatalog.open(path).join()) {
            assertThrows(CompletionException.class, () -> LibraryCatalog.open(path).join());
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            replace(catalog, "a", "encoding", "empty");
            try (var writer = new NativeSqlite(path, false)) {
                writer.exec("BEGIN IMMEDIATE");
                try {
                    assertEquals(
                            0,
                            catalog.figures("a", Cancellation.NONE)
                                    .join()
                                    .original()
                                    .storedBytes());
                } finally {
                    writer.exec("ROLLBACK");
                }
            }
        }
    }

    @Test
    void reclaimsOldInventoriesInBoundedPagesAndRecoversAbandonedPendingGeneration() {
        Path path = initialized();
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            AtomicInteger page = new AtomicInteger();
            catalog.replace(
                            "a",
                            "encoding",
                            "large",
                            (limit, token) -> {
                                assertEquals(LibraryCatalog.PAGE_LIMIT, limit);
                                int number = page.getAndIncrement();
                                if (number >= 2) {
                                    return List.of();
                                }
                                int count = number == 0 ? limit : 1;
                                var references =
                                        new java.util.ArrayList<LibraryCatalog.Reference>();
                                for (int i = 0; i < count; i++) {
                                    byte[] key = new byte[16];
                                    Format.le(key).putInt(number * limit + i);
                                    references.add(
                                            new LibraryCatalog.Reference(
                                                    new ObjectKey(ObjectKey.CONFIG, key), 10));
                                }
                                return references;
                            },
                            Cancellation.NONE)
                    .join();
            assertEquals(
                    1025,
                    catalog.figures("a", Cancellation.NONE).join().original().referencedObjects());
            replace(catalog, "a", "encoding", "empty");
            assertEquals(1024, catalog.reclaim(Cancellation.NONE).join());
            assertEquals(1, catalog.reclaim(Cancellation.NONE).join());
        }
        // An acknowledged import batch can remain on disk after a process dies before publish.
        try (var connection = new NativeSqlite(path, false)) {
            connection.exec(
                    "INSERT INTO catalog_generations"
                            + " VALUES('abandoned','a','encoding','unfinished','INCOMPLETE')");
            connection.exec(
                    "UPDATE catalog_expected SET pending='abandoned' WHERE owner='a' AND"
                            + " source='encoding'");
            connection.exec(
                    "INSERT INTO catalog_refs VALUES('abandoned',?,'ORIGINAL',99)",
                    new ObjectKey(ObjectKey.CONFIG, new byte[16]).indexBytes());
        }
        try (var catalog = LibraryCatalog.open(path).join()) {
            assertTrue(catalog.current("a", "encoding", "empty").join());
            assertFalse(catalog.current("a", "encoding", "unfinished").join());
            assertEquals(
                    0,
                    catalog.figures("a", Cancellation.NONE).join().original().referencedObjects());
            assertEquals(1, catalog.reclaim(Cancellation.NONE).join());
            replace(catalog, "a", "encoding", "restart");
            assertTrue(catalog.current("a", "encoding", "restart").join());
        }
    }

    @Test
    void closeDrainsAcceptedInventoryAndRejectsNewWork() throws Exception {
        Path path = initialized();
        LibraryCatalog catalog = LibraryCatalog.open(path).join();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            var replacement =
                    catalog.replace(
                            "a",
                            "encoding",
                            "done",
                            (limit, token) -> {
                                entered.countDown();
                                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "Test did not release producer");
                                }
                                return List.of();
                            },
                            Cancellation.NONE);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var close = catalog.closeAsync();
            assertFalse(close.isDone());
            assertThrows(
                    CompletionException.class,
                    () -> catalog.figures("a", Cancellation.NONE).join());
            release.countDown();
            replacement.join();
            close.join();
        } finally {
            release.countDown();
            catalog.close();
        }
        try (var reopened = LibraryCatalog.open(path).join()) {
            assertTrue(reopened.current("a", "encoding", "done").join());
        }
    }

    @Test
    void inconsistentCrossSourceMetadataDoesNotBecomeAMisleadingSize() {
        Path path = initialized();
        var object = original(10, 1);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "first", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "a", "second", LibraryCatalog.Role.ORIGINAL);
            replace(catalog, "a", "first", "v1", object);
            AtomicInteger pages = new AtomicInteger();
            catalog.replace(
                            "a",
                            "second",
                            "v1",
                            (limit, token) ->
                                    pages.getAndIncrement() == 0
                                            ? List.of(
                                                    new LibraryCatalog.Reference(object.key(), 11))
                                            : List.of(),
                            Cancellation.NONE)
                    .join();
            assertThrows(
                    CompletionException.class,
                    () -> catalog.figures("a", Cancellation.NONE).join());
        }
    }

    @Test
    void refusesUnrelatedTableCompatibleDatabaseWithoutChangingIt() throws Exception {
        Path path = directory.resolve("foreign.sqlite");
        try (var connection = new NativeSqlite(path, false)) {
            connection.exec("CREATE TABLE storage_objects(k BLOB,length INTEGER,role TEXT)");
            connection.exec("CREATE TABLE storage_metadata(version INTEGER)");
            connection.exec("INSERT INTO storage_metadata VALUES(1)");
        }
        unchangedRefusal(path);
    }

    @Test
    void refusesFutureBackendVersionWithoutChangingIt() throws Exception {
        Path path = initialized();
        try (var connection = new NativeSqlite(path, false)) {
            connection.exec("UPDATE storage_metadata SET version=2");
        }
        unchangedRefusal(path);
    }

    @Test
    void refusesFutureAndUnversionedCatalogWithoutChangingIt() throws Exception {
        Path path = initialized();
        LibraryCatalog.open(path).join().close();
        try (var connection = new NativeSqlite(path, false)) {
            connection.exec("UPDATE catalog_metadata SET version=2");
        }
        unchangedRefusal(path);
        try (var connection = new NativeSqlite(path, false)) {
            connection.exec("DROP TABLE catalog_metadata");
        }
        unchangedRefusal(path);
    }

    @Test
    void openCompletionCanSynchronouslyCloseTheCatalog() throws Exception {
        Path path = initialized();
        java.util.concurrent.CompletableFuture<Void> completion;
        try (var blocker = new NativeSqlite(path, false)) {
            blocker.exec("BEGIN IMMEDIATE");
            try {
                completion =
                        LibraryCatalog.open(path)
                                .thenAccept(catalog -> catalog.closeAsync().join());
            } finally {
                blocker.exec("ROLLBACK");
            }
        }
        completion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        LibraryCatalog.open(path).join().close();
    }

    @Test
    void writeCompletionCanSynchronouslyCloseTheCatalog() throws Exception {
        Path path = initialized();
        LibraryCatalog catalog = LibraryCatalog.open(path).join();
        java.util.concurrent.CompletableFuture<Void> completion;
        try (var blocker = new NativeSqlite(path, false)) {
            blocker.exec("BEGIN IMMEDIATE");
            try {
                completion =
                        catalog.retainOwner("a", LibraryCatalog.Scope.BUILD)
                                .thenRun(() -> catalog.closeAsync().join());
            } finally {
                blocker.exec("ROLLBACK");
            }
        }
        completion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        LibraryCatalog.open(path).join().close();
    }

    @Test
    void readCompletionCanSynchronouslyCloseTheCatalog() throws Exception {
        Path path = initialized();
        LibraryCatalog catalog = LibraryCatalog.open(path).join();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            catalog.retainOwner("a", LibraryCatalog.Scope.BUILD).join();
            catalog.beforeReadProbe =
                    () -> {
                        entered.countDown();
                        await(release);
                    };
            var completion =
                    catalog.figures("a", Cancellation.NONE)
                            .thenRun(() -> catalog.closeAsync().join());
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            completion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            catalog.closeAsync();
        }
        LibraryCatalog.open(path).join().close();
    }

    @Test
    void replacementCompletionCanSynchronouslyCloseTheCatalog() throws Exception {
        Path path = initialized();
        LibraryCatalog catalog = LibraryCatalog.open(path).join();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            expect(catalog, "a", "encoding", LibraryCatalog.Role.ORIGINAL);
            var completion =
                    catalog.replace(
                                    "a",
                                    "encoding",
                                    "v1",
                                    (limit, token) -> {
                                        entered.countDown();
                                        await(release);
                                        return List.of();
                                    },
                                    Cancellation.NONE)
                            .thenRun(() -> catalog.closeAsync().join());
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            completion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            catalog.closeAsync();
        }
        LibraryCatalog.open(path).join().close();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release worker");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static void unchangedRefusal(Path path) throws Exception {
        byte[] before = java.nio.file.Files.readAllBytes(path);
        List<String> schema;
        try (var connection = new NativeSqlite(path, true)) {
            schema =
                    connection
                            .query(
                                    "SELECT name||':'||coalesce(sql,'') FROM sqlite_master ORDER BY"
                                            + " name")
                            .stream()
                            .map(row -> (String) row[0])
                            .toList();
        }
        CompletionException error =
                assertThrows(CompletionException.class, () -> LibraryCatalog.open(path).join());
        assertEquals("OBJECT_STORE_FORMAT", ((StoreException) error.getCause()).code());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                before, java.nio.file.Files.readAllBytes(path));
        try (var connection = new NativeSqlite(path, true)) {
            assertEquals(
                    schema,
                    connection
                            .query(
                                    "SELECT name||':'||coalesce(sql,'') FROM sqlite_master ORDER BY"
                                            + " name")
                            .stream()
                            .map(row -> (String) row[0])
                            .toList());
        }
    }

    @Test
    void largestSourceNeedNotBeFirstAndSecondarySourcesMergeOverlapsAndNewKeys() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        var first = original(10, 1);
        var second = original(20, 2);
        var third = original(30, 3);
        var extra = original(40, 4);
        var derived =
                new StoreTypes.PutObject(
                        new ObjectKey(ObjectKey.GENERATED, new byte[16]), new byte[5]);
        try (var catalog = LibraryCatalog.open(path).join()) {
            expect(catalog, "a", "a-small-first", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "a", "z-large-last", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "a", "b-secondary", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "a", "derived", LibraryCatalog.Role.DERIVED);
            replace(catalog, "a", "a-small-first", "v1", first, extra);
            replace(catalog, "a", "z-large-last", "v1", first, second, third);
            replace(catalog, "a", "b-secondary", "v1", extra);
            replace(catalog, "a", "derived", "v1", derived);
            store.put(List.of(first, third, derived)).join();
            var figures = catalog.figures("a", Cancellation.NONE).join();
            assertEquals(4, figures.original().referencedObjects());
            assertEquals(100, figures.original().declaredBytes());
            assertEquals(40, figures.original().storedBytes());
            assertEquals(40, figures.original().uniqueToBuildBytes());
            assertEquals(1, figures.derived().referencedObjects());
            assertEquals(5, figures.derived().storedBytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void requiresSqliteVersionSupportingMaterializedOwnershipQueries() {
        LibraryCatalog.requireSqliteVersion("3.35.0");
        LibraryCatalog.requireSqliteVersion("3.51.2");
        LibraryCatalog.requireSqliteVersion("4.0.0");
        for (String version :
                new String[] {"3.34.9", "2.9.0", "3.999999999999999999.0", "unrecognized"}) {
            StoreException error =
                    assertThrows(
                            StoreException.class,
                            () -> LibraryCatalog.requireSqliteVersion(version));
            assertEquals("OBJECT_STORE_NATIVE_LIBRARY", error.code());
            assertTrue(error.getMessage().contains("SQLite 3.35"));
        }
    }

    @Test
    void unknownLengthsMergeWithKnownButDoNotHideConflictingPositiveSecondarySources() {
        Path path = initialized();
        try (var catalog = LibraryCatalog.open(path).join()) {
            var target = original(19, 1);
            var other = original(11, 2);
            var last = original(13, 3);
            expect(catalog, "build", "principal", LibraryCatalog.Role.ORIGINAL);
            expect(catalog, "build", "known", LibraryCatalog.Role.ORIGINAL);
            AtomicInteger page = new AtomicInteger();
            catalog.replace(
                            "build",
                            "principal",
                            "v1",
                            (limit, token) ->
                                    page.getAndIncrement() == 0
                                            ? List.of(
                                                    new LibraryCatalog.Reference(target.key(), 0),
                                                    reference(other),
                                                    reference(last))
                                            : List.of(),
                            Cancellation.NONE)
                    .join();
            replace(catalog, "build", "known", "v1", target);
            assertEquals(
                    43,
                    catalog.figures("build", Cancellation.NONE).join().original().declaredBytes());
            expect(catalog, "build", "conflict", LibraryCatalog.Role.ORIGINAL);
            page.set(0);
            catalog.replace(
                            "build",
                            "conflict",
                            "v1",
                            (limit, token) ->
                                    page.getAndIncrement() == 0
                                            ? List.of(
                                                    new LibraryCatalog.Reference(target.key(), 20))
                                            : List.of(),
                            Cancellation.NONE)
                    .join();
            assertThrows(
                    CompletionException.class,
                    () -> catalog.figures("build", Cancellation.NONE).join());
        }
    }

    @Test
    void duplicateUnknownAndKnownLengthsCoalesceInsideOneInventory() {
        Path path = initialized();
        try (var catalog = LibraryCatalog.open(path).join()) {
            var value = original(19, 1);
            expect(catalog, "build", "encoding", LibraryCatalog.Role.ORIGINAL);
            AtomicInteger page = new AtomicInteger();
            catalog.replace(
                            "build",
                            "encoding",
                            "v1",
                            (limit, token) ->
                                    page.getAndIncrement() == 0
                                            ? List.of(
                                                    new LibraryCatalog.Reference(value.key(), 0),
                                                    reference(value),
                                                    new LibraryCatalog.Reference(value.key(), 0))
                                            : List.of(),
                            Cancellation.NONE)
                    .join();
            assertEquals(
                    19,
                    catalog.figures("build", Cancellation.NONE).join().original().declaredBytes());
        }
    }

    private Path initialized() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore.open(path).join().closeAsync().join();
        return path;
    }

    private static void expect(
            LibraryCatalog catalog, String owner, String source, LibraryCatalog.Role role) {
        catalog.expect(owner, LibraryCatalog.Scope.BUILD, source, role).join();
    }

    private static void replace(
            LibraryCatalog catalog,
            String owner,
            String source,
            String version,
            StoreTypes.PutObject... objects) {
        AtomicInteger calls = new AtomicInteger();
        catalog.replace(
                        owner,
                        source,
                        version,
                        (limit, token) ->
                                calls.getAndIncrement() == 0
                                        ? java.util.Arrays.stream(objects)
                                                .map(LibraryCatalogTest::reference)
                                                .toList()
                                        : List.of(),
                        Cancellation.NONE)
                .join();
    }

    private static LibraryCatalog.Reference reference(StoreTypes.PutObject object) {
        return new LibraryCatalog.Reference(object.key(), object.declaredLength());
    }

    private static StoreTypes.PutObject original(int length, int seed) {
        byte[] bytes = new byte[length];
        new java.util.Random(seed).nextBytes(bytes);
        return new StoreTypes.PutObject(
                new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes)), bytes);
    }
}
