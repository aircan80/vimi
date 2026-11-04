package com.vimi.gui;

import com.vibeminer.formats.world.WorldFormats;
import java.util.Comparator;
import java.util.List;

/** Orders the recorded camera neighborhood without acquiring source data. */
final class WorldStreamNeighborhood {
    private WorldStreamNeighborhood() {}

    static List<WorldFormats.Tile> select(List<WorldFormats.Tile> recorded,
            int cameraX, int cameraY, double yaw) {
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Nonfinite World heading");
        double forwardX = -Math.cos(yaw), forwardY = -Math.sin(yaw);
        return recorded.stream()
                .filter(tile -> Math.abs(tile.x() - cameraX) <= 1
                        && Math.abs(tile.y() - cameraY) <= 1)
                .sorted(Comparator.comparingInt((WorldFormats.Tile tile) ->
                        Math.abs(tile.x() - cameraX) + Math.abs(tile.y() - cameraY))
                        // Filename X/Y increase opposite native world X/Y. Keep the camera
                        // tile first, then prefer visible-forward neighbors at equal distance.
                        .thenComparingDouble(tile -> -((tile.x() - cameraX) * forwardX
                                + (tile.y() - cameraY) * forwardY))
                        .thenComparingInt(WorldFormats.Tile::x)
                        .thenComparingInt(WorldFormats.Tile::y))
                .limit(9).toList();
    }
}
