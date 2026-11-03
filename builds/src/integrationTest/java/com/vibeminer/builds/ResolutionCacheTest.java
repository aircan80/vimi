package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
final class ResolutionCacheTest {
    @TempDir Path directory;

    @Test
    void newLibrarySessionRestoresResolutionAfterSourceIndexesAreGone() {
        var source = new SyntheticBuild();
        var options = new BuildOpener.Options();
        var library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        LibraryCatalog.open(library.databasePath()).join().close();
        var owner =
                library.forOwner(
                        "test-build",
                        LibraryCatalog.Scope.BUILD,
                        "build-indexes",
                        LibraryCatalog.Role.DERIVED);
        try (var cache = new BuildResolutionCache()) {
            var opener =
                    new BuildOpener(
                            source, options, cache, new BuildResolutionCache.Storage(owner, owner));
            try {
                assertEquals(BuildOpenState.OPEN, opener.open(source.ref).join().state());
            } finally {
                opener.closeAsync().join();
            }
        }
        library.closeAsync().join();
        source.objects.remove(source.archiveIndexKey);
        source.objects.remove(source.fileIndexKey);
        library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        owner =
                library.forOwner(
                        "test-build",
                        LibraryCatalog.Scope.BUILD,
                        "build-indexes",
                        LibraryCatalog.Role.DERIVED);
        try (var cache = new BuildResolutionCache()) {
            var opener =
                    new BuildOpener(
                            source, options, cache, new BuildResolutionCache.Storage(owner, owner));
            try {
                assertEquals(BuildOpenState.OPEN, opener.open(source.ref).join().state());
            } finally {
                opener.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void invalidProjectionFallsBackToAuthenticatedIndexesAndIsRepaired() {
        var source = new SyntheticBuild();
        var options = new BuildOpener.Options();
        var library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var owner =
                    library.forOwner(
                            "test-build",
                            LibraryCatalog.Scope.BUILD,
                            "build-indexes",
                            LibraryCatalog.Role.DERIVED);
            var key =
                    new com.vibeminer.store.ObjectKey(
                            com.vibeminer.store.ObjectKey.GENERATED,
                            java.util.Arrays.copyOf(
                                    BuildResolutionCache.identity(source.ref, options), 16));
            owner.put(
                            java.util.List.of(
                                    new com.vibeminer.store.StoreTypes.PutObject(
                                            key,
                                            3,
                                            new byte[3],
                                            com.vibeminer.store.StoreTypes.Origin.UNKNOWN)))
                    .join();
            try (var cache = new BuildResolutionCache()) {
                var opener =
                        new BuildOpener(
                                source,
                                options,
                                cache,
                                new BuildResolutionCache.Storage(owner, owner));
                try {
                    assertEquals(BuildOpenState.OPEN, opener.open(source.ref).join().state());
                } finally {
                    opener.closeAsync().join();
                }
            }
            org.junit.jupiter.api.Assertions.assertTrue(owner.read(key).join().bytes().length > 3);
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void projectionSpanningChunksRestoresAndMissingChunkFallsBack() throws Exception {
        var source = new SyntheticBuild();
        var options = new BuildOpener.Options();
        var archive = MetadataFixtures.key(1);
        var rows = new java.util.ArrayList<com.vibeminer.builds.formats.ArchiveIndex.Entry>();
        for (int i = 0; i < 300_000; i++) {
            byte[] key = new byte[16];
            java.nio.ByteBuffer.wrap(key).putInt(i);
            rows.add(
                    new com.vibeminer.builds.formats.ArchiveIndex.Entry(
                            new com.vibeminer.network.CdnKey(key), 100, i));
        }
        var archives = java.util.List.of(archive);
        var locations =
                com.vibeminer.builds.formats.ArchiveLocations.build(
                        java.util.List.of(
                                new com.vibeminer.builds.formats.ArchiveLocations.Indexed(
                                        archive, rows)),
                        java.util.List.of(),
                        new com.vibeminer.builds.formats.ArchiveLocations.Limits(),
                        () -> {});
        var library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join();
                var cache = new BuildResolutionCache()) {
            var owner =
                    library.forOwner(
                            "test-build",
                            LibraryCatalog.Scope.BUILD,
                            "build-indexes",
                            LibraryCatalog.Role.DERIVED);
            var storage = new BuildResolutionCache.Storage(owner, owner);
            cache.save(source.ref, options, archives, locations, storage);
            cache.drain().join();
            var restored =
                    cache.restore(
                            source.ref,
                            options,
                            archives,
                            storage,
                            com.vibeminer.access.Cancellation.NONE);
            assertEquals(300_000, restored.entryCount());
            assertEquals(
                    locations.locate(rows.getLast().key()), restored.locate(rows.getLast().key()));
            var root =
                    new com.vibeminer.store.ObjectKey(
                            com.vibeminer.store.ObjectKey.GENERATED,
                            java.util.Arrays.copyOf(
                                    BuildResolutionCache.identity(source.ref, options), 16));
            byte[] manifest = owner.read(root).join().bytes();
            assertEquals(84, manifest.length);
            var firstChunk =
                    new com.vibeminer.store.ObjectKey(
                            com.vibeminer.store.ObjectKey.GENERATED,
                            java.util.Arrays.copyOfRange(manifest, 12, 28));
            owner.delete(java.util.List.of(firstChunk)).join();
            org.junit.jupiter.api.Assertions.assertNull(
                    cache.restore(
                            source.ref,
                            options,
                            archives,
                            storage,
                            com.vibeminer.access.Cancellation.NONE));
        } finally {
            library.closeAsync().join();
        }
    }
}
