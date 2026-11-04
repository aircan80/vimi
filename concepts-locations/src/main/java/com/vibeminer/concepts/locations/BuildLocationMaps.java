package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Borrowed selected-build table/file handles; metadata never initiates image downloads. */
public interface BuildLocationMaps extends AutoCloseable {
    CompletableFuture<MapTypes.Resolution> resolve(
            LocationTypes.Location location, Cancellation token);

    CompletableFuture<MapTypes.Artwork> artwork(long uiMapId, Cancellation token);

    CompletableFuture<MapTypes.TileImage> tile(long uiMapId, long tileRowId, Cancellation token);

    /** Optional strict prepared-local navigation; never prepares or acquires sources. */
    default CompletableFuture<MapNavigationSourceTypes.Result> navigation(Cancellation token) {
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.completedFuture(MapNavigationSourceTypes.Result.unsupported());
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
