package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureMips;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded upload plan for one World object texture's packed mip chain (issue #265), the object
 * counterpart of {@link WorldStreamAtlasMips#STEPS}.
 *
 * <p>A 256 x 256 chain is 349,524 bytes, more than one upload step may issue, so the texture is
 * created mipmapped without data and filled level by level in row bands. Small levels share a step;
 * a level larger than the budget is split into row bands, so every step holds at most the budget by
 * construction.
 */
final class WorldStreamObjectMips {
    /**
     * One bgfx_update_texture_2d call: rows [row, row + rows) of one level; offset is relative to
     * the start of the image's packed chain.
     */
    record Slice(int level, int row, int rows, int offset, int bytes) {}

    private WorldStreamObjectMips() {}

    /** Upload steps in order; each holds at most {@code budget} bytes and every texel once. */
    static List<List<Slice>> plan(int width, int height, int budget) {
        int levels = ObjectTextureMips.levels(width, height);
        var steps = new ArrayList<List<Slice>>();
        var step = new ArrayList<Slice>();
        int room = budget;
        for (int level = 0; level < levels; level++) {
            int pitch = ObjectTextureMips.levelWidth(width, level) * 4;
            int levelRows = ObjectTextureMips.levelWidth(height, level);
            if (pitch > budget) {
                throw new IllegalArgumentException("Object texture row exceeds the step budget");
            }
            int base = ObjectTextureMips.levelOffset(width, height, level);
            for (int row = 0; row < levelRows; ) {
                if (room < pitch) {
                    steps.add(List.copyOf(step));
                    step.clear();
                    room = budget;
                }
                int rows = Math.min(levelRows - row, room / pitch);
                int bytes = rows * pitch;
                step.add(new Slice(level, row, rows, base + row * pitch, bytes));
                room -= bytes;
                row += rows;
            }
        }
        steps.add(List.copyOf(step));
        return List.copyOf(steps);
    }
}
