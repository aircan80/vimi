package com.vibeminer.concepts.mounts;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only catalogue borrowing one build/locale table and file workspace. */
public interface BuildMounts extends AutoCloseable {
    CompletableFuture<MountTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<MountTypes.Overview> overview(long mountId, Cancellation token);

    default CompletableFuture<com.vibeminer.concepts.models.DisplayModelTypes.Preview> preview(
            long mountId, long variantRecordId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new com.vibeminer.concepts.models.DisplayModelTypes.Preview(
                        com.vibeminer.concepts.models.DisplayModelTypes.State.UNAVAILABLE,
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.List.of(),
                        false,
                        "Model previews unavailable"));
    }

    /** Lazy idle qualification for the same recorded variant as {@link #preview}. */
    default CompletableFuture<com.vibeminer.concepts.models.DisplayModelTypes.Idle> idle(
            long mountId, long variantRecordId, Cancellation token) {
        return CompletableFuture.completedFuture(
                com.vibeminer.concepts.models.DisplayModelTypes.Idle.unavailable(
                        "Idle animation unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
