package com.vibeminer.formats;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Async parser execution does not occupy a lower-layer admission worker while awaiting bytes. */
public final class AssetTasks {
    private AssetTasks() {}

    public static <T> CompletableFuture<T> run(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        result.complete(action.get());
                    } catch (Throwable failure) {
                        result.completeExceptionally(unwrap(failure));
                    }
                });
        return result;
    }

    public static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }
}
