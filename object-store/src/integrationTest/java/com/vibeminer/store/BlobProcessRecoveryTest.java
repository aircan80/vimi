package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Process termination checks; these do not simulate physical power loss. */
final class BlobProcessRecoveryTest {
    @TempDir Path directory;

    @Test
    void killedUncommittedWriterLeavesNoPayloadOrOwnership() throws Exception {
        runKilledWriter(false);
    }

    @Test
    void killedCommittedWriterPreservesPayloadAndOwnership() throws Exception {
        runKilledWriter(true);
    }

    private void runKilledWriter(boolean committed) throws Exception {
        Path database = directory.resolve("prototype.sqlite");
        Path marker = directory.resolve("ready");
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
                                Boolean.toString(committed)));
        String library = System.getProperty("vibeminer.sqlite.library");
        if (library != null) {
            command.add(1, "-Dvibeminer.sqlite.library=" + library);
        }
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(marker) && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(
                    Files.exists(marker),
                    () -> {
                        try {
                            return Files.readString(log);
                        } catch (IOException error) {
                            return error.toString();
                        }
                    });
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
        try (var reopened = new SqliteBlobPrototype(database)) {
            assertEquals(committed ? 1 : 0, reopened.buildBytes("build"));
            var output = new java.io.ByteArrayOutputStream();
            if (committed) {
                assertTrue(reopened.read("key", 0, 1, output, Cancellation.NONE));
                assertEquals(7, output.toByteArray()[0]);
            } else {
                assertFalse(reopened.read("key", 0, 1, output, Cancellation.NONE));
            }
        }
        try (var index = new NativeSqlite(database, true)) {
            assertEquals("ok", index.query("PRAGMA integrity_check").getFirst()[0]);
            assertEquals(
                    committed ? 1L : 0L,
                    index.query("SELECT count(*) FROM payloads").getFirst()[0]);
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            Path marker = Path.of(args[1]);
            boolean committed = Boolean.parseBoolean(args[2]);
            try (var store = new SqliteBlobPrototype(database)) {
                if (committed) {
                    store.put(
                            "key",
                            "build",
                            false,
                            1,
                            new ByteArrayInputStream(new byte[] {7}),
                            Cancellation.NONE);
                    Files.writeString(marker, "committed");
                    System.in.read();
                } else {
                    InputStream input =
                            new InputStream() {
                                private int chunks;

                                @Override
                                public int read() {
                                    return 7;
                                }

                                @Override
                                public int read(byte[] bytes, int offset, int length)
                                        throws IOException {
                                    java.util.Objects.checkFromIndexSize(
                                            offset, length, bytes.length);
                                    if (++chunks == 2) {
                                        Files.writeString(marker, "uncommitted");
                                        System.in.read();
                                    }
                                    java.util.Arrays.fill(bytes, offset, offset + length, (byte) 7);
                                    return length;
                                }
                            };
                    store.put("key", "build", false, 200_000, input, Cancellation.NONE);
                }
            }
        }
    }
}
