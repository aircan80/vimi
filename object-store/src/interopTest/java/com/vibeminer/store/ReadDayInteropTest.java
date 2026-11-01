package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class ReadDayInteropTest {
    @TempDir Path folder;

    private long day() {
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), true)) {
            return db.scalar(
                    "SELECT last_read FROM objects WHERE k=?",
                    key("read-day compatibility".getBytes(StandardCharsets.US_ASCII)).indexBytes());
        }
    }

    @Test
    void bothRuntimesPreserveAndAdvanceReadDaysWithoutClockRegression() throws Exception {
        byte[] bytes = "read-day compatibility".getBytes(StandardCharsets.US_ASCII);
        Options options =
                new Options(
                        StoreTypes.Kind.CDN,
                        2L << 30,
                        Clock.fixed(Instant.ofEpochSecond(20_000L * 86_400), ZoneOffset.UTC));
        ObjectStore store = ObjectStore.open(folder, options).join();
        try {
            store.put(List.of(new PutObject(key(bytes), bytes))).join();
            store.read(key(bytes)).join();
        } finally {
            store.closeAsync().join();
        }
        assertEquals(20_000L, day());
        Process process =
                new ProcessBuilder(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.interop.harness"),
                                "read-days",
                                folder.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(20_001L, day());
        store = ObjectStore.open(folder, options).join();
        try {
            store.scan(Cancellation.NONE).join();
            assertEquals(20_001L, day());
            store.read(key(bytes)).join();
        } finally {
            store.closeAsync().join();
        }
        assertEquals(20_001L, day());
    }
}
