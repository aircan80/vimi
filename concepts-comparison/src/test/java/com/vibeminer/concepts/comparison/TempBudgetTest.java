package com.vibeminer.concepts.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try") // Tests release old stages before final idempotent cleanup.
class TempBudgetTest {
    @Test
    void sortingInputsReplacementAndResultsShareOneDiskQuota() throws Exception {
        try (var budget = new TempBudget(32, 100);
                var a = budget.reserveDisk("input-a", 30, new Cancellation());
                var b = budget.reserveDisk("input-b", 30, new Cancellation());
                var output = budget.reserveDisk("merge-output", 0, new Cancellation())) {
            output.grow(35, new Cancellation());
            assertThrows(TempBudget.Limit.class, () -> output.grow(6, new Cancellation()));
            assertEquals(95, budget.snapshot().disk());
            // Only after the old input has been deleted may its reservation be released.
            a.close();
            output.grow(20, new Cancellation());
            assertEquals(85, budget.snapshot().disk());
            assertEquals(95, budget.snapshot().peakDisk());
            assertSame(output, output.transferTo("sealed-input"));
            assertEquals("sealed-input", output.owner());
        }
    }

    @Test
    void memoryIsIndependentButSharedAcrossBothEndpoints() throws Exception {
        try (var budget = new TempBudget(32, 100);
                var left = budget.reserveMemory("left-page", 14, new Cancellation());
                var right = budget.reserveMemory("right-page", 14, new Cancellation());
                var codec = budget.reserveMemory("codec", 4, new Cancellation());
                var disk = budget.reserveDisk("results", 100, new Cancellation())) {
            assertThrows(
                    TempBudget.Limit.class,
                    () -> budget.reserveMemory("next-page", 1, new Cancellation()));
            left.close();
            try (var next = budget.reserveMemory("next-page", 14, new Cancellation())) {
                assertEquals(32, budget.snapshot().memory());
            }
        }
    }

    @Test
    void rejectedOverflowAndCancellationNeverPartiallyCharge() throws Exception {
        try (var budget = new TempBudget(32, 100);
                var disk = budget.reserveDisk("input", 10, new Cancellation())) {
            assertThrows(
                    TempBudget.Limit.class, () -> disk.grow(Long.MAX_VALUE, new Cancellation()));
            assertThrows(IllegalArgumentException.class, () -> disk.shrink(11));
            assertThrows(IllegalArgumentException.class, () -> disk.grow(-1, new Cancellation()));
            var canceled = new Cancellation();
            canceled.cancel();
            assertThrows(
                    java.util.concurrent.CancellationException.class, () -> disk.grow(5, canceled));
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> budget.reserveDisk("other", 5, canceled));
            assertEquals(10, budget.snapshot().disk());
            assertEquals(1, budget.snapshot().reservations());
        }
    }

    @Test
    void budgetCloseDetectsResourcesAndCanRetryAfterCleanup() throws Exception {
        var budget = new TempBudget(32, 100);
        var disk = budget.reserveDisk("retained-input", 0, new Cancellation());
        assertThrows(IllegalStateException.class, budget::close);
        disk.grow(10, new Cancellation());
        disk.shrink(4);
        assertEquals(6, disk.size());
        disk.close();
        disk.close();
        budget.close();
        budget.close();
        assertThrows(
                IllegalStateException.class,
                () -> budget.reserveDisk("late", 1, new Cancellation()));
        assertThrows(IllegalStateException.class, () -> disk.grow(1, new Cancellation()));
        assertEquals(0, budget.snapshot().disk());
    }

    @Test
    void concurrentReservationsHaveOneWinnerWithoutWaiting() throws Exception {
        try (var budget = new TempBudget(32, 100)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var attempted = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var accepted = new AtomicInteger();
            var limited = new AtomicInteger();
            var task =
                    (java.util.function.Supplier<Void>)
                            () -> {
                                TempBudget.Reservation reservation = null;
                                try {
                                    ready.countDown();
                                    go.await();
                                    try {
                                        reservation =
                                                budget.reserveDisk(
                                                        "concurrent-input", 60, new Cancellation());
                                        accepted.incrementAndGet();
                                    } catch (TempBudget.Limit expected) {
                                        limited.incrementAndGet();
                                    } finally {
                                        attempted.countDown();
                                    }
                                    release.await();
                                    return null;
                                } catch (InterruptedException failure) {
                                    throw new java.util.concurrent.CompletionException(failure);
                                } finally {
                                    if (reservation != null) {
                                        reservation.close();
                                    }
                                }
                            };
            var a = CompletableFuture.supplyAsync(task);
            var b = CompletableFuture.supplyAsync(task);
            try {
                assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
                go.countDown();
                assertTrue(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, accepted.get());
                assertEquals(1, limited.get());
                assertEquals(60, budget.snapshot().disk());
            } finally {
                go.countDown();
                release.countDown();
                CompletableFuture.allOf(a, b).join();
            }
            assertEquals(0, budget.snapshot().disk());
        }
    }

    @Test
    void zeroByteBookkeepingCannotGrowWithoutBound() throws Exception {
        try (var budget = new TempBudget(32, 100)) {
            var reservations = new java.util.ArrayList<TempBudget.Reservation>();
            try {
                for (int i = 0; i < 1024; i++) {
                    reservations.add(budget.reserveDisk("empty-file", 0, new Cancellation()));
                }
                assertThrows(
                        TempBudget.Limit.class,
                        () -> budget.reserveDisk("extra-file", 0, new Cancellation()));
            } finally {
                reservations.forEach(TempBudget.Reservation::close);
            }
            assertEquals(0, budget.snapshot().reservations());
        }
    }
}
