package com.vibeminer.store;

/** Disk-backed transient metadata. Java consumers page at most 256 rows at a time. */
final class MaintenanceSpool implements AutoCloseable {
    static final int PAGE_ROWS = 256;
    static final int TEMP_CACHE_KIB = 2048;
    private final NativeSqlite db;
    private final String table;

    static void configure(NativeSqlite db) {
        if (db.scalar("PRAGMA temp_store") != 1) {
            if (db.inTransaction()) {
                throw new IllegalStateException("Configure maintenance spool before transaction");
            }
            db.exec("PRAGMA temp_store=FILE");
        }
        db.exec("PRAGMA temp.cache_size=-" + TEMP_CACHE_KIB);
    }

    private MaintenanceSpool(NativeSqlite db, String table, String columns) {
        configure(db);
        this.db = db;
        this.table = table;
        db.exec("DROP TABLE IF EXISTS temp." + table);
        db.exec("CREATE TEMP TABLE " + table + columns);
    }

    static MaintenanceSpool replay(NativeSqlite db) {
        MaintenanceSpool spool =
                new MaintenanceSpool(
                        db,
                        "replay_heads",
                        "(pack INTEGER PRIMARY KEY,at INTEGER NOT NULL,end INTEGER NOT NULL,seq INTEGER,header BLOB,valid_end INTEGER NOT NULL)");
        return spool.initialize(
                "CREATE INDEX temp.replay_order ON replay_heads(seq,pack) WHERE header IS NOT NULL");
    }

    static MaintenanceSpool moves(NativeSqlite db) {
        return new MaintenanceSpool(
                db,
                "compaction_moves",
                "(k BLOB PRIMARY KEY,offset INTEGER NOT NULL) WITHOUT ROWID");
    }

    NativeSqlite database() {
        return db;
    }

    static MaintenanceSpool candidates(NativeSqlite db) {
        MaintenanceSpool spool =
                new MaintenanceSpool(
                        db,
                        "compaction_candidates",
                        "(ordinal INTEGER PRIMARY KEY,pack INTEGER NOT NULL,length INTEGER NOT NULL,live INTEGER NOT NULL)");
        return spool.initialize(
                "INSERT INTO temp.compaction_candidates(pack,length,live) SELECT seq,committed_length,live_bytes FROM packs WHERE state=1 AND committed_length-64-live_bytes>0 ORDER BY committed_length-64-live_bytes DESC,seq");
    }

    private MaintenanceSpool initialize(String sql) {
        try {
            db.exec(sql);
        } catch (RuntimeException | Error error) {
            try {
                close();
            } catch (RuntimeException | Error cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
        return this;
    }

    @Override
    public void close() {
        db.uncancelled(() -> db.exec("DROP TABLE " + table));
    }
}
