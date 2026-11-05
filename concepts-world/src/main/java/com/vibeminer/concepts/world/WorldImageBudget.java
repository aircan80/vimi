package com.vibeminer.concepts.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Mip-level selection for object textures under a per-tile pixel budget (#267), charged in whole
 * mip chains (#265). Pure and reusable: a level is always one the BLP file records, so the authored
 * mips are uploaded rather than resampled.
 *
 * <p>{@link #allocate} shares the budget fairly in rounds: every image first gets the smallest
 * allowed side, then each round raises images to the next side in priority order while the budget
 * allows. An image is dropped only when even the smallest sides of all higher-priority images leave
 * no room, so a tight budget lowers resolution instead of leaving surfaces without texture.
 */
public final class WorldImageBudget {
    /** BLP2 header: mip offsets at 20 and sizes at 84, sixteen 32-bit entries each. */
    private static final int MIP_OFFSETS = 20, MIP_SIZES = 84, MAX_LEVELS = 16;

    private WorldImageBudget() {}

    /** One texture to place: its level-0 size, recorded mip count and priority order. */
    public record Request(long width, long height, int levels) {
        public Request {
            if (width < 1 || height < 1 || levels < 1 || levels > MAX_LEVELS) {
                throw new IllegalArgumentException("Invalid image request");
            }
        }
    }

    /**
     * The smallest recorded level whose sides are both at most {@code side}; the last recorded
     * level when the file stops before that.
     */
    public static int levelFor(long width, long height, int side, int levels) {
        if (width < 1 || height < 1 || side < 1 || levels < 1) {
            throw new IllegalArgumentException("Invalid level request");
        }
        int level = 0;
        while (level < levels - 1
                && (Math.max(1, width >> level) > side || Math.max(1, height >> level) > side)) {
            level++;
        }
        return level;
    }

    /** Pixels of one level. */
    public static long pixels(long width, long height, int level) {
        return Math.max(1, width >> level) * Math.max(1, height >> level);
    }

    /**
     * What {@link #allocate} charges for one level: its whole mip chain down to 1 x 1, which is
     * what the World uploads (#265), at most about 4/3 of the level's own pixels.
     */
    public static long cost(long width, long height, int level) {
        return com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                (int) Math.max(1, width >> level), (int) Math.max(1, height >> level));
    }

    /**
     * Levels for {@code requests} (highest priority first) within {@code budget} pixels, using the
     * allowed {@code sides} (largest first). Returns -1 for a request that cannot fit at all.
     */
    public static int[] allocate(List<Request> requests, long budget, int[] sides) {
        Objects.requireNonNull(requests);
        if (budget < 0 || sides.length == 0) {
            throw new IllegalArgumentException("Invalid image budget");
        }
        for (int i = 1; i < sides.length; i++) {
            if (sides[i] < 1 || sides[i] >= sides[i - 1]) {
                throw new IllegalArgumentException("Sides must strictly decrease");
            }
        }
        int n = requests.size();
        int[] level = new int[n];
        long total = 0;
        int smallest = sides[sides.length - 1];
        for (int i = 0; i < n; i++) {
            var r = requests.get(i);
            level[i] = levelFor(r.width(), r.height(), smallest, r.levels());
            long cost = cost(r.width(), r.height(), level[i]);
            if (total + cost > budget) {
                level[i] = -1; // lower priority than everything that fit
            } else {
                total += cost;
            }
        }
        for (int round = sides.length - 2; round >= 0; round--) {
            for (int i = 0; i < n; i++) {
                if (level[i] < 0) {
                    continue;
                }
                var r = requests.get(i);
                int next = levelFor(r.width(), r.height(), sides[round], r.levels());
                if (next >= level[i]) {
                    continue;
                }
                long delta =
                        cost(r.width(), r.height(), next) - cost(r.width(), r.height(), level[i]);
                if (total + delta <= budget) {
                    level[i] = next;
                    total += delta;
                }
            }
        }
        return level;
    }

    /**
     * Mip levels a BLP2 file records: the leading run of levels with a non-zero size whose bytes
     * lie inside the file, at least one. Zero when the header is too short to say.
     */
    public static int recordedLevels(byte[] blp) {
        Objects.requireNonNull(blp);
        if (blp.length < MIP_SIZES + 4 * MAX_LEVELS) {
            return 0;
        }
        var header = ByteBuffer.wrap(blp).order(ByteOrder.LITTLE_ENDIAN);
        int levels = 0;
        for (int i = 0; i < MAX_LEVELS; i++) {
            long offset = Integer.toUnsignedLong(header.getInt(MIP_OFFSETS + 4 * i));
            long size = Integer.toUnsignedLong(header.getInt(MIP_SIZES + 4 * i));
            if (size == 0 || offset == 0 || offset + size > blp.length) {
                break;
            }
            levels++;
        }
        return levels;
    }
}
