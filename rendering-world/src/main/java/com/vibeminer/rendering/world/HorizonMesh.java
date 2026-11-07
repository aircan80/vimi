package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.formats.world.WorldLowRes;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * A low-resolution horizon mesh from a map's WDL, for the tiles in a square window around the
 * camera tile that are not drawn at full detail.
 *
 * <p>Positions use the same native axes and tile origins as ADT chunks: a tile's origin is X = (32
 * - tile.x) * T, Y = (32 - tile.y) * T, with X decreasing along a row and Y along a column at T /
 * 16 spacing. Each 16 x 16 cell is the ADT chunk's four-triangle fan (corner, centre, corner) with
 * the same winding. Hole cells are left out.
 *
 * <p>Tiles drawn at full detail are not drawn here, so the two never overlap; they meet along tile
 * edges. There the full tile's edge follows every ADT sample while the horizon's follows only every
 * chunk corner, so the horizon sits {@link #SINK} lower than its source heights to stay under the
 * full edge, and along an edge shared with a full-detail tile a skirt, facing that tile, drops
 * {@link #SKIRT} further to close the gap. Known limit: where the full tile's edge dips more than
 * {@code SINK + SKIRT} below the horizon's (a cliff between chunk corners), a gap can still show;
 * fog hides it at distance.
 *
 * <p>The mesh keeps each tile's index ranges (its surface, and one skirt per edge), so a renderer
 * can leave out full-detail tiles and draw only the skirts that face them at draw time, without a
 * rebuild whenever the full-detail set changes ({@link #buildWindow}).
 */
public final class HorizonMesh {
    public static final int MAX_RADIUS = 8;
    public static final float SINK = 2f, SKIRT = 24f;
    public static final int VERTICES_PER_TILE = WorldLowRes.HEIGHTS, TRIANGLES_PER_TILE = 1024;

    /** Skirt edges, by the neighbour each faces: x - 1, x + 1, y - 1, y + 1. */
    public static final int[][] EDGE_NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    /** Skirt vertices and triangles per tile: four edges of 17 corners and 16 quads each. */
    private static final int SKIRT_VERTICES = 4 * 17, SKIRT_TRIANGLES = 4 * 16 * 2;

    private final float[] positions;
    private final int[] indices;
    private final int tiles, centreX, centreY, radius;
    // Per drawn tile: coordinates, surface index range, and per edge (4 each) a skirt range whose
    // count is 0 when the edge has none. Surfaces come first in window order, then the skirts.
    private final int[] tileX, tileY, surfaceStart, surfaceCount, skirtStart, skirtCount;

    private HorizonMesh(
            float[] positions,
            int[] indices,
            int centreX,
            int centreY,
            int radius,
            int[] tileX,
            int[] tileY,
            int[] surfaceStart,
            int[] surfaceCount,
            int[] skirtStart,
            int[] skirtCount) {
        this.positions = positions;
        this.indices = indices;
        this.tiles = tileX.length;
        this.centreX = centreX;
        this.centreY = centreY;
        this.radius = radius;
        this.tileX = tileX;
        this.tileY = tileY;
        this.surfaceStart = surfaceStart;
        this.surfaceCount = surfaceCount;
        this.skirtStart = skirtStart;
        this.skirtCount = skirtCount;
    }

    /**
     * Each tile's index ranges, kept apart from the vertex and index arrays so a renderer can hold
     * them after the upload. Immutable.
     */
    public static final class Ranges {
        private final int tiles;
        private final int[] tileX, tileY, surfaceStart, surfaceCount, skirtStart, skirtCount;

        private Ranges(
                int[] tileX,
                int[] tileY,
                int[] surfaceStart,
                int[] surfaceCount,
                int[] skirtStart,
                int[] skirtCount) {
            this.tiles = tileX.length;
            this.tileX = tileX;
            this.tileY = tileY;
            this.surfaceStart = surfaceStart;
            this.surfaceCount = surfaceCount;
            this.skirtStart = skirtStart;
            this.skirtCount = skirtCount;
        }

        /**
         * Index ranges to draw now, as (start, count) pairs into {@code out}, for a mesh from
         * {@link #buildWindow}: every tile not drawn at full detail, consecutive tiles merged into
         * one range, and the skirts of those tiles that face a full-detail tile. Returns the number
         * of ints written. {@code out} needs room for {@link #maxDrawRangeInts()} ints.
         */
        public int drawRanges(Hidden fullDetail, int[] out) {
            Objects.requireNonNull(fullDetail);
            int written = 0, runStart = -1, runEnd = -1;
            for (int i = 0; i < tiles; i++) {
                if (fullDetail.hidden(tileX[i], tileY[i]) || surfaceCount[i] == 0) {
                    continue;
                }
                if (runStart >= 0 && surfaceStart[i] == runEnd) {
                    runEnd += surfaceCount[i];
                } else {
                    if (runStart >= 0) {
                        out[written++] = runStart;
                        out[written++] = runEnd - runStart;
                    }
                    runStart = surfaceStart[i];
                    runEnd = runStart + surfaceCount[i];
                }
            }
            if (runStart >= 0) {
                out[written++] = runStart;
                out[written++] = runEnd - runStart;
            }
            for (int i = 0; i < tiles; i++) {
                if (fullDetail.hidden(tileX[i], tileY[i])) {
                    continue;
                }
                for (int e = 0; e < 4; e++) {
                    int count = skirtCount[i * 4 + e];
                    if (count > 0
                            && fullDetail.hidden(
                                    tileX[i] + EDGE_NEIGHBOURS[e][0],
                                    tileY[i] + EDGE_NEIGHBOURS[e][1])) {
                        out[written++] = skirtStart[i * 4 + e];
                        out[written++] = count;
                    }
                }
            }
            return written;
        }

        /** Room {@link #drawRanges} may need: one range per tile surface and per skirt. */
        public int maxDrawRangeInts() {
            return tiles * 2 + tiles * 4 * 2;
        }
    }

    /** This mesh's per-tile index ranges. */
    public Ranges ranges() {
        return new Ranges(tileX, tileY, surfaceStart, surfaceCount, skirtStart, skirtCount);
    }

    /** Tiles that are drawn at full detail, keyed by WDT coordinates. */
    @FunctionalInterface
    public interface Hidden {
        boolean hidden(int x, int y);
    }

    /**
     * Builds the horizon for tiles within Chebyshev {@code radius} of the camera tile. Tiles the
     * WDL lacks or {@code hidden} reports are skipped, and only edges facing a hidden tile get a
     * skirt.
     */
    public static HorizonMesh build(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            int radius,
            Hidden hidden,
            BooleanSupplier canceled) {
        Objects.requireNonNull(hidden);
        return build(lowRes, cameraX, cameraY, radius, hidden, false, canceled);
    }

    /**
     * Builds every WDL tile of the window, each with a skirt on all four edges, for a renderer that
     * picks tiles and skirts at draw time ({@link Ranges#drawRanges}).
     */
    public static HorizonMesh buildWindow(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            int radius,
            BooleanSupplier canceled) {
        return build(lowRes, cameraX, cameraY, radius, (x, y) -> false, true, canceled);
    }

    private static HorizonMesh build(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            int radius,
            Hidden hidden,
            boolean everySkirt,
            BooleanSupplier canceled) {
        Objects.requireNonNull(lowRes);
        Objects.requireNonNull(canceled);
        if (cameraX < 0
                || cameraX >= 64
                || cameraY < 0
                || cameraY >= 64
                || radius < 0
                || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Invalid horizon window");
        }
        int side = 2 * radius + 1;
        boolean[] drawn = new boolean[side * side];
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                int x = cameraX + dx, y = cameraY + dy;
                if (lowRes.tile(x, y) != null && !hidden.hidden(x, y)) {
                    drawn[(dx + radius) * side + dy + radius] = true;
                    count++;
                }
            }
        }
        float[] positions = new float[count * (VERTICES_PER_TILE + SKIRT_VERTICES) * 3];
        int[] indices = new int[count * TRIANGLES_PER_TILE * 3];
        int[] skirts = new int[count * SKIRT_TRIANGLES * 3];
        int[] tileX = new int[count], tileY = new int[count];
        int[] surfaceStart = new int[count], surfaceCount = new int[count];
        int[] skirtStart = new int[count * 4], skirtCount = new int[count * 4];
        int vertex = 0, index = 0, skirtIndex = 0, drawnTile = 0;
        double t = WorldFormats.TILE_SIZE, step = t / 16;
        // {corner of the edge's first sample (row, column), row step, column step, flip}, in
        // EDGE_NEIGHBOURS order. Walking an edge in that order gives faces toward +X along a row
        // edge and toward -Y along a column edge; flip reverses them so every skirt faces its
        // full-detail neighbour.
        int[][] edges = {{0, 0, 0, 1, 0}, {16, 0, 0, 1, 1}, {0, 0, 1, 0, 1}, {0, 16, 1, 0, 0}};
        for (int dx = -radius; dx <= radius; dx++) {
            check(canceled);
            for (int dy = -radius; dy <= radius; dy++) {
                if (!drawn[(dx + radius) * side + dy + radius]) {
                    continue;
                }
                int x = cameraX + dx, y = cameraY + dy;
                var tile = lowRes.tile(x, y);
                double originX = (32 - x) * t, originY = (32 - y) * t;
                tileX[drawnTile] = x;
                tileY[drawnTile] = y;
                surfaceStart[drawnTile] = index;
                int base = vertex;
                for (int row = 0; row < 17; row++) {
                    for (int column = 0; column < 17; column++) {
                        put(
                                positions,
                                vertex++,
                                originX - row * step,
                                originY - column * step,
                                tile.corner(row, column) - SINK);
                    }
                }
                int centres = vertex;
                for (int row = 0; row < 16; row++) {
                    for (int column = 0; column < 16; column++) {
                        put(
                                positions,
                                vertex++,
                                originX - (row + .5) * step,
                                originY - (column + .5) * step,
                                tile.centre(row, column) - SINK);
                    }
                }
                for (int row = 0; row < 16; row++) {
                    for (int column = 0; column < 16; column++) {
                        if (tile.hole(row, column)) {
                            continue;
                        }
                        int a = base + row * 17 + column, b = a + 1, d = a + 17, e = d + 1;
                        int m = centres + row * 16 + column;
                        index = triangle(indices, index, a, m, b);
                        index = triangle(indices, index, b, m, e);
                        index = triangle(indices, index, e, m, d);
                        index = triangle(indices, index, d, m, a);
                    }
                }
                surfaceCount[drawnTile] = index - surfaceStart[drawnTile];
                for (int e = 0; e < 4; e++) {
                    int nx = x + EDGE_NEIGHBOURS[e][0], ny = y + EDGE_NEIGHBOURS[e][1];
                    boolean inside = nx >= 0 && nx < 64 && ny >= 0 && ny < 64;
                    if (!inside || !everySkirt && !hidden.hidden(nx, ny)) {
                        continue;
                    }
                    int[] edge = edges[e];
                    skirtStart[drawnTile * 4 + e] = skirtIndex;
                    int skirt = vertex;
                    for (int i = 0; i < 17; i++) {
                        int row = edge[0] + i * edge[2], column = edge[1] + i * edge[3];
                        put(
                                positions,
                                vertex++,
                                originX - row * step,
                                originY - column * step,
                                tile.corner(row, column) - SINK - SKIRT);
                    }
                    for (int i = 0; i < 16; i++) {
                        int row = edge[0] + i * edge[2], column = edge[1] + i * edge[3];
                        int top = base + row * 17 + column;
                        int next = base + (row + edge[2]) * 17 + column + edge[3];
                        if (edge[4] == 0) {
                            skirtIndex = triangle(skirts, skirtIndex, top, next, skirt + i);
                            skirtIndex =
                                    triangle(skirts, skirtIndex, next, skirt + i + 1, skirt + i);
                        } else {
                            skirtIndex = triangle(skirts, skirtIndex, top, skirt + i, next);
                            skirtIndex =
                                    triangle(skirts, skirtIndex, next, skirt + i, skirt + i + 1);
                        }
                    }
                    skirtCount[drawnTile * 4 + e] = skirtIndex - skirtStart[drawnTile * 4 + e];
                }
                drawnTile++;
            }
        }
        check(canceled);
        // Skirts follow every surface, so the surfaces of neighbouring drawn tiles stay one run.
        int[] all = Arrays.copyOf(indices, index + skirtIndex);
        System.arraycopy(skirts, 0, all, index, skirtIndex);
        for (int i = 0; i < skirtStart.length; i++) {
            if (skirtCount[i] > 0) {
                skirtStart[i] += index;
            }
        }
        return new HorizonMesh(
                Arrays.copyOf(positions, vertex * 3),
                all,
                cameraX,
                cameraY,
                radius,
                tileX,
                tileY,
                surfaceStart,
                surfaceCount,
                skirtStart,
                skirtCount);
    }

    private static void put(float[] positions, int vertex, double x, double y, double z) {
        positions[vertex * 3] = (float) x;
        positions[vertex * 3 + 1] = (float) y;
        positions[vertex * 3 + 2] = (float) z;
    }

    private static int triangle(int[] indices, int at, int a, int b, int c) {
        indices[at] = a;
        indices[at + 1] = b;
        indices[at + 2] = c;
        return at + 3;
    }

    public FloatBuffer positions() {
        return FloatBuffer.wrap(positions).asReadOnlyBuffer();
    }

    public IntBuffer indices() {
        return IntBuffer.wrap(indices).asReadOnlyBuffer();
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    /** Horizon tiles in this mesh (in the window, present in the WDL and not hidden). */
    public int tiles() {
        return tiles;
    }

    public int centreX() {
        return centreX;
    }

    public int centreY() {
        return centreY;
    }

    public int radius() {
        return radius;
    }

    public long payloadBytes() {
        return positions.length * 4L + indices.length * 4L;
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
