package com.vibeminer.generated;

import static com.vibeminer.generated.GeneratedTypes.BUSY;
import static com.vibeminer.generated.GeneratedTypes.CLOSED;
import static com.vibeminer.generated.GeneratedTypes.LIMIT;
import static com.vibeminer.generated.GeneratedTypes.MAX_OBJECT_BYTES;
import static com.vibeminer.generated.GeneratedTypes.READ_ONLY;
import static com.vibeminer.generated.GeneratedTypes.UNUSABLE;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectReader;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Generated data read straight from a store reader, typically a {@code ReadOnlyStore} over the pack
 * library opened without the writer lock. Reads record no ownership claims and nothing else; every
 * write ({@link #put}, {@link #delete}, {@link #compact}) fails with {@link
 * GeneratedTypes#READ_ONLY} without touching the store. The caller owns the reader's lifetime.
 */
public final class ReadOnlyGeneratedStore implements GeneratedStore {
    private final ObjectReader reader;

    public ReadOnlyGeneratedStore(ObjectReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    private static ObjectKey key(GeneratedKey key) {
        return new ObjectKey(ObjectKey.GENERATED, key.bytes());
    }

    @Override
    public CompletableFuture<Boolean> has(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return reader.has(key(key), cancellation.objectToken());
                });
    }

    @Override
    public CompletableFuture<Read> read(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return reader.read(key(key), cancellation.objectToken())
                            .thenApply(ReadOnlyGeneratedStore::readResult);
                });
    }

    @Override
    public CompletableFuture<Read> readRange(
            GeneratedKey key, long offset, int length, Cancellation cancellation) {
        return call(
                () -> {
                    if (offset < 0 || length < 0 || length > MAX_OBJECT_BYTES) {
                        throw new IllegalArgumentException("Range is past the object's end");
                    }
                    cancellation.check();
                    return reader.readRange(key(key), offset, length, cancellation.objectToken())
                            .thenApply(ReadOnlyGeneratedStore::readResult);
                });
    }

    private static Read readResult(StoreTypes.ObjectRead read) {
        return switch (read.state()) {
            case PRESENT -> new Read(ReadState.PRESENT, read.info().length(), read.bytes(), null);
            case ABSENT -> new Read(ReadState.ABSENT, 0, null, null);
            default -> new Read(ReadState.CORRUPT, 0, null, read.problem());
        };
    }

    @Override
    public CompletableFuture<List<PutOutcome>> put(List<Put> batch, Cancellation cancellation) {
        return refused();
    }

    @Override
    public CompletableFuture<Void> delete(List<GeneratedKey> keys, Cancellation cancellation) {
        return refused();
    }

    @Override
    public CompletableFuture<CompactReport> compact(
            double minDeadFraction, int maxPacks, Cancellation cancellation) {
        return refused();
    }

    private static <T> CompletableFuture<T> refused() {
        return CompletableFuture.failedFuture(
                new GeneratedStoreException(READ_ONLY, "Generated data is opened read-only"));
    }

    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get().exceptionallyCompose(e -> CompletableFuture.failedFuture(map(e)));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    private static Throwable map(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (!(failure instanceof StoreException e)) {
            return failure;
        }
        String code =
                switch (e.code()) {
                    case "OBJECT_STORE_CLOSED" -> CLOSED;
                    case "OBJECT_STORE_LIMIT" -> LIMIT;
                    case "OBJECT_STORE_BUSY", "OBJECT_STORE_QUEUE_FULL" -> BUSY;
                    default -> UNUSABLE;
                };
        return new GeneratedStoreException(code, e.getMessage(), e);
    }
}
