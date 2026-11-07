package com.vimi.gui;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import java.util.Comparator;
import java.util.List;

/** Orders the recorded camera neighborhood without acquiring source data. */
final class WorldStreamNeighborhood {
    private WorldStreamNeighborhood() {}

    /** The product default, NEAR (3 x 3); see the view-distance overload. */
    static List<WorldFormats.Tile> select(List<WorldFormats.Tile> recorded,
            int cameraX, int cameraY, double yaw) {
        return select(recorded, cameraX, cameraY, yaw, WorldStreamTransport.ViewDistance.NEAR);
    }

    static List<WorldFormats.Tile> select(List<WorldFormats.Tile> recorded,
            int cameraX, int cameraY, double yaw, WorldStreamTransport.ViewDistance distance) {
        int radius = distance.radius();
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Nonfinite World heading");
        double forwardX = -Math.cos(yaw), forwardY = -Math.sin(yaw);
        return recorded.stream()
                .filter(tile -> ring(tile, cameraX, cameraY) <= radius)
                // Whole rings first, so the inner 3 x 3 still loads before the outer ring.
                .sorted(Comparator.comparingInt((WorldFormats.Tile tile) ->
                        ring(tile, cameraX, cameraY))
                        .thenComparingInt(tile ->
                                Math.abs(tile.x() - cameraX) + Math.abs(tile.y() - cameraY))
                        // Filename X/Y increase opposite native world X/Y. Keep the camera
                        // tile first, then prefer visible-forward neighbors at equal distance.
                        .thenComparingDouble(tile -> -((tile.x() - cameraX) * forwardX
                                + (tile.y() - cameraY) * forwardY))
                        .thenComparingInt(WorldFormats.Tile::x)
                        .thenComparingInt(WorldFormats.Tile::y))
                .limit(distance.residents()).toList();
    }

    /** Chebyshev tile distance: 0 for the camera tile, 1 for the inner ring, 2 for the outer. */
    static int ring(WorldFormats.Tile tile, int cameraX, int cameraY) {
        return Math.max(Math.abs(tile.x() - cameraX), Math.abs(tile.y() - cameraY));
    }
}
