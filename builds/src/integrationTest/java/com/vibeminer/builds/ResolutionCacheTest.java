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
}
