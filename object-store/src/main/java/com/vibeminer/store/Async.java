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
     * CancellationException} at once and frees its queue slot. A full queue fails with {@link
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
                                pool.remove(task);
                                result.completeExceptionally(new CancellationException());
                            }
                        });
        registration.set(registered);
        if (state.get() != WAITING && registration.getAndSet(null) != null) {
            registered.close(); // Claimed or canceled before registration finished.
        }
        return result;
    }
}
