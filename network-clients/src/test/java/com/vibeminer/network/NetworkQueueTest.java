package com.vibeminer.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class NetworkQueueTest {
    @Test
    void overflowCancellationAndCloseHaveBoundedAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger cleanup = new AtomicInteger(), ran = new AtomicInteger();
        NetworkQueue queue = new NetworkQueue("test", 1, 1, cleanup::incrementAndGet);
        CompletableFuture<Integer> active =
                queue.submit(
                        Cancellation.NONE,
                        () -> {
                            entered.countDown();
                            await(release);
                            return 1;
                        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Cancellation token = new Cancellation();
            CompletableFuture<Integer> queued = queue.submit(token, ran::incrementAndGet);
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () -> queue.submit(Cancellation.NONE, () -> 3).join());
            assertEquals("NETWORK_LIMIT", ((NetworkException) error.getCause()).code());
            token.cancel();
            assertTrue(
                    queued.handle((v, e) -> e instanceof java.util.concurrent.CancellationException)
                            .join());
            CompletableFuture<Integer> replacement = queue.submit(Cancellation.NONE, () -> 2);
            CompletableFuture<Void> closed = queue.closeAsync();
            assertFalse(closed.isDone());
            release.countDown();
            closed.join();
            assertTrue(active.isDone());
            assertTrue(replacement.isDone());
            assertEquals(1, active.join());
            assertEquals(2, replacement.join());
            assertEquals(0, ran.get());
            assertEquals(1, cleanup.get());
            assertEquals(
                    "NETWORK_CLOSED",
                    ((NetworkException)
                                    assertThrows(
                                                    CompletionException.class,
                                                    () ->
                                                            queue.submit(Cancellation.NONE, () -> 3)
                                                                    .join())
                                            .getCause())
                            .code());
        } finally {
            release.countDown();
            queue.closeAsync().join();
        }
    }

    @Test
    void continuationsCanCloseTheQueueWithoutDeadlockingItsWorker() {
        NetworkQueue queue = new NetworkQueue("reentrant", 1, 1, () -> {});
        queue.submit(Cancellation.NONE, () -> 1).thenCompose(ignored -> queue.closeAsync()).join();
    }

    @Test
    void canceledAdmissionAndFailedWorkDoNotPoisonTheQueue() {
        NetworkQueue queue = new NetworkQueue("failure", 1, 1, () -> {});
        try {
            Cancellation canceled = new Cancellation();
            canceled.cancel();
            assertTrue(
                    queue.submit(
                                    canceled,
                                    () -> {
                                        throw new AssertionError("Canceled work ran");
                                    })
                            .isCompletedExceptionally());
            assertThrows(
                    CompletionException.class,
                    () ->
                            queue.submit(
                                            Cancellation.NONE,
                                            () -> {
                                                throw new IllegalStateException("test");
                                            })
                                    .join());
            assertEquals(2, queue.submit(Cancellation.NONE, () -> 2).join());
        } finally {
            queue.closeAsync().join();
        }
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
}
