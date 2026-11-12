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
 * <p>Liquid placed with buildings (WMO group MLIQ, such as the Stormwind canals) joins it through
 * {@link #with}: each liquid in its own coordinates ({@link Local}) with its building's transform;
 * the highest surface of terrain and buildings answers. The older MCLQ chunk liquid is not read.
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

    /** A liquid in its own coordinates, such as a WMO group's MLIQ. */
    public interface Local {
        /** The surface height at local (x, y), or NaN where it holds no liquid. */
        double heightAt(double x, double y);

        /** The local box it covers: minimum x, y, maximum x, y. */
        double[] bounds();

        /** Its lowest and highest surface height, local. */
        double minHeight();

        double maxHeight();

        /** Its LiquidType ID, 0 when unknown (informational: the kind is {@link Placed#kind}). */
        default int liquidType() {
            return 0;
        }
    }

    /**
     * A local liquid placed in the world: native = origin + scale * rotation * local, with the
     * row-major rotation of its building (the collision transform). {@code key} names the placed
     * liquid (its building and group), so a building recorded on several tiles counts once. Only a
     * rotation that keeps up pointing up (a heading, no pitch or roll) is admitted ({@link
     * #upright}), so that a vertical line in the world is one in the liquid too.
     */
    public record Placed(
            java.lang.Object key,
            Local liquid,
            Kind kind,
            double[] rotation,
            double scale,
            double x,
            double y,
            double z) {
        public Placed {
            Objects.requireNonNull(key);
            Objects.requireNonNull(liquid);
            Objects.requireNonNull(kind);
            rotation = rotation.clone();
            if (!upright(rotation)
                    || !(scale > 0)
                    || !Double.isFinite(scale)
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid liquid placement");
            }
        }

        @Override
        public double[] rotation() {
            return rotation.clone();
        }
    }

    /** Whether a row-major rotation turns only about +Z (no pitch or roll), within 1e-4. */
    public static boolean upright(double[] r) {
        if (r == null || r.length != 9) {
            return false;
        }
        for (double v : r) {
            if (!Double.isFinite(v)) {
                return false;
            }
        }
        return Math.abs(r[2]) <= 1e-4
                && Math.abs(r[5]) <= 1e-4
                && Math.abs(r[6]) <= 1e-4
                && Math.abs(r[7]) <= 1e-4
                && Math.abs(r[8] - 1) <= 1e-4;
    }

    /** Most placed liquids one surface holds: the 3 x 3 tiles of Stormwind are well under it. */
    public static final int MAX_PLACED = 4096;

    /** A placed liquid with its native box, for a quick reject. */
    private record Boxed(Placed placed, double x0, double y0, double x1, double y1) {}

    /** One retained tile holds at most every cell of four overlapping layers. */
    public static final int MAX_CELLS = 4 * 128 * 128;

    /** The World stream's residents plus staged and retiring tiles, as {@link TerrainGround}. */
    public static final int MAX_TILES = TerrainGround.MAX_TILES;

    static final double CELL = WorldFormats.TILE_SIZE / 128;

    private static final LiquidSurface EMPTY = new LiquidSurface(List.of(), List.of());
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
    private final List<Boxed> objects;

    private LiquidSurface(List<Tile> tiles, List<Boxed> objects) {
        this.tiles = tiles;
        this.objects = objects;
    }

    /**
     * This terrain liquid with {@code placed} liquids (buildings) on top, replacing any before; a
     * key given more than once counts once, and past {@link #MAX_PLACED} the rest are left out.
     */
    public LiquidSurface with(List<Placed> placed) {
        Objects.requireNonNull(placed);
        var boxed = new java.util.ArrayList<Boxed>(Math.min(placed.size(), MAX_PLACED));
        var keys = new java.util.HashSet<java.lang.Object>();
        for (var p : placed) {
            if (boxed.size() >= MAX_PLACED || !keys.add(p.key())) {
                continue;
            }
            double[] b = p.liquid().bounds(), r = p.rotation;
            double x0 = Double.POSITIVE_INFINITY, y0 = Double.POSITIVE_INFINITY;
            double x1 = Double.NEGATIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < 4; c++) {
                double a = (c & 1) == 0 ? b[0] : b[2], d = (c & 2) == 0 ? b[1] : b[3];
                double wx = p.x() + p.scale() * (r[0] * a + r[1] * d);
                double wy = p.y() + p.scale() * (r[3] * a + r[4] * d);
                x0 = Math.min(x0, wx);
                y0 = Math.min(y0, wy);
                x1 = Math.max(x1, wx);
                y1 = Math.max(y1, wy);
            }
            boxed.add(new Boxed(p, x0, y0, x1, y1));
        }
        return tiles.isEmpty() && boxed.isEmpty()
                ? EMPTY
                : new LiquidSurface(tiles, List.copyOf(boxed));
    }

    /** Placed (building) liquids this surface holds. */
    public int placed() {
        return objects.size();
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
        return kept.isEmpty() ? EMPTY : new LiquidSurface(kept, List.of());
    }

    public int tiles() {
        return tiles.size();
    }

    /**
     * The highest liquid surface over the point, terrain or placed, or null where no liquid covers
     * it.
     */
    public Sample at(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        Sample terrain = terrainAt(x, y);
        double best = terrain == null ? Double.NEGATIVE_INFINITY : terrain.height();
        Kind kind = terrain == null ? null : terrain.kind();
        for (var o : objects) {
            if (x < o.x0 || x > o.x1 || y < o.y0 || y > o.y1) {
                continue;
            }
            var p = o.placed;
            double[] r = p.rotation;
            double dx = x - p.x(), dy = y - p.y();
            // The inverse of an upright rotation: its transpose, in the horizontal plane.
            double a = (r[0] * dx + r[3] * dy) / p.scale(), b = (r[1] * dx + r[4] * dy) / p.scale();
            double h = p.liquid().heightAt(a, b);
            if (Double.isNaN(h)) {
                continue;
            }
            double height = p.z() + p.scale() * h;
            if (height > best) {
                best = height;
                kind = p.kind();
            }
        }
        return kind == null ? null : new Sample(best, kind);
    }

    private Sample terrainAt(double x, double y) {
        if (tiles.isEmpty()) {
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
