package com.vibeminer.concepts.effects;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

final class EffectsTasks {
    static final int MAX_ACTIVE = 1024;
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private EffectsTasks() {}

    static <T> CompletableFuture<T> run(Supplier<T> action) {
        Objects.requireNonNull(action);
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE) {
            ACTIVE.decrementAndGet();
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ITEM_EFFECTS_OPERATION_LIMIT"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Thread.startVirtualThread(
                    () -> {
                        T value = null;
                        Throwable failure = null;
                        try {
                            value = action.get();
                        } catch (Throwable e) {
                            failure = unwrap(e);
                        } finally {
                            ACTIVE.decrementAndGet();
                        }
                        if (failure == null) {
                            future.complete(value);
                        } else {
                            future.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable e) {
            ACTIVE.decrementAndGet();
            future.completeExceptionally(e);
        }
        return future;
    }

    static Throwable unwrap(Throwable e) {
        while (e instanceof CompletionException && e.getCause() != null) {
            e = e.getCause();
        }
        return e;
    }
}
