package com.vibeminer.formats.world;

import java.util.Objects;

/** Source-neutral MH2O rectangle mapping onto recorded native terrain anchors, not provenance. */
public final class WorldLiquidGrid {
    public static final String REVISION = "adt18-mh2o-native-xy-v1";

    private WorldLiquidGrid() {}

    public static final class Mapping {
        private final int chunkX, chunkY, x, y, width, height;
        private final float north, west;

        private Mapping(TerrainGrid.Chunk chunk, int x, int y, int width, int height) {
            chunkX = chunk.x();
            chunkY = chunk.y();
            north = chunk.originX();
            west = chunk.originY();
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        public int chunkX() {
            return chunkX;
        }

        public int chunkY() {
            return chunkY;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /**
         * Row follows source Y/south; column follows source X/east. Height is absolute, never
         * filled.
         */
        public TerrainGrid.Vec3 vertex(int row, int column, float actualHeight) {
            if (row < 0 || row > height || column < 0 || column > width) {
                throw new IllegalArgumentException("Liquid vertex outside rectangle");
            }
            coordinate(actualHeight);
            // Same arithmetic as even/V9 terrain samples; raw native +X north,+Y west,+Z up.
            float px = (float) (north - (2 * (y + row)) * (WorldFormats.TILE_SIZE / 256));
            float py = (float) (west - (x + column) * (WorldFormats.TILE_SIZE / 128));
            coordinate(px);
            coordinate(py);
            return new TerrainGrid.Vec3(px, py, actualHeight);
        }
    }

    /**
     * Header h means MCNK ix=h%16,iy=h/16, irrespective of MCNK parse/list order. Caller
     * authenticates the common root and separately qualifies the rectangle/mask/height. A
     * caller-made TerrainGrid/rectangle or successful mapping never certifies Source geometry.
     */
    public static Mapping rectangle(
            TerrainGrid grid, int header, int x, int y, int width, int height) {
        Objects.requireNonNull(grid);
        if (header < 0
                || header >= 256
                || x < 0
                || y < 0
                || width < 1
                || height < 1
                || width > 8
                || height > 8
                || x + width > 8
                || y + height > 8) {
            throw new IllegalArgumentException("Liquid rectangle profile");
        }
        int chunkX = header % 16, chunkY = header / 16;
        TerrainGrid.Chunk found = null;
        for (var chunk : grid.chunks()) {
            if (chunk.x() == chunkX && chunk.y() == chunkY) {
                if (found != null) {
                    throw new IllegalArgumentException("Duplicate liquid chunk key");
                }
                found = chunk;
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("Liquid chunk key absent");
        }
        return new Mapping(found, x, y, width, height);
    }

    private static void coordinate(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
            throw new IllegalArgumentException("Liquid coordinate profile");
        }
    }
}
