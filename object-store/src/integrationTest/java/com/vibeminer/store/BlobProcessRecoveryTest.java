package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    /** Bounds only a hung child; never a guess at how fast a busy machine starts a JVM. */
    private static final long HANG_GUARD_SECONDS = 120;

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
            awaitReady(process, marker, log);
        } finally {
            process.destroyForcibly();
            assertTrue(
                    process.waitFor(HANG_GUARD_SECONDS, TimeUnit.SECONDS),
                    "Killed writer did not exit within " + HANG_GUARD_SECONDS + " s");
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

    /**
     * Waits for the writer to reach its kill point. Only two outcomes end the wait: the marker
     * appears, or the child exits first (a real failure). A slow child is not a failure: a loaded
     * Windows runner once took about 10 s to start it. The guard only stops a genuine hang.
     */
    private static void awaitReady(Process process, Path marker, Path log) throws Exception {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(HANG_GUARD_SECONDS);
        while (!Files.exists(marker)) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (!process.isAlive()) {
                // The marker is written before the child blocks; re-check after observing exit.
                if (Files.exists(marker)) {
                    return;
                }
                fail(
                        "Writer exited with "
                                + process.exitValue()
                                + " before its kill point, after "
                                + elapsed
                                + " ms; child log:\n"
                                + childLog(log));
            }
            if (System.nanoTime() - deadline > 0) {
                fail(
                        "Writer still alive but not at its kill point after "
                                + elapsed
                                + " ms; child log:\n"
                                + childLog(log));
            }
            Thread.sleep(20);
        }
    }

    private static String childLog(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException error) {
            return error.toString();
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[0]);
            Path marker = Path.of(args[1]);
            boolean committed = Boolean.parseBoolean(args[2]);
            // Progress in the child log, so a slow or stuck child shows where it was.
            System.out.println("child started");
            System.out.flush();
            try (var store = new SqliteBlobPrototype(database)) {
                System.out.println("store open");
                System.out.flush();
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
