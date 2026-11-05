package com.vibeminer.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
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
        String found =
                probe.lines().filter(line -> line.startsWith("PROBE_DLL")).findFirst().orElse("");
        System.out.println(found);
        assumeTrue(
                !probe.contains("PROBE_LOADED"),
                "sqlite3.dll is already on this machine's default DLL search path: " + found);
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
    void tooOldOrSingleThreadedSqliteIsRejectedBeforeAnyOpen() {
        SqliteRuntime.requireUsable(SqliteRuntime.MINIMUM_VERSION, "3.24.0", 1);
        SqliteRuntime.Unusable old =
                assertThrows(
                        SqliteRuntime.Unusable.class,
                        () -> SqliteRuntime.requireUsable(3_023_001, "3.23.1", 1));
        assertTrue(old.getMessage().contains("3.23.1"), old.getMessage());
        assertTrue(old.getMessage().contains("3.24.0"), old.getMessage());
        SqliteRuntime.Unusable single =
                assertThrows(
                        SqliteRuntime.Unusable.class,
                        () -> SqliteRuntime.requireUsable(3_054_001, "3.54.1", 0));
        assertTrue(single.getMessage().contains("SQLITE_THREADSAFE=0"), single.getMessage());
    }

    @Test
    void missingRuntimeEntryPointIsRejectedBeforeAnyCall() {
        SqliteRuntime.Unusable missing =
                assertThrows(
                        SqliteRuntime.Unusable.class,
                        () -> SqliteRuntime.initialize(name -> Optional.empty()));
        assertTrue(
                missing.getMessage().contains("sqlite3_libversion_number"), missing.getMessage());
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

    @SuppressWarnings("restricted") // Diagnostic FFM calls in the child JVM only.
    public static final class Child {
        public static void main(String[] args) {
            Path store = Path.of(args[0]);
            if (args[1].equals("probe")) {
                describeDefaultLibrary();
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

        /**
         * Names the DLL that default-name lookup finds, using only calls that need no SQLite
         * initialization, so a crash in the store open that follows can still be diagnosed.
         */
        private static void describeDefaultLibrary() {
            try (Arena arena = Arena.ofConfined()) {
                SymbolLookup sqlite;
                try {
                    sqlite = SymbolLookup.libraryLookup("sqlite3.dll", arena);
                } catch (IllegalArgumentException missing) {
                    System.out.println("PROBE_DLL none");
                    return;
                }
                Linker linker = Linker.nativeLinker();
                StringBuilder line = new StringBuilder("PROBE_DLL");
                sqlite.find("sqlite3_libversion")
                        .ifPresent(
                                f ->
                                        line.append(" version=")
                                                .append(
                                                        ((MemorySegment)
                                                                        invoke(
                                                                                linker,
                                                                                f,
                                                                                FunctionDescriptor
                                                                                        .of(
                                                                                                ADDRESS)))
                                                                .reinterpret(64)
                                                                .getString(0)));
                sqlite.find("sqlite3_threadsafe")
                        .ifPresent(
                                f ->
                                        line.append(" threadsafe=")
                                                .append(
                                                        invoke(
                                                                linker,
                                                                f,
                                                                FunctionDescriptor.of(JAVA_INT))));
                sqlite.find("sqlite3_compileoption_used")
                        .ifPresent(
                                f ->
                                        line.append(" omit_autoinit=")
                                                .append(
                                                        invoke(
                                                                linker,
                                                                f,
                                                                FunctionDescriptor.of(
                                                                        JAVA_INT, ADDRESS),
                                                                arena.allocateFrom(
                                                                        "OMIT_AUTOINIT"))));
                sqlite.find("sqlite3_libversion")
                        .ifPresent(f -> line.append(" module=").append(modulePath(arena, f)));
                System.out.println(line);
                System.out.flush();
            }
        }

        private static String modulePath(Arena arena, MemorySegment address) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            Linker linker = Linker.nativeLinker();
            MemorySegment handle = arena.allocate(ADDRESS);
            // FROM_ADDRESS | UNCHANGED_REFCOUNT
            int found =
                    (int)
                            invoke(
                                    linker,
                                    kernel32.find("GetModuleHandleExW").orElseThrow(),
                                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS),
                                    6,
                                    address,
                                    handle);
            if (found == 0) {
                return "unknown";
            }
            MemorySegment name = arena.allocate(2L * 1024);
            int length =
                    (int)
                            invoke(
                                    linker,
                                    kernel32.find("GetModuleFileNameW").orElseThrow(),
                                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT),
                                    handle.get(ADDRESS, 0),
                                    name,
                                    1024);
            return new String(
                    name.asSlice(0, 2L * length).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
        }

        private static Object invoke(
                Linker linker,
                MemorySegment function,
                FunctionDescriptor descriptor,
                Object... args) {
            try {
                return linker.downcallHandle(function, descriptor).invokeWithArguments(args);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
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
