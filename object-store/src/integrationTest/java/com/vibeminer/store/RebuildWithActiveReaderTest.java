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
        var first = ObjectStore.open(folder).join();
        first.put(List.of(new PutObject(key(bytes), bytes)), true, NONE).join();
        first.closeAsync().join();
        var reader = ObjectStore.openReadOnly(folder, new Options()).join();
        var stop = new AtomicBoolean();
        var reads = new AtomicInteger();
        var after = new AtomicInteger();
        var rebuilt = new AtomicBoolean();
        var loop =
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
                                });
        ObjectStore writer = null;
        try {
            while (reads.get() < 50) {
                Thread.sleep(5);
            }
            // Invalidate the index (an out-of-range clean flag) so the next writer rebuilds it.
            try (NativeSqlite db = new NativeSqlite(folder.resolve("objects.sqlite3"), false)) {
                db.exec("UPDATE store SET clean_close=5");
            }
            writer = ObjectStore.open(folder).get(30, TimeUnit.SECONDS);
            assertTrue(writer.openReport().indexRebuilt());
            rebuilt.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (after.get() < 20 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(after.get() >= 20, "the reader did not continue on the new index");
            assertArrayEquals(bytes, writer.read(key(bytes), NONE).join().bytes());
        } finally {
            stop.set(true);
            loop.join();
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
            store.indexInUseWaitNanos = TimeUnit.MILLISECONDS.toNanos(100);
            var attempts = new AtomicInteger();
            var refused =
                    assertThrows(
                            StoreException.class,
                            () ->
                                    store.whileIndexInUse(
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
                                    () -> {
                                        other.incrementAndGet();
                                        throw new java.nio.file.NoSuchFileException("x");
                                    }));
            assertThrows(
                    IOException.class,
                    () ->
                            store.whileIndexInUse(
                                    () -> {
                                        other.incrementAndGet();
                                        throw new IOException("injected");
                                    }));
            assertEquals(2, other.get());
        } finally {
            store.closeAsync().join();
        }
    }
}
