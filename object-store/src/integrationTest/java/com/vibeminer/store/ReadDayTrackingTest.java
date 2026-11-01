package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class ReadDayTrackingTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final byte[] bytes = {1, 2, 3, 4};
    private final MutableClock clock = new MutableClock();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final CountDownLatch release = new CountDownLatch(1);

    private static final class MutableClock extends Clock {
        final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.ofEpochSecond(20_000L * 86_400 + 123));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static final class ManualScheduler implements ReadDays.Scheduler {
        private final List<Runnable> actions = new ArrayList<>();
        private final List<AtomicBoolean> canceled = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();

        @Override
        public synchronized ReadDays.Wakeup schedule(Runnable action, long delay, TimeUnit unit) {
            delays.add(unit.toSeconds(delay));
            AtomicBoolean cancellation = new AtomicBoolean();
            actions.add(action);
            canceled.add(cancellation);
            return () -> cancellation.set(true);
        }

        synchronized int size() {
            return actions.size();
        }

        synchronized long delaySeconds(int index) {
            return delays.get(index);
        }

        synchronized boolean canceled(int index) {
            return canceled.get(index).get();
        }

        void fire(int index) {
            Runnable action;
            synchronized (this) {
                if (canceled.get(index).get()) {
                    return;
                }
                action = actions.get(index);
            }
            action.run();
        }
    }

    @BeforeEach
    void open() {
        store = ObjectStore.open(folder, new Options(StoreTypes.Kind.CDN, 2L << 30, clock)).join();
        store.readDayScheduler = scheduler;
        store.put(List.of(new PutObject(key(bytes), bytes))).join();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        store.database.statementProbe = null;
        store.database.afterStatementProbe = null;
        store.readProbe = null;
        store.closeAsync().get(5, TimeUnit.SECONDS);
    }

    private Long day() {
        return store.reading(
                        Cancellation.NONE,
                        index ->
                                (Long)
                                        index.db.query(
                                                        "SELECT last_read FROM objects WHERE k=?",
                                                        key(bytes).indexBytes())
                                                .getFirst()[0])
                .join();
    }

    private void flush() {
        store.write(() -> null).join();
    }

    @Test
    void successfulWholeAndRangeReadsCoalesceAndPersistOnWriter() {
        assertNull(day());
        for (int i = 0; i < 10; i++) {
            store.read(key(bytes)).join();
        }
        store.readRange(key(bytes), 1, 2, Cancellation.NONE).join();
        assertEquals(1, store.readDays.size());
        assertEquals(1, scheduler.size());
        assertNull(day());
        flush();
        assertEquals(20_000L, day());
        assertTrue(store.readDays.isEmpty());
        clock.now.set(Instant.ofEpochSecond(20_001L * 86_400));
        store.readRange(key(bytes), 0, 1, Cancellation.NONE).join();
        flush();
        assertEquals(20_001L, day());
        clock.now.set(Instant.ofEpochSecond(19_999L * 86_400));
        store.read(key(bytes)).join();
        flush();
        assertEquals(20_001L, day());
    }

    @Test
    void idleTimerEnqueuesWriterWorkInsteadOfDoingIoOnReaderOrTimer() throws Exception {
        Thread caller = Thread.currentThread();
        AtomicBoolean updated = new AtomicBoolean();
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("UPDATE objects SET last_read=")) {
                        assertFalse(Thread.currentThread() == caller);
                        updated.set(true);
                    }
                };
        store.read(key(bytes)).join();
        assertFalse(updated.get());
        assertEquals(30L, scheduler.delaySeconds(0));
        scheduler.fire(0);
        flush();
        assertTrue(updated.get());
        assertEquals(20_000L, day());
    }

    @Test
    void readsCompleteWhileTheWriterIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.write(
                () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(ReadState.PRESENT, store.read(key(bytes)).get(5, TimeUnit.SECONDS).state());
        assertNull(day());
        release.countDown();
        flush();
        assertEquals(20_000L, day());
    }

    @Test
    void hasEmptyRangesAndVerificationDoNotCountAsPayloadReads() {
        assertTrue(store.has(key(bytes)).join());
        store.readRange(key(bytes), 0, 0, Cancellation.NONE).join();
        store.verify(true, Cancellation.NONE).join();
        assertTrue(store.readDays.isEmpty());
        assertEquals(0, scheduler.size());
        assertNull(day());
    }

    @Test
    void absentAndCorruptReadsDoNotEnqueueTracking() throws Exception {
        assertEquals(ReadState.ABSENT, store.read(key(new byte[] {9})).join().state());
        try (var file =
                java.nio.channels.FileChannel.open(
                        store.packPath(1), java.nio.file.StandardOpenOption.WRITE)) {
            Format.write(file, 64 + 96, new byte[] {9});
        }
        assertEquals(ReadState.CORRUPT, store.read(key(bytes)).join().state());
        assertTrue(store.readDays.isEmpty());
        assertNull(day());
    }

    @Test
    void scanPreservesFlushedAndQueuedReadDays() {
        store.read(key(bytes)).join();
        store.scan(Cancellation.NONE).join();
        assertEquals(20_000L, day());
        clock.now.set(Instant.ofEpochSecond(20_001L * 86_400));
        store.scan(Cancellation.NONE).join();
        assertEquals(20_000L, day());
    }

    @Test
    void closeFlushesAcceptedReadsAndCancelsIdleWakeup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.PACK_OPENED) {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                    }
                };
        var reading = store.read(key(bytes));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var closed = store.closeAsync();
        release.countDown();
        reading.get(5, TimeUnit.SECONDS);
        closed.get(5, TimeUnit.SECONDS);
        store = ObjectStore.open(folder, new Options(StoreTypes.Kind.CDN, 2L << 30, clock)).join();
        store.readDayScheduler = scheduler;
        assertEquals(20_000L, day());
    }

    @Test
    void closeCancelsScheduledWakeupAndReadOnlyStoreNeverTracks() {
        store.read(key(bytes)).join();
        store.closeAsync().join();
        assertTrue(scheduler.canceled(0));
        scheduler.fire(0);
        clock.now.set(Instant.ofEpochSecond(20_001L * 86_400));
        ReadOnlyStore reader =
                ObjectStore.openReadOnly(folder, new Options(StoreTypes.Kind.CDN, 2L << 30, clock))
                        .join();
        try {
            // Read-only access does not own the writer lock.
            store =
                    ObjectStore.open(folder, new Options(StoreTypes.Kind.CDN, 2L << 30, clock))
                            .join();
            store.readDayScheduler = scheduler;
            reader.read(key(bytes), Cancellation.NONE).join();
            reader.readRange(key(bytes), 0, 1, Cancellation.NONE).join();
        } finally {
            reader.closeAsync().join();
        }
        assertEquals(1, scheduler.size());
        assertEquals(20_000L, day());
    }

    @Test
    void failedReadDaySqlRollsBackWithoutFailingTheReadOrStoppingWriter() {
        store.read(key(bytes)).join();
        AtomicBoolean once = new AtomicBoolean(true);
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("UPDATE objects SET last_read=") && once.getAndSet(false)) {
                        throw new NativeSqlite.Failure(13, "Injected read-day full");
                    }
                };
        flush();
        store.database.statementProbe = null;
        assertNull(day());
        byte[] next = {9, 8, 7};
        store.put(List.of(new PutObject(key(next), next))).join();
        store.read(key(bytes)).join();
        flush();
        assertEquals(20_000L, day());
    }

    @Test
    void negativeEpochDaysUseFloorDivision() {
        clock.now.set(Instant.ofEpochSecond(-1));
        store.read(key(bytes)).join();
        flush();
        assertEquals(-1L, day());
    }

    @Test
    void fullWriterAdmissionDefersIdleFlushWithoutFailingReaders() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        store.write(
                () -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return null;
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        List<CompletableFuture<Void>> accepted = new ArrayList<>();
        for (int i = 0; i < WriteQueue.QUEUED_REQUESTS + WriteQueue.WAITING_REQUESTS; i++) {
            accepted.add(store.write(() -> null));
        }
        assertEquals(ReadState.PRESENT, store.read(key(bytes)).get(5, TimeUnit.SECONDS).state());
        scheduler.fire(0);
        assertEquals(2, scheduler.size());
        assertEquals(1, store.readDays.size());
        release.countDown();
        CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new))
                .get(5, TimeUnit.SECONDS);
        flush();
        assertEquals(20_000L, day());
    }

    @Test
    void closeDropsFailedBestEffortMetadataAndStillMarksClean() {
        store.read(key(bytes)).join();
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("UPDATE objects SET last_read=")) {
                        throw new NativeSqlite.Failure(13, "Injected best-effort close failure");
                    }
                };
        store.closeAsync().join();
        store = ObjectStore.open(folder, new Options(StoreTypes.Kind.CDN, 2L << 30, clock)).join();
        store.readDayScheduler = scheduler;
        assertFalse(store.openReport().fullIndexCheck());
        assertNull(day());
        assertEquals(ReadState.PRESENT, store.read(key(bytes)).join().state());
    }

    @Test
    void readDaysJoinTheNextPutCommitWithoutAnotherTransaction() {
        store.read(key(bytes)).join();
        AtomicInteger commits = new AtomicInteger();
        store.database.afterStatementProbe =
                sql -> {
                    if (sql.equals("COMMIT")) {
                        commits.incrementAndGet();
                    }
                };
        byte[] next = {7, 8, 9};
        store.put(List.of(new PutObject(key(next), next))).join();
        assertEquals(1, commits.get());
        assertEquals(20_000L, day());
    }

    @Test
    void aLargeFlushUsesBoundedSqlChunksAndUpdatesEveryStoredKey() {
        List<PutObject> objects = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            byte[] content = new byte[4];
            Format.le(content).putInt(i);
            objects.add(new PutObject(key(content), content));
        }
        store.put(objects).join();
        for (PutObject object : objects) {
            store.read(object.key()).join();
        }
        AtomicInteger updates = new AtomicInteger();
        store.database.statementProbe =
                sql -> {
                    if (sql.startsWith("UPDATE objects SET last_read=")) {
                        updates.incrementAndGet();
                    }
                };
        flush();
        assertEquals(2, updates.get());
        assertEquals(
                300L,
                store.reading(
                                Cancellation.NONE,
                                index ->
                                        index.db.scalar(
                                                "SELECT count(*) FROM objects WHERE last_read=20000"))
                        .join());
    }
}
