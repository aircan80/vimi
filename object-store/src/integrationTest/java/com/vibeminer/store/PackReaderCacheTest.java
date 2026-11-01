package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.key;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.store.StoreTypes.Options;
import com.vibeminer.store.StoreTypes.PutObject;
import com.vibeminer.store.StoreTypes.ReadState;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
final class PackReaderCacheTest {
    @TempDir Path folder;
    private ObjectStore store;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() throws Exception {
        release.countDown();
        if (store != null) {
            store.readProbe = null;
            store.closeAsync().get(10, TimeUnit.SECONDS);
            assertEquals(new PackReaders.State(0, 0, 0), store.packReaders.state());
        }
    }

    private PutObject object(int value) {
        byte[] bytes = {(byte) value, 2, 3, 4};
        return new PutObject(key(bytes), bytes);
    }

    @Test
    void repeatedAndConcurrentReadsReuseCachedHandles() throws Exception {
        store = ObjectStore.open(folder).join();
        store.put(List.of(object(1))).join();
        List<CompletableFuture<?>> reads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            reads.add(
                    store.read(object(1).key())
                            .thenAccept(
                                    read -> assertArrayEquals(object(1).bytes(), read.bytes())));
        }
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertEquals(new PackReaders.State(1, 1, 0), store.packReaders.state());
        assertArrayEquals(
                new byte[] {2, 3},
                store.readRange(object(1).key(), 1, 2, Cancellation.NONE).join().bytes());
        assertEquals(new PackReaders.State(1, 1, 0), store.packReaders.state());
    }

    @Test
    void configuredLruLimitClosesOldIdleChannels() throws Exception {
        store =
                ObjectStore.open(
                                folder, new Options(StoreTypes.Kind.CDN, 128, Clock.systemUTC(), 1))
                        .join();
        store.put(List.of(object(1), object(2), object(3))).join();
        store.read(object(1).key()).join();
        FileChannel first;
        try (var lease = store.packReaders.acquire(1)) {
            first = lease.handle();
        }
        assertTrue(first.isOpen());
        store.read(object(2).key()).join();
        assertFalse(first.isOpen());
        store.read(object(3).key()).join();
        assertEquals(new PackReaders.State(1, 1, 0), store.packReaders.state());
    }

    @Test
    void cachedActivePackSeesLaterAppends() {
        store = ObjectStore.open(folder).join();
        store.put(List.of(object(1))).join();
        store.read(object(1).key()).join();
        store.put(List.of(object(2))).join();
        assertArrayEquals(object(2).bytes(), store.read(object(2).key()).join().bytes());
        assertEquals(1, store.packReaders.state().openHandles());
    }

    @Test
    void leasedChannelAllowsConcurrentWritesAndUnlinkOnThisPlatform() throws Exception {
        Path path = folder.resolve("shared-pack.bin");
        Files.write(path, new byte[] {1, 2, 3});
        try (PackReaders<FileChannel> cache =
                new PackReaders<>(1, ignored -> FileChannel.open(path, StandardOpenOption.READ))) {
            var lease = cache.acquire(1);
            FileChannel channel = lease.handle();
            try {
                try (FileChannel writer = FileChannel.open(path, StandardOpenOption.WRITE)) {
                    Format.write(writer, 3, new byte[] {4});
                }
                Files.delete(path);
                cache.invalidate(1);
                assertFalse(Files.exists(path));
                assertTrue(channel.isOpen());
                assertArrayEquals(new byte[] {1, 2, 3, 4}, Format.read(channel, 0, 4));
            } finally {
                lease.close();
            }
            assertFalse(channel.isOpen());
            assertEquals(new PackReaders.State(0, 0, 0), cache.state());
        }
    }

    @Test
    void compactionRetiresIdleCachedSources() throws Exception {
        CompactionFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
        var key = key(CompactionFixture.kept());
        store.read(key).join();
        FileChannel source;
        try (var lease = store.packReaders.acquire(2)) {
            source = lease.handle();
        }
        assertTrue(source.isOpen());
        assertEquals(1, store.compact(0, 1, Cancellation.NONE).join().packsCompacted());
        assertFalse(source.isOpen());
        assertFalse(Files.exists(store.packPath(2)));
        assertArrayEquals(CompactionFixture.kept(), store.read(key).join().bytes());
    }

    @Test
    void anotherReadOnlyCacheDoesNotBlockCompactionDeletion() throws Exception {
        CompactionFixture.prepare(folder);
        store = ObjectStore.open(folder).join();
        ReadOnlyStore reader = ObjectStore.openReadOnly(folder, new Options()).join();
        try {
            var key = key(CompactionFixture.kept());
            reader.read(key, Cancellation.NONE).join();
            store.compact(0, 1, Cancellation.NONE).join();
            assertFalse(Files.exists(store.packPath(2)));
            assertArrayEquals(
                    CompactionFixture.kept(), reader.read(key, Cancellation.NONE).join().bytes());
        } finally {
            reader.closeAsync().join();
        }
    }

    @Test
    void closeWaitsForAcceptedReadLeaseBeforeDisposingItsChannel() throws Exception {
        store = ObjectStore.open(folder).join();
        store.put(List.of(object(1))).join();
        store.read(object(1).key()).join();
        FileChannel channel;
        try (var lease = store.packReaders.acquire(1)) {
            channel = lease.handle();
        }
        CountDownLatch acquired = new CountDownLatch(1);
        store.readProbe =
                (point, key) -> {
                    if (point == ReadPoint.PACK_OPENED) {
                        acquired.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            throw new AssertionError(error);
                        }
                    }
                };
        var reading = store.read(object(1).key());
        assertTrue(acquired.await(10, TimeUnit.SECONDS));
        var closed = store.closeAsync();
        assertFalse(closed.isDone());
        assertTrue(channel.isOpen());
        release.countDown();
        assertEquals(ReadState.PRESENT, reading.get(10, TimeUnit.SECONDS).state());
        closed.get(10, TimeUnit.SECONDS);
        assertFalse(channel.isOpen());
    }
}
