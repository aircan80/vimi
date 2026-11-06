package com.vimi.gui;

import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Saved World camera bookmarks and the World explorer's control preferences. With a library
 * database bookmarks persist in two versioned {@code world_bookmarks*} tables and controls in two
 * versioned {@code world_controls*} tables (one encoded {@link WorldControls} row); without one
 * both last for the session. All SQL runs on one bounded daemon worker, never on the JavaFX
 * thread. Unreadable rows are counted, not repaired.
 */
final class WorldBookmarkStore implements AutoCloseable {
    record Entry(long id, WorldBookmarks.Bookmark bookmark) {
        @Override
        public String toString() {
            return bookmark.toString();
        }
    }

    record Snapshot(List<Entry> entries, int unreadable, boolean persistent) {
        Snapshot {
            entries = List.copyOf(entries);
        }
    }

    private final Path database;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                    Thread.ofPlatform().daemon().name("vimi-world-bookmarks").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    // Worker-confined state.
    private final List<Entry> session = new ArrayList<>();
    private long nextSessionId = 1;
    private boolean prepared, controlsPrepared;
    private String sessionControls;

    private WorldBookmarkStore(Path database) {
        this.database = database;
    }

    /** Session-only bookmarks, used when no library database is available. */
    static WorldBookmarkStore session() {
        return new WorldBookmarkStore(null);
    }

    /** Persistent bookmarks in the unified library database; the schema is checked lazily. */
    static WorldBookmarkStore forLibrary(Path database) {
        return new WorldBookmarkStore(Objects.requireNonNull(database));
    }

    boolean persistent() {
        return database != null;
    }

    CompletableFuture<Snapshot> load() {
        return submit(this::read);
    }

    CompletableFuture<Snapshot> add(WorldBookmarks.Bookmark bookmark) {
        Objects.requireNonNull(bookmark);
        return submit(() -> {
            var current = read();
            if (current.entries().size() + current.unreadable() >= WorldBookmarks.MAX_BOOKMARKS)
                throw new IllegalStateException("Bookmark limit reached");
            if (database == null) {
                session.add(new Entry(nextSessionId++, bookmark));
            } else {
                write(sql -> sql.execute("INSERT INTO world_bookmarks(record) VALUES(?)",
                        WorldBookmarks.encode(bookmark)));
            }
            return read();
        });
    }

    CompletableFuture<Snapshot> remove(long id) {
        return submit(() -> {
            if (database == null) session.removeIf(entry -> entry.id() == id);
            else write(sql -> sql.execute("DELETE FROM world_bookmarks WHERE id=?", id));
            return read();
        });
    }

    /** The stored encoded controls, or null when none were saved. */
    CompletableFuture<String> loadControls() {
        return submit(() -> {
            if (database == null) return sessionControls;
            prepareControls();
            try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
                return connection.run(Cancellation.NONE, false, sql -> {
                    var rows = sql.query("SELECT record FROM world_controls WHERE id=1");
                    return rows.isEmpty() ? null : (String) rows.getFirst()[0];
                });
            }
        });
    }

    /** Replaces the stored encoded controls. */
    CompletableFuture<Void> saveControls(String record) {
        Objects.requireNonNull(record);
        return submit(() -> {
            if (database == null) {
                sessionControls = record;
                return null;
            }
            prepareControls();
            try (var connection = new DerivedSqliteConnection(database)) {
                connection.run(Cancellation.NONE, true, sql -> {
                    sql.execute("INSERT INTO world_controls(id,record) VALUES(1,?)"
                            + " ON CONFLICT(id) DO UPDATE SET record=excluded.record", record);
                    return null;
                });
            }
            return null;
        });
    }

    @Override
    public void close() {
        worker.shutdown();
    }

    /** The same library and versioned-schema gate as the bookmarks, for the controls tables. */
    private void prepareControls() throws Exception {
        if (controlsPrepared) return;
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            connection.run(Cancellation.NONE, false, sql -> {
                if (((Number) sql.query("PRAGMA application_id").getFirst()[0]).intValue() != 2
                        || ((Number) sql.query("PRAGMA user_version").getFirst()[0]).intValue() != 1)
                    throw new IllegalArgumentException("World controls require a unified library");
                long tables = ((Number) sql.query("SELECT count(*) FROM sqlite_master WHERE"
                        + " type='table' AND name IN ('world_controls_metadata','world_controls')")
                        .getFirst()[0]).longValue();
                if (tables != 0 && tables != 2)
                    throw new IllegalArgumentException("Incomplete world controls schema");
                if (tables == 2) {
                    var versions = sql.query("SELECT version FROM world_controls_metadata");
                    if (versions.size() != 1 || ((Number) versions.getFirst()[0]).intValue() != 1)
                        throw new IllegalArgumentException("Unsupported world controls schema");
                    sql.query("SELECT id,record FROM world_controls LIMIT 0");
                }
                return null;
            });
        }
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(Cancellation.NONE, true, sql -> {
                sql.execute("CREATE TABLE IF NOT EXISTS world_controls_metadata(version INTEGER NOT NULL)");
                if (sql.query("SELECT version FROM world_controls_metadata").isEmpty())
                    sql.execute("INSERT INTO world_controls_metadata VALUES(1)");
                sql.execute("CREATE TABLE IF NOT EXISTS world_controls(id INTEGER PRIMARY KEY"
                        + " CHECK(id=1), record TEXT NOT NULL)");
                return null;
            });
        }
        controlsPrepared = true;
    }

    private <T> CompletableFuture<T> submit(Callable<T> work) {
        var result = new CompletableFuture<T>();
        try {
            worker.execute(() -> {
                try {
                    result.complete(work.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(rejected);
        }
        return result;
    }

    private interface Write {
        void apply(DerivedSqliteConnection.Session sql);
    }

    private void write(Write change) throws Exception {
        prepare();
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(Cancellation.NONE, true, sql -> {
                change.apply(sql);
                return null;
            });
        }
    }

    private Snapshot read() throws Exception {
        if (database == null) return new Snapshot(session, 0, false);
        prepare();
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(Cancellation.NONE, false, sql -> {
                var rows = sql.query("SELECT id,record FROM world_bookmarks ORDER BY id LIMIT ?",
                        WorldBookmarks.MAX_BOOKMARKS + 1);
                if (rows.size() > WorldBookmarks.MAX_BOOKMARKS)
                    throw new IllegalStateException("Too many saved bookmarks");
                var entries = new ArrayList<Entry>();
                int unreadable = 0;
                for (var row : rows) {
                    try {
                        entries.add(new Entry(((Number) row[0]).longValue(),
                                WorldBookmarks.decode((String) row[1])));
                    } catch (RuntimeException invalid) {
                        unreadable++;
                    }
                }
                return new Snapshot(entries, unreadable, true);
            });
        }
    }

    /** Same unified-library and versioned-schema gate as the other application preferences. */
    private void prepare() throws Exception {
        if (prepared) return;
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            connection.run(Cancellation.NONE, false, sql -> {
                if (((Number) sql.query("PRAGMA application_id").getFirst()[0]).intValue() != 2
                        || ((Number) sql.query("PRAGMA user_version").getFirst()[0]).intValue() != 1)
                    throw new IllegalArgumentException("World bookmarks require a unified library");
                long tables = ((Number) sql.query("SELECT count(*) FROM sqlite_master WHERE"
                        + " type='table' AND name IN ('world_bookmarks_metadata','world_bookmarks')")
                        .getFirst()[0]).longValue();
                if (tables != 0 && tables != 2)
                    throw new IllegalArgumentException("Incomplete world bookmark schema");
                if (tables == 2) {
                    var versions = sql.query("SELECT version FROM world_bookmarks_metadata");
                    if (versions.size() != 1 || ((Number) versions.getFirst()[0]).intValue() != 1)
                        throw new IllegalArgumentException("Unsupported world bookmark schema");
                    sql.query("SELECT id,record FROM world_bookmarks LIMIT 0");
                }
                return null;
            });
        }
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(Cancellation.NONE, true, sql -> {
                sql.execute("CREATE TABLE IF NOT EXISTS world_bookmarks_metadata(version INTEGER NOT NULL)");
                if (sql.query("SELECT version FROM world_bookmarks_metadata").isEmpty())
                    sql.execute("INSERT INTO world_bookmarks_metadata VALUES(1)");
                sql.execute("CREATE TABLE IF NOT EXISTS world_bookmarks(id INTEGER PRIMARY KEY,"
                        + " record TEXT NOT NULL)");
                return null;
            });
        }
        prepared = true;
    }
}
