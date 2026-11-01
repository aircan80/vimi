package com.vibeminer.store;

import com.vibeminer.store.StoreTypes.DeleteOutcome;
import com.vibeminer.store.StoreTypes.DeleteResult;
import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.ObjectInfo;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import com.vibeminer.store.StoreTypes.ReadState;
import com.vibeminer.store.StoreTypes.Refusal;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Deterministic role-boundary fake. State is in memory; no SQLite or filesystem access. */
public final class MemoryStore implements ObjectReader, ObjectWriter, HistoryLog {
    private final Kind kind;
    private final Verification verifier;
    private final Map<ObjectKey, PutObject> objects = new TreeMap<>();
    private final Map<ObjectKey, byte[]> stagedBytes = new HashMap<>();
    private final Map<ObjectKey, Stage> stages = new HashMap<>();
    private long stagingBudget;
    private boolean closed;

    public MemoryStore() {
        this(Kind.CDN);
    }

    public MemoryStore(Kind kind) {
        this.kind = Objects.requireNonNull(kind);
        verifier = kind == Kind.CDN ? Verification.CDN : Verification.GENERATED;
    }

    private synchronized <T> CompletableFuture<T> result(Supplier<T> work) {
        try {
            if (closed) {
                throw new StoreException("OBJECT_STORE_CLOSED", "Store is closed");
            }
            return CompletableFuture.completedFuture(work.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation c) {
        return result(
                () -> {
                    c.check();
                    return objects.containsKey(key);
                });
    }

    public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation c) {
        return result(
                () -> {
                    c.check();
                    return readCore(key, 0, -1);
                });
    }

    private ObjectRead readCore(ObjectKey key, long offset, int length) {
        PutObject object = objects.get(key);
        if (object == null) {
            return new ObjectRead(ReadState.ABSENT, null, null, null);
        }
        if (offset > object.declaredLength()
                || length >= 0 && length > object.declaredLength() - offset) {
            throw new IllegalArgumentException("Range extends past object");
        }
        byte[] bytes =
                length < 0
                        ? object.bytes()
                        : Arrays.copyOfRange(object.data(), (int) offset, (int) offset + length);
        return new ObjectRead(
                ReadState.PRESENT,
                new ObjectInfo(key, object.declaredLength(), object.origin(), Instant.EPOCH),
                bytes,
                null);
    }

    public CompletableFuture<ObjectRead> readRange(
            ObjectKey key, long offset, int length, Cancellation c) {
        if (offset < 0 || length < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Negative range"));
        }
        if (length > Format.WHOLE_LIMIT) {
            return CompletableFuture.failedFuture(
                    new StoreException("OBJECT_STORE_LIMIT", "Range too large"));
        }
        return result(
                () -> {
                    c.check();
                    return readCore(key, offset, length);
                });
    }

    public CompletableFuture<List<PutResult>> put(
            List<PutObject> batch, boolean commitNow, Cancellation c) {
        List<PutObject> owned = List.copyOf(batch);
        return result(
                () -> {
                    if (owned.size() > 4096
                            || owned.stream().mapToLong(p -> p.data().length).sum() > 256L << 20) {
                        throw new StoreException("OBJECT_STORE_LIMIT", "Batch too large");
                    }
                    List<PutResult> results = new ArrayList<>();
                    for (PutObject p : owned) {
                        Refusal refusal = verifier.check(p.key(), p.data(), p.declaredLength());
                        if (refusal != null) {
                            results.add(new PutResult(p.key(), PutOutcome.REFUSED, refusal.code()));
                            continue;
                        }
                        if (c.isCanceled()) {
                            results.add(new PutResult(p.key(), PutOutcome.CANCELED, null));
                            continue;
                        }
                        if (objects.containsKey(p.key())) {
                            results.add(new PutResult(p.key(), PutOutcome.ALREADY_PRESENT, null));
                            continue;
                        }
                        History.Entry h =
                                p.key().type() == 6 ? History.parse(p.data(), p.key()) : null;
                        if (h != null && !objects.containsKey(h.document())) {
                            results.add(
                                    new PutResult(
                                            p.key(),
                                            PutOutcome.REFUSED,
                                            "OBJECT_HISTORY_DOCUMENT_MISSING"));
                            continue;
                        }
                        objects.put(p.key(), p);
                        results.add(new PutResult(p.key(), PutOutcome.STORED, null));
                    }
                    return List.copyOf(results);
                });
    }

    public CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation c) {
        List<ObjectKey> owned = List.copyOf(keys);
        return result(
                () -> {
                    if (owned.size() > 4096) {
                        throw new StoreException("OBJECT_STORE_LIMIT", "Delete batch too large");
                    }
                    List<DeleteResult> results = new ArrayList<>();
                    for (ObjectKey key : owned) {
                        c.check();
                        boolean refused = key.type() == 5 || key.type() == 6;
                        results.add(
                                new DeleteResult(
                                        key,
                                        refused
                                                ? DeleteOutcome.REFUSED
                                                : objects.remove(key) == null
                                                        ? DeleteOutcome.ABSENT
                                                        : DeleteOutcome.DELETED,
                                        refused ? "OBJECT_DELETE_REFUSED" : null));
                    }
                    return List.copyOf(results);
                });
    }

    public CompletableFuture<List<PutResult>> appendHistory(
            History.Observation observation, Cancellation c) {
        return put(History.objects(observation), true, c);
    }

    public CompletableFuture<List<History.Entry>> readHistory(History.Query query, Cancellation c) {
        return result(
                () -> {
                    c.check();
                    return objects.values().stream()
                            .filter(p -> p.key().type() == 6)
                            .map(p -> History.parse(p.data(), p.key()))
                            .filter(
                                    e ->
                                            query.product() == null
                                                    || query.product().equals(e.product()))
                            .filter(
                                    e ->
                                            query.region() == null
                                                    || query.region().equals(e.region()))
                            .filter(e -> query.kind() == null || query.kind() == e.kind())
                            .sorted(
                                    Comparator.comparing(History.Entry::observedAt)
                                            .thenComparing(History.Entry::record))
                            .filter(
                                    e ->
                                            query.after() == null
                                                    || e.observedAt()
                                                            .isAfter(query.after().observedAt())
                                                    || e.observedAt()
                                                                    .equals(
                                                                            query.after()
                                                                                    .observedAt())
                                                            && e.record()
                                                                            .compareTo(
                                                                                    query.after()
                                                                                            .record())
                                                                    > 0)
                            .limit(query.limit())
                            .toList();
                });
    }

    public CompletableFuture<ObjectStaging> stage(
            ObjectKey key, long length, Origin origin, Cancellation c) {
        return result(
                () -> {
                    c.check();
                    Objects.requireNonNull(origin);
                    if (kind != Kind.CDN
                            || key.type() != 4
                            || key.namespace() != 0
                            || length < 1
                            || length > Format.MAX_PAYLOAD) {
                        throw new IllegalArgumentException(
                                "Staging requires a CDN encoded object of 1–512 MiB");
                    }
                    if (stages.containsKey(key)) {
                        throw new IllegalStateException("Key is already being staged");
                    }
                    if (stages.size() >= 16 || length > (2L << 30) - stagingBudget) {
                        throw new StoreException("OBJECT_STORE_LIMIT", "Staging budget is in use");
                    }
                    Stage stage = new Stage(key, length, origin);
                    stages.put(key, stage);
                    stagingBudget += length;
                    return stage;
                });
    }

    public synchronized CompletableFuture<Void> closeAsync() {
        for (Stage stage : List.copyOf(stages.values())) {
            stage.release(false);
        }
        closed = true;
        return CompletableFuture.completedFuture(null);
    }

    private final class Stage implements ObjectStaging {
        private final ObjectKey key;
        private final long declared;
        private final Origin origin;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ObjectCheck check;
        private boolean finished, broken;

        Stage(ObjectKey key, long declared, Origin origin) {
            this.key = key;
            this.declared = declared;
            this.origin = origin;
            byte[] saved = stagedBytes.getOrDefault(key, new byte[0]);
            ObjectCheck probe = verifier.begin(key, declared);
            if (saved.length <= declared) {
                probe.append(saved);
            }
            buffer.writeBytes(Arrays.copyOf(saved, (int) probe.provenLength()));
            stagedBytes.put(key, buffer.toByteArray());
            check = verifier.begin(key, declared);
            check.append(buffer.toByteArray());
        }

        public ObjectKey key() {
            return key;
        }

        public long declaredLength() {
            return declared;
        }

        public long length() {
            synchronized (MemoryStore.this) {
                return buffer.size();
            }
        }

        private void requireOpen() {
            if (finished) {
                throw new StoreException("OBJECT_STORE_CLOSED", "Staging is closed");
            }
        }

        public CompletableFuture<Void> append(byte[] bytes, Cancellation c) {
            byte[] owned = bytes.clone();
            return result(
                    () -> {
                        requireOpen();
                        if (broken) {
                            throw new StoreException(
                                    "OBJECT_STAGING_REJECTED", "Start staging again");
                        }
                        c.check();
                        if (owned.length > declared - buffer.size()) {
                            throw new IllegalArgumentException("Append exceeds declared length");
                        }
                        buffer.writeBytes(owned);
                        Refusal refusal = check.append(owned);
                        if (refusal != null) {
                            broken = true;
                            byte[] proven =
                                    Arrays.copyOf(buffer.toByteArray(), (int) check.provenLength());
                            buffer.reset();
                            buffer.writeBytes(proven);
                            stagedBytes.put(key, proven);
                            throw new StoreException("OBJECT_STAGING_REJECTED", refusal.code());
                        }
                        stagedBytes.put(key, buffer.toByteArray());
                        return null;
                    });
        }

        public CompletableFuture<PutResult> commit(Cancellation c) {
            return result(
                    () -> {
                        requireOpen();
                        if (broken || buffer.size() != declared) {
                            throw new IllegalStateException(
                                    "Only complete unrefused staging can be committed");
                        }
                        if (c.isCanceled()) {
                            release(false);
                            return new PutResult(key, PutOutcome.CANCELED, null);
                        }
                        try {
                            Refusal refusal = check.complete();
                            if (refusal != null) {
                                return new PutResult(key, PutOutcome.REFUSED, refusal.code());
                            }
                            if (objects.containsKey(key)) {
                                return new PutResult(key, PutOutcome.ALREADY_PRESENT, null);
                            }
                            objects.put(
                                    key,
                                    new PutObject(key, declared, buffer.toByteArray(), origin));
                            return new PutResult(key, PutOutcome.STORED, null);
                        } finally {
                            release(true);
                        }
                    });
        }

        void release(boolean delete) {
            if (finished) {
                return;
            }
            finished = true;
            if (stages.remove(key, this)) {
                stagingBudget -= declared;
            }
            if (delete) {
                stagedBytes.remove(key);
            }
        }

        public CompletableFuture<Void> abandon() {
            return result(
                    () -> {
                        release(true);
                        return null;
                    });
        }

        public CompletableFuture<Void> closeAsync() {
            return result(
                    () -> {
                        release(false);
                        return null;
                    });
        }
    }
}
