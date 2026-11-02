package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBlobPrototypeTest {
    @TempDir Path directory;

    @Test
    void streamsAcrossChunksSharesOwnershipAndReopens() throws Exception {
        byte[] bytes = new byte[200_003];
        new Random(42).nextBytes(bytes);
        Path path = directory.resolve("prototype.sqlite");
        try (var store = new SqliteBlobPrototype(path)) {
            store.put(
                    "original:test",
                    "build-a",
                    false,
                    bytes.length,
                    new ByteArrayInputStream(bytes),
                    Cancellation.NONE);
            store.put(
                    "original:test",
                    "build-b",
                    false,
                    bytes.length,
                    new ByteArrayInputStream(bytes),
                    Cancellation.NONE);
            assertEquals(bytes.length, store.buildBytes("build-a"));
            assertEquals(bytes.length, store.buildBytes("build-b"));
            store.checkpoint();
        }
        try (var store = new SqliteBlobPrototype(path)) {
            var output = new ByteArrayOutputStream();
            assertTrue(store.read("original:test", 65_530, 100_000, output, Cancellation.NONE));
            assertArrayEquals(Arrays.copyOfRange(bytes, 65_530, 165_530), output.toByteArray());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.read("original:test", bytes.length, 1, output, Cancellation.NONE));
        }
    }

    @Test
    void incompleteAndCanceledWritesPublishNothing() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("prototype.sqlite"))) {
            assertThrows(
                    EOFException.class,
                    () ->
                            store.put(
                                    "short",
                                    "build",
                                    false,
                                    100,
                                    new ByteArrayInputStream(new byte[1]),
                                    Cancellation.NONE));
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    CancellationException.class,
                    () ->
                            store.put(
                                    "canceled",
                                    "build",
                                    false,
                                    100,
                                    new ByteArrayInputStream(new byte[100]),
                                    canceled));
            assertEquals(0, store.buildBytes("build"));
            assertFalse(store.read("short", 0, 1, new ByteArrayOutputStream(), Cancellation.NONE));
        }
    }

    @Test
    void cancellationAfterFirstChunkRemovesUnpublishedPayload() throws Exception {
        Path path = directory.resolve("prototype.sqlite");
        var token = new Cancellation();
        var input =
                new ByteArrayInputStream(new byte[200_000]) {
                    private int calls;

                    @Override
                    public synchronized int read(byte[] bytes, int offset, int length) {
                        int result = super.read(bytes, offset, length);
                        if (++calls == 2) {
                            token.cancel();
                        }
                        return result;
                    }
                };
        try (var store = new SqliteBlobPrototype(path)) {
            assertThrows(
                    CancellationException.class,
                    () -> store.put("partial", "build", false, 200_000, input, token));
        }
        try (var database = new NativeSqlite(path, true)) {
            assertEquals(0L, database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            assertEquals(0L, database.query("SELECT count(*) FROM objects").getFirst()[0]);
            assertEquals(0L, database.query("SELECT count(*) FROM build_objects").getFirst()[0]);
        }
    }

    @Test
    void rejectsConflictingMetadataWithoutAddingOwner() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("prototype.sqlite"))) {
            store.put(
                    "key",
                    "first",
                    false,
                    1,
                    new ByteArrayInputStream(new byte[1]),
                    Cancellation.NONE);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            store.put(
                                    "key",
                                    "second",
                                    true,
                                    1,
                                    new ByteArrayInputStream(new byte[1]),
                                    Cancellation.NONE));
            assertEquals(0, store.buildBytes("second"));
            assertEquals(1, store.buildBytes("first"));
        }
    }

    @Test
    void verifiedObjectsRejectInvalidIdentityAndDetectCorruption() throws Exception {
        Path path = directory.resolve("prototype.sqlite");
        byte[] bytes = new byte[100_000];
        new Random(7).nextBytes(bytes);
        ObjectKey key = new ObjectKey(1, Format.hash("MD5", bytes));
        try (var store = new SqliteBlobPrototype(path)) {
            store.putVerified(new StoreTypes.PutObject(key, bytes), "build", Cancellation.NONE);
            assertArrayEquals(bytes, store.readVerified(key, Cancellation.NONE));
            bytes[0] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            store.putVerified(
                                    new StoreTypes.PutObject(key, bytes),
                                    "build",
                                    Cancellation.NONE));
        }
        try (var database = new NativeSqlite(path, false)) {
            database.exec("UPDATE payloads SET data=zeroblob(length(data))");
        }
        try (var reader = SqliteBlobPrototype.reader(path)) {
            assertThrows(
                    java.io.IOException.class, () -> reader.readVerified(key, Cancellation.NONE));
        }
    }

    @Test
    void independentReaderSeesOnlyCommittedPayloadsDuringWrite() throws Exception {
        Path path = directory.resolve("prototype.sqlite");
        try (var seed = new SqliteBlobPrototype(path)) {
            seed.put(
                    "old",
                    "build",
                    false,
                    1,
                    new ByteArrayInputStream(new byte[] {9}),
                    Cancellation.NONE);
        }
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
                var reader = SqliteBlobPrototype.reader(path)) {
            var write =
                    workers.submit(
                            () -> {
                                try (var writer = new SqliteBlobPrototype(path)) {
                                    var input =
                                            new ByteArrayInputStream(new byte[100_000]) {
                                                @Override
                                                public synchronized int read(
                                                        byte[] bytes, int offset, int length) {
                                                    entered.countDown();
                                                    try {
                                                        if (!release.await(
                                                                10,
                                                                java.util.concurrent.TimeUnit
                                                                        .SECONDS)) {
                                                            throw new IllegalStateException(
                                                                    "Reader did not release writer");
                                                        }
                                                    } catch (InterruptedException error) {
                                                        Thread.currentThread().interrupt();
                                                        throw new IllegalStateException(error);
                                                    }
                                                    return super.read(bytes, offset, length);
                                                }
                                            };
                                    writer.put(
                                            "new",
                                            "build",
                                            false,
                                            100_000,
                                            input,
                                            Cancellation.NONE);
                                }
                                return null;
                            });
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, reader.buildBytes("build"));
                var output = new ByteArrayOutputStream();
                assertTrue(reader.read("old", 0, 1, output, Cancellation.NONE));
                assertArrayEquals(new byte[] {9}, output.toByteArray());
                assertFalse(reader.read("new", 0, 1, output, Cancellation.NONE));
            } finally {
                release.countDown();
            }
            write.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(100_001, reader.buildBytes("build"));
        }
    }
}
