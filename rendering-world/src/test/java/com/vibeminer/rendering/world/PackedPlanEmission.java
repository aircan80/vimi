package com.vibeminer.rendering.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-only source-index emitter. These batches are not ADTs or production scene/IPC objects. */
final class PackedPlanEmission {
    record Leaf(
            int source,
            int chunk,
            int row,
            int column,
            int size,
            int mask,
            int firstIndex,
            int indexCount) {}

    record Batch(int vertices, int indices) {}

    final float[] positions;
    final int[] indices, sourceSamples;
    final List<Leaf> leaves = new ArrayList<>();
    final List<Batch> chunks = new ArrayList<>(), batches = new ArrayList<>();
    private final PackedTerrainPlanner.ScopedPlan plan;
    private final int[] mapped = new int[145];
    private int source = -1, chunk = -1, vertices, indexCount, chunkStartVertex, chunkStartIndex;

    PackedPlanEmission(PackedTerrainPlanner.ScopedPlan plan) {
        this.plan = plan;
        var counts = plan.counts();
        if (counts.vertices() > 334080 || counts.indices() > 1769472 || counts.batches() > 9) {
            throw new AssertionError("Output preflight caps");
        }
        positions = new float[counts.vertices() * 3];
        indices = new int[counts.indices()];
        sourceSamples = new int[counts.vertices()];
        plan.visit(this::leaf, () -> false);
        finishChunk();
        if (vertices != counts.vertices() || indexCount != counts.indices()) {
            throw new AssertionError("Counted/emitted geometry differs");
        }
        int bv = 0, bi = 0;
        for (var piece : chunks) {
            if (bv + piece.vertices() > 37120 || bi + piece.indices() > 196608) {
                batches.add(new Batch(bv, bi));
                bv = 0;
                bi = 0;
            }
            bv += piece.vertices();
            bi += piece.indices();
        }
        if (bi > 0) {
            batches.add(new Batch(bv, bi));
        }
        if (batches.size() != counts.batches()) {
            throw new AssertionError("Counted/emitted partitions differ");
        }
    }

    private void leaf(int s, int ch, int row, int col, int size, int mask) {
        if (source != s || chunk != ch) {
            finishChunk();
            source = s;
            chunk = ch;
            Arrays.fill(mapped, -1);
            chunkStartVertex = vertices;
            chunkStartIndex = indexCount;
        }
        leaves.add(
                new Leaf(
                        s, ch, row, col, size, mask, indexCount, (4 + Integer.bitCount(mask)) * 3));
        int half = size / 2;
        int center = size == 1 ? row * 17 + col + 9 : (row + half) * 17 + col + half;
        int[] perimeter = new int[8];
        int n = 0;
        perimeter[n++] = row * 17 + col;
        if ((mask & 1) != 0) {
            perimeter[n++] = row * 17 + col + half;
        }
        perimeter[n++] = row * 17 + col + size;
        if ((mask & 2) != 0) {
            perimeter[n++] = (row + half) * 17 + col + size;
        }
        perimeter[n++] = (row + size) * 17 + col + size;
        if ((mask & 4) != 0) {
            perimeter[n++] = (row + size) * 17 + col + half;
        }
        perimeter[n++] = (row + size) * 17 + col;
        if ((mask & 8) != 0) {
            perimeter[n++] = (row + half) * 17 + col;
        }
        for (int i = 0; i < n; i++) {
            indices[indexCount++] = vertex(perimeter[i]);
            indices[indexCount++] = vertex(center);
            indices[indexCount++] = vertex(perimeter[(i + 1) % n]);
        }
    }

    private int vertex(int sample) {
        if (mapped[sample] < 0) {
            int index = vertices++;
            plan.copyPosition(source, chunk, sample, positions, index * 3);
            sourceSamples[index] = source * 37120 + chunk * 145 + sample;
            mapped[sample] = index;
        }
        return mapped[sample];
    }

    private void finishChunk() {
        if (source >= 0) {
            chunks.add(new Batch(vertices - chunkStartVertex, indexCount - chunkStartIndex));
        }
    }
}
