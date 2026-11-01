package com.vibeminer.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import static java.nio.file.StandardOpenOption.*;
import com.vibeminer.store.StoreTypes.*;

/** A resumable encoded object, owned by the store's writer queue. */
final class Staging implements ObjectStaging {
    final ObjectStore store;
    final ObjectKey key;
    final long declared;
    final Origin origin;
    final Path path;
    ObjectCheck check;
    volatile long length;
    boolean closed, broken;

    private Staging(ObjectStore store, ObjectKey key, long declared, Origin origin) {
        this.store = store; this.key = key; this.declared = declared; this.origin = origin;
        path = store.staging.resolve(java.util.HexFormat.of().formatHex(key.indexBytes()) + ".part");
    }
    static Staging open(ObjectStore store, ObjectKey key, long declared, Origin origin, Cancellation cancellation) throws IOException {
        if (store.kind() != Kind.CDN || key.type() != ObjectKey.ENCODED || key.namespace() != 0 || declared < 1 || declared > Format.MAX_PAYLOAD)
            throw new IllegalArgumentException("Staging requires a CDN encoded object of 1–512 MiB");
        if (store.stagings.containsKey(key)) throw new IllegalStateException("Key is already being staged");
        if (store.stagings.size() >= 16 || declared > (2L << 30) - store.stagingBytes)
            throw new StoreException("OBJECT_STORE_LIMIT", "Staging budget or key already in use");
        Staging result = new Staging(store, key, declared, java.util.Objects.requireNonNull(origin));
        try (FileChannel file = FileChannel.open(result.path, CREATE, READ, WRITE)) {
            ObjectCheck probe = store.verifier.begin(key, declared);
            long existing = file.size();
            for (long at = 0; existing <= declared && at < existing;) {
                cancellation.check();
                byte[] bytes = Format.read(file, at, (int)Math.min(Format.BLOCK, file.size() - at));
                if (probe.append(bytes) != null) break;
                at += bytes.length;
            }
            cancellation.check();
            result.length = probe.provenLength(); file.truncate(result.length);
            result.check = store.verifier.begin(key, declared);
            for (long at = 0; at < result.length;) {
                cancellation.check();
                byte[] bytes = Format.read(file, at, (int)Math.min(Format.BLOCK, result.length - at));
                result.check.append(bytes); at += bytes.length;
            }
        }
        store.stagings.put(key, result); store.stagingBytes += declared; return result;
    }
    public ObjectKey key() { return key; }
    public long declaredLength() { return declared; }
    public long length() { return length; }
    private void requireOpen() { if (closed) throw ObjectStore.closed(); }
    private void requireUsable() {
        requireOpen();
        if (broken) throw new StoreException("OBJECT_STAGING_REJECTED", "Start staging again after closing this refused handle");
    }
    public CompletableFuture<Void> append(byte[] bytes, Cancellation cancellation) {
        byte[] owned = bytes.clone();
        return store.write(() -> {
            requireUsable(); cancellation.check();
            if (owned.length > declared - length) throw new IllegalArgumentException("Append exceeds declared length");
            try (FileChannel file = FileChannel.open(path, WRITE)) {
                Format.write(file, length, owned);
                Refusal refusal = check.append(owned);
                if (refusal != null) {
                    broken = true; length = check.provenLength(); file.truncate(length);
                    throw new StoreException("OBJECT_STAGING_REJECTED", refusal.code());
                }
                length += owned.length;
            }
            return null;
        });
    }
    public CompletableFuture<PutResult> commit(Cancellation cancellation) {
        return store.write(() -> {
            requireOpen();
            if (broken || length != declared) throw new IllegalStateException("Only a complete unrefused staging can be committed");
            if (cancellation.isCanceled()) {
                release(false);
                return new PutResult(key, PutOutcome.CANCELED, null);
            }
            try {
                Refusal refusal = check.complete();
                if (refusal != null) return new PutResult(key, PutOutcome.REFUSED, refusal.code());
                if (store.index.find(key) != null) return new PutResult(key, PutOutcome.ALREADY_PRESENT, null);
                store.append(key, declared, origin, false, null, path, null);
                return new PutResult(key, PutOutcome.STORED, null);
            } catch (IOException e) { throw ObjectStore.translate(e, "OBJECT_STORE_WRITE_FAILED"); }
            catch (StoreException e) {
                if (e.code().startsWith("OBJECT_STORE_")) throw e;
                return new PutResult(key, PutOutcome.REFUSED, e.code());
            } finally { release(true); }
        });
    }
    void release(boolean delete) throws IOException {
        if (closed) return;
        closed = true;
        if (store.stagings.remove(key, this)) store.stagingBytes -= declared;
        if (delete) Files.deleteIfExists(path);
    }
    public CompletableFuture<Void> abandon() { return store.write(() -> { release(true); return null; }); }
    public CompletableFuture<Void> closeAsync() { return store.write(() -> { release(false); return null; }); }
}
