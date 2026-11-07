package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Timeout(KillPoint.TEST_TIMEOUT_SECONDS)
final class StagingProcessRecoveryTest {
    @TempDir Path directory;

    @Test
    void killedChunkTransactionRetainsOnlyPreviouslyCommittedProgress() throws Exception {
        run(false);
    }

    @Test
    void killedPublishedStageRemainsReadable() throws Exception {
        run(true);
    }

    private void run(boolean published) throws Exception {
        Path path = directory.resolve("catalog.sqlite");
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
                                path.toString(),
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
        try (var store = new SqliteBlobPrototype(path)) {
            var stages = new BoundedBlobStaging(store);
            if (!published) {
                assertEquals(0, store.buildBytes("build"));
                String id =
                        stages.begin(
                                "key", BoundedBlobStaging.CHUNK + 37, false, Cancellation.NONE);
                assertEquals(BoundedBlobStaging.CHUNK, stages.progress(id));
                assertEquals(
                        1L, store.database.query("SELECT count(*) FROM payloads").getFirst()[0]);
                stages.append(id, BoundedBlobStaging.CHUNK, new byte[37], Cancellation.NONE);
                stages.publish(
                        id,
                        "build",
                        Format.hash("SHA-256", new byte[BoundedBlobStaging.CHUNK + 37]),
                        Cancellation.NONE);
            }
            assertEquals(BoundedBlobStaging.CHUNK + 37, store.buildBytes("build"));
            var bytes = new ByteArrayOutputStream();
            assertTrue(
                    store.read("key", BoundedBlobStaging.CHUNK - 3, 10, bytes, Cancellation.NONE));
            assertArrayEquals(new byte[10], bytes.toByteArray());
            assertEquals("ok", store.database.query("PRAGMA integrity_check").getFirst()[0]);
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path marker = Path.of(args[1]);
            boolean published = Boolean.parseBoolean(args[2]);
            try (var store = new SqliteBlobPrototype(Path.of(args[0]))) {
                var stages = new BoundedBlobStaging(store);
                String id =
                        stages.begin(
                                "key", BoundedBlobStaging.CHUNK + 37, false, Cancellation.NONE);
                stages.append(id, 0, new byte[BoundedBlobStaging.CHUNK], Cancellation.NONE);
                if (!published) {
                    store.database.statementProbe =
                            sql -> {
                                if (sql.startsWith("UPDATE staging SET progress")) {
                                    try {
                                        Files.writeString(marker, "chunk-open");
                                        System.in.read();
                                    } catch (java.io.IOException error) {
                                        throw new IllegalStateException(error);
                                    }
                                }
                            };
                }
                stages.append(id, BoundedBlobStaging.CHUNK, new byte[37], Cancellation.NONE);
                stages.publish(
                        id,
                        "build",
                        Format.hash("SHA-256", new byte[BoundedBlobStaging.CHUNK + 37]),
                        Cancellation.NONE);
                Files.writeString(marker, "published");
                System.in.read();
            }
        }
    }
}
