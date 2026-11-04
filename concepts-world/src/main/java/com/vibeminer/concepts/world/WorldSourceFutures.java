package com.vibeminer.concepts.world;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Preserves the actual failure at a trusted source await, without interpreting its meaning. */
final class WorldSourceFutures {
    private WorldSourceFutures() {}

    /**
     * JDK join can create a CancellationException whose cause is the original cancellation.
     * Encapsulate the ORIGINAL stored failure before join; preserve any existing completion wrapper
     * unchanged. Does not unwrap dirty/foreign cancellation, inspect arbitrary causes, cancel a
     * provider, or promise IO/callback retirement. Each owning caller still drains/closes its work.
     */
    static <T> T await(CompletableFuture<T> future) {
        return Objects.requireNonNull(future)
                .handle(
                        (value, failure) -> {
                            if (failure instanceof CompletionException wrapped) {
                                throw wrapped;
                            }
                            if (failure != null) {
                                throw new CompletionException("World source await failed", failure);
                            }
                            return value;
                        })
                .join();
    }
}
