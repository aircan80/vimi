package com.vimi.gui;

import com.vibeminer.builds.Products;
import com.vibeminer.store.Cancellation;
import com.vibeminer.store.DerivedSqliteConnection;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned application preferences; calls belong to the Settings service's background worker. */
final class SettingsPreferences {
    static final int PRODUCT_LIMIT = 256;

    record Stored(Set<String> known, Map<String, Boolean> overrides) {
        Stored {
            known = Set.copyOf(known);
            overrides = Map.copyOf(overrides);
        }
    }

    private final Path database;

    SettingsPreferences(Path database) throws Exception {
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
                                    "Settings require a unified pack library");
                        }
                        long schemas =
                                ((Number)
                                                sql.query(
                                                                "SELECT count(*) FROM sqlite_master"
                                                                    + " WHERE type='table' AND name"
                                                                    + " IN ('settings_metadata','settings_products')")
                                                        .getFirst()[0])
                                        .longValue();
                        if (schemas != 0 && schemas != 2)
                            throw new IllegalArgumentException("Incomplete settings schema");
                        if (schemas == 2) {
                            var versions = sql.query("SELECT version FROM settings_metadata");
                            if (versions.size() != 1
                                    || ((Number) versions.getFirst()[0]).intValue() != 1)
                                throw new IllegalArgumentException("Unsupported settings schema");
                            sql.query("SELECT product,enabled FROM settings_products LIMIT 0");
                        }
                        return null;
                    });
        }
        try (var connection = writable()) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS settings_metadata(version INTEGER NOT"
                                        + " NULL)");
                        if (sql.query("SELECT version FROM settings_metadata").isEmpty())
                            sql.execute("INSERT INTO settings_metadata VALUES(1)");
                        sql.execute(
                                "CREATE TABLE IF NOT EXISTS settings_products(product TEXT PRIMARY"
                                        + " KEY,enabled INTEGER CHECK(enabled IN (0,1))) WITHOUT"
                                        + " ROWID");
                        return null;
                    });
        }
        load();
    }

    Stored load() throws Exception {
        try (var connection = DerivedSqliteConnection.openReadOnly(database)) {
            return connection.run(
                    Cancellation.NONE,
                    false,
                    sql -> {
                        var rows =
                                sql.query(
                                        "SELECT product,enabled FROM settings_products ORDER BY"
                                                + " product LIMIT ?",
                                        PRODUCT_LIMIT + 1);
                        if (rows.size() > PRODUCT_LIMIT)
                            throw new IllegalArgumentException("Too many product preferences");
                        var known = new HashSet<String>();
                        var overrides = new HashMap<String, Boolean>();
                        for (var row : rows) {
                            String product = (String) row[0];
                            if (!Products.isWow(product))
                                throw new IllegalArgumentException("Invalid saved product ID");
                            known.add(product);
                            if (row[1] != null) {
                                int value = ((Number) row[1]).intValue();
                                if (value != 0 && value != 1)
                                    throw new IllegalArgumentException(
                                            "Invalid product preference");
                                overrides.put(product, value != 0);
                            }
                        }
                        return new Stored(known, overrides);
                    });
        }
    }

    void remember(List<String> products) throws Exception {
        try (var connection = writable()) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        long count =
                                ((Number)
                                                sql.query("SELECT count(*) FROM settings_products")
                                                        .getFirst()[0])
                                        .longValue();
                        for (String product : products) {
                            if (!Products.isWow(product))
                                throw new IllegalArgumentException("Invalid product ID");
                            if (!sql.query(
                                            "SELECT product FROM settings_products WHERE product=?",
                                            product)
                                    .isEmpty()) continue;
                            if (++count > PRODUCT_LIMIT)
                                throw new IllegalStateException("Too many product preferences");
                            sql.execute("INSERT INTO settings_products VALUES(?,NULL)", product);
                        }
                        return null;
                    });
        }
    }

    void setEnabled(String product, boolean enabled) throws Exception {
        if (!Products.isWow(product)) throw new IllegalArgumentException("Invalid product ID");
        try (var connection = writable()) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        if (sql.query(
                                                "SELECT product FROM settings_products WHERE"
                                                        + " product=?",
                                                product)
                                        .isEmpty()
                                && ((Number)
                                                        sql.query(
                                                                        "SELECT count(*) FROM"
                                                                            + " settings_products")
                                                                .getFirst()[0])
                                                .longValue()
                                        >= PRODUCT_LIMIT)
                            throw new IllegalStateException("Too many product preferences");
                        sql.execute(
                                "INSERT INTO settings_products VALUES(?,?) ON CONFLICT(product) DO"
                                        + " UPDATE SET enabled=excluded.enabled",
                                product,
                                enabled ? 1 : 0);
                        return null;
                    });
        }
    }

    void reset() throws Exception {
        try (var connection = writable()) {
            connection.run(
                    Cancellation.NONE,
                    true,
                    sql -> {
                        sql.execute("UPDATE settings_products SET enabled=NULL");
                        return null;
                    });
        }
    }

    /** NORMAL like every store in the shared library WAL; see docs/durability-policy.md. */
    private DerivedSqliteConnection writable() throws Exception {
        return new DerivedSqliteConnection(database);
    }
}
