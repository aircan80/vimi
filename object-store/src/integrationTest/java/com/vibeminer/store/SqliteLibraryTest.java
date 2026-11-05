package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqliteLibraryTest {
    @TempDir Path folder;

    @Test
    void missingLibraryIsActionableAndRepeatableForBothOpenModes() throws Exception {
        runChild(folder.resolve("missing-library").toString(), "failure");
    }

    @Test
    void incompatibleFileIsActionableAndRepeatable() throws Exception {
        Path invalid = folder.resolve("invalid-library");
        Files.writeString(invalid, "not a native library");
        runChild(invalid.toString(), "failure");
    }

    @Test
    void defaultLibraryStartsInFreshProcess() throws Exception {
        if (!windows()) {
            runChild(null, "success");
            return;
        }
        // Windows ships no sqlite3.dll, so prove default-name startup through the standard DLL
        // search path by putting only the supplied DLL's directory first on the child's PATH.
        String configured = System.getProperty("vibeminer.sqlite.library");
        assumeTrue(configured != null, "Supply -PsqliteLibrary to place sqlite3.dll on PATH");
        Path library = Path.of(configured).toAbsolutePath();
        assumeTrue(
                library.getFileName().toString().equalsIgnoreCase("sqlite3.dll"),
                "Default-name lookup needs a library named sqlite3.dll");
        assumeNoDefaultSqliteOnWindows();
        runChild(null, "success", library.getParent());
    }

    @Test
    void windowsDefaultNameWithoutPathEntryIsActionable() throws Exception {
        assumeTrue(windows(), "Unix systems provide SQLite under its default name");
        assumeNoDefaultSqliteOnWindows();
        runChild(null, "failure");
    }

    /**
     * Windows searches the JDK, System32, the Windows directory and the working directory before
     * PATH. If any of them already provides sqlite3.dll, the PATH case would pass for the wrong
     * reason, so both default-name cases skip.
     */
    private void assumeNoDefaultSqliteOnWindows() throws Exception {
        String probe = runChild(null, "probe");
        assumeTrue(
                !probe.contains("PROBE_LOADED"),
                "sqlite3.dll is already on this machine's default DLL search path");
        assertTrue(probe.contains("PROBE_MISSING"), probe);
    }

    private static boolean windows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    @Test
    void missingRequiredSqliteSymbolIsRejected() {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                SqliteLibrary.requireSymbols(
                                        name ->
                                                name.equals("sqlite3_step")
                                                        ? Optional.empty()
                                                        : Optional.of(
                                                                java.lang.foreign.MemorySegment
                                                                        .NULL)));
        assertTrue(error.getMessage().contains("sqlite3_step"));
    }

    @Test
    void configuredLibraryStartsInFreshProcess() throws Exception {
        String configured = System.getProperty("vibeminer.sqlite.library");
        assumeTrue(configured != null, "Supply -PsqliteLibrary to test an explicit override");
        runChild(configured, "success");
    }

    private String runChild(String library, String outcome) throws Exception {
        return runChild(library, outcome, null);
    }

    private String runChild(String library, String outcome, Path searchDirectory) throws Exception {
        Path output = folder.resolve("child-output.txt");
        List<String> command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                Child.class.getName(),
                                folder.resolve("store").toString(),
                                outcome));
        if (library != null) {
            command.add(1, "-Dvibeminer.sqlite.library=" + library);
        }
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        if (searchDirectory != null) {
            // Windows environment keys are case-insensitive, so this updates Path as well.
            String path = builder.environment().get("PATH");
            builder.environment()
                    .put(
                            "PATH",
                            path == null
                                    ? searchDirectory.toString()
                                    : searchDirectory + File.pathSeparator + path);
        }
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Native dependency probe timed out");
            String text = Files.readString(output);
            assertEquals(0, child.exitValue(), text);
            return text;
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class Child {
        public static void main(String[] args) {
            Path store = Path.of(args[0]);
            if (args[1].equals("probe")) {
                CompletableFuture<ObjectStore> open = ObjectStore.open(store);
                if (open.handle((opened, error) -> error == null).join()) {
                    open.join().closeAsync().join();
                    System.out.println("PROBE_LOADED");
                } else {
                    expectFailure(open);
                    System.out.println("PROBE_MISSING");
                }
                return;
            }
            if (args[1].equals("success")) {
                ObjectStore.open(store).join().closeAsync().join();
                ObjectStore.openReadOnly(store, new StoreTypes.Options())
                        .join()
                        .closeAsync()
                        .join();
                return;
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                expectFailure(ObjectStore.open(store));
                expectFailure(ObjectStore.openReadOnly(store, new StoreTypes.Options()));
            }
            assertFalse(Files.exists(store), "Dependency failure must not create a store");
        }

        private static void expectFailure(CompletableFuture<?> future) {
            CompletionException error = assertThrows(CompletionException.class, future::join);
            StoreException failure = assertInstanceOf(StoreException.class, error.getCause());
            assertEquals("OBJECT_STORE_NATIVE_LIBRARY", failure.code());
            assertTrue(failure.getMessage().contains("JDK architecture"));
            assertTrue(failure.getMessage().contains("-Dvibeminer.sqlite.library"));
        }
    }
}
