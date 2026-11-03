package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Ordered, pinned input. Closing drains adapter work, never closes borrowed build APIs. */
public interface ComparisonInput {
    ComparisonTypes.Descriptor descriptor();

    CompletableFuture<ComparisonTypes.InputPage> read(String cursor, int limit, Cancellation token);

    /**
     * Borrow a caller-owned page allowance. It covers combined source framing/decoding, returned
     * page and core canonical-copy overlap, not independent allocations for each phase. The caller
     * retains it until the pending read and retained canonical page drain. Implementations must not
     * close, shrink or transfer a borrowed allowance. Legacy metadata inputs keep their existing
     * method; allocation-aware inputs validate the live same-budget allowance before work starts.
     */
    default CompletableFuture<ComparisonTypes.InputPage> read(
            String cursor, int limit, Cancellation token, TempBudget.Reservation pageAllowance) {
        return read(cursor, limit, token);
    }

    CompletableFuture<Void> closeAsync();
}
