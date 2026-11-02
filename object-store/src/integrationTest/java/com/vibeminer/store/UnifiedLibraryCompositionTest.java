package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual payload/catalog composition: physical presence is joined, never mirrored by a refresh. */
@Timeout(20)
final class UnifiedLibraryCompositionTest {
    @TempDir Path directory;

    @Test
    void downloadsAndDeletesImmediatelyChangeSharedBuildFiguresAndSurviveReopen() {
        Path path = directory.resolve("library.sqlite");
        byte[] uniqueBytes = new byte[] {1, 2, 3};
        byte[] sharedBytes = new byte[] {4, 5};
        byte[] pendingBytes = new byte[7];
        byte[] derivedBytes = new byte[] {8, 9, 10, 11};
        ObjectKey unique = key(uniqueBytes);
        ObjectKey shared = key(sharedBytes);
        ObjectKey pending = key(pendingBytes);
        ObjectKey derived = new ObjectKey(ObjectKey.GENERATED, Format.hash("MD5", derivedBytes));
        SqliteObjectStore library = SqliteObjectStore.open(path).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(path).join()) {
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
            catalog.expect(
                            "global",
                            LibraryCatalog.Scope.GLOBAL,
                            "provider",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            catalog.expect(
                            "build-a",
                            LibraryCatalog.Scope.BUILD,
                            "tables",
                            LibraryCatalog.Role.DERIVED)
                    .join();
            replace(
                    catalog,
                    "build-a",
                    "encoding",
                    List.of(ref(unique, 3), ref(shared, 2), ref(pending, 7)));
            replace(catalog, "build-b", "encoding", List.of(ref(shared, 2)));
            replace(catalog, "global", "provider", List.of(ref(shared, 2)));
            replace(catalog, "build-a", "tables", List.of(ref(derived, 4)));
            assertEquals(
                    0,
                    catalog.figures("build-a", Cancellation.NONE).join().original().storedBytes());
            library.put(
                            List.of(
                                    new StoreTypes.PutObject(unique, uniqueBytes),
                                    new StoreTypes.PutObject(shared, sharedBytes),
                                    new StoreTypes.PutObject(derived, derivedBytes)))
                    .join();
            var figures = catalog.figures("build-a", Cancellation.NONE).join();
            assertEquals(12, figures.original().declaredBytes());
            assertEquals(5, figures.original().storedBytes());
            assertEquals(3, figures.original().uniqueToBuildBytes());
            assertEquals(2, figures.original().sharedWithBuildBytes());
            assertEquals(2, figures.original().sharedWithGlobalBytes());
            assertEquals(4, figures.derived().storedBytes());
            assertTrue(figures.original().sharingComplete());
            library.put(List.of(new StoreTypes.PutObject(pending, pendingBytes))).join();
            assertEquals(
                    12,
                    catalog.figures("build-a", Cancellation.NONE).join().original().storedBytes());
            library.delete(List.of(unique)).join();
            assertEquals(
                    9,
                    catalog.figures("build-a", Cancellation.NONE).join().original().storedBytes());
        } finally {
            library.closeAsync().join();
        }
        library = SqliteObjectStore.open(path).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(path).join()) {
            assertEquals(
                    9,
                    catalog.figures("build-a", Cancellation.NONE).join().original().storedBytes());
            assertEquals(
                    4,
                    catalog.figures("build-a", Cancellation.NONE).join().derived().storedBytes());
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void stalledInventoryProducerDoesNotHoldWriterOrExposeIncompleteReplacement() throws Exception {
        Path path = directory.resolve("library.sqlite");
        var library = SqliteObjectStore.open(path).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(path).join()) {
            byte[] oldBytes = new byte[] {1, 2, 3};
            byte[] newBytes = new byte[] {4, 5};
            ObjectKey oldKey = key(oldBytes);
            ObjectKey newKey = key(newBytes);
            catalog.expect(
                            "build",
                            LibraryCatalog.Scope.BUILD,
                            "encoding",
                            LibraryCatalog.Role.ORIGINAL)
                    .join();
            replace(catalog, "build", "encoding", List.of(ref(oldKey, 3)));
            library.put(List.of(new StoreTypes.PutObject(oldKey, oldBytes))).join();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = new AtomicBoolean(true);
            var replacement =
                    catalog.replace(
                            "build",
                            "encoding",
                            "next",
                            (limit, token) -> {
                                if (first.getAndSet(false)) {
                                    return List.of(ref(newKey, 2));
                                }
                                entered.countDown();
                                if (!release.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Inventory not released");
                                }
                                return List.of();
                            },
                            Cancellation.NONE);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                library.put(List.of(new StoreTypes.PutObject(newKey, newBytes)))
                        .get(5, TimeUnit.SECONDS);
                assertEquals(
                        3,
                        catalog.figures("build", Cancellation.NONE)
                                .get(5, TimeUnit.SECONDS)
                                .original()
                                .storedBytes());
            } finally {
                release.countDown();
            }
            replacement.get(5, TimeUnit.SECONDS);
            assertEquals(
                    2, catalog.figures("build", Cancellation.NONE).join().original().storedBytes());
        } finally {
            library.closeAsync().join();
        }
    }

    private static ObjectKey key(byte[] bytes) {
        return new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
    }

    private static LibraryCatalog.Reference ref(ObjectKey key, long length) {
        return new LibraryCatalog.Reference(key, length);
    }

    private static void replace(
            LibraryCatalog catalog,
            String owner,
            String source,
            List<LibraryCatalog.Reference> references) {
        var first = new AtomicBoolean(true);
        catalog.replace(
                        owner,
                        source,
                        "v1",
                        (limit, token) -> first.getAndSet(false) ? references : List.of(),
                        Cancellation.NONE)
                .join();
    }
}
