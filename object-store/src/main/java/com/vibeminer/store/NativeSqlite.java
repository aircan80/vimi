package com.vibeminer.store;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.Serial;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Small internal SQLite C API adapter. All native allocations have bounded, lexical lifetimes. */
@SuppressWarnings("restricted") // Intentional FFM access; launch with --enable-native-access.
final class NativeSqlite implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIB = SqliteLibrary.lookup();
    private static final MemorySegment TRANSIENT = MemorySegment.ofAddress(-1);

    static MethodHandle function(
            SymbolLookup lookup, String name, MemoryLayout result, MemoryLayout... args) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(), FunctionDescriptor.of(result, args));
    }

    static Object call(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static final MethodHandle OPEN =
            function(LIB, "sqlite3_open_v2", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    private static final MethodHandle CLOSE = function(LIB, "sqlite3_close_v2", JAVA_INT, ADDRESS);
    private static final MethodHandle PREPARE =
            function(
                    LIB,
                    "sqlite3_prepare_v2",
                    JAVA_INT,
                    ADDRESS,
                    ADDRESS,
                    JAVA_INT,
                    ADDRESS,
                    ADDRESS);
    private static final MethodHandle FINALIZE =
            function(LIB, "sqlite3_finalize", JAVA_INT, ADDRESS);
    private static final MethodHandle STEP = function(LIB, "sqlite3_step", JAVA_INT, ADDRESS);
    private static final MethodHandle BIND_LONG =
            function(LIB, "sqlite3_bind_int64", JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG);
    private static final MethodHandle BIND_BLOB =
            function(
                    LIB,
                    "sqlite3_bind_blob",
                    JAVA_INT,
                    ADDRESS,
                    JAVA_INT,
                    ADDRESS,
                    JAVA_INT,
                    ADDRESS);
    private static final MethodHandle BIND_TEXT =
            function(
                    LIB,
                    "sqlite3_bind_text",
                    JAVA_INT,
                    ADDRESS,
                    JAVA_INT,
                    ADDRESS,
                    JAVA_INT,
                    ADDRESS);
    private static final MethodHandle BIND_NULL =
            function(LIB, "sqlite3_bind_null", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle COUNT =
            function(LIB, "sqlite3_column_count", JAVA_INT, ADDRESS);
    private static final MethodHandle TYPE =
            function(LIB, "sqlite3_column_type", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle LONG =
            function(LIB, "sqlite3_column_int64", JAVA_LONG, ADDRESS, JAVA_INT);
    private static final MethodHandle BLOB =
            function(LIB, "sqlite3_column_blob", ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle TEXT =
            function(LIB, "sqlite3_column_text", ADDRESS, ADDRESS, JAVA_INT);
    private static final MethodHandle BYTES =
            function(LIB, "sqlite3_column_bytes", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle ERROR = function(LIB, "sqlite3_errmsg", ADDRESS, ADDRESS);
    private static final MethodHandle TIMEOUT =
            function(LIB, "sqlite3_busy_timeout", JAVA_INT, ADDRESS, JAVA_INT);
    private static final MethodHandle PROGRESS =
            LINKER.downcallHandle(
                    LIB.find("sqlite3_progress_handler").orElseThrow(),
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle BLOB_OPEN =
            function(
                    LIB,
                    "sqlite3_blob_open",
                    JAVA_INT,
                    ADDRESS,
                    ADDRESS,
                    ADDRESS,
                    ADDRESS,
                    JAVA_LONG,
                    JAVA_INT,
                    ADDRESS);
    private static final MethodHandle BLOB_READ =
            function(LIB, "sqlite3_blob_read", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle BLOB_WRITE =
            function(LIB, "sqlite3_blob_write", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT);
    private static final MethodHandle BLOB_CLOSE =
            function(LIB, "sqlite3_blob_close", JAVA_INT, ADDRESS);

    /** Transfers a fixed range using a bounded buffer; the connection remains thread confined. */
    void transferBlob(
            long row,
            int offset,
            int length,
            java.io.InputStream input,
            java.io.OutputStream output,
            Cancellation token)
            throws java.io.IOException {
        transferBlob("payloads", row, offset, length, input, output, token);
    }

    void transferBlob(
            String table,
            long row,
            int offset,
            int length,
            java.io.InputStream input,
            java.io.OutputStream output,
            Cancellation token)
            throws java.io.IOException {
        if (!table.equals("payloads") && !table.equals("storage_payloads")) {
            throw new IllegalArgumentException("Unsupported payload table");
        }
        if (offset < 0
                || length < 0
                || (long) offset + length > Integer.MAX_VALUE
                || (input == null) == (output == null)) {
            throw new IllegalArgumentException("Invalid BLOB transfer");
        }
        token.check();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pointer = arena.allocate(ADDRESS);
            check(
                    (int)
                            call(
                                    BLOB_OPEN,
                                    db,
                                    arena.allocateFrom("main"),
                                    arena.allocateFrom(table),
                                    arena.allocateFrom("data"),
                                    row,
                                    input == null ? 0 : 1,
                                    pointer));
            MemorySegment blob = pointer.get(ADDRESS, 0);
            Throwable failure = null;
            try {
                byte[] buffer = new byte[65536];
                MemorySegment nativeBuffer = arena.allocate(buffer.length);
                int done = 0;
                while (done < length) {
                    token.check();
                    int count = Math.min(buffer.length, length - done);
                    if (input != null) {
                        int received = input.readNBytes(buffer, 0, count);
                        if (received != count) {
                            throw new java.io.EOFException("Incomplete BLOB input");
                        }
                        token.check();
                        nativeBuffer
                                .asSlice(0, count)
                                .copyFrom(MemorySegment.ofArray(buffer).asSlice(0, count));
                        check((int) call(BLOB_WRITE, blob, nativeBuffer, count, offset + done));
                    } else {
                        check((int) call(BLOB_READ, blob, nativeBuffer, count, offset + done));
                        MemorySegment.ofArray(buffer)
                                .asSlice(0, count)
                                .copyFrom(nativeBuffer.asSlice(0, count));
                        output.write(buffer, 0, count);
                    }
                    done += count;
                }
            } catch (java.io.IOException | RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                int result = (int) call(BLOB_CLOSE, blob);
                if (result != 0) {
                    Failure closeError = new Failure(result, message());
                    if (failure != null) {
                        failure.addSuppressed(closeError);
                    } else {
                        throw closeError;
                    }
                }
            }
        }
    }

    private static final MethodHandle CANCELED = cancellationCallback();

    private static MethodHandle cancellationCallback() {
        try {
            return MethodHandles.lookup()
                    .findStatic(
                            NativeSqlite.class,
                            "canceled",
                            MethodType.methodType(int.class, Progress.class, MemorySegment.class));
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    // Invoked synchronously by SQLite; never throw through the native callback boundary.
    private static int canceled(Progress progress, MemorySegment ignored) {
        try {
            if (progress.probe != null) {
                progress.probe.run();
            }
            return progress.token.isCanceled() ? 1 : 0;
        } catch (Throwable error) {
            progress.failure = error;
            return 1;
        }
    }

    private static final class Progress {
        final Cancellation token;
        final Runnable probe;
        Throwable failure;

        Progress(Cancellation token, Runnable probe) {
            this.token = token;
            this.probe = probe;
        }
    }

    Runnable progressProbe;

    private Cancellation cancellation = Cancellation.NONE;

    <T> T withCancellation(Cancellation token, Async.Work<T> work) throws Exception {
        Cancellation previous = cancellation;
        cancellation = token;
        try {
            return work.run();
        } finally {
            cancellation = previous;
        }
    }

    void uncancelled(Runnable work) {
        Cancellation previous = cancellation;
        cancellation = Cancellation.NONE;
        try {
            work.run();
        } finally {
            cancellation = previous;
        }
    }

    private static final MethodHandle AUTOCOMMIT =
            function(LIB, "sqlite3_get_autocommit", JAVA_INT, ADDRESS);
    private MemorySegment db;
    java.util.function.Consumer<String> statementProbe;
    java.util.function.Consumer<String> afterStatementProbe;

    enum Outcome {
        ROLLED_BACK,
        COMMITTED,
        UNKNOWN
    }

    static final class TransactionFailure extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final Outcome outcome;

        TransactionFailure(Outcome outcome, Throwable cause) {
            super("Index transaction outcome: " + outcome, cause);
            this.outcome = outcome;
        }
    }

    boolean inTransaction() {
        return (int) call(AUTOCOMMIT, db) == 0;
    }

    NativeSqlite(Path path, boolean readOnly) {
        this(path.toString(), readOnly);
    }

    NativeSqlite(String path, boolean readOnly) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int rc =
                    (int)
                            call(
                                    OPEN,
                                    arena.allocateFrom(path),
                                    out,
                                    (readOnly ? 1 : 2 | 4) | 0x10000 | 0x40,
                                    MemorySegment.NULL);
            db = out.get(ADDRESS, 0);
            if (rc != 0) {
                String error = message();
                close();
                throw new Failure(rc, error);
            }
            check((int) call(TIMEOUT, db, BUSY_TIMEOUT_MILLIS));
        }
    }

    static final int BUSY_TIMEOUT_MILLIS = 5000;

    static final class Failure extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;
        final int result;

        Failure(int result, String message) {
            super(message);
            this.result = result;
        }
    }

    private String message() {
        if (db == null || db.address() == 0) {
            return "Cannot open SQLite";
        }
        return ((MemorySegment) call(ERROR, db)).reinterpret(4096).getString(0);
    }

    private void check(int rc) {
        if (rc != 0) {
            throw new Failure(rc, message());
        }
    }

    void exec(String sql, Object... parameters) {
        query(sql, parameters);
    }

    List<Object[]> query(String sql, Object... parameters) {
        if (statementProbe != null) {
            statementProbe.accept(sql);
        }
        Cancellation token = cancellation;
        Progress progress = new Progress(token, progressProbe);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment callback =
                    token == Cancellation.NONE
                            ? MemorySegment.NULL
                            : LINKER.upcallStub(
                                    CANCELED.bindTo(progress),
                                    FunctionDescriptor.of(JAVA_INT, ADDRESS),
                                    arena);
            call(PROGRESS, db, token == Cancellation.NONE ? 0 : 1000, callback, MemorySegment.NULL);
            try {
                return queryStatement(sql, parameters, arena, progress);
            } finally {
                // Unregister before the callback's arena closes, on the connection's owning thread.
                call(PROGRESS, db, 0, MemorySegment.NULL, MemorySegment.NULL);
            }
        }
    }

    private List<Object[]> queryStatement(
            String sql, Object[] parameters, Arena arena, Progress progress) {
        MemorySegment out = arena.allocate(ADDRESS);
        int prepared =
                (int) call(PREPARE, db, arena.allocateFrom(sql), -1, out, MemorySegment.NULL);
        MemorySegment stmt = out.get(ADDRESS, 0);
        try {
            checkStatement(prepared, progress);
            for (int i = 0; i < parameters.length; i++) {
                Object p = parameters[i];
                int slot = i + 1, rc;
                if (p == null) {
                    rc = (int) call(BIND_NULL, stmt, slot);
                } else if (p instanceof byte[] bytes) {
                    MemorySegment data = arena.allocate(Math.max(1, bytes.length));
                    data.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
                    rc = (int) call(BIND_BLOB, stmt, slot, data, bytes.length, TRANSIENT);
                } else if (p instanceof String s) {
                    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                    rc =
                            (int)
                                    call(
                                            BIND_TEXT,
                                            stmt,
                                            slot,
                                            arena.allocateFrom(s),
                                            bytes.length,
                                            TRANSIENT);
                } else {
                    rc = (int) call(BIND_LONG, stmt, slot, ((Number) p).longValue());
                }
                check(rc);
            }
            List<Object[]> rows = new ArrayList<>();
            int rc;
            while ((rc = (int) call(STEP, stmt)) == 100) {
                Object[] row = new Object[(int) call(COUNT, stmt)];
                for (int i = 0; i < row.length; i++) {
                    int type = (int) call(TYPE, stmt, i);
                    if (type == 5) {
                        row[i] = null;
                    } else if (type == 1) {
                        row[i] = (long) call(LONG, stmt, i);
                    } else if (type == 3 || type == 4) {
                        MemorySegment data = (MemorySegment) call(type == 3 ? TEXT : BLOB, stmt, i);
                        int count = (int) call(BYTES, stmt, i);
                        byte[] bytes =
                                count == 0
                                        ? new byte[0]
                                        : data.reinterpret(count).toArray(JAVA_BYTE);
                        row[i] = type == 3 ? new String(bytes, StandardCharsets.UTF_8) : bytes;
                    } else {
                        row[i] = Double.NaN;
                    } // Never coerce invalid REAL values into checked
                    // integers.
                }
                rows.add(row);
            }
            if (rc != 101) {
                checkStatement(rc, progress);
            }
            if (afterStatementProbe != null) {
                afterStatementProbe.accept(sql);
            }
            return rows;
        } finally {
            call(FINALIZE, stmt);
        }
    }

    private void checkStatement(int result, Progress progress) {
        if (progress.failure instanceof Error error) {
            throw error;
        }
        if (progress.failure instanceof RuntimeException error) {
            throw error;
        }
        if (progress.failure != null) {
            throw new IllegalStateException(progress.failure);
        }
        if ((result & 255) == 9 && progress.token.isCanceled()) {
            throw new CancellationException("SQLite statement canceled");
        }
        check(result);
    }

    long scalar(String sql, Object... p) {
        return (long) query(sql, p).getFirst()[0];
    }

    void transaction(boolean full, Runnable action) {
        Cancellation previous = cancellation;
        try {
            transactionBody(full, action);
        } finally {
            cancellation = previous;
        }
    }

    /**
     * Diagnostic for SQLITE_BUSY: says how long this connection waited for the write lock, so a
     * stalled holder (over the busy timeout) can be told apart from an immediate busy return.
     */
    private void beginImmediate() {
        long started = System.nanoTime();
        try {
            exec("BEGIN IMMEDIATE");
        } catch (Failure failure) {
            if ((failure.result & 255) != 5) {
                throw failure;
            }
            long waited = (System.nanoTime() - started) / 1_000_000;
            var busy =
                    new Failure(
                            failure.result,
                            failure.getMessage()
                                    + " (write lock unavailable after "
                                    + waited
                                    + " ms; busy timeout "
                                    + BUSY_TIMEOUT_MILLIS
                                    + " ms)");
            busy.setStackTrace(failure.getStackTrace());
            throw busy;
        }
    }

    private void transactionBody(boolean full, Runnable action) {
        if (full) {
            exec("PRAGMA synchronous=FULL");
        }
        Outcome outcome = Outcome.ROLLED_BACK;
        Throwable failure = null;
        boolean commitAttempted = false;
        try {
            beginImmediate();
            action.run();
            commitAttempted = true;
            uncancelled(() -> exec("COMMIT"));
            outcome = Outcome.COMMITTED;
        } catch (Throwable error) {
            failure = error;
            cancellation = Cancellation.NONE;
            // FULL/IOERR can auto-rollback SQLite's transaction. Never assume a rollback is still
            // needed.
            boolean rolledBack = false;
            if (inTransaction()) {
                try {
                    exec("ROLLBACK");
                    rolledBack = true;
                } catch (Throwable rollback) {
                    failure.addSuppressed(rollback);
                }
            }
            outcome =
                    !inTransaction() && (!commitAttempted || rolledBack)
                            ? Outcome.ROLLED_BACK
                            : Outcome.UNKNOWN;
        }
        if (full) {
            try {
                uncancelled(() -> exec("PRAGMA synchronous=NORMAL"));
            } catch (Throwable reset) {
                if (failure == null) {
                    failure = reset;
                } else {
                    failure.addSuppressed(reset);
                }
            }
        }
        if (failure != null) {
            // Callers must not truncate a committed pack or delete a switched compaction target.
            if (outcome != Outcome.ROLLED_BACK) {
                throw new TransactionFailure(outcome, failure);
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        if (db != null && db.address() != 0) {
            call(CLOSE, db);
            db = MemorySegment.NULL;
        }
    }
}
