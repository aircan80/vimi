package com.vibeminer.formats.models;

import java.util.List;

public final class GeometryTypes {
    private GeometryTypes() {}

    public record OriginalSource(
            String contentKey,
            String encodingKey,
            String encodedSha256,
            long encodedBytes,
            long decodedBytes,
            String verifierRevision) {}

    public record SourceBinding(
            OriginalSource model,
            OriginalSource skin,
            long modelFileDataId,
            long skinFileDataId,
            int mainSkinOrdinal) {}

    public record BufferRegion(int offset, int byteLength) {}

    public record Evaluations(String material, String pose, String visibility, String appearance) {}

    public record Bounds(List<Float> min, List<Float> max) {
        public Bounds {
            min = List.copyOf(min);
            max = List.copyOf(max);
        }

        public Bounds(M2Types.Vec3 min, M2Types.Vec3 max) {
            this(List.of(min.x(), min.y(), min.z()), List.of(max.x(), max.y(), max.z()));
        }
    }

    public record Manifest(
            String artifactId,
            String bindingSha256,
            String sha256,
            long byteLength,
            int formatVersion,
            int headerBytes,
            String scope,
            String coordinatePolicy,
            Evaluations evaluations,
            int vertexCount,
            int vertexLookupCount,
            int sourceTriangleIndexCount,
            int sectionIndexCount,
            int lineIndexCount,
            int sectionCount,
            BufferRegion positions,
            BufferRegion lineIndices,
            BufferRegion sections,
            Bounds bounds,
            long modelFileDataId,
            long skinFileDataId,
            int mainSkinOrdinal,
            String modelReaderRevision,
            String skinReaderRevision,
            String interpretationPolicy,
            String artifactRevision,
            String edgePolicy) {}

    public record Artifact(
            SourceBinding binding,
            String bindingSha256,
            String artifactId,
            String sha256,
            long byteLength,
            Manifest manifest) {}
}
