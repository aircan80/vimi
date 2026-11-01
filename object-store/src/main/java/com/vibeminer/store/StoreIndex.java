package com.vibeminer.store;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Rebuildable index. Pack bytes, not this database, are the durable source of object content. */
final class StoreIndex {
    static final String[] DDL = {
        "CREATE TABLE store(id INTEGER PRIMARY KEY CHECK(id = 1), store_id BLOB NOT NULL CHECK(length(store_id) = 16), format INTEGER NOT NULL, created_at INTEGER NOT NULL, next_seq INTEGER NOT NULL, object_count INTEGER NOT NULL, object_digest INTEGER NOT NULL, history_count INTEGER NOT NULL, history_digest INTEGER NOT NULL, clean_close INTEGER NOT NULL, row_check INTEGER NOT NULL)",
        "CREATE TABLE packs(seq INTEGER PRIMARY KEY, state INTEGER NOT NULL, committed_length INTEGER NOT NULL, live_bytes INTEGER NOT NULL, row_check INTEGER NOT NULL)",
        "CREATE TABLE objects(k BLOB PRIMARY KEY, pack INTEGER NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL, sha256 BLOB NOT NULL, last_read INTEGER) WITHOUT ROWID",
        "CREATE TABLE history(record BLOB PRIMARY KEY, observed_at INTEGER NOT NULL, product TEXT NOT NULL, region TEXT NOT NULL, kind INTEGER NOT NULL, document BLOB NOT NULL, url TEXT NOT NULL) WITHOUT ROWID",
        "CREATE INDEX history_order ON history(product, region, kind, observed_at, record)",
        "CREATE INDEX history_time ON history(observed_at, record)"
    };
    static final Map<String, String> COLUMNS = Map.of(
        "store", "id,store_id,format,created_at,next_seq,object_count,object_digest,history_count,history_digest,clean_close,row_check",
        "packs", "seq,state,committed_length,live_bytes,row_check",
        "objects", "k,pack,offset,length,sha256,last_read",
        "history", "record,observed_at,product,region,kind,document,url");
    final NativeSqlite db;
    byte[] id;
    long created, next, objectCount, objectDigest, historyCount, historyDigest, clean;
    StoreIndex(NativeSqlite db) { this.db = db; }
    void create(byte[] id, long now, int kind) {
        db.exec("PRAGMA journal_mode=WAL"); db.exec("PRAGMA synchronous=NORMAL");
        db.transaction(true, () -> {
            for (String sql : DDL) db.exec(sql);
            db.exec("PRAGMA user_version=1"); db.exec("PRAGMA application_id=" + kind);
            this.id = id.clone(); created = now; next = 1; objectCount = objectDigest = historyCount = historyDigest = 0; clean = 1;
            db.exec("INSERT INTO store VALUES(1,?,1,?,?,?,?,?,?,?,0)", id, created, next, objectCount, objectDigest, historyCount, historyDigest, clean);
            save();
        });
    }
    void load() {
        Object[] r = db.query("SELECT * FROM store").getFirst();
        id = (byte[])r[1]; created = (long)r[3]; next = (long)r[4]; objectCount = (long)r[5]; objectDigest = (long)r[6];
        historyCount = (long)r[7]; historyDigest = (long)r[8]; clean = (long)r[9];
    }
    long rowCheck() {
        return Format.crc(ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).put(id).putLong(1).putLong(created).putLong(next)
            .putLong(objectCount).putLong(objectDigest).putLong(historyCount).putLong(historyDigest).putLong(clean).array());
    }
    void save() {
        db.exec("UPDATE store SET next_seq=?,object_count=?,object_digest=?,history_count=?,history_digest=?,clean_close=?,row_check=? WHERE id=1",
            next, objectCount, objectDigest, historyCount, historyDigest, clean, rowCheck());
    }
    static String normalized(String sql) { return sql.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    boolean damagedVersion(long version) {
        if (version == 1 || !(version < 1 || Integer.bitCount((int)version ^ 1) == 1)) return false;
        List<Object[]> rows = db.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name");
        // Match actual reference schema, including implicit SQLite indexes (only INTEGER PKs and WITHOUT ROWID).
        if (rows.size() != DDL.length) return false;
        Set<String> expected = new HashSet<>(); for (String sql : DDL) expected.add(normalized(sql));
        for (Object[] row : rows) if (!(row[3] instanceof String sql) || !expected.remove(normalized(sql))) return false;
        return expected.isEmpty();
    }
    boolean validate(byte[] packId, boolean full, Cancellation cancellation) {
        try {
            for (var item : COLUMNS.entrySet()) {
                StringJoiner actual = new StringJoiner(",");
                for (Object[] col : db.query("PRAGMA table_info(" + item.getKey() + ")")) actual.add((String)col[1]);
                if (!actual.toString().equals(item.getValue())) return false;
            }
            List<Object[]> stores = db.query("SELECT * FROM store"); if (stores.size() != 1) return false;
            Object[] s = stores.getFirst();
            for (int i = 0; i < s.length; i++) if (i != 1 && !(s[i] instanceof Long)) return false;
            if (!(s[1] instanceof byte[] bytes) || bytes.length != 16 || (long)s[0] != 1 || (long)s[2] != 1 || (long)s[4] < 1 || (long)s[9] < 0 || (long)s[9] > 1) return false;
            load(); if ((long)s[10] != rowCheck() || packId != null && !Arrays.equals(packId, id)) return false;
            int active = 0;
            for (Object[] p : db.query("SELECT * FROM packs")) {
                for (Object value : p) if (!(value instanceof Long)) return false;
                long seq = (long)p[0], state = (long)p[1], committed = (long)p[2];
                if (seq < 1 || state < 0 || state > 3 || committed < 64 || (long)p[4] != Format.packCheck(seq, state, committed)) return false;
                if (state == 0) active++;
            }
            if (active > 1) return false;
            return !full || fullCheck(cancellation);
        } catch (NativeSqlite.Failure | ClassCastException | IndexOutOfBoundsException | IllegalArgumentException e) { return false; }
    }
    boolean fullCheck(Cancellation cancellation) {
        db.exec("BEGIN");
        try {
            load(); // Pin one snapshot for store totals, integrity check and every page.
            List<Object[]> integrity = db.query("PRAGMA integrity_check");
            if (integrity.size() != 1 || !"ok".equals(integrity.getFirst()[0])) return false;
            long oc = 0, od = 0, hc = 0, hd = 0;
            byte[] cursor = new byte[0];
            while (true) {
                cancellation.check(); List<Object[]> page = db.query("SELECT k,pack,offset,length,sha256 FROM objects WHERE k>? ORDER BY k LIMIT 1024", cursor);
                if (page.isEmpty()) break;
                for (Object[] row : page) { Row r = Row.from(row); if (Arrays.compareUnsigned(cursor, r.key.indexBytes()) >= 0) return false; od ^= r.mix(); oc++; cursor = r.key.indexBytes(); }
            }
            cursor = new byte[0];
            while (true) {
                cancellation.check(); List<Object[]> page = db.query("SELECT * FROM history WHERE record>? ORDER BY record LIMIT 1024", cursor);
                if (page.isEmpty()) break;
                for (Object[] row : page) { History.Entry e = history(row); cursor = e.record().bytes(); hd ^= historyMix(e); hc++; }
            }
            return oc == objectCount && od == objectDigest && hc == historyCount && hd == historyDigest;
        } finally { db.exec("ROLLBACK"); }
    }
    record Row(ObjectKey key, int pack, long offset, long length, byte[] sha) {
        static Row from(Object[] r) { return new Row(ObjectKey.fromIndex((byte[])r[0]), Math.toIntExact((long)r[1]), (long)r[2], (long)r[3], (byte[])r[4]); }
        long mix() { return Format.mix(ByteBuffer.allocate(74).order(ByteOrder.LITTLE_ENDIAN).put(key.indexBytes()).putLong(pack).putLong(offset).putLong(length).put(sha).array()); }
    }
    Row find(ObjectKey key) {
        List<Object[]> rows = db.query("SELECT k,pack,offset,length,sha256 FROM objects WHERE k=?", key.indexBytes());
        return rows.isEmpty() ? null : Row.from(rows.getFirst());
    }
    void insert(Row row, History.Entry history) {
        db.exec("INSERT INTO objects(k,pack,offset,length,sha256) VALUES(?,?,?,?,?)", row.key.indexBytes(), row.pack, row.offset, row.length, row.sha);
        objectCount++; objectDigest ^= row.mix();
        if (history != null) {
            db.exec("INSERT INTO history VALUES(?,?,?,?,?,?,?)", history.record().bytes(), history.observedAt().toEpochMilli(), history.product(), history.region(), history.kind(), history.document().bytes(), history.url());
            historyCount++; historyDigest ^= historyMix(history);
        }
    }
    void remove(Row row) {
        db.exec("DELETE FROM objects WHERE k=?", row.key.indexBytes()); objectCount--; objectDigest ^= row.mix();
        if (row.key.type() == ObjectKey.HISTORY_RECORD) {
            List<Object[]> h = db.query("SELECT * FROM history WHERE record=?", row.key.bytes());
            if (!h.isEmpty()) { historyDigest ^= historyMix(history(h.getFirst())); historyCount--; db.exec("DELETE FROM history WHERE record=?", row.key.bytes()); }
        }
    }
    void pack(int seq, int state, long committed, long live) {
        db.exec("INSERT INTO packs VALUES(?,?,?,?,?) ON CONFLICT(seq) DO UPDATE SET state=excluded.state,committed_length=excluded.committed_length,live_bytes=excluded.live_bytes,row_check=excluded.row_check",
            seq, state, committed, live, Format.packCheck(seq, state, committed));
    }
    static History.Entry history(Object[] r) {
        return new History.Entry(Math.toIntExact((long)r[4]), (String)r[2], (String)r[3], java.time.Instant.ofEpochMilli((long)r[1]), (String)r[6],
            new ObjectKey(ObjectKey.HISTORY_DOCUMENT, (byte[])r[5]), new ObjectKey(ObjectKey.HISTORY_RECORD, (byte[])r[0]));
    }
    static long historyMix(History.Entry e) {
        byte[] text = (e.product() + "\n" + e.region() + "\n" + e.url()).getBytes(StandardCharsets.UTF_8);
        return Format.mix(ByteBuffer.allocate(48 + text.length).order(ByteOrder.LITTLE_ENDIAN).put(e.record().bytes()).put(e.document().bytes())
            .putLong(e.observedAt().toEpochMilli()).putLong(e.kind()).put(text).array());
    }
}
