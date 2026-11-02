package com.vibeminer.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/** Experimental all-SQLite catalog. Excluded from production artifacts. Single owning thread. */
final class SqliteBlobPrototype implements AutoCloseable {
    private final NativeSqlite database;

    SqliteBlobPrototype(Path path) {
        this(path, false);
    }

    private SqliteBlobPrototype(Path path, boolean readOnly) {
        database = new NativeSqlite(path, readOnly);
        if (readOnly) {
            return;
        }
        database.exec("PRAGMA journal_mode=WAL");
        database.exec("PRAGMA synchronous=FULL");
        database.exec("PRAGMA foreign_keys=ON");
        database.exec(
                "CREATE TABLE IF NOT EXISTS payloads(id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
        database.exec(
                "CREATE TABLE IF NOT EXISTS objects(k TEXT PRIMARY KEY, payload INTEGER NOT NULL"
                        + " UNIQUE REFERENCES payloads(id), length INTEGER NOT NULL CHECK(length>=0),"
                        + " derived INTEGER NOT NULL CHECK(derived IN (0,1)), sha BLOB, crc BLOB)");
        database.exec(
                "CREATE TABLE IF NOT EXISTS build_objects(build TEXT NOT NULL, k TEXT NOT NULL"
                        + " REFERENCES objects(k), PRIMARY KEY(build,k)) WITHOUT ROWID");
        database.exec("CREATE INDEX IF NOT EXISTS build_objects_key ON build_objects(k)");
    }

    static SqliteBlobPrototype reader(Path path) {
        return new SqliteBlobPrototype(path, true);
    }

    void putVerified(StoreTypes.PutObject object, String build, Cancellation token)
            throws IOException {
        PreparedPut prepared = PreparedPut.prepare(Verification.CDN, object, token);
        token.check();
        if (prepared.refusal() != null) {
            throw new IllegalArgumentException(prepared.refusal().code());
        }
        put(
                java.util.HexFormat.of().formatHex(object.key().indexBytes()),
                build,
                false,
                object.data().length,
                new java.io.ByteArrayInputStream(object.data()),
                token,
                prepared.sha(),
                prepared.table());
    }

    byte[] readVerified(ObjectKey key, Cancellation token) throws IOException {
        String identity = java.util.HexFormat.of().formatHex(key.indexBytes());
        List<Object[]> rows =
                database.query("SELECT length,sha,crc FROM objects WHERE k=?", identity);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.getFirst();
        long length = ((Number) row[0]).longValue();
        if (length > Format.WHOLE_LIMIT || row[1] == null || row[2] == null) {
            throw new IllegalArgumentException(
                    "Whole verified read needs bounded integrity metadata");
        }
        var output = new java.io.ByteArrayOutputStream((int) length);
        if (!read(identity, 0, (int) length, output, token)) {
            throw new IOException("Object disappeared");
        }
        byte[] bytes = output.toByteArray();
        token.check();
        if (!java.util.Arrays.equals((byte[]) row[1], Format.hash("SHA-256", bytes))) {
            throw new IOException("Payload SHA mismatch");
        }
        var crc = Format.le((byte[]) row[2]);
        if (crc.remaining() != Format.blocks(length) * 4) {
            throw new IOException("Invalid CRC table");
        }
        for (int at = 0; at < bytes.length; at += Format.BLOCK) {
            token.check();
            if (crc.getInt()
                    != (int) Format.crc(bytes, at, Math.min(Format.BLOCK, bytes.length - at))) {
                throw new IOException("Payload CRC mismatch");
            }
        }
        return bytes;
    }

    // Input must already be locally available: never hold this transaction through a CDN request.
    // Integrity verification/typed identity integration are still required before production use.
    void put(
            String key,
            String build,
            boolean derived,
            int length,
            InputStream input,
            Cancellation token)
            throws IOException {
        put(key, build, derived, length, input, token, null, null);
    }

    private void put(
            String key,
            String build,
            boolean derived,
            int length,
            InputStream input,
            Cancellation token,
            byte[] sha,
            byte[] crc)
            throws IOException {
        if (length < 0 || length > Format.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Unsupported payload length");
        }
        database.exec("BEGIN IMMEDIATE");
        try {
            token.check();
            List<Object[]> existing =
                    database.query("SELECT length,derived FROM objects WHERE k=?", key);
            if (existing.isEmpty()) {
                database.exec("INSERT INTO payloads(data) VALUES(zeroblob(?))", length);
                long row =
                        ((Number) database.query("SELECT last_insert_rowid()").getFirst()[0])
                                .longValue();
                database.transferBlob(row, 0, length, input, null, token);
                token.check();
                database.exec(
                        "INSERT INTO objects VALUES(?,?,?,?,?,?)",
                        key,
                        row,
                        length,
                        derived ? 1 : 0,
                        sha,
                        crc);
            } else if (((Number) existing.getFirst()[0]).longValue() != length
                    || ((Number) existing.getFirst()[1]).intValue() != (derived ? 1 : 0)) {
                throw new IllegalArgumentException("Conflicting object metadata");
            }
            database.exec("INSERT OR IGNORE INTO build_objects VALUES(?,?)", build, key);
            token.check();
            database.exec("COMMIT");
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

    boolean read(String key, int offset, int length, OutputStream output, Cancellation token)
            throws IOException {
        database.exec("BEGIN");
        try {
            List<Object[]> rows =
                    database.query("SELECT payload,length FROM objects WHERE k=?", key);
            if (rows.isEmpty()) {
                return false;
            }
            Object[] row = rows.getFirst();
            if (offset < 0
                    || length < 0
                    || (long) offset + length > ((Number) row[1]).longValue()) {
                throw new IllegalArgumentException("Range outside object");
            }
            database.transferBlob(
                    ((Number) row[0]).longValue(), offset, length, null, output, token);
            return true;
        } finally {
            database.exec("ROLLBACK");
        }
    }

    long buildBytes(String build) {
        return ((Number)
                        database.query(
                                        "SELECT coalesce(sum(o.length),0) FROM build_objects b JOIN"
                                                + " objects o ON o.k=b.k WHERE b.build=?",
                                        build)
                                .getFirst()[0])
                .longValue();
    }

    void checkpoint() {
        Object[] result = database.query("PRAGMA wal_checkpoint(TRUNCATE)").getFirst();
        if (((Number) result[0]).intValue() != 0) {
            throw new IllegalStateException("Checkpoint blocked");
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
