package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.LibraryImporter.Outcome;
import com.vibeminer.store.StoreTypes.ObjectInfo;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class LibraryImporterTest {
    @TempDir Path directory;

    @Test
    void copiesSelectedOriginalsAndReportsAbsentWithoutChangingSource() {
        ObjectStore source = ObjectStore.open(directory.resolve("source")).join();
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            byte[] bytes = new byte[] {1, 2, 3};
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
            ObjectKey absent = new ObjectKey(ObjectKey.CONFIG, new byte[16]);
            source.put(List.of(new StoreTypes.PutObject(key, bytes))).join();
            var report =
                    new LibraryImporter(source, target)
                            .importKeys(List.of(key, absent), Cancellation.NONE)
                            .join();
            assertEquals(
                    List.of(Outcome.COPIED, Outcome.SOURCE_ABSENT),
                    report.entries().stream().map(LibraryImporter.Entry::outcome).toList());
            assertArrayEquals(bytes, target.read(key).join().bytes());
            assertArrayEquals(bytes, source.read(key).join().bytes());
            assertFalse(target.has(absent).join());
        } finally {
            target.closeAsync().join();
            source.closeAsync().join();
        }
    }

    @Test
    void receiptStyleGeneratedObjectSurvivesDestinationReopenAndExistingValueWins() {
        var options = new StoreTypes.Options(StoreTypes.Kind.GENERATED, 1 << 20, Clock.systemUTC());
        ObjectStore source = ObjectStore.open(directory.resolve("generated"), options).join();
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore target = SqliteObjectStore.open(path).join();
        ObjectKey key =
                new ObjectKey(
                        ObjectKey.GENERATED,
                        java.util.Arrays.copyOf(
                                Format.hash(
                                        "SHA-256",
                                        "vimi-preparation-history-v1"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                16));
        byte[] bytes =
                "{\"Format\":\"vimi-preparation-history-v1\",\"Builds\":[]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            source.put(List.of(new StoreTypes.PutObject(key, bytes))).join();
            assertEquals(
                    Outcome.COPIED,
                    new LibraryImporter(source, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join()
                            .entries()
                            .getFirst()
                            .outcome());
            target.closeAsync().join();
            target = SqliteObjectStore.open(path).join();
            assertArrayEquals(bytes, target.read(key).join().bytes());
            assertEquals(
                    Outcome.ALREADY_PRESENT,
                    new LibraryImporter(source, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join()
                            .entries()
                            .getFirst()
                            .outcome());
            assertArrayEquals(bytes, source.read(key).join().bytes());
        } finally {
            target.closeAsync().join();
            source.closeAsync().join();
        }
    }

    @Test
    void existingGeneratedValueIsNotOverwrittenByDifferentSourceBytes() {
        ObjectKey key = new ObjectKey(ObjectKey.GENERATED, new byte[16]);
        var options = new StoreTypes.Options(StoreTypes.Kind.GENERATED, 1 << 20, Clock.systemUTC());
        ObjectStore source = ObjectStore.open(directory.resolve("generated"), options).join();
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            source.put(List.of(new StoreTypes.PutObject(key, new byte[] {1}))).join();
            target.put(List.of(new StoreTypes.PutObject(key, new byte[] {2}))).join();
            var report =
                    new LibraryImporter(source, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join();
            assertEquals(Outcome.ALREADY_PRESENT, report.entries().getFirst().outcome());
            assertArrayEquals(new byte[] {2}, target.read(key).join().bytes());
            assertArrayEquals(new byte[] {1}, source.read(key).join().bytes());
        } finally {
            target.closeAsync().join();
            source.closeAsync().join();
        }
    }

    @Test
    void refusesInvalidIdentityAndReportsUnreadableSourceSeparately() {
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, new byte[16]);
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            ObjectReader invalid =
                    reader(
                            new ObjectRead(
                                    ReadState.PRESENT,
                                    new ObjectInfo(key, 1, StoreTypes.Origin.CDN, Instant.EPOCH),
                                    new byte[] {1},
                                    null));
            var result =
                    new LibraryImporter(invalid, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join();
            assertEquals(Outcome.REFUSED, result.entries().getFirst().outcome());
            assertFalse(target.has(key).join());
            ObjectReader corrupt = reader(new ObjectRead(ReadState.CORRUPT, null, null, "damaged"));
            result =
                    new LibraryImporter(corrupt, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join();
            assertEquals(Outcome.SOURCE_UNREADABLE, result.entries().getFirst().outcome());
            assertEquals("damaged", result.entries().getFirst().problem());
        } finally {
            target.closeAsync().join();
        }
    }

    @Test
    void validatesBoundsAndCancellationBeforeSourceAccess() {
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            var importer =
                    new LibraryImporter(
                            reader(new ObjectRead(ReadState.ABSENT, null, null, null)), target);
            var token = new Cancellation();
            token.cancel();
            assertThrows(
                    CancellationException.class,
                    () -> importer.importKeys(List.of(), token).join());
            assertThrows(
                    CompletionException.class,
                    () ->
                            importer.importKeys(
                                            java.util.Collections.nCopies(
                                                    4097, new ObjectKey(1, new byte[16])),
                                            Cancellation.NONE)
                                    .join());
            assertTrue(
                    importer.importKeys(List.of(), Cancellation.NONE).join().entries().isEmpty());
        } finally {
            target.closeAsync().join();
        }
    }

    @Test
    void permitsOnlyOneActiveImportAndUnexpectedFailureIsExceptional() throws Exception {
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            var entered = new CountDownLatch(1);
            var pending = new CompletableFuture<ObjectRead>();
            ObjectReader source =
                    new ObjectReader() {
                        @Override
                        public CompletableFuture<Boolean> has(ObjectKey key, Cancellation token) {
                            return CompletableFuture.completedFuture(false);
                        }

                        @Override
                        public CompletableFuture<ObjectRead> read(
                                ObjectKey key, Cancellation token) {
                            return pending;
                        }

                        @Override
                        public CompletableFuture<ObjectRead> readRange(
                                ObjectKey key, long offset, int length, Cancellation token) {
                            entered.countDown();
                            return pending;
                        }
                    };
            var importer = new LibraryImporter(source, target);
            var first =
                    importer.importKeys(List.of(new ObjectKey(1, new byte[16])), Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure =
                    assertThrows(
                            CompletionException.class,
                            () -> importer.importKeys(List.of(), Cancellation.NONE).join());
            assertEquals("OBJECT_STORE_BUSY", ((StoreException) failure.getCause()).code());
            pending.completeExceptionally(new IllegalStateException("source unavailable"));
            failure = assertThrows(CompletionException.class, first::join);
            assertEquals("source unavailable", failure.getCause().getMessage());
        } finally {
            target.closeAsync().join();
        }
    }

    @Test
    void importsLargeEncodedObjectInRangesAndResumesAfterCancellation() {
        int length = 65 << 20;
        byte[] header = new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'};
        var md5 = Format.digest("MD5");
        md5.update(header);
        byte[] zeros = new byte[65536];
        for (int at = header.length; at < length; ) {
            int count = Math.min(zeros.length, length - at);
            md5.update(zeros, 0, count);
            at += count;
        }
        ObjectKey key = new ObjectKey(ObjectKey.ENCODED, md5.digest());
        var info = new ObjectInfo(key, length, StoreTypes.Origin.CDN, Instant.EPOCH);
        var token = new Cancellation();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var lowestResumedOffset = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
        ObjectReader source =
                new ObjectReader() {
                    @Override
                    public CompletableFuture<Boolean> has(
                            ObjectKey requested, Cancellation cancellation) {
                        return CompletableFuture.completedFuture(true);
                    }

                    @Override
                    public CompletableFuture<ObjectRead> read(
                            ObjectKey requested, Cancellation cancellation) {
                        throw new AssertionError("Large imports must use ranges");
                    }

                    @Override
                    public CompletableFuture<ObjectRead> readRange(
                            ObjectKey requested,
                            long offset,
                            int count,
                            Cancellation cancellation) {
                        assertTrue(count <= 1 << 20);
                        byte[] bytes = new byte[count];
                        if (offset < header.length) {
                            int copy = Math.min(count, header.length - (int) offset);
                            System.arraycopy(header, (int) offset, bytes, 0, copy);
                        }
                        if (count > 0) {
                            int call = calls.incrementAndGet();
                            if (call == 2) {
                                token.cancel();
                            } else if (call > 2) {
                                lowestResumedOffset.accumulateAndGet(offset, Math::min);
                            }
                        }
                        return CompletableFuture.completedFuture(
                                new ObjectRead(ReadState.PRESENT, info, bytes, null));
                    }
                };
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            var importer = new LibraryImporter(source, target);
            assertThrows(
                    CancellationException.class,
                    () -> importer.importKeys(List.of(key), token).join());
            assertFalse(target.has(key).join());
            ObjectStaging progress =
                    target.stage(key, length, StoreTypes.Origin.CDN, Cancellation.NONE).join();
            assertEquals(1 << 20, progress.length());
            progress.closeAsync().join();
            var report = importer.importKeys(List.of(key), Cancellation.NONE).join();
            assertEquals(Outcome.COPIED, report.entries().getFirst().outcome());
            assertEquals(length, report.entries().getFirst().bytes());
            assertEquals(1 << 20, lowestResumedOffset.get());
            assertArrayEquals(header, target.readRange(key, 0, header.length).join().bytes());
        } finally {
            target.closeAsync().join();
        }
    }

    @Test
    void importingFromReadOnlyLegacyStoreLeavesSourcePayloadAndIndexBytesUnchanged()
            throws Exception {
        Path folder = directory.resolve("legacy");
        byte[] bytes = new byte[] {3, 4, 5};
        ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
        ObjectStore seed = ObjectStore.open(folder).join();
        seed.put(List.of(new StoreTypes.PutObject(key, bytes))).join();
        seed.closeAsync().join();
        var before = files(folder);
        ReadOnlyStore source = ObjectStore.openReadOnly(folder, new StoreTypes.Options()).join();
        SqliteObjectStore target =
                SqliteObjectStore.open(directory.resolve("library.sqlite")).join();
        try {
            assertEquals(
                    Outcome.COPIED,
                    new LibraryImporter(source, target)
                            .importKeys(List.of(key), Cancellation.NONE)
                            .join()
                            .entries()
                            .getFirst()
                            .outcome());
        } finally {
            target.closeAsync().join();
            source.closeAsync().join();
        }
        var after = files(folder);
        for (var entry : before.entrySet()) {
            assertEquals(entry.getValue(), after.get(entry.getKey()));
        }
        // SQLite may create reader coordination sidecars even for a read-only connection.
        after.keySet().removeAll(before.keySet());
        assertTrue(
                after.keySet().stream()
                        .allMatch(
                                name ->
                                        name.equals("objects.sqlite3-wal")
                                                || name.equals("objects.sqlite3-shm")));
    }

    @Test
    void selectedHistoryDocumentAndRecordRebuildIndexAfterReopen() {
        Path legacy = directory.resolve("legacy");
        byte[] reply = new byte[] {5, 6, 7};
        var observation =
                new History.Observation(
                        1,
                        "wow",
                        "us",
                        Instant.ofEpochMilli(123),
                        "https://example.invalid/fixture",
                        reply);
        List<ObjectKey> keys =
                History.objects(observation).stream().map(StoreTypes.PutObject::key).toList();
        ObjectStore seed = ObjectStore.open(legacy).join();
        seed.appendHistory(observation, Cancellation.NONE).join();
        seed.closeAsync().join();
        ReadOnlyStore source = ObjectStore.openReadOnly(legacy, new StoreTypes.Options()).join();
        Path path = directory.resolve("library.sqlite");
        SqliteObjectStore target = SqliteObjectStore.open(path).join();
        try {
            var report =
                    new LibraryImporter(source, target).importKeys(keys, Cancellation.NONE).join();
            assertEquals(
                    List.of(Outcome.COPIED, Outcome.COPIED),
                    report.entries().stream().map(LibraryImporter.Entry::outcome).toList());
        } finally {
            target.closeAsync().join();
            source.closeAsync().join();
        }
        target = SqliteObjectStore.open(path).join();
        try {
            var entries =
                    target.readHistory(
                                    new History.Query("wow", "us", 1, null, 10), Cancellation.NONE)
                            .join();
            assertEquals(1, entries.size());
            History.Entry entry = entries.getFirst();
            assertEquals(keys.get(0), entry.document());
            assertEquals(keys.get(1), entry.record());
            assertEquals(observation.observedAt(), entry.observedAt());
            assertEquals(observation.url(), entry.url());
            assertArrayEquals(reply, target.read(entry.document()).join().bytes());
        } finally {
            target.closeAsync().join();
        }
    }

    private static java.util.Map<String, String> files(Path folder) throws java.io.IOException {
        var contents = new java.util.TreeMap<String, String>();
        try (var paths = java.nio.file.Files.walk(folder)) {
            for (Path path : paths.filter(java.nio.file.Files::isRegularFile).toList()) {
                contents.put(
                        folder.relativize(path).toString(),
                        java.util.HexFormat.of()
                                .formatHex(
                                        Format.hash(
                                                "SHA-256",
                                                java.nio.file.Files.readAllBytes(path))));
            }
        }
        return contents;
    }

    private static ObjectReader reader(ObjectRead result) {
        return new ObjectReader() {
            @Override
            public CompletableFuture<Boolean> has(ObjectKey key, Cancellation token) {
                return CompletableFuture.completedFuture(result.state() == ReadState.PRESENT);
            }

            @Override
            public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation token) {
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public CompletableFuture<ObjectRead> readRange(
                    ObjectKey key, long offset, int length, Cancellation token) {
                return CompletableFuture.completedFuture(result);
            }
        };
    }
}
