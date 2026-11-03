package com.vibeminer.concepts.models;

import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.MeshAppearance;

/** Exact reviewed static source profiles; raw shader integers do not reach renderers. */
final class DisplayMaterialProfiles {
    enum Kind {
        SIMPLE,
        ENVIRONMENT,
        UNLIT_OPAQUE,
        ADDITIVE_CARD
    }

    record Profile(
            Kind kind,
            MeshAppearance.Combiner combiner,
            MeshAppearance.Lighting lighting,
            MeshAppearance.Blend blend,
            int stages) {
        MeshAppearance.Coordinate coordinate(int stage) {
            return kind == Kind.ENVIRONMENT && stage == 1
                    ? MeshAppearance.Coordinate.ENV_SPHERE
                    : MeshAppearance.Coordinate.UV0;
        }
    }

    private DisplayMaterialProfiles() {}

    static Profile admit(M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts) {
        if (DisplayModelService.simpleDraw(model, batch, facts)) {
            return new Profile(
                    Kind.SIMPLE,
                    MeshAppearance.Combiner.DIFFUSE,
                    MeshAppearance.Lighting.LIT,
                    model.materials().get(batch.materialIndex()).blendMode() == 1
                            ? MeshAppearance.Blend.ALPHA_KEY
                            : MeshAppearance.Blend.OPAQUE,
                    1);
        }
        if (!model.isChunked()
                || model.version() != 272 && model.version() != 274
                || batch.colorIndex() != -1
                || batch.priority() != 0
                || batch.materialLayer() != 0
                || batch.flags2() != 0
                        && !(model.version() == 272 && batch.flags2() == batch.sectionIndex())) {
            return null;
        }
        var material = model.materials().get(batch.materialIndex());
        Kind kind;
        if ((model.version() == 274 && batch.flags2() == 0 && material.flags() == 0
                        || model.version() == 272
                                && batch.flags2() == batch.sectionIndex()
                                && (material.flags() == 0 || material.flags() == 4))
                && batch.shaderId() == 14
                && batch.textureCount() == 2
                && batch.flags() == 0x90
                && material.blendMode() == 0
                && model.textureCoordinates().isEmpty()) {
            kind = Kind.ENVIRONMENT;
        } else if (model.version() == 272
                && batch.shaderId() == 0
                && batch.textureCount() == 1
                && batch.flags() == 0x10
                && batch.flags2() == batch.sectionIndex()
                && material.flags() == 1
                && material.blendMode() == 0) {
            kind = Kind.UNLIT_OPAQUE;
        } else if (batch.shaderId() == 16
                && batch.textureCount() == 1
                && batch.flags() == 0x10
                && material.blendMode() == 4
                && (model.version() == 274 && batch.flags2() == 0 && material.flags() == 0x11
                        || model.version() == 272
                                && batch.flags2() == batch.sectionIndex()
                                && material.flags() == 0x15)) {
            kind = Kind.ADDITIVE_CARD;
        } else {
            return null;
        }
        if (!shared(model, batch, facts, kind)) {
            return null;
        }
        return new Profile(
                kind,
                kind == Kind.ENVIRONMENT
                        ? MeshAppearance.Combiner.OPAQUE_MOD2X_NA
                        : MeshAppearance.Combiner.DIFFUSE,
                kind == Kind.ENVIRONMENT
                        ? MeshAppearance.Lighting.LIT
                        : MeshAppearance.Lighting.UNLIT,
                kind == Kind.ADDITIVE_CARD
                        ? MeshAppearance.Blend.ADDITIVE
                        : MeshAppearance.Blend.OPAQUE,
                batch.textureCount());
    }

    private static boolean shared(
            M2Types.Model model, M2Types.Batch batch, M2Appearance.ModelFacts facts, Kind kind) {
        int weight = model.textureWeights().get(batch.textureWeightComboIndex());
        if (weight != 65535
                && (facts == null
                        || facts.model() != model
                        || weight >= facts.staticWeights().size()
                        || !facts.staticWeights().get(weight))) {
            return false;
        }
        if (kind != Kind.ENVIRONMENT
                && !model.textureCoordinates().isEmpty()
                && (batch.textureCoordinateComboIndex() >= model.textureCoordinates().size()
                        || model.textureCoordinates().get(batch.textureCoordinateComboIndex())
                                != 0)) {
            return false;
        }
        for (int stage = 0; stage < batch.textureCount(); stage++) {
            if (model.textureTransforms().get(batch.textureTransformComboIndex() + stage)
                    != 65535) {
                return false;
            }
            var texture =
                    model.textures()
                            .get(model.textureCombos().get(batch.textureComboIndex() + stage));
            if ((texture.flags() & ~3L) != 0) {
                return false;
            }
        }
        return true;
    }
}
