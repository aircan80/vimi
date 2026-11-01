package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CrashRecoveryInteropTest {
    @TempDir Path folder;

    List<String> command(String... arguments) {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                System.getProperty("vimi.dotnet"),
                                System.getProperty("vimi.interop.harness")));
        command.addAll(List.of(arguments));
        return command;
    }

    @ParameterizedTest
    @EnumSource(WritePoint.class)
    void csharpRecoversKilledJavaWriter(WritePoint point) throws Exception {
        CrashFixture.prepare(folder);
        CrashProcess.killJava(folder, point.name(), "put");
        boolean complete =
                Set.of(
                                WritePoint.HEADER_WRITTEN,
                                WritePoint.PACK_SYNCED,
                                WritePoint.INDEX_UPDATED,
                                WritePoint.INDEX_COMMITTED)
                        .contains(point);
        // Redirect output to avoid a pipe deadlock and keep a bounded wait for .NET recovery.
        Path output = folder.resolveSibling(folder.getFileName() + "-csharp-output.txt");
        try {
            Process process =
                    new ProcessBuilder(
                                    command(
                                            "recover-crash",
                                            folder.toString(),
                                            Boolean.toString(complete)))
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "C# recovery timed out");
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
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertFalse(store.openReport().fullIndexCheck());
            assertEquals(complete, store.has(key(CrashFixture.target()), NONE).join());
            assertArrayEquals(
                    CrashFixture.baseline(),
                    store.read(key(CrashFixture.baseline()), NONE).join().bytes());
            assertNull(store.scrub(true, NONE).join().indexProblem());
        } finally {
            store.closeAsync().join();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = MaintenancePoint.class,
            names = {
                "REBUILD_CREATED",
                "REPLAY_ENTRY",
                "REBUILD_REPLAYED",
                "REBUILD_CHECKPOINTED",
                "REBUILD_BEFORE_REPLACE",
                "REBUILD_REPLACED"
            })
    void csharpRecoversInterruptedJavaRebuild(MaintenancePoint point) throws Exception {
        CrashFixture.prepare(folder);
        try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
            db.exec("UPDATE store SET row_check=row_check+1");
        }
        CrashProcess.killJava(folder, point.name(), "rebuild");
        Path output = folder.resolveSibling(folder.getFileName() + "-rebuild-output.txt");
        try {
            Process process =
                    new ProcessBuilder(command("recover-rebuild", folder.toString(), "false"))
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "C# rebuild recovery timed out");
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
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertFalse(store.openReport().fullIndexCheck());
            assertEquals(3, store.stats().join().objects());
            assertArrayEquals(
                    CrashFixture.baseline(),
                    store.read(key(CrashFixture.baseline()), NONE).join().bytes());
            assertNull(store.scrub(true, NONE).join().indexProblem());
        } finally {
            store.closeAsync().join();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "EntryStarted",
                "EntryHalfWritten",
                "EntryWritten",
                "AfterPackSync",
                "InsideIndexCommit",
                "AfterIndexCommit"
            })
    void javaRecoversKilledCsharpWriter(String point) throws Exception {
        CrashFixture.prepare(folder);
        CrashProcess.killAtReady(
                command("crash-writer", folder.toString(), point), "READY " + point);
        boolean complete = !point.equals("EntryStarted") && !point.equals("EntryHalfWritten");
        ObjectStore store = ObjectStore.open(folder).join();
        try {
            assertTrue(store.openReport().fullIndexCheck());
            assertEquals(complete, store.has(key(CrashFixture.target()), NONE).join());
            assertArrayEquals(
                    CrashFixture.baseline(),
                    store.read(key(CrashFixture.baseline()), NONE).join().bytes());
            assertEquals(1, store.readHistory(new History.Query(), NONE).join().size());
            assertEquals(0, store.verify(true, NONE).join().corruptCount());
            assertEquals(0, store.verify(true, NONE).join().lostCount());
        } finally {
            store.closeAsync().join();
        }
        store = ObjectStore.open(folder).join();
        try {
            assertFalse(store.openReport().fullIndexCheck());
            assertEquals(0, store.openReport().recoveredEntries());
            assertEquals(complete, store.has(key(CrashFixture.target()), NONE).join());
        } finally {
            store.closeAsync().join();
        }
    }
}
