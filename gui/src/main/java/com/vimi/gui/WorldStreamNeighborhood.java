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
        return select(recorded, cameraX, cameraY, yaw, distance, cameraX, cameraY);
    }

    /**
     * As above, ordered for a moving camera (#366): membership is still every recorded tile within
     * the view distance of the camera tile, and the camera tile stays first (presentation waits
     * on it), but the rest are ranked by ring and distance from the tile the camera is predicted
     * to reach, {@code aheadX, aheadY}, so tiles ahead load before tiles behind. With the camera
     * still (ahead = camera tile) the order is exactly the stationary one.
     */
    static List<WorldFormats.Tile> select(List<WorldFormats.Tile> recorded,
            int cameraX, int cameraY, double yaw, WorldStreamTransport.ViewDistance distance,
            int aheadX, int aheadY) {
        int radius = distance.radius();
        if (!Double.isFinite(yaw)) throw new IllegalArgumentException("Nonfinite World heading");
        double forwardX = -Math.cos(yaw), forwardY = -Math.sin(yaw);
        return recorded.stream()
                .filter(tile -> ring(tile, cameraX, cameraY) <= radius)
                // Whole rings first, so the inner 3 x 3 still loads before the outer ring.
                .sorted(Comparator.comparingInt((WorldFormats.Tile tile) ->
                                tile.x() == cameraX && tile.y() == cameraY ? 0 : 1)
                        .thenComparingInt(tile -> ring(tile, aheadX, aheadY))
                        .thenComparingInt(tile ->
                                Math.abs(tile.x() - aheadX) + Math.abs(tile.y() - aheadY))
                        // Filename X/Y increase opposite native world X/Y. Keep the camera
                        // tile first, then prefer visible-forward neighbors at equal distance.
                        .thenComparingDouble(tile -> -((tile.x() - aheadX) * forwardX
                                + (tile.y() - aheadY) * forwardY))
                        .thenComparingInt(WorldFormats.Tile::x)
                        .thenComparingInt(WorldFormats.Tile::y))
                .limit(distance.residents()).toList();
    }

    /**
     * The recorded tiles one ring beyond the view distance (#366): not demanded, but a tile already
     * being prepared there is kept rather than canceled, so a camera crossing a tile border back
     * and forth does not restart it.
     */
    static List<WorldFormats.Tile> keep(List<WorldFormats.Tile> recorded,
            int cameraX, int cameraY, WorldStreamTransport.ViewDistance distance) {
        int radius = distance.radius() + 1;
        return recorded.stream().filter(tile -> ring(tile, cameraX, cameraY) == radius).toList();
    }

    /** Chebyshev tile distance: 0 for the camera tile, 1 for the inner ring, 2 for the outer. */
    static int ring(WorldFormats.Tile tile, int cameraX, int cameraY) {
        return Math.max(Math.abs(tile.x() - cameraX), Math.abs(tile.y() - cameraY));
    }

    /**
     * Tile-space camera motion (#366): a smoothed velocity in tiles per second and the tile the
     * camera is predicted to reach after {@link #LEAD_SECONDS}, about one tile's measured
     * reserve-to-staged latency. The prediction never reaches past the view radius; a jump of a
     * tile or more between samples, or a gap of a second or more, restarts it at rest.
     */
    static final class Motion {
        static final double LEAD_SECONDS = 1.2, SMOOTHING = 0.3;
        private double lastX, lastY, velocityX, velocityY;
        private long lastNanos;
        private boolean seeded;

        /** Records the camera at fractional tile {@code x, y} and returns the predicted tile. */
        int[] ahead(double x, double y, long nanos, int radius) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || radius < 0) {
                throw new IllegalArgumentException("World camera tile position");
            }
            double seconds = (nanos - lastNanos) / 1e9;
            if (!seeded
                    || seconds <= 0
                    || seconds >= 1
                    || Math.abs(x - lastX) >= 1
                    || Math.abs(y - lastY) >= 1) {
                velocityX = 0;
                velocityY = 0;
            } else {
                velocityX += SMOOTHING * ((x - lastX) / seconds - velocityX);
                velocityY += SMOOTHING * ((y - lastY) / seconds - velocityY);
            }
            lastX = x;
            lastY = y;
            lastNanos = nanos;
            seeded = true;
            double dx = Math.clamp(velocityX * LEAD_SECONDS, -radius, radius);
            double dy = Math.clamp(velocityY * LEAD_SECONDS, -radius, radius);
            return new int[] {(int) Math.floor(x + dx), (int) Math.floor(y + dy)};
        }
    }
}
