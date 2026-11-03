package com.vibeminer.concepts.dungeons;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Read-only dungeon metadata projection borrowing selected-build tables. */
public interface BuildDungeons extends AutoCloseable {
    CompletableFuture<DungeonTypes.Catalog> catalog(Cancellation token);

    CompletableFuture<DungeonTypes.Overview> overview(long mapId, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
