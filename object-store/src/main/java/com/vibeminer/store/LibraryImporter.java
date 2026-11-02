package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.ObjectInfo;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import com.vibeminer.store.StoreTypes.ReadState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, bounded copy of selected objects into a new library. Never deletes or enumerates the
 * source. Use a read-only source to avoid access-bookkeeping changes; SQLite readers may still
 * create coordination sidecars. Both stores are borrowed and must remain open while accepted work
 * drains.
 */
public final class LibraryImporter {
    public static final int MAX_KEYS = 4096;
    private static final int CHUNK_BYTES = 1 << 20;
    private final ObjectReader source;
    private final SqliteObjectStore destination;
    private final AtomicBoolean running = new AtomicBoolean();

    public enum Outcome {
        COPIED,
        ALREADY_PRESENT,
        SOURCE_ABSENT,
        SOURCE_UNREADABLE,
        REFUSED
    }

    public record Entry(ObjectKey key, Outcome outcome, long bytes, String problem) {}

    public record Report(List<Entry> entries) {
        public Report {
            entries = List.copyOf(entries);
        }
    }

    public LibraryImporter(ObjectReader source, SqliteObjectStore destination) {
        this.source = Objects.requireNonNull(source);
        this.destination = Objects.requireNonNull(destination);
    }

    /**
     * Copies at most 4096 explicitly supplied keys in order, with one active import per importer.
     * Existing destination objects are retained without overwrite. Missing/unreadable sources and
     * target verification refusals have explicit entries; unexpected failures complete
     * exceptionally. Cancellation completes exceptionally, keeps previously committed objects and
     * resumable chunks, and returns no successful report. Retry the same keys to continue. It is
     * not an atomic batch. This imports payloads only, not history indexes, build ownership,
     * settings or activation state.
     */
    public CompletableFuture<Report> importKeys(List<ObjectKey> keys, Cancellation cancellation) {
        final List<ObjectKey> owned;
        try {
            Objects.requireNonNull(cancellation);
            owned = List.copyOf(keys);
            if (owned.size() > MAX_KEYS) {
                throw new IllegalArgumentException("Import at most 4096 selected keys");
            }
            cancellation.check();
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_BUSY", "Another import is already running"));
        }
        var result = new CompletableFuture<Report>();
        var token = new Cancellation();
        Cancellation.Registration registration = cancellation.onCancel(token::cancel);
        result.whenComplete(
                (value, failure) -> {
                    if (result.isCancelled()) {
                        token.cancel();
                    }
                });
        try {
            Thread.startVirtualThread(
                    () -> {
                        Report report = null;
                        Throwable failure = null;
                        try {
                            var entries = new ArrayList<Entry>();
                            for (ObjectKey key : owned) {
                                token.check();
                                entries.add(copy(key, token));
                            }
                            token.check();
                            report = new Report(entries);
                        } catch (Throwable error) {
                            failure = unwrap(error);
                        } finally {
                            registration.close();
                            running.set(false);
                        }
                        if (failure == null) {
                            result.complete(report);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (RuntimeException | Error failure) {
            registration.close();
            running.set(false);
            result.completeExceptionally(failure);
        }
        return result;
    }

    private Entry copy(ObjectKey key, Cancellation token) {
        if (destination.has(key, token).join()) {
            long length = destination.storedLengths(List.of(key), token).join().getFirst();
            if (length >= 0) {
                return new Entry(key, Outcome.ALREADY_PRESENT, length, null);
            }
        }
        ObjectRead probe = source.readRange(key, 0, 0, token).join();
        Entry unreadable = sourceProblem(key, probe);
        if (unreadable != null) {
            return unreadable;
        }
        ObjectInfo info = probe.info();
        long length = info.length();
        if (length < 1 || length > Format.MAX_PAYLOAD) {
            return new Entry(key, Outcome.REFUSED, 0, "OBJECT_TOO_LARGE");
        }
        if (length > Format.WHOLE_LIMIT) {
            if (key.type() != ObjectKey.ENCODED) {
                return new Entry(key, Outcome.REFUSED, 0, "OBJECT_TOO_LARGE");
            }
            return copyLarge(key, info, token);
        }
        ObjectRead read = source.read(key, token).join();
        unreadable = sourceProblem(key, read);
        if (unreadable != null) {
            return unreadable;
        }
        byte[] bytes = read.bytes();
        if (read.info().length() != length || bytes == null || bytes.length != length) {
            return new Entry(
                    key, Outcome.SOURCE_UNREADABLE, 0, "Source length changed or is invalid");
        }
        token.check();
        var put = new StoreTypes.PutObject(key, length, bytes, info.origin());
        PutResult result = destination.put(List.of(put), true, token).join().getFirst();
        return outcome(key, length, result);
    }

    private Entry copyLarge(ObjectKey key, ObjectInfo info, Cancellation token) {
        ObjectStaging staging = destination.stage(key, info.length(), info.origin(), token).join();
        Throwable failure = null;
        try {
            for (long offset = staging.length(); offset < info.length(); ) {
                token.check();
                int count = (int) Math.min(CHUNK_BYTES, info.length() - offset);
                ObjectRead read = source.readRange(key, offset, count, token).join();
                Entry problem = sourceProblem(key, read);
                if (problem != null) {
                    return problem;
                }
                byte[] bytes = read.bytes();
                if (read.info().length() != info.length()
                        || bytes == null
                        || bytes.length != count) {
                    return new Entry(
                            key,
                            Outcome.SOURCE_UNREADABLE,
                            0,
                            "Source range changed or is invalid");
                }
                staging.append(bytes, token).join();
                offset += count;
            }
            token.check();
            return outcome(key, info.length(), staging.commit(token).join());
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                staging.closeAsync().join();
            } catch (RuntimeException | Error closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    private static Entry sourceProblem(ObjectKey key, ObjectRead read) {
        if (read.state() == ReadState.ABSENT) {
            return new Entry(key, Outcome.SOURCE_ABSENT, 0, null);
        }
        if (read.state() != ReadState.PRESENT) {
            return new Entry(key, Outcome.SOURCE_UNREADABLE, 0, read.problem());
        }
        if (read.info() == null || !key.equals(read.info().key()) || read.info().origin() == null) {
            return new Entry(key, Outcome.SOURCE_UNREADABLE, 0, "Invalid source identity metadata");
        }
        return null;
    }

    private static Entry outcome(ObjectKey key, long length, PutResult result) {
        if (!key.equals(result.key())) {
            throw new IllegalStateException("Destination returned a different object key");
        }
        if (result.outcome() == PutOutcome.CANCELED) {
            throw new CancellationException();
        }
        return switch (result.outcome()) {
            case STORED -> new Entry(key, Outcome.COPIED, length, null);
            case ALREADY_PRESENT -> new Entry(key, Outcome.ALREADY_PRESENT, length, null);
            default -> new Entry(key, Outcome.REFUSED, 0, result.code());
        };
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
