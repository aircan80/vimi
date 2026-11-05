package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Worker-side full mip chain for one 512 x 512 RGBA8 World terrain atlas, and its bounded upload
 * plan (issue #174).
 *
 * <p>The atlas is 16 x 16 chunk sub-images of 32 x 32 texels laid out in world order, so adjacent
 * sub-images are adjacent ground and filtering across their borders is correct. The one thing that
 * must not bleed is an unsupported chunk: alpha 0 and black RGB, which the shader replaces with the
 * vertex color. Levels are therefore built by a coverage-weighted box filter (an uncovered texel
 * has weight 0, so it never darkens covered ground), with alpha the covered fraction. Uncovered
 * texels then take the color of their nearest covered ancestor ("pull-push" fill) while keeping
 * alpha 0, so bilinear taps across a covered/uncovered border blend into nearby ground color, not
 * black, and the shader's alpha 0.5 cut stays on the chunk border. Levels 1 to 5 never mix two
 * chunks, because their aligned 2^k blocks stay inside one 32-texel chunk.
 */
final class WorldStreamAtlasMips {
    static final int SIDE = 512, LEVELS = 10;

    /** All ten levels, 512 x 512 down to 1 x 1, tightly packed: 1,398,100 bytes. */
    static final int CHAIN_BYTES = offset(LEVELS);

    /** At most this many atlas bytes per upload step: the former 32-row level-0 slice. */
    static final int STEP_BYTES = 32 * SIDE * 4;

    /**
     * Levels below this are uploaded by zero-copy release references; the 21,844-byte tail is
     * copied so the whole atlas takes 21 of the upload's 64 release tickets.
     */
    static final int REFERENCED_LEVELS = 3;

    /** One bgfx_update_texture_2d call: rows [row, row + rows) of one level. */
    record Slice(int level, int row, int rows, int offset, int bytes, boolean referenced) {}

    /** Upload steps in order; each holds at most STEP_BYTES and every texel exactly once. */
    static final List<List<Slice>> STEPS = plan();

    private WorldStreamAtlasMips() {}

    static int side(int level) {
        return SIDE >> level;
    }

    static int offset(int level) {
        int bytes = 0;
        for (int i = 0; i < level; i++) {
            bytes += side(i) * side(i) * 4;
        }
        return bytes;
    }

    /** Fills levels 1 to 9 of the chain and the RGB of uncovered texels; level 0 is the atlas. */
    static void build(ByteBuffer chain, BooleanSupplier canceled) {
        if (chain.capacity() != CHAIN_BYTES) {
            throw new IllegalArgumentException("Atlas mip chain size");
        }
        for (int level = 1; level < LEVELS; level++) {
            check(canceled);
            pull(chain, level);
        }
        for (int level = LEVELS - 2; level >= 0; level--) {
            check(canceled);
            push(chain, level);
        }
    }

    private static void pull(ByteBuffer chain, int level) {
        int side = side(level), childSide = side * 2;
        int from = offset(level - 1), to = offset(level);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int weight = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int at = from + ((2 * y + dy) * childSide + 2 * x + dx) * 4;
                        int alpha = chain.get(at + 3) & 255;
                        weight += alpha;
                        r += (chain.get(at) & 255) * alpha;
                        g += (chain.get(at + 1) & 255) * alpha;
                        b += (chain.get(at + 2) & 255) * alpha;
                    }
                }
                int out = to + (y * side + x) * 4;
                if (weight == 0) {
                    chain.putInt(out, 0);
                } else {
                    chain.put(out, (byte) ((r + weight / 2) / weight));
                    chain.put(out + 1, (byte) ((g + weight / 2) / weight));
                    chain.put(out + 2, (byte) ((b + weight / 2) / weight));
                    chain.put(out + 3, (byte) ((weight + 2) / 4));
                }
            }
        }
    }

    private static void push(ByteBuffer chain, int level) {
        int side = side(level), parentSide = side / 2;
        int at = offset(level), parent = offset(level + 1);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int out = at + (y * side + x) * 4;
                if (chain.get(out + 3) == 0) {
                    int source = parent + ((y / 2) * parentSide + x / 2) * 4;
                    chain.put(out, chain.get(source));
                    chain.put(out + 1, chain.get(source + 1));
                    chain.put(out + 2, chain.get(source + 2));
                }
            }
        }
    }

    private static List<List<Slice>> plan() {
        var steps = new ArrayList<List<Slice>>();
        var step = new ArrayList<Slice>();
        int budget = STEP_BYTES;
        for (int level = 0; level < LEVELS; level++) {
            int pitch = side(level) * 4;
            for (int row = 0; row < side(level); ) {
                if (budget < pitch) {
                    steps.add(List.copyOf(step));
                    step.clear();
                    budget = STEP_BYTES;
                }
                int rows = Math.min(side(level) - row, budget / pitch);
                int bytes = rows * pitch;
                step.add(
                        new Slice(
                                level,
                                row,
                                rows,
                                offset(level) + row * pitch,
                                bytes,
                                level < REFERENCED_LEVELS));
                budget -= bytes;
                row += rows;
            }
        }
        steps.add(List.copyOf(step));
        return List.copyOf(steps);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
