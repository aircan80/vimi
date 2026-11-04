package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

/**
 * Height of the rendered terrain surface under a native XY point. This is surface sampling of the
 * already admitted ADT18 triangle fans, not collision, walkability or floor inference: WMO floors,
 * doodads, water and steep slopes are not considered. Holes and unqualified layouts are reported as
 * unavailable rather than interpolated.
 */
public final class TerrainGround {
    public static final int MAX_TILES = 12;
    private static final int GRID = 145, CELLS = 64, MAX_CHUNKS = 256;
    private static final double UNIT = WorldFormats.TILE_SIZE / 256, CELL = 2 * UNIT;
    private static final float POSITION_TOLERANCE = .01f;
    private static final double SEAM = .01, SEAM_CELLS = SEAM / CELL;
    private static final TerrainGround EMPTY = new TerrainGround(List.of());

    /**
     * Compact retained heights of one tile: 145 absolute heights and a rendered-cell mask per
     * chunk.
     */
    public static final class Tile {
        private final float[] originX, originY, heights;
        private final long[] rendered;
        private final float minX, minY, maxX, maxY;

        private Tile(
                float[] originX, float[] originY, float[] heights, long[] rendered, float[] box) {
            this.originX = originX;
            this.originY = originY;
            this.heights = heights;
            this.rendered = rendered;
            minX = box[0];
            minY = box[1];
            maxX = box[2];
            maxY = box[3];
        }

        public int chunks() {
            return originX.length;
        }

        /**
         * Qualifies the parser's source-order layout: 145 vertices per chunk on the documented
         * 17-row grid and one 12-index fan per rendered cell. Anything else returns null, so
         * callers keep free flight instead of guessing heights for a different mesh.
         */
        public static Tile of(FloatBuffer vertices, IntBuffer indices) {
            Objects.requireNonNull(vertices);
            Objects.requireNonNull(indices);
            var v = vertices.duplicate();
            var t = indices.duplicate();
            int vertexFloats = v.remaining(), indexCount = t.remaining();
            if (vertexFloats == 0
                    || vertexFloats % (GRID * 3) != 0
                    || vertexFloats / (GRID * 3) > MAX_CHUNKS
                    || indexCount % 12 != 0) {
                return null;
            }
            int chunks = vertexFloats / (GRID * 3);
            float[] ox = new float[chunks], oy = new float[chunks], z = new float[chunks * GRID];
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            int base = v.position();
            for (int chunk = 0; chunk < chunks; chunk++) {
                int first = base + chunk * GRID * 3;
                float px = v.get(first), py = v.get(first + 1);
                if (!Float.isFinite(px) || !Float.isFinite(py)) {
                    return null;
                }
                ox[chunk] = px;
                oy[chunk] = py;
                for (int row = 0, at = 0; row < 17; row++) {
                    for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++, at++) {
                        int offset = first + at * 3;
                        float ex = (float) (px - row * UNIT),
                                ey = (float) (py - col * CELL - (row % 2) * UNIT);
                        float vz = v.get(offset + 2);
                        if (Math.abs(v.get(offset) - ex) > POSITION_TOLERANCE
                                || Math.abs(v.get(offset + 1) - ey) > POSITION_TOLERANCE
                                || !Float.isFinite(vz)) {
                            return null;
                        }
                        z[chunk * GRID + at] = vz;
                    }
                }
                box[0] = Math.min(box[0], (float) (px - 16 * UNIT));
                box[1] = Math.min(box[1], (float) (py - 8 * CELL));
                box[2] = Math.max(box[2], px);
                box[3] = Math.max(box[3], py);
            }
            long[] rendered = new long[chunks];
            int indexBase = t.position();
            for (int fan = 0; fan < indexCount; fan += 12) {
                int a = t.get(indexBase + fan), m = t.get(indexBase + fan + 1);
                int chunk = a / GRID, local = a - chunk * GRID, row = local / 17, col = local % 17;
                if (a < 0 || chunk >= chunks || col >= 8 || row >= 8 || m != a + 9) {
                    return null;
                }
                int b = a + 1, d = a + 17, e = d + 1;
                int[] expected = {a, m, b, b, m, e, e, m, d, d, m, a};
                for (int i = 0; i < 12; i++) {
                    if (t.get(indexBase + fan + i) != expected[i]) {
                        return null;
                    }
                }
                rendered[chunk] |= 1L << (row * 8 + col);
            }
            return new Tile(ox, oy, z, rendered, box);
        }

        /** Copies only the retained heights; source arrays are not referenced afterwards. */
        public static Tile of(TerrainScene.Tile tile) {
            return of(Objects.requireNonNull(tile).vertices(), tile.indices());
        }

        double heightAt(double x, double y) {
            if (x < minX - SEAM || x > maxX + SEAM || y < minY - SEAM || y > maxY + SEAM) {
                return Double.NaN;
            }
            for (int chunk = 0; chunk < originX.length; chunk++) {
                double s = (originX[chunk] - x) / CELL, t = (originY[chunk] - y) / CELL;
                // Float-rounded neighbor origins leave tiny seams; accept a bounded overlap.
                if (s < -SEAM_CELLS
                        || s > 8 + SEAM_CELLS
                        || t < -SEAM_CELLS
                        || t > 8 + SEAM_CELLS) {
                    continue;
                }
                int row = Math.clamp((int) Math.floor(s), 0, 7),
                        col = Math.clamp((int) Math.floor(t), 0, 7);
                if ((rendered[chunk] >>> (row * 8 + col) & 1) == 0) {
                    continue;
                }
                return cellHeight(chunk, row, col, x, y);
            }
            return Double.NaN;
        }

        private double cellHeight(int chunk, int row, int col, double x, double y) {
            int a = row * 17 + col, b = a + 1, d = a + 17, e = d + 1, m = a + 9;
            double px = originX[chunk], py = originY[chunk];
            // Local fan coordinates: s toward the d/e edge (native -X), t toward b/e (native -Y).
            double s = (px - 2 * row * UNIT - x) / CELL - .5, t = (py - col * CELL - y) / CELL - .5;
            int p, q;
            if (Math.abs(s) >= Math.abs(t)) {
                p = s < 0 ? a : e;
                q = s < 0 ? b : d;
            } else {
                p = t < 0 ? d : b;
                q = t < 0 ? a : e;
            }
            return barycentric(chunk, p, m, q, x, y);
        }

        private double barycentric(int chunk, int p, int m, int q, double x, double y) {
            double[] pp = position(chunk, p), pm = position(chunk, m), pq = position(chunk, q);
            double det = (pm[1] - pq[1]) * (pp[0] - pq[0]) + (pq[0] - pm[0]) * (pp[1] - pq[1]);
            double wp = ((pm[1] - pq[1]) * (x - pq[0]) + (pq[0] - pm[0]) * (y - pq[1])) / det;
            double wm = ((pq[1] - pp[1]) * (x - pq[0]) + (pp[0] - pq[0]) * (y - pq[1])) / det;
            double wq = 1 - wp - wm;
            int base = chunk * GRID;
            return wp * heights[base + p] + wm * heights[base + m] + wq * heights[base + q];
        }

        private double[] position(int chunk, int at) {
            int row = 0, rest = at;
            while (rest >= (row % 2 == 0 ? 9 : 8)) {
                rest -= row % 2 == 0 ? 9 : 8;
                row++;
            }
            return new double[] {
                (float) (originX[chunk] - row * UNIT),
                (float) (originY[chunk] - rest * CELL - (row % 2) * UNIT)
            };
        }
    }

    private final List<Tile> tiles;

    private TerrainGround(List<Tile> tiles) {
        this.tiles = tiles;
    }

    public static TerrainGround empty() {
        return EMPTY;
    }

    /** Unqualified (null) tiles are skipped; their area remains unavailable. */
    public static TerrainGround of(List<Tile> tiles) {
        Objects.requireNonNull(tiles);
        var qualified = tiles.stream().filter(Objects::nonNull).toList();
        if (qualified.size() > MAX_TILES) {
            throw new IllegalArgumentException("Too many ground tiles");
        }
        return qualified.isEmpty() ? EMPTY : new TerrainGround(qualified);
    }

    public int tiles() {
        return tiles.size();
    }

    /** Rendered terrain height, or NaN for holes, unloaded or unqualified coverage. */
    public double heightAt(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return Double.NaN;
        }
        for (var tile : tiles) {
            double height = tile.heightAt(x, y);
            if (!Double.isNaN(height)) {
                return height;
            }
        }
        return Double.NaN;
    }
}
