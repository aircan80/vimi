package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.network.CdnKey;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildObjectInventoryTest {
    @TempDir Path directory;

    @Test
    void indexesOnlyEncodingReferencesAndReusesCompletedVersionAfterReopen() {
        var pages = new AtomicInteger();
        Build opened = fixture(pages);
        ObjectStore library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            BuildObjectInventory.index(catalog, "canonical-build", opened, Cancellation.NONE)
                    .join();
            var usage = catalog.figures("canonical-build", Cancellation.NONE).join().original();
            assertEquals(2, usage.referencedObjects());
            assertEquals(50, usage.declaredBytes());
            assertEquals(0, usage.storedBytes());
            assertTrue(usage.inventoryComplete());
        } finally {
            library.closeAsync().join();
        }
        int calls = pages.get();
        library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try (LibraryCatalog catalog = LibraryCatalog.open(library.databasePath()).join()) {
            BuildObjectInventory.index(catalog, "canonical-build", opened, Cancellation.NONE)
                    .join();
            assertEquals(calls, pages.get());
        } finally {
            library.closeAsync().join();
        }
    }

    private static Build fixture(AtomicInteger pages) {
        var first = new ObjectKey(ObjectKey.ENCODED, new byte[16]);
        byte[] secondBytes = new byte[16];
        secondBytes[0] = 1;
        var references =
                List.of(
                        new BuildContent.Entry(first, 20),
                        new BuildContent.Entry(new ObjectKey(ObjectKey.ENCODED, secondBytes), 30));
        return new Build() {
            @Override
            public BuildTypes.BuildRef build() {
                return new BuildTypes.BuildRef("wow", "0".repeat(32), "1".repeat(32), "us");
            }

            @Override
            public BuildTypes.BuildMetadata metadata() {
                var key = new CdnKey(new byte[16]);
                return new BuildTypes.BuildMetadata(
                        "fixture", key, key, key, 0, 0, List.of("enUS"));
            }

            @Override
            public com.vibeminer.access.SourceTypes.CdnContext cdn() {
                throw new AssertionError("Inventory does not access CDN");
            }

            @Override
            public BuildTypes.FileResolution resolve(long id) {
                throw new AssertionError("Inventory does not resolve files");
            }

            @Override
            public BuildTypes.FileResolution resolve(String path) {
                throw new AssertionError("Inventory does not resolve files");
            }

            @Override
            public Iterable<Long> fileDataIds(String locale, long fromId) {
                throw new AssertionError("Inventory does not load tables or file lists");
            }

            @Override
            public List<BuildContent.Entry> encodedContentPage(int offset, int limit) {
                pages.incrementAndGet();
                return references.subList(
                        Math.min(offset, references.size()),
                        Math.min(references.size(), offset + limit));
            }
        };
    }
}
