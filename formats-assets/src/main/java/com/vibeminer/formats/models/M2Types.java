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

    public record Model(
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
        public Model {
            vertices = List.copyOf(vertices);
            textures = List.copyOf(textures);
            materials = List.copyOf(materials);
            bones = List.copyOf(bones);
            textureCombos = List.copyOf(textureCombos);
            textureCoordinates = List.copyOf(textureCoordinates);
            textureWeights = List.copyOf(textureWeights);
            textureTransforms = List.copyOf(textureTransforms);
            boneLookup = List.copyOf(boneLookup);
            skinFileDataIds = List.copyOf(skinFileDataIds);
            lodSkinFileDataIds = List.copyOf(lodSkinFileDataIds);
            boneFileDataIds = List.copyOf(boneFileDataIds);
            animationFiles = List.copyOf(animationFiles);
            declarations = List.copyOf(declarations);
            chunks = List.copyOf(chunks);
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

    public record Skin(
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
        public Skin {
            vertexLookup = List.copyOf(vertexLookup);
            triangleLookup = List.copyOf(triangleLookup);
            boneProperties = List.copyOf(boneProperties);
            sections = List.copyOf(sections);
            batches = List.copyOf(batches);
            shadowBatches = List.copyOf(shadowBatches);
        }
    }

    public enum GeometryScope {
        UNFILTERED_DECLARED_SECTIONS
    }

    public enum GeometryEvaluation {
        NOT_EVALUATED
    }

    public record GeometrySkin(
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
        public static final String INTERPRETATION_POLICY = "skin-unfiltered-source-geometry-v1";

        public GeometrySkin {
            vertexLookup = List.copyOf(vertexLookup);
            triangleLookup = List.copyOf(triangleLookup);
            boneProperties = List.copyOf(boneProperties);
            sections = List.copyOf(sections);
        }

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
