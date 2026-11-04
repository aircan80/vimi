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
 * Saved World camera bookmarks. With a library database they persist in two versioned
 * {@code world_bookmarks*} tables; without one they last for the session. All SQL runs on one
 * bounded daemon worker, never on the JavaFX thread. Unreadable rows are counted, not repaired.
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
    private boolean prepared;

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

    @Override
    public void close() {
        worker.shutdown();
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
            connection.run(Cancellation.NONE, false, sql -> {
                sql.execute("PRAGMA synchronous=FULL");
                return null;
            });
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
            connection.run(Cancellation.NONE, false, sql -> {
                sql.execute("PRAGMA synchronous=FULL");
                return null;
            });
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
