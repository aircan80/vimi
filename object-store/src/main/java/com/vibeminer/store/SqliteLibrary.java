package com.vibeminer.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Process-wide native dependency; a failed load stays an ordinary, repeatable store error. */
@SuppressWarnings("restricted") // Intentional FFM access; launch with --enable-native-access.
final class SqliteLibrary {
    /** UPSERT ({@code ON CONFLICT ... DO UPDATE}) in the store's SQL needs 3.24.0. */
    static final int MINIMUM_VERSION = 3_024_000;

    private static final Result RESULT = load();

    private SqliteLibrary() {}

    static SymbolLookup lookup() {
        if (RESULT.failure != null) {
            throw RESULT.failure;
        }
        return RESULT.lookup;
    }

    private record Result(SymbolLookup lookup, StoreException failure) {}

    private static Result load() {
        try {
            String configured = System.getProperty("vibeminer.sqlite.library");
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            SymbolLookup lookup =
                    configured != null
                            ? SymbolLookup.libraryLookup(Path.of(configured), Arena.global())
                            : SymbolLookup.libraryLookup(
                                    os.contains("win")
                                            ? "sqlite3.dll"
                                            : os.contains("mac")
                                                    ? "libsqlite3.dylib"
                                                    : "libsqlite3.so.0",
                                    Arena.global());
            requireSymbols(lookup);
            initialize(lookup);
            return new Result(lookup, null);
        } catch (RuntimeException | LinkageError error) {
            return new Result(
                    null,
                    new StoreException(
                            "OBJECT_STORE_NATIVE_LIBRARY",
                            "Cannot load SQLite. Install a SQLite shared library matching the JDK architecture "
                                    + "or set -Dvibeminer.sqlite.library to its absolute path "
                                    + "(-PsqliteLibrary for Gradle tests). Restart the process after correcting it."
                                    + (error instanceof Unusable unusable
                                            ? " " + unusable.getMessage()
                                            : ""),
                            error));
        }
    }

    /** A library that loaded but cannot be used; its message names what was found. */
    static final class Unusable extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Unusable(String message) {
            super(message);
        }
    }

    /**
     * Checks version and threading with calls that need no initialization, then initializes
     * explicitly. Builds with SQLITE_OMIT_AUTOINIT otherwise call unset mutex methods through null
     * pointers on the first open, which crashes the process instead of failing.
     */
    private static void initialize(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        int version = (int) invoke(linker, lookup, "sqlite3_libversion_number");
        String text =
                ((MemorySegment)
                                invokeWith(
                                        linker,
                                        lookup,
                                        "sqlite3_libversion",
                                        FunctionDescriptor.of(ADDRESS)))
                        .reinterpret(64)
                        .getString(0);
        int threadsafe = (int) invoke(linker, lookup, "sqlite3_threadsafe");
        requireUsable(version, text, threadsafe);
        int rc = (int) invoke(linker, lookup, "sqlite3_initialize");
        if (rc != 0) {
            throw new Unusable("SQLite " + text + " failed to initialize (result " + rc + ").");
        }
    }

    static void requireUsable(int version, String text, int threadsafe) {
        if (version < MINIMUM_VERSION) {
            throw new Unusable("Found SQLite " + text + "; 3.24.0 or newer is required.");
        }
        if (threadsafe == 0) {
            throw new Unusable(
                    "Found SQLite " + text + " built without thread safety (SQLITE_THREADSAFE=0).");
        }
    }

    private static Object invoke(Linker linker, SymbolLookup lookup, String name) {
        return invokeWith(linker, lookup, name, FunctionDescriptor.of(JAVA_INT));
    }

    private static Object invokeWith(
            Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MethodHandle handle = linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
        try {
            return handle.invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    static void requireSymbols(SymbolLookup lookup) {
        for (String symbol :
                List.of(
                        "sqlite3_libversion",
                        "sqlite3_libversion_number",
                        "sqlite3_threadsafe",
                        "sqlite3_initialize",
                        "sqlite3_open_v2",
                        "sqlite3_close_v2",
                        "sqlite3_prepare_v2",
                        "sqlite3_finalize",
                        "sqlite3_step",
                        "sqlite3_bind_int64",
                        "sqlite3_bind_blob",
                        "sqlite3_blob_open",
                        "sqlite3_blob_read",
                        "sqlite3_blob_write",
                        "sqlite3_blob_close",
                        "sqlite3_bind_text",
                        "sqlite3_bind_null",
                        "sqlite3_column_count",
                        "sqlite3_column_type",
                        "sqlite3_column_int64",
                        "sqlite3_column_blob",
                        "sqlite3_column_text",
                        "sqlite3_column_bytes",
                        "sqlite3_errmsg",
                        "sqlite3_busy_timeout",
                        "sqlite3_progress_handler",
                        "sqlite3_get_autocommit")) {
            if (lookup.find(symbol).isEmpty()) {
                throw new Unusable("SQLite library lacks required symbol " + symbol);
            }
        }
    }
}
