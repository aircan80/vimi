package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Building water of one object set as the child draws it: each placement of a model whose mesh
 * holds a liquid ({@link WorldStreamObjectsFile.Liquids}) gets that liquid's live tiles as quads in
 * native coordinates, transformed by the placement (translation, scale, row-major rotation), in
 * terrain water's vertex layout ({@link WorldStreamPackedTile#VERTEX_BYTES}: position, the flat
 * colour, depth and kind in the MCCV slot, and a zero normal so the shader leaves it unlit). So the
 * terrain water shader draws it with the recorded water light. Each run is one placement's liquid:
 * its first index, index count, placement and mesh, so the renderer skips a run whose placement is
 * culled or whose interior group a portal walk did not reach.
 *
 * <p>Built on the decoder worker; at most {@link #MAX_TILES} tiles per set, liquids past it are
 * left out whole and counted. Indices are 32-bit. The memory is native and freed with {@link
 * #free}.
 */
final class WorldObjectWater {
    /** Tiles of building water per object set: four times the Stormwind canals' live tiles. */
    static final int MAX_TILES = 1 << 16;

    /**
     * The depth (0 shallow to 1 deep) building water is shaded at: MLIQ records no depth, so it
     * takes the water light's deep colour and opacity. Flagged: an explorer choice.
     */
    static final float DEPTH = 1;

    /** Per run: first index, index count, placement, mesh. */
    static final int RUN_INTS = 4;

    static final WorldObjectWater NONE = new WorldObjectWater(null, null, new int[0], 0, 0);

    ByteBuffer vertices, indices;
    final int[] runs;
    final int tiles;

    /** Placement liquids left out past {@link #MAX_TILES}. */
    final int dropped;

    private WorldObjectWater(
            ByteBuffer vertices, ByteBuffer indices, int[] runs, int tiles, int dropped) {
        this.vertices = vertices;
        this.indices = indices;
        this.runs = runs;
        this.tiles = tiles;
        this.dropped = dropped;
    }

    int runCount() {
        return runs.length / RUN_INTS;
    }

    long bytes() {
        return (vertices == null ? 0 : vertices.capacity())
                + (long) (indices == null ? 0 : indices.capacity());
    }

    /** Builds the set's water; {@link #NONE} without liquids. */
    static WorldObjectWater build(WorldStreamObjectsFile.Data data, BooleanSupplier canceled) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(canceled);
        var liquids = data.liquids;
        if (liquids.count() == 0) {
            return NONE;
        }
        // Per mesh: its liquid entry, or -1.
        var byMesh = new int[data.meshCount()];
        java.util.Arrays.fill(byMesh, -1);
        for (int i = 0; i < liquids.count(); i++) {
            byMesh[liquids.meshes[i]] = i;
        }
        int[] live = new int[liquids.count()];
        for (int i = 0; i < live.length; i++) {
            live[i] = liquids.liveTiles(i);
        }
        int placements = data.placementCount();
        // First pass: which placement liquids fit, in placement order.
        var chosen = new java.util.ArrayList<int[]>();
        long tiles = 0;
        int dropped = 0;
        for (int p = 0; p < placements; p++) {
            int m = data.placementModels[p];
            for (int mesh = data.models[m * 2];
                    mesh < data.models[m * 2] + data.models[m * 2 + 1];
                    mesh++) {
                int entry = byMesh[mesh];
                if (entry < 0 || live[entry] == 0) {
                    continue;
                }
                if (tiles + live[entry] > MAX_TILES) {
                    dropped++;
                    continue;
                }
                tiles += live[entry];
                chosen.add(new int[] {p, mesh, entry});
            }
        }
        if (chosen.isEmpty()) {
            return new WorldObjectWater(null, null, new int[0], 0, dropped);
        }
        int total = (int) tiles;
        ByteBuffer vertices = null, indices = null;
        try {
            vertices =
                    MemoryUtil.memAlloc(total * 4 * WorldStreamPackedTile.VERTEX_BYTES)
                            .order(ByteOrder.nativeOrder());
            indices = MemoryUtil.memAlloc(total * 6 * 4).order(ByteOrder.nativeOrder());
            var runs = new int[chosen.size() * RUN_INTS];
            int vertex = 0, index = 0, r = 0;
            int stride = WorldStreamObjectsFile.PLACEMENT_DOUBLES;
            for (var pick : chosen) {
                check(canceled);
                int p = pick[0], mesh = pick[1], e = pick[2];
                double[] t = data.placements;
                int at = p * stride;
                double px = t[at], py = t[at + 1], pz = t[at + 2], scale = t[at + 3];
                double r0 = t[at + 4], r1 = t[at + 5], r2 = t[at + 6];
                double r3 = t[at + 7], r4 = t[at + 8], r5 = t[at + 9];
                double r6 = t[at + 10], r7 = t[at + 11], r8 = t[at + 12];
                int xv = liquids.grids[e * 2], yv = liquids.grids[e * 2 + 1];
                float cx = liquids.corners[e * 3], cy = liquids.corners[e * 3 + 1];
                float[] heights = liquids.heights[e];
                byte[] tileLive = liquids.live[e];
                int kind = liquids.kinds[e], colour = liquids.colours[e];
                int first = index;
                for (int ty = 0; ty < yv - 1; ty++) {
                    for (int tx = 0; tx < xv - 1; tx++) {
                        if (tileLive[ty * (xv - 1) + tx] == 0) {
                            continue;
                        }
                        // Corners (tx, ty), (tx + 1, ty), (tx, ty + 1), (tx + 1, ty + 1).
                        for (int c = 0; c < 4; c++) {
                            int gx = tx + (c & 1), gy = ty + (c >> 1);
                            double a = cx + gx * com.vibeminer.formats.world.WmoLiquid.UNIT;
                            double b = cy + gy * com.vibeminer.formats.world.WmoLiquid.UNIT;
                            double h = heights[gy * xv + gx];
                            put(
                                    vertices,
                                    vertex + c,
                                    px + scale * (r0 * a + r1 * b + r2 * h),
                                    py + scale * (r3 * a + r4 * b + r5 * h),
                                    pz + scale * (r6 * a + r7 * b + r8 * h),
                                    colour,
                                    kind);
                        }
                        // Split on the (tx, ty)--(tx + 1, ty + 1) diagonal, as swimming reads it.
                        int[] order = {0, 1, 3, 0, 3, 2};
                        for (int k = 0; k < 6; k++) {
                            indices.putInt(index * 4, vertex + order[k]);
                            index++;
                        }
                        vertex += 4;
                    }
                }
                runs[r * RUN_INTS] = first;
                runs[r * RUN_INTS + 1] = index - first;
                runs[r * RUN_INTS + 2] = p;
                runs[r * RUN_INTS + 3] = mesh;
                r++;
            }
            var built = new WorldObjectWater(vertices, indices, runs, total, dropped);
            vertices = null;
            indices = null;
            return built;
        } finally {
            if (vertices != null) {
                MemoryUtil.memFree(vertices);
            }
            if (indices != null) {
                MemoryUtil.memFree(indices);
            }
        }
    }

    private static void put(
            ByteBuffer out, int vertex, double x, double y, double z, int colour, int kind) {
        int at = vertex * WorldStreamPackedTile.VERTEX_BYTES;
        out.putFloat(at, (float) x);
        out.putFloat(at + 4, (float) y);
        out.putFloat(at + 8, (float) z);
        // COLOR0: a flat liquid's colour (water and ocean take the water light).
        out.put(at + 12, (byte) (colour >>> 16));
        out.put(at + 13, (byte) (colour >>> 8));
        out.put(at + 14, (byte) colour);
        out.put(at + 15, (byte) 255);
        // COLOR1 (the MCCV slot): depth, then the liquid kind, as terrain water carries them.
        out.put(at + 16, (byte) Math.round(DEPTH * 255));
        out.put(at + 17, (byte) kind);
        out.put(at + 18, (byte) 0);
        out.put(at + 19, (byte) 0);
        // NORMAL: (128, 128, 128) decodes to about zero: unlit, as terrain water.
        out.put(at + 20, (byte) 128);
        out.put(at + 21, (byte) 128);
        out.put(at + 22, (byte) 128);
        out.put(at + 23, (byte) 0);
    }

    /** Frees the native memory; the runs stay readable. */
    void free() {
        if (vertices != null) {
            MemoryUtil.memFree(vertices);
            vertices = null;
        }
        if (indices != null) {
            MemoryUtil.memFree(indices);
            indices = null;
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
