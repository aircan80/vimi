package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Bounded authenticated metadata contexts; one flight per build and independently cancelable
 * waiters.
 */
final class MetadataCache<T> {
    private static final class Flight<T> {
        final Cancellation token = new Cancellation();
        final CompletableFuture<T> result = new CompletableFuture<>();
        int waiters;
        boolean done;
        volatile Runnable work;
    }

    private final Object gate = new Object();
    private final int capacity;
    private final Map<BuildRef, T> opened = new LinkedHashMap<>(16, .75f, true);
    private final Map<BuildRef, Flight<T>> flights = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private CompletableFuture<Void> closing;

    MetadataCache(int capacity, int concurrent, int queued) {
        if (capacity < 1 || concurrent < 1 || queued < 1) {
            throw new IllegalArgumentException("Positive open bounds required");
        }
        this.capacity = capacity;
        workers =
                new ThreadPoolExecutor(
                        concurrent,
                        concurrent,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(queued),
                        Thread.ofPlatform().daemon().name("build-metadata-", 0).factory());
        workers.allowCoreThreadTimeOut(true);
    }

    CompletableFuture<T> open(
            BuildRef build, Function<Cancellation, T> action, Cancellation token) {
        if (token.isCanceled()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        Flight<T> flight;
        boolean start = false;
        synchronized (gate) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new BuildOpenException("BUILD_OPEN_CLOSED", "Build opener is closed"));
            }
            T cached = opened.get(build);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
            flight = flights.get(build);
            if (flight == null) {
                flight = new Flight<>();
                Flight<T> created = flight;
                created.work =
                        () -> {
                            try {
                                created.token.check();
                                finish(build, created, action.apply(created.token), null);
                            } catch (Throwable e) {
                                finish(build, created, null, e);
                            }
                        };
                flights.put(build, flight);
                start = true;
            }
            flight.waiters++;
        }
        Flight<T> shared = flight;
        Waiter<T> waiter = new Waiter<>();
        waiter.registration =
                token.onCancel(
                        () -> {
                            if (!waiter.done.compareAndSet(false, true)) {
                                return;
                            }
                            boolean abandon = false;
                            synchronized (gate) {
                                shared.waiters--;
                                if (shared.waiters == 0
                                        && flights.get(build) == shared
                                        && !shared.done) {
                                    flights.remove(build);
                                    abandon = true;
                                }
                            }
                            if (abandon) {
                                shared.token.cancel();
                                if (shared.work != null && workers.remove(shared.work)) {
                                    finish(build, shared, null, new CancellationException());
                                }
                            }
                            waiter.closeRegistration();
                            waiter.publish(null, new CancellationException());
                        });
        if (waiter.done.get()) {
            waiter.closeRegistration();
        }
        shared.result.whenComplete(
                (value, error) -> {
                    if (waiter.done.compareAndSet(false, true)) {
                        waiter.closeRegistration();
                        waiter.publish(value, error);
                    }
                });
        if (start) {
            try {
                workers.execute(shared.work);
                if (shared.token.isCanceled() && workers.remove(shared.work)) {
                    finish(build, shared, null, new CancellationException());
                }
            } catch (RejectedExecutionException e) {
                finish(
                        build,
                        shared,
                        null,
                        new BuildOpenException(
                                workers.isShutdown() ? "BUILD_OPEN_CLOSED" : "BUILD_OPEN_LIMIT",
                                "Build open admission is full or closed"));
            }
        }
        return waiter.future;
    }

    private void finish(BuildRef build, Flight<T> flight, T result, Throwable error) {
        synchronized (gate) {
            if (flight.done) {
                return;
            }
            flight.done = true;
            if (flights.get(build) != flight) {
                error = new CancellationException();
            } else {
                flights.remove(build);
                if (error == null && result != null) {
                    opened.put(build, result);
                    while (opened.size() > capacity) {
                        opened.remove(opened.keySet().iterator().next());
                    }
                }
            }
        }
        if (error == null) {
            flight.result.complete(result);
        } else {
            flight.result.completeExceptionally(error);
        }
    }

    CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closing != null) {
                return closing.copy();
            }
            closing = new CompletableFuture<>();
            workers.shutdown();
        }
        Thread.startVirtualThread(
                () -> {
                    try {
                        while (!workers.awaitTermination(1, TimeUnit.DAYS)) {
                            /* Drain accepted opens. */
                        }
                        synchronized (gate) {
                            opened.clear();
                        }
                        closing.complete(null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closing.completeExceptionally(e);
                    }
                });
        return closing.copy();
    }

    private static final class Waiter<T> {
        final AtomicBoolean done = new AtomicBoolean();
        final CompletableFuture<T> future = new CompletableFuture<>();
        volatile Cancellation.Registration registration;

        void closeRegistration() {
            var current = registration;
            if (current != null) {
                current.close();
            }
        }

        void publish(T result, Throwable failure) {
            Throwable error = failure;
            while (error instanceof CompletionException && error.getCause() != null) {
                error = error.getCause();
            }
            Throwable actual = error;
            Thread.startVirtualThread(
                    () -> {
                        if (actual == null) {
                            future.complete(result);
                        } else {
                            future.completeExceptionally(actual);
                        }
                    });
            while (!future.isDone()) {
                java.util.concurrent.locks.LockSupport.parkNanos(100000);
            }
        }
    }
}
