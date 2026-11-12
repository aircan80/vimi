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

    /** Per range: model-space centre of a blended range's bounds, see WorldStreamObjectCulling. */
    final float[] rangeCentres;

    final int draws;

    /**
     * Compressed images this owner decoded to RGBA8 because the renderer does not sample their
     * format, and compressed images it keeps as blocks (issue #374); diagnostics.
     */
    final int decodedImages, compressedImages;

    /** Per mesh: its group in its model's portal set, or -1 (#383). */
    final int[] meshGroups;

    /**
     * Per placement: the placement whose portal set it is tied to and the interior group of that
     * set, or -1 and -1 (see {@link WorldStreamObjectsFile.Data#placementParents}).
     */
    final int[] placementParents, placementGroups;

    /**
     * Per placement: its index in {@link #lights}, or -1 for the World light. Equal lights share
     * one index, so placements of one model and one light draw as one instanced group.
     */
    final int[] placementLights;

    /**
     * Distinct placement lights, {@link WorldStreamObjectsFile.Lights#FLOATS} floats each (see
     * {@link WorldStreamObjectsFile.Lights}).
     */
    final float[] lights;

    /**
     * Per model: the traversal of its portal set, or null without one (#383 step 3a). Built here on
     * the decoder worker from the validated tables; the renderer does not cull with it yet.
     */
    private final WmoPortalTraversal[] portals;

    private final int portalSets;

    /**
     * Per image: its identity for sharing one texture across object sets ({@link
     * WorldStreamObjectTextures.Key}), computed here on the decoder worker.
     */
    private final WorldStreamObjectTextures.Key[] keys;

    /**
     * Per model: its idle animation (#384), or null for a static model. Parsed and checked here on
     * the decoder worker; an entry that does not fit its model is dropped (counted in {@link
     * #refusedAnimations}) and the model stays static.
     */
    final ObjectAnimation[] animations;

    /** Animation entries dropped because they did not fit their model. */
    final int refusedAnimations;

    /**
     * One animated model's child-side data (#384): its idle animation, the rest vertex each of its
     * vertices (all its meshes, in order) is skinned from, and those vertices in the GPU layout
     * ({@link #GPU_VERTEX_BYTES} each), whose texture coordinates and colours a re-skin keeps.
     */
    static final class ObjectAnimation {
        final com.vibeminer.rendering.models.PreviewAnimation animation;
        final int[] sources;
        final byte[] vertices;

        /** Per mesh of the model (in order): its first vertex within {@link #vertices}. */
        final int[] meshOffsets;

        ObjectAnimation(
                com.vibeminer.rendering.models.PreviewAnimation animation,
                int[] sources,
                byte[] vertices,
                int[] meshOffsets) {
            this.animation = animation;
            this.sources = sources;
            this.vertices = vertices;
            this.meshOffsets = meshOffsets;
        }

        int vertexCount() {
            return sources.length;
        }

        /** Heap bytes this entry holds besides its animation's own arrays. */
        long bytes() {
            return sources.length * 4L + vertices.length;
        }
    }

    ByteBuffer vertices, indices, pixels;

    /** The set's building water, built here on the decoder worker ({@link WorldObjectWater}). */
    WorldObjectWater water = WorldObjectWater.NONE;

    private WorldStreamPackedObjects(
            WorldStreamObjectsFile.Data data,
            int[] images,
            WorldStreamObjectTextures.Key[] keys,
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
        this.keys = keys;
        this.decodedImages = decodedImages;
        this.compressedImages = compressedImages;
        placements = data.placements.clone();
        spheres =
                WorldStreamObjectCulling.placementSpheres(
                        placementModels,
                        placements,
                        WorldStreamObjectCulling.modelSpheres(
                                models, meshes, ranges, data.vertices, data.indices, canceled));
        rangeCentres =
                WorldStreamObjectCulling.rangeCentres(
                        meshes, ranges, data.vertices, data.indices, canceled);
        draws = data.draws;
        meshGroups = data.meshGroups.clone();
        placementParents = data.placementParents.clone();
        placementGroups = data.placementGroups.clone();
        placementLights = new int[data.placementCount()];
        java.util.Arrays.fill(placementLights, -1);
        var distinct = new java.util.LinkedHashMap<java.util.List<Float>, Integer>();
        int floats = WorldStreamObjectsFile.Lights.FLOATS;
        for (int i = 0; i < data.lights.count(); i++) {
            var key = new java.util.ArrayList<Float>(floats);
            for (int f = 0; f < floats; f++) {
                key.add(data.lights.values[i * floats + f]);
            }
            Integer index = distinct.get(key);
            if (index == null) {
                index = distinct.size();
                distinct.put(key, index);
            }
            placementLights[data.lights.placements[i]] = index;
        }
        lights = new float[distinct.size() * floats];
        int at = 0;
        for (var key : distinct.keySet()) {
            for (float v : key) {
                lights[at++] = v;
            }
        }
        animations = new ObjectAnimation[data.modelCount()];
        int refused = 0;
        for (int i = 0; i < data.animations.count(); i++) {
            check(canceled);
            int model = data.animations.models[i];
            var parsed =
                    animation(
                            data, model, data.animations.sources[i], data.animations.animations[i]);
            if (parsed == null) {
                refused++;
            } else {
                animations[model] = parsed;
            }
        }
        refusedAnimations = refused;
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

    /**
     * One animation entry as the child uses it, or null when it does not fit its model: an
     * unreadable or camera-facing animation, or a source outside its rest surface.
     */
    static ObjectAnimation animation(
            WorldStreamObjectsFile.Data data, int model, int[] sources, byte[] bytes) {
        var animation = WorldObjectAnimation.parse(bytes);
        if (animation == null) {
            return null;
        }
        if (animation.viewDependent() || animation.durationMillis() <= 0) {
            return null;
        }
        for (int source : sources) {
            if (source >= animation.vertexCount()) {
                return null;
            }
        }
        int firstMesh = data.models[model * 2], meshCount = data.models[model * 2 + 1];
        var offsets = new int[meshCount];
        var out =
                ByteBuffer.allocate(sources.length * GPU_VERTEX_BYTES)
                        .order(ByteOrder.nativeOrder());
        int at = 0;
        int stride = WorldStreamObjectsFile.VERTEX_FLOATS;
        for (int m = 0; m < meshCount; m++) {
            int mesh = firstMesh + m,
                    first = data.meshes[mesh * 6],
                    count = data.meshes[mesh * 6 + 1];
            offsets[m] = at;
            for (int v = first; v < first + count; v++, at++) {
                int to = at * GPU_VERTEX_BYTES;
                for (int f = 0; f < stride; f++) {
                    out.putFloat(to + f * 4, data.vertices[v * stride + f]);
                }
                int colour = data.colours[v];
                out.put(to + 32, (byte) colour);
                out.put(to + 33, (byte) (colour >>> 8));
                out.put(to + 34, (byte) (colour >>> 16));
                out.put(to + 35, (byte) 0);
            }
        }
        return new ObjectAnimation(animation, sources.clone(), out.array(), offsets);
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
            int vertexCount = data.vertexCount();
            vertices =
                    MemoryUtil.memAlloc(vertexCount * GPU_VERTEX_BYTES)
                            .order(ByteOrder.nativeOrder());
            interleave(data.vertices, data.colours, vertices, canceled);
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
            var keys = new WorldStreamObjectTextures.Key[count];
            var digest = sha256();
            int from = 0;
            for (int i = 0; i < count; i++) {
                check(canceled);
                int width = images[i * 3], height = images[i * 3 + 1], format = images[i * 3 + 2];
                int stored = ObjectTextureFormats.chainBytes(format, width, height);
                var id = identity(digest, format, width, height, data.pixels, from, stored);
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
                keys[i] =
                        new WorldStreamObjectTextures.Key(
                                images[i * 3 + 2], width, height, id[0], id[1]);
                from += stored;
            }
            pixels.flip();
            var result =
                    new WorldStreamPackedObjects(
                            data,
                            images,
                            keys,
                            vertices,
                            indices,
                            pixels,
                            decoded,
                            compressed,
                            canceled);
            vertices = indices = pixels = null;
            try {
                result.water = WorldObjectWater.build(data, canceled);
            } catch (RuntimeException failed) {
                result.close();
                throw failed;
            }
            return result;
        } finally {
            free(vertices);
            free(indices);
            free(pixels);
        }
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    /**
     * The first 128 bits of the SHA-256 of one stored chain: its stored format, size and bytes. The
     * upload format beside it in the key is a function of these (a decode is deterministic), so
     * equal keys upload equal texels.
     */
    static long[] identity(
            java.security.MessageDigest digest,
            int format,
            int width,
            int height,
            byte[] pixels,
            int from,
            int length) {
        digest.reset();
        var head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(format).putInt(width).putInt(height);
        digest.update(head.array());
        digest.update(pixels, from, length);
        var sum = ByteBuffer.wrap(digest.digest());
        return new long[] {sum.getLong(), sum.getLong()};
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

    /** Image {@code image}'s identity, shared by every set that uploads the same chain. */
    WorldStreamObjectTextures.Key key(int image) {
        return keys[image];
    }

    /** Full GPU bytes of image {@code image}'s chain as uploaded. */
    long imageBytes(int image) {
        return ObjectTextureFormats.chainBytes(format(image), width(image), height(image));
    }

    /** Native vertex and index bytes: the set's own GPU buffers. */
    long bufferBytes() {
        return (vertices == null ? 0 : vertices.capacity())
                + (long) (indices == null ? 0 : indices.capacity())
                + water.bytes();
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
        water.free();
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
