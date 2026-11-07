package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.ObjectTextureFormats;
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

    /**
     * Compressed images this owner decoded to RGBA8 because the renderer does not sample their
     * format, and compressed images it keeps as blocks (issue #374); diagnostics.
     */
    final int decodedImages, compressedImages;

    ByteBuffer vertices, indices, pixels;

    private WorldStreamPackedObjects(
            WorldStreamObjectsFile.Data data,
            int[] images,
            ByteBuffer vertices,
            ByteBuffer indices,
            ByteBuffer pixels,
            int decodedImages,
            int compressedImages,
            BooleanSupplier canceled) {
        models = data.models.clone();
        meshes = data.meshes.clone();
        ranges = data.ranges.clone();
        placementModels = data.placementModels.clone();
        this.images = images;
        this.decodedImages = decodedImages;
        this.compressedImages = compressedImages;
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

    /** Packs with every compressed format sampled: chains stay as stored. */
    static WorldStreamPackedObjects pack(
            WorldStreamObjectsFile.Data data, BooleanSupplier canceled) {
        return pack(data, canceled, 0xE);
    }

    /**
     * Packs for a renderer that samples {@code sampledFormats} as blocks (bit per {@link
     * ObjectTextureFormats} code). A chain in any other compressed format is decoded to RGBA8 here,
     * on the decode worker, so no upload step decodes (issue #374); the file's GPU byte cap already
     * charges such images their decoded size.
     *
     * <p>Peak native memory: the packed pixels are what the GPU will hold, at most {@link
     * WorldStreamObjectsFile#MAX_DECODED_BYTES} per set, not the file's stored bytes. A set whose
     * BC chains are decoded here can be several times its stored size, so anything sizing how many
     * packed sets may wait for upload must count this bound, not the file length.
     */
    static WorldStreamPackedObjects pack(
            WorldStreamObjectsFile.Data data, BooleanSupplier canceled, int sampledFormats) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(canceled);
        if ((data.compressedFormats & ~sampledFormats) != 0) {
            throw new IllegalArgumentException(
                    "Objects file charges compressed formats the renderer decodes");
        }
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
            int[] images = data.images.clone();
            int count = images.length / WorldStreamObjectsFile.IMAGE_INTS;
            long total = 0;
            int decoded = 0, compressed = 0;
            for (int i = 0; i < count; i++) {
                int width = images[i * 3], height = images[i * 3 + 1], format = images[i * 3 + 2];
                boolean decode =
                        ObjectTextureFormats.compressed(format)
                                && (sampledFormats & (1 << format)) == 0;
                total +=
                        decode
                                ? com.vibeminer.rendering.world.ObjectTextureMips.chainPixels(
                                                width, height)
                                        * 4L
                                : ObjectTextureFormats.chainBytes(format, width, height);
            }
            pixels = MemoryUtil.memAlloc(Math.toIntExact(total));
            var source = java.nio.ByteBuffer.wrap(data.pixels);
            int from = 0;
            for (int i = 0; i < count; i++) {
                check(canceled);
                int width = images[i * 3], height = images[i * 3 + 1], format = images[i * 3 + 2];
                int stored = ObjectTextureFormats.chainBytes(format, width, height);
                if (ObjectTextureFormats.compressed(format)
                        && (sampledFormats & (1 << format)) == 0) {
                    pixels.put(
                            ObjectTextureFormats.decodeChain(format, width, height, source, from));
                    images[i * 3 + 2] = ObjectTextureFormats.RGBA8;
                    decoded++;
                } else {
                    for (int at = 0; at < stored; at += 64 << 10) {
                        pixels.put(data.pixels, from + at, Math.min(64 << 10, stored - at));
                    }
                    compressed += ObjectTextureFormats.compressed(format) ? 1 : 0;
                }
                from += stored;
            }
            pixels.flip();
            var result =
                    new WorldStreamPackedObjects(
                            data, images, vertices, indices, pixels, decoded, compressed, canceled);
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
