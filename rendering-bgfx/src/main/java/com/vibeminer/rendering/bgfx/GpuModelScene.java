package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable bounded upload snapshot; native allocations and GPU handles belong to its renderer. */
public final class GpuModelScene {
    public static final int VERTEX_FLOATS = 12;
    public static final int VERTEX_BYTES = VERTEX_FLOATS * Float.BYTES;

    /** Section ordinal selects materials; recorded part ID selects visibility. */
    public record Draw(
            int partOrdinal,
            int partId,
            int firstIndex,
            int indexCount,
            int textureIndex,
            boolean wrapU,
            boolean wrapV,
            boolean alphaKey,
            MeshAppearance.Material material) {
        public boolean additive(boolean texturesEnabled) {
            return texturesEnabled
                    && material != null
                    && material.blend() == MeshAppearance.Blend.ADDITIVE;
        }

        public boolean textured() {
            return textureIndex >= 0;
        }

        public boolean visible(Set<Integer> selectedParts, boolean qualifiedMaterialsOnly) {
            return visible(selectedParts) && (!qualifiedMaterialsOnly || textured());
        }

        public boolean visible(Set<Integer> selectedParts) {
            return selectedParts.isEmpty() || selectedParts.contains(partId);
        }
    }

    /** Straight RGBA8 bytes, with explicit channel order independent of the host endianness. */
    public static final class Texture {
        private final int width;
        private final int height;
        private final byte[] rgba8;

        private Texture(MeshAppearance.Texture source) {
            width = source.width();
            height = source.height();
            int[] pixels = source.argb();
            rgba8 = new byte[Math.multiplyExact(pixels.length, 4)];
            int offset = 0;
            for (int pixel : pixels) {
                rgba8[offset++] = (byte) (pixel >>> 16);
                rgba8[offset++] = (byte) (pixel >>> 8);
                rgba8[offset++] = (byte) pixel;
                rgba8[offset++] = (byte) (pixel >>> 24);
            }
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** Borrowed immutable storage; each call has an independent position and limit. */
        public ByteBuffer rgba8() {
            return ByteBuffer.wrap(rgba8).asReadOnlyBuffer();
        }
    }

    private final float[] vertices;
    private final int[] indices;
    private final float[] min;
    private final float[] max;
    private final List<Draw> draws;
    private final List<Texture> textures;
    private final boolean hasNormals;
    private final long uploadBytes;

    private GpuModelScene(PreviewMesh mesh, MeshAppearance appearance) {
        if (appearance != null && appearance.mesh() != mesh) {
            throw new IllegalArgumentException("Appearance must belong to the uploaded mesh");
        }
        float[] positions = mesh.positions();
        float[] normals = appearance == null ? new float[0] : appearance.normals();
        float[] uv = appearance == null ? new float[0] : appearance.uv();
        hasNormals = normals.length != 0;
        vertices = new float[Math.multiplyExact(mesh.vertexCount(), VERTEX_FLOATS)];
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int target = vertex * VERTEX_FLOATS;
            System.arraycopy(positions, vertex * 3, vertices, target, 3);
            if (hasNormals) {
                System.arraycopy(normals, vertex * 3, vertices, target + 3, 3);
            }
            if (appearance != null) {
                System.arraycopy(uv, vertex * 2, vertices, target + 6, 2);
                int group = appearance.billboardGroup(vertex);
                if (group >= 0) {
                    var pivot = appearance.billboards().get(group).modelPivot();
                    vertices[target + 8] = pivot.x();
                    vertices[target + 9] = pivot.y();
                    vertices[target + 10] = pivot.z();
                    vertices[target + 11] = appearance.billboards().get(group).kind().code();
                }
            }
        }
        indices = mesh.indices();
        min =
                new float[] {
                    appearance == null ? mesh.minimum(0) : appearance.minimum(0),
                    appearance == null ? mesh.minimum(1) : appearance.minimum(1),
                    appearance == null ? mesh.minimum(2) : appearance.minimum(2)
                };
        max =
                new float[] {
                    appearance == null ? mesh.maximum(0) : appearance.maximum(0),
                    appearance == null ? mesh.maximum(1) : appearance.maximum(1),
                    appearance == null ? mesh.maximum(2) : appearance.maximum(2)
                };
        var surfaces = new HashMap<Integer, MeshAppearance.Surface>();
        if (appearance != null) {
            for (var surface : appearance.surfaces()) {
                surfaces.put(surface.sectionIndex(), surface);
            }
        }
        var commands = new ArrayList<Draw>(mesh.parts().size());
        for (int ordinal = 0; ordinal < mesh.parts().size(); ordinal++) {
            var part = mesh.parts().get(ordinal);
            var surface = surfaces.get(ordinal);
            commands.add(
                    new Draw(
                            ordinal,
                            part.id(),
                            part.firstIndex(),
                            part.indexCount(),
                            surface == null ? -1 : surface.textureIndex(),
                            surface != null && surface.wrapU(),
                            surface != null && surface.wrapV(),
                            surface != null && surface.alphaKey(),
                            surface == null ? null : surface.material()));
        }
        draws = List.copyOf(commands);
        var images = new ArrayList<Texture>();
        long bytes = (long) vertices.length * Float.BYTES + (long) indices.length * Integer.BYTES;
        if (appearance != null) {
            for (var source : appearance.textures()) {
                var texture = new Texture(source);
                images.add(texture);
                bytes = Math.addExact(bytes, texture.rgba8.length);
            }
        }
        textures = List.copyOf(images);
        uploadBytes = bytes;
    }

    public static GpuModelScene create(PreviewMesh mesh, MeshAppearance appearance) {
        return new GpuModelScene(Objects.requireNonNull(mesh), appearance);
    }

    /**
     * Shared vertices: native XYZ, optional native normal XYZ, UV0 and optional card pivot/marker;
     * 48-byte stride.
     */
    public FloatBuffer vertexData() {
        return FloatBuffer.wrap(vertices).asReadOnlyBuffer();
    }

    /** Original topology in an integer view; validated indices fit unsigned16 for native upload. */
    public IntBuffer indexData() {
        return IntBuffer.wrap(indices).asReadOnlyBuffer();
    }

    public int vertexCount() {
        return vertices.length / VERTEX_FLOATS;
    }

    public int indexCount() {
        return indices.length;
    }

    public boolean hasNormals() {
        return hasNormals;
    }

    public float minimum(int axis) {
        return min[axis];
    }

    public float maximum(int axis) {
        return max[axis];
    }

    public List<Draw> draws() {
        return draws;
    }

    public List<Texture> textures() {
        return textures;
    }

    /**
     * Immutable scene payload bytes; derived compact index/edge buffers and driver storage are
     * separate.
     */
    public long uploadBytes() {
        return uploadBytes;
    }
}
