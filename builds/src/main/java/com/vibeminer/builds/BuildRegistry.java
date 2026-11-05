package com.vibeminer.builds;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Authoritative persisted metadata-load status, independent of table/item preparation. The
 * application supplies the initialized shared library database and owns consumer lifetimes.
 */
public final class BuildRegistry implements AutoCloseable {
    public static final int PAGE_LIMIT = 1024;

    public enum Status {
        DISCOVERED,
        LOADING,
        LOADED,
        FAILED,
        INTERRUPTED
    }

    public record Entry(
            String identity,
            BuildTypes.BuildRef reference,
            String version,
            String locale,
            Status status,
            String errorCode,
            String errorMessage,
            Instant updatedAt,
            long attempt) {}

    /** A persisted generation prevents a superseded load from overwriting a newer result. */
    public record LoadAttempt(String identity, long attempt) {
        public LoadAttempt {
            BuildRegistry.identity(identity);
            if (attempt < 1) {
                throw new IllegalArgumentException("A load attempt must be positive");
            }
        }
    }

    private static final String COLUMNS =
            "identity,product,build_config,cdn_config,region,version,locale,status,error_code,"
                    + "error_message,updated_at,attempt";
    private final Path path;
    private final Object gate = new Object();
    private final ThreadPoolExecutor actor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64),
                    work -> {
                        Thread worker = new Thread(work, "vimi-build-registry");
                        worker.setDaemon(true);
                        return worker;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
    private DerivedSqliteConnection database;
    private FileChannel lockChannel;
    private FileLock lock;
    private boolean closing;
    private CompletableFuture<Void> closed;

    private BuildRegistry(Path path) {
        this.path = path;
    }

    public static CompletableFuture<BuildRegistry> open(Path path) {
        Objects.requireNonNull(path);
        var registry = new BuildRegistry(path.toAbsolutePath().normalize());
        CompletableFuture<BuildRegistry> opening =
                registry.submit(
                        () -> {
                            try {
                                registry.initialize();
                                return registry;
                            } catch (Throwable failure) {
                                try {
                                    registry.release();
                                } catch (Throwable cleanup) {
                                    failure.addSuppressed(cleanup);
                                }
                                registry.actor.shutdown();
                                throw failure;
                            }
                        });
        opening.whenComplete(
                (value, error) -> {
                    if (opening.isCancelled()) {
                        registry.closeAsync();
                    }
                });
        return opening;
    }

    private void initialize() throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Initialize the shared library before its registry");
        }
        try (var header = DerivedSqliteConnection.openReadOnly(path)) {
            header.run(
                    Cancellation.NONE,
                    false,
                    session -> {
                        if (number(session.query("PRAGMA application_id").getFirst()[0]) != 2
                                || number(session.query("PRAGMA user_version").getFirst()[0])
                                        != 1) {
                            throw new IllegalArgumentException(
                                    "Build registry requires a unified pack library");
                        }
                        List<Object[]> storage =
                                session.query("SELECT version FROM storage_metadata");
                        if (storage.size() != 1 || number(storage.getFirst()[0]) != 1) {
                            throw new IllegalArgumentException(
                                    "Unsupported unified storage format");
                        }
                        // The storage view is the common original/generated physical-presence
                        // boundary.
                        session.query("SELECT k,length,role FROM storage_objects LIMIT 0");
                        long existing =
                                number(
                                        session.query(
                                                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='builds_registry_metadata'")
                                                .getFirst()[0]);
                        if (existing != 0) {
                            List<Object[]> rows =
                                    session.query("SELECT version FROM builds_registry_metadata");
                            if (rows.size() != 1 || number(rows.getFirst()[0]) != 1) {
                                throw new IllegalArgumentException(
                                        "Unsupported build registry format");
                            }
                        } else if (number(
                                        session.query(
                                                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name GLOB 'builds_registry*'")
                                                .getFirst()[0])
                                != 0) {
                            throw new IllegalArgumentException(
                                    "Build registry has no format version");
                        }
                        return null;
                    });
        }
        lockChannel =
                FileChannel.open(
                        path.resolveSibling(path.getFileName() + ".builds_registry.lock"),
                        CREATE,
                        WRITE);
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException busy) {
            throw new IllegalStateException("Build registry is already open", busy);
        }
        if (lock == null) {
            throw new IllegalStateException("Build registry is already open");
        }
        database = new DerivedSqliteConnection(path);
        database.run(
                Cancellation.NONE,
                true,
                session -> {
                    session.execute(
                            "CREATE TABLE IF NOT EXISTS builds_registry_metadata(version INTEGER NOT NULL)");
                    if (number(
                                    session.query("SELECT count(*) FROM builds_registry_metadata")
                                            .getFirst()[0])
                            == 0) {
                        session.execute("INSERT INTO builds_registry_metadata VALUES(1)");
                    }
                    session.execute(
                            "CREATE TABLE IF NOT EXISTS builds_registry(identity TEXT PRIMARY KEY,product TEXT NOT NULL,build_config TEXT NOT NULL,cdn_config TEXT NOT NULL,region TEXT,version TEXT,locale TEXT,status TEXT NOT NULL CHECK(status IN ('DISCOVERED','LOADING','LOADED','FAILED','INTERRUPTED')),error_code TEXT,error_message TEXT,updated_at INTEGER NOT NULL,attempt INTEGER NOT NULL CHECK(attempt>=0)) WITHOUT ROWID");
                    // A prior process cannot still own an accepted operation after its library
                    // owner exits.
                    session.execute(
                            "UPDATE builds_registry SET status='INTERRUPTED',error_code='BUILD_LOAD_INTERRUPTED',error_message='The previous metadata load did not finish',updated_at=? WHERE status='LOADING'",
                            Instant.now().toEpochMilli());
                    return null;
                });
    }

    /** Discovery updates route/display facts, preserving prior load status and attempt identity. */
    public CompletableFuture<Entry> observe(
            String identity,
            BuildTypes.BuildRef reference,
            String version,
            String locale,
            Cancellation token) {
        validate(identity, reference, version, locale, token);
        return submit(
                () ->
                        run(
                                token,
                                true,
                                session -> {
                                    upsert(session, identity, reference, version, locale);
                                    return find(session, identity);
                                }));
    }

    public CompletableFuture<LoadAttempt> beginLoad(
            String identity,
            BuildTypes.BuildRef reference,
            String version,
            String locale,
            Cancellation token) {
        validate(identity, reference, version, locale, token);
        return submit(
                () ->
                        run(
                                token,
                                true,
                                session -> {
                                    upsert(session, identity, reference, version, locale);
                                    Entry previous = find(session, identity);
                                    long attempt = Math.addExact(previous.attempt(), 1);
                                    session.execute(
                                            "UPDATE builds_registry SET status='LOADING',error_code=NULL,error_message=NULL,updated_at=?,attempt=? WHERE identity=?",
                                            Instant.now().toEpochMilli(),
                                            attempt,
                                            identity);
                                    return new LoadAttempt(identity, attempt);
                                }));
    }

    public CompletableFuture<Boolean> loaded(LoadAttempt attempt, Cancellation token) {
        return finish(attempt, Status.LOADED, null, null, token);
    }

    public CompletableFuture<Boolean> failed(
            LoadAttempt attempt, String code, String message, Cancellation token) {
        bounded(code, 128, "Error code");
        bounded(message, 2048, "Error message");
        return finish(attempt, Status.FAILED, code, message, token);
    }

    public CompletableFuture<Boolean> interrupted(LoadAttempt attempt, Cancellation token) {
        return finish(
                attempt,
                Status.INTERRUPTED,
                "BUILD_LOAD_INTERRUPTED",
                "The metadata load was canceled or interrupted",
                token);
    }

    private CompletableFuture<Boolean> finish(
            LoadAttempt attempt, Status status, String code, String message, Cancellation token) {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(token);
        return submit(
                () ->
                        run(
                                token,
                                true,
                                session -> {
                                    Entry existing = find(session, attempt.identity());
                                    if (existing == null
                                            || existing.attempt() != attempt.attempt()
                                            || existing.status() != Status.LOADING) {
                                        return false;
                                    }
                                    session.execute(
                                            "UPDATE builds_registry SET status=?,error_code=?,error_message=?,updated_at=? WHERE identity=? AND attempt=? AND status='LOADING'",
                                            status.name(),
                                            code,
                                            message,
                                            Instant.now().toEpochMilli(),
                                            attempt.identity(),
                                            attempt.attempt());
                                    return true;
                                }));
    }

    public CompletableFuture<Entry> get(String identity, Cancellation token) {
        identity(identity);
        Objects.requireNonNull(token);
        return submit(() -> run(token, false, session -> find(session, identity)));
    }

    /** Stable bounded paging, including previously loaded builds absent from latest discovery. */
    public CompletableFuture<List<Entry>> list(
            String afterIdentity, int limit, Cancellation token) {
        if (limit < 1 || limit > PAGE_LIMIT) {
            throw new IllegalArgumentException("Registry page limit is 1-1024");
        }
        if (afterIdentity != null) {
            identity(afterIdentity);
        }
        Objects.requireNonNull(token);
        return submit(
                () ->
                        run(
                                token,
                                false,
                                session -> {
                                    List<Object[]> rows =
                                            afterIdentity == null
                                                    ? session.query(
                                                            "SELECT "
                                                                    + COLUMNS
                                                                    + " FROM builds_registry ORDER BY identity LIMIT ?",
                                                            limit)
                                                    : session.query(
                                                            "SELECT "
                                                                    + COLUMNS
                                                                    + " FROM builds_registry WHERE identity>? ORDER BY identity LIMIT ?",
                                                            afterIdentity,
                                                            limit);
                                    return rows.stream().map(BuildRegistry::entry).toList();
                                }));
    }

    private static void upsert(
            DerivedSqliteConnection.Session session,
            String identity,
            BuildTypes.BuildRef reference,
            String version,
            String locale) {
        Entry previous = find(session, identity);
        if (previous != null
                && (!previous.reference().product().equals(reference.product())
                        || !previous.reference().buildConfig().equals(reference.buildConfig()))) {
            throw new IllegalArgumentException("Canonical identity maps to another build");
        }
        session.execute(
                "INSERT INTO builds_registry("
                        + COLUMNS
                        + ") VALUES(?,?,?,?,?,?,?,'DISCOVERED',NULL,NULL,?,0) ON CONFLICT(identity) DO UPDATE SET cdn_config=excluded.cdn_config,region=excluded.region,version=coalesce(excluded.version,builds_registry.version),locale=coalesce(excluded.locale,builds_registry.locale)",
                identity,
                reference.product(),
                reference.buildConfig(),
                reference.cdnConfig(),
                reference.region(),
                version,
                locale,
                Instant.now().toEpochMilli());
    }

    private static Entry find(DerivedSqliteConnection.Session session, String identity) {
        List<Object[]> rows =
                session.query(
                        "SELECT " + COLUMNS + " FROM builds_registry WHERE identity=?", identity);
        return rows.isEmpty() ? null : entry(rows.getFirst());
    }

    private static Entry entry(Object[] row) {
        return new Entry(
                (String) row[0],
                new BuildTypes.BuildRef(
                        (String) row[1], (String) row[2], (String) row[3], (String) row[4]),
                (String) row[5],
                (String) row[6],
                Status.valueOf((String) row[7]),
                (String) row[8],
                (String) row[9],
                Instant.ofEpochMilli(number(row[10])),
                number(row[11]));
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static void validate(
            String identity,
            BuildTypes.BuildRef reference,
            String version,
            String locale,
            Cancellation token) {
        identity(identity);
        Objects.requireNonNull(reference);
        Objects.requireNonNull(token);
        bounded(version, 256, "Version");
        if (locale != null && !locale.matches("[A-Za-z]{4}")) {
            throw new IllegalArgumentException("Locale must be four letters");
        }
    }

    private static void identity(String identity) {
        if (identity == null || identity.isBlank() || identity.length() > 256) {
            throw new IllegalArgumentException("A bounded canonical build identity is required");
        }
    }

    private static void bounded(String value, int maximum, String name) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(name + " is too long");
        }
    }

    private <T> T run(
            Cancellation token,
            boolean transaction,
            Function<DerivedSqliteConnection.Session, T> work)
            throws Exception {
        token.check();
        return database.run(
                token,
                transaction,
                session -> {
                    T result = work.apply(session);
                    token.check();
                    return result;
                });
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> submit(Work<T> work) {
        synchronized (gate) {
            if (closing) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Build registry is closed"));
            }
            var result = new CompletableFuture<T>();
            try {
                actor.execute(
                        () -> {
                            T value = null;
                            Throwable failure = null;
                            try {
                                value = work.run();
                            } catch (Throwable error) {
                                failure = error;
                            }
                            T completed = value;
                            Throwable error = failure;
                            Thread.startVirtualThread(
                                    () -> {
                                        if (error == null) {
                                            result.complete(completed);
                                        } else {
                                            result.completeExceptionally(error);
                                        }
                                    });
                        });
            } catch (RejectedExecutionException failure) {
                result.completeExceptionally(
                        new IllegalStateException("Build registry queue is full", failure));
            }
            return result;
        }
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closed != null) {
                return closed.copy();
            }
            closing = true;
            actor.shutdown();
            closed = new CompletableFuture<>();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            while (!actor.awaitTermination(1, TimeUnit.SECONDS)) {
                                // Drain all accepted metadata writes before releasing the shared
                                // connection.
                            }
                            release();
                            closed.complete(null);
                        } catch (Throwable failure) {
                            closed.completeExceptionally(failure);
                        }
                    });
            return closed.copy();
        }
    }

    private void release() throws Exception {
        try {
            if (database != null) {
                database.close();
            }
        } finally {
            try {
                if (lock != null) {
                    lock.release();
                }
            } finally {
                if (lockChannel != null) {
                    lockChannel.close();
                }
            }
        }
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
