package com.vibeminer.concepts.talents;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Independent domain projection over a borrowed build/locale table handle. */
public interface BuildTalents extends AutoCloseable {
    CompletableFuture<TalentTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<TalentTypes.ClassTrees> trees(long classId, Cancellation token);

    default CompletableFuture<com.vibeminer.concepts.effects.SpellTexts.Description> description(
            long spellId, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Spell preview unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
