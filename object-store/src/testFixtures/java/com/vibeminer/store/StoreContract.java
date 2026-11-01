package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.check;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;

import com.vibeminer.store.StoreTypes.DeleteOutcome;
import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.ObjectRead;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.PutResult;
import com.vibeminer.store.StoreTypes.ReadState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Reusable public-role contracts. Each method expects a fresh store. No JUnit dependency. */
public final class StoreContract {
    private StoreContract() {}

    public static void verify(ObjectReader reader) {
        readerWriter(reader, (ObjectWriter) reader);
        history(reader, (HistoryLog) reader, (ObjectWriter) reader);
    }

    public static void readerWriter(ObjectReader reader, ObjectWriter writer) {
        byte[] bytes = {4, 5, 6};
        ObjectKey key = key(bytes);
        check(!reader.has(key, NONE).join());
        check(reader.read(key, NONE).join().state() == ReadState.ABSENT);
        check(
                writer.put(List.of(new PutObject(key, bytes)), true, NONE)
                                .join()
                                .getFirst()
                                .outcome()
                        == PutOutcome.STORED);
        check(
                writer.put(List.of(new PutObject(key, bytes)), true, NONE)
                                .join()
                                .getFirst()
                                .outcome()
                        == PutOutcome.ALREADY_PRESENT);
        check(Arrays.equals(reader.read(key, NONE).join().bytes(), bytes));
        byte[] returned = reader.read(key, NONE).join().bytes();
        returned[0] = 99;
        check(Arrays.equals(reader.read(key, NONE).join().bytes(), bytes));
        check(Arrays.equals(reader.readRange(key, 1, 2, NONE).join().bytes(), new byte[] {5, 6}));
        check(reader.readRange(key, 3, 0, NONE).join().bytes().length == 0);
        expect(IllegalArgumentException.class, reader.readRange(key, 2, 2, NONE));
        expect(IllegalArgumentException.class, reader.readRange(key, -1, 0, NONE));
        failure(reader.readRange(key, 0, Format.WHOLE_LIMIT + 1, NONE), "OBJECT_STORE_LIMIT");
        var bad =
                writer.put(List.of(new PutObject(key, new byte[] {9})), true, NONE)
                        .join()
                        .getFirst();
        check(bad.outcome() == PutOutcome.REFUSED && "OBJECT_KEY_MISMATCH".equals(bad.code()));
        ObjectKey namespaced = new ObjectKey(1, 1, key.bytes());
        check(
                "OBJECT_NAMESPACE_UNSUPPORTED"
                        .equals(
                                writer.put(List.of(new PutObject(namespaced, bytes)), true, NONE)
                                        .join()
                                        .getFirst()
                                        .code()));
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        ObjectKey absent = key(new byte[] {7});
        check(
                writer.put(List.of(new PutObject(absent, new byte[] {7})), true, canceled)
                                .join()
                                .getFirst()
                                .outcome()
                        == PutOutcome.CANCELED);
        check(!reader.has(absent, NONE).join());
        expect(CancellationException.class, reader.read(key, canceled));
        expect(CancellationException.class, writer.delete(List.of(key), canceled));
        failure(writer.delete(Collections.nCopies(4097, key), NONE), "OBJECT_STORE_LIMIT");
        failure(
                writer.put(Collections.nCopies(4097, new PutObject(key, bytes)), true, NONE),
                "OBJECT_STORE_LIMIT");
        check(
                writer.delete(List.of(key), NONE).join().getFirst().outcome()
                        == DeleteOutcome.DELETED);
        check(
                writer.delete(List.of(key), NONE).join().getFirst().outcome()
                        == DeleteOutcome.ABSENT);
        check(reader.read(key, NONE).join().state() == ReadState.ABSENT);
    }

    public static void history(ObjectReader reader, HistoryLog history, ObjectWriter writer) {
        byte[] reply = {4, 5, 6};
        var first = new History.Observation(1, "wow", "us", Instant.EPOCH, "", reply);
        var second =
                new History.Observation(1, "wow", "us", Instant.EPOCH.plusMillis(1), "", reply);
        var tied = new History.Observation(1, "wow", "us", Instant.EPOCH, "another", reply);
        List<PutObject> observation = History.objects(first);
        var missing = writer.put(List.of(observation.getLast()), true, NONE).join().getFirst();
        check("OBJECT_HISTORY_DOCUMENT_MISSING".equals(missing.code()));
        history.appendHistory(first, NONE).join();
        history.appendHistory(first, NONE).join();
        history.appendHistory(second, NONE).join();
        history.appendHistory(tied, NONE).join();
        List<History.Entry> all = history.readHistory(new History.Query(), NONE).join();
        check(all.size() == 3);
        check(reader.has(all.getFirst().document(), NONE).join());
        List<History.Entry> page =
                history.readHistory(new History.Query("wow", "us", 1, null, 1), NONE).join();
        check(page.size() == 1 && page.getFirst().equals(all.getFirst()));
        check(
                history.readHistory(new History.Query("wow", "us", 1, page.getFirst(), 1), NONE)
                        .join()
                        .equals(List.of(all.get(1))));
        check(
                all.get(0).observedAt().equals(all.get(1).observedAt())
                        && all.get(0).record().compareTo(all.get(1).record()) < 0);
        check(
                history.readHistory(new History.Query(null, null, null, all.getLast(), 1), NONE)
                        .join()
                        .isEmpty());
        check(
                history.readHistory(new History.Query("other", null, null, null, 10), NONE)
                        .join()
                        .isEmpty());
        check(
                history.readHistory(new History.Query(null, "eu", null, null, 10), NONE)
                        .join()
                        .isEmpty());
        check(
                history.readHistory(new History.Query(null, null, 2, null, 10), NONE)
                        .join()
                        .isEmpty());
        for (PutObject object : observation) {
            check(
                    writer.delete(List.of(object.key()), NONE).join().getFirst().outcome()
                            == DeleteOutcome.REFUSED);
        }
    }

    public static void generated(ObjectReader reader, ObjectWriter writer, HistoryLog history) {
        ObjectKey key = new ObjectKey(7, new byte[16]);
        byte[] bytes = {1, 2, 3};
        check(
                writer.put(List.of(new PutObject(key, bytes)), true, NONE)
                                .join()
                                .getFirst()
                                .outcome()
                        == PutOutcome.STORED);
        check(Arrays.equals(reader.read(key, NONE).join().bytes(), bytes));
        check(Arrays.equals(reader.readRange(key, 1, 2, NONE).join().bytes(), new byte[] {2, 3}));
        for (int type = 1; type <= 6; type++) {
            check(
                    "OBJECT_KEY_TYPE_UNSUPPORTED"
                            .equals(
                                    writer.put(
                                                    List.of(
                                                            new PutObject(
                                                                    new ObjectKey(
                                                                            type, new byte[16]),
                                                                    bytes)),
                                                    true,
                                                    NONE)
                                            .join()
                                            .getFirst()
                                            .code()));
        }
        check(
                "OBJECT_NAMESPACE_UNSUPPORTED"
                        .equals(
                                writer.put(
                                                List.of(
                                                        new PutObject(
                                                                new ObjectKey(7, 1, new byte[16]),
                                                                bytes)),
                                                true,
                                                NONE)
                                        .join()
                                        .getFirst()
                                        .code()));
        check(
                "OBJECT_TOO_LARGE"
                        .equals(
                                writer.put(
                                                List.of(
                                                        new PutObject(
                                                                key,
                                                                Format.WHOLE_LIMIT + 1L,
                                                                bytes,
                                                                Origin.CDN)),
                                                true,
                                                NONE)
                                        .join()
                                        .getFirst()
                                        .code()));
        check(
                "OBJECT_SIZE_MISMATCH"
                        .equals(
                                writer.put(
                                                List.of(new PutObject(key, 2, bytes, Origin.CDN)),
                                                true,
                                                NONE)
                                        .join()
                                        .getFirst()
                                        .code()));
        var observation = new History.Observation(1, "wow", "us", Instant.EPOCH, "", bytes);
        check(
                history.appendHistory(observation, NONE).join().stream()
                        .allMatch(r -> "OBJECT_KEY_TYPE_UNSUPPORTED".equals(r.code())));
        check(history.readHistory(new History.Query(), NONE).join().isEmpty());
        expect(
                IllegalArgumentException.class,
                writer.stage(EncodedFixture.sample().key(), 100, Origin.CDN, NONE));
        check(
                writer.delete(List.of(key), NONE).join().getFirst().outcome()
                        == DeleteOutcome.DELETED);
    }

    public static void staging(ObjectReader reader, ObjectWriter writer) {
        EncodedFixture fixture = EncodedFixture.sample();
        byte[] bytes = fixture.bytes();
        ObjectStaging staged =
                writer.stage(fixture.key(), bytes.length, Origin.SHARED_STORE, NONE).join();
        check(
                staged.key().equals(fixture.key())
                        && staged.declaredLength() == bytes.length
                        && staged.length() == 0);
        expect(IllegalArgumentException.class, staged.append(new byte[bytes.length + 1], NONE));
        check(staged.length() == 0);
        expect(
                IllegalStateException.class,
                writer.stage(fixture.key(), bytes.length, Origin.CDN, NONE));
        for (int i = 0; i < fixture.firstChunkEnd() + 2; i++) {
            staged.append(new byte[] {bytes[i]}, NONE).join();
        }
        check(!reader.has(fixture.key(), NONE).join());
        staged.closeAsync().join();
        ObjectStaging resumed =
                writer.stage(fixture.key(), bytes.length, Origin.SHARED_STORE, NONE).join();
        check(resumed.length() == fixture.firstChunkEnd());
        staged.abandon().join(); // a stale handle must not delete its replacement
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        expect(CancellationException.class, resumed.append(new byte[] {1}, canceled));
        check(resumed.length() == fixture.firstChunkEnd());
        expect(IllegalStateException.class, resumed.commit(NONE));
        resumed.append(Arrays.copyOfRange(bytes, fixture.firstChunkEnd(), bytes.length), NONE)
                .join();
        check(resumed.commit(NONE).join().outcome() == PutOutcome.STORED);
        ObjectRead read = reader.read(fixture.key(), NONE).join();
        check(Arrays.equals(bytes, read.bytes()) && read.info().origin() == Origin.SHARED_STORE);
        failure(resumed.append(new byte[0], NONE), "OBJECT_STORE_CLOSED");
        ObjectStaging again = writer.stage(fixture.key(), bytes.length, Origin.CDN, NONE).join();
        again.append(bytes, NONE).join();
        check(again.commit(NONE).join().outcome() == PutOutcome.ALREADY_PRESENT);
        ObjectStaging abandoned =
                writer.stage(fixture.key(), bytes.length, Origin.CDN, NONE).join();
        abandoned.append(Arrays.copyOf(bytes, fixture.firstChunkEnd()), NONE).join();
        abandoned.abandon().join();
        ObjectStaging fresh = writer.stage(fixture.key(), bytes.length, Origin.CDN, NONE).join();
        check(fresh.length() == 0);
        fresh.abandon().join();
    }

    public static void failedStaging(ObjectReader reader, ObjectWriter writer) {
        EncodedFixture f = EncodedFixture.sample();
        byte[] bad = f.bytes().clone();
        bad[bad.length - 1] ^= 1;
        ObjectStaging staged = writer.stage(f.key(), bad.length, Origin.CDN, NONE).join();
        failure(staged.append(bad, NONE), "OBJECT_STAGING_REJECTED");
        check(staged.length() == f.firstChunkEnd());
        failure(staged.append(new byte[] {1}, NONE), "OBJECT_STAGING_REJECTED");
        staged.closeAsync().join();
        ObjectStaging resumed = writer.stage(f.key(), bad.length, Origin.CDN, NONE).join();
        check(resumed.length() == f.firstChunkEnd());
        resumed.append(Arrays.copyOfRange(f.bytes(), f.firstChunkEnd(), f.bytes().length), NONE)
                .join();
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        check(resumed.commit(canceled).join().outcome() == PutOutcome.CANCELED);
        check(!reader.has(f.key(), NONE).join());
        resumed = writer.stage(f.key(), bad.length, Origin.CDN, NONE).join();
        check(resumed.length() == f.bytes().length);
        check(resumed.commit(NONE).join().outcome() == PutOutcome.STORED);
    }

    public static void stagingBudget(ObjectWriter writer) {
        List<ObjectStaging> handles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            byte[] key = new byte[16];
            key[0] = (byte) i;
            handles.add(writer.stage(new ObjectKey(4, key), 1, Origin.CDN, NONE).join());
        }
        byte[] extra = new byte[16];
        extra[0] = 17;
        failure(writer.stage(new ObjectKey(4, extra), 1, Origin.CDN, NONE), "OBJECT_STORE_LIMIT");
        for (ObjectStaging stage : handles) {
            stage.abandon().join();
        }
        ObjectStaging free = writer.stage(new ObjectKey(4, extra), 1, Origin.CDN, NONE).join();
        free.abandon().join();
    }

    public static void drainOnClose(
            ObjectReader reader,
            ObjectWriter writer,
            java.util.function.Supplier<CompletableFuture<Void>> close) {
        List<CompletableFuture<List<PutResult>>> pending = new ArrayList<>();
        ObjectKey key = key(new byte[] {1, 2, 3});
        for (int i = 0; i < 20; i++) {
            pending.add(writer.put(List.of(new PutObject(key, new byte[] {1, 2, 3})), true, NONE));
        }
        close.get().join();
        check(pending.getFirst().join().getFirst().outcome() == PutOutcome.STORED);
        for (int i = 1; i < pending.size(); i++) {
            check(pending.get(i).join().getFirst().outcome() == PutOutcome.ALREADY_PRESENT);
        }
        failure(reader.has(key, NONE), "OBJECT_STORE_CLOSED");
    }

    public static void closed(ObjectReader reader, ObjectWriter writer, HistoryReader history) {
        ObjectKey key = key(new byte[] {1});
        failure(reader.has(key, NONE), "OBJECT_STORE_CLOSED");
        failure(reader.read(key, NONE), "OBJECT_STORE_CLOSED");
        failure(
                writer.put(List.of(new PutObject(key, new byte[] {1})), true, NONE),
                "OBJECT_STORE_CLOSED");
        failure(writer.delete(List.of(key), NONE), "OBJECT_STORE_CLOSED");
        failure(history.readHistory(new History.Query(), NONE), "OBJECT_STORE_CLOSED");
        failure(
                writer.stage(EncodedFixture.sample().key(), 100, Origin.CDN, NONE),
                "OBJECT_STORE_CLOSED");
    }

    public static void verifier(ObjectVerifier verifier, Kind kind) {
        byte[] bytes = {1, 2, 3};
        ObjectKey key = kind == Kind.CDN ? key(bytes) : new ObjectKey(7, new byte[16]);
        check(verifier.check(key, bytes, bytes.length) == null);
        ObjectCheck incremental = verifier.begin(key, bytes.length);
        check(incremental.append(new byte[] {1}) == null);
        check(incremental.append(new byte[] {2, 3}) == null);
        check(incremental.complete() == null);
        ObjectCheck refused = verifier.begin(new ObjectKey(255, 1, new byte[16]), 0);
        check("OBJECT_KEY_TYPE_UNSUPPORTED".equals(refused.append(bytes).code()));
        check("OBJECT_KEY_TYPE_UNSUPPORTED".equals(refused.complete().code()));
        check(
                "OBJECT_NAMESPACE_UNSUPPORTED"
                        .equals(
                                verifier.check(new ObjectKey(key.type(), 1, key.bytes()), bytes, 0)
                                        .code()));
        check("OBJECT_TOO_LARGE".equals(verifier.check(key, bytes, 0).code()));
        check("OBJECT_SIZE_MISMATCH".equals(verifier.check(key, bytes, 2).code()));
    }

    private static void expect(Class<? extends Throwable> type, CompletableFuture<?> future) {
        try {
            future.join();
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (CompletionException e) {
            if (!type.isInstance(e.getCause())) {
                throw e;
            }
        } catch (CancellationException e) {
            if (!type.isInstance(e)) {
                throw e;
            }
        }
    }
}
