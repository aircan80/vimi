package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.BudgetedTempFile;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.TempBudget;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Setup owns capture, then installs the final paired Items scope before core admission. */
final class PreparedItemsComparisonInput implements ComparisonInput {
    @FunctionalInterface
    interface Capture {
        BudgetedTempFile capture(Cancellation token) throws Exception;
    }

    @FunctionalInterface
    interface Adopt {
        ComparisonInput adopt(BudgetedTempFile file) throws Exception;
    }

    private ComparisonTypes.Descriptor descriptor;
    private Capture capture;
    private BudgetedTempFile candidates;
    private ComparisonInput prepared;
    private CompletableFuture<Void> closing;

    PreparedItemsComparisonInput(ComparisonTypes.Descriptor initial, Capture capture) {
        descriptor = Objects.requireNonNull(initial);
        this.capture = Objects.requireNonNull(capture);
    }

    // Only the coordinator setup worker calls these; teardown waits for its setup barrier.
    void capture(Cancellation token) throws Exception {
        if (capture == null || candidates != null || prepared != null)
            throw new IllegalStateException("Items input is already captured or closed");
        token.check();
        candidates = Objects.requireNonNull(capture.capture(token));
        capture = null;
    }

    void finish(Adopt adopter) throws Exception {
        if (candidates == null || prepared != null)
            throw new IllegalStateException("Items candidates are unavailable");
        prepared = Objects.requireNonNull(adopter.adopt(candidates));
        candidates = null; // Successful adoption transfers the sealed file ownership.
        descriptor = prepared.descriptor();
    }

    @Override
    public ComparisonTypes.Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token) {
        return prepared == null
                ? CompletableFuture.failedFuture(
                        new IllegalStateException("Items input is not prepared"))
                : prepared.read(cursor, limit, token);
    }

    @Override
    public CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token, TempBudget.Reservation allowance) {
        return prepared == null
                ? CompletableFuture.failedFuture(
                        new IllegalStateException("Items input is not prepared"))
                : prepared.read(cursor, limit, token, allowance);
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) return closing.copy();
        closing = new CompletableFuture<>();
        Thread.startVirtualThread(
                () -> {
                    try {
                        if (prepared != null) prepared.closeAsync().join();
                        if (candidates != null) candidates.close();
                        prepared = null;
                        candidates = null;
                        capture = null;
                        closing.complete(null);
                    } catch (Throwable failure) {
                        closing.completeExceptionally(failure);
                    }
                });
        return closing.copy();
    }
}
