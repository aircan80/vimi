package com.vibeminer.store;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static java.lang.foreign.ValueLayout.*;

/** Small internal SQLite C API adapter. All native allocations have bounded, lexical lifetimes. */
final class NativeSqlite implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = library();
    private static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1);
    private static SymbolLookup library() {
        String configured = System.getProperty("vibeminer.sqlite.library");
        if (configured != null) return SymbolLookup.libraryLookup(Path.of(configured), Arena.global());
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        return SymbolLookup.libraryLookup(os.contains("win") ? "sqlite3.dll" : os.contains("mac") ? "libsqlite3.dylib" : "libsqlite3.so.0", Arena.global());
    }
    static MethodHandle function(SymbolLookup lookup, String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }
    static Object call(MethodHandle handle, Object... args) {
        try { return handle.invokeWithArguments(args); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }
    private static final MethodHandle OPEN = function(LIB, "sqlite3_open_v2", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle CLOSE = function(LIB, "sqlite3_close_v2", JAVA_INT, ADDRESS);
    private static final MethodHandle PREPARE = function(LIB, "sqlite3_prepare_v2", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final MethodHandle FINALIZE = function(LIB, "sqlite3_finalize", JAVA_INT, ADDRESS);
    private static final MethodHandle STEP = function(LIB, "sqlite3_step", JAVA_INT, ADDRESS);
    private static final MethodHandle BIND_LONG = function(LIB, "sqlite3_bind_int64", JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG);
    private static final MethodHandle BIND_BLOB = function(LIB, "sqlite3_bind_blob", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle BIND_TEXT = function(LIB, "sqlite3_bind_text", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle BIND_NULL = function(LIB, "sqlite3_bind_null", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle COUNT = function(LIB, "sqlite3_column_count", JAVA_INT, ADDRESS);
    private static final MethodHandle TYPE = function(LIB, "sqlite3_column_type", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle LONG = function(LIB, "sqlite3_column_int64", JAVA_LONG, ADDRESS, JAVA_INT);
    private static final MethodHandle BLOB = function(LIB, "sqlite3_column_blob", ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle TEXT = function(LIB, "sqlite3_column_text", ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle BYTES = function(LIB, "sqlite3_column_bytes", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle ERROR = function(LIB, "sqlite3_errmsg", ADDRESS, ADDRESS);
    private static final MethodHandle TIMEOUT = function(LIB, "sqlite3_busy_timeout", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle INTERRUPT = Linker.nativeLinker().downcallHandle(LIB.find("sqlite3_interrupt").orElseThrow(), FunctionDescriptor.ofVoid(ADDRESS));
    private MemorySegment db;
    NativeSqlite(Path path, boolean readOnly) { this(path.toString(), readOnly); }
    NativeSqlite(String path, boolean readOnly) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int rc = (int) call(OPEN, arena.allocateFrom(path), out, (readOnly ? 1 : 2 | 4) | 0x10000 | 0x40, MemorySegment.NULL);
            db = out.get(ADDRESS, 0);
            if (rc != 0) { String error = message(); close(); throw new Failure(rc, error); }
            check((int) call(TIMEOUT, db, 5000));
        }
    }
    static final class Failure extends RuntimeException {
        final int result;
        Failure(int result, String message) { super(message); this.result = result; }
    }
    private String message() {
        if (db == null || db.address() == 0) return "Cannot open SQLite";
        return ((MemorySegment) call(ERROR, db)).reinterpret(4096).getString(0);
    }
    private void check(int rc) { if (rc != 0) throw new Failure(rc, message()); }
    void exec(String sql, Object... parameters) { query(sql, parameters); }
    List<Object[]> query(String sql, Object... parameters) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            check((int) call(PREPARE, db, arena.allocateFrom(sql), -1, out, MemorySegment.NULL));
            MemorySegment stmt = out.get(ADDRESS, 0);
            try {
                for (int i = 0; i < parameters.length; i++) {
                    Object p = parameters[i]; int slot = i + 1, rc;
                    if (p == null) rc = (int) call(BIND_NULL, stmt, slot);
                    else if (p instanceof byte[] bytes) {
                        MemorySegment data = arena.allocate(Math.max(1, bytes.length)); data.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
                        rc = (int) call(BIND_BLOB, stmt, slot, data, bytes.length, TRANSIENT);
                    } else if (p instanceof String s) {
                        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                        rc = (int) call(BIND_TEXT, stmt, slot, arena.allocateFrom(s), bytes.length, TRANSIENT);
                    } else rc = (int) call(BIND_LONG, stmt, slot, ((Number) p).longValue());
                    check(rc);
                }
                List<Object[]> rows = new ArrayList<>();
                int rc;
                while ((rc = (int) call(STEP, stmt)) == 100) {
                    Object[] row = new Object[(int) call(COUNT, stmt)];
                    for (int i = 0; i < row.length; i++) {
                        int type = (int) call(TYPE, stmt, i);
                        if (type == 5) row[i] = null;
                        else if (type == 1) row[i] = (long) call(LONG, stmt, i);
                        else if (type == 3 || type == 4) {
                            MemorySegment data = (MemorySegment) call(type == 3 ? TEXT : BLOB, stmt, i);
                            int count = (int) call(BYTES, stmt, i);
                            byte[] bytes = count == 0 ? new byte[0] : data.reinterpret(count).toArray(JAVA_BYTE);
                            row[i] = type == 3 ? new String(bytes, StandardCharsets.UTF_8) : bytes;
                        } else row[i] = Double.NaN; // Never coerce invalid REAL values into checked integers.
                    }
                    rows.add(row);
                }
                if (rc != 101) throw new Failure(rc, message());
                return rows;
            } finally { call(FINALIZE, stmt); }
        }
    }
    long scalar(String sql, Object... p) { return (long) query(sql, p).getFirst()[0]; }
    void transaction(boolean full, Runnable action) {
        if (full) exec("PRAGMA synchronous=FULL");
        exec("BEGIN IMMEDIATE");
        try { action.run(); exec("COMMIT"); }
        catch (Throwable e) { try { exec("ROLLBACK"); } catch (Throwable suppressed) { e.addSuppressed(suppressed); } throw e; }
        finally { if (full) exec("PRAGMA synchronous=NORMAL"); }
    }
    void interrupt() { call(INTERRUPT, db); }
    @Override public void close() {
        if (db != null && db.address() != 0) { call(CLOSE, db); db = MemorySegment.NULL; }
    }
}
