package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded serialized actors; no layer 3 disk/source wait runs on a GUI thread. */
final class CatalogTasks {
    private static final CatalogTasks OPENERS = new CatalogTasks("catalog-open", 2, 64, () -> {});
    private final ThreadPoolExecutor workers;
    private final Runnable cleanup;
    private CompletableFuture<Void> closing;

    CatalogTasks(String name, int threads, int capacity, Runnable cleanup) {
        this.cleanup = cleanup;
        workers =
                new ThreadPoolExecutor(
                        threads,
                        threads,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(capacity),
                        Thread.ofPlatform().daemon().name(name + "-", 0).factory());
        workers.allowCoreThreadTimeOut(true);
    }

    static <T> CompletableFuture<T> open(Cancellation token, Supplier<T> action) {
        return OPENERS.submit(token, action);
    }

    <T> CompletableFuture<T> submit(Cancellation token, Supplier<T> action) {
        if (token.isCanceled()) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        }
        Task<T> task = new Task<>(token, action);
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
                    workers.isShutdown()
                            ? new IllegalStateException("Catalog is closed")
                            : new CatalogStoreException(
                                    "CATALOG_LIMIT", "Catalog request queue is full"));
        }
        return task.result;
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
                            /* Drain accepted calls. */
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

    static Throwable unwrap(Throwable e) {
        while (e instanceof CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }

    private static final class Task<T> implements Runnable {
        private final Cancellation token;
        private Supplier<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private Cancellation.Registration registration;
        private boolean finished;

        Task(Cancellation token, Supplier<T> action) {
            this.token = token;
            this.action = action;
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
            try {
                token.check();
                finish(action.get(), null);
            } catch (Throwable e) {
                finish(null, unwrap(e));
            }
        }

        synchronized void finish(T value, Throwable error) {
            if (finished) {
                return;
            }
            finished = true;
            action = null;
            if (registration != null) {
                registration.close();
                registration = null;
            }
            Thread.startVirtualThread(
                    () -> {
                        if (error == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(error);
                        }
                    });
            while (!result.isDone()) {
                java.util.concurrent.locks.LockSupport.parkNanos(100000);
            }
        }
    }
}
