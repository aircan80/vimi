package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try") // Permission scope restores filesystem state on every exit.
final class ReadOnlyMediaTest {
    @TempDir Path folder;
    private final byte[] bytes = {1, 2, 3, 4};

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanClosedStoreReadsWithoutCreatingSidecarsOnReadOnlyMedia(boolean emptyWal)
            throws Exception {
        createClosedStore();
        if (emptyWal) {
            Files.createFile(folder.resolve("objects.sqlite3-wal"));
        }
        try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(folder)) {
            ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
            try {
                assertArrayEquals(bytes, reader.read(key(bytes), NONE).join().bytes());
                assertEquals(emptyWal, Files.exists(folder.resolve("objects.sqlite3-wal")));
                if (emptyWal) {
                    assertEquals(0, Files.size(folder.resolve("objects.sqlite3-wal")));
                }
                assertFalse(Files.exists(folder.resolve("objects.sqlite3-shm")));
            } finally {
                reader.closeAsync().join();
            }
        }
    }

    @Test
    void immutableFallbackRejectsUncleanMainFile() throws Exception {
        createClosedStore();
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
            db.exec("UPDATE store SET clean_close=0");
            db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(folder)) {
            failure(
                    ObjectStore.openReadOnly(folder, new Options()),
                    "OBJECT_STORE_INDEX_UNREADABLE");
        }
    }

    @Test
    void inaccessibleWalCommitsNeverFallBackToMainFile() throws Exception {
        Path source = folder.resolve("source");
        ObjectStore writer = ObjectStore.open(source).join();
        Path copy = folder.resolve("copy");
        try {
            writer.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
            Files.createDirectories(copy.resolve("packs"));
            Files.copy(source.resolve("objects.sqlite3"), copy.resolve("objects.sqlite3"));
            Files.copy(source.resolve("objects.sqlite3-wal"), copy.resolve("objects.sqlite3-wal"));
            try (var packs = Files.newDirectoryStream(source.resolve("packs"))) {
                for (Path pack : packs) {
                    Files.copy(pack, copy.resolve("packs").resolve(pack.getFileName()));
                }
            }
            assertTrue(Files.size(copy.resolve("objects.sqlite3-wal")) > 0);
            try (ReadOnlyPermissions ignored = new ReadOnlyPermissions(copy)) {
                failure(
                        ObjectStore.openReadOnly(copy, new Options()),
                        "OBJECT_STORE_INDEX_UNREADABLE");
            }
        } finally {
            writer.closeAsync().join();
        }
    }

    @Test
    void secondaryReaderSeesLaterCommitsAndNeverTakesWriterOwnership() {
        createClosedStore();
        ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            ObjectStore writer = ObjectStore.open(folder).join();
            try {
                byte[] later = {5, 6, 7, 8};
                assertFalse(reader.has(key(later), NONE).join());
                writer.put(List.of(new PutObject(key(later), later)), true, NONE).join();
                assertArrayEquals(later, reader.read(key(later), NONE).join().bytes());
                failure(ObjectStore.open(folder), "OBJECT_STORE_BUSY");
                writer.delete(List.of(key(later)), NONE).join();
                assertFalse(reader.has(key(later), NONE).join());
            } finally {
                writer.closeAsync().join();
            }
        } finally {
            reader.closeAsync().join();
        }
    }

    private void createClosedStore() {
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            store.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        } finally {
            store.closeAsync().join();
        }
    }

    /** Changes only this test's files and always restores them, including on assumption skips. */
    private static final class ReadOnlyPermissions implements AutoCloseable {
        private final Map<Path, Set<PosixFilePermission>> original = new LinkedHashMap<>();

        ReadOnlyPermissions(Path root) throws IOException {
            assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
            try {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.toList()) {
                        original.put(path, Files.getPosixFilePermissions(path));
                        Files.setPosixFilePermissions(
                                path,
                                Files.isDirectory(path)
                                        ? Set.of(
                                                PosixFilePermission.OWNER_READ,
                                                PosixFilePermission.OWNER_EXECUTE)
                                        : Set.of(PosixFilePermission.OWNER_READ));
                    }
                }
                assumeTrue(
                        !Files.isWritable(root),
                        "Requires an unprivileged permission-enforcing user");
            } catch (IOException | RuntimeException | Error error) {
                try {
                    close();
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
        }

        @Override
        public void close() throws IOException {
            for (var entry : original.entrySet()) {
                Files.setPosixFilePermissions(entry.getKey(), entry.getValue());
            }
        }
    }
}
