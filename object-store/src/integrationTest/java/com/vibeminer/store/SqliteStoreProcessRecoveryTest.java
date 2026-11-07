package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Actual backend process termination checks, independent of the earlier experimental backend. */
@Timeout(KillPoint.TEST_TIMEOUT_SECONDS)
final class SqliteStoreProcessRecoveryTest {
    @TempDir Path directory;

    @Test
    void interruptedChunkRetainsOnlyCommittedProgressAndCanResume() throws Exception {
        run(false);
    }

    @Test
    void completedPublicationSurvivesProcessTermination() throws Exception {
        run(true);
    }

    private void run(boolean published) throws Exception {
        Path database = directory.resolve("library.sqlite");
        Path marker = directory.resolve("marker");
        Path log = directory.resolve("child.log");
        var command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                Child.class.getName(),
                                database.toString(),
                                marker.toString(),
                                Boolean.toString(published)));
        String library = System.getProperty("vibeminer.sqlite.library");
        if (library != null) {
            command.add(1, "-Dvibeminer.sqlite.library=" + library);
        }
        Process child =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        KillPoint.kill(child, marker, log);
        byte[] bytes = encoded();
        ObjectKey key = key(bytes);
        var store = SqliteObjectStore.open(database).join();
        try {
            if (!published) {
                assertFalse(store.has(key).join());
                ObjectStaging staging =
                        store.stage(key, bytes.length, StoreTypes.Origin.CDN, Cancellation.NONE)
                                .join();
                assertEquals(1 << 20, staging.length());
                staging.append(Arrays.copyOfRange(bytes, 1 << 20, bytes.length), Cancellation.NONE)
                        .join();
                assertEquals(
                        StoreTypes.PutOutcome.STORED,
                        staging.commit(Cancellation.NONE).join().outcome());
            }
            assertArrayEquals(bytes, store.read(key).join().bytes());
            assertEquals(0, store.libraryStats(Cancellation.NONE).join().pendingObjects());
        } finally {
            store.closeAsync().join();
        }
        try (var check = new NativeSqlite(database, true)) {
            assertEquals("ok", check.query("PRAGMA integrity_check").getFirst()[0]);
            assertEquals(2L, check.query("SELECT count(*) FROM storage_payloads").getFirst()[0]);
        }
    }

    private static byte[] encoded() {
        byte[] bytes = new byte[(1 << 20) + 37];
        System.arraycopy(new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N'}, 0, bytes, 0, 9);
        return bytes;
    }

    private static ObjectKey key(byte[] bytes) {
        return new ObjectKey(ObjectKey.ENCODED, Format.hash("MD5", bytes));
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[1]);
            boolean published = Boolean.parseBoolean(args[2]);
            byte[] bytes = encoded();
            var store = SqliteObjectStore.open(Path.of(args[0])).join();
            ObjectStaging staging =
                    store.stage(key(bytes), bytes.length, StoreTypes.Origin.CDN, Cancellation.NONE)
                            .join();
            staging.append(Arrays.copyOf(bytes, 1 << 20), Cancellation.NONE).join();
            if (!published) {
                store.writer().statementProbe =
                        sql -> {
                            if (sql.startsWith("UPDATE storage_staging SET progress")) {
                                try {
                                    Files.writeString(marker, "chunk-open");
                                    System.in.read();
                                } catch (java.io.IOException error) {
                                    throw new IllegalStateException(error);
                                }
                            }
                        };
            }
            staging.append(Arrays.copyOfRange(bytes, 1 << 20, bytes.length), Cancellation.NONE)
                    .join();
            if (staging.commit(Cancellation.NONE).join().outcome()
                    != StoreTypes.PutOutcome.STORED) {
                throw new AssertionError("Publication failed");
            }
            Files.writeString(marker, "published");
            System.in.read();
        }
    }
}
