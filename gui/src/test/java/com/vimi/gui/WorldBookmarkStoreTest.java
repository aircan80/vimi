package com.vimi.gui;

import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;
import com.vibeminer.store.ObjectStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletionException;

/** Session and unified-library bookmark persistence with authored records only. */
public final class WorldBookmarkStoreTest {
    private WorldBookmarkStoreTest() {}

    private static WorldBookmarks.Bookmark bookmark(String name) {
        return new WorldBookmarks.Bookmark(name, 0, "Azeroth", "Eastern Kingdoms", 32, 48,
                -10, -8600, 60, 1, -.2, "authored");
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("vimi-world-bookmarks-");
        try {
            session();
            library(directory.resolve("library"));
            foreignDatabase(directory.resolve("plain.sqlite3"));
            controls(directory.resolve("controls-library"));
            System.out.println("World bookmarks: session, persisted order/removal, unreadable rows,"
                    + " limit and schema gates passed; World controls: session, persisted upsert,"
                    + " version and library gates passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static void session() {
        try (var store = WorldBookmarkStore.session()) {
            require(!store.persistent(), "Session store is not persistent");
            var first = store.add(bookmark("One")).join();
            var second = store.add(bookmark("Two")).join();
            require(second.entries().size() == 2 && !second.persistent(), "Session bookmarks listed");
            var removed = store.remove(first.entries().getFirst().id()).join();
            require(removed.entries().size() == 1
                    && removed.entries().getFirst().bookmark().name().equals("Two"), "Session removal");
        }
    }

    private static void library(Path root) throws Exception {
        var library = ObjectStore.openLibrary(root).join();
        try {
            Path database = library.databasePath();
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                require(store.load().join().entries().isEmpty(), "New library has no bookmarks");
                store.add(bookmark("Goldshire")).join();
                store.add(bookmark("Stormwind gate")).join();
            }
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                var reopened = store.load().join();
                require(reopened.persistent() && reopened.entries().size() == 2
                        && reopened.entries().get(0).bookmark().equals(bookmark("Goldshire"))
                        && reopened.entries().get(1).bookmark().name().equals("Stormwind gate"),
                        "Bookmarks persist in insertion order");
                write(database, "INSERT INTO world_bookmarks(record) VALUES('v9|future')");
                var withUnreadable = store.load().join();
                require(withUnreadable.entries().size() == 2 && withUnreadable.unreadable() == 1,
                        "Unreadable rows are counted, not dropped or repaired");
                var removed = store.remove(reopened.entries().getFirst().id()).join();
                require(removed.entries().size() == 1 && removed.unreadable() == 1, "Removal persists");
                for (int i = removed.entries().size() + removed.unreadable();
                        i < WorldBookmarks.MAX_BOOKMARKS; i++) {
                    store.add(bookmark("Fill " + i)).join();
                }
                try {
                    store.add(bookmark("Over")).join();
                    throw new AssertionError("Bookmark limit is enforced");
                } catch (CompletionException expected) {
                    require(expected.getCause() instanceof IllegalStateException, "Limit refusal");
                }
            }
            write(database, "UPDATE world_bookmarks_metadata SET version=2");
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                try {
                    store.load().join();
                    throw new AssertionError("Future schema is refused");
                } catch (CompletionException expected) {
                    require(expected.getCause() instanceof IllegalArgumentException, "Schema refusal");
                }
            }
        } finally {
            library.closeAsync().join();
        }
    }

    private static void foreignDatabase(Path path) throws Exception {
        write(path, "CREATE TABLE unrelated(x)");
        try (var store = WorldBookmarkStore.forLibrary(path)) {
            try {
                store.load().join();
                throw new AssertionError("Non-library database is refused");
            } catch (CompletionException expected) {
                require(expected.getCause() instanceof IllegalArgumentException, "Library gate");
            }
        }
        try (var connection = DerivedSqliteConnection.openReadOnly(path)) {
            long tables = connection.run(Cancellation.NONE, false, sql -> ((Number) sql.query(
                    "SELECT count(*) FROM sqlite_master WHERE name LIKE 'world_bookmarks%'")
                    .getFirst()[0]).longValue());
            require(tables == 0, "Refused database is not modified");
        }
    }

    /** Explorer controls: one encoded row beside the bookmarks, with the same gates. */
    private static void controls(Path root) throws Exception {
        try (var session = WorldBookmarkStore.session()) {
            require(session.loadControls().join() == null, "Session store starts without controls");
            session.saveControls("v1;invertY=true").join();
            require("v1;invertY=true".equals(session.loadControls().join()), "Session controls kept");
        }
        var library = ObjectStore.openLibrary(root).join();
        try {
            Path database = library.databasePath();
            var custom = WorldControls.DEFAULTS.withSensitivity(.011).withInvertY(true);
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                require(store.loadControls().join() == null, "A new library has no controls");
                store.saveControls(WorldControls.DEFAULTS.encode()).join();
                store.saveControls(custom.encode()).join();
                store.add(bookmark("Beside the controls")).join();
            }
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                require(WorldControls.decode(store.loadControls().join()).equals(custom),
                        "The last saved controls survive reopening");
                require(store.load().join().entries().size() == 1, "Bookmarks are untouched");
                var loaded = WorldControls.Store.of(store).load().join();
                require(WorldControls.decode(loaded).equals(custom), "The view's store adapter reads the same row");
            }
            try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
                long rows = connection.run(Cancellation.NONE, false, sql -> ((Number) sql.query(
                        "SELECT count(*) FROM world_controls").getFirst()[0]).longValue());
                require(rows == 1, "Saving replaces the single row: " + rows);
            }
            write(database, "UPDATE world_controls_metadata SET version=2");
            try (var store = WorldBookmarkStore.forLibrary(database)) {
                try {
                    store.loadControls().join();
                    throw new AssertionError("A newer controls schema is refused");
                } catch (CompletionException expected) {
                    require(expected.getCause() instanceof IllegalArgumentException, "Version gate");
                }
                require(store.load().join().entries().size() == 1, "Bookmarks still load");
            }
        } finally {
            library.closeAsync().join();
        }
        var plain = root.resolveSibling("plain-controls.sqlite3");
        write(plain, "CREATE TABLE unrelated(x)");
        try (var store = WorldBookmarkStore.forLibrary(plain)) {
            try {
                store.saveControls("v1").join();
                throw new AssertionError("Non-library database is refused");
            } catch (CompletionException expected) {
                require(expected.getCause() instanceof IllegalArgumentException, "Library gate");
            }
        }
        try (var connection = DerivedSqliteConnection.openReadOnly(plain)) {
            long tables = connection.run(Cancellation.NONE, false, sql -> ((Number) sql.query(
                    "SELECT count(*) FROM sqlite_master WHERE name LIKE 'world_controls%'")
                    .getFirst()[0]).longValue());
            require(tables == 0, "Refused database is not modified");
        }
    }

    private static void write(Path database, String statement) throws Exception {
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(Cancellation.NONE, true, sql -> {
                sql.execute(statement);
                return null;
            });
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
