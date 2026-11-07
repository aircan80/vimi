package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainScene;
import java.nio.ByteBuffer;

/**
 * Instanced World object drawing (issue #382): per-instance transforms and the per-frame grouping
 * of visible placements by model. Pure; no bgfx calls.
 *
 * <p>One instance is a 3 x 4 matrix as three vec4 rows ({@link #STRIDE} bytes): the placement's
 * uniform scale times its row-major rotation, with the translation relative to the frame origin
 * (the camera) in w. These are exactly the first three rows of {@link
 * BgfxWorldStreamRenderer#objectMatrices}' model matrix: the translation is computed in double and
 * cast to float once, so precision is unchanged. The {@code world-wmo} vertex shader reads the rows
 * as {@code i_data0..2}.
 */
final class WorldObjectInstances {
    /** Bytes per instance: three vec4 rows of floats. */
    static final int STRIDE = 48;

    private WorldObjectInstances() {}

    /**
     * Writes placement p's instance rows at {@code out}'s position, in {@code out}'s byte order
     * (native for bgfx instance data). Allocates nothing.
     */
    static void put(double[] placements, int p, TerrainScene.Origin origin, ByteBuffer out) {
        int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES;
        double s = placements[at + 3];
        row(out, s, placements, at + 4, placements[at] - origin.x());
        row(out, s, placements, at + 7, placements[at + 1] - origin.y());
        row(out, s, placements, at + 10, placements[at + 2] - origin.z());
    }

    private static void row(ByteBuffer out, double s, double[] placements, int r, double offset) {
        out.putFloat((float) (s * placements[r]))
                .putFloat((float) (s * placements[r + 1]))
                .putFloat((float) (s * placements[r + 2]))
                .putFloat((float) offset);
    }

    /**
     * Writes the instance rows of a column-major 4 x 4 affine matrix (the cold WMO pass's model
     * matrix): its first three rows.
     */
    static void putColumnMajor(java.nio.FloatBuffer matrix, ByteBuffer out) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                out.putFloat(matrix.get(column * 4 + row));
            }
        }
    }

    /**
     * Groups the visible placements by model with a counting sort. {@code order} receives the
     * visible placement indices model by model, each model's placements in ascending order; {@code
     * starts} (at least models + 1 long) receives each model's first position in {@code order}, and
     * {@code starts[models]} the number of visible placements, which is returned. Allocates
     * nothing, so the scratch arrays can be reused every frame.
     */
    static int groupByModel(
            int[] placementModels, boolean[] visible, int models, int[] starts, int[] order) {
        java.util.Arrays.fill(starts, 0, models + 1, 0);
        for (int p = 0; p < placementModels.length; p++) {
            if (visible[p]) {
                starts[placementModels[p] + 1]++;
            }
        }
        for (int m = 0; m < models; m++) {
            starts[m + 1] += starts[m];
        }
        int total = starts[models];
        // Fill using starts as cursors, then shift them back.
        for (int p = 0; p < placementModels.length; p++) {
            if (visible[p]) {
                order[starts[placementModels[p]]++] = p;
            }
        }
        for (int m = models; m > 0; m--) {
            starts[m] = starts[m - 1];
        }
        starts[0] = 0;
        return total;
    }
}
