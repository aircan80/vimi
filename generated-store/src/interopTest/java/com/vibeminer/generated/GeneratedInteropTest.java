package com.vibeminer.generated;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
final class GeneratedInteropTest {
    @TempDir Path folder;

    private static GeneratedKey key() {
        return GeneratedKey.hash("interop-record-file".getBytes(StandardCharsets.UTF_8));
    }

    private static long id(int i) {
        return i == 0 ? 0xffff_ffffL : (3000 - i) * 2L;
    }

    private static byte[] payload(int i) {
        return ("record-" + i + "-" + String.valueOf((char) ('a' + i % 26)).repeat(20 + i % 79))
                .getBytes(StandardCharsets.UTF_8);
    }

    private Process process(String command) throws Exception {
        return new ProcessBuilder(
                        System.getProperty("vimi.dotnet"),
                        System.getProperty("vimi.interop.harness"),
                        command,
                        folder.toString())
                .redirectErrorStream(true)
                .start();
    }

    private void run(String command) throws Exception {
        Process p = process(command);
        try {
            assertTrue(p.waitFor(45, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    p.exitValue(),
                    new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void csharpReadsJavaRecordsPagesAndUnsignedIdIndexes() throws Exception {
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            try (RecordFileWriter writer =
                    new RecordFileWriter(
                            store, new RecordFileOptions(4000, 1000, 1000, 512, 2048, 9))) {
                for (int i = 0; i < 3000; i++) {
                    writer.add(id(i), payload(i)).join();
                }
                writer.complete(key(), "metadata".getBytes(StandardCharsets.UTF_8)).join();
            }
        } finally {
            store.closeAsync().join();
        }
        run("verify");
    }

    @Test
    void javaReadsCsharpRecordsPagesAndUnsignedIdIndexes() throws Exception {
        run("write");
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            RecordFile file = RecordFile.open(store, key()).join().file();
            assertEquals(3000, file.count());
            assertArrayEquals("metadata".getBytes(StandardCharsets.UTF_8), file.metadata());
            int ordinal = 0;
            try (var cursor = file.readAll()) {
                while (true) {
                    var batch = cursor.next().join();
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (var record : batch) {
                        assertEquals(ordinal, record.ordinal());
                        assertEquals(id(ordinal), record.id());
                        assertArrayEquals(payload(ordinal++), record.bytes());
                    }
                }
            }
            assertEquals(3000, ordinal);
            for (int i : new int[] {0, 1, 999, 1024, 2047, 2999}) {
                var record = file.find(id(i)).join();
                assertEquals(i, record.ordinal());
                assertArrayEquals(payload(i), record.bytes());
            }
            assertEquals(4, file.findAtOrAfter(3).join().id());
            var page = file.read(1020, 20).join();
            for (int i = 0; i < page.size(); i++) {
                assertEquals(id(1020 + i), page.get(i).id());
            }
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void javaWriterExcludesCsharp() throws Exception {
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            run("busy");
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void csharpWriterExcludesJava() throws Exception {
        Process p = process("hold");
        try {
            var line = CompletableReader.firstLine(p);
            assertEquals("READY", line);
            CompletionException e =
                    assertThrows(
                            CompletionException.class,
                            () -> DiskGeneratedStore.open(folder).join());
            assertEquals(GeneratedTypes.BUSY, ((GeneratedStoreException) e.getCause()).code());
        } finally {
            p.getOutputStream().close();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class CompletableReader {
        static String firstLine(Process p) throws Exception {
            return java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return new java.io.BufferedReader(
                                                    new java.io.InputStreamReader(
                                                            p.getInputStream(),
                                                            StandardCharsets.UTF_8))
                                            .readLine();
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            })
                    .get(20, TimeUnit.SECONDS);
        }
    }
}
