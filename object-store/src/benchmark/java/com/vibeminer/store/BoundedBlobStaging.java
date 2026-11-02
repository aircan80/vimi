package com.vibeminer.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Fixed-size committed chunks; caller owns this connection and schedules one operation at a time.
 */
final class BoundedBlobStaging {
    static final int CHUNK = 1 << 20;
    private static final long BUDGET = 2L << 30;
    private final NativeSqlite database;

    BoundedBlobStaging(SqliteBlobPrototype store) {
        database = store.database;
    }

    static void initialize(NativeSqlite database) {
        database.exec("CREATE TABLE IF NOT EXISTS chunk_sets(id TEXT PRIMARY KEY)");
        database.exec(
                "CREATE TABLE IF NOT EXISTS payload_chunks(set_id TEXT NOT NULL REFERENCES chunk_sets(id), position INTEGER NOT NULL, payload INTEGER NOT NULL UNIQUE REFERENCES payloads(id), length INTEGER NOT NULL, PRIMARY KEY(set_id,position)) WITHOUT ROWID");
        database.exec(
                "CREATE TABLE IF NOT EXISTS staging(id TEXT PRIMARY KEY REFERENCES chunk_sets(id), k TEXT NOT NULL UNIQUE, length INTEGER NOT NULL, derived INTEGER NOT NULL, progress INTEGER NOT NULL, discarding INTEGER NOT NULL DEFAULT 0)");
    }

    String begin(String key, int length, boolean derived, Cancellation token) throws IOException {
        if (key == null || key.isBlank() || length < 1 || length > Format.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Unsupported staged object");
        }
        return transaction(
                token,
                () -> {
                    if (!database.query("SELECT k FROM objects WHERE k=?", key).isEmpty()) {
                        throw new IllegalStateException("Object already published");
                    }
                    List<Object[]> rows =
                            database.query(
                                    "SELECT id,length,derived,discarding FROM staging WHERE k=?",
                                    key);
                    if (!rows.isEmpty()) {
                        Object[] row = rows.getFirst();
                        if (number(row[1]) != length
                                || number(row[2]) != (derived ? 1 : 0)
                                || number(row[3]) != 0) {
                            throw new IllegalArgumentException("Conflicting or discarding stage");
                        }
                        return (String) row[0];
                    }
                    Object[] usage =
                            database.query("SELECT count(*),coalesce(sum(length),0) FROM staging")
                                    .getFirst();
                    if (number(usage[0]) >= 16 || number(usage[1]) + length > BUDGET) {
                        throw new IllegalStateException("Staging budget exceeded");
                    }
                    String id = UUID.randomUUID().toString();
                    database.exec("INSERT INTO chunk_sets VALUES(?)", id);
                    database.exec(
                            "INSERT INTO staging(id,k,length,derived,progress) VALUES(?,?,?,?,0)",
                            id,
                            key,
                            length,
                            derived ? 1 : 0);
                    return id;
                });
    }

    int progress(String id) {
        return (int) number(stage(id)[3]);
    }

    // The caller fills this bounded buffer before entering SQLite, including any network waits.
    void append(String id, int expectedOffset, byte[] bytes, Cancellation token)
            throws IOException {
        if (bytes.length < 1 || bytes.length > CHUNK) {
            throw new IllegalArgumentException("Use a nonempty chunk of at most 1 MiB");
        }
        transaction(
                token,
                () -> {
                    Object[] stage = stage(id);
                    long length = number(stage[1]);
                    if (number(stage[3]) != expectedOffset
                            || bytes.length != Math.min(CHUNK, length - expectedOffset)) {
                        throw new IllegalArgumentException("Unexpected offset or chunk length");
                    }
                    database.exec("INSERT INTO payloads(data) VALUES(zeroblob(?))", bytes.length);
                    long payload =
                            number(database.query("SELECT last_insert_rowid()").getFirst()[0]);
                    database.transferBlob(
                            payload, 0, bytes.length, new ByteArrayInputStream(bytes), null, token);
                    database.exec(
                            "INSERT INTO payload_chunks VALUES(?,?,?,?)",
                            id,
                            expectedOffset,
                            payload,
                            bytes.length);
                    database.exec(
                            "UPDATE staging SET progress=? WHERE id=?",
                            expectedOffset + bytes.length,
                            id);
                    return null;
                });
    }

    void publish(String id, String build, byte[] expectedSha, Cancellation token)
            throws IOException {
        if (expectedSha == null || expectedSha.length != 32) {
            throw new IllegalArgumentException("Expected SHA-256 required");
        }
        publish(id, build, expectedSha.clone(), null, token);
    }

    void publishVerified(String id, String build, ObjectKey key, Cancellation token)
            throws IOException {
        Object[] row = stage(id);
        if (!HexFormat.of().formatHex(key.indexBytes()).equals(row[0])) {
            throw new IllegalArgumentException("Staging identity differs");
        }
        publish(id, build, null, Verification.CDN.begin(key, number(row[1])), token);
    }

    private void publish(
            String id, String build, byte[] expectedSha, ObjectCheck identity, Cancellation token)
            throws IOException {
        Object[] snapshot = stage(id);
        int length = (int) number(snapshot[1]);
        if (number(snapshot[3]) != length) {
            throw new IllegalStateException("Incomplete stage");
        }
        // Verification only reads committed immutable chunks. It never holds a writer transaction.
        MessageDigest sha = Format.digest("SHA-256");
        var crc = Format.le(new byte[Format.blocks(length) * 4]);
        for (int offset = 0; offset < length; offset += CHUNK) {
            token.check();
            int count = Math.min(CHUNK, length - offset);
            var output = new ByteArrayOutputStream(count);
            read(database, id, offset, count, output, token);
            byte[] bytes = output.toByteArray();
            sha.update(bytes);
            if (identity != null && identity.append(bytes) != null) {
                throw new IOException("Staged identity verification failed");
            }
            for (int at = 0; at < count; at += Format.BLOCK) {
                crc.putInt((int) Format.crc(bytes, at, Math.min(Format.BLOCK, count - at)));
            }
        }
        byte[] digest = sha.digest();
        if ((expectedSha != null && !Arrays.equals(expectedSha, digest))
                || (identity != null && identity.complete() != null)) {
            throw new IOException("Staged identity verification failed");
        }
        transaction(
                token,
                () -> {
                    Object[] current = stage(id);
                    if (!Arrays.equals(snapshot, current)) {
                        throw new IllegalStateException("Staging changed during verification");
                    }
                    database.exec(
                            "INSERT INTO objects(k,length,derived,sha,crc,chunks) VALUES(?,?,?,?,?,?)",
                            snapshot[0],
                            length,
                            snapshot[2],
                            digest,
                            crc.array(),
                            id);
                    database.exec("INSERT INTO build_objects VALUES(?,?)", build, snapshot[0]);
                    database.exec("DELETE FROM staging WHERE id=?", id);
                    return null;
                });
    }

    void abandon(String id, Cancellation token) throws IOException {
        transaction(
                token,
                () -> {
                    if (database.query("SELECT id FROM staging WHERE id=?", id).isEmpty()) {
                        throw new IllegalStateException("Unknown stage");
                    }
                    database.exec("UPDATE staging SET discarding=1 WHERE id=?", id);
                    return null;
                });
        while (true) {
            boolean finished =
                    transaction(
                            token,
                            () -> {
                                List<Object[]> rows =
                                        database.query(
                                                "SELECT position,payload FROM payload_chunks WHERE set_id=? ORDER BY position LIMIT 1",
                                                id);
                                if (rows.isEmpty()) {
                                    database.exec("DELETE FROM staging WHERE id=?", id);
                                    database.exec("DELETE FROM chunk_sets WHERE id=?", id);
                                    return true;
                                }
                                Object[] row = rows.getFirst();
                                database.exec(
                                        "DELETE FROM payload_chunks WHERE set_id=? AND position=?",
                                        id,
                                        row[0]);
                                database.exec("DELETE FROM payloads WHERE id=?", row[1]);
                                return false;
                            });
            if (finished) {
                return;
            }
        }
    }

    static void read(
            NativeSqlite database,
            String id,
            int offset,
            int length,
            OutputStream output,
            Cancellation token)
            throws IOException {
        long end = (long) offset + length;
        int done = 0;
        List<Object[]> rows =
                database.query(
                        "SELECT position,payload,length FROM payload_chunks WHERE set_id=? AND position<? AND position+length>? ORDER BY position",
                        id,
                        end,
                        offset);
        for (Object[] row : rows) {
            token.check();
            int position = (int) number(row[0]);
            int start = Math.max(offset, position);
            int count = (int) (Math.min(end, (long) position + number(row[2])) - start);
            if (start != offset + done) {
                throw new IOException("Missing staged chunk");
            }
            database.transferBlob(number(row[1]), start - position, count, null, output, token);
            done += count;
        }
        if (done != length) {
            throw new IOException("Incomplete staged range");
        }
    }

    private Object[] stage(String id) {
        List<Object[]> rows =
                database.query(
                        "SELECT k,length,derived,progress FROM staging WHERE id=? AND discarding=0",
                        id);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Unknown or discarding stage");
        }
        return rows.getFirst();
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws IOException;
    }

    private <T> T transaction(Cancellation token, Work<T> work) throws IOException {
        token.check();
        database.exec("BEGIN IMMEDIATE");
        try {
            T result = work.run();
            token.check();
            database.exec("COMMIT");
            return result;
        } catch (IOException | RuntimeException | Error error) {
            if (database.inTransaction()) {
                try {
                    database.exec("ROLLBACK");
                } catch (RuntimeException rollback) {
                    error.addSuppressed(rollback);
                }
            }
            throw error;
        }
    }
}
