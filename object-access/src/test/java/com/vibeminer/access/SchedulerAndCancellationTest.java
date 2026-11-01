package com.vibeminer.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.Options;
import com.vibeminer.network.NetworkTypes.Failure;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class SchedulerAndCancellationTest {
    private static Options options(int min, int max, int interactive, int queued, long bytes) {
        return new Options(
                min,
                max,
                interactive,
                queued,
                bytes,
                bytes,
                64L << 20,
                64L << 20,
                Duration.ofSeconds(30),
                Clock.systemUTC());
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
    void interactiveReserveRunsWhileBackgroundIsSaturatedAndPromotesWaitingWork() throws Exception {
        StreamScheduler scheduler = new StreamScheduler(options(1, 1, 1, 8, 10));
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                promoted = new CountDownLatch(1);
        try {
            var first =
                    scheduler.submit(
                            FetchPriority.BACKGROUND,
                            10,
                            Cancellation.NONE,
                            () -> {
                                entered.countDown();
                                await(release);
                                return 1;
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second =
                    scheduler.submit(
                            FetchPriority.BACKGROUND,
                            10,
                            Cancellation.NONE,
                            () -> {
                                promoted.countDown();
                                return 2;
                            });
            assertFalse(second.future().isDone());
            second.promote();
            assertTrue(promoted.await(5, TimeUnit.SECONDS));
            assertEquals(FetchPriority.INTERACTIVE, second.priority());
            assertEquals(2, second.future().join());
            assertEquals(1, scheduler.backgroundActive());
            release.countDown();
            assertEquals(1, first.future().join());
        } finally {
            release.countDown();
            scheduler.closeAsync().join();
        }
    }

    @Test
    void byteBudgetsQueueWorkWithoutBlockingAdmissionAndCancellationReleasesAQueueSlot()
            throws Exception {
        StreamScheduler scheduler = new StreamScheduler(options(2, 2, 1, 1, 10));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try {
            var first =
                    scheduler.submit(
                            FetchPriority.BACKGROUND,
                            6,
                            Cancellation.NONE,
                            () -> {
                                entered.countDown();
                                await(release);
                                return 1;
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(6, scheduler.backgroundBytes());
            Cancellation token = new Cancellation();
            var queued = scheduler.submit(FetchPriority.BACKGROUND, 6, token, ran::incrementAndGet);
            assertFalse(queued.future().isDone());
            assertThrows(
                    CompletionException.class,
                    () ->
                            scheduler
                                    .submit(FetchPriority.BACKGROUND, 1, Cancellation.NONE, () -> 3)
                                    .future()
                                    .join());
            token.cancel();
            assertTrue(
                    queued.future()
                            .handle(
                                    (v, e) ->
                                            e instanceof java.util.concurrent.CancellationException)
                            .join());
            var replacement =
                    scheduler.submit(FetchPriority.BACKGROUND, 6, Cancellation.NONE, () -> 2);
            release.countDown();
            assertEquals(1, first.future().join());
            assertEquals(2, replacement.future().join());
            assertEquals(0, ran.get());
        } finally {
            release.countDown();
            scheduler.closeAsync().join();
        }
    }

    @Test
    void adaptiveLimitGrowsOnStreaksAndShrinksOnPassingFailuresWithinBounds() {
        StreamScheduler scheduler = new StreamScheduler(options(1, 4, 1, 4, 10));
        try {
            scheduler.observe(null);
            assertEquals(2, scheduler.backgroundLimit());
            scheduler.observe(null);
            scheduler.observe(null);
            assertEquals(3, scheduler.backgroundLimit());
            scheduler.observe(Failure.NETWORK);
            assertEquals(2, scheduler.backgroundLimit());
            scheduler.observe(Failure.TIMEOUT);
            assertEquals(1, scheduler.backgroundLimit());
            scheduler.observe(Failure.NETWORK);
            assertEquals(1, scheduler.backgroundLimit());
        } finally {
            scheduler.closeAsync().join();
        }
    }

    @Test
    void cancellationRegistrationRaceInvokesEachListenerExactlyOnce() throws Exception {
        for (int trial = 0; trial < 300; trial++) {
            Cancellation token = new Cancellation();
            AtomicInteger called = new AtomicInteger();
            CyclicBarrier start = new CyclicBarrier(2);
            CompletableFuture<Void> registered =
                    CompletableFuture.runAsync(
                            () -> {
                                cross(start);
                                token.onCancel(called::incrementAndGet);
                            });
            CompletableFuture<Void> canceled =
                    CompletableFuture.runAsync(
                            () -> {
                                cross(start);
                                token.cancel();
                            });
            CompletableFuture.allOf(registered, canceled).get(5, TimeUnit.SECONDS);
            assertEquals(1, called.get());
        }
    }

    @Test
    void throwingCallbackDoesNotPreventOtherCallbacksOrLowerTokenCancellation() {
        Cancellation token = new Cancellation();
        AtomicInteger called = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("callback");
        token.onCancel(
                () -> {
                    throw expected;
                });
        token.onCancel(called::incrementAndGet);
        assertTrue(assertThrows(IllegalStateException.class, token::cancel) == expected);
        assertEquals(1, called.get());
        assertTrue(token.storeToken().isCanceled());
        assertTrue(token.networkToken().isCanceled());
        token.cancel();
        assertEquals(1, called.get());
        Cancellation.NONE.cancel();
        assertFalse(Cancellation.NONE.isCanceled());
    }

    @Test
    void closedRegistrationDoesNotRetainOrNotifyItsCallback() {
        Cancellation token = new Cancellation();
        AtomicInteger called = new AtomicInteger();
        token.onCancel(called::incrementAndGet).close();
        token.cancel();
        assertEquals(0, called.get());
        token.onCancel(called::incrementAndGet);
        assertEquals(1, called.get());
    }

    @Test
    void canceledCapacityWaitIsAnExceptionalFutureRatherThanASynchronousThrow() {
        StreamScheduler scheduler = new StreamScheduler(options(1, 1, 1, 1, 10));
        try {
            Cancellation token = new Cancellation();
            token.cancel();
            var waiting = scheduler.whenAvailable(token);
            assertTrue(
                    waiting.handle(
                                    (value, error) ->
                                            error
                                                    instanceof
                                                    java.util.concurrent.CancellationException)
                            .join());
        } finally {
            scheduler.closeAsync().join();
        }
    }

    private static void cross(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
