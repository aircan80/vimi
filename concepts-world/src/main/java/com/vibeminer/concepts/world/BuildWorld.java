package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.world.WorldFormats;
import java.util.concurrent.CompletableFuture;

/** One build/locale; implementations own request lifetimes and borrow source services. */
public interface BuildWorld extends AutoCloseable {
    CompletableFuture<WorldService.Catalog> catalog(Cancellation token);

    CompletableFuture<WorldService.Inventory> inventory(
            WorldService.MapInfo map, Cancellation token);

    CompletableFuture<WorldService.LoadedTile> tile(
            WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
