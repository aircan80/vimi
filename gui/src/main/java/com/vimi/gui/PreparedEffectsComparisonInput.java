package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.BudgetedTempFile;
import com.vibeminer.concepts.comparison.ComparisonInput;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import com.vibeminer.concepts.comparison.SealedCandidateInput;
import com.vibeminer.concepts.comparison.TempBudget;
import com.vibeminer.concepts.comparison.effects.EffectsSnapshot;
import com.vibeminer.concepts.comparison.effects.EffectsStageCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Setup-barrier-owned capture followed by the final sealed paired effects input. */
final class PreparedEffectsComparisonInput implements ComparisonInput {
    @FunctionalInterface
    interface Capture {
        BudgetedTempFile capture(EffectsSnapshot snapshot, EffectsSnapshot.Side side,
                Cancellation token) throws Exception;
    }
    private final EffectsSnapshot.Endpoint endpoint;
    private ComparisonTypes.Descriptor descriptor;
    private Capture capture;
    private BudgetedTempFile candidates;
    private Path directory;
    private ComparisonInput prepared;
    private CompletableFuture<Void> closing;

    PreparedEffectsComparisonInput(ComparisonTypes.Descriptor initial,
            EffectsSnapshot.Endpoint endpoint, Capture capture) {
        this.descriptor = Objects.requireNonNull(initial);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.capture = Objects.requireNonNull(capture);
        if (!initial.namespace().equals(EffectsSnapshot.NAMESPACE)
                || !initial.product().equals(endpoint.build().product())
                || !initial.locale().equals(endpoint.locale())) {
            throw new IllegalArgumentException("Pending effects descriptor differs from endpoint");
        }
    }
    EffectsSnapshot.Endpoint endpoint() { return endpoint; }

    // Coordinator setup only; teardown waits its actual setup barrier before closing this owner.
    void capture(EffectsSnapshot snapshot, EffectsSnapshot.Side side, Cancellation token) throws Exception {
        if (capture == null || candidates != null || prepared != null) {
            throw new IllegalStateException("Effects input already captured or closed");
        }
        if (!snapshot.endpoint(side).equals(endpoint)) {
            throw new IllegalArgumentException("Effects capture endpoint/session differs");
        }
        token.check();
        candidates = Objects.requireNonNull(capture.capture(snapshot, side, token));
        directory = candidates.path().getParent();
        capture = null;
    }
    void finish(EffectsSnapshot snapshot, EffectsSnapshot.Side side) throws Exception {
        if (candidates == null || prepared != null || !snapshot.endpoint(side).equals(endpoint)) {
            throw new IllegalStateException("Effects candidates/endpoint unavailable");
        }
        var codec = new EffectsStageCodec(snapshot);
        prepared = SealedCandidateInput.adopt(candidates, snapshot.descriptor(side), snapshot.budget(),
                record -> codec.project(side, record));
        candidates = null;
        descriptor = prepared.descriptor();
    }
    @Override public ComparisonTypes.Descriptor descriptor() { return descriptor; }
    @Override public CompletableFuture<ComparisonTypes.InputPage> read(String cursor, int limit, Cancellation token) {
        return prepared == null ? CompletableFuture.failedFuture(new IllegalStateException("Effects input is not prepared"))
                : prepared.read(cursor, limit, token);
    }
    @Override public CompletableFuture<ComparisonTypes.InputPage> read(String cursor, int limit,
            Cancellation token, TempBudget.Reservation allowance) {
        return prepared == null ? CompletableFuture.failedFuture(new IllegalStateException("Effects input is not prepared"))
                : prepared.read(cursor, limit, token, allowance);
    }
    @Override public synchronized CompletableFuture<Void> closeAsync() {
        if (closing != null) { return closing.copy(); }
        closing = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            Throwable problem = null;
            try { if (prepared != null) { prepared.closeAsync().join(); } }
            catch (Throwable failure) { problem = failure; }
            try { if (candidates != null) { candidates.close(); } }
            catch (Throwable failure) { if (problem == null) { problem = failure; } else { problem.addSuppressed(failure); } }
            try { if (directory != null) { Files.deleteIfExists(directory); } }
            catch (Throwable failure) { if (problem == null) { problem = failure; } else { problem.addSuppressed(failure); } }
            prepared = null;
            candidates = null;
            capture = null;
            if (problem == null) { closing.complete(null); } else { closing.completeExceptionally(problem); }
        });
        return closing.copy();
    }
}
