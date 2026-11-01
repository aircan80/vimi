package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FailedWriteInteropTest {
    @TempDir Path folder;

    @ParameterizedTest
    @ValueSource(strings = {"truncate", "rollback", "post-commit"})
    void csharpRecoversJavaWriteWithUncertainFailure(String mode) throws Exception {
        CrashFixture.prepare(folder);
        ObjectStore store = ObjectStore.open(folder).join();
        if (mode.equals("truncate")) {
            store.io.fault =
                    (point, path) -> {
                        if (point == StoreIo.Point.PACK_SYNC
                                || point == StoreIo.Point.PACK_TRUNCATE) {
                            throw new IOException("Injected sync/cut failure");
                        }
                    };
        } else if (mode.equals("rollback")) {
            store.database.statementProbe =
                    sql -> {
                        if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                            throw new NativeSqlite.Failure(10, "Injected commit/rollback error");
                        }
                    };
        } else {
            AtomicBoolean once = new AtomicBoolean();
            store.database.afterStatementProbe =
                    sql -> {
                        if (sql.equals("COMMIT") && once.compareAndSet(false, true)) {
                            throw new NativeSqlite.Failure(10, "Injected post-commit error");
                        }
                    };
        }
        try {
            failure(
                    store.put(
                            List.of(
                                    new PutObject(
                                            key(CrashFixture.target()), CrashFixture.target())),
                            true,
                            NONE),
                    "OBJECT_STORE_WRITE_FAILED");
        } finally {
            store.closeAsync().get(5, TimeUnit.SECONDS);
        }
        Path output = folder.resolveSibling(folder.getFileName() + "-failed-write-output.txt");
        try {
            Process process =
                    new ProcessBuilder(
                                    System.getProperty("vimi.dotnet"),
                                    System.getProperty("vimi.interop.harness"),
                                    "recover-crash",
                                    folder.toString(),
                                    "true")
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), Files.readString(output));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        } finally {
            Files.deleteIfExists(output);
        }
        store = ObjectStore.open(folder).join();
        try {
            assertFalse(store.openReport().fullIndexCheck());
            assertArrayEquals(
                    CrashFixture.target(),
                    store.read(key(CrashFixture.target()), NONE).join().bytes());
            assertArrayEquals(
                    CrashFixture.baseline(),
                    store.read(key(CrashFixture.baseline()), NONE).join().bytes());
            assertNull(store.scrub(true, NONE).join().indexProblem());
        } finally {
            store.closeAsync().join();
        }
    }
}
