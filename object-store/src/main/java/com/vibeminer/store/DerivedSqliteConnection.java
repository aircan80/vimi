package com.vibeminer.store;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/** SQLite connection for disposable derived query indexes, independent of object-store schema. */
public final class DerivedSqliteConnection implements AutoCloseable {
    public interface Session {
        void execute(String sql, Object... parameters);

        List<Object[]> query(String sql, Object... parameters);
    }

    private final NativeSqlite db;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    public DerivedSqliteConnection(Path path) {
        db = new NativeSqlite(path, false);
        try {
            db.exec("PRAGMA journal_mode=WAL");
            db.exec("PRAGMA synchronous=NORMAL");
        } catch (RuntimeException | Error failure) {
            db.close();
            throw failure;
        }
    }

    public synchronized <T> T run(
            Cancellation cancellation, boolean transaction, Function<Session, T> work)
            throws Exception {
        if (closed || Thread.currentThread() != owner) {
            throw new IllegalStateException(
                    "Derived SQLite connection is closed or used outside its actor");
        }
        final class Scope implements Session {
            boolean valid = true;

            void check(Object[] parameters) {
                if (!valid || Thread.currentThread() != owner) {
                    throw new IllegalStateException("Derived SQLite session expired");
                }
                for (Object parameter : parameters) {
                    if (parameter != null
                            && !(parameter instanceof String)
                            && !(parameter instanceof byte[])
                            && !(parameter instanceof Byte)
                            && !(parameter instanceof Short)
                            && !(parameter instanceof Integer)
                            && !(parameter instanceof Long)) {
                        throw new IllegalArgumentException(
                                "Derived query parameters support integers, text, blobs and null");
                    }
                }
            }

            public void execute(String sql, Object... parameters) {
                check(parameters);
                db.exec(sql, parameters);
            }

            public List<Object[]> query(String sql, Object... parameters) {
                check(parameters);
                return db.query(sql, parameters);
            }
        }
        var scope = new Scope();
        try {
            return db.withCancellation(
                    cancellation,
                    () -> {
                        if (!transaction) {
                            return work.apply(scope);
                        }
                        var result = new java.util.concurrent.atomic.AtomicReference<T>();
                        db.transaction(false, () -> result.set(work.apply(scope)));
                        return result.get();
                    });
        } finally {
            scope.valid = false;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            db.close();
        }
    }
}
