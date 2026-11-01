package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.generated.GeneratedTypes.Options;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class GeneratedPersistenceTest {
    @TempDir Path folder;

    private static GeneratedKey key(int i) {
        return GeneratedStoreContract.key(i);
    }

    private static void code(String code, Runnable work) {
        CompletionException e = assertThrows(CompletionException.class, work::run);
        assertEquals(code, ((GeneratedStoreException) e.getCause()).code());
    }

    @Test
    void openMapsBusyAndKindMismatchAndCloseMapsClosed() {
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            code(GeneratedTypes.BUSY, () -> DiskGeneratedStore.open(folder).join());
        } finally {
            store.closeAsync().join();
        }
        code(GeneratedTypes.CLOSED, () -> store.read(key(1)).join());
        Path cdn = folder.resolve("cdn");
        ObjectStore object = ObjectStore.open(cdn).join();
        object.closeAsync().join();
        code(GeneratedTypes.UNUSABLE, () -> DiskGeneratedStore.open(cdn).join());
    }

    @Test
    void generatedDataSurvivesReopenAndRecordPagesReadFromDisk() {
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            try (RecordFileWriter writer =
                    new RecordFileWriter(
                            store, new RecordFileOptions(1000, 1000, 1000, 512, 2048, 9))) {
                for (int i = 0; i < 1000; i++) {
                    writer.add(i, GeneratedStoreContract.bytes(100)).join();
                }
                writer.complete(key(1), new byte[] {7}).join();
            }
        } finally {
            store.closeAsync().join();
        }
        store = DiskGeneratedStore.open(folder).join();
        try {
            RecordFile file = RecordFile.open(store, key(1)).join().file();
            assertEquals(1000, file.count());
            assertArrayEquals(GeneratedStoreContract.bytes(100), file.find(999).join().bytes());
            assertEquals(20, file.read(500, 20).join().size());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void putSplitsCommitsAndDeleteSplitsBatches() {
        ObjectStore engine =
                ObjectStore.open(
                                folder,
                                new StoreTypes.Options(
                                        StoreTypes.Kind.GENERATED, 256L << 20, Clock.systemUTC()))
                        .join();
        DiskGeneratedStore store = DiskGeneratedStore.withCommitLimit(engine, 10);
        try {
            List<Put> batch =
                    List.of(
                            new Put(key(1), new byte[10]),
                            new Put(key(2), new byte[10]),
                            new Put(key(1), new byte[10]));
            assertEquals(
                    List.of(PutOutcome.STORED, PutOutcome.STORED, PutOutcome.ALREADY_PRESENT),
                    store.put(batch).join());
            List<GeneratedKey> keys = new ArrayList<>();
            for (int i = 0; i < 4100; i++) {
                keys.add(key(i));
            }
            store.delete(keys).join();
            assertEquals(0, store.stats().join().objects());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void compactionReclaimsGeneratedPacksAndKeepsLiveData() {
        DiskGeneratedStore store =
                DiskGeneratedStore.open(
                                folder, new Options(1024, Clock.systemUTC(), 2), Cancellation.NONE)
                        .join();
        try {
            for (int i = 0; i < 5; i++) {
                store.put(List.of(new Put(key(i), GeneratedStoreContract.bytes(800)))).join();
            }
            store.delete(List.of(key(0), key(1))).join();
            var report = store.compact(0, 10, Cancellation.NONE).join();
            assertTrue(report.packsCompacted() > 0);
            assertEquals(3, store.stats().join().objects());
            for (int i = 2; i < 5; i++) {
                assertArrayEquals(
                        GeneratedStoreContract.bytes(800), store.read(key(i)).join().bytes());
            }
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void exact64MiBBoundaryStoresAndOversizedObjectsFail() {
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            byte[] bytes = new byte[GeneratedTypes.MAX_OBJECT_BYTES];
            bytes[0] = 1;
            bytes[bytes.length - 1] = 2;
            store.put(List.of(new Put(key(1), bytes))).join();
            assertArrayEquals(
                    new byte[] {0, 2}, store.readRange(key(1), bytes.length - 2, 2).join().bytes());
            code(
                    GeneratedTypes.LIMIT,
                    () ->
                            store.put(
                                            List.of(
                                                    new Put(
                                                            key(2),
                                                            new byte
                                                                    [GeneratedTypes.MAX_OBJECT_BYTES
                                                                            + 1])))
                                    .join());
        } finally {
            store.closeAsync().join();
        }
    }
}
