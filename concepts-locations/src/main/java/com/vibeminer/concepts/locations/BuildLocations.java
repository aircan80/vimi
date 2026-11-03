package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Locations for a fixed selection of already prepared source snapshots. */
public interface BuildLocations extends AutoCloseable {
    CompletableFuture<LocationTypes.Locations> locations(
            LocationTypes.Entity entity, Cancellation token);

    CompletableFuture<LocationTypes.Pickups> pickups(long questId, Cancellation token);

    CompletableFuture<LocationTypes.Frames> frames(Cancellation token);

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
