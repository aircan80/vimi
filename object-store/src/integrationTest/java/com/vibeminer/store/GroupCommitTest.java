package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.failure;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.PutOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
final class GroupCommitTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final AtomicInteger commits = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    private PutObject object(int number) {
        byte[] bytes = ("group-" + number).getBytes(StandardCharsets.US_ASCII);
        return new PutObject(key(bytes), bytes);
    }

    @BeforeEach
    void open() {
        store = ObjectStore.open(folder).join();
        store.put(List.of(object(-1))).join();
        store.database.afterStatementProbe =
                sql -> {
                    if (sql.equals("COMMIT")) {
                        commits.incrementAndGet();
                    }
                };
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        store.writeProbe = null;
        store.database.statementProbe = null;
        store.database.afterStatementProbe = null;
        store.io.fault = null;
        store.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private void pause() {
        try {
            assertTrue(release.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private void blockWriter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.write(
                () -> {
                    entered.countDown();
                    pause();
                    return null;
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void queuedRequestsShareOneCommitAndRemainInvisibleUntilItCompletes() throws Exception {
        blockWriter();
        CountDownLatch secondWritten = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        store.writeProbe =
                (point, key) -> {
                    if (point == WritePoint.HEADER_WRITTEN && key.equals(object(2).key())) {
                        secondWritten.countDown();
                        try {
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                    }
                };
        var first = store.put(List.of(object(1)));
        var second = store.put(List.of(object(2)));
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        try {
            assertTrue(secondWritten.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertFalse(store.has(object(1).key()).get(5, TimeUnit.SECONDS));
            assertEquals(0, commits.get());
        } finally {
            finish.countDown();
        }
        assertEquals(PutOutcome.STORED, first.get(5, TimeUnit.SECONDS).getFirst().outcome());
        second.get(5, TimeUnit.SECONDS);
        assertEquals(1, commits.get());
        assertTrue(store.has(object(1).key()).join());
    }

    @Test
    void objectCountClosesGroupInsideOneLargeRequest() {
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            objects.add(object(i));
        }
        var result = store.put(objects).join();
        assertEquals(129, result.size());
        assertEquals(2, commits.get());
        assertEquals(130, store.stats().join().objects());
    }

    @Test
    void entryBytesCloseGroupBeforeNextSmallObject() {
        byte[] first = new byte[8 << 20], second = new byte[8 << 20];
        second[0] = 1;
        store.put(
                        List.of(
                                new PutObject(key(first), first),
                                new PutObject(key(second), second),
                                object(2)))
                .join();
        assertEquals(2, commits.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitNowAndElapsedDeadlineFlushDespiteQueuedWork(boolean commitNow) throws Exception {
        blockWriter();
        store.commitClock =
                new java.util.function.LongSupplier() {
                    int calls;

                    @Override
                    public long getAsLong() {
                        return commitNow || calls++ == 0 ? 0 : PendingWrites.MAX_NANOS;
                    }
                };
        var first = store.put(List.of(object(1)), commitNow, Cancellation.NONE);
        var second = store.put(List.of(object(2)));
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertEquals(2, commits.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void batchFailureFailsEveryPendingOwnerAndCutsTheWholeTail(boolean sqlFailure)
            throws Exception {
        blockWriter();
        AtomicBoolean once = new AtomicBoolean(true);
        if (sqlFailure) {
            store.database.statementProbe =
                    sql -> {
                        if (sql.startsWith("INSERT INTO objects") && once.getAndSet(false)) {
                            throw new NativeSqlite.Failure(10, "Injected group failure");
                        }
                    };
        } else {
            store.io.fault =
                    (point, path) -> {
                        if (point == StoreIo.Point.PACK_SYNC && once.getAndSet(false)) {
                            throw new IOException("Injected group sync failure");
                        }
                    };
        }
        var first = store.put(List.of(object(1)));
        var second = store.put(List.of(object(2)));
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        failure(first, "OBJECT_STORE_WRITE_FAILED");
        failure(second, "OBJECT_STORE_WRITE_FAILED");
        store.database.statementProbe = null;
        store.io.fault = null;
        assertFalse(store.has(object(1).key()).join());
        assertFalse(store.has(object(2).key()).join());
        assertEquals(1, store.stats().join().objects());
        assertEquals(PutOutcome.STORED, store.put(List.of(object(1))).join().getFirst().outcome());
        assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
    }

    @Test
    void cancellationAfterAppendCommitsStoredObjectsAndCancelsRemainingObjects() {
        Cancellation token = new Cancellation();
        store.writeProbe =
                (point, key) -> {
                    if (point == WritePoint.HEADER_WRITTEN) {
                        token.cancel();
                    }
                };
        var result = store.put(List.of(object(1), object(2)), false, token).join();
        assertEquals(PutOutcome.STORED, result.get(0).outcome());
        assertEquals(PutOutcome.CANCELED, result.get(1).outcome());
        assertTrue(store.has(object(1).key()).join());
        assertFalse(store.has(object(2).key()).join());
    }

    @Test
    void deleteFlushesPendingPutsThenCommitsTombstonesTogether() throws Exception {
        blockWriter();
        var put = store.put(List.of(object(1), object(2)));
        var delete = store.delete(List.of(object(1).key(), object(2).key()));
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        put.get(5, TimeUnit.SECONDS);
        delete.get(5, TimeUnit.SECONDS);
        assertEquals(2, commits.get());
        assertFalse(store.has(object(1).key()).join());
        assertFalse(store.has(object(2).key()).join());
    }

    @Test
    void closeDrainsAndCommitsAcceptedPendingRequests() throws Exception {
        blockWriter();
        var put = store.put(List.of(object(1), object(2)));
        var closed = store.closeAsync();
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        closed.get(5, TimeUnit.SECONDS);
        assertEquals(PutOutcome.STORED, put.get(5, TimeUnit.SECONDS).getFirst().outcome());
        store = ObjectStore.open(folder).join();
        assertTrue(store.has(object(1).key()).join());
    }

    @Test
    void historyDocumentAndRecordShareACommitAndResolvePendingDocument() {
        store.appendHistory(CrashFixture.observation(false), Cancellation.NONE).join();
        assertEquals(1, commits.get());
        assertEquals(1, store.readHistory(new History.Query(), Cancellation.NONE).join().size());
        assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
    }

    @Test
    void packRollCommitsOldTailBeforeFullMetadataTransition() {
        store.closeAsync().join();
        store =
                ObjectStore.open(
                                folder,
                                new StoreTypes.Options(
                                        StoreTypes.Kind.CDN, 300, java.time.Clock.systemUTC()))
                        .join();
        List<Long> modes = new ArrayList<>();
        store.database.statementProbe =
                sql -> {
                    if (sql.equals("BEGIN IMMEDIATE")) {
                        modes.add(store.database.scalar("PRAGMA synchronous"));
                    }
                };
        store.put(List.of(object(1), object(2), object(3))).join();
        assertEquals(List.of(1L, 2L, 1L), modes);
        assertEquals(4, store.stats().join().objects());
        assertEquals(0, store.verify(true, Cancellation.NONE).join().corruptCount());
    }

    @Test
    void maintenanceRunsOnlyAfterPendingObjectsAreCommitted() throws Exception {
        blockWriter();
        AtomicBoolean observed = new AtomicBoolean();
        store.maintenanceProbe =
                point -> {
                    if (point == MaintenancePoint.SCAN_SEALED) {
                        assertTrue(store.has(object(1).key()).join());
                        observed.set(true);
                    }
                };
        var put = store.put(List.of(object(1)));
        var scan = store.scan(Cancellation.NONE);
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        put.get(5, TimeUnit.SECONDS);
        scan.get(5, TimeUnit.SECONDS);
        store.maintenanceProbe = null;
        assertTrue(observed.get());
    }

    @Test
    void laterPayloadFailureRollsBackEarlierPendingOwners() throws Exception {
        blockWriter();
        AtomicBoolean secondStarted = new AtomicBoolean();
        AtomicBoolean once = new AtomicBoolean(true);
        store.writeProbe =
                (point, key) -> {
                    if (point == WritePoint.ENTRY_STARTED && key.equals(object(2).key())) {
                        secondStarted.set(true);
                    }
                };
        store.io.fault =
                (point, path) -> {
                    if (point == StoreIo.Point.PACK_WRITE
                            && secondStarted.get()
                            && once.getAndSet(false)) {
                        throw new IOException("Injected later payload failure");
                    }
                };
        var first = store.put(List.of(object(1)));
        var second = store.put(List.of(object(2)));
        store.writer.preparations().get(5, TimeUnit.SECONDS);
        release.countDown();
        failure(first, "OBJECT_STORE_WRITE_FAILED");
        failure(second, "OBJECT_STORE_WRITE_FAILED");
        assertEquals(1, store.stats().join().objects());
        assertFalse(store.has(object(1).key()).join());
        assertFalse(store.has(object(2).key()).join());
    }

    @Test
    void duplicatePendingKeysAreNotAppendedTwice() {
        var result = store.put(List.of(object(1), object(1))).join();
        assertEquals(PutOutcome.STORED, result.getFirst().outcome());
        assertEquals(PutOutcome.ALREADY_PRESENT, result.getLast().outcome());
        assertEquals(1, commits.get());
        assertEquals(2, store.stats().join().objects());
    }
}
