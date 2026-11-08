package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.NONE;
import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A writer's index rebuild alongside a reader that keeps reading (#458). */
@Timeout(60)
final class RebuildWithActiveReaderTest {
    @TempDir Path folder;
    private final byte[] bytes = {1, 2, 3, 4};

    @Test
    void rebuildCompletesWhileAReaderReadsContinuouslyAndTheReaderContinues() throws Exception {
        rebuildWithReaders(1);
    }

    /** Overlapping reads from several threads must not starve the replace (#480 review). */
    @Test
    void rebuildCompletesWhileFourReaderThreadsReadContinuously() throws Exception {
        rebuildWithReaders(4);
    }

    private void rebuildWithReaders(int threads) throws Exception {
        var first = ObjectStore.open(folder).join();
        first.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        first.closeAsync().join();
        var reader = ObjectStore.openReadOnly(folder, new Options()).join();
        var stop = new AtomicBoolean();
        var reads = new AtomicInteger();
        var after = new AtomicInteger();
        var rebuilt = new AtomicBoolean();
        var loops = new java.util.ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            loops.add(
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        while (!stop.get()) {
                                            try {
                                                var read = reader.read(key(bytes), NONE).join();
                                                if (read.bytes() != null) {
                                                    reads.incrementAndGet();
                                                    if (rebuilt.get()) {
                                                        after.incrementAndGet();
                                                    }
                                                }
                                            } catch (RuntimeException transientDuringSwitch) {
                                                // A read racing the switch may fail; the next opens
                                                // anew.
                                            }
                                        }
                                    }));
        }
        ObjectStore writer = null;
        try {
            while (reads.get() < 50) {
                Thread.sleep(5);
            }
            // Invalidate the index (an out-of-range clean flag) so the next writer rebuilds it.
            try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
                db.exec("UPDATE store SET clean_close=5");
            }
            long opening = System.nanoTime();
            writer = ObjectStore.open(folder).get(30, TimeUnit.SECONDS);
            assertTrue(writer.openReport().indexRebuilt());
            assertTrue(
                    System.nanoTime() - opening < TimeUnit.SECONDS.toNanos(8),
                    "the rebuild only got through near its 10 s bound");
            rebuilt.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (after.get() < 20 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(after.get() >= 20, "the readers did not continue on the new index");
            assertArrayEquals(bytes, writer.read(key(bytes), NONE).join().bytes());
        } finally {
            stop.set(true);
            for (var loop : loops) {
                loop.join();
            }
            reader.closeAsync().join();
            if (writer != null) {
                writer.closeAsync().join();
            }
        }
    }

    @Test
    void indexFilesKeptInUsePastTheBoundRefuseWithAReason() throws Exception {
        var store = ObjectStore.open(folder).join();
        try {
            store.retryIndexInUse = true; // The Windows behaviour, on any platform.
            // Long enough that a cold first attempt on a slow runner still leaves room to retry.
            long bound = TimeUnit.SECONDS.toNanos(1);
            var attempts = new AtomicInteger();
            var refused =
                    assertThrows(
                            StoreException.class,
                            () ->
                                    store.whileIndexInUse(
                                            System.nanoTime() + bound,
                                            () -> {
                                                attempts.incrementAndGet();
                                                throw new FileSystemException(
                                                        "objects.sqlite3",
                                                        null,
                                                        "used by another process");
                                            }));
            assertEquals("OBJECT_STORE_INDEX_IN_USE", refused.code());
            assertTrue(attempts.get() > 1, "the action was not retried");
            // A replace onto a file another process holds open fails as access denied.
            var denied = new AtomicInteger();
            var deniedRefusal =
                    assertThrows(
                            StoreException.class,
                            () ->
                                    store.whileIndexInUse(
                                            System.nanoTime() + bound,
                                            () -> {
                                                denied.incrementAndGet();
                                                throw new java.nio.file.AccessDeniedException("x");
                                            }));
            assertEquals("OBJECT_STORE_INDEX_IN_USE", deniedRefusal.code());
            assertTrue(denied.get() > 1);
            // Other failures are not retried.
            var other = new AtomicInteger();
            assertThrows(
                    java.nio.file.NoSuchFileException.class,
                    () ->
                            store.whileIndexInUse(
                                    System.nanoTime() + bound,
                                    () -> {
                                        other.incrementAndGet();
                                        throw new java.nio.file.NoSuchFileException("x");
                                    }));
            assertThrows(
                    IOException.class,
                    () ->
                            store.whileIndexInUse(
                                    System.nanoTime() + bound,
                                    () -> {
                                        other.incrementAndGet();
                                        throw new IOException("injected");
                                    }));
            assertEquals(2, other.get());
            // Elsewhere an open file can be replaced: such a failure is real and not retried.
            store.retryIndexInUse = false;
            var posix = new AtomicInteger();
            assertThrows(
                    FileSystemException.class,
                    () ->
                            store.whileIndexInUse(
                                    System.nanoTime() + bound,
                                    () -> {
                                        posix.incrementAndGet();
                                        throw new FileSystemException("objects.sqlite3");
                                    }));
            assertEquals(1, posix.get());
        } finally {
            store.closeAsync().join();
        }
    }
}
