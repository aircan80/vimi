package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.ObjectTextureMips;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded upload plan for one World object texture's packed mip chain (issue #265), the object
 * counterpart of {@link WorldStreamAtlasMips#STEPS}.
 *
 * <p>A 256 x 256 RGBA8 chain is 349,524 bytes, more than one upload step may issue, so the texture
 * is created mipmapped without data and filled level by level in row bands. Small levels share a
 * step; a level larger than the budget is split into row bands, so every step holds at most the
 * budget by construction. Compressed chains (issue #374) are banded in block rows: a slice's rows
 * are a multiple of four texels except where a level ends.
 */
final class WorldStreamObjectMips {
    /**
     * One bgfx_update_texture_2d call: texel rows [row, row + rows) of one level, {@code pitch}
     * bytes per row of units (texels or blocks); offset is relative to the start of the image's
     * packed chain.
     */
    record Slice(int level, int row, int rows, int offset, int bytes, int pitch) {}

    private WorldStreamObjectMips() {}

    /** An RGBA8 chain's plan. */
    static List<List<Slice>> plan(int width, int height, int budget) {
        return plan(ObjectTextureFormats.RGBA8, width, height, budget);
    }

    /** Upload steps in order; each holds at most {@code budget} bytes and every unit once. */
    static List<List<Slice>> plan(int format, int width, int height, int budget) {
        int levels = ObjectTextureMips.levels(width, height);
        int unitRows = ObjectTextureFormats.unitRows(format);
        var steps = new ArrayList<List<Slice>>();
        var step = new ArrayList<Slice>();
        int room = budget;
        for (int level = 0; level < levels; level++) {
            int texelRows = ObjectTextureMips.levelWidth(height, level);
            int pitch =
                    ObjectTextureFormats.pitch(format, ObjectTextureMips.levelWidth(width, level));
            int rowsOfUnits = ObjectTextureFormats.unitRowCount(format, texelRows);
            if (pitch > budget) {
                throw new IllegalArgumentException("Object texture row exceeds the step budget");
            }
            int base = ObjectTextureFormats.levelOffset(format, width, height, level);
            for (int unit = 0; unit < rowsOfUnits; ) {
                if (room < pitch) {
                    steps.add(List.copyOf(step));
                    step.clear();
                    room = budget;
                }
                int units = Math.min(rowsOfUnits - unit, room / pitch);
                int bytes = units * pitch;
                int row = unit * unitRows;
                int rows = Math.min(texelRows, (unit + units) * unitRows) - row;
                step.add(new Slice(level, row, rows, base + unit * pitch, bytes, pitch));
                room -= bytes;
                unit += units;
            }
        }
        steps.add(List.copyOf(step));
        return List.copyOf(steps);
    }
}
