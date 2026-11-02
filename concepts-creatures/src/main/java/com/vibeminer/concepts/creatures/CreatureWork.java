package com.vibeminer.concepts.creatures;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Bounded fail-fast virtual work: no blocked lower-layer completion/admission threads. */
final class CreatureWork {
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private CreatureWork() {}

    static <T> CompletableFuture<T> submit(Supplier<T> action) {
        if (ACTIVE.incrementAndGet() > 512) {
            ACTIVE.decrementAndGet();
            return CompletableFuture.failedFuture(
                    new IllegalStateException("CREATURES_OPERATION_LIMIT"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Thread.startVirtualThread(
                    () -> {
                        T value = null;
                        Throwable error = null;
                        try {
                            value = action.get();
                        } catch (Throwable e) {
                            error = unwrap(e);
                        } finally {
                            ACTIVE.decrementAndGet();
                        }
                        if (error == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(error);
                        }
                    });
        } catch (Throwable error) {
            ACTIVE.decrementAndGet();
            result.completeExceptionally(error);
        }
        return result;
    }

    static Throwable unwrap(Throwable e) {
        while (e instanceof java.util.concurrent.CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
