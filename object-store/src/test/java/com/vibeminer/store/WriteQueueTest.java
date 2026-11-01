package com.vibeminer.store;

import static com.vibeminer.store.StoreAssertions.failure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class WriteQueueTest {
    private final WriteQueue queue = new WriteQueue(2, 2, 16);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() throws Exception {
        release.countDown();
        queue.shutdown();
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
    }

    private CompletableFuture<Integer> block(long bytes) throws Exception {
        var result =
                queue.submit(
                        bytes,
                        Cancellation.NONE,
                        () -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return 0;
                        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return result;
    }

    @Test
    void saturationWaitsWithoutRunningWorkOnSubmittingThreadAndPreservesFifo() throws Exception {
        var first = block(1);
        Thread caller = Thread.currentThread();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Integer>> requests = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            int value = i;
            requests.add(
                    queue.submit(
                            1,
                            Cancellation.NONE,
                            () -> {
                                assertFalse(Thread.currentThread() == caller);
                                order.add(value);
                                return value;
                            }));
        }
        assertEquals(2, queue.state().queued());
        assertEquals(2, queue.state().waiting());
        assertEquals(5, queue.state().retainedBytes());
        assertTrue(requests.stream().noneMatch(CompletableFuture::isDone));
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        for (var result : requests) {
            result.get(5, TimeUnit.SECONDS);
        }
        assertEquals(List.of(1, 2, 3, 4), order);
        assertEquals(0, queue.state().retainedBytes());
    }

    @Test
    void waiterBudgetRejectsOnlyBeyondTheAdditionalWaitingCapacity() throws Exception {
        block(0);
        for (int i = 0; i < 4; i++) {
            queue.submit(0, Cancellation.NONE, () -> 1);
        }
        failure(queue.submit(0, Cancellation.NONE, () -> 1), "OBJECT_STORE_LIMIT");
        assertEquals(2, queue.state().waiting());
    }

    @Test
    void payloadBudgetIncludesActiveQueuedAndWaitingInputs() throws Exception {
        block(14);
        queue.submit(1, Cancellation.NONE, () -> 1);
        queue.submit(1, Cancellation.NONE, () -> 2);
        failure(queue.submit(1, Cancellation.NONE, () -> 3), "OBJECT_STORE_LIMIT");
        assertEquals(16, queue.state().retainedBytes());
    }

    @Test
    void cancellationRemovesOnlyTheWaitingRequestAndFreesItsReservation() throws Exception {
        var first = block(1);
        queue.submit(1, Cancellation.NONE, () -> 1);
        queue.submit(1, Cancellation.NONE, () -> 2);
        Cancellation canceled = new Cancellation();
        AtomicBoolean ran = new AtomicBoolean();
        var dropped =
                queue.submit(
                        4,
                        canceled,
                        () -> {
                            ran.set(true);
                            return 3;
                        });
        var last = queue.submit(1, Cancellation.NONE, () -> 4);
        canceled.cancel();
        assertThrows(CancellationException.class, dropped::join);
        assertEquals(1, queue.state().waiting());
        assertEquals(4, queue.state().retainedBytes());
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertEquals(4, last.get(5, TimeUnit.SECONDS));
        assertFalse(ran.get());
    }

    @Test
    void shutdownRejectsUnacceptedWaitersAndDrainsAcceptedWork() throws Exception {
        var first = block(1);
        var accepted = queue.submit(1, Cancellation.NONE, () -> 1);
        var alsoAccepted = queue.submit(1, Cancellation.NONE, () -> 2);
        var waiting = queue.submit(1, Cancellation.NONE, () -> 3);
        queue.shutdown();
        failure(waiting, "OBJECT_STORE_CLOSED");
        failure(queue.submit(0, Cancellation.NONE, () -> 4), "OBJECT_STORE_CLOSED");
        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        assertEquals(1, accepted.get(5, TimeUnit.SECONDS));
        assertEquals(2, alsoAccepted.get(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, queue.state().retainedBytes());
    }

    @Test
    void cancelingTheResultFutureDoesNotCancelAcceptedIo() throws Exception {
        block(0);
        AtomicBoolean ran = new AtomicBoolean();
        var result =
                queue.submit(
                        0,
                        Cancellation.NONE,
                        () -> {
                            ran.set(true);
                            return 1;
                        });
        result.cancel(false);
        release.countDown();
        queue.shutdown();
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(ran.get());
    }

    @Test
    void completionContinuationCanWaitForAnotherWriteWithoutBlockingWriter() throws Exception {
        var first = block(0);
        var chained = first.thenApply(value -> queue.submit(0, Cancellation.NONE, () -> 42).join());
        release.countDown();
        assertEquals(42, chained.get(5, TimeUnit.SECONDS));
    }

    @Test
    void completionContinuationCanCloseItsQueue() throws Exception {
        var first = block(0);
        var closed =
                first.thenApply(
                        value -> {
                            queue.shutdown();
                            try {
                                assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(e);
                            }
                            return value;
                        });
        release.countDown();
        assertEquals(0, closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void asynchronousPreparationDoesNotAllowLaterRequestsToOvertake() throws Exception {
        CompletableFuture<Void> prepared = new CompletableFuture<>();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        var first =
                queue.submit(
                        1,
                        Cancellation.NONE,
                        () -> {
                            order.add(1);
                            return 1;
                        },
                        () -> {},
                        prepared);
        var second =
                queue.submit(
                        1,
                        Cancellation.NONE,
                        () -> {
                            order.add(2);
                            return 2;
                        });
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertFalse(queue.hasReadyWork());
        prepared.complete(null);
        assertEquals(1, first.get(5, TimeUnit.SECONDS));
        assertEquals(2, second.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), order);
    }

    @Test
    void waitingAdmissionDoesNotStartPreparationAndCancellationRemovesIt() throws Exception {
        block(0);
        queue.submit(0, Cancellation.NONE, () -> 1);
        queue.submit(0, Cancellation.NONE, () -> 2);
        Cancellation token = new Cancellation();
        AtomicBoolean started = new AtomicBoolean();
        var result =
                queue.submit(1, token, () -> 3, () -> started.set(true), new CompletableFuture<>());
        assertFalse(started.get());
        token.cancel();
        assertThrows(CancellationException.class, result::join);
        assertFalse(started.get());
        assertEquals(0, queue.state().retainedBytes());
    }

    @Test
    void shutdownDrainsAdmittedPreparationBeforeStoppingWriter() throws Exception {
        CompletableFuture<Void> prepared = new CompletableFuture<>();
        var accepted = queue.submit(1, Cancellation.NONE, () -> 42, () -> {}, prepared);
        queue.shutdown();
        assertFalse(queue.awaitTermination(0, TimeUnit.SECONDS));
        prepared.complete(null);
        assertEquals(42, accepted.get(5, TimeUnit.SECONDS));
        assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, queue.state().retainedBytes());
    }

    @Test
    void preparationFailurePublishesFailureAndDoesNotStrandTheQueue() throws Exception {
        CompletableFuture<Void> prepared = new CompletableFuture<>();
        var failed =
                queue.submit(
                        1,
                        Cancellation.NONE,
                        () -> {
                            prepared.join();
                            return 1;
                        },
                        () -> {
                            throw new IllegalStateException("Synthetic preparation failure");
                        },
                        prepared);
        assertThrows(CompletionException.class, failed::join);
        assertEquals(42, queue.submit(1, Cancellation.NONE, () -> 42).get(5, TimeUnit.SECONDS));
        assertEquals(0, queue.state().retainedBytes());
    }
}
