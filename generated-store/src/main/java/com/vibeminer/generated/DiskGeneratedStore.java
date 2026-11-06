package com.vibeminer.generated;

import static com.vibeminer.generated.GeneratedTypes.BUSY;
import static com.vibeminer.generated.GeneratedTypes.CLOSED;
import static com.vibeminer.generated.GeneratedTypes.DISK_FULL;
import static com.vibeminer.generated.GeneratedTypes.LIMIT;
import static com.vibeminer.generated.GeneratedTypes.MAX_BATCH;
import static com.vibeminer.generated.GeneratedTypes.MAX_OBJECT_BYTES;
import static com.vibeminer.generated.GeneratedTypes.UNUSABLE;
import static com.vibeminer.generated.GeneratedTypes.WRITE_FAILED;

import com.vibeminer.generated.GeneratedTypes.CompactReport;
import com.vibeminer.generated.GeneratedTypes.Options;
import com.vibeminer.generated.GeneratedTypes.Put;
import com.vibeminer.generated.GeneratedTypes.PutOutcome;
import com.vibeminer.generated.GeneratedTypes.Read;
import com.vibeminer.generated.GeneratedTypes.ReadState;
import com.vibeminer.generated.GeneratedTypes.Stats;
import com.vibeminer.store.ObjectKey;
import com.vibeminer.store.ObjectStore;
import com.vibeminer.store.StoreException;
import com.vibeminer.store.StoreTypes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A separate pack-store instance of kind GENERATED; never opens the CDN store as generated data.
 */
public final class DiskGeneratedStore implements GeneratedStore {
    public static final String DEFAULT_FOLDER_NAME = "generated-v1";
    private final ObjectStore store;
    private final long maxCommitBytes;

    private DiskGeneratedStore(ObjectStore store, long maxCommitBytes) {
        this.store = store;
        this.maxCommitBytes = maxCommitBytes;
    }

    public static CompletableFuture<DiskGeneratedStore> open(Path folder) {
        return open(folder, new Options(), Cancellation.NONE);
    }

    public static CompletableFuture<DiskGeneratedStore> open(
            Path folder, Options options, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return ObjectStore.open(
                                    folder,
                                    new StoreTypes.Options(
                                            StoreTypes.Kind.GENERATED,
                                            options.maxPackBytes(),
                                            options.clock(),
                                            options.packHandles()))
                            .thenCompose(
                                    store -> {
                                        if (cancellation.isCanceled()) {
                                            return store.closeAsync()
                                                    .thenCompose(
                                                            ignored ->
                                                                    CompletableFuture.failedFuture(
                                                                            new CancellationException()));
                                        }
                                        return CompletableFuture.completedFuture(
                                                new DiskGeneratedStore(store, 256L << 20));
                                    });
                });
    }

    // Small deterministic split threshold for contract tests, without allocating hundreds of MiB.
    static DiskGeneratedStore withCommitLimit(ObjectStore store, long limit) {
        return new DiskGeneratedStore(store, limit);
    }

    private static ObjectKey key(GeneratedKey key) {
        return new ObjectKey(ObjectKey.GENERATED, key.bytes());
    }

    /** Local index lengths only; no generated payload decompression or reads. */
    public CompletableFuture<List<Long>> storedLengths(
            List<GeneratedKey> keys, Cancellation token) {
        var owned = List.copyOf(keys);
        return call(
                () ->
                        store.storedLengths(
                                owned.stream().map(DiskGeneratedStore::key).toList(),
                                token.objectToken()));
    }

    @Override
    public CompletableFuture<Boolean> has(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return store.has(key(key), cancellation.objectToken());
                });
    }

    @Override
    public CompletableFuture<Read> read(GeneratedKey key, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return store.read(key(key), cancellation.objectToken())
                            .thenApply(DiskGeneratedStore::readResult);
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
                    return store.readRange(key(key), offset, length, cancellation.objectToken())
                            .thenApply(DiskGeneratedStore::readResult);
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
        return call(
                () -> {
                    cancellation.check();
                    List<Put> owned = List.copyOf(batch);
                    if (owned.size() > MAX_BATCH) {
                        throw new GeneratedStoreException(LIMIT, "Too many objects in one put");
                    }
                    for (Put put : owned) {
                        if (put.data().length == 0 || put.data().length > MAX_OBJECT_BYTES) {
                            throw new GeneratedStoreException(
                                    LIMIT, "An object is empty or over 64 MiB");
                        }
                    }
                    return putPart(owned, 0, new ArrayList<>(), cancellation);
                });
    }

    private CompletableFuture<List<PutOutcome>> putPart(
            List<Put> batch, int start, List<PutOutcome> results, Cancellation cancellation) {
        if (start == batch.size()) {
            return CompletableFuture.completedFuture(List.copyOf(results));
        }
        cancellation.check();
        int end = start;
        long bytes = 0;
        List<StoreTypes.PutObject> part = new ArrayList<>();
        while (end < batch.size()
                && (end == start || bytes + batch.get(end).data().length <= maxCommitBytes)) {
            Put put = batch.get(end++);
            bytes += put.data().length;
            part.add(
                    new StoreTypes.PutObject(
                            key(put.key()),
                            put.data().length,
                            put.data(),
                            StoreTypes.Origin.UNKNOWN));
        }
        int next = end;
        return store.putUnlessRefused(part, true, cancellation.objectToken())
                .thenCompose(
                        outcomes -> {
                            for (StoreTypes.PutResult outcome : outcomes) {
                                results.add(
                                        switch (outcome.outcome()) {
                                            case STORED -> PutOutcome.STORED;
                                            case ALREADY_PRESENT -> PutOutcome.ALREADY_PRESENT;
                                            case CANCELED -> throw new CancellationException();
                                            default ->
                                                    throw new GeneratedStoreException(
                                                            LIMIT,
                                                            "Object refused: " + outcome.code());
                                        });
                            }
                            return putPart(batch, next, results, cancellation);
                        });
    }

    @Override
    public CompletableFuture<Void> delete(List<GeneratedKey> keys, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    List<GeneratedKey> owned = List.copyOf(keys);
                    CompletableFuture<Void> result = CompletableFuture.completedFuture(null);
                    for (int start = 0; start < owned.size(); start += MAX_BATCH) {
                        List<ObjectKey> part =
                                owned
                                        .subList(start, Math.min(start + MAX_BATCH, owned.size()))
                                        .stream()
                                        .map(DiskGeneratedStore::key)
                                        .toList();
                        result =
                                result.thenCompose(
                                        ignored -> {
                                            cancellation.check();
                                            return store.delete(part, cancellation.objectToken())
                                                    .thenApply(deleted -> null);
                                        });
                    }
                    return result;
                });
    }

    public CompletableFuture<Stats> stats() {
        return call(
                () ->
                        store.stats()
                                .thenApply(
                                        s ->
                                                new Stats(
                                                        s.packs(),
                                                        s.objects(),
                                                        s.packBytes(),
                                                        s.liveBytes(),
                                                        s.createdAt())));
    }

    @Override
    public CompletableFuture<CompactReport> compact(
            double minDeadFraction, int maxPacks, Cancellation cancellation) {
        return call(
                () -> {
                    cancellation.check();
                    return store.compact(minDeadFraction, maxPacks, cancellation.objectToken())
                            .thenApply(
                                    r ->
                                            new CompactReport(
                                                    r.packsCompacted(),
                                                    r.bytesBefore(),
                                                    r.bytesAfter(),
                                                    r.moved(),
                                                    r.skippedCorrupt()));
                });
    }

    public CompletableFuture<Void> closeAsync() {
        return call(store::closeAsync);
    }

    private static <T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> action) {
        try {
            return action.get().exceptionallyCompose(e -> CompletableFuture.failedFuture(map(e)));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(map(e));
        }
    }

    // Package-private for the store-code mapping test.
    static Throwable map(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (!(failure instanceof StoreException e)) {
            return failure;
        }
        String code =
                switch (e.code()) {
                    case "OBJECT_STORE_DISK_FULL" -> DISK_FULL;
                    case "OBJECT_STORE_WRITE_FAILED" -> WRITE_FAILED;
                    case "OBJECT_STORE_CLOSED" -> CLOSED;
                    case "OBJECT_STORE_LIMIT" -> LIMIT;
                    case "OBJECT_STORE_BUSY", "OBJECT_STORE_QUEUE_FULL" -> BUSY;
                    default -> UNUSABLE;
                };
        return new GeneratedStoreException(code, e.getMessage(), e);
    }
}
