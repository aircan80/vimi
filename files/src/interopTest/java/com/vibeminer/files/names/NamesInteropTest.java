package com.vibeminer.files.names;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.generated.DiskGeneratedStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NamesInteropTest {
    @TempDir Path temporary;
    private static final byte[] CSV =
            "0;A/blp\n9;a\\BLP\n4294967295;é".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TARGET =
            "0;Z/blp\r\n3;New.skin\n4294967295;é\n".getBytes(StandardCharsets.UTF_8);

    private static String sha(byte[] bytes) {
        return HexFormat.of().formatHex(ListfileStore.digest().digest(bytes));
    }

    @Test
    void csharpDeltaReadsInJava() throws Exception {
        Path delta = temporary.resolve("csharp.vmld");
        run("delta-write", delta);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ListfileDelta.apply(
                new ByteArrayInputStream(CSV),
                List.of(Files.readAllBytes(delta)),
                out,
                Cancellation.NONE);
        assertArrayEquals(TARGET, out.toByteArray());
    }

    @Test
    void javaDeltaReadsInCsharp() throws Exception {
        Path delta = temporary.resolve("java.vmld");
        Files.write(
                delta,
                ListfileDelta.encode(
                        new ByteArrayInputStream(CSV),
                        new ByteArrayInputStream(TARGET),
                        sha(CSV),
                        sha(TARGET),
                        TARGET.length,
                        1 << 20,
                        Cancellation.NONE));
        run("delta-read", delta);
    }

    @Test
    void csharpGeneratedIndexReadsInJava() throws Exception {
        Path folder = temporary.resolve("csharp-generated");
        run("index-write", folder);
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            ListfileStore index =
                    ListfileStore.open(store, "v1", sha(CSV), Cancellation.NONE).join().index();
            assertEquals("é", index.nameOf(4294967295L, Cancellation.NONE).join());
            assertEquals(List.of(0L, 9L), index.idsOf("A/blP", Cancellation.NONE).join());
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void javaGeneratedIndexReadsInCsharp() throws Exception {
        Path folder = temporary.resolve("java-generated");
        DiskGeneratedStore store = DiskGeneratedStore.open(folder).join();
        try {
            ListfileStore.build(
                            store,
                            new ListfileStore.Release(
                                    "v1", sha(CSV), Instant.EPOCH, Instant.EPOCH.plusSeconds(1)),
                            new ByteArrayInputStream(CSV),
                            false,
                            Cancellation.NONE)
                    .join();
        } finally {
            store.closeAsync().join();
        }
        run("index-read", folder);
    }

    private void run(String command, Path path) throws Exception {
        Process process =
                new ProcessBuilder(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.files.interop.harness"),
                                command,
                                path.toString())
                        .redirectErrorStream(true)
                        .start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "C# harness timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
