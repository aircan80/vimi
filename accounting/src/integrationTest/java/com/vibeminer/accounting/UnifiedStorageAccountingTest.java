package com.vibeminer.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class UnifiedStorageAccountingTest {
    @TempDir Path directory;

    @Test
    void joinsActualPresenceWithoutCallbacksAndNeverDoubleCountsGlobalOverlap() throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                byte[] bytes = new byte[] {1, 2, 3};
                ObjectKey key =
                        new ObjectKey(
                                ObjectKey.CONFIG, MessageDigest.getInstance("MD5").digest(bytes));
                var reference =
                        new UsageIndex.Resource(
                                "objects",
                                HexFormat.of().formatHex(key.indexBytes()),
                                UsageIndex.Role.BLIZZARD_ENCODED,
                                bytes.length);
                for (String owner : List.of("build-a", "build-b")) {
                    accounting
                            .retain(owner, false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                            .join();
                    accounting
                            .replace(
                                    owner,
                                    "encoding",
                                    "v1",
                                    inventory(List.of(reference)),
                                    neverPresence(),
                                    Cancellation.NONE)
                            .join();
                }
                accounting.retain("provider", true, "shared", UsageIndex.Role.PROVIDER).join();
                accounting
                        .replace(
                                "provider",
                                "shared",
                                "v1",
                                inventory(List.of(reference)),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                assertEquals(0, accounting.figures("build-a").join().storedObjects());
                var writer =
                        library.forOwner(
                                "build-b",
                                LibraryCatalog.Scope.BUILD,
                                "metadata",
                                LibraryCatalog.Role.ORIGINAL);
                writer.put(List.of(new StoreTypes.PutObject(key, bytes))).join();
                var figures = accounting.figures("build-a").join();
                assertEquals(3, figures.original().shared());
                assertEquals(0, figures.original().onlyThisBuild());
                assertEquals(0, figures.original().global());
                assertEquals(3, figures.publishedBytes());
                assertEquals(1, figures.storedObjects());
                assertEquals(2, figures.retainedBuilds());
                accounting.refresh("objects", neverPresence(), Cancellation.NONE).join();
                accounting
                        .update(
                                "objects",
                                List.of(reference.key()),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                writer.delete(List.of(key)).join();
                assertEquals(0, accounting.figures("build-a").join().storedObjects());
            } finally {
                accounting.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void derivedUnknownLengthsUseSharedIndexAndDatabaseSourcesAreNotInventoried() {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                byte[] keyBytes = new byte[16];
                var key = new ObjectKey(ObjectKey.GENERATED, keyBytes);
                library.forOwner(
                                "build",
                                LibraryCatalog.Scope.BUILD,
                                "generated",
                                LibraryCatalog.Role.DERIVED)
                        .put(List.of(new StoreTypes.PutObject(key, new byte[19])))
                        .join();
                accounting
                        .retain("build", false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
                accounting
                        .replace(
                                "build",
                                "encoding",
                                "empty",
                                inventory(List.of()),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                accounting.retain("build", false, "tables", UsageIndex.Role.DERIVED).join();
                accounting
                        .replace(
                                "build",
                                "tables",
                                "v1",
                                inventory(
                                        List.of(
                                                new UsageIndex.Resource(
                                                        "generated",
                                                        HexFormat.of().formatHex(keyBytes),
                                                        UsageIndex.Role.DERIVED,
                                                        -1))),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                accounting.retain("build", false, "items-db", UsageIndex.Role.DATABASE).join();
                accounting
                        .replace(
                                "build",
                                "items-db",
                                "v1",
                                () -> {
                                    throw new AssertionError(
                                            "Relational tables are not object inventories");
                                },
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                var figures = accounting.figures("build").join();
                assertEquals(19, figures.derived().onlyThisBuild());
                assertEquals(0, figures.derived().databaseBytes());
                assertTrue(figures.derivedComplete());
            } finally {
                accounting.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void failedProducerClosesCursorAndKeepsPriorGeneration() {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                accounting
                        .retain("build", false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
                accounting
                        .replace(
                                "build",
                                "encoding",
                                "v1",
                                inventory(List.of()),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                AtomicBoolean closed = new AtomicBoolean();
                assertThrows(
                        CompletionException.class,
                        () ->
                                accounting
                                        .replace(
                                                "build",
                                                "encoding",
                                                "v2",
                                                () ->
                                                        new UsageIndex.Cursor() {
                                                            @Override
                                                            public List<UsageIndex.Resource> next(
                                                                    Cancellation token)
                                                                    throws Exception {
                                                                throw new java.io.IOException(
                                                                        "Producer unavailable");
                                                            }

                                                            @Override
                                                            public void close() {
                                                                closed.set(true);
                                                            }
                                                        },
                                                neverPresence(),
                                                Cancellation.NONE)
                                        .join());
                assertTrue(closed.get());
                assertTrue(accounting.current("build", "encoding", "v1").join());
                assertFalse(accounting.current("build", "encoding", "v2").join());
            } finally {
                accounting.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void coveragePersistsAndCompletionCallbackCanCloseBorrowedFacade() throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            accounting.saveCoverage("build", "enUS", "names-v1", 100, 71, Cancellation.NONE).join();
            accounting
                    .coverage("build", "enUS", "names-v1")
                    .thenRun(() -> accounting.closeAsync().join())
                    .get(5, TimeUnit.SECONDS);
            UnifiedStorageAccounting reopened =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                assertEquals(
                        new UsageIndex.Coverage(100, 71),
                        reopened.coverage("build", "enUS", "names-v1").join());
                assertEquals(null, reopened.coverage("build", "enUS", "other").join());
                catalog.retainOwner("still-open", LibraryCatalog.Scope.BUILD).join();
            } finally {
                reopened.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void refusesFutureCoverageSchemaBeforeChangingIt() throws Exception {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            accounting.closeAsync().join();
            try (var connection =
                    new com.vibeminer.store.DerivedSqliteConnection(library.databasePath())) {
                connection.run(
                        Cancellation.NONE,
                        true,
                        session -> {
                            session.execute("UPDATE catalog_reporting_metadata SET version=2");
                            return null;
                        });
            }
            assertThrows(
                    CompletionException.class,
                    () ->
                            UnifiedStorageAccounting.open(library.databasePath(), catalog, library)
                                    .join());
            try (var connection =
                    com.vibeminer.store.DerivedSqliteConnection.openReadOnly(
                            library.databasePath())) {
                assertEquals(
                        2L,
                        connection
                                .run(
                                        Cancellation.NONE,
                                        false,
                                        session ->
                                                (Long)
                                                        session.query(
                                                                        "SELECT version FROM"
                                                                                + " catalog_reporting_metadata")
                                                                .getFirst()[0])
                                .longValue());
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void missingDerivedReferenceAcquiresActualBytesAfterPublicationWithoutReindexing() {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                ObjectKey key = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
                accounting
                        .retain("build", false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
                accounting
                        .replace(
                                "build",
                                "encoding",
                                "v1",
                                inventory(List.of()),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                accounting.retain("build", false, "artifacts", UsageIndex.Role.DERIVED).join();
                accounting
                        .replace(
                                "build",
                                "artifacts",
                                "v1",
                                inventory(
                                        List.of(
                                                new UsageIndex.Resource(
                                                        "generated",
                                                        key.hex(),
                                                        UsageIndex.Role.DERIVED,
                                                        -1))),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                assertEquals(0, accounting.figures("build").join().derived().onlyThisBuild());
                library.forOwner(
                                "build",
                                LibraryCatalog.Scope.BUILD,
                                "generated",
                                LibraryCatalog.Role.DERIVED)
                        .put(List.of(new StoreTypes.PutObject(key, new byte[19])))
                        .join();
                assertEquals(19, accounting.figures("build").join().derived().onlyThisBuild());
                assertTrue(accounting.current("build", "artifacts", "v1").join());
            } finally {
                accounting.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void cursorCloseFailureDoesNotPublishReplacementAndClosesOnlyOnce() {
        ObjectStore library = ObjectStore.openLibrary(directory).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            UnifiedStorageAccounting accounting =
                    UnifiedStorageAccounting.open(library.databasePath(), catalog, library).join();
            try {
                accounting
                        .retain("build", false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
                accounting
                        .replace(
                                "build",
                                "encoding",
                                "v1",
                                inventory(List.of()),
                                neverPresence(),
                                Cancellation.NONE)
                        .join();
                var closes = new java.util.concurrent.atomic.AtomicInteger();
                assertThrows(
                        CompletionException.class,
                        () ->
                                accounting
                                        .replace(
                                                "build",
                                                "encoding",
                                                "v2",
                                                () ->
                                                        new UsageIndex.Cursor() {
                                                            @Override
                                                            public List<UsageIndex.Resource> next(
                                                                    Cancellation token) {
                                                                return List.of();
                                                            }

                                                            @Override
                                                            public void close() {
                                                                closes.incrementAndGet();
                                                                throw new IllegalStateException(
                                                                        "Producer close failed");
                                                            }
                                                        },
                                                neverPresence(),
                                                Cancellation.NONE)
                                        .join());
                assertEquals(1, closes.get());
                assertTrue(accounting.current("build", "encoding", "v1").join());
                assertFalse(accounting.current("build", "encoding", "v2").join());
            } finally {
                accounting.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    private static UsageIndex.Inventory inventory(List<UsageIndex.Resource> resources) {
        return () ->
                new UsageIndex.Cursor() {
                    private boolean done;

                    @Override
                    public List<UsageIndex.Resource> next(Cancellation token) {
                        if (done) {
                            return List.of();
                        }
                        done = true;
                        return resources;
                    }
                };
    }

    private static UsageIndex.Presence neverPresence() {
        return (resources, token) -> {
            throw new AssertionError("Presence mirror must not be queried");
        };
    }
}
