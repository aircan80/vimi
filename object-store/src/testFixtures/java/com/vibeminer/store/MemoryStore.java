package com.vibeminer.store;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.vibeminer.store.StoreTypes.*;

/** In-memory layer-boundary fake; no native or file dependencies. */
public final class MemoryStore implements ObjectReader, ObjectWriter, HistoryLog {
    private final Map<ObjectKey, PutObject> objects = new TreeMap<>();
    private <T> CompletableFuture<T> result(java.util.function.Supplier<T> work) {
        try { return CompletableFuture.completedFuture(work.get()); }
        catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
    public CompletableFuture<Boolean> has(ObjectKey key, Cancellation c) { return result(() -> { c.check(); return objects.containsKey(key); }); }
    public CompletableFuture<ObjectRead> read(ObjectKey key, Cancellation c) {
        return result(() -> { c.check(); PutObject p = objects.get(key); return p == null ? new ObjectRead(ReadState.ABSENT, null, null, null) : new ObjectRead(ReadState.PRESENT, new ObjectInfo(key,p.declaredLength(),p.origin(),Instant.EPOCH),p.bytes(),null); });
    }
    public CompletableFuture<ObjectRead> readRange(ObjectKey key, long offset, int length, Cancellation c) {
        return result(() -> {
            if (offset < 0 || length < 0) throw new IllegalArgumentException();
            if (length > Format.WHOLE_LIMIT) throw new StoreException("OBJECT_STORE_LIMIT", "Range too large");
            ObjectRead r = read(key,c).join(); if (r.state() != ReadState.PRESENT) return r;
            if (offset > r.bytes().length || length > r.bytes().length-offset) throw new IllegalArgumentException();
            return new ObjectRead(r.state(),r.info(),Arrays.copyOfRange(r.bytes(),(int)offset,(int)offset+length),null);
        });
    }
    public CompletableFuture<List<PutResult>> put(List<PutObject> batch, boolean commitNow, Cancellation c) {
        return result(() -> {
            if (batch.size() > 4096 || batch.stream().mapToLong(p -> p.data().length).sum() > 256L<<20) throw new StoreException("OBJECT_STORE_LIMIT","Batch too large");
            List<PutResult> results = new ArrayList<>();
            for (PutObject p : batch) {
                Refusal r = Verification.CDN.check(p.key(),p.data(),p.declaredLength());
                String code = r == null ? null : r.code(); History.Entry h = p.key().type()==6 ? History.parse(p.data(),p.key()) : null;
                if (code == null && h != null && !objects.containsKey(h.document())) code = "OBJECT_HISTORY_DOCUMENT_MISSING";
                PutOutcome outcome = code != null ? PutOutcome.REFUSED : c.isCanceled() ? PutOutcome.CANCELED : objects.containsKey(p.key()) ? PutOutcome.ALREADY_PRESENT : PutOutcome.STORED;
                if (outcome == PutOutcome.STORED) objects.put(p.key(),p);
                results.add(new PutResult(p.key(),outcome,code));
            }
            return List.copyOf(results);
        });
    }
    public CompletableFuture<List<DeleteResult>> delete(List<ObjectKey> keys, Cancellation c) {
        return result(() -> {
            List<DeleteResult> results = new ArrayList<>();
            for (ObjectKey key : keys) { c.check(); boolean refused = key.type()==5 || key.type()==6;
                results.add(new DeleteResult(key,refused ? DeleteOutcome.REFUSED : objects.remove(key)==null ? DeleteOutcome.ABSENT : DeleteOutcome.DELETED,refused ? "OBJECT_DELETE_REFUSED" : null)); }
            return List.copyOf(results);
        });
    }
    public CompletableFuture<ObjectStaging> stage(ObjectKey key,long length,Origin origin,Cancellation c) { return CompletableFuture.failedFuture(new UnsupportedOperationException("Use disk staging tests")); }
    public CompletableFuture<List<PutResult>> appendHistory(History.Observation o,Cancellation c) { return put(History.objects(o),true,c); }
    public CompletableFuture<List<History.Entry>> readHistory(History.Query q,Cancellation c) {
        return result(() -> { c.check(); return objects.values().stream().filter(p->p.key().type()==6).map(p->History.parse(p.data(),p.key()))
            .filter(e->q.product()==null || q.product().equals(e.product())).filter(e->q.region()==null || q.region().equals(e.region())).filter(e->q.kind()==null || q.kind()==e.kind())
            .sorted(Comparator.comparing(History.Entry::observedAt).thenComparing(History.Entry::record))
            .filter(e->q.after()==null || e.observedAt().isAfter(q.after().observedAt()) || e.observedAt().equals(q.after().observedAt()) && e.record().compareTo(q.after().record())>0).limit(q.limit()).toList(); });
    }
}
