package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.DeleteOutcome;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteObjectStoreTest {
    @TempDir Path directory;

    @Test
    void originalAndGeneratedObjectsCoexistAndReopen() {
        Path path = directory.resolve("library.sqlite");
        byte[] bytes = bytes(2_000_013);
        ObjectKey original = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        ObjectKey generated = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            assertEquals(
                    List.of(PutOutcome.STORED, PutOutcome.STORED),
                    store
                            .put(
                                    List.of(
                                            new PutObject(original, bytes),
                                            new PutObject(generated, new byte[] {9})))
                            .join()
                            .stream()
                            .map(StoreTypes.PutResult::outcome)
                            .toList());
            assertEquals(
                    List.of((long) bytes.length, 1L),
                    store.storedLengths(List.of(original, generated), Cancellation.NONE).join());
        } finally {
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            assertArrayEquals(bytes, store.read(original).join().bytes());
            assertArrayEquals(
                    Arrays.copyOfRange(bytes, (1 << 20) - 3, (1 << 20) + 8),
                    store.readRange(original, (1 << 20) - 3, 11).join().bytes());
            assertArrayEquals(new byte[] {9}, store.read(generated).join().bytes());
            assertEquals(2, store.libraryStats(Cancellation.NONE).join().objects());
        } finally {
            store.closeAsync().join();
        }
        try (var db = new NativeSqlite(path, true)) {
            assertEquals(
                    List.of("DERIVED", "ORIGINAL"),
                    db.query("SELECT role FROM storage_objects ORDER BY role").stream()
                            .map(row -> (String) row[0])
                            .toList());
            assertEquals(
                    List.of("id", "data"),
                    db.query("PRAGMA table_info(storage_payloads)").stream()
                            .map(row -> (String) row[1])
                            .toList());
        }
    }

    @Test
    void generatedFirstValueWinsDespiteDifferentLengthAndDeleteAllowsReplacement() {
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        ObjectKey key = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
        byte[] bytes = bytes(5000);
        try {
            var result =
                    store.put(
                                    List.of(
                                            new PutObject(key, bytes),
                                            new PutObject(key, new byte[] {1})))
                            .join();
            assertEquals(
                    List.of(PutOutcome.STORED, PutOutcome.ALREADY_PRESENT),
                    result.stream().map(StoreTypes.PutResult::outcome).toList());
            assertArrayEquals(bytes, store.read(key).join().bytes());
            assertEquals(
                    DeleteOutcome.DELETED, store.delete(List.of(key)).join().getFirst().outcome());
            assertFalse(store.has(key).join());
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, new byte[] {2})))
                            .join()
                            .getFirst()
                            .outcome());
            store.collectGarbage(Cancellation.NONE).join();
            assertArrayEquals(new byte[] {2}, store.read(key).join().bytes());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().reclaimableChunks());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void refusesBadIdentityAndChecksFullIntersectingBlockBeforeReturningRange() {
        Path path = directory.resolve("library.sqlite");
        byte[] bytes = bytes(131_072);
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            byte[] invalid = bytes.clone();
            invalid[0] ^= 1;
            assertEquals(
                    PutOutcome.REFUSED,
                    store.put(List.of(new PutObject(key, invalid))).join().getFirst().outcome());
            assertFalse(store.has(key).join());
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, bytes))).join().getFirst().outcome());
        } finally {
            store.closeAsync().join();
        }
        try (var db = new NativeSqlite(path, false)) {
            long row = db.scalar("SELECT id FROM storage_payloads LIMIT 1");
            db.transferBlob(
                    "storage_payloads",
                    row,
                    65_000,
                    1,
                    new java.io.ByteArrayInputStream(new byte[] {(byte) ~bytes[65_000]}),
                    null,
                    Cancellation.NONE);
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        store = SqliteObjectStore.open(path).join();
        try {
            assertEquals(ReadState.CORRUPT, store.readRange(key, 0, 1).join().state());
            assertEquals(ReadState.PRESENT, store.readRange(key, 65_536, 1).join().state());
            assertEquals(ReadState.CORRUPT, store.read(key).join().state());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void resumesStagedChunksAfterRestartAndPublishesOnlyAfterVerification() {
        Path path = directory.resolve("library.sqlite");
        byte[] bytes = encoded((1 << 20) + 77);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(Arrays.copyOf(bytes, 1 << 20), Cancellation.NONE).join();
            assertEquals(1 << 20, stage.length());
            assertFalse(store.has(key).join());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().objects());
        } finally {
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            assertEquals(1 << 20, stage.length());
            stage.append(Arrays.copyOfRange(bytes, 1 << 20, bytes.length), Cancellation.NONE)
                    .join();
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().pendingObjects());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void stageCommitRefusesInvalidContentAndCancellationKeepsCompleteStage() {
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        byte[] bytes = encoded(123);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(bytes, Cancellation.NONE).join();
            Cancellation canceled = new Cancellation();
            canceled.cancel();
            assertEquals(PutOutcome.CANCELED, stage.commit(canceled).join().outcome());
            assertFalse(store.has(key).join());
            stage = store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            assertEquals(bytes.length, stage.length());
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            ObjectKey invalid = new ObjectKey(ObjectKey.ENCODED, new byte[16]);
            stage = store.stage(invalid, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(bytes, Cancellation.NONE).join();
            assertEquals(PutOutcome.REFUSED, stage.commit(Cancellation.NONE).join().outcome());
            assertFalse(store.has(invalid).join());
            store.collectGarbage(Cancellation.NONE).join();
            assertEquals(1, store.libraryStats(Cancellation.NONE).join().objects());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void historyIsPersistentFilteredPagedAndProtectedFromDeletion() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            store.appendHistory(
                            new History.Observation(
                                    1,
                                    "wow",
                                    "us",
                                    Instant.ofEpochMilli(200),
                                    null,
                                    new byte[] {1}),
                            Cancellation.NONE)
                    .join();
            store.appendHistory(
                            new History.Observation(
                                    1,
                                    "wow",
                                    "us",
                                    Instant.ofEpochMilli(100),
                                    "https://example.invalid/fixture",
                                    new byte[] {2}),
                            Cancellation.NONE)
                    .join();
        } finally {
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            var first =
                    store.readHistory(new History.Query("wow", "us", 1, null, 1), Cancellation.NONE)
                            .join();
            assertEquals(100, first.getFirst().observedAt().toEpochMilli());
            var next =
                    store.readHistory(
                                    new History.Query("wow", "us", 1, first.getFirst(), 1),
                                    Cancellation.NONE)
                            .join();
            assertEquals(200, next.getFirst().observedAt().toEpochMilli());
            assertEquals(
                    DeleteOutcome.REFUSED,
                    store.delete(List.of(first.getFirst().record())).join().getFirst().outcome());
            assertEquals(
                    DeleteOutcome.REFUSED,
                    store.delete(List.of(first.getFirst().document())).join().getFirst().outcome());
            assertEquals(
                    0,
                    store.readHistory(
                                    new History.Query("other", null, null, null, 10),
                                    Cancellation.NONE)
                            .join()
                            .size());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void secondWriterIsExcludedAndCloseRejectsFurtherOperations() {
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            CompletionException error =
                    assertThrows(
                            CompletionException.class, () -> SqliteObjectStore.open(path).join());
            assertEquals("OBJECT_STORE_BUSY", ((StoreException) error.getCause()).code());
        } finally {
            store.closeAsync().join();
        }
        assertThrows(
                CompletionException.class,
                () -> store.has(new ObjectKey(ObjectKey.CONFIG, new byte[16])).join());
        SqliteObjectStore reopened = SqliteObjectStore.open(path).join();
        reopened.closeAsync().join();
    }

    @Test
    void fullDatabaseLeavesStageResumableAndTranslatesNativeFailure() {
        Path path = directory.resolve("full.sqlite");
        byte[] bytes = encoded(1 << 20);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            long pages = store.writer().scalar("PRAGMA page_count");
            store.writer().exec("PRAGMA max_page_count=" + pages);
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () -> stage.append(bytes, Cancellation.NONE).join());
            assertEquals("OBJECT_STORE_DISK_FULL", ((StoreException) error.getCause()).code());
            assertEquals(0, stage.length());
            assertFalse(store.has(key).join());
            assertEquals(0L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            store.writer().exec("PRAGMA max_page_count=100000");
            stage.append(bytes, Cancellation.NONE).join();
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void closeDrainsAcceptedWritesEvenWhenCallerCancelsReturnedFuture() throws Exception {
        Path path = directory.resolve("drain.sqlite");
        byte[] bytes = bytes(5000);
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        store.writer().statementProbe =
                sql -> {
                    if (sql.startsWith("INSERT INTO storage_payloads")) {
                        entered.countDown();
                        try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Test did not release writer");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }
                };
        try {
            var put = store.put(List.of(new PutObject(key, bytes)));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            put.cancel(true);
            var closed = store.closeAsync();
            assertFalse(closed.isDone());
        } finally {
            release.countDown();
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void wrongDatabaseAndFutureSchemaAreRejectedWithoutReformatting() {
        Path unrelated = directory.resolve("unrelated.sqlite");
        try (var db = new NativeSqlite(unrelated, false)) {
            db.exec("CREATE TABLE unrelated(v TEXT)");
            db.exec("INSERT INTO unrelated VALUES('keep')");
        }
        CompletionException rejected =
                assertThrows(
                        CompletionException.class, () -> SqliteObjectStore.open(unrelated).join());
        assertEquals("OBJECT_STORE_FORMAT", ((StoreException) rejected.getCause()).code());
        try (var db = new NativeSqlite(unrelated, true)) {
            assertEquals("keep", db.query("SELECT v FROM unrelated").getFirst()[0]);
            assertEquals(
                    0L,
                    db.scalar("SELECT count(*) FROM sqlite_master WHERE name='storage_objects'"));
        }
        Path future = directory.resolve("future.sqlite");
        SqliteObjectStore.open(future).join().closeAsync().join();
        try (var db = new NativeSqlite(future, false)) {
            db.exec("UPDATE storage_metadata SET version=2");
        }
        rejected =
                assertThrows(
                        CompletionException.class, () -> SqliteObjectStore.open(future).join());
        assertEquals("OBJECT_STORE_FORMAT", ((StoreException) rejected.getCause()).code());
        try (var db = new NativeSqlite(future, true)) {
            assertEquals(2L, db.scalar("SELECT version FROM storage_metadata"));
        }
    }

    @Test
    void refusalAndReplacementReclaimChunksBeforeCompleting() {
        Path path = directory.resolve("cleanup.sqlite");
        byte[] invalid = encoded((1 << 20) + 13);
        ObjectKey wrong = new ObjectKey(ObjectKey.ENCODED, new byte[16]);
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                ObjectStaging stage =
                        store.stage(wrong, invalid.length, Origin.CDN, Cancellation.NONE).join();
                stage.append(Arrays.copyOf(invalid, 1 << 20), Cancellation.NONE).join();
                stage.append(
                                Arrays.copyOfRange(invalid, 1 << 20, invalid.length),
                                Cancellation.NONE)
                        .join();
                assertEquals(PutOutcome.REFUSED, stage.commit(Cancellation.NONE).join().outcome());
                assertEquals(0L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
                assertEquals(0L, store.writer().scalar("SELECT count(*) FROM storage_chunk_sets"));
                assertEquals(0, store.libraryStats(Cancellation.NONE).join().pendingObjects());
            }
            byte[] bytes = encoded((1 << 20) + 3);
            ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(Arrays.copyOf(bytes, 1 << 20), Cancellation.NONE).join();
            stage.closeAsync().join();
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, bytes))).join().getFirst().outcome());
            assertEquals(2L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            assertEquals(1L, store.writer().scalar("SELECT count(*) FROM storage_chunk_sets"));
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().reclaimableChunks());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void outstandingParentOperationsRemainBoundedUntilTheirContinuationsFinish() {
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("bounded.sqlite")).join();
        var pending = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        try {
            for (int i = 0; i < SqliteObjectStore.MAX_PENDING_OPERATIONS; i++) {
                var continued = new java.util.concurrent.CompletableFuture<Void>();
                pending.add(continued);
                store.stageOperation(() -> continued);
            }
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, new byte[16]);
            CompletionException error =
                    assertThrows(CompletionException.class, () -> store.has(key).join());
            assertEquals("OBJECT_STORE_LIMIT", ((StoreException) error.getCause()).code());
            pending.getFirst().complete(null);
            assertFalse(store.has(key).join());
        } finally {
            pending.forEach(future -> future.complete(null));
            store.closeAsync().join();
        }
    }

    @Test
    void userCompletionCanCloseAndJoinWithoutOccupyingNativeWorkers() throws Exception {
        for (boolean reading : new boolean[] {false, true}) {
            Path path = directory.resolve("callback-" + reading + ".sqlite");
            SqliteObjectStore store = SqliteObjectStore.open(path).join();
            byte[] bytes = bytes(1000);
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
            try {
                java.util.concurrent.CompletableFuture<?> operation;
                if (reading) {
                    store.put(List.of(new PutObject(key, bytes))).join();
                    operation = store.read(key);
                } else {
                    operation = store.put(List.of(new PutObject(key, bytes)));
                }
                operation
                        .thenRun(() -> store.closeAsync().join())
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                store.closeAsync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void reopeningReclaimsInterruptedWholePutsButPreservesExplicitStages() {
        Path path = directory.resolve("temporary.sqlite");
        byte[] bytes = encoded(1 << 20);
        ObjectKey stagedKey = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        ObjectKey temporaryKey = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        try {
            ObjectStaging stage =
                    store.stage(stagedKey, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(bytes, Cancellation.NONE).join();
            SqliteObjectStore fixture = store;
            store.write(
                            0,
                            Cancellation.NONE,
                            () -> {
                                String id =
                                        SqliteObjectData.begin(
                                                fixture.writer(),
                                                temporaryKey,
                                                bytes.length,
                                                Origin.UNKNOWN,
                                                Cancellation.NONE,
                                                false);
                                SqliteObjectData.append(
                                        fixture.writer(),
                                        temporaryKey,
                                        id,
                                        0,
                                        bytes,
                                        Cancellation.NONE);
                                return null;
                            })
                    .join();
        } finally {
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            store.collectGarbage(Cancellation.NONE).join();
            assertFalse(store.has(temporaryKey).join());
            assertEquals(1L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            ObjectStaging resumed =
                    store.stage(stagedKey, bytes.length, Origin.CDN, Cancellation.NONE).join();
            assertEquals(bytes.length, resumed.length());
            assertEquals(PutOutcome.STORED, resumed.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(stagedKey).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void verifiedSmallPublicationUsesOneCommitAndNoStagingRows() {
        SqliteObjectStore store = SqliteObjectStore.open(directory.resolve("small.sqlite")).join();
        byte[] bytes = bytes(1 << 20);
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        try {
            store.writer().statementProbe =
                    sql -> {
                        if (sql.equals("COMMIT")) {
                            commits.incrementAndGet();
                        }
                        if (sql.startsWith("INSERT INTO storage_staging")) {
                            throw new AssertionError("Verified small input used resumable staging");
                        }
                    };
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, bytes))).join().getFirst().outcome());
            assertEquals(1, commits.get());
            store.writer().statementProbe = null;
            assertEquals(0L, store.writer().scalar("SELECT count(*) FROM storage_staging"));
            assertArrayEquals(bytes, store.read(key).join().bytes());
            var canceled = new Cancellation();
            canceled.cancel();
            assertEquals(
                    PutOutcome.CANCELED,
                    store.put(List.of(new PutObject(key, bytes)), true, canceled)
                            .join()
                            .getFirst()
                            .outcome());
            byte[] invalid = bytes.clone();
            invalid[0] ^= 1;
            assertEquals(
                    PutOutcome.REFUSED,
                    store.put(List.of(new PutObject(key, invalid))).join().getFirst().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.writer().statementProbe = null;
            store.closeAsync().join();
        }
    }

    @Test
    void failedSmallReplacementPreservesClosedExplicitStageAtomically() {
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("small-full.sqlite")).join();
        byte[] bytes = encoded(1 << 20);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        try {
            ObjectStaging original =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            original.append(Arrays.copyOf(bytes, 65_536), Cancellation.NONE).join();
            original.closeAsync().join();
            long pages = store.writer().scalar("PRAGMA page_count");
            store.writer().exec("PRAGMA max_page_count=" + pages);
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () -> store.put(List.of(new PutObject(key, bytes))).join());
            assertEquals("OBJECT_STORE_DISK_FULL", ((StoreException) error.getCause()).code());
            assertFalse(store.has(key).join());
            store.writer().exec("PRAGMA max_page_count=100000");
            ObjectStaging resumed =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            assertEquals(65_536, resumed.length());
            resumed.closeAsync().join();
            assertEquals(
                    PutOutcome.STORED,
                    store.put(List.of(new PutObject(key, bytes))).join().getFirst().outcome());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().pendingObjects());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().reclaimableChunks());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void smallReadyPutNeverReplacesAnOpenStagingHandle() {
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("active-small.sqlite")).join();
        byte[] bytes = encoded(300);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            stage.append(Arrays.copyOf(bytes, 10), Cancellation.NONE).join();
            assertThrows(
                    CompletionException.class,
                    () -> store.put(List.of(new PutObject(key, bytes))).join());
            assertEquals(10, stage.length());
            stage.append(Arrays.copyOfRange(bytes, 10, bytes.length), Cancellation.NONE).join();
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void fragmentedAppendsCoalesceAcrossPhysicalBoundariesAndResumePartialTail() {
        Path path = directory.resolve("fragments.sqlite");
        byte[] bytes = encoded((1 << 20) + 101);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        SqliteObjectStore store = SqliteObjectStore.open(path).join();
        int pause = (1 << 20) - 4096;
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            for (int offset = 0; offset < pause; offset += 8192) {
                int end = Math.min(pause, offset + 8192);
                stage.append(Arrays.copyOfRange(bytes, offset, end), Cancellation.NONE).join();
            }
            assertEquals(pause, stage.length());
            assertEquals(1L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            assertEquals(
                    1L << 20,
                    store.writer().scalar("SELECT sum(length(data)) FROM storage_payloads"));
            assertFalse(store.has(key).join());
        } finally {
            store.closeAsync().join();
        }
        store = SqliteObjectStore.open(path).join();
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            assertEquals(pause, stage.length());
            // This append crosses a 1 MiB physical row boundary; its tail remains in the next row.
            stage.append(Arrays.copyOfRange(bytes, pause, bytes.length), Cancellation.NONE).join();
            assertEquals(2L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void oneByteAppendsUseOnePhysicalPayload() {
        byte[] bytes = encoded(257);
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
        SqliteObjectStore store =
                SqliteObjectStore.open(directory.resolve("single-byte.sqlite")).join();
        try {
            ObjectStaging stage =
                    store.stage(key, bytes.length, Origin.CDN, Cancellation.NONE).join();
            for (byte value : bytes) {
                stage.append(new byte[] {value}, Cancellation.NONE).join();
            }
            assertEquals(bytes.length, stage.length());
            assertEquals(1L, store.writer().scalar("SELECT count(*) FROM storage_payloads"));
            assertEquals(1L, store.writer().scalar("SELECT chunks FROM storage_staging"));
            assertEquals(PutOutcome.STORED, stage.commit(Cancellation.NONE).join().outcome());
            assertArrayEquals(bytes, store.read(key).join().bytes());
        } finally {
            store.closeAsync().join();
        }
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        new Random(17).nextBytes(bytes);
        return bytes;
    }

    private static byte[] encoded(int length) {
        byte[] bytes = bytes(length);
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        return bytes;
    }
}
