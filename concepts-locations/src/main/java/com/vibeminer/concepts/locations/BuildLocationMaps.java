package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import java.util.concurrent.CompletableFuture;

/** Borrowed selected-build table/file handles; metadata never initiates image downloads. */
public interface BuildLocationMaps extends AutoCloseable {
    CompletableFuture<MapTypes.Resolution> resolve(
            LocationTypes.Location location, Cancellation token);

    /**
     * Resolves many observations in order. Implementations may share one bounded worker pass; the
     * default composes {@link #resolve} sequentially.
     */
    default CompletableFuture<java.util.List<MapTypes.Resolution>> resolveAll(
            java.util.List<LocationTypes.Location> locations, Cancellation token) {
        var copy = java.util.List.copyOf(locations);
        var all = new java.util.ArrayList<MapTypes.Resolution>(copy.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (var location : copy) {
            chain = chain.thenCompose(ignored -> resolve(location, token).thenAccept(all::add));
        }
        return chain.thenApply(ignored -> java.util.List.copyOf(all));
    }

    CompletableFuture<MapTypes.Artwork> artwork(long uiMapId, Cancellation token);

    CompletableFuture<MapTypes.TileImage> tile(long uiMapId, long tileRowId, Cancellation token);

    /** Optional strict prepared-local navigation; never prepares or acquires sources. */
    default CompletableFuture<MapNavigationSourceTypes.Result> navigation(Cancellation token) {
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.completedFuture(MapNavigationSourceTypes.Result.unsupported());
    }

    /**
     * UiMapAssignment rows for {@code worldMapId} that name a WMO group ({@code WMOGroupID != 0}),
     * as recorded. {@link #resolve} keeps ignoring these rows; matching them to WMO group headers
     * is the caller's job. The default reports the query unsupported.
     */
    default CompletableFuture<MapTypes.WmoGroupAssignments> wmoGroupAssignments(
            long worldMapId, Cancellation token) {
        java.util.Objects.requireNonNull(token);
        return CompletableFuture.completedFuture(
                MapTypes.WmoGroupAssignments.unsupported(worldMapId));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
