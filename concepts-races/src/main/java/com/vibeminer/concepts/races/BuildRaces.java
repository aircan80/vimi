package com.vibeminer.concepts.races;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only race projection borrowing one build/locale table handle. */
public interface BuildRaces extends AutoCloseable {
    CompletableFuture<RaceTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<RaceTypes.Overview> overview(long raceId, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
