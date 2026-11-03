package com.vibeminer.accounting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LegacyCatalogOwnersTest {
    @TempDir Path directory;

    @Test
    void copiesPagedCanonicalOwnerIntentAndLeavesInventoriesProvisional() throws Exception {
        Path source = directory.resolve("accounting.sqlite");
        UsageIndex legacy = UsageIndex.open(source).join();
        try {
            for (int i = 0; i < 257; i++) {
                legacy.retain("build-" + i, false, "encoding", UsageIndex.Role.BLIZZARD_ENCODED)
                        .join();
            }
            legacy.retain("provider", true, "provider-data", UsageIndex.Role.PROVIDER).join();
        } finally {
            legacy.closeAsync().join();
        }
        byte[] original = Files.readAllBytes(source);
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            LegacyCatalogOwners.copy(source, catalog, Cancellation.NONE).join();
            LegacyCatalogOwners.copy(source, catalog, Cancellation.NONE).join();
            var owners = catalog.owners(null, 1024, Cancellation.NONE).join();
            assertEquals(258, owners.size());
            assertTrue(
                    owners.contains(
                            new LibraryCatalog.Owner("provider", LibraryCatalog.Scope.GLOBAL)));
            assertTrue(
                    owners.contains(
                            new LibraryCatalog.Owner("build-256", LibraryCatalog.Scope.BUILD)));
            var figures = catalog.figures("build-256", Cancellation.NONE).join();
            assertEquals(0, figures.original().storedBytes());
            assertFalse(figures.original().inventoryComplete());
            assertFalse(figures.original().sharingComplete());
        } finally {
            library.closeAsync().join();
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    @Test
    void missingSourceIsNoOpAndCancellationCreatesNoOwners() throws Exception {
        Path source = directory.resolve("missing.sqlite");
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            LegacyCatalogOwners.copy(source, catalog, Cancellation.NONE).join();
            assertFalse(Files.exists(source));
            Cancellation canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    CancellationException.class,
                    () -> LegacyCatalogOwners.copy(source, catalog, canceled).join());
            assertTrue(catalog.owners(null, 1024, Cancellation.NONE).join().isEmpty());
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void unreadableLegacyOwnersFailWithoutFabricatingSuccessfulIntent() throws Exception {
        Path source = directory.resolve("wrong-schema.sqlite");
        try (var connection = new DerivedSqliteConnection(source)) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("CREATE TABLE unrelated(value TEXT)");
                        session.execute("INSERT INTO unrelated VALUES('preserve')");
                        return null;
                    });
        }
        byte[] original = Files.readAllBytes(source);
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            assertThrows(
                    CompletionException.class,
                    () -> LegacyCatalogOwners.copy(source, catalog, Cancellation.NONE).join());
            assertTrue(catalog.owners(null, 1024, Cancellation.NONE).join().isEmpty());
        } finally {
            library.closeAsync().join();
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }
}
