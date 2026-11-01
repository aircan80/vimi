package com.vibeminer.builds;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildOpenResult;
import com.vibeminer.builds.BuildTypes.BuildOpenState;
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

/** Bounded LRU and FIFO work admission, one flight per build, cancellation isolated per waiter. */
final class OpenCache {
    private static final class Flight {
        final Cancellation token = new Cancellation();
        final CompletableFuture<BuildOpenResult> result = new CompletableFuture<>();
        int waiters;
        boolean done;
        volatile Runnable work;
    }

    private final Object gate = new Object();
    private final int capacity;
    private final Map<BuildRef, Build> opened = new LinkedHashMap<>(16, .75f, true);
    private final Map<BuildRef, Flight> flights = new HashMap<>();
    private final ThreadPoolExecutor workers;
    private CompletableFuture<Void> closing;

    OpenCache(int capacity, int concurrent, int queued) {
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
                        Thread.ofPlatform().daemon().name("build-open-", 0).factory());
        workers.allowCoreThreadTimeOut(true);
    }

    CompletableFuture<BuildOpenResult> open(
            BuildRef build, Function<Cancellation, BuildOpenResult> action, Cancellation token) {
        if (token.isCanceled()) {
            return CompletableFuture.failedFuture(new CancellationException());
        }
        Flight flight;
        boolean start = false;
        synchronized (gate) {
            if (closing != null) {
                return CompletableFuture.failedFuture(
                        new BuildOpenException("BUILD_OPEN_CLOSED", "Build opener is closed"));
            }
            Build cached = opened.get(build);
            if (cached != null) {
                return CompletableFuture.completedFuture(
                        new BuildOpenResult(
                                build, BuildOpenState.OPEN, cached, null, null, null, null));
            }
            flight = flights.get(build);
            if (flight == null) {
                flight = new Flight();
                Flight created = flight;
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
        Flight shared = flight;
        Waiter waiter = new Waiter();
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

    private void finish(BuildRef build, Flight flight, BuildOpenResult result, Throwable error) {
        synchronized (gate) {
            if (flight.done) {
                return;
            }
            flight.done = true;
            if (flights.get(build) != flight) {
                error = new CancellationException();
            } else {
                flights.remove(build);
                if (error == null
                        && result.state() == BuildOpenState.OPEN
                        && result.opened() != null) {
                    opened.put(build, result.opened());
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
                return closing;
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
        return closing;
    }

    private static final class Waiter {
        final AtomicBoolean done = new AtomicBoolean();
        final CompletableFuture<BuildOpenResult> future = new CompletableFuture<>();
        volatile Cancellation.Registration registration;

        void closeRegistration() {
            var current = registration;
            if (current != null) {
                current.close();
            }
        }

        void publish(BuildOpenResult result, Throwable failure) {
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
