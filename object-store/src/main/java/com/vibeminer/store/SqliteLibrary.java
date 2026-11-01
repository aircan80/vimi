package com.vibeminer.store;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Process-wide native dependency; a failed load stays an ordinary, repeatable store error. */
@SuppressWarnings("restricted") // Intentional FFM access; launch with --enable-native-access.
final class SqliteLibrary {
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
            return new Result(lookup, null);
        } catch (RuntimeException | LinkageError error) {
            return new Result(
                    null,
                    new StoreException(
                            "OBJECT_STORE_NATIVE_LIBRARY",
                            "Cannot load SQLite. Install a SQLite shared library matching the JDK architecture "
                                    + "or set -Dvibeminer.sqlite.library to its absolute path "
                                    + "(-PsqliteLibrary for Gradle tests). Restart the process after correcting it.",
                            error));
        }
    }

    static void requireSymbols(SymbolLookup lookup) {
        for (String symbol :
                List.of(
                        "sqlite3_open_v2",
                        "sqlite3_close_v2",
                        "sqlite3_prepare_v2",
                        "sqlite3_finalize",
                        "sqlite3_step",
                        "sqlite3_bind_int64",
                        "sqlite3_bind_blob",
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
                throw new IllegalArgumentException(
                        "SQLite library lacks required symbol " + symbol);
            }
        }
    }
}
