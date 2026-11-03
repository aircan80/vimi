package com.vibeminer.concepts.races;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Set;

/** Exact client-model references and explicitly static rest-surface previews. */
public final class RaceMediaTypes {
    private RaceMediaTypes() {}

    public record Variant(
            long chrModelId, int sex, long displayId, long modelDataId, long modelFileDataId) {
        public String label() {
            return sex == 0
                    ? "Male"
                    : sex == 1 ? "Female" : "Model " + chrModelId + " (sex " + sex + ")";
        }
    }

    public record Variants(
            RaceTypes.State state, List<Variant> variants, List<String> issues, String message) {
        public Variants {
            variants = List.copyOf(variants);
            issues = List.copyOf(issues);
        }
    }

    public record Preview(
            RaceTypes.State state,
            Variant variant,
            PreviewMesh mesh,
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
