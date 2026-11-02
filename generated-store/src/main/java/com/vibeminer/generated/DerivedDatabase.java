package com.vibeminer.generated;

import com.vibeminer.store.DerivedSqliteConnection;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Bounded asynchronous SQLite actor for derived grids. Upper layers own schemas and typed queries.
 */
public final class DerivedDatabase implements AutoCloseable {
    public interface Session {
        void execute(String sql, Object... parameters);

        List<Object[]> query(String sql, Object... parameters);
    }

    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128),
                    Thread.ofVirtual().name("vimi-derived-index-", 0).factory());
    private DerivedSqliteConnection connection;
    private CompletableFuture<Void> closing;

    private DerivedDatabase() {}

    public static CompletableFuture<DerivedDatabase> open(Path path, Cancellation token) {
        var database = new DerivedDatabase();
        var result = new CompletableFuture<DerivedDatabase>();
        database.submit(
                        token,
                        () -> {
                            try {
                                java.nio.file.Files.createDirectories(
                                        path.toAbsolutePath().getParent());
                            } catch (java.io.IOException error) {
                                throw new java.io.UncheckedIOException(error);
                            }
                            database.connection = new DerivedSqliteConnection(path);
                            return database;
                        })
                .whenComplete(
                        (value, error) -> {
                            if (error != null) {
                                database.closeAsync();
                                result.completeExceptionally(error);
                            } else if (!result.complete(value)) {
                                database.closeAsync();
                            }
                        });
        return result;
    }

    public <T> CompletableFuture<T> read(Function<Session, T> operation, Cancellation token) {
        return run(operation, token, false);
    }

    public <T> CompletableFuture<T> transaction(
            Function<Session, T> operation, Cancellation token) {
        return run(operation, token, true);
    }

    private <T> CompletableFuture<T> run(
            Function<Session, T> operation, Cancellation token, boolean transaction) {
        return submit(
                token,
                () ->
                        connection.run(
                                token.objectToken(),
                                transaction,
                                session ->
                                        operation.apply(
                                                new Session() {
                                                    public void execute(
                                                            String sql, Object... parameters) {
                                                        session.execute(sql, parameters);
                                                    }

                                                    public List<Object[]> query(
                                                            String sql, Object... parameters) {
                                                        return session.query(sql, parameters);
                                                    }
                                                })));
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private synchronized <T> CompletableFuture<T> submit(Cancellation token, Work<T> operation) {
        if (closing != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Derived database closed"));
        }
        var result = new CompletableFuture<T>();
        try {
            worker.execute(
                    () -> {
                        try {
                            token.check();
                            result.complete(operation.run());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RejectedExecutionException full) {
            result.completeExceptionally(
                    new IllegalStateException("Derived database request queue full", full));
        }
        return result.copy();
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing.copy();
        }
        closing = new CompletableFuture<>();
        worker.shutdown();
        var result = closing;
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {
                            /* Drain accepted operations. */
                        }
                        if (connection != null) {
                            connection.close();
                        }
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result.copy();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
