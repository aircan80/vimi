package com.vibeminer.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Closing a store fails reads still waiting for a worker. The close once hung: a worker that
 * finished its read after the shutdown saw a waiting read, went to take it, and close drained the
 * queue under it, so the worker waited forever and the pool never terminated.
 */
final class TakeWaitingTest {
    /** A queue that holds one worker inside {@code take()} until the test lets it go on. */
    private static final class GatedQueue extends ArrayBlockingQueue<Runnable> {
        private static final long serialVersionUID = 1L;
        final transient CountDownLatch taking = new CountDownLatch(1),
                proceed = new CountDownLatch(1);
        transient volatile boolean gate;

        GatedQueue() {
            super(8);
        }

        @Override
        public Runnable take() throws InterruptedException {
            if (gate) {
                gate = false;
                taking.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        if (proceed.await(60, TimeUnit.SECONDS)) {
                            break;
                        }
                    } catch (InterruptedException wake) {
                        interrupted = true; // Kept for the real take below.
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.take();
        }
    }

    @Test
    void aWorkerAlreadyGoingForAWaitingTaskStillFinishesWhenCloseTakesIt() throws Exception {
        var queue = new GatedQueue();
        var pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, queue);
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waitingRan = new AtomicBoolean();
        try {
            pool.execute(
                    () -> {
                        running.countDown();
                        await(release);
                    });
            assertTrue(running.await(10, TimeUnit.SECONDS));
            Runnable waiting = () -> waitingRan.set(true);
            pool.execute(waiting);
            pool.shutdown();
            // The running task ends after the shutdown; a task still waits, so the worker goes on
            // to take it and is held there, past the pool's check, while close takes the task.
            queue.gate = true;
            release.countDown();
            assertTrue(queue.taking.await(10, TimeUnit.SECONDS));
            var taken = Async.takeWaiting(pool);
            assertEquals(java.util.List.of(waiting), taken);
            queue.proceed.countDown();
            assertTrue(
                    pool.awaitTermination(10, TimeUnit.SECONDS),
                    "the worker finishes instead of waiting forever on the emptied queue");
            assertFalse(waitingRan.get(), "the taken task never ran on a worker");
        } finally {
            queue.proceed.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void onlyAShutDownPoolGivesUpItsWaitingTasks() {
        var pool = Async.pool("test", 1);
        try {
            assertThrows(IllegalStateException.class, () -> Async.takeWaiting(pool));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(60, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
