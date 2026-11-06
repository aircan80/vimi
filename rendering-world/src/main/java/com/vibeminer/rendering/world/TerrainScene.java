package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Objects;

/** Immutable native-coordinate snapshot; construction belongs on a bounded preparation worker. */
public final class TerrainScene {
    public static final int MAX_TILES = 9;
    public static final int MAX_VERTICES_PER_TILE = 256 * 145;
    public static final int MAX_INDICES_PER_TILE = 256 * 8 * 8 * 12;
    public static final int MAX_TRIANGLES = MAX_TILES * MAX_INDICES_PER_TILE / 3;
    public static final double MAX_COORDINATE = 1_000_000;

    public record Origin(double x, double y, double z) {
        public Origin {
            for (double value : new double[] {x, y, z}) {
                if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) {
                    throw new IllegalArgumentException("Invalid terrain origin");
                }
            }
        }
    }

    public static final class Tile {
        private final int x, y, chunks;
        private final float[] vertices;
        private final int[] indices;
        // Immutable payload, so its geometry fingerprint is computed at most once.
        private volatile String fingerprint;

        /** Copies the terrain's payload once through its read-only views. */
        public Tile(int x, int y, WorldFormats.Terrain terrain) {
            this(
                    x,
                    y,
                    copy(Objects.requireNonNull(terrain).vertexView()),
                    copy(terrain.triangleView()),
                    terrain.chunks(),
                    true);
        }

        private static float[] copy(FloatBuffer view) {
            float[] values = new float[view.remaining()];
            view.get(values);
            return values;
        }

        private static int[] copy(IntBuffer view) {
            int[] values = new int[view.remaining()];
            view.get(values);
            return values;
        }

        String cachedFingerprint() {
            return fingerprint;
        }

        void cacheFingerprint(String value) {
            fingerprint = value;
        }

        private Tile(int x, int y, float[] vertices, int[] indices, int chunks, boolean owned) {
            Objects.requireNonNull(vertices);
            Objects.requireNonNull(indices);
            if (x < 0
                    || x > 63
                    || y < 0
                    || y > 63
                    || chunks < 1
                    || chunks > 256
                    || vertices.length == 0
                    || vertices.length % 3 != 0
                    || vertices.length > MAX_VERTICES_PER_TILE * 3
                    || indices.length % 3 != 0
                    || indices.length > MAX_INDICES_PER_TILE) {
                throw new IllegalArgumentException("Invalid terrain tile bounds");
            }
            vertices = owned ? vertices : vertices.clone();
            indices = owned ? indices : indices.clone();
            for (float coordinate : vertices) {
                if (!Float.isFinite(coordinate) || Math.abs(coordinate) > MAX_COORDINATE) {
                    throw new IllegalArgumentException("Invalid terrain coordinate");
                }
            }
            int count = vertices.length / 3;
            for (int index : indices) {
                if (index < 0 || index >= count) {
                    throw new IllegalArgumentException("Invalid terrain index");
                }
            }
            this.x = x;
            this.y = y;
            this.chunks = chunks;
            this.vertices = vertices;
            this.indices = indices;
        }

        /** Copies bounded caller arrays; views never expose their backing arrays. */
        public static Tile copyOf(int x, int y, float[] vertices, int[] indices, int chunks) {
            return new Tile(x, y, vertices, indices, chunks, false);
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int chunks() {
            return chunks;
        }

        public int vertexCount() {
            return vertices.length / 3;
        }

        public int indexCount() {
            return indices.length;
        }

        public int triangleCount() {
            return indices.length / 3;
        }

        public FloatBuffer vertices() {
            return FloatBuffer.wrap(vertices).asReadOnlyBuffer();
        }

        public IntBuffer indices() {
            return IntBuffer.wrap(indices).asReadOnlyBuffer();
        }

        public long payloadBytes() {
            return (long) vertices.length * Float.BYTES + (long) indices.length * Integer.BYTES;
        }
    }

    private final List<Tile> tiles;
    private final Origin origin;
    private final long payloadBytes;
    private final int triangleCount;

    public TerrainScene(List<Tile> tiles) {
        Objects.requireNonNull(tiles);
        if (tiles.isEmpty() || tiles.size() > MAX_TILES) {
            throw new IllegalArgumentException("Terrain scene requires 1..9 tiles");
        }
        this.tiles = List.copyOf(tiles);
        boolean[] seen = new boolean[64 * 64];
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = -minX, maxY = -minX, maxZ = -minX;
        long bytes = 0;
        int triangles = 0;
        for (var tile : this.tiles) {
            int key = tile.y * 64 + tile.x;
            if (seen[key]) {
                throw new IllegalArgumentException("Duplicate terrain tile");
            }
            seen[key] = true;
            bytes += tile.payloadBytes();
            triangles += tile.triangleCount();
            for (int at = 0; at < tile.vertices.length; at += 3) {
                minX = Math.min(minX, tile.vertices[at]);
                maxX = Math.max(maxX, tile.vertices[at]);
                minY = Math.min(minY, tile.vertices[at + 1]);
                maxY = Math.max(maxY, tile.vertices[at + 1]);
                minZ = Math.min(minZ, tile.vertices[at + 2]);
                maxZ = Math.max(maxZ, tile.vertices[at + 2]);
            }
        }
        this.origin = new Origin((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        this.payloadBytes = bytes;
        this.triangleCount = triangles;
    }

    public List<Tile> tiles() {
        return tiles;
    }

    public Origin origin() {
        return origin;
    }

    public long payloadBytes() {
        return payloadBytes;
    }

    public int triangleCount() {
        return triangleCount;
    }
}
