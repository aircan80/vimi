package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.check;
import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.Origin;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiskStoreTest {
    @Test
    void satisfiesReaderWriterAndHistoryContracts(@TempDir Path path) {
        ObjectStore store = ObjectStore.open(path).join();
        try {
            StoreContract.verify(store);
        } finally {
            store.closeAsync().join();
        }
    }

    @Test
    void supportsDiskOperationsAndDetectsCorruption(@TempDir Path path) throws Exception {
        Options options = new Options(Kind.CDN, 300, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ObjectStore s = ObjectStore.open(path, options).join();
        byte[] a = new byte[100];
        Arrays.fill(a, (byte) 1);
        byte[] b = new byte[100];
        Arrays.fill(b, (byte) 2);
        check(
                s.put(List.of(new PutObject(key(a), a)), true, NONE).join().getFirst().outcome()
                        == PutOutcome.STORED);
        check(Arrays.equals(s.read(key(a), NONE).join().bytes(), a));
        check(s.readRange(key(a), 5, 10, NONE).join().bytes().length == 10);
        failure(ObjectStore.open(path), "OBJECT_STORE_BUSY");
        ReadOnlyStore reader = ObjectStore.openReadOnly(path, options).join();
        check(reader.has(key(a), NONE).join());
        Cancellation canceled = new Cancellation();
        canceled.cancel();
        check(
                s.put(List.of(new PutObject(key(b), b)), true, canceled).join().getFirst().outcome()
                        == PutOutcome.CANCELED);
        List<CompletableFuture<?>> reads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            reads.add(reader.read(key(a), NONE));
        }
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).join();
        s.put(List.of(new PutObject(key(b), b)), true, NONE).join();
        check(reader.has(key(b), NONE).join());
        reader.closeAsync().join();
        s.delete(List.of(key(a)), NONE).join();
        check(s.read(key(a), NONE).join().state() == ReadState.ABSENT);
        var observation =
                new History.Observation(
                        1,
                        "wow",
                        "us",
                        Instant.EPOCH,
                        "https://example.test",
                        new byte[] {1, 2, 3});
        s.appendHistory(observation, NONE).join();
        check(s.readHistory(new History.Query(), NONE).join().size() == 1);
        check(s.scan(NONE).join().live() == 3);
        check(s.compact(0, 10, NONE).join().packsCompacted() > 0);
        check(s.scan(NONE).join().live() == 3);
        check(s.verify(true, NONE).join().corruptCount() == 0);
        check(s.scrub(true, NONE).join().indexProblem() == null);
        s.closeAsync().join();
        s = ObjectStore.open(path, options).join();
        check(!s.has(key(a), NONE).join());
        check(s.has(key(b), NONE).join());
        StoreIndex.Row row = s.index.find(key(b));
        try (var file = FileChannel.open(s.packPath(row.pack()), StandardOpenOption.WRITE)) {
            Format.write(file, row.offset() + 96, new byte[] {99});
        }
        check(s.read(key(b), NONE).join().state() == ReadState.CORRUPT);
        check(s.verify(true, NONE).join().corruptCount() == 1);
        check(s.scan(NONE).join().corrupt() == 1);
        s.closeAsync().join();
    }

    @Test
    void resumesAndCommitsStaging(@TempDir Path path) {
        ObjectStore s = ObjectStore.open(path).join();
        byte[] bytes = new byte[] {'B', 'L', 'T', 'E', 0, 0, 0, 0, 'N', 1, 2, 3};
        ObjectKey key = new ObjectKey(4, Format.hash("MD5", bytes));
        ObjectStaging staged = s.stage(key, bytes.length, Origin.CDN, NONE).join();
        staged.append(Arrays.copyOf(bytes, 10), NONE).join();
        staged.closeAsync().join();
        staged = s.stage(key, bytes.length, Origin.CDN, NONE).join();
        check(staged.length() == 0);
        staged.append(bytes, NONE).join();
        check(staged.commit(NONE).join().outcome() == PutOutcome.STORED);
        check(Arrays.equals(s.read(key, NONE).join().bytes(), bytes));
        s.closeAsync().join();
    }

    @Test
    void recoversDurableTailAndRebuildsMissingIndex(@TempDir Path path) throws Exception {
        ObjectStore s = ObjectStore.open(path).join();
        byte[] bytes = new byte[] {9, 8, 7};
        ObjectKey k = key(bytes);
        s.put(List.of(new PutObject(k, bytes)), true, NONE).join();
        s.closeAsync().join();
        Path pack = path.resolve("packs/pack-000001.vmp");
        byte[] more = new byte[] {5, 6, 7};
        ObjectKey second = key(more);
        try (FileChannel file = FileChannel.open(pack, StandardOpenOption.WRITE)) {
            long at = file.size();
            var e =
                    new Format.Entry(
                            second,
                            more.length,
                            Format.hash("SHA-256", more),
                            0,
                            Origin.CDN,
                            2,
                            false);
            Format.write(file, at, e.header());
            Format.write(file, at + 96, more);
            Format.write(
                    file, at + 99, Format.le(new byte[4]).putInt((int) Format.crc(more)).array());
            Format.write(file, at + 103, new byte[] {1, 2, 3});
            file.force(true);
        }
        s = ObjectStore.open(path).join();
        check(s.openReport().recoveredEntries() == 1);
        check(s.openReport().truncatedBytes() == 3);
        check(s.has(second, NONE).join());
        s.closeAsync().join();
        Files.delete(path.resolve("objects.sqlite3"));
        s = ObjectStore.open(path).join();
        check(s.openReport().indexRebuilt());
        check(s.has(k, NONE).join());
        check(s.has(second, NONE).join());
        s.closeAsync().join();
    }
}
