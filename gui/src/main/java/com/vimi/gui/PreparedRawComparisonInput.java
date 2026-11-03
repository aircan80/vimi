package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SortedComparisonInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.formats.tables.BuildTables;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Admission exposes only a pinned descriptor; sorting begins after both schemas are compatible. */
final class PreparedRawComparisonInput implements ComparisonInput {
    private final ComparisonTypes.Descriptor descriptor;
    private final Path temporaryParent;
    private final TempBudget budget;
    private SortedComparisonInput.Source source;
    private BuildTables tables;
    private SortedComparisonInput sorted;
    private CompletableFuture<Void> closing;

    PreparedRawComparisonInput(
            SortedComparisonInput.Source source,
            BuildTables tables,
            Path temporaryParent,
            TempBudget budget) {
        this.source = source;
        this.tables = tables;
        this.temporaryParent = temporaryParent;
        this.budget = budget;
        descriptor = source.descriptor();
    }

    // Coordinator setup owns this transition; teardown waits for its setup barrier first.
    void prepare(Cancellation token) {
        if (sorted != null || source == null)
            throw new IllegalStateException("Raw capture already prepared or closed");
        token.check();
        var ownedSource = source;
        var ownedTables = tables;
        source = null;
        tables = null;
        try (ownedTables) {
            sorted =
                    SortedComparisonInput.capture(ownedSource, temporaryParent, budget, token)
                            .join();
        }
    }

    @Override
    public ComparisonTypes.Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token) {
        return sorted == null
                ? CompletableFuture.failedFuture(
                        new IllegalStateException("Raw input is not prepared"))
                : sorted.read(cursor, limit, token);
    }

    @Override
    public CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token, TempBudget.Reservation allowance) {
        return sorted == null
                ? CompletableFuture.failedFuture(
                        new IllegalStateException("Raw input is not prepared"))
                : sorted.read(cursor, limit, token, allowance);
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closing = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    Throwable problem = null;
                    try {
                        if (sorted != null) sorted.closeAsync().join();
                        else if (source != null) source.closeAsync().join();
                    } catch (Throwable failure) {
                        problem = failure;
                    }
                    try {
                        if (tables != null) tables.close();
                    } catch (Throwable failure) {
                        if (problem == null) problem = failure;
                        else problem.addSuppressed(failure);
                    }
                    source = null;
                    tables = null;
                    sorted = null;
                    if (problem == null) closing.complete(null);
                    else closing.completeExceptionally(problem);
                });
        return closing.copy();
    }
}
