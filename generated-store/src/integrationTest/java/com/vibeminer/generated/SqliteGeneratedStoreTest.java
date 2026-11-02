package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.SqliteObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the existing generated-store consumer contract against the shared SQLite backend. */
final class SqliteGeneratedStoreTest extends GeneratedStoreContract {
    @TempDir Path directory;
    private SqliteObjectStore library;

    @Override
    protected GeneratedStore create() {
        library = SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        return new SqliteGeneratedStore(library);
    }

    @Override
    protected void closeStore() {
        library.closeAsync().join();
    }

    @Override
    protected void damage(GeneratedKey key, byte[] bytes) throws Exception {
        closeStore();
        try (var database = new DerivedSqliteConnection(directory.resolve("library.sqlite"))) {
            database.run(
                    com.vibeminer.store.Cancellation.NONE,
                    true,
                    session -> {
                        session.execute("UPDATE storage_payloads SET data=zeroblob(length(data))");
                        return null;
                    });
        }
        store = create();
    }

    @Test
    void originalAndGeneratedObjectsCoexistAndRecordFilesSurviveReopen() throws Exception {
        byte[] original = new byte[] {1, 2, 3};
        ObjectKey key =
                new ObjectKey(ObjectKey.CONFIG, MessageDigest.getInstance("MD5").digest(original));
        assertEquals(
                StoreTypes.PutOutcome.STORED,
                library.put(List.of(new StoreTypes.PutObject(key, original)))
                        .join()
                        .getFirst()
                        .outcome());
        try (var writer =
                new RecordFileWriter(
                        store, new RecordFileOptions(1000, 1000, 1000, 512, 2048, 9))) {
            for (int i = 0; i < 1000; i++) {
                writer.add(i, bytes(100)).join();
            }
            writer.complete(key(1001), new byte[] {7}).join();
        }
        closeStore();
        store = create();
        RecordFile file = RecordFile.open(store, key(1001)).join().file();
        assertEquals(1000, file.count());
        assertArrayEquals(bytes(100), file.find(999).join().bytes());
        assertEquals(20, file.read(500, 20).join().size());
        assertArrayEquals(original, library.read(key).join().bytes());
    }

    @Test
    void generatedLengthsAreMetadataOnlyAndCompactionDoesNotClaimPackWork() {
        store.put(List.of(new GeneratedTypes.Put(key(1), new byte[19]))).join();
        assertEquals(
                List.of(19L, -1L),
                ((SqliteGeneratedStore) store)
                        .storedLengths(List.of(key(1), key(2)), Cancellation.NONE)
                        .join());
        assertEquals(
                new GeneratedTypes.CompactReport(0, 0, 0, 0, 0),
                store.compact(0, 4, Cancellation.NONE).join());
    }
}
