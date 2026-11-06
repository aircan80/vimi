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
 * <p>Tiles drawn at full detail are left out, so the two never overlap; they meet along tile edges.
 * There the full tile's edge follows every ADT sample while the horizon's follows only every chunk
 * corner, so the horizon sits {@link #SINK} lower than its source heights to stay under the full
 * edge, and along an edge shared with a full-detail tile a skirt, facing that tile, drops {@link
 * #SKIRT} further to close the gap. Known limit: where the full tile's edge dips more than {@code
 * SINK + SKIRT} below the horizon's (a cliff between chunk corners), a gap can still show; fog
 * hides it at distance.
 */
public final class HorizonMesh {
    public static final int MAX_RADIUS = 8;
    public static final float SINK = 2f, SKIRT = 24f;
    public static final int VERTICES_PER_TILE = WorldLowRes.HEIGHTS, TRIANGLES_PER_TILE = 1024;

    /** Skirt vertices and triangles per tile: four edges of 17 corners and 16 quads each. */
    private static final int SKIRT_VERTICES = 4 * 17, SKIRT_TRIANGLES = 4 * 16 * 2;

    private final float[] positions;
    private final int[] indices;
    private final int tiles, centreX, centreY, radius;

    private HorizonMesh(
            float[] positions, int[] indices, int tiles, int centreX, int centreY, int radius) {
        this.positions = positions;
        this.indices = indices;
        this.tiles = tiles;
        this.centreX = centreX;
        this.centreY = centreY;
        this.radius = radius;
    }

    /** Tiles that are drawn at full detail, keyed by WDT coordinates. */
    @FunctionalInterface
    public interface Hidden {
        boolean hidden(int x, int y);
    }

    /**
     * Builds the horizon for tiles within Chebyshev {@code radius} of the camera tile. Tiles the
     * WDL lacks or {@code hidden} reports are skipped.
     */
    public static HorizonMesh build(
            WorldLowRes.LowRes lowRes,
            int cameraX,
            int cameraY,
            int radius,
            Hidden hidden,
            BooleanSupplier canceled) {
        Objects.requireNonNull(lowRes);
        Objects.requireNonNull(hidden);
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
        int[] indices = new int[count * (TRIANGLES_PER_TILE + SKIRT_TRIANGLES) * 3];
        int vertex = 0, index = 0;
        double t = WorldFormats.TILE_SIZE, step = t / 16;
        for (int dx = -radius; dx <= radius; dx++) {
            check(canceled);
            for (int dy = -radius; dy <= radius; dy++) {
                if (!drawn[(dx + radius) * side + dy + radius]) {
                    continue;
                }
                int x = cameraX + dx, y = cameraY + dy;
                var tile = lowRes.tile(x, y);
                double originX = (32 - x) * t, originY = (32 - y) * t;
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
                // Skirts on edges shared with a full-detail tile: {neighbour dx, dy, corner of
                // the edge's first sample, row step, column step, flip}. Walking the edge in
                // that order gives faces toward +X along a row edge and toward -Y along a column
                // edge; flip reverses them so every skirt faces its full-detail neighbour.
                int[][] edges = {
                    {-1, 0, 0, 0, 0, 1, 0},
                    {1, 0, 16, 0, 0, 1, 1},
                    {0, -1, 0, 0, 1, 0, 1},
                    {0, 1, 0, 16, 1, 0, 0}
                };
                for (int[] edge : edges) {
                    int nx = x + edge[0], ny = y + edge[1];
                    if (nx < 0 || nx >= 64 || ny < 0 || ny >= 64 || !hidden.hidden(nx, ny)) {
                        continue;
                    }
                    int skirt = vertex;
                    for (int i = 0; i < 17; i++) {
                        int row = edge[2] + i * edge[4], column = edge[3] + i * edge[5];
                        put(
                                positions,
                                vertex++,
                                originX - row * step,
                                originY - column * step,
                                tile.corner(row, column) - SINK - SKIRT);
                    }
                    for (int i = 0; i < 16; i++) {
                        int row = edge[2] + i * edge[4], column = edge[3] + i * edge[5];
                        int top = base + row * 17 + column;
                        int next = base + (row + edge[4]) * 17 + column + edge[5];
                        if (edge[6] == 0) {
                            index = triangle(indices, index, top, next, skirt + i);
                            index = triangle(indices, index, next, skirt + i + 1, skirt + i);
                        } else {
                            index = triangle(indices, index, top, skirt + i, next);
                            index = triangle(indices, index, next, skirt + i, skirt + i + 1);
                        }
                    }
                }
            }
        }
        check(canceled);
        return new HorizonMesh(
                Arrays.copyOf(positions, vertex * 3),
                Arrays.copyOf(indices, index),
                count,
                cameraX,
                cameraY,
                radius);
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

    /** Horizon tiles drawn (in the window, present in the WDL and not hidden). */
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
