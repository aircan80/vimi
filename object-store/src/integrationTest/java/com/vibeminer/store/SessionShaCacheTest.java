package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class SessionShaCacheTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final byte[] bytes = {1, 2, 3, 4};
    private final AtomicInteger checks = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void open() {
        store = ObjectStore.open(folder).join();
        store.put(List.of(new PutObject(key(bytes), bytes))).join();
        store.wholeShaProbe = ignored -> checks.incrementAndGet();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        store.wholeShaProbe = null;
        store.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private void rewritePayload(byte[] payload, boolean updateCrc) throws Exception {
        StoreIndex.Row row = store.index.find(key(bytes));
        try (FileChannel file =
                FileChannel.open(store.packPath(row.pack()), StandardOpenOption.WRITE)) {
            Format.write(file, row.offset() + 96, payload);
            if (updateCrc) {
                Format.write(
                        file,
                        row.offset() + 96 + payload.length,
                        Format.le(new byte[4]).putInt((int) Format.crc(payload)).array());
            }
            file.force(true);
        }
    }

    @Test
    void repeatedWholeReadsReuseShaButRangesDoNotPopulateTheSet() {
        store.readRange(key(bytes), 0, bytes.length, Cancellation.NONE).join();
        assertEquals(0, store.sessionHashes.size());
        assertEquals(0, checks.get());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(bytes, store.read(key(bytes)).join().bytes());
        }
        assertEquals(1, checks.get());
        assertEquals(1, store.sessionHashes.size());
    }

    @Test
    void cacheHitsStillCheckCrcAndCorruptionDropsOldProofs() throws Exception {
        store.read(key(bytes)).join();
        rewritePayload(new byte[] {9, 2, 3, 4}, false);
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertEquals(0, store.sessionHashes.size());
        rewritePayload(bytes, true);
        assertEquals(ReadState.PRESENT, store.read(key(bytes)).join().state());
        assertEquals(2, checks.get());
    }

    @Test
    void failedShaNeverBecomesAProofEvenIfBlockCrcIsValid() throws Exception {
        rewritePayload(new byte[] {9, 2, 3, 4}, true);
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertEquals(0, store.sessionHashes.size());
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertEquals(2, checks.get());
    }

    @Test
    void fullVerificationBypassesAWholeReadCacheHit() throws Exception {
        store.read(key(bytes)).join();
        rewritePayload(new byte[] {9, 2, 3, 4}, true);
        assertEquals(1, store.verify(true, Cancellation.NONE).join().corruptCount());
        assertEquals(0, store.sessionHashes.size());
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
    }

    @Test
    void scanRequiresNewProofsAndCannotAcceptAnOldInFlightPublication() throws Exception {
        CountDownLatch hashing = new CountDownLatch(1);
        store.wholeShaProbe =
                ignored -> {
                    hashing.countDown();
                    try {
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        throw new AssertionError(error);
                    }
                };
        var reading = store.read(key(bytes));
        assertTrue(hashing.await(10, TimeUnit.SECONDS));
        store.scan(Cancellation.NONE).get(10, TimeUnit.SECONDS);
        release.countDown();
        assertEquals(ReadState.PRESENT, reading.get(10, TimeUnit.SECONDS).state());
        assertEquals(0, store.sessionHashes.size());
        store.wholeShaProbe = ignored -> checks.incrementAndGet();
        store.read(key(bytes)).join();
        assertEquals(1, checks.get());
    }

    @Test
    void reopenedChannelCannotInheritProofForReplacedFile() throws Exception {
        store.read(key(bytes)).join();
        StoreIndex.Row row = store.index.find(key(bytes));
        Path source = store.packPath(row.pack());
        Path replacement = folder.resolve("replacement.vmp");
        java.nio.file.Files.copy(source, replacement);
        try (FileChannel file = FileChannel.open(replacement, StandardOpenOption.WRITE)) {
            byte[] wrong = {9, 2, 3, 4};
            Format.write(file, row.offset() + 96, wrong);
            Format.write(
                    file,
                    row.offset() + 96 + wrong.length,
                    Format.le(new byte[4]).putInt((int) Format.crc(wrong)).array());
        }
        store.packReaders.invalidate(row.pack());
        java.nio.file.Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertEquals(2, checks.get());
    }

    @Test
    void changedExpectedShaCannotReuseAnOldProof() throws Exception {
        store.read(key(bytes)).join();
        StoreIndex.Row row = store.index.find(key(bytes));
        byte[] wrongSha = new byte[32];
        try (FileChannel file =
                FileChannel.open(
                        store.packPath(row.pack()),
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {
            Format.Entry original =
                    Format.entry(Format.read(file, row.offset(), 96), StoreTypes.Kind.CDN);
            Format.write(
                    file,
                    row.offset(),
                    new Format.Entry(
                                    original.key(),
                                    original.length(),
                                    wrongSha,
                                    original.time(),
                                    original.origin(),
                                    original.sequence(),
                                    false)
                            .header());
        }
        store.write(
                        () -> {
                            store.database.exec(
                                    "UPDATE objects SET sha256=? WHERE k=?",
                                    wrongSha,
                                    key(bytes).indexBytes());
                            return null;
                        })
                .join();
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertEquals(2, checks.get());
    }

    @Test
    void movedCompactionEntriesEarnTheirOwnProof() throws Exception {
        store.closeAsync().join();
        CompactionFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
        store.wholeShaProbe = ignored -> checks.incrementAndGet();
        var key = key(CompactionFixture.kept());
        store.read(key).join();
        assertEquals(1, checks.get());
        store.compact(0, 1, Cancellation.NONE).join();
        store.read(key).join();
        assertEquals(2, checks.get());
    }

    @Test
    void eligibilityIncludesExactlyOneMiBAndExcludesLargerWholeReads() {
        byte[] small = new byte[1 << 20];
        byte[] large = new byte[(1 << 20) + 1];
        store.put(List.of(new PutObject(key(small), small), new PutObject(key(large), large)))
                .join();
        store.read(key(small)).join();
        store.read(key(small)).join();
        store.read(key(large)).join();
        store.read(key(large)).join();
        assertEquals(1, checks.get());
        assertEquals(1, store.sessionHashes.size());
    }

    @Test
    void concurrentReadsReuseCompletedProofAndReopenStartsAnotherSession() throws Exception {
        store.read(key(bytes)).join();
        List<CompletableFuture<?>> reads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            reads.add(store.read(key(bytes)));
        }
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertEquals(1, checks.get());
        store.closeAsync().join();
        store = ObjectStore.open(folder).join();
        store.wholeShaProbe = ignored -> checks.incrementAndGet();
        store.read(key(bytes)).join();
        assertEquals(2, checks.get());
    }
}
