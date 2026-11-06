package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class ReadConnectionPoolTest {
    @TempDir Path folder;
    private final byte[] bytes = {1, 2, 3, 4};
    private final AtomicInteger opens = new AtomicInteger();
    private ObjectStore store;

    private ObjectStore open(Options options) {
        store = ObjectStore.open(folder, options).join();
        store.readerOpenProbe = opens::incrementAndGet;
        store.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        return store;
    }

    @AfterEach
    void close() throws Exception {
        if (store != null) {
            store.readProbe = null;
            store.closeAsync().get(10, TimeUnit.SECONDS);
            assertEquals(0, store.readConnections.idle());
        }
    }

    @Test
    void sequentialReadsReuseOneProbedConnection() {
        open(new Options());
        for (int i = 0; i < 20; i++) {
            assertArrayEquals(bytes, store.read(key(bytes), NONE).join().bytes());
            assertTrue(store.has(key(bytes), NONE).join());
            assertArrayEquals(
                    new byte[] {2, 3}, store.readRange(key(bytes), 1, 2, NONE).join().bytes());
        }
        assertEquals(1, opens.get());
        assertEquals(1, store.readConnections.idle());
    }

    @Test
    void defaultReaderCountIsEight() {
        open(new Options());
        assertEquals(Options.DEFAULT_READERS, store.options.readers());
        assertEquals(8, store.readers.getMaximumPoolSize());
    }

    @Test
    void configuredReaderCountBoundsWorkersAndIdleConnections() throws Exception {
        open(new Options(Kind.CDN, 2L << 30, Clock.systemUTC(), 64, 2));
        assertEquals(2, store.readers.getMaximumPoolSize());
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.ROW_FOUND) {
                        entered.countDown();
                        await(release);
                    }
                };
        List<CompletableFuture<StoreTypes.ObjectRead>> reads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reads.add(store.read(key(bytes), NONE));
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        release.countDown();
        for (var read : reads) {
            assertArrayEquals(bytes, read.join().bytes());
        }
        assertTrue(opens.get() <= 2, "opens " + opens.get());
        assertTrue(store.readConnections.idle() <= 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> new Options(Kind.CDN, 2L << 30, Clock.systemUTC(), 64, 0));
    }

    @Test
    void failedOrCanceledReadClosesItsConnection() {
        open(new Options());
        store.read(key(bytes), NONE).join();
        assertEquals(1, store.readConnections.idle());
        store.readProbe =
                (point, key) -> {
                    throw new CancellationException("stop");
                };
        var failed =
                assertThrows(
                        CancellationException.class, () -> store.read(key(bytes), NONE).join());
        assertEquals("stop", failed.getCause().getMessage());
        assertEquals(0, store.readConnections.idle());
        store.readProbe = null;
        var canceled = new Cancellation();
        canceled.cancel();
        assertThrows(CancellationException.class, () -> store.read(key(bytes), canceled).join());
        assertArrayEquals(bytes, store.read(key(bytes), NONE).join().bytes());
        assertEquals(2, opens.get());
    }

    @Test
    void readLeavingATransactionOpenIsNotPooled() {
        open(new Options());
        store.reading(
                        NONE,
                        index -> {
                            index.db.exec("BEGIN");
                            index.db.scalar("SELECT count(*) FROM objects");
                            return null;
                        })
                .join();
        assertEquals(0, store.readConnections.idle());
        store.read(key(bytes), NONE).join();
        assertEquals(2, opens.get());
        assertEquals(1, store.readConnections.idle());
    }

    @Test
    void scanRetiresIdleAndLentConnections() throws Exception {
        open(new Options());
        store.read(key(bytes), NONE).join();
        assertEquals(1, store.readConnections.idle());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.ROW_FOUND) {
                        entered.countDown();
                        await(release);
                    }
                };
        var lent = store.read(key(bytes), NONE);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        store.scan(NONE).join();
        assertEquals(0, store.readConnections.idle());
        release.countDown();
        assertArrayEquals(bytes, lent.join().bytes());
        assertEquals(0, store.readConnections.idle(), "connection lent across a scan was pooled");
        store.readProbe = null;
        assertArrayEquals(bytes, store.read(key(bytes), NONE).join().bytes());
        assertEquals(2, opens.get());
    }

    @Test
    void closeWaitsForReadsInFlightAndClosesEveryConnection() throws Exception {
        open(new Options());
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.ROW_FOUND) {
                        entered.countDown();
                        await(release);
                    }
                };
        List<CompletableFuture<StoreTypes.ObjectRead>> reads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            reads.add(store.read(key(bytes), NONE));
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var closing = store.closeAsync();
        assertFalse(closing.isDone());
        release.countDown();
        for (var read : reads) {
            assertArrayEquals(bytes, read.join().bytes());
        }
        closing.get(10, TimeUnit.SECONDS);
        assertEquals(0, store.readConnections.idle());
        assertThrows(CompletionException.class, () -> store.read(key(bytes), NONE).join());
    }

    @Test
    void idleConnectionsExpireAndAReplacedIndexFileIsNeverReused() throws Exception {
        open(new Options());
        long[] now = {0};
        var pool = new ReadConnections(4, () -> now[0]);
        pool.idleTtlNanos = 100;
        Object first = "inode-1", second = "inode-2";
        var a = new ReadConnections.Pooled(store.openReader(), pool.generation(), first);
        pool.give(a, true);
        now[0] = 50;
        assertSame(a, pool.take(first));
        pool.give(a, true);
        now[0] = 200;
        assertNull(pool.take(first), "an expired connection was reused");
        assertEquals(0, pool.idle());
        var b = new ReadConnections.Pooled(store.openReader(), pool.generation(), first);
        var c = new ReadConnections.Pooled(store.openReader(), pool.generation(), first);
        pool.give(b, true);
        pool.give(c, true);
        assertNull(pool.take(second), "a connection on a replaced index file was reused");
        assertEquals(0, pool.idle());
        var d = new ReadConnections.Pooled(store.openReader(), pool.generation(), null);
        pool.give(d, true);
        assertSame(d, pool.take(null), "no identity reported: not compared");
        pool.give(d, true);
        now[0] = 1000;
        pool.sweep();
        assertEquals(0, pool.idle());
        pool.close();
        var late = new ReadConnections.Pooled(store.openReader(), pool.generation(), null);
        pool.give(late, true);
        assertEquals(0, pool.idle());
    }

    @Test
    void sweeperClosesIdleConnectionsAfterTheirLifetime() throws Exception {
        open(new Options());
        store.readConnections.idleTtlNanos = TimeUnit.MILLISECONDS.toNanos(50);
        store.read(key(bytes), NONE).join();
        assertEquals(1, store.readConnections.idle());
        waitForNoIdle(store);
        store.read(key(bytes), NONE).join();
        assertEquals(2, opens.get());
    }

    @Test
    void pooledConnectionsSeeLaterWritesFromTheWriterAndAReadOnlyView() {
        open(new Options());
        byte[] later = {9, 8, 7};
        store.read(key(bytes), NONE).join();
        ReadOnlyStore view = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            var viewOpens = new AtomicInteger();
            view.inner().readerOpenProbe = viewOpens::incrementAndGet;
            assertArrayEquals(bytes, view.read(key(bytes), NONE).join().bytes());
            assertFalse(view.has(key(later), NONE).join());
            store.put(List.of(new PutObject(key(later), later)), true, NONE).join();
            assertArrayEquals(later, store.read(key(later), NONE).join().bytes());
            assertArrayEquals(later, view.read(key(later), NONE).join().bytes());
            assertEquals(1, opens.get(), "the writer's reads reused one pooled connection");
            assertEquals(1, viewOpens.get(), "the read-only view reused one pooled connection");
        } finally {
            view.closeAsync().join();
        }
    }

    @Test
    void idlePooledConnectionsDoNotBlockAWalTruncateCheckpoint() throws Exception {
        open(new Options());
        ReadOnlyStore view = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            for (int i = 0; i < 5; i++) {
                store.read(key(bytes), NONE).join();
                view.read(key(bytes), NONE).join();
            }
            assertEquals(1, store.readConnections.idle());
            assertEquals(1, view.inner().readConnections.idle());
            try (NativeSqlite checkpoint = new NativeSqlite(store.indexPath, false)) {
                Object[] row = checkpoint.query("PRAGMA wal_checkpoint(TRUNCATE)").getFirst();
                assertEquals(0L, row[0], "checkpoint was busy");
            }
            assertEquals(0, java.nio.file.Files.size(folder.resolve("objects.sqlite3-wal")));
            assertArrayEquals(bytes, view.read(key(bytes), NONE).join().bytes());
        } finally {
            view.closeAsync().join();
        }
    }

    /** Another process's rebuild replaces the index: an idle reader must not pin or reuse it. */
    @Test
    void writerRebuildWithALivePooledReader() throws Exception {
        open(new Options());
        store.closeAsync().get(10, TimeUnit.SECONDS);
        ReadOnlyStore view = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            var inner = view.inner();
            var viewOpens = new AtomicInteger();
            inner.readerOpenProbe = viewOpens::incrementAndGet;
            boolean identities =
                    java.nio.file.Files.readAttributes(
                                            store.indexPath,
                                            java.nio.file.attribute.BasicFileAttributes.class)
                                    .fileKey()
                            != null;
            if (!identities) {
                // Windows: an open handle would block the rebuild, so idle ones must expire.
                inner.readConnections.idleTtlNanos = TimeUnit.MILLISECONDS.toNanos(50);
            }
            assertArrayEquals(bytes, view.read(key(bytes), NONE).join().bytes());
            assertEquals(1, inner.readConnections.idle());
            if (!identities) {
                waitForNoIdle(inner);
            }
            for (String name : List.of("objects.sqlite3", "objects.sqlite3-wal")) {
                java.nio.file.Files.deleteIfExists(folder.resolve(name));
            }
            store = ObjectStore.open(folder, new Options()).join();
            store.readerOpenProbe = opens::incrementAndGet;
            assertTrue(store.openReport().indexRebuilt());
            assertArrayEquals(bytes, view.read(key(bytes), NONE).join().bytes());
            assertEquals(2, viewOpens.get(), "the reader reused a connection on the old index");
        } finally {
            view.closeAsync().join();
        }
    }

    private static void waitForNoIdle(ObjectStore target) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (target.readConnections.idle() > 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Idle connections never expired");
            }
            Thread.sleep(10);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
