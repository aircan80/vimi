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

    /**
     * The description of one rank of a talent (1-based). Legacy talents use that rank's spell;
     * readers that record per-rank values (TraitTree talents) apply them. The default uses the
     * rank's spell, or the last recorded spell.
     */
    default CompletableFuture<com.vibeminer.concepts.effects.SpellTexts.Description> description(
            TalentTypes.Talent talent, int rank, Cancellation token) {
        if (rank < 1 || rank > talent.maxRanks()) {
            throw new IllegalArgumentException("Rank is outside the talent's recorded ranks");
        }
        var spells = talent.rankSpells();
        return description(spells.get(Math.min(rank, spells.size()) - 1), token);
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
