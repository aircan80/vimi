package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        assumeTrue(
                !System.getProperty("os.name").startsWith("Windows"),
                "Windows CI supplies an explicit DLL rather than installing it on PATH");
        runChild(null, "success");
    }

    @Test
    void unrelatedNativeLibraryReportsMissingSqliteSymbols() throws Exception {
        String directory = System.getProperty("os.name").startsWith("Windows") ? "bin" : "lib";
        Path library =
                Path.of(System.getProperty("java.home"), directory, System.mapLibraryName("java"));
        assertTrue(Files.isRegularFile(library), "JDK native library is required for this probe");
        runChild(library.toString(), "failure");
    }

    @Test
    void configuredLibraryStartsInFreshProcess() throws Exception {
        String configured = System.getProperty("vibeminer.sqlite.library");
        assumeTrue(configured != null, "Supply -PsqliteLibrary to test an explicit override");
        runChild(configured, "success");
    }

    private void runChild(String library, String outcome) throws Exception {
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
        Process child =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Native dependency probe timed out");
            assertEquals(0, child.exitValue(), Files.readString(output));
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class Child {
        public static void main(String[] args) {
            Path store = Path.of(args[0]);
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
            assertTrue(!Files.exists(store), "Dependency failure must not create a store");
        }

        private static void expectFailure(java.util.concurrent.CompletableFuture<?> future) {
            java.util.concurrent.CompletionException error =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            java.util.concurrent.CompletionException.class, future::join);
            StoreException failure =
                    org.junit.jupiter.api.Assertions.assertInstanceOf(
                            StoreException.class, error.getCause());
            assertEquals("OBJECT_STORE_NATIVE_LIBRARY", failure.code());
            assertTrue(failure.getMessage().contains("JDK architecture"));
            assertTrue(failure.getMessage().contains("-Dvibeminer.sqlite.library"));
        }
    }
}
