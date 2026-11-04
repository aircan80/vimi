package com.vimi.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Four issued legacy invocations, with independent logical views and observed provider lifetimes.
 * This is not an IO-drain certificate for arbitrary providers that finish their future early.
 * Native-only promotion additionally audits the provider contract and waits the separate terrain
 * texture source's actual idle barrier. Suppliers own cleanup of unreturned work if they throw.
 * A paused owner admits no new invocations; queued wrappers retire without calling providers.
 * Returned copies may be canceled without affecting the actual future or its issued slot.
 * Cancellation keys use identity, are coalesced while retained, and actions must be idempotent.
 * All callbacks/IO occur outside metadata locks; cancellation never runs on the calling thread.
 */
final class LegacyWorldActivity implements AutoCloseable {
    private static final int CAPACITY = 4;
    private final Object lock = new Object();
    private final ThreadPoolExecutor requests, cancellation;
    private final Task<?>[] slots = new Task<?>[CAPACITY];
    private final Stop[] stops = new Stop[CAPACITY];
    private boolean paused, closed;
    private CompletableFuture<Void> barrier;
    private IOException failure;

    private static ThreadPoolExecutor lane(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CAPACITY - 1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    LegacyWorldActivity(String name, boolean cancellationLane) {
        if (name == null || !name.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Activity name");
        requests = lane(name);
        cancellation = cancellationLane ? lane(name + "-cancel") : null;
    }

    private static final class Stop {
        final Object key;
        Runnable action;
        int references;
        boolean scheduled, done;
        Stop(Object key, Runnable action) { this.key = key; this.action = action; }
    }

    private final class Task<T> implements Runnable {
        Supplier<CompletableFuture<T>> action;
        final CompletableFuture<T> view = new CompletableFuture<>();
        final Stop stop;
        boolean started, skip, supplierDone, actualDone, retired;
        Task(Supplier<CompletableFuture<T>> action, Stop stop) { this.action = action; this.stop = stop; }
        @Override public void run() {
            boolean skipped;
            synchronized (lock) { started = true; skipped = skip || paused || closed; }
            boolean gotFuture = false;
            try {
                if (skipped) {
                    view.completeExceptionally(new java.util.concurrent.CancellationException("Legacy request retired before invocation"));
                    synchronized (lock) { actualDone = true; }
                } else {
                    CompletableFuture<T> actual = Objects.requireNonNull(action.get());
                    gotFuture = true;
                    actual.whenComplete((value, error) -> {
                        try {
                            boolean retiring;
                            synchronized(lock){retiring=skip || paused || closed;}
                            if(retiring) view.completeExceptionally(new java.util.concurrent.CancellationException("Legacy request retired"));
                            else if (error == null) view.complete(value);
                            else view.completeExceptionally(detached(error));
                        } finally {
                            Completion completion;
                            synchronized (lock) { actualDone = true; retire(this); completion = completion(); }
                            publish(completion);
                        }
                    });
                }
            } catch (Throwable error) {
                view.completeExceptionally(detached(error));
                synchronized (lock) {
                    if (!gotFuture) actualDone = true;
                    else if (failure == null) failure = detached(error); // Failed observation is not actual completion.
                }
            } finally {
                Completion completion;
                synchronized (lock) {
                    action = null;
                    supplierDone = true;
                    retire(this);
                    completion = completion();
                }
                publish(completion);
            }
        }
    }

    <T> CompletableFuture<T> invoke(Supplier<CompletableFuture<T>> action) {
        return invoke(null, null, action);
    }

    <T> CompletableFuture<T> invoke(Object key, Runnable stop, Supplier<CompletableFuture<T>> action) {
        Objects.requireNonNull(action);
        if ((key == null) != (stop == null)) throw new IllegalArgumentException("Cancellation key/action pair");
        Task<T> task;
        synchronized (lock) {
            if (closed || paused || failure != null) return CompletableFuture.failedFuture(new IllegalStateException("Legacy activity paused/closed"));
            int index = 0;
            while (index < CAPACITY && slots[index] != null) index++;
            if (index == CAPACITY) return CompletableFuture.failedFuture(new IllegalStateException("Legacy activity capacity"));
            Stop token = key == null ? null : token(key, stop);
            task = new Task<>(action, token);
            if (token != null) token.references++;
            slots[index] = task;
        }
        try { requests.execute(task); }
        catch (RuntimeException | Error rejected) {
            task.view.completeExceptionally(detached(rejected));
            Completion completion;
            synchronized (lock) { task.action = null; task.supplierDone = task.actualDone = true; retire(task); completion = completion(); }
            publish(completion);
            if (rejected instanceof Error fatal) throw fatal;
        }
        // Caller cancellation/completion cannot alter the provider's actual future or slot.
        return task.view.copy();
    }

    /** Replaces legacy queue.clear: queued tasks still execute their retirement finally. */
    void discardQueued() {
        synchronized (lock) { for (var task : slots) if (task != null && !task.started) task.skip = true; }
    }

    /** Requests cancellation off caller even when no invocation currently retains this token. */
    void stop(Object key, Runnable action) {
        Objects.requireNonNull(key); Objects.requireNonNull(action);
        Stop planned;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Legacy activity closed");
            planned = token(key, action);
            for (var task : slots) if (task != null && task.stop == planned) task.skip = true;
            if (planned.scheduled) return;
            planned.scheduled = true;
        }
        schedule(planned);
    }

    CompletableFuture<Void> pauseAndDrain() { return pause(null, null, false); }
    CompletableFuture<Void> pauseAndDrain(Object key, Runnable action) {
        Objects.requireNonNull(key); Objects.requireNonNull(action);
        return pause(key, action, false);
    }

    private CompletableFuture<Void> pause(Object key, Runnable action, boolean closing) {
        var planned = new ArrayList<Stop>(CAPACITY);
        CompletableFuture<Void> result;
        Completion completed;
        synchronized (lock) {
            paused = true;
            closed |= closing;
            for (var task : slots) if (task != null) task.skip = true;
            if (key != null) token(key, action);
            for (var token : stops) if (token != null && !token.scheduled) { token.scheduled = true; planned.add(token); }
            if (barrier == null) barrier = new CompletableFuture<>();
            result = barrier.copy();
            completed = completion();
        }
        for (var token : planned) schedule(token);
        publish(completed);
        return result;
    }

    void resume() {
        synchronized (lock) {
            if (closed || failure != null || busy()) throw new IllegalStateException("Legacy activity has not drained");
            paused = false;
        }
    }

    boolean hasActiveWork() { synchronized (lock) { return busy(); } }

    CompletableFuture<Void> closeAsync() { return pause(null, null, true); }
    @Override public void close() { closeAsync(); }

    private Stop token(Object key, Runnable action) {
        if (cancellation == null) throw new IllegalStateException("No cancellation lane");
        for (var token : stops) if (token != null && token.key == key) return token;
        for (int i = 0; i < CAPACITY; i++) if (stops[i] == null) return stops[i] = new Stop(key, action);
        throw new IllegalStateException("Legacy cancellation capacity");
    }

    private void schedule(Stop token) {
        try {
            cancellation.execute(() -> {
                IOException error = null;
                try { token.action.run(); } catch (Throwable failed) { error = detached(failed); }
                finally {
                    Completion completion;
                    synchronized (lock) {
                        token.action = null; token.done = true;
                        if (error != null && failure == null) failure = error;
                        removeToken(token);
                        completion = completion();
                    }
                    publish(completion);
                }
            });
        } catch (RuntimeException | Error rejected) {
            Completion completion;
            synchronized (lock) {
                token.action = null; token.done = true;
                if (failure == null) failure = detached(rejected);
                removeToken(token); completion = completion();
            }
            publish(completion);
            if (rejected instanceof Error fatal) throw fatal;
        }
    }

    private void retire(Task<?> task) {
        if (task.retired || !task.supplierDone || !task.actualDone) return;
        task.retired = true;
        for (int i = 0; i < CAPACITY; i++) if (slots[i] == task) { slots[i] = null; break; }
        if (task.stop != null) { task.stop.references--; removeToken(task.stop); }
    }

    private void removeToken(Stop token) {
        if (token.references != 0 || token.scheduled && !token.done) return;
        for (int i = 0; i < CAPACITY; i++) if (stops[i] == token) { stops[i] = null; token.action = null; break; }
    }

    private boolean busy() {
        for (var task : slots) if (task != null) return true;
        for (var token : stops) if (token != null) return true;
        return false;
    }

    private record Completion(CompletableFuture<Void> future, IOException failure, boolean shutdown) {}
    private Completion completion() {
        if (busy()) return null;
        var result = new Completion(barrier, failure, closed);
        barrier = null;
        return result;
    }
    private void publish(Completion completion) {
        if (completion == null) return;
        if (completion.shutdown) { requests.shutdown(); if (cancellation != null) cancellation.shutdown(); }
        if (completion.future != null) {
            if (completion.failure == null) completion.future.complete(null);
            else completion.future.completeExceptionally(completion.failure);
        }
    }
    private static IOException detached(Throwable error) {
        return new IOException(error.getClass().getName()); // Never call arbitrary Throwable text/cause accessors.
    }
}
