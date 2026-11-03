package com.vimi.gui;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Application-owned source policy. Calls belong to a serialized background worker. */
final class SourcePreferences {
    private final Path database;
    private volatile Map<String, String> values;

    SourcePreferences(Path database) throws Exception {
        this.database = database;
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            connection.run(
                    Cancellation.NONE,
                    false,
                    sql -> {
                        if (((Number) sql.query("PRAGMA application_id").getFirst()[0]).intValue()
                                        != 2
                                || ((Number) sql.query("PRAGMA user_version").getFirst()[0])
                                                .intValue()
                                        != 1) {
                            throw new IllegalArgumentException(
                                    "Source policy requires a unified library");
                        }
                        long tables =
                                ((Number)
                                                sql.query(
                                                                "SELECT count(*) FROM sqlite_master"
                                                                    + " WHERE type='table' AND name"
                                                                    + " IN ('sources_metadata','sources_preferences')")
                                                        .getFirst()[0])
                                        .longValue();
                        if (tables != 0 && tables != 2)
                            throw new IllegalArgumentException("Incomplete source schema");
                        if (tables == 2) {
                            var versions = sql.query("SELECT version FROM sources_metadata");
                            if (versions.size() != 1
                                    || ((Number) versions.getFirst()[0]).intValue() != 1)
                                throw new IllegalArgumentException("Unsupported source schema");
                            sql.query("SELECT k,v FROM sources_preferences LIMIT 0");
                        }
                        return null;
                    });
        }
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    Cancellation.NONE,
                    false,
                    sql -> {
                        sql.execute("PRAGMA synchronous=FULL");
                        return null;
                    });
            values =
                    connection.run(
                            Cancellation.NONE,
                            true,
                            sql -> {
                                sql.execute(
                                        "CREATE TABLE IF NOT EXISTS sources_metadata(version"
                                            + " INTEGER NOT NULL)");
                                if (sql.query("SELECT version FROM sources_metadata").isEmpty())
                                    sql.execute("INSERT INTO sources_metadata VALUES(1)");
                                sql.execute(
                                        "CREATE TABLE IF NOT EXISTS sources_preferences(k TEXT"
                                            + " PRIMARY KEY,v TEXT NOT NULL) WITHOUT ROWID");
                                var result = new HashMap<String, String>();
                                var rows =
                                        sql.query("SELECT k,v FROM sources_preferences LIMIT 4097");
                                if (rows.size() > 4096)
                                    throw new IllegalArgumentException(
                                            "Too many source preferences");
                                for (var row : rows) result.put((String) row[0], (String) row[1]);
                                return Map.copyOf(result);
                            });
        }
    }

    String get(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    void put(String key, String value) throws Exception {
        if (key == null || key.length() > 1200 || value == null || value.length() > 4096)
            throw new IllegalArgumentException("Invalid source preference");
        if (!values.containsKey(key) && values.size() >= 4096)
            throw new IllegalStateException("Too many source preferences");
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    Cancellation.NONE,
                    false,
                    sql -> {
                        sql.execute("PRAGMA synchronous=FULL");
                        return null;
                    });
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute(
                                "INSERT INTO sources_preferences VALUES(?,?) ON CONFLICT(k) DO"
                                    + " UPDATE SET v=excluded.v",
                                key,
                                value);
                        return null;
                    });
        }
        var next = new HashMap<>(values);
        next.put(key, value);
        values = Map.copyOf(next);
    }
}
