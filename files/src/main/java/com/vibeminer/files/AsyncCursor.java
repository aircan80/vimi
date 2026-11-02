package com.vibeminer.files;

import com.vibeminer.access.Cancellation;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Ordered bounded pages. An empty page ends the cursor; one next call at a time. */
public interface AsyncCursor<T> extends AutoCloseable {
    CompletableFuture<List<T>> next(int limit, Cancellation cancellation);

    @Override
    void close();
}
