package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.lwjgl.system.MemoryUtil;

/**
 * Decoder-worker packing of one validated objects file into native-order upload memory. Performs no
 * bgfx calls; the renderer copies bounded slices with bgfx_copy and closes this owner.
 */
final class WorldStreamPackedObjects implements AutoCloseable {
    /**
     * GPU object vertex: the file's 32-byte position, normal and uv floats, then its vertex colour
     * as four bytes R, G, B, 0 (COLOR0, normalized; #375).
     */
    static final int GPU_VERTEX_BYTES = WorldStreamObjectsFile.VERTEX_BYTES + 4;

    final int[] models, meshes, ranges, placementModels, images;
    final double[] placements;

    /** Per placement world bounding sphere (x, y, z, radius), see WorldStreamObjectCulling. */
    final double[] spheres;

    final int draws;

    /** Per mesh: its group in its model's portal set, or -1 (#383). */
    final int[] meshGroups;

    /**
     * Per model: the traversal of its portal set, or null without one (#383 step 3a). Built here on
     * the decoder worker from the validated tables; the renderer does not cull with it yet.
     */
    private final WmoPortalTraversal[] portals;

    private final int portalSets;

    ByteBuffer vertices, indices, pixels;

    private WorldStreamPackedObjects(
            WorldStreamObjectsFile.Data data,
            ByteBuffer vertices,
            ByteBuffer indices,
            ByteBuffer pixels,
            BooleanSupplier canceled) {
        models = data.models.clone();
        meshes = data.meshes.clone();
        ranges = data.ranges.clone();
        placementModels = data.placementModels.clone();
        images = data.images.clone();
        placements = data.placements.clone();
        spheres =
                WorldStreamObjectCulling.placementSpheres(
                        placementModels,
                        placements,
                        WorldStreamObjectCulling.modelSpheres(
                                models, meshes, ranges, data.vertices, data.indices, canceled));
        draws = data.draws;
        meshGroups = data.meshGroups.clone();
        portals = new WmoPortalTraversal[data.modelCount()];
        portalSets = data.portalSetCount();
        for (int set = 0; set < portalSets; set++) {
            check(canceled);
            portals[data.portalModel(set)] = data.portalSet(set).traversal();
        }
        this.vertices = vertices;
        this.indices = indices;
        this.pixels = pixels;
    }

    static WorldStreamPackedObjects pack(
            WorldStreamObjectsFile.Data data, BooleanSupplier canceled) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(canceled);
        ByteBuffer vertices = null, indices = null, pixels = null;
        try {
            check(canceled);
            int vertexCount = data.vertexCount();
            vertices =
                    MemoryUtil.memAlloc(vertexCount * GPU_VERTEX_BYTES)
                            .order(ByteOrder.nativeOrder());
            interleave(data.vertices, data.colours, vertices, canceled);
            indices = MemoryUtil.memAlloc(data.indices.length * 2).order(ByteOrder.nativeOrder());
            indices.asShortBuffer().put(data.indices);
            check(canceled);
            pixels = MemoryUtil.memAlloc(data.pixels.length);
            for (int at = 0; at < data.pixels.length; at += 64 << 10) {
                check(canceled);
                pixels.put(data.pixels, at, Math.min(64 << 10, data.pixels.length - at));
            }
            pixels.flip();
            var result = new WorldStreamPackedObjects(data, vertices, indices, pixels, canceled);
            vertices = indices = pixels = null;
            return result;
        } finally {
            free(vertices);
            free(indices);
            free(pixels);
        }
    }

    /**
     * Writes each vertex's floats (native order) followed by its colour bytes R, G, B, 0 into out,
     * which holds exactly one GPU vertex per colour.
     */
    static void interleave(
            float[] floats, int[] colours, ByteBuffer out, BooleanSupplier canceled) {
        int stride = WorldStreamObjectsFile.VERTEX_FLOATS;
        for (int v = 0; v < colours.length; v++) {
            if ((v & 4095) == 0) {
                check(canceled);
            }
            int at = v * GPU_VERTEX_BYTES;
            for (int i = 0; i < stride; i++) {
                out.putFloat(at + i * 4, floats[v * stride + i]);
            }
            int colour = colours[v];
            out.put(at + 32, (byte) colour);
            out.put(at + 33, (byte) (colour >>> 8));
            out.put(at + 34, (byte) (colour >>> 16));
            out.put(at + 35, (byte) 0);
        }
    }

    int meshCount() {
        return meshes.length / 6;
    }

    /** The traversal of model {@code model}'s portal set, or null when it carries none. */
    WmoPortalTraversal portals(int model) {
        return portals[model];
    }

    int portalSetCount() {
        return portalSets;
    }

    int imageCount() {
        return images.length / WorldStreamObjectsFile.IMAGE_INTS;
    }

    int width(int image) {
        return images[image * WorldStreamObjectsFile.IMAGE_INTS];
    }

    int height(int image) {
        return images[image * WorldStreamObjectsFile.IMAGE_INTS + 1];
    }

    /** The image's stored format ({@link ObjectTextureFormats}). */
    int format(int image) {
        return images[image * WorldStreamObjectsFile.IMAGE_INTS + 2];
    }

    int placementCount() {
        return placementModels.length;
    }

    /** Native upload bytes this owner retains. */
    long payloadBytes() {
        return (vertices == null ? 0 : vertices.capacity())
                + (long) (indices == null ? 0 : indices.capacity())
                + (pixels == null ? 0 : pixels.capacity());
    }

    /** Byte offset of image i in pixels. */
    int imageOffset(int image) {
        int at = 0;
        for (int i = 0; i < image; i++) {
            at += ObjectTextureFormats.chainBytes(format(i), width(i), height(i));
        }
        return at;
    }

    @Override
    public void close() {
        free(vertices);
        free(indices);
        free(pixels);
        vertices = indices = pixels = null;
    }

    private static void free(ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}
