package com.vibeminer.concepts.races;

import com.vibeminer.rendering.models.PreviewMesh;
import java.util.List;
import java.util.Set;

/** Exact client-model references and explicitly static rest-surface previews. */
public final class RaceMediaTypes {
    private RaceMediaTypes() {}

    /** Standard or high definition, as named by an explicit recorded source field. */
    public enum Quality {
        SD,
        HD
    }

    /** Whether the recorded source states the definition of the selected display. */
    public enum DefinitionState {
        /** One explicit SD/HD field pair names exactly the selected display. */
        SELECTED,
        /** The pair fields are verified, but one side records no display. */
        NO_RECORDED_PAIR,
        /** This layout has no verified SD/HD pair fields; the source states nothing. */
        UNSUPPORTED_PROFILE,
        /** Both fields name the same display, so the definition cannot be distinguished. */
        AMBIGUOUS,
        /** The selected display matches neither recorded pair field. */
        UNMATCHED,
        /** The source row or cell could not be read; retry can reassess it. */
        UNREADABLE
    }

    /**
     * Source-qualified definition of one recorded display. Only {@code SELECTED} carries a quality;
     * every other state is a refusal with its reason. Labelling never changes the shown display.
     */
    public record Definition(
            DefinitionState state,
            Quality quality,
            String source,
            long standardDisplayId,
            long highDisplayId,
            String message) {
        public Definition {
            if (state == null
                    || message == null
                    || (state == DefinitionState.SELECTED) != (quality != null)
                    || (quality != null) != (source != null)) {
                throw new IllegalArgumentException("Inconsistent model definition");
            }
        }

        static Definition refused(DefinitionState state, String message) {
            return new Definition(state, null, null, 0, 0, message);
        }

        public static Definition notAssessed() {
            return refused(
                    DefinitionState.UNSUPPORTED_PROFILE, "Model definition was not assessed.");
        }

        public String label() {
            return state == DefinitionState.SELECTED
                    ? quality + " · " + source
                    : "Definition not stated · " + message;
        }
    }

    public record Variant(
            long chrModelId,
            int sex,
            long displayId,
            long modelDataId,
            long modelFileDataId,
            Definition definition) {
        public Variant {
            java.util.Objects.requireNonNull(definition, "definition");
        }

        public Variant(
                long chrModelId, int sex, long displayId, long modelDataId, long modelFileDataId) {
            this(
                    chrModelId,
                    sex,
                    displayId,
                    modelDataId,
                    modelFileDataId,
                    Definition.notAssessed());
        }

        public String label() {
            String base =
                    sex == 0
                            ? "Male"
                            : sex == 1 ? "Female" : "Model " + chrModelId + " (sex " + sex + ")";
            return definition.quality() == null ? base : base + " · " + definition.quality();
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
