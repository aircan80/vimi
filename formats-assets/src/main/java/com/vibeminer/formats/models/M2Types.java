package com.vibeminer.formats.models;

import java.util.List;

/** Immutable native static structures. These values do not assert acquired appearance or pose. */
public final class M2Types {
    private M2Types() {}

    public record Limits(
            long maxSourceBytes,
            long maxOutputBytes,
            int maxVertices,
            int maxVertexLookup,
            int maxTriangleIndices,
            int maxSections,
            int maxMaterials,
            int maxBatches,
            int maxTextures,
            int maxSkinProfiles,
            int maxChunks,
            int maxNameBytes,
            int maxMetadataEntries) {
        public Limits() {
            this(
                    64L << 20,
                    128L << 20,
                    1_000_000,
                    65_536,
                    3_000_000,
                    256,
                    256,
                    512,
                    32,
                    32,
                    128,
                    4096,
                    65_536);
        }
    }

    public record Vec2(float x, float y) {}

    public record Vec3(float x, float y, float z) {}

    public record Vertex(
            Vec3 position, long boneWeights, long boneIndices, Vec3 normal, Vec2 uv0, Vec2 uv1) {}

    public record Bounds(Vec3 minimum, Vec3 maximum, float radius) {}

    public record ArrayDeclaration(String name, long count, long offset, int stride) {}

    public record Chunk(String id, long offset, long byteLength) {}

    public record Texture(int ordinal, long type, long flags, String filename, Long fileDataId) {}

    public record Material(int flags, int blendMode) {}

    public record Bone(int keyBoneId, long flags, int parent, int submeshId, Vec3 pivot) {}

    public record AnimationFile(int animationId, int variation, long fileDataId) {}

    public static final class Model {
        private final long version;
        private final boolean isChunked;
        private final long flags;
        private final String name;
        private final long sourceBytes;
        private final long baseOffset;
        private final long baseBytes;
        private final long viewCount;
        private final List<Vertex> vertices;
        private final List<Texture> textures;
        private final List<Material> materials;
        private final List<Bone> bones;
        private final List<Integer> textureCombos;
        private final List<Integer> textureCoordinates;
        private final List<Integer> textureWeights;
        private final List<Integer> textureTransforms;
        private final List<Integer> boneLookup;
        private final List<Long> skinFileDataIds;
        private final List<Long> lodSkinFileDataIds;
        private final long skeletonFileDataId;
        private final List<Long> boneFileDataIds;
        private final List<AnimationFile> animationFiles;
        private final List<ArrayDeclaration> declarations;
        private final List<Chunk> chunks;
        private final Bounds declaredBounds;
        private final Bounds computedBounds;
        private final long retainedBytes;
        private final Limits limits;

        Model(
                long version,
                boolean isChunked,
                long flags,
                String name,
                long sourceBytes,
                long baseOffset,
                long baseBytes,
                long viewCount,
                List<Vertex> vertices,
                List<Texture> textures,
                List<Material> materials,
                List<Bone> bones,
                List<Integer> textureCombos,
                List<Integer> textureCoordinates,
                List<Integer> textureWeights,
                List<Integer> textureTransforms,
                List<Integer> boneLookup,
                List<Long> skinFileDataIds,
                List<Long> lodSkinFileDataIds,
                long skeletonFileDataId,
                List<Long> boneFileDataIds,
                List<AnimationFile> animationFiles,
                List<ArrayDeclaration> declarations,
                List<Chunk> chunks,
                Bounds declaredBounds,
                Bounds computedBounds,
                long retainedBytes,
                Limits limits) {
            this.version = version;
            this.isChunked = isChunked;
            this.flags = flags;
            this.name = name;
            this.sourceBytes = sourceBytes;
            this.baseOffset = baseOffset;
            this.baseBytes = baseBytes;
            this.viewCount = viewCount;
            this.vertices = List.copyOf(vertices);
            this.textures = List.copyOf(textures);
            this.materials = List.copyOf(materials);
            this.bones = List.copyOf(bones);
            this.textureCombos = List.copyOf(textureCombos);
            this.textureCoordinates = List.copyOf(textureCoordinates);
            this.textureWeights = List.copyOf(textureWeights);
            this.textureTransforms = List.copyOf(textureTransforms);
            this.boneLookup = List.copyOf(boneLookup);
            this.skinFileDataIds = List.copyOf(skinFileDataIds);
            this.lodSkinFileDataIds = List.copyOf(lodSkinFileDataIds);
            this.skeletonFileDataId = skeletonFileDataId;
            this.boneFileDataIds = List.copyOf(boneFileDataIds);
            this.animationFiles = List.copyOf(animationFiles);
            this.declarations = List.copyOf(declarations);
            this.chunks = List.copyOf(chunks);
            this.declaredBounds = declaredBounds;
            this.computedBounds = computedBounds;
            this.retainedBytes = retainedBytes;
            this.limits = limits;
        }

        public long version() {
            return version;
        }

        public boolean isChunked() {
            return isChunked;
        }

        public long flags() {
            return flags;
        }

        public String name() {
            return name;
        }

        public long sourceBytes() {
            return sourceBytes;
        }

        public long baseOffset() {
            return baseOffset;
        }

        public long baseBytes() {
            return baseBytes;
        }

        public long viewCount() {
            return viewCount;
        }

        public List<Vertex> vertices() {
            return vertices;
        }

        public List<Texture> textures() {
            return textures;
        }

        public List<Material> materials() {
            return materials;
        }

        public List<Bone> bones() {
            return bones;
        }

        public List<Integer> textureCombos() {
            return textureCombos;
        }

        public List<Integer> textureCoordinates() {
            return textureCoordinates;
        }

        public List<Integer> textureWeights() {
            return textureWeights;
        }

        public List<Integer> textureTransforms() {
            return textureTransforms;
        }

        public List<Integer> boneLookup() {
            return boneLookup;
        }

        public List<Long> skinFileDataIds() {
            return skinFileDataIds;
        }

        public List<Long> lodSkinFileDataIds() {
            return lodSkinFileDataIds;
        }

        public long skeletonFileDataId() {
            return skeletonFileDataId;
        }

        public List<Long> boneFileDataIds() {
            return boneFileDataIds;
        }

        public List<AnimationFile> animationFiles() {
            return animationFiles;
        }

        public List<ArrayDeclaration> declarations() {
            return declarations;
        }

        public List<Chunk> chunks() {
            return chunks;
        }

        public Bounds declaredBounds() {
            return declaredBounds;
        }

        public Bounds computedBounds() {
            return computedBounds;
        }

        public long retainedBytes() {
            return retainedBytes;
        }

        public Limits limits() {
            return limits;
        }
    }

    /**
     * Position/topology proof only; bone palettes, materials, visibility and pose are not
     * evaluated.
     */
    public record RestSkin(
            Model model,
            List<Integer> vertexLookup,
            List<Integer> triangleLookup,
            List<Section> sections) {
        public RestSkin {
            vertexLookup = List.copyOf(vertexLookup);
            triangleLookup = List.copyOf(triangleLookup);
            sections = List.copyOf(sections);
        }
    }

    /**
     * Validated raw draw declarations for native rest surfaces; no rig, tracks or shaders
     * evaluated.
     */
    public record RestDrawSkin(RestSkin surface, List<Batch> batches) {
        public RestDrawSkin {
            batches = List.copyOf(batches);
        }
    }

    public record Section(
            int id,
            int level,
            int vertexStart,
            int vertexCount,
            long triangleStart,
            int triangleCount,
            int boneCount,
            int boneStart,
            int boneInfluences,
            int centerBoneIndex,
            Vec3 center,
            Vec3 sortCenter,
            float sortRadius) {}

    public record Batch(
            int flags,
            int priority,
            int shaderId,
            int sectionIndex,
            int flags2,
            int colorIndex,
            int materialIndex,
            int materialLayer,
            int textureCount,
            int textureComboIndex,
            int textureCoordinateComboIndex,
            int textureWeightComboIndex,
            int textureTransformComboIndex) {}

    public record ShadowBatch(
            int flags,
            int flags2,
            int unknown,
            int sectionIndex,
            int textureIndex,
            int colorIndex,
            int weightIndex) {}

    public static final class Skin {
        private final Model model;
        private final List<Integer> vertexLookup;
        private final List<Integer> triangleLookup;
        private final List<Long> boneProperties;
        private final List<Section> sections;
        private final List<Batch> batches;
        private final List<ShadowBatch> shadowBatches;
        private final long maximumBones;
        private final long sourceBytes;
        private final long retainedBytes;
        private final Limits limits;

        Skin(
                Model model,
                List<Integer> vertexLookup,
                List<Integer> triangleLookup,
                List<Long> boneProperties,
                List<Section> sections,
                List<Batch> batches,
                List<ShadowBatch> shadowBatches,
                long maximumBones,
                long sourceBytes,
                long retainedBytes,
                Limits limits) {
            this.model = model;
            this.vertexLookup = List.copyOf(vertexLookup);
            this.triangleLookup = List.copyOf(triangleLookup);
            this.boneProperties = List.copyOf(boneProperties);
            this.sections = List.copyOf(sections);
            this.batches = List.copyOf(batches);
            this.shadowBatches = List.copyOf(shadowBatches);
            this.maximumBones = maximumBones;
            this.sourceBytes = sourceBytes;
            this.retainedBytes = retainedBytes;
            this.limits = limits;
        }

        public Model model() {
            return model;
        }

        public List<Integer> vertexLookup() {
            return vertexLookup;
        }

        public List<Integer> triangleLookup() {
            return triangleLookup;
        }

        public List<Long> boneProperties() {
            return boneProperties;
        }

        public List<Section> sections() {
            return sections;
        }

        public List<Batch> batches() {
            return batches;
        }

        public List<ShadowBatch> shadowBatches() {
            return shadowBatches;
        }

        public long maximumBones() {
            return maximumBones;
        }

        public long sourceBytes() {
            return sourceBytes;
        }

        public long retainedBytes() {
            return retainedBytes;
        }

        public Limits limits() {
            return limits;
        }
    }

    public enum GeometryScope {
        UNFILTERED_DECLARED_SECTIONS
    }

    public enum GeometryEvaluation {
        NOT_EVALUATED
    }

    public static final class GeometrySkin {
        private final Model model;
        private final List<Integer> vertexLookup;
        private final List<Integer> triangleLookup;
        private final List<Long> boneProperties;
        private final List<Section> sections;
        private final long maximumBones;
        private final long sourceBytes;
        private final int headerBytes;
        private final ArrayDeclaration batchDeclaration;
        private final ArrayDeclaration shadowBatchDeclaration;
        private final long retainedBytes;
        private final Limits limits;

        GeometrySkin(
                Model model,
                List<Integer> vertexLookup,
                List<Integer> triangleLookup,
                List<Long> boneProperties,
                List<Section> sections,
                long maximumBones,
                long sourceBytes,
                int headerBytes,
                ArrayDeclaration batchDeclaration,
                ArrayDeclaration shadowBatchDeclaration,
                long retainedBytes,
                Limits limits) {
            this.model = model;
            this.vertexLookup = List.copyOf(vertexLookup);
            this.triangleLookup = List.copyOf(triangleLookup);
            this.boneProperties = List.copyOf(boneProperties);
            this.sections = List.copyOf(sections);
            this.maximumBones = maximumBones;
            this.sourceBytes = sourceBytes;
            this.headerBytes = headerBytes;
            this.batchDeclaration = batchDeclaration;
            this.shadowBatchDeclaration = shadowBatchDeclaration;
            this.retainedBytes = retainedBytes;
            this.limits = limits;
        }

        public Model model() {
            return model;
        }

        public List<Integer> vertexLookup() {
            return vertexLookup;
        }

        public List<Integer> triangleLookup() {
            return triangleLookup;
        }

        public List<Long> boneProperties() {
            return boneProperties;
        }

        public List<Section> sections() {
            return sections;
        }

        public long maximumBones() {
            return maximumBones;
        }

        public long sourceBytes() {
            return sourceBytes;
        }

        public int headerBytes() {
            return headerBytes;
        }

        public ArrayDeclaration batchDeclaration() {
            return batchDeclaration;
        }

        public ArrayDeclaration shadowBatchDeclaration() {
            return shadowBatchDeclaration;
        }

        public long retainedBytes() {
            return retainedBytes;
        }

        public Limits limits() {
            return limits;
        }

        public static final String INTERPRETATION_POLICY = "skin-unfiltered-source-geometry-v1";

        public GeometryScope scope() {
            return GeometryScope.UNFILTERED_DECLARED_SECTIONS;
        }

        public GeometryEvaluation materialEvaluation() {
            return GeometryEvaluation.NOT_EVALUATED;
        }

        public GeometryEvaluation poseEvaluation() {
            return GeometryEvaluation.NOT_EVALUATED;
        }

        public GeometryEvaluation visibilityEvaluation() {
            return GeometryEvaluation.NOT_EVALUATED;
        }

        public GeometryEvaluation appearanceEvaluation() {
            return GeometryEvaluation.NOT_EVALUATED;
        }
    }
}
