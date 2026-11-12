package com.vibeminer.store;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class Async {
    /**
     * Admission refused because a waiting queue is at its hard bound. Distinct from {@code
     * OBJECT_STORE_LIMIT} (a request over a size limit): callers treat it as a transient store
     * error, never as an absent object.
     */
    static final String QUEUE_FULL = "OBJECT_STORE_QUEUE_FULL";

    private static final int WAITING = 0, STARTED = 1, CANCELED = 2;

    private Async() {}

    static ThreadPoolExecutor pool(String name, int threads) {
        return pool(name, threads, 64);
    }

    /**
     * {@code threads} workers; up to {@code queued} accepted tasks wait for one without blocking.
     */
    static ThreadPoolExecutor pool(String name, int threads, int queued) {
        AtomicInteger number = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queued),
                r -> {
                    Thread t = new Thread(r, name + "-" + number.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Takes every task still waiting in the queue of {@code pool}, which must be shut down, so the
     * caller can fail them without running their work. Tasks are removed through the pool, never by
     * draining its queue directly: a worker that finished a task after the shutdown saw a queued
     * task, so it went to wait for one, and draining the queue under it would leave it waiting
     * forever and the pool never terminating. {@link ThreadPoolExecutor#remove} and {@link
     * ThreadPoolExecutor#purge} let the pool wake such a worker once its queue is empty.
     */
    static java.util.List<Runnable> takeWaiting(ThreadPoolExecutor pool) {
        if (!pool.isShutdown()) {
            throw new IllegalStateException("Shut the pool down first");
        }
        var waiting = new java.util.ArrayList<Runnable>();
        for (Runnable task : pool.getQueue().toArray(new Runnable[0])) {
            if (pool.remove(task)) {
                waiting.add(task);
            }
        }
        pool.purge(); // Wakes an idle worker even when nothing was left to remove.
        return waiting;
    }

    interface Work<T> {
        T run() throws Exception;
    }

    static <T> CompletableFuture<T> submit(Executor executor, Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(
                    () -> {
                        try {
                            result.complete(work.run());
                        } catch (Throwable e) {
                            result.completeExceptionally(e);
                        }
                    });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(
                    new StoreException("OBJECT_STORE_LIMIT", "Worker queue is full or closed", e));
        }
        return result;
    }

    /**
     * Queues {@code work} on {@code pool}; the caller waits asynchronously for a worker. Canceling
     * {@code cancellation} while the work waits completes the result with a {@link
     * CancellationException} promptly, on a virtual thread rather than the canceling one, and frees
     * its queue slot; a worker reaching a canceled task skips it. A full queue fails with {@link
     * #QUEUE_FULL}; a shut-down pool fails with {@code closed}.
     */
    static <T> CompletableFuture<T> admit(
            ThreadPoolExecutor pool,
            Cancellation cancellation,
            java.util.function.Supplier<StoreException> closed,
            Work<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (cancellation.isCanceled()) {
            result.completeExceptionally(new CancellationException());
            return result;
        }
        // WAITING until a worker claims the task or cancellation does; only one of them wins.
        AtomicInteger state = new AtomicInteger(WAITING);
        java.util.concurrent.atomic.AtomicReference<Cancellation.Registration> registration =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task =
                () -> {
                    if (!state.compareAndSet(WAITING, STARTED)) {
                        return; // Canceled while waiting.
                    }
                    var registered = registration.getAndSet(null);
                    if (registered != null) {
                        registered.close();
                    }
                    try {
                        result.complete(work.run());
                    } catch (Throwable e) {
                        result.completeExceptionally(e);
                    }
                };
        try {
            pool.execute(task);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(
                    pool.isShutdown()
                            ? closed.get()
                            : new StoreException(QUEUE_FULL, "Store work queue is full", e));
            return result;
        }
        var registered =
                cancellation.onCancel(
                        () -> {
                            if (state.compareAndSet(WAITING, CANCELED)) {
                                // Off the canceling thread: queue removal is linear and the
                                // result's callbacks must not run on, say, the FX thread.
                                Thread.ofVirtual()
                                        .name("vimi-store-cancel")
                                        .start(
                                                () -> {
                                                    pool.remove(task);
                                                    result.completeExceptionally(
                                                            new CancellationException());
                                                });
                            }
                        });
        registration.set(registered);
        if (state.get() != WAITING && registration.getAndSet(null) != null) {
            registered.close(); // Claimed or canceled before registration finished.
        }
        return result;
    }
}
