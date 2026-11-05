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

    /**
     * Optional exact-area discovery; custom world providers retain explicit unsupported behavior.
     */
    default CompletableFuture<WorldAreaNavigation.Result> findArea(
            WorldAreaNavigation.Request request,
            Cancellation token,
            java.util.function.Consumer<WorldAreaNavigation.Progress> progress) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Area terrain discovery is unavailable"));
    }

    /** Revalidate a discovered source and entry before opening its terrain. */
    default CompletableFuture<WorldService.LoadedTile> areaTile(
            WorldService.MapInfo map, WorldAreaNavigation.Match match, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Area terrain entry is unavailable"));
    }

    /**
     * Recorded minimap artwork only; custom providers may explicitly omit this optional feature.
     */
    default CompletableFuture<WorldService.Minimap> minimap(
            WorldFormats.Tile tile, Cancellation token) {
        return CompletableFuture.completedFuture(
                WorldService.Minimap.unavailable("Minimap artwork unavailable"));
    }

    /** Optional complete raw object metadata; no transform or visible-appearance admission. */
    default CompletableFuture<WorldPlacementSource.Result> placements(
            WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
        return CompletableFuture.completedFuture(
                WorldPlacementSource.Result.unavailable("World object metadata is unsupported"));
    }

    /** Optional recorded basic-coverage texture batch; caller closes the owned result. */
    default CompletableFuture<WorldTerrainTextureSource.BatchResult> terrainTextures(
            WorldService.MapInfo map,
            java.util.List<WorldFormats.Tile> tiles,
            Cancellation token,
            java.util.function.Consumer<WorldTerrainTextureSource.Progress> progress) {
        token.check();
        return CompletableFuture.completedFuture(
                WorldTerrainTextureSource.BatchResult.unsupported(tiles));
    }

    /** Optional single-root static water preview joined to actual prepared terrain tiles. */
    default java.util.Optional<WorldWaterPreview> waterPreview() {
        return java.util.Optional.empty();
    }

    /** Prepares the native tables water qualification reads; true only when both are ready. */
    default CompletableFuture<Boolean> prepareWaterTables(Cancellation token) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Optional recorded dungeon and raid links (area and entrance on the parent map); custom
     * providers state that they have none.
     */
    default CompletableFuture<WorldInstanceLinks> instanceLinks(Cancellation token) {
        return CompletableFuture.completedFuture(
                WorldInstanceLinks.unavailable("Instance links are unavailable for this source"));
    }

    /** Optional actual-source global scene capability; custom providers perform no fallback IO. */
    default java.util.Optional<WorldGlobalScenes> globalScenes() {
        return java.util.Optional.empty();
    }

    default java.util.Optional<WorldTerrainStreaming> terrainStreaming() {
        return java.util.Optional.empty();
    }

    /** Optional recorded area music/ambience relationships; custom providers report none. */
    default CompletableFuture<WorldAreaAudio.Plan> areaAudio(
            long mapId,
            WorldCurrentArea.Result area,
            WorldAreaAudio.Variant variant,
            Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Recorded area audio is unavailable"));
    }

    /** Optional bounded original audio bytes for a recorded sound-kit file. */
    default CompletableFuture<WorldAreaAudio.AudioFile> audioFile(
            long fileDataId, Cancellation token) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Recorded area audio is unavailable"));
    }

    CompletableFuture<Void> closeAsync();

    @Override
    default void close() {
        closeAsync().join();
    }
}
