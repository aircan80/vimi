package com.vibeminer.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Internal schema and bounded payload operations for the unified SQLite store. */
final class SqliteObjectData {
    static final int CHUNK = 1 << 20;
    static final int MAX_CHUNKS = (int) (Format.MAX_PAYLOAD / CHUNK);
    static final int MAX_STAGES = 16;
    static final long STAGING_BYTES = 2L << 30;

    private SqliteObjectData() {}

    static void initialize(NativeSqlite db) {
        boolean initialized =
                db.scalar(
                                "SELECT count(*) FROM sqlite_master WHERE type='table' AND"
                                        + " name='storage_metadata'")
                        != 0;
        if (!initialized
                && db.scalar(
                                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT"
                                        + " GLOB 'sqlite_*' AND name NOT GLOB 'catalog_*'")
                        != 0) {
            throw new StoreException(
                    "OBJECT_STORE_FORMAT", "Database is not a unified SQLite object store");
        }
        long application = db.scalar("PRAGMA application_id");
        if ((initialized && application != 0x56494d49) || (!initialized && application != 0)) {
            throw new StoreException(
                    "OBJECT_STORE_FORMAT", "Unexpected SQLite application identity");
        }
        if (initialized && db.scalar("SELECT version FROM storage_metadata") != 1) {
            throw new StoreException(
                    "OBJECT_STORE_FORMAT", "Unsupported unified SQLite store version");
        }
        db.exec("PRAGMA journal_mode=WAL");
        db.exec("PRAGMA synchronous=FULL");
        db.exec("PRAGMA foreign_keys=ON");
        db.exec("BEGIN IMMEDIATE");
        try {
            db.exec("CREATE TABLE IF NOT EXISTS storage_metadata(version INTEGER NOT NULL)");
            if (!initialized) {
                db.exec("INSERT INTO storage_metadata VALUES(1)");
                db.exec("PRAGMA application_id=" + 0x56494d49);
            }
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_payloads(id INTEGER PRIMARY KEY,data BLOB"
                            + " NOT NULL)");
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_chunk_sets(id TEXT PRIMARY KEY,garbage"
                            + " INTEGER NOT NULL DEFAULT 0 CHECK(garbage IN (0,1)))");
            db.exec(
                    "CREATE INDEX IF NOT EXISTS storage_garbage_sets ON storage_chunk_sets(id)"
                            + " WHERE garbage=1");
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_payload_chunks(set_id TEXT NOT NULL"
                            + " REFERENCES storage_chunk_sets(id),position INTEGER NOT NULL"
                            + " CHECK(position>=0),payload INTEGER NOT NULL UNIQUE REFERENCES"
                            + " storage_payloads(id),length INTEGER NOT NULL CHECK(length>0 AND"
                            + " length<=1048576),PRIMARY KEY(set_id,position)) WITHOUT ROWID");
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_objects(k BLOB PRIMARY KEY"
                            + " CHECK(length(k)=18),length INTEGER NOT NULL CHECK(length>0 AND"
                            + " length<=536870912),role TEXT NOT NULL CHECK(role IN"
                            + " ('ORIGINAL','DERIVED')),set_id TEXT NOT NULL UNIQUE REFERENCES"
                            + " storage_chunk_sets(id),sha BLOB NOT NULL CHECK(length(sha)=32),crc BLOB"
                            + " NOT NULL,origin INTEGER NOT NULL CHECK(origin BETWEEN 0 AND"
                            + " 2),stored_at INTEGER NOT NULL) WITHOUT ROWID");
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_staging(k BLOB PRIMARY KEY"
                            + " CHECK(length(k)=18),set_id TEXT NOT NULL UNIQUE REFERENCES"
                            + " storage_chunk_sets(id),declared INTEGER NOT NULL CHECK(declared>0 AND"
                            + " declared<=536870912),progress INTEGER NOT NULL CHECK(progress>=0 AND"
                            + " progress<=declared),origin INTEGER NOT NULL CHECK(origin BETWEEN 0 AND"
                            + " 2),chunks INTEGER NOT NULL CHECK(chunks>=0 AND chunks<=512),resumable"
                            + " INTEGER NOT NULL CHECK(resumable IN (0,1))) WITHOUT ROWID");
            db.exec(
                    "CREATE TABLE IF NOT EXISTS storage_history(record BLOB PRIMARY KEY REFERENCES"
                            + " storage_objects(k),product TEXT NOT NULL,region TEXT NOT NULL,kind"
                            + " INTEGER NOT NULL,observed_at INTEGER NOT NULL,url TEXT,document BLOB"
                            + " NOT NULL CHECK(length(document)=16)) WITHOUT ROWID");
            db.exec(
                    "CREATE INDEX IF NOT EXISTS storage_history_order ON"
                            + " storage_history(observed_at,record)");
            db.exec(
                    "UPDATE storage_chunk_sets SET garbage=1 WHERE id IN (SELECT set_id FROM"
                            + " storage_staging WHERE resumable=0)");
            db.exec("DELETE FROM storage_staging WHERE resumable=0");
            db.exec("COMMIT");
        } catch (RuntimeException | Error error) {
            rollback(db, error);
            throw error;
        }
    }

    static String begin(
            NativeSqlite db,
            ObjectKey key,
            long length,
            StoreTypes.Origin origin,
            Cancellation token)
            throws IOException {
        return begin(db, key, length, origin, token, true);
    }

    static String begin(
            NativeSqlite db,
            ObjectKey key,
            long length,
            StoreTypes.Origin origin,
            Cancellation token,
            boolean resumable)
            throws IOException {
        if (length < 1 || length > Format.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Staging size is 1 byte to 512 MiB");
        }
        return transaction(
                db,
                token,
                () -> {
                    List<Object[]> existing =
                            db.query(
                                    "SELECT set_id,declared,origin,resumable FROM storage_staging"
                                            + " WHERE k=?",
                                    key.indexBytes());
                    if (!existing.isEmpty()) {
                        Object[] row = existing.getFirst();
                        if (number(row[1]) != length
                                || number(row[2]) != origin.ordinal()
                                || number(row[3]) != (resumable ? 1 : 0)) {
                            throw new IllegalArgumentException("Conflicting staging metadata");
                        }
                        return (String) row[0];
                    }
                    Object[] budget =
                            db.query(
                                            "SELECT count(*),coalesce(sum(declared),0) FROM"
                                                    + " storage_staging")
                                    .getFirst();
                    long garbage =
                            db.scalar(
                                    "SELECT coalesce(sum(length(p.data)),0) FROM storage_chunk_sets"
                                            + " s JOIN storage_payload_chunks c ON c.set_id=s.id JOIN"
                                            + " storage_payloads p ON p.id=c.payload WHERE"
                                            + " s.garbage=1");
                    if (number(budget[0]) >= MAX_STAGES
                            || number(budget[1]) + garbage + length > STAGING_BYTES) {
                        throw new StoreException(
                                "OBJECT_STORE_LIMIT", "Incomplete-object staging budget exceeded");
                    }
                    String id = UUID.randomUUID().toString();
                    db.exec("INSERT INTO storage_chunk_sets(id) VALUES(?)", id);
                    db.exec(
                            "INSERT INTO storage_staging VALUES(?,?,?,0,?,0,?)",
                            key.indexBytes(),
                            id,
                            length,
                            origin.ordinal(),
                            resumable ? 1 : 0);
                    return id;
                });
    }

    static void append(
            NativeSqlite db,
            ObjectKey key,
            String id,
            long offset,
            byte[] bytes,
            Cancellation token)
            throws IOException {
        if (bytes.length < 1 || bytes.length > CHUNK) {
            throw new IllegalArgumentException("Append size is 1 byte to 1 MiB");
        }
        transaction(
                db,
                token,
                () -> {
                    Object[] stage = stage(db, key, id);
                    if (number(stage[1]) != offset || bytes.length > number(stage[0]) - offset) {
                        throw new IllegalArgumentException(
                                "Append does not match committed offset or length");
                    }
                    long declared = number(stage[0]);
                    int consumed = 0;
                    int added = 0;
                    while (consumed < bytes.length) {
                        token.check();
                        long at = offset + consumed;
                        long position = at / CHUNK * CHUNK;
                        int within = (int) (at - position);
                        int capacity = (int) Math.min(CHUNK, declared - position);
                        int count = Math.min(bytes.length - consumed, capacity - within);
                        long payload;
                        if (within == 0) {
                            if (number(stage[3]) + added >= MAX_CHUNKS) {
                                throw new StoreException(
                                        "OBJECT_STORE_LIMIT", "Too many physical payload chunks");
                            }
                            db.exec(
                                    "INSERT INTO storage_payloads(data) VALUES(zeroblob(?))",
                                    capacity);
                            payload = db.scalar("SELECT last_insert_rowid()");
                            added++;
                        } else {
                            List<Object[]> tail =
                                    db.query(
                                            "SELECT payload,length FROM storage_payload_chunks"
                                                    + " WHERE set_id=? AND position=?",
                                            id,
                                            position);
                            if (tail.size() != 1 || number(tail.getFirst()[1]) != within) {
                                throw new IOException(
                                        "Staged tail does not match committed progress");
                            }
                            payload = number(tail.getFirst()[0]);
                        }
                        db.transferBlob(
                                "storage_payloads",
                                payload,
                                within,
                                count,
                                new java.io.ByteArrayInputStream(bytes, consumed, count),
                                null,
                                token);
                        // Close the incremental handle before mutating the tail's metadata. Only
                        // the uncommitted suffix changes; previously committed prefix bytes remain.
                        if (within == 0) {
                            db.exec(
                                    "INSERT INTO storage_payload_chunks VALUES(?,?,?,?)",
                                    id,
                                    position,
                                    payload,
                                    count);
                        } else {
                            db.exec(
                                    "UPDATE storage_payload_chunks SET length=? WHERE set_id=? AND"
                                            + " position=?",
                                    within + count,
                                    id,
                                    position);
                        }
                        consumed += count;
                    }
                    db.exec(
                            "UPDATE storage_staging SET progress=?,chunks=chunks+? WHERE k=? AND"
                                    + " set_id=?",
                            offset + bytes.length,
                            added,
                            key.indexBytes(),
                            id);
                    return null;
                });
    }

    static Object[] stage(NativeSqlite db, ObjectKey key, String id) {
        List<Object[]> rows =
                db.query(
                        "SELECT declared,progress,origin,chunks FROM storage_staging WHERE k=? AND"
                                + " set_id=?",
                        key.indexBytes(),
                        id);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Staging handle is no longer current");
        }
        return rows.getFirst();
    }

    static boolean exists(NativeSqlite db, ObjectKey key) {
        return db.scalar("SELECT count(*) FROM storage_objects WHERE k=?", key.indexBytes()) != 0;
    }

    static void publish(
            NativeSqlite db,
            ObjectKey key,
            String id,
            Proof proof,
            StoreTypes.Origin origin,
            History.Entry history,
            Cancellation token)
            throws IOException {
        transaction(
                db,
                token,
                () -> {
                    Object[] stage = stage(db, key, id);
                    if (number(stage[0]) != proof.length() || number(stage[1]) != proof.length()) {
                        throw new IllegalStateException("Incomplete or changed staged object");
                    }
                    record(db, key, id, proof, origin, history);
                    db.exec(
                            "DELETE FROM storage_staging WHERE k=? AND set_id=?",
                            key.indexBytes(),
                            id);
                    return null;
                });
    }

    private static void record(
            NativeSqlite db,
            ObjectKey key,
            String id,
            Proof proof,
            StoreTypes.Origin origin,
            History.Entry history) {
        db.exec(
                "INSERT INTO storage_objects VALUES(?,?,?,?,?,?,?,?)",
                key.indexBytes(),
                proof.length(),
                key.type() == ObjectKey.GENERATED ? "DERIVED" : "ORIGINAL",
                id,
                proof.sha(),
                proof.crc(),
                origin.ordinal(),
                Instant.now().toEpochMilli());
        if (history != null) {
            db.exec(
                    "INSERT INTO storage_history VALUES(?,?,?,?,?,?,?)",
                    key.indexBytes(),
                    history.product(),
                    history.region(),
                    history.kind(),
                    history.observedAt().toEpochMilli(),
                    history.url(),
                    history.document().bytes());
        }
    }

    /** Verified ready input up to one chunk publishes with one durable transaction. */
    static void storeSmall(NativeSqlite db, PreparedPut prepared) throws IOException {
        StoreTypes.PutObject input = prepared.input();
        if (input.data().length > CHUNK
                || prepared.refusal() != null
                || prepared.sha() == null
                || prepared.table() == null) {
            throw new IllegalArgumentException("Small publication requires bounded verified input");
        }
        transaction(
                db,
                Cancellation.NONE,
                () -> {
                    // Closed partial stages may be replaced, atomically with the new publication.
                    // Active
                    // handle ownership was checked on the writer before entering this transaction.
                    List<Object[]> old =
                            db.query(
                                    "SELECT set_id FROM storage_staging WHERE k=?",
                                    input.key().indexBytes());
                    if (!old.isEmpty()) {
                        db.exec("DELETE FROM storage_staging WHERE k=?", input.key().indexBytes());
                        db.exec(
                                "UPDATE storage_chunk_sets SET garbage=1 WHERE id=?",
                                old.getFirst()[0]);
                    }
                    String id = UUID.randomUUID().toString();
                    db.exec("INSERT INTO storage_chunk_sets(id) VALUES(?)", id);
                    db.exec("INSERT INTO storage_payloads(data) VALUES(?)", input.data());
                    long payload = db.scalar("SELECT last_insert_rowid()");
                    db.exec(
                            "INSERT INTO storage_payload_chunks VALUES(?,0,?,?)",
                            id,
                            payload,
                            input.data().length);
                    record(
                            db,
                            input.key(),
                            id,
                            new Proof(input.data().length, prepared.sha(), prepared.table()),
                            input.origin(),
                            prepared.history());
                    return null;
                });
    }

    static void markAbandoned(NativeSqlite db, ObjectKey key, String id) throws IOException {
        transaction(
                db,
                Cancellation.NONE,
                () -> {
                    stage(db, key, id);
                    db.exec(
                            "DELETE FROM storage_staging WHERE k=? AND set_id=?",
                            key.indexBytes(),
                            id);
                    db.exec("UPDATE storage_chunk_sets SET garbage=1 WHERE id=?", id);
                    return null;
                });
    }

    /** One chunk per transaction, suitable for rescheduling between calls. */
    static boolean collectOne(NativeSqlite db) throws IOException {
        if (db.query("SELECT id FROM storage_chunk_sets WHERE garbage=1 LIMIT 1").isEmpty()) {
            return false;
        }
        return transaction(
                db,
                Cancellation.NONE,
                () -> {
                    List<Object[]> sets =
                            db.query("SELECT id FROM storage_chunk_sets WHERE garbage=1 LIMIT 1");
                    if (sets.isEmpty()) {
                        return false;
                    }
                    String id = (String) sets.getFirst()[0];
                    List<Object[]> rows =
                            db.query(
                                    "SELECT position,payload FROM storage_payload_chunks WHERE"
                                            + " set_id=? ORDER BY position LIMIT 1",
                                    id);
                    if (rows.isEmpty()) {
                        db.exec("DELETE FROM storage_chunk_sets WHERE id=?", id);
                    } else {
                        Object[] row = rows.getFirst();
                        db.exec(
                                "DELETE FROM storage_payload_chunks WHERE set_id=? AND position=?",
                                id,
                                row[0]);
                        db.exec("DELETE FROM storage_payloads WHERE id=?", row[1]);
                    }
                    return true;
                });
    }

    record Part(long position, long payload, int length) {}

    static List<Part> parts(NativeSqlite db, String id, long length) throws IOException {
        var result = new ArrayList<Part>();
        long end = 0;
        for (Object[] row :
                db.query(
                        "SELECT position,payload,length FROM storage_payload_chunks WHERE set_id=?"
                                + " ORDER BY position LIMIT 513",
                        id)) {
            long position = number(row[0]);
            long payload = number(row[1]);
            long count = number(row[2]);
            if (position != end
                    || payload < 1
                    || count < 1
                    || count > CHUNK
                    || result.size() == MAX_CHUNKS
                    || end + count > length) {
                throw new IOException("Invalid payload chunk layout");
            }
            result.add(new Part(position, payload, (int) count));
            end += count;
        }
        if (end != length) {
            throw new IOException("Incomplete payload chunk layout");
        }
        return List.copyOf(result);
    }

    static byte[] range(
            NativeSqlite db, List<Part> parts, long offset, int count, Cancellation token)
            throws IOException {
        var output = new ByteArrayOutputStream(count);
        long end = offset + count;
        for (Part part : parts) {
            if (part.position() >= end) {
                break;
            }
            if (part.position() + part.length() <= offset) {
                continue;
            }
            token.check();
            long start = Math.max(offset, part.position());
            int take = Math.toIntExact(Math.min(end, part.position() + part.length()) - start);
            db.transferBlob(
                    "storage_payloads",
                    part.payload(),
                    Math.toIntExact(start - part.position()),
                    take,
                    null,
                    output,
                    token);
        }
        if (output.size() != count) {
            throw new IOException("Missing payload bytes");
        }
        return output.toByteArray();
    }

    record Proof(long length, byte[] sha, byte[] crc) {}

    static Proof verify(NativeSqlite db, ObjectKey key, String id, long length, Cancellation token)
            throws IOException {
        List<Part> parts = parts(db, id, length);
        ObjectCheck identity = verifier(key).begin(key, length);
        var sha = Format.digest("SHA-256");
        var crc = Format.le(new byte[Format.blocks(length) * 4]);
        for (long offset = 0; offset < length; offset += Format.BLOCK) {
            token.check();
            byte[] bytes =
                    range(db, parts, offset, (int) Math.min(Format.BLOCK, length - offset), token);
            if (identity.append(bytes) != null) {
                throw new IOException("Staged object identity verification failed");
            }
            sha.update(bytes);
            crc.putInt((int) Format.crc(bytes));
        }
        if (identity.complete() != null) {
            throw new IOException("Staged object identity verification failed");
        }
        return new Proof(length, sha.digest(), crc.array());
    }

    static Verification verifier(ObjectKey key) {
        return key.type() == ObjectKey.GENERATED ? Verification.GENERATED : Verification.CDN;
    }

    static long number(Object value) {
        return ((Number) value).longValue();
    }

    @FunctionalInterface
    interface Work<T> {
        T run() throws IOException;
    }

    static <T> T transaction(NativeSqlite db, Cancellation token, Work<T> work) throws IOException {
        token.check();
        db.exec("BEGIN IMMEDIATE");
        boolean committing = false;
        try {
            T value = work.run();
            token.check();
            committing = true;
            db.exec("COMMIT");
            return value;
        } catch (IOException | RuntimeException | Error error) {
            boolean active = db.inTransaction();
            rollback(db, error);
            if (committing && !active) {
                throw new StoreException(
                        "OBJECT_STORE_COMMIT_UNKNOWN",
                        "Commit outcome must be reconciled after reopening",
                        error);
            }
            throw error;
        }
    }

    private static void rollback(NativeSqlite db, Throwable error) {
        if (db.inTransaction()) {
            try {
                db.exec("ROLLBACK");
            } catch (RuntimeException rollback) {
                error.addSuppressed(rollback);
            }
        }
    }
}
