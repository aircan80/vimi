package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class MetadataCacheTest {
    private static BuildRef ref(int i) {
        return new BuildRef("wow", String.format("%032x", i), "b".repeat(32), "us");
    }

    @Test
    void successfulContextsAreBoundedByLruAndFailuresAreNotCached() {
        var cache = new MetadataCache<String>(1, 1, 2);
        var reads = new AtomicInteger();
        try {
            for (int i : new int[] {1, 1, 2, 1}) {
                assertEquals(
                        "ok",
                        cache.open(
                                        ref(i),
                                        token -> {
                                            reads.incrementAndGet();
                                            return "ok";
                                        },
                                        Cancellation.NONE)
                                .join());
            }
            assertEquals(3, reads.get());
            for (int i = 0; i < 2; i++) {
                assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () ->
                                cache.open(
                                                ref(3),
                                                token -> {
                                                    reads.incrementAndGet();
                                                    throw new IllegalStateException("bad");
                                                },
                                                Cancellation.NONE)
                                        .join());
            }
            assertEquals(5, reads.get());
        } finally {
            cache.closeAsync().join();
        }
    }

    @Test
    void admissionIsBoundedAndCanceledLastWaiterReleasesQueuedWork() throws Exception {
        var cache = new MetadataCache<String>(1, 1, 1);
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var queuedToken = new Cancellation();
        try {
            var first =
                    cache.open(
                            ref(1),
                            token -> {
                                entered.countDown();
                                release.join();
                                return "first";
                            },
                            Cancellation.NONE);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = cache.open(ref(2), token -> "second", queuedToken);
            var rejected = cache.open(ref(3), token -> "third", Cancellation.NONE);
            var error =
                    assertThrows(java.util.concurrent.CompletionException.class, rejected::join);
            assertEquals("BUILD_OPEN_LIMIT", ((BuildOpenException) error.getCause()).code());
            queuedToken.cancel();
            assertThrows(java.util.concurrent.CancellationException.class, queued::join);
            var accepted = cache.open(ref(3), token -> "third", Cancellation.NONE);
            release.complete(null);
            assertEquals("first", first.join());
            assertEquals("third", accepted.join());
        } finally {
            release.complete(null);
            cache.closeAsync().join();
        }
    }

    @Test
    void closeObserversCannotForgeDrainAndSurvivingWaitersShareOneAction() throws Exception {
        var cache = new MetadataCache<String>(1, 1, 1);
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var firstToken = new Cancellation();
        var calls = new AtomicInteger();
        var first =
                cache.open(
                        ref(1),
                        token -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            release.join();
                            token.check();
                            return "context";
                        },
                        firstToken);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var second =
                cache.open(
                        ref(1),
                        token -> {
                            throw new AssertionError("Duplicate flight");
                        },
                        Cancellation.NONE);
        firstToken.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, first::join);
        var observer = cache.closeAsync();
        observer.complete(null);
        var actual = cache.closeAsync();
        try {
            assertFalse(actual.isDone());
            assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> cache.open(ref(2), token -> "late", Cancellation.NONE).join());
        } finally {
            release.complete(null);
        }
        assertEquals("context", second.join());
        actual.get(5, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
    }
}
