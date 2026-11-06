package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.LibraryCatalog;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Generated data read without the writer lock, alongside or after the writer (#346). */
@Timeout(60)
final class ReadOnlyGeneratedStoreTest {
    @TempDir Path folder;

    private static final GeneratedKey A = GeneratedKey.hash("a".getBytes(StandardCharsets.UTF_8));
    private static final GeneratedKey B = GeneratedKey.hash("b".getBytes(StandardCharsets.UTF_8));
    private static final GeneratedKey ABSENT =
            GeneratedKey.hash("absent".getBytes(StandardCharsets.UTF_8));

    private static byte[] bytes(int seed) {
        byte[] out = new byte[4096];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * 7 + seed);
        }
        return out;
    }

    private record Writer(ObjectStore library, LibraryCatalog catalog, PackGeneratedStore store)
            implements AutoCloseable {
        @Override
        public void close() {
            catalog.closeAsync().join();
            library.closeAsync().join();
        }
    }

    private Writer writer() {
        var library = ObjectStore.openLibrary(folder.toAbsolutePath()).join();
        var catalog = LibraryCatalog.open(library.databasePath()).join();
        return new Writer(
                library,
                catalog,
                new PackGeneratedStore(
                        library,
                        library.forOwner(
                                "fixture:derived",
                                LibraryCatalog.Scope.GLOBAL,
                                "generated",
                                LibraryCatalog.Role.DERIVED)));
    }

    private com.vibeminer.store.ReadOnlyStore readOnly() {
        return ObjectStore.openReadOnly(
                        folder.toAbsolutePath(),
                        new StoreTypes.Options(
                                StoreTypes.Kind.LIBRARY, 2L << 30, java.time.Clock.systemUTC()))
                .join();
    }

    private static void assertReadOnlyRefusal(java.util.concurrent.CompletableFuture<?> write) {
        var failure = assertThrows(CompletionException.class, write::join);
        assertEquals(
                GeneratedTypes.READ_ONLY, ((GeneratedStoreException) failure.getCause()).code());
    }

    @Test
    void readsWhatTheWriterPublishesWhileTheWriterHoldsTheLock() {
        try (var writer = writer()) {
            writer.store().put(List.of(new GeneratedTypes.Put(A, bytes(1)))).join();
            var reader = readOnly();
            try {
                var store = new ReadOnlyGeneratedStore(reader);
                assertArrayEquals(bytes(1), store.read(A).join().bytes());
                assertTrue(store.has(A).join());
                assertArrayEquals(
                        java.util.Arrays.copyOfRange(bytes(1), 10, 20),
                        store.readRange(A, 10, 10).join().bytes());
                assertEquals(GeneratedTypes.ReadState.ABSENT, store.read(ABSENT).join().state());
                assertFalse(store.has(B).join());
                writer.store().put(List.of(new GeneratedTypes.Put(B, bytes(2)))).join();
                assertArrayEquals(bytes(2), store.read(B).join().bytes());
                assertReadOnlyRefusal(store.put(List.of(new GeneratedTypes.Put(ABSENT, bytes(3)))));
                assertReadOnlyRefusal(store.delete(List.of(A)));
                assertReadOnlyRefusal(store.compact(0.5, 1, Cancellation.NONE));
                assertTrue(writer.store().has(A).join(), "a refused delete removed nothing");
            } finally {
                reader.closeAsync().join();
            }
        }
    }

    @Test
    void readingAClosedLibraryChangesNoFile() throws Exception {
        try (var writer = writer()) {
            writer.store().put(List.of(new GeneratedTypes.Put(A, bytes(4)))).join();
        }
        Map<String, String> before = snapshot(folder);
        var reader = readOnly();
        try {
            var store = new ReadOnlyGeneratedStore(reader);
            for (int i = 0; i < 3; i++) {
                assertArrayEquals(bytes(4), store.read(A).join().bytes());
            }
            assertEquals(GeneratedTypes.ReadState.ABSENT, store.read(ABSENT).join().state());
        } finally {
            reader.closeAsync().join();
        }
        var after = snapshot(folder);
        // SQLite's WAL index sidecars: a WAL reader creates them when no writer has them open.
        // They hold no data (the -wal stays empty) and a later writer reuses them. Every data
        // file (index database, packs, lock files) keeps its size and modification time.
        assertEquals("0", after.remove("objects.sqlite3-wal").split("@")[0]);
        after.remove("objects.sqlite3-shm");
        assertEquals(before, after);
    }

    @Test
    void readingAlongsideAnOpenWriterCreatesNoFile() throws Exception {
        try (var writer = writer()) {
            writer.store().put(List.of(new GeneratedTypes.Put(A, bytes(5)))).join();
            Map<String, String> before = snapshot(folder);
            var reader = readOnly();
            try {
                assertArrayEquals(
                        bytes(5), new ReadOnlyGeneratedStore(reader).read(A).join().bytes());
            } finally {
                reader.closeAsync().join();
            }
            // No new file, and every file keeps its size and modification time.
            assertEquals(before, snapshot(folder));
        }
    }

    /** Every file with its size and modification time. */
    static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.toList()) {
                if (Files.isRegularFile(path)) {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    files.put(
                            root.relativize(path).toString(),
                            attributes.size() + "@" + attributes.lastModifiedTime().toMillis());
                }
            }
        }
        return files;
    }
}
