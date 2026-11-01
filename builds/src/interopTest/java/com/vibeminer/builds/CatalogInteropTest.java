package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class CatalogInteropTest {
    @TempDir Path folder;

    private void run(String mode, Path path) throws Exception {
        Process process =
                new ProcessBuilder(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.builds.harness"),
                                mode,
                                path.toString())
                        .redirectErrorStream(true)
                        .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(
                    0,
                    process.exitValue(),
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void csharpReadsJavaCatalogProjection() throws Exception {
        CatalogSource source = new CatalogSource();
        source.append(
                1,
                "wow",
                "us",
                Instant.ofEpochMilli(123456),
                "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|VersionsName!STRING:0\n## seqn = 7\nus|"
                        + "a".repeat(32)
                        + "|"
                        + "b".repeat(32)
                        + "|one\n");
        Path path = folder.resolve("catalog.sqlite3");
        Catalog catalog = Catalog.open(source, path).join();
        try {
            assertEquals(1, catalog.listBuilds("wow").join().builds().size());
        } finally {
            catalog.closeAsync().join();
        }
        run("verify", path);
    }

    @Test
    void javaReadsCsharpCatalogProjection() throws Exception {
        Path path = folder.resolve("catalog.sqlite3");
        run("write", path);
        try (CatalogStore store = CatalogStore.open(path, Cancellation.NONE)) {
            var list = store.list("wow", Cancellation.NONE);
            assertEquals(1, list.builds().size());
            var build = list.builds().getFirst();
            assertEquals("a".repeat(32), build.buildConfig());
            assertEquals("one", build.versionsName());
            assertTrue(build.current());
            assertEquals(Instant.ofEpochMilli(123456), list.lastObservedAt());
        }
    }
}
