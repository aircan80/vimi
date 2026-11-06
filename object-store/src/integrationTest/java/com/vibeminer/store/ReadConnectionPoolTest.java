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
    void connectionsDoNotOutliveAnAccessModeSwitch() throws Exception {
        open(new Options());
        store.read(key(bytes), NONE).join();
        var pool = new ReadConnections(4);
        var writable = new ReadConnections.Pooled(store.openReader(), false, pool.generation());
        pool.give(writable, true, false);
        assertEquals(1, pool.idle());
        assertNull(pool.take(true), "a WAL-mode connection served an immutable read");
        assertEquals(0, pool.idle());
        var again = new ReadConnections.Pooled(store.openReader(), false, pool.generation());
        pool.give(again, true, true);
        assertEquals(0, pool.idle(), "a connection returned after the switch was pooled");
        var current = new ReadConnections.Pooled(store.openReader(), true, pool.generation());
        pool.give(current, true, true);
        assertSame(current, pool.take(true));
        pool.give(current, true, true);
        pool.close();
        assertEquals(0, pool.idle());
        var late = new ReadConnections.Pooled(store.openReader(), true, pool.generation());
        pool.give(late, true, true);
        assertEquals(0, pool.idle());
        assertNull(pool.take(true));
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
