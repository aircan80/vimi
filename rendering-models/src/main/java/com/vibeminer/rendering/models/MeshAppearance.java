package com.vibeminer.rendering.models;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded diffuse texture bindings for a static surface; no animation or full shader claim. */
public final class MeshAppearance {
    public enum Coordinate {
        UV0(1),
        ENV_SPHERE(2);
        private final int code;

        Coordinate(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public enum Combiner {
        DIFFUSE(1),
        OPAQUE_MOD2X_NA(2);
        private final int code;

        Combiner(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public enum Lighting {
        LIT(1),
        UNLIT(2);
        private final int code;

        Lighting(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    /**
     * How a surface combines with what is behind it. {@link #BLENDED} (source-alpha over) is
     * produced only by the World's creature binding and drawn only by the World; the model preview
     * renderers have no blended pass.
     */
    public enum Blend {
        OPAQUE(1),
        ALPHA_KEY(2),
        ADDITIVE(3),
        BLENDED(4);
        private final int code;

        Blend(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    public record TextureStage(
            int textureIndex, Coordinate coordinate, boolean wrapU, boolean wrapV) {
        public TextureStage {
            if (textureIndex < 0 || textureIndex >= 16 || coordinate == null) {
                throw new IllegalArgumentException("Invalid texture stage");
            }
        }
    }

    public record Material(
            Combiner combiner, List<TextureStage> stages, Lighting lighting, Blend blend) {
        public Material {
            stages = List.copyOf(stages);
            boolean diffuse =
                    combiner == Combiner.DIFFUSE
                            && stages.size() == 1
                            && stages.getFirst().coordinate() == Coordinate.UV0
                            && (lighting == Lighting.LIT
                                            && (blend == Blend.OPAQUE
                                                    || blend == Blend.ALPHA_KEY
                                                    || blend == Blend.BLENDED)
                                    || lighting == Lighting.UNLIT
                                            && (blend == Blend.OPAQUE
                                                    || blend == Blend.ADDITIVE
                                                    || blend == Blend.BLENDED));
            boolean environment =
                    combiner == Combiner.OPAQUE_MOD2X_NA
                            && stages.size() == 2
                            && stages.get(0).coordinate() == Coordinate.UV0
                            && stages.get(1).coordinate() == Coordinate.ENV_SPHERE
                            && lighting == Lighting.LIT
                            && blend == Blend.OPAQUE;
            if (!diffuse && !environment) {
                throw new IllegalArgumentException("Unsupported material combination");
            }
        }
    }

    public record Surface(int sectionIndex, Material material) {
        public Surface {
            if (sectionIndex < 0 || sectionIndex >= PreviewMesh.MAX_PARTS || material == null) {
                throw new IllegalArgumentException("Invalid material surface");
            }
        }

        public Surface(
                int sectionIndex,
                int textureIndex,
                boolean wrapU,
                boolean wrapV,
                boolean alphaKey) {
            this(
                    sectionIndex,
                    new Material(
                            Combiner.DIFFUSE,
                            List.of(new TextureStage(textureIndex, Coordinate.UV0, wrapU, wrapV)),
                            Lighting.LIT,
                            alphaKey ? Blend.ALPHA_KEY : Blend.OPAQUE));
        }

        public int textureIndex() {
            return material.stages().getFirst().textureIndex();
        }

        public boolean wrapU() {
            return material.stages().getFirst().wrapU();
        }

        public boolean wrapV() {
            return material.stages().getFirst().wrapV();
        }

        public boolean alphaKey() {
            return material.blend() == Blend.ALPHA_KEY;
        }
    }

    public record Vec3(float x, float y, float z) {
        public Vec3 {
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(z)
                    || Math.abs(x) > 1_000_000
                    || Math.abs(y) > 1_000_000
                    || Math.abs(z) > 1_000_000) {
                throw new IllegalArgumentException("Invalid model pivot");
            }
        }
    }

    /**
     * How a rigid card turns toward the camera: fully ({@code SPHERICAL}, bone flag 0x8), or only
     * about the model's Z axis ({@code CYLINDRICAL_Z}, flag 0x40) or Y axis ({@code CYLINDRICAL_Y},
     * flag 0x20), which stays fixed.
     */
    public enum CardKind {
        SPHERICAL(1),
        CYLINDRICAL_Z(2),
        CYLINDRICAL_Y(3);

        private final int code;

        CardKind(int code) {
            this.code = code;
        }

        /** The renderer's card attribute value, and the codec's stored kind. */
        public int code() {
            return code;
        }

        public static CardKind of(int code) {
            for (var kind : values()) {
                if (kind.code == code) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown card kind " + code);
        }
    }

    public record RigidBillboard(int boneId, Vec3 modelPivot, int[] vertexIndices, CardKind kind) {
        public RigidBillboard(int boneId, Vec3 modelPivot, int[] vertexIndices) {
            this(boneId, modelPivot, vertexIndices, CardKind.SPHERICAL);
        }

        public RigidBillboard {
            if (boneId < 0
                    || boneId > 65535
                    || kind == null
                    || modelPivot == null
                    || vertexIndices.length < 1
                    || vertexIndices.length > 8) {
                throw new IllegalArgumentException("Invalid rigid billboard group");
            }
            vertexIndices = vertexIndices.clone();
            for (int i = 0; i < vertexIndices.length; i++) {
                if (vertexIndices[i] < 0 || i > 0 && vertexIndices[i] <= vertexIndices[i - 1]) {
                    throw new IllegalArgumentException(
                            "Billboard members must be unique and sorted");
                }
            }
        }

        @Override
        public int[] vertexIndices() {
            return vertexIndices.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RigidBillboard b
                    && boneId == b.boneId
                    && kind == b.kind
                    && modelPivot.equals(b.modelPivot)
                    && java.util.Arrays.equals(vertexIndices, b.vertexIndices);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * boneId + kind.hashCode()) + modelPivot.hashCode())
                    + java.util.Arrays.hashCode(vertexIndices);
        }
    }

    public static final class Texture {
        private final int width, height;
        final int[] pixels;

        /** A texture over a private copy of {@code argb}. */
        public Texture(int width, int height, int[] argb) {
            this(argb.clone(), width, height);
        }

        private Texture(int[] owned, int width, int height) {
            if (width < 1
                    || height < 1
                    || width > 2048
                    || height > 2048
                    || (long) width * height != owned.length) {
                throw new IllegalArgumentException("Preview texture exceeds bounds");
            }
            this.width = width;
            this.height = height;
            pixels = owned;
        }

        /**
         * A texture that takes ownership of {@code argb} without copying it (#361): for callers
         * that have just built the array and never touch it again, such as a decoder or a cache
         * reader.
         */
        public static Texture adopt(int width, int height, int[] argb) {
            return new Texture(java.util.Objects.requireNonNull(argb), width, height);
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int[] argb() {
            return pixels.clone();
        }

        /** The pixels as a read-only view, without the copy {@link #argb()} makes. */
        public java.nio.IntBuffer argbView() {
            return java.nio.IntBuffer.wrap(pixels).asReadOnlyBuffer();
        }
    }

    private final PreviewMesh mesh;
    final float[] uv;
    final float[] normals;
    final Map<Integer, Surface> surfaces;
    final List<Texture> textures;
    final List<RigidBillboard> billboards;
    final int[] billboardGroups;
    private final float[] minimum = new float[3], maximum = new float[3];

    public MeshAppearance(
            PreviewMesh mesh, float[] uv, List<Surface> surfaces, List<Texture> textures) {
        this(mesh, uv, new float[0], surfaces, textures);
    }

    public MeshAppearance(
            PreviewMesh mesh,
            float[] uv,
            float[] normals,
            List<Surface> surfaces,
            List<Texture> textures) {
        this(mesh, uv, normals, surfaces, textures, List.of());
    }

    public MeshAppearance(
            PreviewMesh mesh,
            float[] uv,
            float[] normals,
            List<Surface> surfaces,
            List<Texture> textures,
            List<RigidBillboard> billboards) {
        if (uv.length != mesh.vertexCount() * 2
                || surfaces.size() > mesh.parts().size()
                || textures.size() > 16) {
            throw new IllegalArgumentException("Invalid static texture binding bounds");
        }
        if (normals.length != 0 && normals.length != mesh.vertexCount() * 3) {
            throw new IllegalArgumentException("Invalid static normal bounds");
        }
        for (float coordinate : normals) {
            if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 1_000_000) {
                throw new IllegalArgumentException("Invalid static normal");
            }
        }
        long pixels = 0;
        for (var texture : textures) {
            pixels += texture.pixels.length;
        }
        if (pixels > 8_388_608) {
            throw new IllegalArgumentException("Preview texture memory exceeds bounds");
        }
        for (float coordinate : uv) {
            if (!Float.isFinite(coordinate) || Math.abs(coordinate) > 1_000_000) {
                throw new IllegalArgumentException("Invalid texture coordinate");
            }
        }
        var mapped = new HashMap<Integer, Surface>();
        for (var surface : surfaces) {
            if (surface.sectionIndex() < 0
                    || surface.sectionIndex() >= mesh.parts().size()
                    || surface.material().stages().stream()
                            .anyMatch(stage -> stage.textureIndex() >= textures.size())
                    || mapped.putIfAbsent(surface.sectionIndex(), surface) != null) {
                throw new IllegalArgumentException("Invalid or ambiguous static texture binding");
            }
        }
        if (billboards.size() > 128) {
            throw new IllegalArgumentException("Too many billboard groups");
        }
        int[] membership = new int[mesh.vertexCount()];
        java.util.Arrays.fill(membership, -1);
        var bones = new java.util.HashSet<Integer>();
        for (int group = 0; group < billboards.size(); group++) {
            var card = billboards.get(group);
            if (!bones.add(card.boneId())) {
                throw new IllegalArgumentException("Duplicate billboard bone");
            }
            for (int vertex : card.vertexIndices) {
                if (vertex >= mesh.vertexCount() || membership[vertex] != -1) {
                    throw new IllegalArgumentException("Invalid or shared billboard member");
                }
                membership[vertex] = group;
            }
        }
        int[] cardTriangles = new int[billboards.size()];
        boolean[] consumed = new boolean[mesh.vertexCount()];
        long traversed = 0;
        for (int ordinal = 0; ordinal < mesh.parts().size(); ordinal++) {
            var part = mesh.parts().get(ordinal);
            var binding = mapped.get(ordinal);
            boolean env =
                    binding != null && binding.material().combiner() == Combiner.OPAQUE_MOD2X_NA;
            if (billboards.isEmpty() && !env) {
                continue;
            }
            traversed += part.indexCount();
            if (traversed > PreviewMesh.MAX_INDICES) {
                throw new IllegalArgumentException("Surface traversal exceeds mesh index budget");
            }
            for (int at = part.firstIndex(); at < part.firstIndex() + part.indexCount(); at += 3) {
                int a = mesh.indices[at], b = mesh.indices[at + 1], c = mesh.indices[at + 2];
                if (env) {
                    for (int vertex : new int[] {a, b, c}) {
                        if (normals.length == 0
                                || (double) normals[vertex * 3] * normals[vertex * 3]
                                                + (double) normals[vertex * 3 + 1]
                                                        * normals[vertex * 3 + 1]
                                                + (double) normals[vertex * 3 + 2]
                                                        * normals[vertex * 3 + 2]
                                        <= 1e-12) {
                            throw new IllegalArgumentException("Consumed ENV normal is unusable");
                        }
                    }
                }
                int group =
                        membership[a] >= 0
                                ? membership[a]
                                : membership[b] >= 0 ? membership[b] : membership[c];
                if (group < 0) {
                    continue;
                }
                if (membership[a] != group
                        || membership[b] != group
                        || membership[c] != group
                        || binding == null
                        || binding.material().lighting() != Lighting.UNLIT
                        || binding.material().blend() != Blend.ADDITIVE
                        || ++cardTriangles[group] > 8) {
                    throw new IllegalArgumentException(
                            "Billboard triangles require one unlit additive group");
                }
                consumed[a] = consumed[b] = consumed[c] = true;
            }
        }
        for (var card : billboards) {
            for (int vertex : card.vertexIndices) {
                if (!consumed[vertex]) {
                    throw new IllegalArgumentException("Unused billboard member");
                }
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            minimum[axis] = mesh.minimum(axis);
            maximum[axis] = mesh.maximum(axis);
        }
        for (var card : billboards) {
            double radius = 0;
            for (int vertex : card.vertexIndices) {
                double x = mesh.positions[vertex * 3] - (double) card.modelPivot().x();
                double y = mesh.positions[vertex * 3 + 1] - (double) card.modelPivot().y();
                double z = mesh.positions[vertex * 3 + 2] - (double) card.modelPivot().z();
                radius = Math.max(radius, Math.sqrt(x * x + y * y + z * z));
            }
            float[] pivot = {card.modelPivot().x(), card.modelPivot().y(), card.modelPivot().z()};
            for (int axis = 0; axis < 3; axis++) {
                minimum[axis] =
                        Math.min(minimum[axis], Math.nextDown((float) (pivot[axis] - radius)));
                maximum[axis] =
                        Math.max(maximum[axis], Math.nextUp((float) (pivot[axis] + radius)));
            }
        }
        this.billboards = List.copyOf(billboards);
        this.billboardGroups = membership;
        this.mesh = mesh;
        this.uv = uv.clone();
        this.normals = normals.clone();
        this.surfaces = Map.copyOf(mapped);
        this.textures = List.copyOf(textures);
    }

    public List<RigidBillboard> billboards() {
        return billboards;
    }

    public int billboardGroup(int vertex) {
        return billboardGroups[vertex];
    }

    public float minimum(int axis) {
        return minimum[axis];
    }

    public float maximum(int axis) {
        return maximum[axis];
    }

    public PreviewMesh mesh() {
        return mesh;
    }

    public float[] uv() {
        return uv.clone();
    }

    public float[] normals() {
        return normals.clone();
    }

    public List<Surface> surfaces() {
        return surfaces.values().stream()
                .sorted(java.util.Comparator.comparingInt(Surface::sectionIndex))
                .toList();
    }

    public List<Texture> textures() {
        return textures;
    }
}
