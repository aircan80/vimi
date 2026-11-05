package com.vibeminer.formats.tables;

import com.vibeminer.access.Cancellation;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Runs independent tasks ahead of an in-order consumer.
 *
 * <p>Tasks start in the order they are offered, and at most {@code limit} tasks are started but not
 * yet taken at any time. {@link #take} returns results in that same order and rethrows a task's own
 * exception unchanged, so a consumer sees exactly what a sequential loop would. Tasks run under a
 * child token: closing with work still pending cancels it and waits for every started task.
 */
final class OrderedLookahead<K, T> implements AutoCloseable {
    private record Outcome<T>(T value, Throwable error) {}

    private record Started<K, T>(K key, CompletableFuture<Outcome<T>> result) {}

    private final int limit;
    private final Cancellation scope = new Cancellation();
    private final Cancellation.Registration registration;
    private final ArrayDeque<Started<K, T>> started = new ArrayDeque<>();

    OrderedLookahead(int limit, Cancellation parent) {
        if (limit < 1) {
            throw new IllegalArgumentException("Lookahead limit must be positive");
        }
        this.limit = limit;
        registration = parent.onCancel(scope::cancel);
    }

    boolean hasRoom() {
        return started.size() < limit;
    }

    /** Starts {@code task} for {@code key}; the task receives the child token. */
    void start(K key, Function<Cancellation, T> task) {
        if (!hasRoom()) {
            throw new IllegalStateException("Lookahead is full");
        }
        var result = new CompletableFuture<Outcome<T>>();
        started.addLast(new Started<>(key, result));
        try {
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    result.complete(new Outcome<>(task.apply(scope), null));
                                } catch (Throwable error) {
                                    result.complete(new Outcome<>(null, error));
                                }
                            });
        } catch (Throwable error) {
            started.removeLast();
            throw error;
        }
    }

    /** Waits for the oldest started task, which must belong to {@code key}. */
    T take(K key) {
        var next = started.pollFirst();
        if (next == null || !next.key().equals(key)) {
            throw new IllegalStateException("Lookahead out of order for " + key);
        }
        var outcome = next.result().join();
        if (outcome.error() instanceof RuntimeException failure) {
            throw failure;
        }
        if (outcome.error() instanceof Error failure) {
            throw failure;
        }
        if (outcome.error() != null) {
            throw new java.util.concurrent.CompletionException(outcome.error());
        }
        return outcome.value();
    }

    @Override
    public void close() {
        try {
            if (!started.isEmpty()) {
                scope.cancel();
                for (var pending : started) {
                    pending.result().join();
                }
                started.clear();
            }
        } finally {
            registration.close();
        }
    }
}
