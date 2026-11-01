package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class OpenCacheTest {
    private static BuildRef ref(int n) {
        return new BuildRef("wow", MetadataFixtures.key(n).hex(), MetadataFixtures.key(99).hex());
    }

    private static Build build(BuildRef ref) {
        return new MemoryBuilds.Opened(
                ref,
                new BuildTypes.BuildMetadata(
                        null,
                        MetadataFixtures.key(1),
                        MetadataFixtures.key(2),
                        MetadataFixtures.key(3),
                        0,
                        0,
                        List.of()),
                null,
                Map.of(),
                Map.of(),
                Map.of());
    }

    private static BuildOpenResult opened(BuildRef ref, Build built) {
        return new BuildOpenResult(ref, BuildOpenState.OPEN, built, null, null, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void cancellationOfOneWaiterDoesNotCancelTheSharedOpen() throws Exception {
        OpenCache cache = new OpenCache(2, 1, 2);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BuildRef ref = ref(1);
        Build built = build(ref);
        Cancellation firstToken = new Cancellation();
        java.util.function.Function<Cancellation, BuildOpenResult> action =
                token -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    await(release);
                    token.check();
                    return opened(ref, built);
                };
        try {
            var first = cache.open(ref, action, firstToken);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = cache.open(ref, action, Cancellation.NONE);
            firstToken.cancel();
            assertTrue(
                    first.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .join());
            assertFalse(second.isDone());
            release.countDown();
            assertSame(built, second.join().opened());
            assertEquals(1, calls.get());
            assertSame(built, cache.open(ref, action, Cancellation.NONE).join().opened());
        } finally {
            release.countDown();
            cache.closeAsync().join();
        }
    }

    @Test
    void lastCanceledQueuedOpenReleasesItsAdmissionSlot() throws Exception {
        OpenCache cache = new OpenCache(2, 1, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        BuildRef first = ref(1), second = ref(2), third = ref(3);
        try {
            var active =
                    cache.open(
                            first,
                            token -> {
                                entered.countDown();
                                await(release);
                                return opened(first, build(first));
                            },
                            Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Cancellation token = new Cancellation();
            var queued =
                    cache.open(
                            second,
                            c -> {
                                throw new AssertionError("Canceled queued work ran");
                            },
                            token);
            token.cancel();
            assertTrue(
                    queued.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .join());
            var replacement =
                    cache.open(third, c -> opened(third, build(third)), Cancellation.NONE);
            release.countDown();
            active.join();
            assertEquals(BuildOpenState.OPEN, replacement.join().state());
        } finally {
            release.countDown();
            cache.closeAsync().join();
        }
    }

    @Test
    void lruEvictionAndFailuresAreRetryableWithoutCachingBadResults() {
        OpenCache cache = new OpenCache(1, 1, 2);
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<BuildRef, BuildOpenResult> load =
                ref -> {
                    calls.incrementAndGet();
                    return opened(ref, build(ref));
                };
        try {
            cache.open(ref(1), c -> load.apply(ref(1)), Cancellation.NONE).join();
            cache.open(ref(2), c -> load.apply(ref(2)), Cancellation.NONE).join();
            cache.open(ref(1), c -> load.apply(ref(1)), Cancellation.NONE).join();
            assertEquals(3, calls.get());
            BuildRef missing = ref(3);
            cache.open(
                            missing,
                            c ->
                                    new BuildOpenResult(
                                            missing,
                                            BuildOpenState.NEEDS_NETWORK,
                                            null,
                                            null,
                                            "offline",
                                            "offline",
                                            null),
                            Cancellation.NONE)
                    .join();
            assertEquals(
                    BuildOpenState.OPEN,
                    cache.open(missing, c -> load.apply(missing), Cancellation.NONE)
                            .join()
                            .state());
        } finally {
            cache.closeAsync().join();
        }
    }

    @Test
    void queueOverflowAndCloseAreExplicitAndReentrantCompletionDoesNotDeadlock() throws Exception {
        OpenCache cache = new OpenCache(1, 1, 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var first =
                    cache.open(
                            ref(1),
                            c -> {
                                entered.countDown();
                                await(release);
                                return opened(ref(1), build(ref(1)));
                            },
                            Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = cache.open(ref(2), c -> opened(ref(2), build(ref(2))), Cancellation.NONE);
            CompletionException full =
                    assertThrows(
                            CompletionException.class,
                            () ->
                                    cache.open(
                                                    ref(3),
                                                    c -> opened(ref(3), build(ref(3))),
                                                    Cancellation.NONE)
                                            .join());
            assertEquals("BUILD_OPEN_LIMIT", ((BuildOpenException) full.getCause()).code());
            var closed = first.thenCompose(v -> cache.closeAsync());
            release.countDown();
            closed.join();
            assertTrue(second.isDone());
            assertEquals(
                    "BUILD_OPEN_CLOSED",
                    ((BuildOpenException)
                                    assertThrows(
                                                    CompletionException.class,
                                                    () ->
                                                            cache.open(
                                                                            ref(4),
                                                                            c ->
                                                                                    opened(
                                                                                            ref(4),
                                                                                            build(
                                                                                                    ref(
                                                                                                            4))),
                                                                            Cancellation.NONE)
                                                                    .join())
                                            .getCause())
                            .code());
        } finally {
            release.countDown();
            cache.closeAsync().join();
        }
    }
}
