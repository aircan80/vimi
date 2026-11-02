package com.vibeminer.formats.generated;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.db2.Db2Types;
import com.vibeminer.generated.GeneratedKey;
import com.vibeminer.generated.GeneratedStoreException;
import com.vibeminer.generated.GeneratedTypes;
import com.vibeminer.generated.RecordFile;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Lazy portable record-file rows. Corruption discards the derived node and manifest. */
public final class GeneratedTable {
    public static final int MAX_PAGE_ROWS = RecordFile.MAX_PAGE_RECORDS;
    private final GeneratedTableSource source;
    private final GeneratedKey node;
    private final GeneratedTables.Metadata metadata;
    private final RecordFile file;
    private final Function<Cancellation, CompletableFuture<Void>> discard;

    GeneratedTable(
            GeneratedTableSource source,
            GeneratedKey node,
            GeneratedTables.Metadata metadata,
            RecordFile file,
            Function<Cancellation, CompletableFuture<Void>> discard) {
        this.source = source;
        this.node = node;
        this.metadata = metadata;
        this.file = file;
        this.discard = discard;
    }

    public GeneratedTableSource source() {
        return source;
    }

    public Db2Types.Header header() {
        return metadata.header();
    }

    public List<Db2Types.Section> sections() {
        return metadata.sections();
    }

    public Db2Types.Completion completion() {
        return metadata.completion();
    }

    public List<String> keysUsed() {
        return metadata.keysUsed();
    }

    public List<String> holeKeys() {
        return metadata.holeKeys();
    }

    public long rowCount() {
        return file.count();
    }

    public String identity() {
        return node.hex();
    }

    public boolean scoped() {
        return metadata.identity().encodingIn() != null;
    }

    public CompletableFuture<List<Db2Types.Row>> readPage(
            long first, int count, Cancellation token) {
        return operation(
                token,
                t ->
                        file.read(first, count, t)
                                .thenApply(
                                        records ->
                                                records.stream()
                                                        .map(
                                                                r ->
                                                                        RowCodec.decode(
                                                                                r.id(), r.bytes()))
                                                        .toList()));
    }

    public CompletableFuture<Db2Types.Row> find(long id, Cancellation token) {
        return operation(
                token,
                t ->
                        file.find(id, t)
                                .thenApply(
                                        r ->
                                                r == null
                                                        ? null
                                                        : RowCodec.decode(r.id(), r.bytes())));
    }

    public CompletableFuture<Db2Types.OrdinalLookup> findWithOrdinal(long id, Cancellation token) {
        return operation(
                token,
                t ->
                        file.find(id, t)
                                .thenApply(
                                        r ->
                                                r == null
                                                        ? null
                                                        : new Db2Types.OrdinalLookup(
                                                                r.ordinal(),
                                                                new Db2Types.Found(
                                                                        RowCodec.decode(
                                                                                r.id(),
                                                                                r.bytes())))));
    }

    public com.vibeminer.files.AsyncCursor<Db2Types.Row> rows(Cancellation token) {
        return new com.vibeminer.files.AsyncCursor<>() {
            long first;
            boolean closed;
            final java.util.concurrent.atomic.AtomicBoolean reading =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public CompletableFuture<List<Db2Types.Row>> next(
                    int limit, Cancellation cancellation) {
                if (limit < 1 || limit > MAX_PAGE_ROWS) {
                    throw new IllegalArgumentException("Invalid generated row page size");
                }
                token.check();
                cancellation.check();
                if (closed) {
                    return CompletableFuture.completedFuture(List.of());
                }
                if (!reading.compareAndSet(false, true)) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Concurrent row cursor read"));
                }
                var combined = new Cancellation();
                var outerRegistration = token.onCancel(combined::cancel);
                var innerRegistration = cancellation.onCancel(combined::cancel);
                return readPage(first, limit, combined)
                        .whenComplete(
                                (rows, error) -> {
                                    if (error == null) {
                                        first += rows.size();
                                    }
                                    reading.set(false);
                                    outerRegistration.close();
                                    innerRegistration.close();
                                });
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    private <T> CompletableFuture<T> operation(
            Cancellation token,
            Function<com.vibeminer.generated.Cancellation, CompletableFuture<T>> action) {
        var lower = new com.vibeminer.generated.Cancellation();
        var registration = token.onCancel(lower::cancel);
        CompletableFuture<T> result;
        try {
            result = action.apply(lower);
        } catch (RuntimeException e) {
            registration.close();
            throw e;
        }
        return result.handle(
                (value, error) -> {
                    registration.close();
                    if (error != null) {
                        Throwable cause = error;
                        while (cause instanceof java.util.concurrent.CompletionException
                                && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof GeneratedStoreException e
                                && e.code().equals(GeneratedTypes.CORRUPT)) {
                            try {
                                discard.apply(token).join();
                            } catch (RuntimeException ignored) {
                            }
                        }
                        throw new java.util.concurrent.CompletionException(cause);
                    }
                    return value;
                });
    }
}
