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
 * <p>Geometry is one indexed mesh per tile (issue #373): every vertex once, with its position, an
 * RGBA8 fallback colour, its MCCV colour and its normal, then u16 indices. Terrain comes first and
 * keeps the tile's own vertex order (145 shared vertices per ADT chunk) and triangle order; water
 * follows as its own vertex and index ranges, its indices relative to its first vertex. Terrain
 * triangles are grouped into runs of consecutive triangles of one chunk, each with a world bounding
 * sphere, so the renderer culls whole chunks.
 */
final class WorldStreamPackedTile implements AutoCloseable {
    static final int MAX_WATER_TRIANGLES = 16384;

    /**
     * Bytes per mesh vertex: float x, y, z; the RGBA8 fallback colour (unlit); the MCCV colour
     * (RGBA8: alpha 255 when present, all zero when absent); the normal as unsigned bytes {@code
     * round(127.5 + 127.5 * n)}, with (128, 128, 128) for an unlit vertex (water).
     */
    static final int VERTEX_BYTES = 24;

    /**
     * The normal bytes of an unlit vertex: decodes to almost zero, which the shader leaves unlit.
     */
    static final byte UNLIT_NORMAL = (byte) 128;

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

    static {
        // Terrain and water indices are written as u16, each relative to its own first vertex.
        if (MAX_TERRAIN_VERTICES > 65536 || MAX_WATER_VERTICES > 65536) {
            throw new ExceptionInInitializerError("Mesh vertices exceed u16 indices");
        }
    }

    /** One credit's largest terrain input: geometry plus the atlas with its mip chain. */
    static final long MAX_PAYLOAD_BYTES =
            (long) MAX_GEOMETRY_BYTES + WorldStreamAtlasMips.CHAIN_BYTES;

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

        /**
         * Optional splatting inputs (issue #174 step 2): the mask chain ({@code splatMaskSide}
         * square, box mips down to 1 x 1), the 16 x 16 table of four array layers, and new layers
         * with their full chains.
         */
        ByteBuffer splatAlpha;

        int splatMaskSide;

        byte[] splatTable;
        int[] splatLayers = new int[0];
        ByteBuffer[] splatChains = new ByteBuffer[0];

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
            long splat = splatAlpha == null ? 0 : splatAlpha.capacity() + 1024L;
            for (var chain : splatChains) {
                splat += chain.capacity();
            }
            return splat
                    + (geometry == null ? 0 : geometry.capacity())
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
            // Optional splatting inputs ride heap buffers: the renderer copies them in bounded
            // slices, so they never hold a release ticket.
            var splat = atlas == null ? null : input.splat();
            ByteBuffer splatAlpha = null;
            ByteBuffer[] splatChains = new ByteBuffer[0];
            if (splat != null) {
                splatAlpha = maskChain(splat.alpha(), splat.maskSide(), canceled);
                splatChains = new ByteBuffer[splat.layers().length];
                for (int i = 0; i < splatChains.length; i++) {
                    check(canceled);
                    // The layer's BC1 chain as carried; the renderer decodes it only when its
                    // backend has no BC1 array.
                    splatChains[i] = ByteBuffer.wrap(splat.pixels()[i]).asReadOnlyBuffer();
                }
            }
            check(canceled);
            var source = new Source(input.header(), mesh, geometry, atlas, objects, objectsState);
            if (splat != null) {
                source.splatAlpha = splatAlpha;
                source.splatMaskSide = splat.maskSide();
                source.splatTable = splat.table();
                source.splatLayers = splat.layers();
                source.splatChains = splatChains;
            }
            var result = new WorldStreamPackedTile(source);
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
        var flat = runs(indices, indexCount);
        int runCount = flat.length / 2;
        var positions = tile.vertices();
        var spheres = new double[runCount * 4];
        for (int r = 0; r < runCount; r++) {
            check(canceled);
            int first = flat[r * 2], count = flat[r * 2 + 1];
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
     * The terrain runs of {@code indexCount} indices as (first index, index count) pairs: runs of
     * consecutive triangles whose first vertex lies in the same chunk. A mesh with more than {@link
     * #MAX_RUNS} runs is one run of every index; the scan stops at the triangle that opens run
     * {@code MAX_RUNS + 1}, so an unusual mesh builds no more than one fixed array of runs.
     */
    static int[] runs(java.nio.IntBuffer indices, int indexCount) {
        var bounds = new int[MAX_RUNS * 2];
        int count = 0, runStart = 0, key = -1;
        for (int at = 0; at < indexCount; at += 3) {
            int next = indices.get(at) / CHUNK_VERTICES;
            if (next != key) {
                if (at != 0) {
                    bounds[count * 2] = runStart;
                    bounds[count * 2 + 1] = at - runStart;
                    if (++count == MAX_RUNS) {
                        // This triangle opens run MAX_RUNS + 1: too many, stop here.
                        return new int[] {0, indexCount};
                    }
                }
                runStart = at;
                key = next;
            }
        }
        if (indexCount != 0) {
            bounds[count * 2] = runStart;
            bounds[count * 2 + 1] = indexCount - runStart;
            count++;
        }
        return java.util.Arrays.copyOf(bounds, count * 2);
    }

    /**
     * Writes the mesh: terrain vertices with their unlit altitude-tint fallback colour, MCCV colour
     * and normal, water vertices in diagnostic teal and unlit, then terrain and water indices. A
     * terrain vertex takes its MCNR normal when its chunk has one, else the area-weighted normal of
     * its triangles; the shader lights both (#373).
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
        var attributes = tile.attributes();
        boolean attributed = attributes.hasRemaining();
        for (int v = 0; v < vertices; v++) {
            if ((v & 4095) == 0) {
                check(canceled);
            }
            float x = positions.get(v * 3), y = positions.get(v * 3 + 1);
            float z = positions.get(v * 3 + 2);
            double nx = normals[v * 3], ny = normals[v * 3 + 1], nz = normals[v * 3 + 2];
            int a = v * WorldStreamTileFile.ATTRIBUTE_BYTES;
            if (attributed
                    && (attributes.get(a) != 0
                            || attributes.get(a + 1) != 0
                            || attributes.get(a + 2) != 0)) {
                // MCNR: signed bytes in the position axes, 127 = 1.0; -128 is clamped.
                nx = Math.max(-127, attributes.get(a));
                ny = Math.max(-127, attributes.get(a + 1));
                nz = Math.max(-127, attributes.get(a + 2));
            }
            double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length == 0) {
                nx = ny = 0;
                nz = length = 1;
            }
            double rock = Math.clamp((z - 150) / 600, 0, 1);
            vertex(out, x, y, z, 72 + rock * 65, 116 + rock * 10, 68 + rock * 57);
            if (attributed && attributes.get(a + 7) != 0) {
                out.put(attributes.get(a + 4))
                        .put(attributes.get(a + 5))
                        .put(attributes.get(a + 6))
                        .put((byte) 255);
            } else {
                out.putInt(0);
            }
            out.put(normal(nx / length)).put(normal(ny / length)).put(normal(nz / length));
            out.put((byte) 255);
        }
        if (water != null) {
            var waterPositions = water.positions();
            var colours = water.colours();
            for (int v = 0; v < mesh.waterVertices; v++) {
                // Each liquid's own colour (#171): water, or a magma or slime flat colour.
                vertex(
                        out,
                        waterPositions[v * 3],
                        waterPositions[v * 3 + 1],
                        waterPositions[v * 3 + 2],
                        colours[v * 3] & 255,
                        colours[v * 3 + 1] & 255,
                        colours[v * 3 + 2] & 255);
                out.putInt(0);
                out.put(UNLIT_NORMAL).put(UNLIT_NORMAL).put(UNLIT_NORMAL).put((byte) 255);
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

    /** One unit normal component as an unsigned normalized byte. */
    static byte normal(double component) {
        return (byte) Math.clamp(Math.round(127.5 + 127.5 * component), 0, 255);
    }

    private static byte colour(double value) {
        return (byte) Math.clamp(Math.round(value), 0, 255);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }

    /**
     * A square RGBA8 mask map's full chain, {@code side} down to 1 x 1 in the {@link
     * com.vibeminer.rendering.world.ObjectTextureMips} layout, each level a 2 x 2 box of the one
     * above (issue #174 step 2b-2: 512 or 1024 per side, by tier).
     */
    static ByteBuffer maskChain(
            byte[] alpha, int side, java.util.function.BooleanSupplier canceled) {
        if (alpha.length != side * side * 4) {
            throw new IllegalArgumentException("Splat mask size");
        }
        var chain =
                ByteBuffer.allocate(
                        com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(side, side)
                                * 4);
        chain.put(0, alpha);
        int levels = com.vibeminer.rendering.world.ObjectTextureMips.levels(side, side);
        for (int level = 1; level < levels; level++) {
            check(canceled);
            int s = side >> level, child = s * 2;
            int from =
                    com.vibeminer.rendering.world.ObjectTextureMips.levelOffset(
                            side, side, level - 1);
            int to = com.vibeminer.rendering.world.ObjectTextureMips.levelOffset(side, side, level);
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    for (int c = 0; c < 4; c++) {
                        int sum = 0;
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dx = 0; dx < 2; dx++) {
                                sum +=
                                        chain.get(
                                                        from
                                                                + ((2 * y + dy) * child
                                                                                + 2 * x
                                                                                + dx)
                                                                        * 4
                                                                + c)
                                                & 255;
                            }
                        }
                        chain.put(to + (y * s + x) * 4 + c, (byte) ((sum + 2) / 4));
                    }
                }
            }
        }
        return chain;
    }
}
