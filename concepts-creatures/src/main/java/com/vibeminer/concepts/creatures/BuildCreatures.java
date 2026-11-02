package com.vibeminer.concepts.creatures;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureQuery;
import com.vibeminer.concepts.creatures.CreatureTypes.CreatureResult;
import com.vibeminer.concepts.creatures.CreatureTypes.CreaturesPage;
import com.vibeminer.formats.tables.TableTypes.Priority;
import java.util.concurrent.CompletableFuture;

public interface BuildCreatures extends AutoCloseable {
    BuildRef build();

    String locale();

    CompletableFuture<CreaturesPage> list(
            CreatureQuery query, Priority priority, Cancellation token);

    CompletableFuture<CreatureResult> get(long id, Priority priority, Cancellation token);

    /** Table facts and declared references only; never initiates asset I/O. */
    default CompletableFuture<CreatureResult> getData(
            long id, Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Data-only detail not supported"));
    }

    default CompletableFuture<com.vibeminer.concepts.core.ConceptWarmup.Result> warmup(
            Priority priority, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Warmup not supported"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
