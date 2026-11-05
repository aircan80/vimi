package com.vibeminer.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Shared validation for every component that loads SQLite. Call {@link #initialize} once per loaded
 * library, before any other SQLite call.
 */
@SuppressWarnings("restricted") // Intentional FFM access; launch with --enable-native-access.
public final class SqliteRuntime {
    /** UPSERT ({@code ON CONFLICT ... DO UPDATE}) in the stores' SQL needs 3.24.0. */
    public static final int MINIMUM_VERSION = 3_024_000;

    private SqliteRuntime() {}

    /** A library that loaded but cannot be used; its message names what was found. */
    public static final class Unusable extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public Unusable(String message) {
            super(message);
        }
    }

    /**
     * Checks version and threading with calls that need no initialization, then initializes
     * explicitly. Builds with SQLITE_OMIT_AUTOINIT otherwise call unset mutex methods through null
     * pointers on the first open, which crashes the process instead of failing.
     *
     * @throws Unusable when a required entry point is missing, the library is too old or
     *     single-threaded, or initialization fails
     */
    public static void initialize(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        int version = (int) call(linker, lookup, "sqlite3_libversion_number", JAVA_INT);
        String text =
                ((MemorySegment) call(linker, lookup, "sqlite3_libversion", ADDRESS))
                        .reinterpret(64)
                        .getString(0);
        int threadsafe = (int) call(linker, lookup, "sqlite3_threadsafe", JAVA_INT);
        requireUsable(version, text, threadsafe);
        int rc = (int) call(linker, lookup, "sqlite3_initialize", JAVA_INT);
        if (rc != 0) {
            throw new Unusable("SQLite " + text + " failed to initialize (result " + rc + ").");
        }
    }

    /** The pure version and threading rules, separate so tests need no unusable library. */
    public static void requireUsable(int version, String text, int threadsafe) {
        if (version < MINIMUM_VERSION) {
            throw new Unusable("Found SQLite " + text + "; 3.24.0 or newer is required.");
        }
        if (threadsafe == 0) {
            throw new Unusable(
                    "Found SQLite " + text + " built without thread safety (SQLITE_THREADSAFE=0).");
        }
    }

    /** The actionable-message suffix for a load failure: the finding for unusable libraries. */
    public static String detail(Throwable error) {
        return error instanceof Unusable unusable ? " " + unusable.getMessage() : "";
    }

    private static Object call(
            Linker linker,
            SymbolLookup lookup,
            String name,
            java.lang.foreign.MemoryLayout result) {
        MemorySegment address =
                lookup.find(name)
                        .orElseThrow(
                                () -> new Unusable("SQLite library lacks required symbol " + name));
        MethodHandle handle = linker.downcallHandle(address, FunctionDescriptor.of(result));
        try {
            return handle.invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
