package com.vimi.gui;

import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application-owned preparation facts; all calls belong to background startup/preparation workers.
 */
final class SqlPreparationReceipts {
    private final Path database;

    SqlPreparationReceipts(Path database) {
        this.database = database;
        try (var header = DerivedSqliteConnection.openReadOnly(database)) {
            header.run(
                    Cancellation.NONE,
                    false,
                    session -> {
                        if (((Number) session.query("PRAGMA application_id").getFirst()[0])
                                                .intValue()
                                        != 2
                                || ((Number) session.query("PRAGMA user_version").getFirst()[0])
                                                .intValue()
                                        != 1
                                || ((Number)
                                                        session.query(
                                                                        "SELECT version FROM"
                                                                            + " storage_metadata")
                                                                .getFirst()[0])
                                                .intValue()
                                        != 1) {
                            throw new IllegalArgumentException(
                                    "Preparation history requires a unified pack library");
                        }
                        boolean exists =
                                ((Number)
                                                        session.query(
                                                                        "SELECT count(*) FROM"
                                                                            + " sqlite_master WHERE"
                                                                            + " type='table' AND"
                                                                            + " name='builds_preparation_metadata'")
                                                                .getFirst()[0])
                                                .longValue()
                                        != 0;
                        boolean receiptsExist =
                                ((Number)
                                                        session.query(
                                                                        "SELECT count(*) FROM"
                                                                            + " sqlite_master WHERE"
                                                                            + " type='table' AND"
                                                                            + " name='builds_preparation_receipts'")
                                                                .getFirst()[0])
                                                .longValue()
                                        != 0;
                        if (exists != receiptsExist) {
                            throw new IllegalArgumentException(
                                    "Incomplete or unversioned preparation schema");
                        }
                        if (exists) {
                            session.query(
                                    "SELECT"
                                        + " identity,product,build_config,cdn_config,region,locale,readiness,sequence"
                                        + " FROM builds_preparation_receipts LIMIT 0");
                        }
                        if (exists
                                && (((Number)
                                                                session.query(
                                                                                "SELECT count(*)"
                                                                                    + " FROM"
                                                                                    + " builds_preparation_metadata")
                                                                        .getFirst()[0])
                                                        .longValue()
                                                != 1
                                        || ((Number)
                                                                session.query(
                                                                                "SELECT version"
                                                                                    + " FROM"
                                                                                    + " builds_preparation_metadata")
                                                                        .getFirst()[0])
                                                        .longValue()
                                                != 1)) {
                            throw new IllegalArgumentException(
                                    "Unsupported preparation history version");
                        }
                        return null;
                    });
        } catch (Exception failure) {
            throw new IllegalStateException("Preparation history could not be validated", failure);
        }
        write(
                session -> {
                    session.execute(
                            "CREATE TABLE IF NOT EXISTS builds_preparation_metadata(version INTEGER"
                                + " NOT NULL)");
                    if (((Number)
                                            session.query(
                                                            "SELECT count(*) FROM"
                                                                + " builds_preparation_metadata")
                                                    .getFirst()[0])
                                    .longValue()
                            == 0) {
                        session.execute("INSERT INTO builds_preparation_metadata VALUES(1)");
                    }
                    session.execute(
                            "CREATE TABLE IF NOT EXISTS builds_preparation_receipts(identity TEXT"
                                + " PRIMARY KEY,product TEXT NOT NULL,build_config TEXT NOT"
                                + " NULL,cdn_config TEXT,region TEXT,locale TEXT NOT NULL,readiness"
                                + " TEXT NOT NULL,sequence INTEGER NOT NULL)");
                });
    }

    Map<String, PreparationHistory.Receipt> load() {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        if (((Number)
                                                session.query(
                                                                "SELECT count(*) FROM"
                                                                    + " builds_preparation_receipts")
                                                        .getFirst()[0])
                                        .longValue()
                                > 1024) {
                            throw new IllegalArgumentException(
                                    "Preparation history exceeds its bound");
                        }
                        var rows =
                                session.query(
                                        "SELECT"
                                            + " identity,product,build_config,cdn_config,region,locale,readiness"
                                            + " FROM builds_preparation_receipts ORDER BY sequence"
                                            + " LIMIT 1024");
                        var result = new LinkedHashMap<String, PreparationHistory.Receipt>();
                        for (Object[] row : rows) {
                            var receipt =
                                    new PreparationHistory.Receipt(
                                            (String) row[0],
                                            (String) row[1],
                                            (String) row[2],
                                            (String) row[3],
                                            (String) row[4],
                                            (String) row[5],
                                            (String) row[6]);
                            com.vibeminer.files.FileLocales.bitOf(receipt.locale());
                            if (receipt.milestone() == BuildPreparation.Ready.NOT_PREPARED) {
                                throw new IllegalArgumentException(
                                        "Invalid saved preparation milestone");
                            }
                            result.put(receipt.identity(), receipt);
                        }
                        return java.util.Collections.unmodifiableMap(result);
                    });
        } catch (Exception failure) {
            throw new IllegalStateException("Preparation history could not be read", failure);
        }
    }

    void save(
            PreparationHistory.Receipt receipt,
            Map<String, PreparationHistory.Receipt> previous,
            Map<String, PreparationHistory.Receipt> next) {
        write(
                session -> {
                    session.execute(
                            "INSERT INTO builds_preparation_receipts VALUES(?,?,?,?,?,?,?,(SELECT"
                                + " coalesce(max(sequence),0)+1 FROM builds_preparation_receipts))"
                                + " ON CONFLICT(identity) DO UPDATE SET"
                                + " product=excluded.product,build_config=excluded.build_config,cdn_config=excluded.cdn_config,region=excluded.region,locale=excluded.locale,readiness=excluded.readiness,sequence=excluded.sequence",
                            receipt.identity(),
                            receipt.product(),
                            receipt.config(),
                            receipt.cdnConfig(),
                            receipt.region(),
                            receipt.locale(),
                            receipt.ready());
                    for (String removed : previous.keySet()) {
                        if (!next.containsKey(removed)) {
                            session.execute(
                                    "DELETE FROM builds_preparation_receipts WHERE identity=?",
                                    removed);
                        }
                    }
                });
    }

    private void write(java.util.function.Consumer<DerivedSqliteConnection.Session> work) {
        try (var connection = new DerivedSqliteConnection(database)) {
            connection.run(
                    Cancellation.NONE,
                    false,
                    session -> {
                        session.execute("PRAGMA synchronous=FULL");
                        return null;
                    });
            connection.run(
                    Cancellation.NONE,
                    true,
                    session -> {
                        work.accept(session);
                        return null;
                    });
        } catch (Exception failure) {
            throw new IllegalStateException("Preparation history could not be saved", failure);
        }
    }
}
