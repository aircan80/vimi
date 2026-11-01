package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class PackReadersTest {
    private static final class Handle implements Closeable {
        boolean closed;
        boolean failClose;
        int closes;

        @Override
        public void close() throws IOException {
            closes++;
            closed = true;
            if (failClose) {
                throw new IOException("Injected close failure");
            }
        }
    }

    @Test
    void repeatedAcquisitionsReuseOneHandleAndReleaseIsIdempotent() throws Exception {
        Handle handle;
        try (PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle())) {
            var first = cache.acquire(1);
            var second = cache.acquire(1);
            handle = first.handle();
            assertSame(handle, second.handle());
            first.close();
            first.close();
            assertFalse(handle.closed);
            assertEquals(1, cache.state().leases());
            assertThrows(IllegalStateException.class, first::handle);
            second.close();
            assertFalse(handle.closed);
        }
        assertTrue(handle.closed);
        assertEquals(1, handle.closes);
    }

    @Test
    void evictsLeastRecentlyUsedIdleHandle() throws Exception {
        try (PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle())) {
            Handle one;
            Handle two;
            try (var lease = cache.acquire(1)) {
                one = lease.handle();
            }
            try (var lease = cache.acquire(2)) {
                two = lease.handle();
            }
            try (var lease = cache.acquire(1)) {
                assertSame(one, lease.handle());
            }
            try (var lease = cache.acquire(3)) {
                assertFalse(lease.handle().closed);
                assertFalse(one.closed);
                assertTrue(two.closed);
                assertEquals(2, cache.state().cached());
            }
        }
    }

    @Test
    void aPinnedLruEntryIsNotAnEvictionVictim() throws Exception {
        try (PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle());
                var pinned = cache.acquire(1)) {
            Handle idle;
            try (var lease = cache.acquire(2)) {
                idle = lease.handle();
            }
            try (var lease = cache.acquire(3)) {
                assertFalse(pinned.handle().closed);
                assertTrue(idle.closed);
                assertFalse(lease.handle().closed);
            }
        }
    }

    @Test
    void allPinnedUsesUncachedLeaseWithoutGrowingRetainedCache() throws Exception {
        try (PackReaders<Handle> cache = new PackReaders<>(1, ignored -> new Handle());
                var pinned = cache.acquire(1)) {
            Handle temporary;
            try (var overflow = cache.acquire(2)) {
                temporary = overflow.handle();
                assertEquals(1, cache.state().cached());
                assertEquals(2, cache.state().openHandles());
                assertFalse(pinned.handle().closed);
            }
            assertTrue(temporary.closed);
            assertEquals(1, cache.state().openHandles());
        }
    }

    @Test
    void retirementKeepsAllExistingLeasesAliveUntilLastRelease() throws Exception {
        try (PackReaders<Handle> cache = new PackReaders<>(1, ignored -> new Handle())) {
            var first = cache.acquire(1);
            var second = cache.acquire(1);
            Handle original = first.handle();
            cache.invalidate(1);
            assertEquals(0, cache.state().cached());
            assertFalse(original.closed);
            try (var replacement = cache.acquire(1)) {
                assertNotSame(original, replacement.handle());
            }
            first.close();
            assertFalse(original.closed);
            second.close();
            assertTrue(original.closed);
            assertEquals(1, original.closes);
        }
    }

    @Test
    void shutdownClosesIdleEntriesAndDefersActiveHandles() throws Exception {
        PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle());
        var active = cache.acquire(1);
        Handle idle;
        try (var lease = cache.acquire(2)) {
            idle = lease.handle();
        }
        Handle pinned = active.handle();
        cache.close();
        assertTrue(idle.closed);
        assertFalse(pinned.closed);
        assertEquals(0, cache.state().cached());
        assertEquals(
                "OBJECT_STORE_CLOSED",
                assertThrows(StoreException.class, () -> cache.acquire(1)).code());
        active.close();
        cache.close();
        assertTrue(pinned.closed);
        assertEquals(0, cache.state().openHandles());
        assertEquals(0, cache.state().leases());
    }

    @Test
    void missingAndFailedOpensDoNotConsumeCacheSlots() throws Exception {
        try (PackReaders<Handle> cache =
                new PackReaders<>(
                        1,
                        number -> {
                            if (number == 1) {
                                throw new NoSuchFileException("missing synthetic pack");
                            }
                            throw new IOException("Synthetic access failure");
                        })) {
            assertNull(cache.acquire(1));
            assertThrows(IOException.class, () -> cache.acquire(2));
            assertEquals(new PackReaders.State(0, 0, 0), cache.state());
        }
    }

    @Test
    void evictionFailureClosesTheNewlyOpenedHandle() throws Exception {
        List<Handle> handles = new ArrayList<>();
        try (PackReaders<Handle> cache =
                new PackReaders<>(
                        1,
                        ignored -> {
                            Handle handle = new Handle();
                            handles.add(handle);
                            return handle;
                        })) {
            try (var first = cache.acquire(1)) {
                first.handle().failClose = true;
            }
            assertThrows(IOException.class, () -> cache.acquire(2));
            assertTrue(handles.stream().allMatch(handle -> handle.closed));
            assertEquals(new PackReaders.State(0, 0, 0), cache.state());
        }
    }

    @Test
    void shutdownAttemptsEveryCloseEvenWhenAnEarlierOneFails() throws Exception {
        PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle());
        Handle first;
        Handle second;
        try (var lease = cache.acquire(1)) {
            first = lease.handle();
            first.failClose = true;
        }
        try (var lease = cache.acquire(2)) {
            second = lease.handle();
        }
        assertThrows(IOException.class, cache::close);
        assertTrue(first.closed);
        assertTrue(second.closed);
        assertEquals(0, cache.state().openHandles());
    }

    @Test
    void concurrentAcquireRetireAndCloseNeverDisposeALeasedHandle() throws Exception {
        PackReaders<Handle> cache = new PackReaders<>(2, ignored -> new Handle());
        CountDownLatch acquired = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int pack = i % 3;
            CompletableFuture<Void> done = new CompletableFuture<>();
            tasks.add(done);
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var lease = cache.acquire(pack)) {
                                    acquired.countDown();
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                    assertFalse(lease.handle().closed);
                                } catch (Throwable error) {
                                    done.completeExceptionally(error);
                                    return;
                                }
                                done.complete(null);
                            });
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertTrue(cache.state().cached() <= 2);
            cache.invalidate(0);
            cache.close();
        } finally {
            release.countDown();
        }
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(new PackReaders.State(0, 0, 0), cache.state());
    }

    @Test
    void configurationKeepsExistingConstructorsAndRejectsInvalidCapacity() {
        assertEquals(64, new StoreTypes.Options().packHandles());
        assertEquals(
                64,
                new StoreTypes.Options(StoreTypes.Kind.CDN, 1024, java.time.Clock.systemUTC())
                        .packHandles());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StoreTypes.Options(
                                StoreTypes.Kind.CDN, 1024, java.time.Clock.systemUTC(), 0));
    }
}
