package com.vibeminer.concepts.world;

import java.util.List;
import java.util.Objects;

/**
 * Mip-level selection for object textures under a per-tile pixel budget (#267). Pure and reusable:
 * a level is always one the BLP file records, so the authored mips are uploaded rather than
 * resampled.
 *
 * <p>{@link #allocate} shares the budget fairly in rounds: every image first gets the smallest
 * allowed side, then each round raises images to the next side in priority order while the budget
 * allows. An image is dropped only when even the smallest sides of all higher-priority images leave
 * no room, so a tight budget lowers resolution instead of leaving surfaces without texture.
 */
public final class WorldImageBudget {
    private static final int MAX_LEVELS = 16;

    private WorldImageBudget() {}

    /**
     * One texture to place: its level-0 size and how many levels it can take. Priority is the order
     * of the request list, highest first.
     */
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
            long cost = pixels(r.width(), r.height(), level[i]);
            if (total + cost > budget) {
                // Its smallest level does not fit beside the higher-priority images already
                // placed; a later, smaller image may still fit.
                level[i] = -1;
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
                        pixels(r.width(), r.height(), next)
                                - pixels(r.width(), r.height(), level[i]);
                if (total + delta <= budget) {
                    level[i] = next;
                    total += delta;
                }
            }
        }
        return level;
    }

    /**
     * Decodable levels from the decoder's own declared mips ({@link
     * com.vibeminer.formats.images.Blp2Decoder#readMipsSynchronous}): the leading run before the
     * first compact block level, which the decoder never decodes.
     */
    public static int recordedLevels(List<com.vibeminer.formats.images.Blp2Types.Mip> mips) {
        Objects.requireNonNull(mips);
        int levels = 0;
        for (var mip : mips) {
            if (mip.level() != levels
                    || mip.storage() == com.vibeminer.formats.images.Blp2Types.MipStorage.COMPACT) {
                break;
            }
            levels++;
        }
        return levels;
    }
}
