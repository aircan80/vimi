package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureMips;
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
    final int[] models, meshes, ranges, placementModels, images;
    final double[] placements;

    /** Per placement world bounding sphere (x, y, z, radius), see WorldStreamObjectCulling. */
    final double[] spheres;

    final int draws;
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
            vertices = MemoryUtil.memAlloc(data.vertices.length * 4).order(ByteOrder.nativeOrder());
            var floats = vertices.asFloatBuffer();
            for (int at = 0; at < data.vertices.length; at += 16384) {
                check(canceled);
                floats.put(data.vertices, at, Math.min(16384, data.vertices.length - at));
            }
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

    int meshCount() {
        return meshes.length / 6;
    }

    int imageCount() {
        return images.length / 2;
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
            at += ObjectTextureMips.chainPixels(images[i * 2], images[i * 2 + 1]) * 4;
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
