package com.vibeminer.rendering.world;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;

/** Design-only source-grid experiment. Not connected to a source reader, renderer, codec or GUI. */
final class TerrainLodSpike {
    static final int TILE_CELLS = 128, CHUNK_CELLS = 8, MAX_SIDE = 7 * TILE_CELLS;
    static final int MAX_VERTICES = TerrainScene.MAX_TILES * TerrainScene.MAX_VERTICES_PER_TILE;
    static final double UNIT = (1600.0 / 3) / TILE_CELLS;

    record Count(int vertices, int triangles, int leaves, int balancePasses) {}

    record Mesh(float[] xyz, int[] indices, Count count) {}

    static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    private final int rows, columns;
    private final byte[] levels;
    private final BitSet holes;
    private final BooleanSupplier canceled;
    private int passes;

    /**
     * Desired spacing and holes are independently authored source metadata, not inferred triangles.
     */
    TerrainLodSpike(
            int rows,
            int columns,
            IntBinaryOperator desired,
            java.util.function.BiPredicate<Integer, Integer> hole,
            BooleanSupplier canceled) {
        if (rows < 8
                || columns < 8
                || rows > MAX_SIDE
                || columns > MAX_SIDE
                || rows % 8 != 0
                || columns % 8 != 0) {
            throw new Refused("Source-grid bound");
        }
        this.rows = rows;
        this.columns = columns;
        this.canceled = canceled;
        levels = new byte[rows * columns];
        holes = new BitSet(levels.length);
        for (int r = 0; r < rows; r++) {
            check();
            for (int c = 0; c < columns; c++) {
                if (hole.test(r, c)) {
                    holes.set(r * columns + c);
                }
            }
        }
        for (int r = 0; r < rows; r += 8) {
            check();
            for (int c = 0; c < columns; c += 8) {
                int size = desired.applyAsInt(r, c);
                if (size != 1 && size != 2 && size != 4 && size != 8) {
                    throw new Refused("Unsupported spacing");
                }
                assign(r, c, 8, size);
            }
        }
        balance();
    }

    private void assign(int r, int c, int size, int desired) {
        if (size > 1 && (size > desired || hasHole(r, c, size))) {
            int half = size / 2;
            assign(r, c, half, desired);
            assign(r, c + half, half, desired);
            assign(r + half, c, half, desired);
            assign(r + half, c + half, half, desired);
        } else {
            fill(r, c, size, size);
        }
    }

    private boolean hasHole(int r, int c, int size) {
        for (int y = r; y < r + size; y++) {
            int first = holes.nextSetBit(y * columns + c);
            if (first >= 0 && first < y * columns + c + size) {
                return true;
            }
        }
        return false;
    }

    private void fill(int r, int c, int size, int spacing) {
        for (int y = r; y < r + size; y++) {
            Arrays.fill(levels, y * columns + c, y * columns + c + size, (byte) spacing);
        }
    }

    private int level(int r, int c) {
        return r < 0 || c < 0 || r >= rows || c >= columns ? 0 : levels[r * columns + c];
    }

    private void balance() {
        boolean changed;
        do {
            check();
            changed = false;
            if (++passes > 16) {
                throw new Refused("Balancing work bound");
            }
            for (int r = 0; r < rows; r++) {
                check();
                for (int c = 0; c < columns; c++) {
                    int s = level(r, c);
                    int fine = s;
                    for (int neighbor :
                            new int[] {
                                level(r - 1, c), level(r + 1, c), level(r, c - 1), level(r, c + 1)
                            }) {
                        if (neighbor > 0) {
                            fine = Math.min(fine, neighbor);
                        }
                    }
                    if (s > 2 * fine) {
                        fill(r / s * s, c / s * s, s, s / 2);
                        changed = true;
                    }
                }
            }
        } while (changed);
    }

    int maximumNeighborRatio() {
        int ratio = 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int a = level(r, c);
                for (int b : new int[] {level(r + 1, c), level(r, c + 1)}) {
                    if (b > 0) {
                        ratio = Math.max(ratio, Math.max(a, b) / Math.min(a, b));
                    }
                }
            }
        }
        return ratio;
    }

    private boolean finer(int r, int c, int size, int side) {
        for (int i = 0; i < size; i++) {
            int neighbor =
                    switch (side) {
                        case 0 -> level(r - 1, c + i);
                        case 1 -> level(r + i, c + size);
                        case 2 -> level(r + size, c + i);
                        default -> level(r + i, c - 1);
                    };
            if (neighbor > 0 && neighbor < size) {
                return true;
            }
        }
        return false;
    }

    /** Perimeter in source-cell order; every midpoint is an existing outer MCVT sample. */
    private int perimeter(int r, int c, int s, int[] ring) {
        int n = 0;
        n = point(ring, n, 2 * r, 2 * c);
        if (finer(r, c, s, 0)) {
            n = point(ring, n, 2 * r, 2 * c + s);
        }
        n = point(ring, n, 2 * r, 2 * (c + s));
        if (finer(r, c, s, 1)) {
            n = point(ring, n, 2 * r + s, 2 * (c + s));
        }
        n = point(ring, n, 2 * (r + s), 2 * (c + s));
        if (finer(r, c, s, 2)) {
            n = point(ring, n, 2 * (r + s), 2 * c + s);
        }
        n = point(ring, n, 2 * (r + s), 2 * c);
        if (finer(r, c, s, 3)) {
            n = point(ring, n, 2 * r + s, 2 * c);
        }
        return n;
    }

    private static int point(int[] ring, int n, int r2, int c2) {
        ring[2 * n] = r2;
        ring[2 * n + 1] = c2;
        return n + 1;
    }

    private static int sample(int r2, int c2, int chunkR, int chunkC) {
        int r = r2 - 2 * chunkR, c = c2 - 2 * chunkC;
        if ((r & 1) != (c & 1) || r < 0 || c < 0 || r > 16 || c > 16) {
            throw new AssertionError("Invented source sample");
        }
        return r / 2 * 17 + c / 2 + ((r & 1) != 0 ? 9 : 0);
    }

    private boolean leaf(int r, int c, int chunkR, int chunkC) {
        int s = level(r, c);
        return (r - chunkR) % s == 0 && (c - chunkC) % s == 0 && !holes.get(r * columns + c);
    }

    Count count() {
        int vertices = 0, triangles = 0, leaves = 0;
        int[] ring = new int[16];
        var used = new BitSet(145);
        for (int chunkR = 0; chunkR < rows; chunkR += 8) {
            check();
            for (int chunkC = 0; chunkC < columns; chunkC += 8) {
                used.clear();
                for (int r = chunkR; r < chunkR + 8; r++) {
                    for (int c = chunkC; c < chunkC + 8; c++) {
                        if (!leaf(r, c, chunkR, chunkC)) {
                            continue;
                        }
                        int s = level(r, c), n = perimeter(r, c, s, ring);
                        leaves++;
                        triangles += n;
                        used.set(sample(2 * r + s, 2 * c + s, chunkR, chunkC));
                        for (int i = 0; i < n; i++) {
                            used.set(sample(ring[2 * i], ring[2 * i + 1], chunkR, chunkC));
                        }
                    }
                }
                vertices += used.cardinality();
            }
        }
        return new Count(vertices, triangles, leaves, passes);
    }

    Mesh mesh(double originX, double originY, DoubleBinaryOperator height) {
        var count = count();
        if (count.vertices > MAX_VERTICES || count.triangles > TerrainScene.MAX_TRIANGLES) {
            throw new Refused(
                    "Existing aggregate geometry budget; keep previous scene or omit far coverage");
        }
        float[] xyz = new float[count.vertices * 3];
        int[] indices = new int[count.triangles * 3];
        int[] mapping = new int[145], ring = new int[16], perimeter = new int[8];
        int[] at = {0, 0};
        for (int chunkR = 0; chunkR < rows; chunkR += 8) {
            check();
            for (int chunkC = 0; chunkC < columns; chunkC += 8) {
                Arrays.fill(mapping, -1);
                for (int r = chunkR; r < chunkR + 8; r++) {
                    for (int c = chunkC; c < chunkC + 8; c++) {
                        if (!leaf(r, c, chunkR, chunkC)) {
                            continue;
                        }
                        int s = level(r, c), n = perimeter(r, c, s, ring);
                        int center =
                                vertex(
                                        2 * r + s, 2 * c + s, chunkR, chunkC, mapping, xyz, at,
                                        originX, originY, height);
                        for (int i = 0; i < n; i++) {
                            perimeter[i] =
                                    vertex(
                                            ring[2 * i],
                                            ring[2 * i + 1],
                                            chunkR,
                                            chunkC,
                                            mapping,
                                            xyz,
                                            at,
                                            originX,
                                            originY,
                                            height);
                        }
                        for (int i = 0; i < n; i++) {
                            indices[at[1]++] = perimeter[i];
                            indices[at[1]++] = center;
                            indices[at[1]++] = perimeter[(i + 1) % n];
                        }
                    }
                }
            }
        }
        if (at[0] != xyz.length || at[1] != indices.length) {
            throw new AssertionError("Geometry preflight mismatch");
        }
        return new Mesh(xyz, indices, count);
    }

    private static int vertex(
            int r2,
            int c2,
            int chunkR,
            int chunkC,
            int[] mapping,
            float[] xyz,
            int[] at,
            double originX,
            double originY,
            DoubleBinaryOperator height) {
        int source = sample(r2, c2, chunkR, chunkC);
        if (mapping[source] >= 0) {
            return mapping[source];
        }
        int id = at[0] / 3;
        mapping[source] = id;
        double z = height.applyAsDouble(r2 * .5, c2 * .5);
        if (!Double.isFinite(z) || Math.abs(z) > TerrainScene.MAX_COORDINATE) {
            throw new Refused("Source height bound");
        }
        xyz[at[0]++] = (float) (originX - r2 * .5 * UNIT);
        xyz[at[0]++] = (float) (originY - c2 * .5 * UNIT);
        xyz[at[0]++] = (float) z;
        return id;
    }

    long planningBytes() {
        return levels.length + (levels.length + 7L) / 8;
    }

    private void check() {
        if (canceled.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
