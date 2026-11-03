package com.vibeminer.rendering.models;

import java.util.List;

/** Immutable native-Z-up static surface; no animation or character appearance is implied. */
public final class PreviewMesh {
    public static final int MAX_VERTICES = 65536, MAX_INDICES = 1_500_000, MAX_PARTS = 256;

    public record Part(int id, int firstIndex, int indexCount) {}

    final float[] positions;
    final int[] indices;
    private final List<Part> parts;
    private final float[] min = new float[3], max = new float[3];

    public PreviewMesh(float[] positions, int[] indices, List<Part> parts) {
        if (positions.length == 0
                || positions.length % 3 != 0
                || positions.length / 3 > MAX_VERTICES
                || indices.length == 0
                || indices.length % 3 != 0
                || indices.length > MAX_INDICES
                || parts.isEmpty()
                || parts.size() > MAX_PARTS) {
            throw new IllegalArgumentException("Static mesh exceeds preview bounds");
        }
        this.positions = positions.clone();
        this.indices = indices.clone();
        this.parts = List.copyOf(parts);
        java.util.Arrays.fill(min, Float.POSITIVE_INFINITY);
        java.util.Arrays.fill(max, Float.NEGATIVE_INFINITY);
        for (int i = 0; i < this.positions.length; i++) {
            float value = this.positions[i];
            if (!Float.isFinite(value) || Math.abs(value) > 1_000_000) {
                throw new IllegalArgumentException("Invalid model coordinate");
            }
            min[i % 3] = Math.min(min[i % 3], value);
            max[i % 3] = Math.max(max[i % 3], value);
        }
        for (int index : this.indices) {
            if (index < 0 || index >= vertexCount()) {
                throw new IllegalArgumentException("Triangle index outside mesh");
            }
        }
        for (var part : parts) {
            if (part.firstIndex() < 0
                    || part.indexCount() < 0
                    || part.firstIndex() % 3 != 0
                    || part.indexCount() % 3 != 0
                    || part.firstIndex() > indices.length - part.indexCount()) {
                throw new IllegalArgumentException("Invalid surface part");
            }
        }
    }

    public int vertexCount() {
        return positions.length / 3;
    }

    public int triangleCount() {
        return indices.length / 3;
    }

    public float[] positions() {
        return positions.clone();
    }

    public int[] indices() {
        return indices.clone();
    }

    public List<Part> parts() {
        return parts;
    }

    public float minimum(int axis) {
        return min[axis];
    }

    public float maximum(int axis) {
        return max[axis];
    }
}
