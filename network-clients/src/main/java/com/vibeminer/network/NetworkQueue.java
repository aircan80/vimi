package com.vibeminer.network;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounded admission; canceled queued requests release their slots without blocked admission
 * threads.
 */
final class NetworkQueue {
    private final ThreadPoolExecutor workers;
    private final Runnable cleanup;
    private CompletableFuture<Void> closing;

    NetworkQueue(String name, int concurrent, int queued, Runnable cleanup) {
        this.cleanup = cleanup;
        workers =
                new ThreadPoolExecutor(
                        concurrent,
                        concurrent,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(queued),
                        Thread.ofPlatform().daemon().name(name + "-", 0).factory());
        workers.allowCoreThreadTimeOut(true);
    }

    <T> CompletableFuture<T> submit(Cancellation token, Supplier<T> work) {
        if (token.isCanceled()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        }
        Task<T> task = new Task<>(token, work);
        task.register(
                token.onCancel(
                        () -> {
                            if (workers.remove(task)) {
                                task.finish(null, new java.util.concurrent.CancellationException());
                            }
                        }));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException e) {
            task.finish(
                    null,
                    new NetworkException(
                            workers.isShutdown() ? "NETWORK_CLOSED" : "NETWORK_LIMIT",
                            workers.isShutdown()
                                    ? "Network client is closed"
                                    : "Network admission queue is full"));
        }
        return task.future;
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) {
            return closing;
        }
        workers.shutdown();
        closing = new CompletableFuture<>();
        CompletableFuture<Void> result = closing;
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!workers.awaitTermination(1, TimeUnit.DAYS)) {
                            /* Drain accepted bounded work. */
                        }
                        cleanup.run();
                        result.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        result.completeExceptionally(e);
                    } catch (Throwable e) {
                        result.completeExceptionally(e);
                    }
                });
        return result;
    }

    private static final class Task<T> implements Runnable {
        private final Cancellation token;
        private Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private Cancellation.Registration registration;
        private boolean finished;

        Task(Cancellation token, Supplier<T> work) {
            this.token = java.util.Objects.requireNonNull(token);
            this.work = work;
        }

        synchronized void register(Cancellation.Registration value) {
            if (finished) {
                value.close();
            } else {
                registration = value;
            }
        }

        @Override
        public void run() {
            T result;
            try {
                token.check();
                result = work.get();
            } catch (Throwable e) {
                while (e instanceof CompletionException && e.getCause() != null) {
                    e = e.getCause();
                }
                finish(null, e);
                return;
            }
            finish(result, null);
        }

        synchronized void finish(T value, Throwable error) {
            if (finished) {
                return;
            }
            finished = true;
            work = null;
            if (registration != null) {
                registration.close();
                registration = null;
            }
            Thread.startVirtualThread(
                    () -> {
                        if (error == null) {
                            future.complete(value);
                        } else {
                            future.completeExceptionally(error);
                        }
                    });
            // Drain means accepted futures are published, not that user continuations return.
            while (!future.isDone()) {
                java.util.concurrent.locks.LockSupport.parkNanos(100_000);
            }
        }
    }
}
