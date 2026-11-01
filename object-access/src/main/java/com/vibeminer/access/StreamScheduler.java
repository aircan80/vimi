package com.vibeminer.access;

import com.vibeminer.access.SourceTypes.FetchPriority;
import com.vibeminer.access.SourceTypes.Options;
import com.vibeminer.network.NetworkTypes.Failure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded priority and byte admission with an interactive reserve; no admission threads wait. */
public final class StreamScheduler {
    private final Options options;
    private final Object gate = new Object();
    private final ArrayDeque<Ticket<?>> interactive = new ArrayDeque<>(),
            background = new ArrayDeque<>();
    private final ThreadPoolExecutor workers;
    private int backgroundLimit, backgroundActive, interactiveActive, streak;
    private long backgroundBytes, interactiveBytes;
    private boolean closed;
    private CompletableFuture<Void> closing;

    public StreamScheduler(Options options) {
        this.options = options;
        backgroundLimit = options.minBackground();
        int count = options.maxBackground() + options.interactive();
        workers =
                new ThreadPoolExecutor(
                        count,
                        count,
                        30,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(count * 2),
                        Thread.ofPlatform().daemon().name("object-access-", 0).factory());
        workers.allowCoreThreadTimeOut(true);
    }

    public int backgroundLimit() {
        synchronized (gate) {
            return backgroundLimit;
        }
    }

    public int backgroundActive() {
        synchronized (gate) {
            return backgroundActive;
        }
    }

    public int interactiveActive() {
        synchronized (gate) {
            return interactiveActive;
        }
    }

    public long backgroundBytes() {
        synchronized (gate) {
            return backgroundBytes;
        }
    }

    public long interactiveBytes() {
        synchronized (gate) {
            return interactiveBytes;
        }
    }

    public final class Ticket<T> {
        private FetchPriority priority;
        private final long bytes;
        private final Cancellation cancellation;
        private Supplier<T> work;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private Cancellation.Registration registration;
        private boolean running, finished, reserve, byteInteractive;
        private long charged;

        private Ticket(
                FetchPriority priority, long bytes, Cancellation cancellation, Supplier<T> work) {
            this.priority = priority;
            this.bytes = bytes;
            this.cancellation = cancellation;
            this.work = work;
        }

        public CompletableFuture<T> future() {
            return future;
        }

        public FetchPriority priority() {
            synchronized (gate) {
                return priority;
            }
        }

        public void promote() {
            List<Runnable> starts;
            synchronized (gate) {
                if (priority == FetchPriority.INTERACTIVE || finished) {
                    return;
                }
                priority = FetchPriority.INTERACTIVE;
                if (!running && background.remove(this)) {
                    interactive.addLast(this);
                }
                starts = grant();
            }
            starts.forEach(Runnable::run);
        }
    }

    public <T> Ticket<T> submit(
            FetchPriority priority, long bytes, Cancellation cancellation, Supplier<T> work) {
        if (bytes < 1) {
            throw new IllegalArgumentException("A byte reservation must be positive");
        }
        Ticket<T> ticket = new Ticket<>(priority, bytes, cancellation, work);
        List<Runnable> starts;
        synchronized (gate) {
            if (closed || interactive.size() + background.size() >= options.queued()) {
                ticket.finished = true;
                ticket.work = null;
                ticket.future.completeExceptionally(
                        new IllegalStateException(
                                closed ? "OBJECT_ACCESS_CLOSED" : "OBJECT_ACCESS_LIMIT"));
                return ticket;
            }
            (priority == FetchPriority.INTERACTIVE ? interactive : background).addLast(ticket);
            ticket.registration = cancellation.onCancel(() -> cancel(ticket));
            starts = grant();
        }
        starts.forEach(Runnable::run);
        return ticket;
    }

    private void cancel(Ticket<?> ticket) {
        List<Runnable> starts;
        synchronized (gate) {
            if (ticket.running || ticket.finished) {
                return;
            }
            interactive.remove(ticket);
            background.remove(ticket);
            ticket.finished = true;
            ticket.work = null;
            if (ticket.registration != null) {
                ticket.registration.close();
            }
            starts = grant();
            completeClosingIfDrained();
        }
        Thread.startVirtualThread(
                () ->
                        ticket.future.completeExceptionally(
                                new java.util.concurrent.CancellationException()));
        starts.forEach(Runnable::run);
    }

    private List<Runnable> grant() {
        List<Runnable> starts = new ArrayList<>();
        while (!interactive.isEmpty()) {
            Ticket<?> ticket = interactive.peekFirst();
            boolean reserve = interactiveActive < options.interactive();
            if (!reserve && backgroundActive >= backgroundLimit) {
                break;
            }
            long capacity = options.interactiveInFlightBytes();
            long charge = Math.min(ticket.bytes, capacity);
            if (charge > capacity - interactiveBytes) {
                break;
            }
            interactive.removeFirst();
            accept(ticket, reserve, charge, starts);
        }
        while (interactive.isEmpty()
                && !background.isEmpty()
                && backgroundActive < backgroundLimit) {
            Ticket<?> ticket = background.peekFirst();
            long charge = Math.min(ticket.bytes, options.inFlightBytes());
            if (charge > options.inFlightBytes() - backgroundBytes) {
                break;
            }
            background.removeFirst();
            accept(ticket, false, charge, starts);
        }
        return starts;
    }

    private void accept(Ticket<?> ticket, boolean reserve, long charge, List<Runnable> starts) {
        if (ticket.finished) {
            return;
        }
        ticket.running = true;
        ticket.reserve = reserve;
        ticket.charged = charge;
        ticket.byteInteractive = ticket.priority == FetchPriority.INTERACTIVE;
        if (reserve) {
            interactiveActive++;
        } else {
            backgroundActive++;
        }
        if (ticket.byteInteractive) {
            interactiveBytes += charge;
        } else {
            backgroundBytes += charge;
        }
        if (ticket.registration != null) {
            ticket.registration.close();
            ticket.registration = null;
        }
        starts.add(() -> workers.execute(() -> run(ticket)));
    }

    private <T> void run(Ticket<T> ticket) {
        T result = null;
        Throwable error = null;
        try {
            ticket.cancellation.check();
            result = ticket.work.get();
        } catch (Throwable e) {
            while (e instanceof CompletionException && e.getCause() != null) {
                e = e.getCause();
            }
            error = e;
        }
        T value = result;
        Throwable failure = error;
        Thread.startVirtualThread(
                () -> {
                    if (failure == null) {
                        ticket.future.complete(value);
                    } else {
                        ticket.future.completeExceptionally(failure);
                    }
                });
        while (!ticket.future.isDone()) {
            java.util.concurrent.locks.LockSupport.parkNanos(100_000);
        }
        List<Runnable> starts;
        synchronized (gate) {
            ticket.finished = true;
            ticket.work = null;
            if (ticket.reserve) {
                interactiveActive--;
            } else {
                backgroundActive--;
            }
            if (ticket.byteInteractive) {
                interactiveBytes -= ticket.charged;
            } else {
                backgroundBytes -= ticket.charged;
            }
            starts = grant();
            completeClosingIfDrained();
        }
        starts.forEach(Runnable::run);
    }

    public void observe(Failure failure) {
        synchronized (gate) {
            if (failure == Failure.TIMEOUT
                    || failure == Failure.NETWORK
                    || failure == Failure.HTTP_STATUS) {
                streak = 0;
                backgroundLimit =
                        Math.max(
                                options.minBackground(),
                                backgroundLimit - Math.max(1, backgroundLimit / 4));
            } else if (failure == null && ++streak >= backgroundLimit) {
                streak = 0;
                backgroundLimit = Math.min(options.maxBackground(), backgroundLimit + 1);
            }
        }
    }

    public CompletableFuture<Void> closeAsync() {
        synchronized (gate) {
            if (closing != null) {
                return closing;
            }
            closed = true;
            closing = new CompletableFuture<>();
            completeClosingIfDrained();
            return closing;
        }
    }

    private void completeClosingIfDrained() {
        if (closing != null
                && interactive.isEmpty()
                && background.isEmpty()
                && interactiveActive == 0
                && backgroundActive == 0) {
            workers.shutdown();
            CompletableFuture<Void> future = closing;
            Thread.startVirtualThread(() -> future.complete(null));
        }
    }
}
