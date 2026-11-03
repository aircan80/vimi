package com.vibeminer.concepts.races;

import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2Types;

/** Contextual character draw policies; no global M2 shader admission. */
final class RaceAppearanceProfiles {
    enum Profile {
        LEGACY,
        DWARF_MALE,
        DWARF_FEMALE
    }

    private RaceAppearanceProfiles() {}

    static boolean dwarfDraw(
            Profile profile,
            M2Types.Model model,
            M2Types.Batch batch,
            M2Appearance.ModelFacts facts,
            int sectionBatches) {
        if (profile == Profile.LEGACY
                || model.version() != 272
                || sectionBatches != 1
                || facts == null
                || facts.model() != model
                || batch.flags() != 16
                || batch.flags2() != 0
                || batch.priority() != 0
                || batch.materialLayer() != 0
                || batch.colorIndex() != -1
                || batch.textureCount() != 1
                || !model.textureCoordinates().isEmpty()
                || batch.textureCoordinateComboIndex() != 65535
                || !index(batch.materialIndex(), model.materials().size())
                || !index(batch.textureComboIndex(), model.textureCombos().size())
                || !index(batch.textureWeightComboIndex(), model.textureWeights().size())
                || !index(batch.textureTransformComboIndex(), model.textureTransforms().size())) {
            return false;
        }
        int textureIndex = model.textureCombos().get(batch.textureComboIndex());
        int weight = model.textureWeights().get(batch.textureWeightComboIndex());
        if (!index(textureIndex, model.textures().size())
                || model.textureTransforms().get(batch.textureTransformComboIndex()) != 65535
                || !index(weight, facts.staticWeights().size())
                || !facts.staticWeights().get(weight)) {
            return false;
        }
        var texture = model.textures().get(textureIndex);
        var material = model.materials().get(batch.materialIndex());
        if (texture.type() == 1) {
            return batch.shaderId() == 0
                    && texture.flags() == 0
                    && material.flags() == 0
                    && material.blendMode() == 0;
        }
        return texture.type() == 6
                && texture.flags() == 3
                && material.flags() == 4
                && (profile == Profile.DWARF_MALE
                        ? batch.shaderId() == 16 && material.blendMode() == 1
                        : batch.shaderId() == 0 && material.blendMode() == 0);
    }

    private static boolean index(int value, int size) {
        return value >= 0 && value < size;
    }
}
