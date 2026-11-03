package com.vimi.gui;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;

import java.nio.file.Path;
import java.util.List;

/** Background-only persistence of user bulk-download policy in the shared library. */
final class DownloadIntents {
    record Entry(
            String identity, BuildRef build, boolean enabled, boolean paused, String outcome) {}

    private final Path database;

    DownloadIntents(Path database) throws Exception {
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
                                        != 1
                                || ((Number)
                                                        sql.query(
                                                                        "SELECT version FROM"
                                                                            + " storage_metadata")
                                                                .getFirst()[0])
                                                .intValue()
                                        != 1) {
                            throw new IllegalArgumentException(
                                    "Downloads require a unified pack library");
                        }
                        long schemas =
                                ((Number)
                                                sql.query(
                                                                "SELECT count(*) FROM sqlite_master"
                                                                    + " WHERE type='table' AND name"
                                                                    + " IN ('builds_download_metadata','builds_download_intents')")
                                                        .getFirst()[0])
                                        .longValue();
                        if (schemas != 0 && schemas != 2)
                            throw new IllegalArgumentException("Incomplete download schema");
                        if (schemas == 2) {
                            var versions =
                                    sql.query("SELECT version FROM builds_download_metadata");
                            if (versions.size() != 1
                                    || ((Number) versions.getFirst()[0]).intValue() != 1)
                                throw new IllegalArgumentException("Unsupported download schema");
                            sql.query(
                                    "SELECT"
                                        + " identity,product,build_config,cdn_config,region,enabled,paused,outcome"
                                        + " FROM builds_download_intents LIMIT 0");
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
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS builds_download_metadata(version"
                                    + " INTEGER NOT NULL)");
                        if (sql.query("SELECT version FROM builds_download_metadata").isEmpty())
                            sql.execute("INSERT INTO builds_download_metadata VALUES(1)");
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS builds_download_intents(identity TEXT"
                                    + " PRIMARY KEY,product TEXT NOT NULL,build_config TEXT NOT"
                                    + " NULL,cdn_config TEXT NOT NULL,region TEXT NOT NULL,enabled"
                                    + " INTEGER NOT NULL CHECK(enabled IN (0,1)),paused INTEGER NOT"
                                    + " NULL CHECK(paused IN (0,1)),outcome TEXT) WITHOUT ROWID");
                        return null;
                    });
        }
    }

    List<Entry> load() throws Exception {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    Cancellation.NONE,
                    false,
                    sql -> {
                        var rows =
                                sql.query(
                                        "SELECT"
                                            + " identity,product,build_config,cdn_config,region,enabled,paused,outcome"
                                            + " FROM builds_download_intents ORDER BY identity"
                                            + " LIMIT 1025");
                        if (rows.size() > 1024)
                            throw new IllegalArgumentException("Too many download intents");
                        return rows.stream()
                                .map(
                                        row ->
                                                new Entry(
                                                        (String) row[0],
                                                        new BuildRef(
                                                                (String) row[1],
                                                                (String) row[2],
                                                                (String) row[3],
                                                                (String) row[4]),
                                                        ((Number) row[5]).intValue() != 0,
                                                        ((Number) row[6]).intValue() != 0,
                                                        (String) row[7]))
                                .toList();
                    });
        }
    }

    void save(Entry entry) throws Exception {
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
                                "INSERT INTO builds_download_intents VALUES(?,?,?,?,?,?,?,?) ON"
                                    + " CONFLICT(identity) DO UPDATE SET"
                                    + " product=excluded.product,build_config=excluded.build_config,cdn_config=excluded.cdn_config,region=excluded.region,enabled=excluded.enabled,paused=excluded.paused,outcome=excluded.outcome",
                                entry.identity(),
                                entry.build().product(),
                                entry.build().buildConfig(),
                                entry.build().cdnConfig(),
                                entry.build().region(),
                                entry.enabled() ? 1 : 0,
                                entry.paused() ? 1 : 0,
                                entry.outcome());
                        return null;
                    });
        }
    }
}
