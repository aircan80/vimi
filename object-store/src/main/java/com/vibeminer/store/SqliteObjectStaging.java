package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A durable staged chunk collection; mutable handle state is confined to the store writer queue.
 */
final class SqliteObjectStaging implements ObjectStaging {
    private final SqliteObjectStore store;
    private final ObjectKey key;
    private final long declared;
    private final Origin origin;
    final String id;
    private volatile long length;
    private boolean committing;
    private boolean closed;

    SqliteObjectStaging(
            SqliteObjectStore store,
            ObjectKey key,
            long declared,
            Origin origin,
            String id,
            long length) {
        this.store = store;
        this.key = key;
        this.declared = declared;
        this.origin = origin;
        this.id = id;
        this.length = length;
    }

    @Override
    public ObjectKey key() {
        return key;
    }

    @Override
    public long declaredLength() {
        return declared;
    }

    @Override
    public long length() {
        return length;
    }

    private void requireOpen() {
        store.requireStage(this);
        if (closed || committing) {
            throw new IllegalStateException("Staging is closed or committing");
        }
    }

    @Override
    public CompletableFuture<Void> append(byte[] bytes, Cancellation token) {
        if (bytes.length < 1 || bytes.length > SqliteObjectData.CHUNK) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Append size is 1 byte to 1 MiB"));
        }
        byte[] owned = bytes.clone();
        return store.stageOperation(
                () ->
                        store.write(
                                owned.length,
                                token,
                                () -> {
                                    requireOpen();
                                    token.check();
                                    SqliteObjectData.append(
                                            store.writer(), key, id, length, owned, token);
                                    length += owned.length;
                                    return null;
                                }));
    }

    @Override
    public CompletableFuture<PutResult> commit(Cancellation token) {
        return store.stageOperation(
                () ->
                        store.write(
                                        0,
                                        token,
                                        () -> {
                                            requireOpen();
                                            if (length != declared) {
                                                throw new IllegalStateException(
                                                        "Only a complete object can commit");
                                            }
                                            if (token.isCanceled()) {
                                                release();
                                                return false;
                                            }
                                            committing = true;
                                            return true;
                                        })
                                .thenCompose(
                                        verify -> {
                                            if (!verify) {
                                                return CompletableFuture.completedFuture(
                                                        new PutResult(
                                                                key, PutOutcome.CANCELED, null));
                                            }
                                            return store.cleanupAfter(
                                                    store.verifyStage(this, token)
                                                            .handle(
                                                                    (proof, error) ->
                                                                            store.continueWrite(
                                                                                    () ->
                                                                                            finish(
                                                                                                    proof,
                                                                                                    error,
                                                                                                    token)))
                                                            .thenCompose(future -> future));
                                        }));
    }

    private PutResult finish(SqliteObjectData.Proof proof, Throwable error, Cancellation token)
            throws IOException {
        try {
            if (token.isCanceled() || unwrap(error) instanceof CancellationException) {
                return new PutResult(key, PutOutcome.CANCELED, null);
            }
            Throwable cause = unwrap(error);
            if (cause instanceof IOException) {
                SqliteObjectData.markAbandoned(store.writer(), key, id);
                return new PutResult(key, PutOutcome.REFUSED, "OBJECT_STAGING_REJECTED");
            }
            if (cause != null) {
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error fatal) {
                    throw fatal;
                }
                throw new IOException("Staging verification failed", cause);
            }
            if (SqliteObjectData.exists(store.writer(), key)) {
                SqliteObjectData.markAbandoned(store.writer(), key, id);
                return new PutResult(key, PutOutcome.ALREADY_PRESENT, null);
            }
            try {
                SqliteObjectData.publish(store.writer(), key, id, proof, origin, null, token);
                return new PutResult(key, PutOutcome.STORED, null);
            } catch (CancellationException canceled) {
                return new PutResult(key, PutOutcome.CANCELED, null);
            }
        } finally {
            release();
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private void release() {
        closed = true;
        committing = false;
        store.releaseStage(this);
    }

    @Override
    public CompletableFuture<Void> abandon() {
        return store.stageOperation(
                () ->
                        store.write(
                                        0,
                                        Cancellation.NONE,
                                        () -> {
                                            requireOpen();
                                            SqliteObjectData.markAbandoned(store.writer(), key, id);
                                            release();
                                            return null;
                                        })
                                .thenCompose(ignored -> store.collectNext(Cancellation.NONE)));
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return store.stageOperation(
                () ->
                        store.write(
                                0,
                                Cancellation.NONE,
                                () -> {
                                    if (!closed) {
                                        requireOpen();
                                        release();
                                    }
                                    return null;
                                }));
    }
}
