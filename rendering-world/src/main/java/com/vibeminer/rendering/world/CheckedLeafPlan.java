package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.TerrainGrid;
import java.util.Objects;

/** Source-index leaf topology only; no normalization, welding or neighbor policy is inferred. */
public final class CheckedLeafPlan {
    public static final int TOP = 1, RIGHT = 2, BOTTOM = 4, LEFT = 8;
    private final TerrainGrid.Chunk source;
    private final int row, column, size, splitMask;
    private final int[] perimeter;
    private final int center;

    private CheckedLeafPlan(
            TerrainGrid.Chunk source,
            int row,
            int column,
            int size,
            int splitMask,
            int[] perimeter,
            int center) {
        this.source = source;
        this.row = row;
        this.column = column;
        this.size = size;
        this.splitMask = splitMask;
        this.perimeter = perimeter;
        this.center = center;
    }

    public static CheckedLeafPlan create(
            TerrainGrid.Chunk source, int row, int column, int size, int splitMask) {
        Objects.requireNonNull(source);
        if ((size != 1 && size != 2 && size != 4 && size != 8)
                || row < 0
                || column < 0
                || row > 7
                || column > 7
                || row + size > 8
                || column + size > 8
                || row % size != 0
                || column % size != 0
                || splitMask < 0
                || splitMask > 15
                || size == 1 && splitMask != 0) {
            throw new IllegalArgumentException("Invalid dyadic leaf/split mask");
        }
        int[] perimeter = new int[4 + Integer.bitCount(splitMask)];
        int n = 0, half = size / 2;
        perimeter[n++] = row * 17 + column;
        if ((splitMask & TOP) != 0) {
            perimeter[n++] = row * 17 + column + half;
        }
        perimeter[n++] = row * 17 + column + size;
        if ((splitMask & RIGHT) != 0) {
            perimeter[n++] = (row + half) * 17 + column + size;
        }
        perimeter[n++] = (row + size) * 17 + column + size;
        if ((splitMask & BOTTOM) != 0) {
            perimeter[n++] = (row + size) * 17 + column + half;
        }
        perimeter[n++] = (row + size) * 17 + column;
        if ((splitMask & LEFT) != 0) {
            perimeter[n] = (row + half) * 17 + column;
        }
        int center = size == 1 ? row * 17 + column + 9 : (row + half) * 17 + column + half;
        return new CheckedLeafPlan(source, row, column, size, splitMask, perimeter, center);
    }

    public TerrainGrid.Chunk source() {
        return source;
    }

    public int row() {
        return row;
    }

    public int column() {
        return column;
    }

    public int size() {
        return size;
    }

    public int splitMask() {
        return splitMask;
    }

    public int triangleCount() {
        return perimeter.length;
    }

    public int sampleCount() {
        return (size + 1) * (size + 1) + size * size;
    }

    int perimeter(int ordinal) {
        return perimeter[ordinal];
    }

    int center() {
        return center;
    }
}
