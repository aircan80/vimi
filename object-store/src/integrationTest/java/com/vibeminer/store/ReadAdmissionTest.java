package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Kind;
import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Read and open admission waits for a worker instead of failing fast (#272). */
@Timeout(60)
final class ReadAdmissionTest {
    @TempDir Path folder;
    private final byte[] bytes = {1, 2, 3, 4};
    private ObjectStore store;
    private final CountDownLatch release = new CountDownLatch(1);

    private ObjectStore open(int readers) {
        store =
                ObjectStore.open(
                                folder,
                                new Options(Kind.CDN, 2L << 30, Clock.systemUTC(), 64, readers))
                        .join();
        store.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        return store;
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        if (store != null) {
            store.readProbe = null;
            store.closeAsync().get(20, TimeUnit.SECONDS);
        }
    }

    /** Blocks the read workers at the row lookup until {@link #release}. */
    private CountDownLatch block(int workers) {
        var entered = new CountDownLatch(workers);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.ROW_FOUND) {
                        entered.countDown();
                        try {
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
        return entered;
    }

    @Test
    void burstFarPastTheOldQueueCompletes() {
        open(Options.DEFAULT_READERS);
        List<CompletableFuture<StoreTypes.ObjectRead>> reads = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            reads.add(store.read(key(bytes), NONE));
        }
        for (var read : reads) {
            assertArrayEquals(bytes, read.join().bytes());
        }
    }

    @Test
    void burstOfOpensPastTheOldOpenerQueueCompletes() {
        open(Options.DEFAULT_READERS).closeAsync().join();
        store = null;
        List<CompletableFuture<ReadOnlyStore>> opens = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            opens.add(ObjectStore.openReadOnly(folder, new Options()));
        }
        for (var opened : opens) {
            var reader = opened.join();
            try {
                assertTrue(reader.has(key(bytes), NONE).join());
            } finally {
                reader.closeAsync().join();
            }
        }
    }

    @Test
    void cancelingAWaitingReadCompletesAtOnceAndFreesItsSlot() throws Exception {
        open(1);
        var entered = block(1);
        var running = store.read(key(bytes), NONE);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        var token = new Cancellation();
        var waiting = store.read(key(bytes), token);
        assertEquals(1, store.readers.getQueue().size());
        token.cancel();
        assertThrows(CancellationException.class, () -> waiting.get(5, TimeUnit.SECONDS));
        assertEquals(0, store.readers.getQueue().size());
        assertFalse(running.isDone());
        release.countDown();
        assertArrayEquals(bytes, running.join().bytes());
    }

    @Test
    void closeFailsWaitingReadsWithoutRunningThem() throws Exception {
        open(1);
        var entered = block(1);
        var running = store.read(key(bytes), NONE);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        List<CompletableFuture<StoreTypes.ObjectRead>> waiting = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            waiting.add(store.read(key(bytes), NONE));
        }
        var closing = store.closeAsync();
        release.countDown();
        assertArrayEquals(bytes, running.join().bytes());
        for (var read : waiting) {
            var failure = assertThrows(CompletionException.class, read::join);
            assertEquals("OBJECT_STORE_CLOSED", ((StoreException) failure.getCause()).code());
        }
        closeOrDumpThreads(closing);
        store = null;
    }

    /**
     * Waits for {@code closing}. This close has hung, rarely (Linux CI once, and once locally on
     * one CPU); a hang prints every thread, virtual ones included, so the next one can be
     * diagnosed.
     */
    private static void closeOrDumpThreads(CompletableFuture<Void> closing) throws Exception {
        try {
            closing.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException hung) {
            Path dump = Files.createTempFile("read-admission-close-", ".txt");
            Files.delete(dump);
            java.lang.management.ManagementFactory.getPlatformMXBean(
                            com.sun.management.HotSpotDiagnosticMXBean.class)
                    .dumpThreads(
                            dump.toString(),
                            com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            System.err.println("Store close did not finish; threads:");
            System.err.println(Files.readString(dump));
            Files.deleteIfExists(dump);
            throw hung;
        }
    }

    @Test
    void theHardBoundFailsWithADistinctQueueFullCode() throws Exception {
        open(1);
        var entered = block(1);
        var running = store.read(key(bytes), NONE);
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        List<CompletableFuture<StoreTypes.ObjectRead>> waiting = new ArrayList<>();
        for (int i = 0; i < ObjectStore.READ_QUEUE; i++) {
            waiting.add(store.read(key(bytes), NONE));
        }
        var refused = store.read(key(bytes), NONE);
        var failure = assertThrows(CompletionException.class, refused::join);
        assertEquals(Async.QUEUE_FULL, ((StoreException) failure.getCause()).code());
        release.countDown();
        assertArrayEquals(bytes, running.join().bytes());
        for (var read : waiting) {
            assertArrayEquals(bytes, read.join().bytes());
        }
    }
}
