package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Single-credit worker preparation. Consumes decoded heap ownership; performs no bgfx calls.
 *
 * <p>Geometry is one indexed mesh per tile (issue #373): every vertex once, with its position and
 * an RGBA8 fallback colour, then u16 indices. Terrain comes first and keeps the tile's own vertex
 * order (145 shared vertices per ADT chunk) and triangle order; water follows as its own vertex and
 * index ranges, its indices relative to its first vertex. Terrain triangles are grouped into runs
 * of consecutive triangles of one chunk, each with a world bounding sphere, so the renderer culls
 * whole chunks.
 */
final class WorldStreamPackedTile implements AutoCloseable {
    static final int MAX_WATER_TRIANGLES = 16384;

    /** Bytes per mesh vertex: float x, y, z then an RGBA8 colour. */
    static final int VERTEX_BYTES = 16;

    static final int MAX_TERRAIN_VERTICES =
            com.vibeminer.rendering.world.TerrainScene.MAX_VERTICES_PER_TILE;
    static final int MAX_TERRAIN_INDICES =
            com.vibeminer.rendering.world.TerrainScene.MAX_INDICES_PER_TILE;
    static final int MAX_WATER_VERTICES = WorldStreamTileFile.MAX_WATER_CELLS * 4;
    static final int MAX_WATER_INDICES = MAX_WATER_TRIANGLES * 3;
    static final int MAX_VERTICES = MAX_TERRAIN_VERTICES + MAX_WATER_VERTICES;
    static final int MAX_INDICES = MAX_TERRAIN_INDICES + MAX_WATER_INDICES;
    static final int MAX_GEOMETRY_BYTES = MAX_VERTICES * VERTEX_BYTES + MAX_INDICES * 2;

    /** Vertices of one ADT chunk, the run key of a triangle: its first vertex / 145. */
    static final int CHUNK_VERTICES = 145;

    /** At most this many cull runs per tile; more (an unusual mesh) collapse into one run. */
    static final int MAX_RUNS = 1024;

    /** One credit's largest terrain input: geometry plus the atlas with its mip chain. */
    static final long MAX_PAYLOAD_BYTES =
            (long) MAX_GEOMETRY_BYTES + WorldStreamAtlasMips.CHAIN_BYTES;

    // Untextured diagnostic teal (sRGB 20,115,148); not a claim of client liquid appearance.
    static final float WATER_R = 20, WATER_G = 115, WATER_B = 148;

    /** Transferred native input owner. Never freed before all make_ref_release credits drain. */
    static final class Source implements AutoCloseable {
        final WorldStreamTileFile.Header header;

        /** Terrain then water triangles. */
        final int triangles, waterTriangles;

        /** Mesh vertex and index counts: terrain first, then water, in geometry. */
        final int terrainVertices, waterVertices, terrainIndices, waterIndices;

        /** Per terrain run: first index, index count. */
        final int[] runs;

        /** Per terrain run: world bounding sphere x, y, z, radius. */
        final double[] runSpheres;

        ByteBuffer geometry, atlas;

        /** Optional exterior objects to upload after terrain, or null. */
        WorldStreamPackedObjects objects;

        /** -1 outside the exterior profile; else 0 none, 1 decoded, 2 failed (terrain kept). */
        final int objectsState;

        private Source(
                WorldStreamTileFile.Header header,
                Mesh mesh,
                ByteBuffer geometry,
                ByteBuffer atlas,
                WorldStreamPackedObjects objects,
                int objectsState) {
            this.header = header;
            this.objects = objects;
            this.objectsState = objectsState;
            this.triangles = mesh.terrainIndices / 3;
            this.waterTriangles = mesh.waterIndices / 3;
            this.terrainVertices = mesh.terrainVertices;
            this.waterVertices = mesh.waterVertices;
            this.terrainIndices = mesh.terrainIndices;
            this.waterIndices = mesh.waterIndices;
            this.runs = mesh.runs;
            this.runSpheres = mesh.spheres;
            this.geometry = geometry;
            this.atlas = atlas;
        }

        /** Byte offset of the index section in geometry. */
        int indexOffset() {
            return (terrainVertices + waterVertices) * VERTEX_BYTES;
        }

        long payloadBytes() {
            return (geometry == null ? 0 : geometry.capacity())
                    + (long) (atlas == null ? 0 : atlas.capacity())
                    + (objects == null ? 0 : objects.payloadBytes());
        }

        @Override
        public void close() {
            if (objects != null) {
                objects.close();
                objects = null;
            }
            if (geometry != null) {
                MemoryUtil.memFree(geometry);
                geometry = null;
            }
            if (atlas != null) {
                MemoryUtil.memFree(atlas);
                atlas = null;
            }
        }
    }

    private Source owned;

    private WorldStreamPackedTile(Source owned) {
        this.owned = owned;
    }

    static WorldStreamPackedTile prepare(
            WorldStreamTileFile.Payload input, BooleanSupplier canceled) {
        return prepare(input, null, -1, canceled);
    }

    /**
     * Takes ownership of optional packed objects (closed on any failure). objectsState follows
     * {@link Source#objectsState}; state 1 requires objects.
     */
    static WorldStreamPackedTile prepare(
            WorldStreamTileFile.Payload input,
            WorldStreamPackedObjects objects,
            int objectsState,
            BooleanSupplier canceled) {
        try {
            Objects.requireNonNull(input);
            Objects.requireNonNull(canceled);
            if (objectsState < -1 || objectsState > 2 || (objectsState == 1) != (objects != null)) {
                throw new IllegalArgumentException("Packed objects state");
            }
            var result = prepareTerrain(input, objects, objectsState, canceled);
            objects = null;
            return result;
        } finally {
            if (objects != null) {
                objects.close();
            }
        }
    }

    private static WorldStreamPackedTile prepareTerrain(
            WorldStreamTileFile.Payload input,
            WorldStreamPackedObjects objects,
            int objectsState,
            BooleanSupplier canceled) {
        ByteBuffer geometry = null, atlas = null;
        try (input) {
            check(canceled);
            var tile = input.tile();
            var water = input.water();
            var mesh = mesh(tile, water, canceled);
            if (mesh.bytes() != 0) {
                geometry = MemoryUtil.memAlloc(mesh.bytes()).order(ByteOrder.nativeOrder());
                write(geometry, tile, water, mesh, canceled);
            }
            var pixels = input.atlas();
            if (pixels != null) {
                if (pixels.remaining() != WorldStreamTileFile.ATLAS_BYTES) {
                    throw new IllegalArgumentException("Packed atlas size");
                }
                // Level 0 starts as the qualified atlas. The worker adds the filtered mip chain and
                // fills the RGB of uncovered (alpha 0) texels, at every level, with nearby ground.
                atlas = MemoryUtil.memAlloc(WorldStreamAtlasMips.CHAIN_BYTES);
                for (int at = 0; at < pixels.remaining(); at += 64 << 10) {
                    check(canceled);
                    int count = Math.min(64 << 10, pixels.remaining() - at);
                    atlas.put(pixels.duplicate().position(at).limit(at + count));
                }
                atlas.clear();
                WorldStreamAtlasMips.build(atlas, canceled);
            }
            check(canceled);
            var result =
                    new WorldStreamPackedTile(
                            new Source(
                                    input.header(), mesh, geometry, atlas, objects, objectsState));
            geometry = null;
            atlas = null;
            return result;
        } finally {
            if (geometry != null) {
                MemoryUtil.memFree(geometry);
            }
            if (atlas != null) {
                MemoryUtil.memFree(atlas);
            }
        }
    }

    synchronized WorldStreamTileFile.Header header() {
        if (owned == null) {
            throw new IllegalStateException("Packed input consumed or closed");
        }
        return owned.header;
    }

    synchronized int objectsState() {
        if (owned == null) {
            throw new IllegalStateException("Packed input consumed or closed");
        }
        return owned.objectsState;
    }

    synchronized Source take() {
        if (owned == null) {
            throw new IllegalStateException("Packed input consumed or closed");
        }
        var result = owned;
        owned = null;
        return result;
    }

    @Override
    public synchronized void close() {
        if (owned != null) {
            owned.close();
            owned = null;
        }
    }

    /** Counts and cull runs of one tile's mesh; geometry bytes follow from the counts. */
    record Mesh(
            int terrainVertices,
            int waterVertices,
            int terrainIndices,
            int waterIndices,
            int[] runs,
            double[] spheres) {
        int bytes() {
            return (terrainVertices + waterVertices) * VERTEX_BYTES
                    + (terrainIndices + waterIndices) * 2;
        }
    }

    /**
     * Plans the mesh: counts within the one-credit bounds, and terrain runs of consecutive
     * triangles whose first vertex lies in the same 145-vertex chunk, each with the bounding sphere
     * (box centre, farthest referenced vertex) of the vertices it references.
     */
    static Mesh mesh(
            com.vibeminer.rendering.world.TerrainScene.Tile tile,
            WorldStreamTileFile.Water water,
            BooleanSupplier canceled) {
        int vertices = tile.vertexCount(), indexCount = tile.indexCount();
        int waterVertices = water == null ? 0 : water.positions().length / 3;
        int waterIndices = water == null ? 0 : water.indices().length;
        if (vertices > MAX_TERRAIN_VERTICES
                || indexCount > MAX_TERRAIN_INDICES
                || waterVertices > MAX_WATER_VERTICES
                || waterIndices > MAX_WATER_INDICES) {
            throw new IllegalArgumentException("Packed tile exceeds one-credit geometry limit");
        }
        var indices = tile.indices();
        var runs = new java.util.ArrayList<int[]>();
        int runStart = 0, key = -1;
        for (int at = 0; at < indexCount; at += 3) {
            int next = indices.get(at) / CHUNK_VERTICES;
            if (next != key) {
                if (at != 0) {
                    runs.add(new int[] {runStart, at - runStart});
                }
                runStart = at;
                key = next;
            }
        }
        if (indexCount != 0) {
            runs.add(new int[] {runStart, indexCount - runStart});
        }
        if (runs.size() > MAX_RUNS) {
            runs.clear();
            runs.add(new int[] {0, indexCount});
        }
        var positions = tile.vertices();
        var flat = new int[runs.size() * 2];
        var spheres = new double[runs.size() * 4];
        for (int r = 0; r < runs.size(); r++) {
            check(canceled);
            int first = runs.get(r)[0], count = runs.get(r)[1];
            flat[r * 2] = first;
            flat[r * 2 + 1] = count;
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int i = first; i < first + count; i++) {
                int v = indices.get(i) * 3;
                minX = Math.min(minX, positions.get(v));
                maxX = Math.max(maxX, positions.get(v));
                minY = Math.min(minY, positions.get(v + 1));
                maxY = Math.max(maxY, positions.get(v + 1));
                minZ = Math.min(minZ, positions.get(v + 2));
                maxZ = Math.max(maxZ, positions.get(v + 2));
            }
            double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
            double radius = 0;
            for (int i = first; i < first + count; i++) {
                int v = indices.get(i) * 3;
                double dx = positions.get(v) - cx, dy = positions.get(v + 1) - cy;
                double dz = positions.get(v + 2) - cz;
                radius = Math.max(radius, dx * dx + dy * dy + dz * dz);
            }
            spheres[r * 4] = cx;
            spheres[r * 4 + 1] = cy;
            spheres[r * 4 + 2] = cz;
            spheres[r * 4 + 3] = Math.sqrt(radius) + WorldStreamObjectCulling.RADIUS_SLACK;
        }
        return new Mesh(vertices, waterVertices, indexCount, waterIndices, flat, spheres);
    }

    /**
     * Writes the mesh: terrain vertices with their fallback colour, water vertices in diagnostic
     * teal, then terrain and water indices. A terrain vertex is shaded from its area-weighted
     * vertex normal with the light and altitude tint each triangle used to get.
     */
    private static void write(
            ByteBuffer out,
            com.vibeminer.rendering.world.TerrainScene.Tile tile,
            WorldStreamTileFile.Water water,
            Mesh mesh,
            BooleanSupplier canceled) {
        var positions = tile.vertices();
        var indices = tile.indices();
        int vertices = mesh.terrainVertices;
        var normals = new double[vertices * 3];
        for (int at = 0; at < mesh.terrainIndices; at += 3) {
            if ((at & 4095) == 0) {
                check(canceled);
            }
            int a = indices.get(at), b = indices.get(at + 1), c = indices.get(at + 2);
            double ux = positions.get(b * 3) - positions.get(a * 3);
            double uy = positions.get(b * 3 + 1) - positions.get(a * 3 + 1);
            double uz = positions.get(b * 3 + 2) - positions.get(a * 3 + 2);
            double vx = positions.get(c * 3) - positions.get(a * 3);
            double vy = positions.get(c * 3 + 1) - positions.get(a * 3 + 1);
            double vz = positions.get(c * 3 + 2) - positions.get(a * 3 + 2);
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int v : new int[] {a, b, c}) {
                normals[v * 3] += nx;
                normals[v * 3 + 1] += ny;
                normals[v * 3 + 2] += nz;
            }
        }
        for (int v = 0; v < vertices; v++) {
            if ((v & 4095) == 0) {
                check(canceled);
            }
            float x = positions.get(v * 3), y = positions.get(v * 3 + 1);
            float z = positions.get(v * 3 + 2);
            double nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double light =
                    length == 0
                            ? .7
                            : .45 + .55 * Math.abs((nx * .3 + ny * .4 + nz * .866) / length);
            double rock = Math.clamp((z - 150) / 600, 0, 1);
            vertex(
                    out,
                    x,
                    y,
                    z,
                    (72 + rock * 65) * light,
                    (116 + rock * 10) * light,
                    (68 + rock * 57) * light);
        }
        if (water != null) {
            var waterPositions = water.positions();
            for (int v = 0; v < mesh.waterVertices; v++) {
                vertex(
                        out,
                        waterPositions[v * 3],
                        waterPositions[v * 3 + 1],
                        waterPositions[v * 3 + 2],
                        WATER_R,
                        WATER_G,
                        WATER_B);
            }
        }
        for (int at = 0; at < mesh.terrainIndices; at++) {
            if ((at & 16383) == 0) {
                check(canceled);
            }
            out.putShort((short) indices.get(at));
        }
        if (water != null) {
            for (int index : water.indices()) {
                out.putShort((short) index);
            }
        }
        if (out.position() != mesh.bytes()) {
            throw new IllegalStateException("Packed mesh size");
        }
        out.clear();
    }

    private static void vertex(
            ByteBuffer out, float x, float y, float z, double r, double g, double b) {
        out.putFloat(x).putFloat(y).putFloat(z);
        out.put(colour(r)).put(colour(g)).put(colour(b)).put((byte) 255);
    }

    private static byte colour(double value) {
        return (byte) Math.clamp(Math.round(value), 0, 255);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
