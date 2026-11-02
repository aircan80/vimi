package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.DeleteOutcome;
import com.vibeminer.store.StoreTypes.DeleteResult;
import com.vibeminer.store.StoreTypes.ObjectInfo;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import com.vibeminer.store.StoreTypes.ReadState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Unified SQLite object storage. Payload rows contain only immutable IDs and bytes. Each staged
 * append commits at most 1 MiB; publication never rewrites the complete object. Existing pack
 * stores are neither opened nor migrated by this implementation.
 */
public final class SqliteObjectStore implements ObjectReader, ObjectWriter, HistoryLog {
    public static final int MAX_PUT_OBJECTS = 128;
    public static final int MAX_PENDING_OPERATIONS = 256;
    public static final int MAX_STAGING_APPEND_BYTES = SqliteObjectData.CHUNK;

    private static final ThreadPoolExecutor OPENERS = Async.pool("vimi-sqlite-open", 2);
    private final Object lifecycle = new Object();
    private final Path path;
    private final NativeSqlite database;
    private final WriterLock lock;
    private final WriteQueue writes = new WriteQueue();
    private final ThreadPoolExecutor readers = Async.pool("vimi-sqlite-read", 2);
    private final ThreadPoolExecutor preparation = Async.pool("vimi-sqlite-verify", 2);
    private final ThreadLocal<NativeSqlite> readerConnection = new ThreadLocal<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<NativeSqlite> readerConnections =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Map<ObjectKey, SqliteObjectStaging> activeStages = new HashMap<>();
    private final Set<CompletableFuture<?>> operations = new HashSet<>();
    private boolean closing;
    private CompletableFuture<Void> closed;

    private SqliteObjectStore(Path path, NativeSqlite database, WriterLock lock) {
        this.path = path;
        this.database = database;
        this.lock = lock;
    }

    /** Opens one library database; a second object-store writer fails with OBJECT_STORE_BUSY. */
    public static CompletableFuture<SqliteObjectStore> open(Path database) {
        Path path = database.toAbsolutePath().normalize();
        return publicErrors(
                Async.<SqliteObjectStore>submit(
                        OPENERS,
                        () -> {
                            Files.createDirectories(path.getParent());
                            WriterLock lock =
                                    new WriterLock(
                                            path.resolveSibling(
                                                    path.getFileName() + ".writer-lock"));
                            NativeSqlite connection = null;
                            try {
                                connection = new NativeSqlite(path, false);
                                SqliteObjectData.initialize(connection);
                                boolean garbage =
                                        connection.scalar(
                                                        "SELECT count(*) FROM storage_chunk_sets"
                                                                + " WHERE garbage=1")
                                                != 0;
                                var store = new SqliteObjectStore(path, connection, lock);
                                if (garbage) {
                                    // Recovery only marks temporary whole-put leftovers.
                                    // Reclamation is
                                    // chunk-bounded and asynchronous; accepted cleanup participates
                                    // in close.
                                    store.collectGarbage(Cancellation.NONE);
                                }
                                return store;
                            } catch (Throwable error) {
                                if (connection != null) {
                                    connection.close();
                                }
                                lock.close();
                                throw error;
                            }
                        }));
    }

    private <T> CompletableFuture<T> operation(Supplier<CompletableFuture<T>> work) {
        synchronized (lifecycle) {
            if (closing) {
                return CompletableFuture.failedFuture(ObjectStore.closed());
            }
            if (operations.size() >= MAX_PENDING_OPERATIONS) {
                return CompletableFuture.failedFuture(
                        new StoreException(
                                "OBJECT_STORE_LIMIT", "Outstanding operation budget is in use"));
            }
            CompletableFuture<T> actual;
            try {
                actual = work.get();
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
            operations.add(actual);
            var published = new CompletableFuture<T>();
            actual.whenComplete(
                    (value, error) -> {
                        synchronized (lifecycle) {
                            operations.remove(actual);
                            lifecycle.notifyAll();
                        }
                        // Release drain tracking before publishing on a separate thread. A caller
                        // may close and join from its callback without occupying native workers.
                        Thread.ofVirtual()
                                .name("vimi-sqlite-result")
                                .start(
                                        () -> {
                                            if (error == null) {
                                                published.complete(value);
                                            } else {
                                                published.completeExceptionally(
                                                        publicFailure(error));
                                            }
                                        });
                    });
            // Caller cancellation is independent of both the underlying work and drain tracking.
            return published;
        }
    }

    private static <T> CompletableFuture<T> publicErrors(CompletableFuture<T> future) {
        return future.exceptionallyCompose(
                error -> CompletableFuture.failedFuture(publicFailure(error)));
    }

    private static Throwable publicFailure(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException
                && error.getCause() != null) {
            error = error.getCause();
        }
        return error instanceof NativeSqlite.Failure || error instanceof IOException
                ? ObjectStore.translate(error, "OBJECT_STORE_WRITE_FAILED")
                : error;
    }

    <T> CompletableFuture<T> write(long bytes, Cancellation token, Async.Work<T> work) {
        return writes.submit(bytes, token, work);
    }

    // Accepted multi-step operations need a control turn even when public admission is full.
    // The parent operation cap bounds continuations until cleanup finishes. Retries use a timer,
    // never a native writer or caller thread. Close drains parents before shutting the queue.
    <T> CompletableFuture<T> continueWrite(Async.Work<T> work) {
        var result = new CompletableFuture<T>();
        continueWrite(work, result);
        return result;
    }

    private <T> void continueWrite(Async.Work<T> work, CompletableFuture<T> result) {
        write(0, Cancellation.NONE, work)
                .whenComplete(
                        (value, error) -> {
                            Throwable cause = error;
                            while (cause instanceof java.util.concurrent.CompletionException
                                    && cause.getCause() != null) {
                                cause = cause.getCause();
                            }
                            if (cause instanceof StoreException failure
                                    && failure.code().equals("OBJECT_STORE_LIMIT")) {
                                CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS)
                                        .execute(() -> continueWrite(work, result));
                            } else if (error != null) {
                                result.completeExceptionally(error);
                            } else {
                                result.complete(value);
                            }
                        });
    }

    private interface ReadWork<T> {
        T run(NativeSqlite connection) throws Exception;
    }

    private <T> CompletableFuture<T> reading(Cancellation token, ReadWork<T> work) {
        return Async.submit(
                readers,
                () -> {
                    token.check();
                    NativeSqlite connection = readerConnection.get();
                    if (connection == null) {
                        connection = new NativeSqlite(path, true);
                        readerConnections.add(connection);
                        readerConnection.set(connection);
                    }
                    NativeSqlite reader = connection;
                    Throwable failure = null;
                    try {
                        return reader.withCancellation(token, () -> work.run(reader));
                    } catch (Exception | Error error) {
                        failure = error;
                        throw error;
                    } finally {
                        try {
                            if (reader.inTransaction()) {
                                reader.uncancelled(() -> reader.exec("ROLLBACK"));
                            }
                        } catch (RuntimeException | Error cleanup) {
                            readerConnection.remove();
                            readerConnections.remove(reader);
                            reader.close();
                            if (failure != null) {
                                failure.addSuppressed(cleanup);
                            } else {
                                throw cleanup;
                            }
                        }
                    }
                });
    }

    @Override
    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation token) {
        return operation(() -> reading(token, reader -> SqliteObjectData.exists(reader, key)));
    }

    @Override
    public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation token) {
        return operation(() -> reading(token, reader -> readCore(reader, key, 0, -1, token)));
    }

    @Override
    public CompletableFuture<ObjectRead> readRange(
            ObjectKey key, long offset, int length, Cancellation token) {
        if (offset < 0 || length < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Negative range"));
        }
        if (length > Format.WHOLE_LIMIT) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_LIMIT", "Range exceeds 64 MiB"));
        }
        return operation(
                () -> reading(token, reader -> readCore(reader, key, offset, length, token)));
    }

    private static ObjectRead readCore(
            NativeSqlite reader, ObjectKey key, long offset, int requested, Cancellation token)
            throws IOException {
        reader.exec("BEGIN");
        try {
            List<Object[]> rows =
                    reader.query(
                            "SELECT length,set_id,sha,crc,origin,stored_at FROM storage_objects"
                                    + " WHERE k=?",
                            key.indexBytes());
            if (rows.isEmpty()) {
                return new ObjectRead(ReadState.ABSENT, null, null, null);
            }
            Object[] row = rows.getFirst();
            long length = SqliteObjectData.number(row[0]);
            byte[] sha = (byte[]) row[2];
            byte[] crc = (byte[]) row[3];
            long origin = SqliteObjectData.number(row[4]);
            if (length < 1
                    || length > Format.MAX_PAYLOAD
                    || sha.length != 32
                    || crc.length != Format.blocks(length) * 4
                    || origin < 0
                    || origin >= Origin.values().length) {
                return corrupt("Invalid object metadata");
            }
            if (requested < 0 && length > Format.WHOLE_LIMIT) {
                throw new StoreException(
                        "OBJECT_STORE_LIMIT", "Whole read exceeds 64 MiB; use ranges");
            }
            int count = requested < 0 ? (int) length : requested;
            if (offset > length || count > length - offset) {
                throw new IllegalArgumentException("Range extends past object");
            }
            var parts = SqliteObjectData.parts(reader, (String) row[1], length);
            byte[] result = new byte[count];
            int copied = 0;
            var checks = Format.le(crc);
            long first = offset / Format.BLOCK;
            long last = count == 0 ? first - 1 : (offset + count - 1) / Format.BLOCK;
            for (long block = first; block <= last; block++) {
                token.check();
                long at = block * Format.BLOCK;
                byte[] bytes =
                        SqliteObjectData.range(
                                reader,
                                parts,
                                at,
                                (int) Math.min(Format.BLOCK, length - at),
                                token);
                if (checks.getInt((int) block * 4) != (int) Format.crc(bytes)) {
                    return corrupt("Block CRC mismatch");
                }
                int from = (int) Math.max(0, offset - at);
                int take = Math.min(bytes.length - from, count - copied);
                System.arraycopy(bytes, from, result, copied, take);
                copied += take;
            }
            if (requested < 0 && !Arrays.equals(sha, Format.hash("SHA-256", result))) {
                return corrupt("Payload SHA mismatch");
            }
            token.check();
            return new ObjectRead(
                    ReadState.PRESENT,
                    new ObjectInfo(
                            key,
                            length,
                            Origin.values()[(int) origin],
                            Instant.ofEpochMilli(SqliteObjectData.number(row[5]))),
                    result,
                    null);
        } catch (NativeSqlite.Failure error) {
            int code = error.result & 255;
            if (code == 1 || code == 11 || code == 26) {
                return corrupt("Payload table or bytes are missing or corrupt");
            }
            throw error;
        } catch (IOException | ClassCastException | ArithmeticException error) {
            return corrupt("Invalid payload layout or content");
        } finally {
            reader.uncancelled(() -> reader.exec("ROLLBACK"));
        }
    }

    private static ObjectRead corrupt(String reason) {
        return new ObjectRead(ReadState.CORRUPT, null, null, reason);
    }

    /** Metadata-only presence lookup; no payload or integrity data is fetched. */
    public CompletableFuture<List<Long>> storedLengths(List<ObjectKey> keys, Cancellation token) {
        if (keys.size() > 4096) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("At most 4096 keys"));
        }
        List<ObjectKey> owned = List.copyOf(keys);
        return operation(
                () ->
                        reading(
                                token,
                                reader -> {
                                    reader.exec("BEGIN");
                                    try {
                                        var result = new ArrayList<Long>();
                                        for (ObjectKey key : owned) {
                                            token.check();
                                            var rows =
                                                    reader.query(
                                                            "SELECT length FROM storage_objects"
                                                                    + " WHERE k=?",
                                                            key.indexBytes());
                                            result.add(
                                                    rows.isEmpty()
                                                            ? -1L
                                                            : SqliteObjectData.number(
                                                                    rows.getFirst()[0]));
                                        }
                                        return List.copyOf(result);
                                    } finally {
                                        reader.uncancelled(() -> reader.exec("ROLLBACK"));
                                    }
                                }));
    }

    @Override
    public CompletableFuture<List<PutResult>> put(
            List<PutObject> batch, boolean commitNow, Cancellation token) {
        List<PutObject> owned = List.copyOf(batch);
        long bytes = 0;
        for (PutObject object : owned) {
            bytes += object.data().length;
        }
        if (owned.size() > MAX_PUT_OBJECTS || bytes > WriteQueue.RETAINED_BYTES) {
            return CompletableFuture.failedFuture(
                    new StoreException(
                            "OBJECT_STORE_LIMIT", "Put batch exceeds retained-input limits"));
        }
        long retained = bytes;
        return operation(
                () -> {
                    var proofs = new CompletableFuture<List<PreparedPut>>();
                    var started = new java.util.concurrent.atomic.AtomicBoolean();
                    var writing =
                            writes.submit(
                                    retained,
                                    token,
                                    () -> {
                                        started.set(true);
                                        List<PreparedPut> prepared = proofs.join();
                                        var results = new ArrayList<PutResult>();
                                        for (PreparedPut proof : prepared) {
                                            ObjectKey key = proof.input().key();
                                            if (token.isCanceled()) {
                                                results.add(
                                                        new PutResult(
                                                                key, PutOutcome.CANCELED, null));
                                            } else if (proof.refusal() != null) {
                                                results.add(
                                                        new PutResult(
                                                                key,
                                                                PutOutcome.REFUSED,
                                                                proof.refusal().code()));
                                            } else if (SqliteObjectData.exists(database, key)) {
                                                results.add(
                                                        new PutResult(
                                                                key,
                                                                PutOutcome.ALREADY_PRESENT,
                                                                null));
                                            } else {
                                                storePrepared(proof);
                                                results.add(
                                                        new PutResult(
                                                                key, PutOutcome.STORED, null));
                                            }
                                        }
                                        return List.copyOf(results);
                                    },
                                    () ->
                                            Async.submit(
                                                            preparation,
                                                            () -> {
                                                                var values =
                                                                        new ArrayList<
                                                                                PreparedPut>();
                                                                for (PutObject object : owned) {
                                                                    values.add(
                                                                            PreparedPut.prepare(
                                                                                    SqliteObjectData
                                                                                            .verifier(
                                                                                                    object
                                                                                                            .key()),
                                                                                    object,
                                                                                    token));
                                                                }
                                                                return List.copyOf(values);
                                                            })
                                                    .whenComplete(
                                                            (value, error) -> {
                                                                if (error == null) {
                                                                    proofs.complete(value);
                                                                } else {
                                                                    proofs.completeExceptionally(
                                                                            error);
                                                                }
                                                            }),
                                    proofs);
                    return writing.handle(
                                    (value, error) -> {
                                        CompletableFuture<List<PutResult>> outcome =
                                                error == null
                                                        ? CompletableFuture.completedFuture(value)
                                                        : CompletableFuture.failedFuture(error);
                                        return started.get() ? cleanupAfter(outcome) : outcome;
                                    })
                            .thenCompose(future -> future);
                });
    }

    private void storePrepared(PreparedPut prepared) throws IOException {
        PutObject object = prepared.input();
        if (activeStages.containsKey(object.key())) {
            throw new IllegalStateException("Object is being staged");
        }
        if (object.data().length <= SqliteObjectData.CHUNK) {
            SqliteObjectData.storeSmall(database, prepared);
            return;
        }
        var old =
                database.query(
                        "SELECT set_id FROM storage_staging WHERE k=?", object.key().indexBytes());
        if (!old.isEmpty()) {
            SqliteObjectData.markAbandoned(database, object.key(), (String) old.getFirst()[0]);
        }
        String id =
                SqliteObjectData.begin(
                        database,
                        object.key(),
                        object.data().length,
                        object.origin(),
                        Cancellation.NONE,
                        false);
        try {
            for (int offset = 0; offset < object.data().length; offset += SqliteObjectData.CHUNK) {
                byte[] bytes =
                        Arrays.copyOfRange(
                                object.data(),
                                offset,
                                Math.min(object.data().length, offset + SqliteObjectData.CHUNK));
                SqliteObjectData.append(
                        database, object.key(), id, offset, bytes, Cancellation.NONE);
            }
            SqliteObjectData.publish(
                    database,
                    object.key(),
                    id,
                    new SqliteObjectData.Proof(
                            object.data().length, prepared.sha(), prepared.table()),
                    object.origin(),
                    prepared.history(),
                    Cancellation.NONE);
        } catch (IOException | RuntimeException | Error error) {
            // Whole puts own their temporary data. Only explicit ObjectStaging handles promise
            // resumable progress. Never delete a possibly published value after an uncertain
            // commit.
            try {
                if (!database.query(
                                "SELECT set_id FROM storage_staging WHERE k=? AND set_id=?",
                                object.key().indexBytes(),
                                id)
                        .isEmpty()) {
                    SqliteObjectData.markAbandoned(database, object.key(), id);
                }
            } catch (RuntimeException | IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    @Override
    public CompletableFuture<ObjectStaging> stage(
            ObjectKey key, long declaredLength, Origin origin, Cancellation token) {
        if (key.type() != ObjectKey.ENCODED
                || key.namespace() != 0
                || declaredLength < 9
                || declaredLength > Format.MAX_PAYLOAD) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "Staging requires a 9-byte to 512 MiB CDN encoded object"));
        }
        return operation(
                () ->
                        write(
                                0,
                                token,
                                () -> {
                                    token.check();
                                    if (activeStages.containsKey(key)) {
                                        throw new IllegalStateException(
                                                "Key already has an open staging handle");
                                    }
                                    if (SqliteObjectData.exists(database, key)) {
                                        throw new IllegalStateException("Object is already stored");
                                    }
                                    String id =
                                            SqliteObjectData.begin(
                                                    database, key, declaredLength, origin, token);
                                    long progress =
                                            SqliteObjectData.number(
                                                    SqliteObjectData.stage(database, key, id)[1]);
                                    var handle =
                                            new SqliteObjectStaging(
                                                    this,
                                                    key,
                                                    declaredLength,
                                                    origin,
                                                    id,
                                                    progress);
                                    activeStages.put(key, handle);
                                    return handle;
                                }));
    }

    <T> CompletableFuture<T> stageOperation(Supplier<CompletableFuture<T>> action) {
        return operation(action);
    }

    void requireStage(SqliteObjectStaging handle) {
        if (activeStages.get(handle.key()) != handle) {
            throw new IllegalStateException("Staging handle is closed");
        }
    }

    void releaseStage(SqliteObjectStaging handle) {
        if (activeStages.get(handle.key()) == handle) {
            activeStages.remove(handle.key());
        }
    }

    CompletableFuture<SqliteObjectData.Proof> verifyStage(
            SqliteObjectStaging handle, Cancellation token) {
        return reading(
                token,
                reader ->
                        SqliteObjectData.verify(
                                reader, handle.key(), handle.id, handle.declaredLength(), token));
    }

    NativeSqlite writer() {
        return database;
    }

    @Override
    public CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation token) {
        List<ObjectKey> owned = List.copyOf(keys);
        if (owned.size() > 4096) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_LIMIT", "Delete batch exceeds 4096"));
        }
        return operation(
                () ->
                        write(
                                        18L * owned.size(),
                                        token,
                                        () -> {
                                            var result = new ArrayList<DeleteResult>();
                                            for (ObjectKey key : owned) {
                                                token.check();
                                                if (key.type() == ObjectKey.HISTORY_DOCUMENT
                                                        || key.type() == ObjectKey.HISTORY_RECORD) {
                                                    result.add(
                                                            new DeleteResult(
                                                                    key,
                                                                    DeleteOutcome.REFUSED,
                                                                    "OBJECT_DELETE_REFUSED"));
                                                    continue;
                                                }
                                                boolean deleted =
                                                        SqliteObjectData.transaction(
                                                                database,
                                                                token,
                                                                () -> {
                                                                    var rows =
                                                                            database.query(
                                                                                    "SELECT set_id"
                                                                                            + " FROM"
                                                                                            + " storage_objects"
                                                                                            + " WHERE"
                                                                                            + " k=?",
                                                                                    key
                                                                                            .indexBytes());
                                                                    if (rows.isEmpty()) {
                                                                        return false;
                                                                    }
                                                                    database.exec(
                                                                            "DELETE FROM"
                                                                                    + " storage_objects"
                                                                                    + " WHERE k=?",
                                                                            key.indexBytes());
                                                                    database.exec(
                                                                            "UPDATE"
                                                                                    + " storage_chunk_sets"
                                                                                    + " SET garbage=1"
                                                                                    + " WHERE id=?",
                                                                            rows.getFirst()[0]);
                                                                    return true;
                                                                });
                                                result.add(
                                                        new DeleteResult(
                                                                key,
                                                                deleted
                                                                        ? DeleteOutcome.DELETED
                                                                        : DeleteOutcome.ABSENT,
                                                                null));
                                            }
                                            return List.copyOf(result);
                                        })
                                .thenCompose(
                                        result ->
                                                collectNext(Cancellation.NONE)
                                                        .thenApply(ignored -> result)));
    }

    /** Removes one unreferenced chunk per queue turn. Payload bytes stay in SQLite's free pages. */
    public CompletableFuture<Void> collectGarbage(Cancellation token) {
        return operation(
                () -> write(0, token, () -> null).thenCompose(ignored -> collectNext(token)));
    }

    <T> CompletableFuture<T> cleanupAfter(CompletableFuture<T> future) {
        return future.handle(
                        (value, error) ->
                                collectNext(Cancellation.NONE)
                                        .handle(
                                                (ignored, cleanup) -> {
                                                    if (error != null) {
                                                        if (cleanup != null && cleanup != error) {
                                                            error.addSuppressed(cleanup);
                                                        }
                                                        throw new java.util.concurrent
                                                                .CompletionException(error);
                                                    }
                                                    if (cleanup != null) {
                                                        throw new java.util.concurrent
                                                                .CompletionException(cleanup);
                                                    }
                                                    return value;
                                                }))
                .thenCompose(result -> result);
    }

    CompletableFuture<Void> collectNext(Cancellation token) {
        return continueWrite(
                        () -> {
                            token.check();
                            return SqliteObjectData.collectOne(database);
                        })
                .thenCompose(
                        more ->
                                more
                                        ? collectNext(token)
                                        : CompletableFuture.completedFuture(null));
    }

    @Override
    public CompletableFuture<List<PutResult>> appendHistory(
            History.Observation observation, Cancellation token) {
        try {
            return put(History.objects(observation), true, token);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<List<History.Entry>> readHistory(
            History.Query query, Cancellation token) {
        return operation(
                () ->
                        reading(
                                token,
                                reader -> {
                                    var sql =
                                            new StringBuilder(
                                                    "SELECT"
                                                            + " kind,product,region,observed_at,url,document,record"
                                                            + " FROM storage_history WHERE 1=1");
                                    var args = new ArrayList<Object>();
                                    if (query.product() != null) {
                                        sql.append(" AND product=?");
                                        args.add(query.product());
                                    }
                                    if (query.region() != null) {
                                        sql.append(" AND region=?");
                                        args.add(query.region());
                                    }
                                    if (query.kind() != null) {
                                        sql.append(" AND kind=?");
                                        args.add(query.kind());
                                    }
                                    if (query.after() != null) {
                                        sql.append(
                                                " AND (observed_at>? OR (observed_at=? AND"
                                                        + " record>?))");
                                        args.add(query.after().observedAt().toEpochMilli());
                                        args.add(query.after().observedAt().toEpochMilli());
                                        args.add(query.after().record().indexBytes());
                                    }
                                    sql.append(" ORDER BY observed_at,record LIMIT ?");
                                    args.add(query.limit());
                                    var result = new ArrayList<History.Entry>();
                                    for (Object[] row :
                                            reader.query(sql.toString(), args.toArray())) {
                                        token.check();
                                        result.add(
                                                new History.Entry(
                                                        Math.toIntExact(
                                                                SqliteObjectData.number(row[0])),
                                                        (String) row[1],
                                                        (String) row[2],
                                                        Instant.ofEpochMilli(
                                                                SqliteObjectData.number(row[3])),
                                                        (String) row[4],
                                                        new ObjectKey(
                                                                ObjectKey.HISTORY_DOCUMENT,
                                                                (byte[]) row[5]),
                                                        ObjectKey.fromIndex((byte[]) row[6])));
                                    }
                                    return List.copyOf(result);
                                }));
    }

    public record LibraryStats(
            long objects,
            long payloadBytes,
            long pendingObjects,
            long pendingBytes,
            long reclaimableChunks) {}

    public CompletableFuture<LibraryStats> libraryStats(Cancellation token) {
        return operation(
                () ->
                        reading(
                                token,
                                reader -> {
                                    reader.exec("BEGIN");
                                    try {
                                        Object[] objects =
                                                reader.query(
                                                                "SELECT"
                                                                        + " count(*),coalesce(sum(length),0)"
                                                                        + " FROM storage_objects")
                                                        .getFirst();
                                        Object[] stages =
                                                reader.query(
                                                                "SELECT"
                                                                        + " count(*),coalesce(sum(progress),0)"
                                                                        + " FROM storage_staging")
                                                        .getFirst();
                                        long garbage =
                                                reader.scalar(
                                                        "SELECT count(*) FROM"
                                                                + " storage_payload_chunks c JOIN"
                                                                + " storage_chunk_sets s ON"
                                                                + " s.id=c.set_id WHERE s.garbage=1");
                                        return new LibraryStats(
                                                SqliteObjectData.number(objects[0]),
                                                SqliteObjectData.number(objects[1]),
                                                SqliteObjectData.number(stages[0]),
                                                SqliteObjectData.number(stages[1]),
                                                garbage);
                                    } finally {
                                        reader.uncancelled(() -> reader.exec("ROLLBACK"));
                                    }
                                }));
    }

    /** Drains accepted operations, preserves resumable stages, then releases native resources. */
    public CompletableFuture<Void> closeAsync() {
        synchronized (lifecycle) {
            if (closed != null) {
                return closed.copy();
            }
            closing = true;
            closed = new CompletableFuture<>();
            Thread.ofVirtual()
                    .name("vimi-sqlite-close")
                    .start(
                            () -> {
                                try {
                                    synchronized (lifecycle) {
                                        while (!operations.isEmpty()) {
                                            lifecycle.wait();
                                        }
                                    }
                                    writes.shutdown();
                                    while (!writes.awaitTermination(1, TimeUnit.SECONDS)) {
                                        // Accepted verification/publication work is drained before
                                        // native close.
                                    }
                                    readers.shutdown();
                                    preparation.shutdown();
                                    while (!readers.awaitTermination(1, TimeUnit.SECONDS)
                                            || !preparation.awaitTermination(1, TimeUnit.SECONDS)) {
                                        // Native statements must finish before releasing the
                                        // library lock.
                                    }
                                    for (NativeSqlite reader : readerConnections) {
                                        reader.close();
                                    }
                                    readerConnections.clear();
                                    database.close();
                                    lock.close();
                                    closed.complete(null);
                                } catch (Throwable error) {
                                    closed.completeExceptionally(error);
                                }
                            });
            return closed.copy();
        }
    }
}
