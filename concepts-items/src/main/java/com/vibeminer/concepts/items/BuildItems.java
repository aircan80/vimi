package com.vibeminer.concepts.items;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.tables.TableTypes.Priority;
import java.util.concurrent.CompletableFuture;

/** Concurrent item list/detail handle. Problems remain typed states, never fabricated absence. */
public interface BuildItems extends AutoCloseable {
    BuildRef build();

    String locale();

    CompletableFuture<ItemsPage> list(ItemQuery query, Priority priority, Cancellation token);

    CompletableFuture<ItemResult> get(long id, Priority priority, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
