package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.PreviewAnimation;
import com.vibeminer.rendering.world.ObjectTextureMips;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The walking body's file ({@code body-1.wsb}): one model as the World draws a creature (eight
 * floats per vertex: position, normal and texture coordinate in model space, Z up and +X forward;
 * indices; first index, index count and image per range; one material class per range; RGBA8 images
 * as full mip chains), the rest vertex each drawn vertex is skinned from, the body's scale and its
 * animations by AnimationData ID ({@link PreviewAnimation#write}). Pure; no bgfx calls.
 *
 * <p>Every count is bounded before anything is allocated, and every reference is checked: indices
 * name drawn vertices, ranges name images and stay inside the indices, and every animation skins
 * the same rest surface, which the source vertices name.
 */
public final class WorldStreamBodyFile {
    static final int MAGIC = 0x56574231; // "VWB1"
    static final int MIN_BYTES = 16;
    public static final int MAX_BYTES = 64 << 20;
    public static final int MAX_VERTICES = 65_535, MAX_INDICES = 196_608, MAX_RANGES = 256;
    public static final int MAX_IMAGES = 64, MAX_IMAGE_SIDE = 512, MAX_ANIMATIONS = 16;
    public static final int VERTEX_FLOATS = 8;

    /** One RGBA8 image with power-of-two sides as its full packed mip chain. */
    public record Image(int width, int height, byte[] rgba) {
        public Image {
            if (!side(width)
                    || !side(height)
                    || rgba == null
                    || rgba.length != (long) ObjectTextureMips.chainPixels(width, height) * 4) {
                throw new IllegalArgumentException("Invalid body image");
            }
        }
    }

    /** One body; see the class comment. */
    public record Body(
            float[] vertices,
            int[] indices,
            int[] ranges,
            int[] blends,
            List<Image> images,
            int[] sources,
            float scale,
            int[] sequences,
            List<PreviewAnimation> animations) {
        public Body {
            images = List.copyOf(images);
            animations = List.copyOf(animations);
            int vertexCount = vertices.length / VERTEX_FLOATS;
            if (vertices.length % VERTEX_FLOATS != 0
                    || vertexCount < 3
                    || vertexCount > MAX_VERTICES
                    || sources.length != vertexCount
                    || indices.length < 3
                    || indices.length % 3 != 0
                    || indices.length > MAX_INDICES
                    || ranges.length != blends.length * 3
                    || blends.length < 1
                    || blends.length > MAX_RANGES
                    || images.size() > MAX_IMAGES
                    || sequences.length != animations.size()
                    || animations.isEmpty()
                    || animations.size() > MAX_ANIMATIONS
                    || !(scale > 0 && scale < 1000)) {
                throw new IllegalArgumentException("Body counts outside bounds");
            }
            for (float v : vertices) {
                if (!Float.isFinite(v) || Math.abs(v) > 1_000_000) {
                    throw new IllegalArgumentException("Invalid body vertex");
                }
            }
            for (int index : indices) {
                if (index < 0 || index >= vertexCount) {
                    throw new IllegalArgumentException("Body index outside its vertices");
                }
            }
            for (int r = 0; r < blends.length; r++) {
                int first = ranges[r * 3], count = ranges[r * 3 + 1], image = ranges[r * 3 + 2];
                if (first < 0
                        || count < 3
                        || count % 3 != 0
                        || first % 3 != 0
                        || (long) first + count > indices.length
                        || image < 0
                        || image >= images.size()
                        || blends[r] < 0
                        || WorldStreamObjectsFile.kind(blends[r])
                                > WorldStreamObjectsFile.ADDITIVE) {
                    throw new IllegalArgumentException("Invalid body range");
                }
            }
            int rest = animations.getFirst().vertexCount();
            for (int i = 0; i < animations.size(); i++) {
                var animation = animations.get(i);
                if (animation.vertexCount() != rest || animation.viewDependent()) {
                    throw new IllegalArgumentException("Body animations skin different surfaces");
                }
                if (sequences[i] < 0 || sequences[i] > 0xffff) {
                    throw new IllegalArgumentException("Invalid body sequence");
                }
                for (int j = 0; j < i; j++) {
                    if (sequences[j] == sequences[i]) {
                        throw new IllegalArgumentException("Repeated body sequence");
                    }
                }
            }
            for (int source : sources) {
                if (source < 0 || source >= rest) {
                    throw new IllegalArgumentException("Body source vertex outside the surface");
                }
            }
            vertices = vertices.clone();
            indices = indices.clone();
            ranges = ranges.clone();
            blends = blends.clone();
            sources = sources.clone();
            sequences = sequences.clone();
        }

        public int vertexCount() {
            return vertices.length / VERTEX_FLOATS;
        }

        /** The animation of AnimationData ID {@code sequence}, or null. */
        public PreviewAnimation animation(int sequence) {
            for (int i = 0; i < sequences.length; i++) {
                if (sequences[i] == sequence) {
                    return animations.get(i);
                }
            }
            return null;
        }
    }

    private WorldStreamBodyFile() {}

    private static boolean side(int value) {
        return value >= 1 && value <= MAX_IMAGE_SIDE && Integer.bitCount(value) == 1;
    }

    public static byte[] encode(Body body) throws IOException {
        Objects.requireNonNull(body);
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(body.vertices().length / VERTEX_FLOATS);
        for (float v : body.vertices()) {
            out.writeFloat(v);
        }
        out.writeInt(body.indices().length);
        for (int i : body.indices()) {
            out.writeShort(i);
        }
        out.writeInt(body.blends().length);
        for (int i = 0; i < body.blends().length; i++) {
            out.writeInt(body.ranges()[i * 3]);
            out.writeInt(body.ranges()[i * 3 + 1]);
            out.writeShort(body.ranges()[i * 3 + 2]);
            out.writeShort(body.blends()[i]);
        }
        out.writeShort(body.images().size());
        for (var image : body.images()) {
            out.writeShort(image.width());
            out.writeShort(image.height());
            out.write(image.rgba());
        }
        for (int source : body.sources()) {
            out.writeInt(source);
        }
        out.writeFloat(body.scale());
        out.writeShort(body.animations().size());
        for (int i = 0; i < body.animations().size(); i++) {
            out.writeShort(body.sequences()[i]);
            var animation = new ByteArrayOutputStream();
            body.animations().get(i).write(new DataOutputStream(animation));
            out.writeInt(animation.size());
            animation.writeTo(out);
        }
        out.flush();
        if (bytes.size() > MAX_BYTES) {
            throw new IOException("Body file exceeds its bound");
        }
        return bytes.toByteArray();
    }

    /** Reads a file {@link #encode} wrote; anything else is refused with an IOException. */
    public static Body decode(byte[] bytes) throws IOException {
        if (bytes.length < MIN_BYTES || bytes.length > MAX_BYTES) {
            throw new IOException("Body file size outside its bounds");
        }
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a body file of this version");
            }
            int vertexCount = bounded(in.readInt(), 3, MAX_VERTICES);
            float[] vertices = new float[vertexCount * VERTEX_FLOATS];
            for (int i = 0; i < vertices.length; i++) {
                vertices[i] = in.readFloat();
            }
            int[] indices = new int[bounded(in.readInt(), 3, MAX_INDICES)];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = in.readUnsignedShort();
            }
            int rangeCount = bounded(in.readInt(), 1, MAX_RANGES);
            int[] ranges = new int[rangeCount * 3], blends = new int[rangeCount];
            for (int i = 0; i < rangeCount; i++) {
                ranges[i * 3] = in.readInt();
                ranges[i * 3 + 1] = in.readInt();
                ranges[i * 3 + 2] = in.readUnsignedShort();
                blends[i] = in.readUnsignedShort();
            }
            int imageCount = bounded(in.readUnsignedShort(), 0, MAX_IMAGES);
            var images = new ArrayList<Image>(imageCount);
            for (int i = 0; i < imageCount; i++) {
                int width = in.readUnsignedShort(), height = in.readUnsignedShort();
                if (!side(width) || !side(height)) {
                    throw new IOException("Invalid body image size");
                }
                byte[] rgba = new byte[ObjectTextureMips.chainPixels(width, height) * 4];
                in.readFully(rgba);
                images.add(new Image(width, height, rgba));
            }
            int[] sources = new int[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                sources[i] = in.readInt();
            }
            float scale = in.readFloat();
            int animationCount = bounded(in.readUnsignedShort(), 1, MAX_ANIMATIONS);
            int[] sequences = new int[animationCount];
            var animations = new ArrayList<PreviewAnimation>(animationCount);
            for (int i = 0; i < animationCount; i++) {
                sequences[i] = in.readUnsignedShort();
                int length = bounded(in.readInt(), 1, in.available());
                byte[] blob = new byte[length];
                in.readFully(blob);
                var blobIn = new DataInputStream(new ByteArrayInputStream(blob));
                animations.add(PreviewAnimation.read(blobIn));
                if (blobIn.available() != 0) {
                    throw new IOException("Body animation record has trailing bytes");
                }
            }
            if (in.available() != 0) {
                throw new IOException("Body file has trailing bytes");
            }
            return new Body(
                    vertices,
                    indices,
                    ranges,
                    blends,
                    images,
                    sources,
                    scale,
                    sequences,
                    animations);
        } catch (java.io.EOFException truncated) {
            throw new IOException("Truncated body file", truncated);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid body file: " + invalid.getMessage(), invalid);
        }
    }

    private static int bounded(int value, int minimum, int maximum) throws IOException {
        if (value < minimum || value > maximum) {
            throw new IOException("Body record outside its bounds");
        }
        return value;
    }
}
