package com.vibeminer.formats;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Async parser execution does not occupy a lower-layer admission worker while awaiting bytes. */
public final class AssetTasks {
    private AssetTasks() {}

    static final int MAX_ACTIVE = 1024;
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE =
            new java.util.concurrent.atomic.AtomicInteger();

    public static <T> CompletableFuture<T> run(Supplier<T> action) {
        java.util.Objects.requireNonNull(action);
        if (ACTIVE.incrementAndGet() > MAX_ACTIVE) {
            ACTIVE.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("ASSET_FORMAT_LIMIT"));
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Thread.startVirtualThread(
                    () -> {
                        T value = null;
                        Throwable failure = null;
                        try {
                            value = action.get();
                        } catch (Throwable error) {
                            failure = unwrap(error);
                        } finally {
                            ACTIVE.decrementAndGet();
                        }
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
        } catch (Throwable error) {
            ACTIVE.decrementAndGet();
            result.completeExceptionally(error);
        }
        return result;
    }

    public static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
