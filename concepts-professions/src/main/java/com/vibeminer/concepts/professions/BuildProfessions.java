package com.vibeminer.concepts.professions;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Cold, bounded read-only projection borrowing a selected build's table handle. */
public interface BuildProfessions extends AutoCloseable {
    CompletableFuture<ProfessionTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<ProfessionTypes.Page> abilities(
            long professionId, String query, long afterId, int limit, Cancellation token);

    CompletableFuture<ProfessionTypes.Detail> detail(long abilityId, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
