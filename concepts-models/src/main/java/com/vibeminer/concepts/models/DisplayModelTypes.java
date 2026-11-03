package com.vibeminer.concepts.models;

import com.vibeminer.rendering.models.MeshAppearance;
import java.util.List;
import java.util.Set;

/**
 * Actual display-linked static geometry/diffuse facts; no runtime pose or character appearance
 * claim.
 */
public final class DisplayModelTypes {
    private DisplayModelTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE
    }

    public record Reference(
            long displayId,
            long modelId,
            long modelFileDataId,
            List<Long> textureVariations,
            Long extendedDisplayInfoId,
            Long creatureGeosetDataId) {
        public Reference {
            textureVariations = List.copyOf(textureVariations);
        }
    }

    public record Preview(
            State state,
            Reference reference,
            MeshAppearance appearance,
            Set<Integer> visibleParts,
            List<String> issues,
            boolean restored,
            String message) {
        public Preview {
            visibleParts = Set.copyOf(visibleParts);
            issues = List.copyOf(issues);
        }
    }
}
