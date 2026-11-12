package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The terrain's liquid surface (ADT MH2O cells, as the World draws them) under a native XY point:
 * its height and what liquid it is. A body uses it to swim. Each liquid cell is a square of the
 * terrain's cell grid (a 16th of a chunk side) with its own corner heights, split on its NE--SW
 * diagonal like the drawn quad. Where several liquid layers cover a point, the highest surface
 * answers.
 *
 * <p>Only terrain liquid is known here. Water inside buildings (WMO liquid, such as the Stormwind
 * canals) and the older MCLQ chunk liquid are not read, so a body walks through them.
 *
 * <p>Immutable and thread-safe.
 */
public final class LiquidSurface {
    /** What a liquid cell holds. */
    public enum Kind {
        /** Lake or river water. */
        WATER,
        /** Water the client draws as ocean. */
        OCEAN,
        /** Magma (LiquidType 3). */
        MAGMA,
        /** Slime (LiquidType 4). */
        SLIME,
        /** Another non-water liquid, drawn in its flat colour. */
        OTHER
    }

    /** The surface at one point: its height (native z) and kind. */
    public record Sample(double height, Kind kind) {}

    /** One retained tile holds at most every cell of four overlapping layers. */
    public static final int MAX_CELLS = 4 * 128 * 128;

    /** The World stream's residents plus staged and retiring tiles, as {@link TerrainGround}. */
    public static final int MAX_TILES = TerrainGround.MAX_TILES;

    static final double CELL = WorldFormats.TILE_SIZE / 128;

    private static final LiquidSurface EMPTY = new LiquidSurface(List.of());
    private static final Kind[] KINDS = Kind.values();

    /** Liquid cells of one terrain tile. */
    public static final class Tile {
        // Per cell: NW corner (north, west), then NW, NE, SW, SE heights.
        private final float[] cells;
        private final byte[] kinds;
        // Global cell key -> cell ordinals there.
        private final Map<Long, int[]> index;

        private Tile(float[] cells, byte[] kinds, Map<Long, int[]> index) {
            this.cells = cells;
            this.kinds = kinds;
            this.index = index;
        }

        public int cells() {
            return kinds.length;
        }
    }

    /** Collects the cells of one tile; {@link #build} qualifies them. */
    public static final class Builder {
        private final ArrayList<float[]> cells = new ArrayList<>();
        private final ArrayList<Kind> kinds = new ArrayList<>();

        /**
         * One cell from its four corners in native coordinates (x north, y west), NW, NE, SW, SE;
         * only the heights of NE, SW and SE are read beyond the NW corner's position, which must
         * lie on the terrain cell grid.
         */
        public Builder cell(
                double north, double west, double nw, double ne, double sw, double se, Kind kind) {
            Objects.requireNonNull(kind);
            double[] values = {north, west, nw, ne, sw, se};
            for (double v : values) {
                if (!Double.isFinite(v) || Math.abs(v) > 1_000_000) {
                    throw new IllegalArgumentException("Liquid cell coordinate");
                }
            }
            if (cells.size() >= MAX_CELLS) {
                throw new IllegalArgumentException("Too many liquid cells");
            }
            cells.add(
                    new float[] {
                        (float) north, (float) west, (float) nw, (float) ne, (float) sw, (float) se
                    });
            kinds.add(kind);
            return this;
        }

        /** The tile, or null when no cell was given. */
        public Tile build() {
            if (cells.isEmpty()) {
                return null;
            }
            int count = cells.size();
            var flat = new float[count * 6];
            var kindBytes = new byte[count];
            var lists = new HashMap<Long, List<Integer>>();
            for (int i = 0; i < count; i++) {
                System.arraycopy(cells.get(i), 0, flat, i * 6, 6);
                kindBytes[i] = (byte) kinds.get(i).ordinal();
                long key =
                        key(
                                Math.round(flat[i * 6] / CELL) - 1,
                                Math.round(flat[i * 6 + 1] / CELL) - 1);
                lists.computeIfAbsent(key, k -> new ArrayList<>(1)).add(i);
            }
            var index = new HashMap<Long, int[]>(lists.size() * 2);
            lists.forEach(
                    (key, list) ->
                            index.put(key, list.stream().mapToInt(Integer::intValue).toArray()));
            return new Tile(flat, kindBytes, index);
        }
    }

    private final List<Tile> tiles;

    private LiquidSurface(List<Tile> tiles) {
        this.tiles = tiles;
    }

    public static LiquidSurface empty() {
        return EMPTY;
    }

    /** Null tiles (no liquid) are skipped. */
    public static LiquidSurface of(List<Tile> tiles) {
        Objects.requireNonNull(tiles);
        var kept = tiles.stream().filter(Objects::nonNull).toList();
        if (kept.size() > MAX_TILES) {
            throw new IllegalArgumentException("Too many liquid tiles");
        }
        return kept.isEmpty() ? EMPTY : new LiquidSurface(kept);
    }

    public int tiles() {
        return tiles.size();
    }

    /** The highest liquid surface over the point, or null where no liquid cell covers it. */
    public Sample at(double x, double y) {
        if (tiles.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        long gx = (long) Math.floor(x / CELL), gy = (long) Math.floor(y / CELL);
        long key = key(gx, gy);
        double best = Double.NEGATIVE_INFINITY;
        Kind kind = null;
        for (var tile : tiles) {
            int[] found = tile.index.get(key);
            if (found == null) {
                continue;
            }
            for (int cell : found) {
                double h = height(tile.cells, cell * 6, x, y);
                if (h > best) {
                    best = h;
                    kind = KINDS[tile.kinds[cell]];
                }
            }
        }
        return kind == null ? null : new Sample(best, kind);
    }

    /**
     * Height on the cell's two triangles, split NE--SW as drawn: u runs west to east, v north to
     * south, both 0..1 over the cell.
     */
    private static double height(float[] cells, int at, double x, double y) {
        double u = Math.clamp((cells[at + 1] - y) / CELL, 0, 1);
        double v = Math.clamp((cells[at] - x) / CELL, 0, 1);
        double nw = cells[at + 2], ne = cells[at + 3], sw = cells[at + 4], se = cells[at + 5];
        return u + v <= 1
                ? nw + u * (ne - nw) + v * (sw - nw)
                : se + (1 - u) * (sw - se) + (1 - v) * (ne - se);
    }

    private static long key(long gx, long gy) {
        return gx << 32 ^ (gy & 0xFFFFFFFFL);
    }
}
