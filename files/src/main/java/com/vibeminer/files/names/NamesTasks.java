package com.vibeminer.files.names;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class NamesTasks {
    private static final Semaphore ADMISSION = new Semaphore(64);

    private NamesTasks() {}

    static <T> CompletableFuture<T> submit(Cancellation c, Supplier<T> work) {
        if (!ADMISSION.tryAcquire()) {
            return CompletableFuture.failedFuture(
                    new RejectedExecutionException("Names work queue full"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    T value = null;
                    Throwable error = null;
                    try {
                        c.check();
                        value = work.get();
                    } catch (Throwable e) {
                        error = unwrap(e);
                    } finally {
                        ADMISSION.release();
                    }
                    if (error == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(error);
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

    static final class Token implements AutoCloseable {
        final com.vibeminer.generated.Cancellation value =
                new com.vibeminer.generated.Cancellation();
        private final Cancellation.Registration registration;

        Token(Cancellation c) {
            registration = c.onCancel(value::cancel);
        }

        public void close() {
            registration.close();
        }
    }
}
