package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BoundedBlobStagingTest {
    @TempDir Path directory;

    @Test
    void resumesAcrossReopenPublishesWithoutCopyAndReadsAcrossChunkBoundary() throws Exception {
        Path path = directory.resolve("staged.sqlite");
        byte[] bytes = bytes(BoundedBlobStaging.CHUNK + 37);
        String id;
        try (var store = new SqliteBlobPrototype(path)) {
            var stages = new BoundedBlobStaging(store);
            id = stages.begin("key", bytes.length, false, Cancellation.NONE);
            stages.append(id, 0, Arrays.copyOf(bytes, BoundedBlobStaging.CHUNK), Cancellation.NONE);
            assertFalse(store.read("key", 0, 1, new ByteArrayOutputStream(), Cancellation.NONE));
            assertEquals(0, store.buildBytes("build"));
        }
        try (var store = new SqliteBlobPrototype(path)) {
            var stages = new BoundedBlobStaging(store);
            assertEquals(id, stages.begin("key", bytes.length, false, Cancellation.NONE));
            assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
            stages.append(
                    id,
                    BoundedBlobStaging.CHUNK,
                    Arrays.copyOfRange(bytes, BoundedBlobStaging.CHUNK, bytes.length),
                    Cancellation.NONE);
            stages.publish(id, "build", Format.hash("SHA-256", bytes), Cancellation.NONE);
            assertEquals(bytes.length, store.buildBytes("build"));
            var output = new ByteArrayOutputStream();
            assertTrue(
                    store.read("key", BoundedBlobStaging.CHUNK - 7, 20, output, Cancellation.NONE));
            assertArrayEquals(
                    Arrays.copyOfRange(
                            bytes, BoundedBlobStaging.CHUNK - 7, BoundedBlobStaging.CHUNK + 13),
                    output.toByteArray());
            assertEquals(2L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            assertEquals(0L, store.database.query("SELECT count(*) FROM staging").getFirst()[0]);
            assertThrows(IllegalStateException.class, () -> stages.abandon(id, Cancellation.NONE));
        }
    }

    @Test
    void permitsOtherWritesBetweenChunksAndRejectsStaleOffsets() throws Exception {
        Path path = directory.resolve("staged.sqlite");
        try (var first = new SqliteBlobPrototype(path);
                var second = new SqliteBlobPrototype(path)) {
            var stages = new BoundedBlobStaging(first);
            var competitor = new BoundedBlobStaging(second);
            String id = stages.begin("big", BoundedBlobStaging.CHUNK * 2, false, Cancellation.NONE);
            stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE);
            second.put(
                    "small",
                    "foreground",
                    false,
                    1,
                    new ByteArrayInputStream(new byte[] {8}),
                    Cancellation.NONE);
            assertEquals(1, first.buildBytes("foreground"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            competitor.append(
                                    id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE));
            assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
            stages.append(
                    id,
                    BoundedBlobStaging.CHUNK,
                    new byte[BoundedBlobStaging.CHUNK],
                    Cancellation.NONE);
        }
    }

    @Test
    void cancellationKeepsCommittedProgressAndInvalidChunksChangeNothing() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            String id = stages.begin("key", BoundedBlobStaging.CHUNK * 2, false, Cancellation.NONE);
            stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE);
            Cancellation canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    CancellationException.class,
                    () ->
                            stages.append(
                                    id,
                                    BoundedBlobStaging.CHUNK,
                                    new byte[BoundedBlobStaging.CHUNK],
                                    canceled));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            stages.append(
                                    id, BoundedBlobStaging.CHUNK, new byte[3], Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            stages.append(
                                    id,
                                    BoundedBlobStaging.CHUNK,
                                    new byte[BoundedBlobStaging.CHUNK + 1],
                                    Cancellation.NONE));
            assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
            assertEquals(1L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            assertThrows(
                    IllegalStateException.class,
                    () -> stages.publish(id, "build", new byte[32], Cancellation.NONE));
            stages.abandon(id, Cancellation.NONE);
            assertEquals(0L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            assertEquals(0L, store.database.query("SELECT count(*) FROM chunk_sets").getFirst()[0]);
        }
    }

    @Test
    void verifiesTypedIdentityBeforePublicationAndDetectsStoredCorruption() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            byte[] bytes = bytes(BoundedBlobStaging.CHUNK + 11);
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, Format.hash("MD5", bytes));
            var stages = new BoundedBlobStaging(store);
            String id =
                    stages.begin(
                            HexFormat.of().formatHex(key.indexBytes()),
                            bytes.length,
                            true,
                            Cancellation.NONE);
            stages.append(id, 0, Arrays.copyOf(bytes, BoundedBlobStaging.CHUNK), Cancellation.NONE);
            stages.append(
                    id,
                    BoundedBlobStaging.CHUNK,
                    Arrays.copyOfRange(bytes, BoundedBlobStaging.CHUNK, bytes.length),
                    Cancellation.NONE);
            assertThrows(
                    IOException.class,
                    () -> stages.publish(id, "build", new byte[32], Cancellation.NONE));
            assertEquals(0, store.buildBytes("build"));
            stages.publishVerified(id, "build", key, Cancellation.NONE);
            assertArrayEquals(bytes, store.readVerified(key, Cancellation.NONE));
            assertEquals(1L, store.database.query("SELECT derived FROM objects").getFirst()[0]);
            store.database.exec(
                    "UPDATE payloads SET data=zeroblob(length(data)) WHERE id=(SELECT min(id) FROM payloads)");
            assertThrows(IOException.class, () -> store.readVerified(key, Cancellation.NONE));
        }
    }

    @Test
    void refusesInvalidTypedContentWithoutPublishing() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            ObjectKey key = new ObjectKey(ObjectKey.CONFIG, new byte[16]);
            String id =
                    stages.begin(
                            HexFormat.of().formatHex(key.indexBytes()),
                            3,
                            false,
                            Cancellation.NONE);
            stages.append(id, 0, new byte[] {1, 2, 3}, Cancellation.NONE);
            assertThrows(
                    IOException.class,
                    () -> stages.publishVerified(id, "build", key, Cancellation.NONE));
            assertEquals(0, store.buildBytes("build"));
            assertEquals(3, stages.progress(id));
        }
    }

    @Test
    void boundsOutstandingStagesAndRejectsConflictingResume() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            for (int i = 0; i < 4; i++) {
                stages.begin("large" + i, 512 << 20, false, Cancellation.NONE);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> stages.begin("extra", 1, false, Cancellation.NONE));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stages.begin("large0", 1, false, Cancellation.NONE));
            assertEquals(0L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
        }
    }

    @Test
    void cancellationInsideChunkRollsBackBytesAndProgressTogether() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            String id = stages.begin("key", BoundedBlobStaging.CHUNK, false, Cancellation.NONE);
            var token = new Cancellation();
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("UPDATE staging SET progress")) {
                            token.cancel();
                        }
                    };
            assertThrows(
                    CancellationException.class,
                    () -> stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], token));
            store.database.statementProbe = null;
            assertEquals(0, stages.progress(id));
            assertEquals(0L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE);
            assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
        }
    }

    @Test
    void canceledPublicationAndCleanupCanBeRetried() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            String id = stages.begin("key", 37, false, Cancellation.NONE);
            stages.append(id, 0, new byte[37], Cancellation.NONE);
            var publication = new Cancellation();
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("INSERT INTO objects")) {
                            publication.cancel();
                        }
                    };
            assertThrows(
                    CancellationException.class,
                    () ->
                            stages.publish(
                                    id,
                                    "build",
                                    Format.hash("SHA-256", new byte[37]),
                                    publication));
            store.database.statementProbe = null;
            assertEquals(0, store.buildBytes("build"));
            assertEquals(37, stages.progress(id));
            var cleanup = new Cancellation();
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("DELETE FROM payloads")) {
                            cleanup.cancel();
                        }
                    };
            assertThrows(CancellationException.class, () -> stages.abandon(id, cleanup));
            store.database.statementProbe = null;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> stages.begin("key", 37, false, Cancellation.NONE));
            stages.abandon(id, Cancellation.NONE);
            assertEquals(0L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            assertEquals(0L, store.database.query("SELECT count(*) FROM staging").getFirst()[0]);
        }
    }

    @Test
    void sqliteFullLeavesProgressResumable() throws Exception {
        try (var store = new SqliteBlobPrototype(directory.resolve("staged.sqlite"))) {
            var stages = new BoundedBlobStaging(store);
            String id = stages.begin("key", BoundedBlobStaging.CHUNK, false, Cancellation.NONE);
            long pages =
                    ((Number) store.database.query("PRAGMA page_count").getFirst()[0]).longValue();
            store.database.exec("PRAGMA max_page_count=" + pages);
            NativeSqlite.Failure error =
                    assertThrows(
                            NativeSqlite.Failure.class,
                            () ->
                                    stages.append(
                                            id,
                                            0,
                                            new byte[BoundedBlobStaging.CHUNK],
                                            Cancellation.NONE));
            assertEquals(13, error.result & 255);
            assertEquals(0, stages.progress(id));
            assertEquals(0L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
            store.database.exec("PRAGMA max_page_count=100000");
            stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE);
            assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
        }
    }

    @Test
    void verificationLeavesWriterAvailableAndPublicationDoesNotRewritePayloads() throws Exception {
        Path path = directory.resolve("staged.sqlite");
        try (var store = new SqliteBlobPrototype(path);
                var foreground = new SqliteBlobPrototype(path)) {
            var stages = new BoundedBlobStaging(store);
            byte[] bytes = bytes(BoundedBlobStaging.CHUNK + 7);
            String id = stages.begin("key", bytes.length, false, Cancellation.NONE);
            stages.append(id, 0, Arrays.copyOf(bytes, BoundedBlobStaging.CHUNK), Cancellation.NONE);
            stages.append(
                    id,
                    BoundedBlobStaging.CHUNK,
                    Arrays.copyOfRange(bytes, BoundedBlobStaging.CHUNK, bytes.length),
                    Cancellation.NONE);
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("SELECT position,payload,length FROM payload_chunks")) {
                            try {
                                foreground.put(
                                        "foreground",
                                        "other",
                                        false,
                                        1,
                                        new ByteArrayInputStream(new byte[1]),
                                        Cancellation.NONE);
                                checks.incrementAndGet();
                            } catch (IOException error) {
                                throw new IllegalStateException(error);
                            }
                        }
                        if (sql.startsWith("INSERT INTO payloads")
                                || sql.startsWith("UPDATE payloads")) {
                            throw new AssertionError("Publication rewrote payloads");
                        }
                    };
            stages.publish(id, "build", Format.hash("SHA-256", bytes), Cancellation.NONE);
            store.database.statementProbe = null;
            assertEquals(2, checks.get());
            assertEquals(1, store.buildBytes("other"));
            assertEquals(bytes.length, store.buildBytes("build"));
        }
    }

    private static byte[] bytes(int length) {
        byte[] bytes = new byte[length];
        new Random(72).nextBytes(bytes);
        return bytes;
    }
}
