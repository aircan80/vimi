package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Read-only resolution storage restores a prepared build or refuses; it never writes (#346). */
@Timeout(60)
@SuppressWarnings("try") // The catalog stays open for the writer's ownership checks.
final class ReadOnlyResolutionTest {
    @TempDir Path directory;

    private com.vibeminer.store.ReadOnlyStore readOnly() {
        return ObjectStore.openReadOnly(
                        directory.toAbsolutePath(),
                        new StoreTypes.Options(
                                StoreTypes.Kind.LIBRARY, 2L << 30, java.time.Clock.systemUTC()))
                .join();
    }

    private static BuildTypes.BuildOpenResult open(
            SyntheticBuild source, BuildResolutionCache.Storage storage) {
        try (var cache = new BuildResolutionCache()) {
            var opener = new BuildOpener(source, new BuildOpener.Options(), cache, storage);
            try {
                return opener.open(source.ref).join();
            } finally {
                opener.closeAsync().join();
            }
        }
    }

    @Test
    void preparedBuildOpensReadOnlyAlongsideTheWriterWithoutItsIndexes() {
        var source = new SyntheticBuild();
        var library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try (var catalog = LibraryCatalog.open(library.databasePath()).join()) {
            var owner =
                    library.forOwner(
                            "test-build",
                            LibraryCatalog.Scope.BUILD,
                            "build-indexes",
                            LibraryCatalog.Role.DERIVED);
            // Preparation: the writer resolves and stores the projection.
            assertEquals(
                    BuildOpenState.OPEN,
                    open(source, new BuildResolutionCache.Storage(owner, owner)).state());
            // The writer stays open. Without the archive indexes, only the stored projection can
            // resolve the build, read through a store opened without the writer lock.
            source.objects.remove(source.archiveIndexKey);
            source.objects.remove(source.fileIndexKey);
            var reader = readOnly();
            try {
                var result = open(source, BuildResolutionCache.Storage.readOnly(reader));
                assertEquals(BuildOpenState.OPEN, result.state(), result.message());
            } finally {
                reader.closeAsync().join();
            }
        } finally {
            library.closeAsync().join();
        }
    }

    @Test
    void unpreparedBuildIsRefusedAsNeedingPreparationAndNothingIsWritten() throws Exception {
        var source = new SyntheticBuild();
        var library = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        LibraryCatalog.open(library.databasePath()).join().close();
        library.closeAsync().join();
        int objects = source.objects.size();
        var reader = readOnly();
        try {
            var storage = BuildResolutionCache.Storage.readOnly(reader);
            assertTrue(storage.isReadOnly());
            var result = open(source, storage);
            assertEquals(BuildOpenState.UNAVAILABLE, result.state());
            assertEquals(BuildTypes.BuildPart.ARCHIVE_INDEX, result.part());
            assertEquals(BuildResolutionCache.NEEDS_PREPARATION, result.code());
            assertTrue(result.message().contains("needs preparation"), result.message());
            assertEquals(objects, source.objects.size(), "the source was written");
            var put =
                    storage.writer()
                            .put(java.util.List.of(), true, com.vibeminer.store.Cancellation.NONE);
            assertTrue(put.isCompletedExceptionally(), "read-only storage accepted a write");
        } finally {
            reader.closeAsync().join();
        }
        var writer = ObjectStore.openLibrary(directory.toAbsolutePath()).join();
        try {
            assertEquals(0, writer.stats().join().objects(), "a projection was stored");
        } finally {
            writer.closeAsync().join();
        }
    }
}
