package com.vibeminer.access;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Admission fence: accepted operations and their bounded internal groups drain before scheduler
 * cleanup.
 */
final class AccessLifetime {
    private final Object gate = new Object();
    private long active;
    private boolean closed, cleanupStarted;
    private Supplier<CompletableFuture<Void>> cleanup;
    private CompletableFuture<Void> closing;

    <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> start) {
        Objects.requireNonNull(start);
        synchronized (gate) {
            if (closed) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("OBJECT_ACCESS_CLOSED"));
            }
            active++;
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Objects.requireNonNull(start.get())
                    .whenComplete(
                            (value, error) -> {
                                publish(result, value, unwrap(error));
                                release();
                            });
        } catch (Throwable error) {
            publish(result, null, unwrap(error));
            release();
        }
        return result;
    }

    void retain() {
        synchronized (gate) {
            if (cleanupStarted || closed && active == 0) {
                throw new IllegalStateException("OBJECT_ACCESS_CLOSED");
            }
            active++;
        }
    }

    void release() {
        boolean begin;
        synchronized (gate) {
            if (active < 1) {
                throw new IllegalStateException("Unbalanced access lifetime release");
            }
            active--;
            begin = beginCleanup();
        }
        if (begin) {
            cleanup();
        }
    }

    CompletableFuture<Void> close(Supplier<CompletableFuture<Void>> cleanup) {
        Objects.requireNonNull(cleanup);
        boolean begin;
        CompletableFuture<Void> result;
        synchronized (gate) {
            if (closing != null) {
                return closing;
            }
            closed = true;
            this.cleanup = cleanup;
            closing = new CompletableFuture<>();
            result = closing;
            begin = beginCleanup();
        }
        if (begin) {
            cleanup();
        }
        return result;
    }

    private boolean beginCleanup() {
        if (closed && active == 0 && !cleanupStarted) {
            cleanupStarted = true;
            return true;
        }
        return false;
    }

    private void cleanup() {
        Thread.startVirtualThread(
                () -> {
                    try {
                        Objects.requireNonNull(cleanup.get())
                                .whenComplete(
                                        (ignored, error) -> publish(closing, null, unwrap(error)));
                    } catch (Throwable error) {
                        publish(closing, null, unwrap(error));
                    }
                });
    }

    private static <T> void publish(CompletableFuture<T> result, T value, Throwable error) {
        Thread.startVirtualThread(
                () -> {
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(error);
                    }
                });
        // Completion sets the result before invoking user continuations. Do not await those
        // continuations:
        // one may synchronously wait for close, which needs this reference released first.
        while (!result.isDone()) {
            LockSupport.parkNanos(100_000);
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }
}
