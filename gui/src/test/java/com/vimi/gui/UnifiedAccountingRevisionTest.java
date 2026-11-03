package com.vimi.gui;

import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Native accounting invalidation contract; no JavaFX toolkit or live data is needed. */
public final class UnifiedAccountingRevisionTest {
    private UnifiedAccountingRevisionTest() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-accounting-revision-");
        ObjectStore library = ObjectStore.openLibrary(directory.resolve("library")).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            byte[] bytes = new byte[] {4, 7, 9};
            ObjectKey key =
                    new ObjectKey(
                            ObjectKey.GENERATED, MessageDigest.getInstance("MD5").digest(bytes));
            library.forOwner(
                            "build-a",
                            LibraryCatalog.Scope.BUILD,
                            "generated",
                            LibraryCatalog.Role.DERIVED)
                    .put(List.of(new StoreTypes.PutObject(key, bytes)))
                    .join();
            catalog.retainOwner("build-b", LibraryCatalog.Scope.BUILD).join();
            OverviewAccounting overview =
                    OverviewAccounting.openUnified(directory, catalog, library);
            try {
                awaitRevision(overview, 0);
                long before = overview.revision();
                long count = library.stats().join().objects();
                var previous = overview.index.figures("build-b").join();
                require(
                        previous.derived().shared() == 0,
                        "Second build starts with no owned bytes");
                library.forOwner(
                                "build-b",
                                LibraryCatalog.Scope.BUILD,
                                "generated",
                                LibraryCatalog.Role.DERIVED)
                        .read(key)
                        .join();
                require(library.stats().join().objects() == count, "Cached read adds no payload");
                awaitRevision(overview, before);
                var current = overview.index.figures("build-b").join();
                require(
                        current.derived().shared() == bytes.length,
                        "Cached ownership change invalidates figures without a new object");
            } finally {
                overview.cancelUpdates();
                overview.closeAsync().join();
            }
            System.out.println("Passed unified accounting cached-ownership invalidation contract");
        } finally {
            library.closeAsync().join();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
    }

    private static void awaitRevision(OverviewAccounting overview, long previous) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (overview.revision() <= previous && System.nanoTime() < deadline) Thread.sleep(20);
        require(overview.revision() > previous, "Accounting observes committed catalog changes");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
