package com.vibeminer.store;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Immutable owner-bound pack-library capability. The application owns the shared store lifetime.
 */
public final class PackLibraryOwner implements ObjectReader, ObjectWriter, HistoryLog {
    private final ObjectStore library;
    private final LibraryOwnership.Claim owner;

    PackLibraryOwner(ObjectStore library, LibraryOwnership.Claim owner) {
        this.library = library;
        this.owner = owner;
    }

    public Path databasePath() {
        return library.databasePath();
    }

    @Override
    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation token) {
        return publish(library.has(key, token));
    }

    @Override
    public CompletableFuture<StoreTypes.ObjectRead> read(ObjectKey key, Cancellation token) {
        return publish(
                library.read(key, token)
                        .thenCompose(
                                read ->
                                        read.state() == StoreTypes.ReadState.PRESENT
                                                ? library.claim(
                                                                owner,
                                                                key,
                                                                read.info().length(),
                                                                token)
                                                        .thenApply(ignored -> read)
                                                : CompletableFuture.completedFuture(read)));
    }

    @Override
    public CompletableFuture<StoreTypes.ObjectRead> readRange(
            ObjectKey key, long offset, int length, Cancellation token) {
        return publish(
                library.readRange(key, offset, length, token)
                        .thenCompose(
                                read ->
                                        read.state() == StoreTypes.ReadState.PRESENT
                                                ? library.claim(
                                                                owner,
                                                                key,
                                                                read.info().length(),
                                                                token)
                                                        .thenApply(ignored -> read)
                                                : CompletableFuture.completedFuture(read)));
    }

    @Override
    public CompletableFuture<List<StoreTypes.PutResult>> put(
            List<StoreTypes.PutObject> batch, boolean commitNow, Cancellation token) {
        return publish(library.putOwned(owner, batch, commitNow, token));
    }

    /** {@link ObjectStore#putUnlessRefused}, owned by this view. */
    public CompletableFuture<List<StoreTypes.PutResult>> putUnlessRefused(
            List<StoreTypes.PutObject> batch, boolean commitNow, Cancellation token) {
        return publish(library.putOwned(owner, batch, commitNow, token, true));
    }

    @Override
    public CompletableFuture<List<StoreTypes.DeleteResult>> delete(
            List<ObjectKey> keys, Cancellation token) {
        return publish(library.delete(keys, token));
    }

    @Override
    public CompletableFuture<ObjectStaging> stage(
            ObjectKey key, long length, StoreTypes.Origin origin, Cancellation token) {
        return publish(
                library.stageOwned(owner, key, length, origin, token).thenApply(OwnedStage::new));
    }

    @Override
    public CompletableFuture<List<StoreTypes.PutResult>> appendHistory(
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
        return publish(library.readHistory(query, token));
    }

    public CompletableFuture<List<Long>> storedLengths(List<ObjectKey> keys, Cancellation token) {
        return publish(library.storedLengths(keys, token));
    }

    private record OwnedStage(ObjectStaging delegate) implements ObjectStaging {
        @Override
        public ObjectKey key() {
            return delegate.key();
        }

        @Override
        public long declaredLength() {
            return delegate.declaredLength();
        }

        @Override
        public long length() {
            return delegate.length();
        }

        @Override
        public CompletableFuture<Void> append(byte[] bytes, Cancellation token) {
            return publish(delegate.append(bytes, token));
        }

        @Override
        public CompletableFuture<StoreTypes.PutResult> commit(Cancellation token) {
            return publish(delegate.commit(token));
        }

        @Override
        public CompletableFuture<Void> abandon() {
            return publish(delegate.abandon());
        }

        @Override
        public CompletableFuture<Void> closeAsync() {
            return publish(delegate.closeAsync());
        }
    }

    private static <T> CompletableFuture<T> publish(CompletableFuture<T> work) {
        var result = new CompletableFuture<T>();
        work.whenComplete(
                (value, error) ->
                        Thread.startVirtualThread(
                                () -> {
                                    if (error == null) {
                                        result.complete(value);
                                    } else {
                                        result.completeExceptionally(error);
                                    }
                                }));
        return result;
    }
}
