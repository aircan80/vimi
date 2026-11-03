package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only race projection borrowing one build/locale table handle. */
public interface BuildRaces extends AutoCloseable {
    CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<RaceTypes.Overview> overview(long raceId, Cancellation token);

    default CompletableFuture<RaceMediaTypes.Variants> modelVariants(
            long raceId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceMediaTypes.Variants(
                        RaceTypes.State.UNAVAILABLE,
                        java.util.List.of(),
                        java.util.List.of(),
                        "Model data unavailable"));
    }

    default CompletableFuture<RaceMediaTypes.Preview> preview(
            long raceId, long classId, long chrModelId, Cancellation token) {
        return CompletableFuture.completedFuture(
                new RaceMediaTypes.Preview(
                        RaceTypes.State.UNAVAILABLE,
                        null,
                        null,
                        java.util.Set.of(),
                        java.util.List.of(),
                        false,
                        "Model preview unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
