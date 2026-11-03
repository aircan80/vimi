package com.vibeminer.concepts.comparison;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Ordered, pinned input. Closing drains adapter work, never closes borrowed build APIs. */
public interface ComparisonInput {
    ComparisonTypes.Descriptor descriptor();

    CompletableFuture<ComparisonTypes.InputPage> read(String cursor, int limit, Cancellation token);

    CompletableFuture<Void> closeAsync();
}
