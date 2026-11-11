package com.vibeminer.concepts.races;

import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.MeshAppearance;

/** Contextual character draw policies; no global M2 shader admission. */
final class RaceAppearanceProfiles {
    enum Profile {
        LEGACY,
        DWARF_MALE,
        DWARF_FEMALE,
        NIGHT_ELF_MALE,
        NIGHT_ELF_FEMALE
    }

    private RaceAppearanceProfiles() {}

    static boolean dwarfDraw(
            Profile profile,
            M2Types.Model model,
            M2Types.Batch batch,
            M2Appearance.ModelFacts facts,
            int sectionBatches) {
        return (profile == Profile.DWARF_MALE || profile == Profile.DWARF_FEMALE)
                && reviewedDraw(profile, model, batch, facts, sectionBatches);
    }

    static boolean nightElf(Profile profile) {
        return profile == Profile.NIGHT_ELF_MALE || profile == Profile.NIGHT_ELF_FEMALE;
    }

    static boolean reviewedDraw(
            Profile profile,
            M2Types.Model model,
            M2Types.Batch batch,
            M2Appearance.ModelFacts facts,
            int sectionBatches) {
        return reviewedDraw(profile, model, batch, facts, sectionBatches, false);
    }

    /** As above; {@code assumeExternal} is passed to {@link #opaqueWeight}. */
    static boolean reviewedDraw(
            Profile profile,
            M2Types.Model model,
            M2Types.Batch batch,
            M2Appearance.ModelFacts facts,
            int sectionBatches,
            boolean assumeExternal) {
        if ((profile != Profile.DWARF_MALE && profile != Profile.DWARF_FEMALE && !nightElf(profile))
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
                || !opaqueWeight(facts, weight, assumeExternal)) {
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

    /**
     * Whether a texture-weight track counts as full opacity. A track proven constant full always
     * does. With {@code assumeExternal}, so does one left unproven only because some of its keys
     * are stored outside the model (in a sequence's external animation file), every key stored in
     * the model being full opacity. A track proven partial never does.
     */
    static boolean opaqueWeight(M2Appearance.ModelFacts facts, int weight, boolean assumeExternal) {
        if (facts == null || !index(weight, facts.staticWeights().size())) {
            return false;
        }
        return facts.staticWeights().get(weight)
                || assumeExternal && facts.externalWeights().get(weight);
    }

    static MeshAppearance.Surface surface(
            M2Types.Batch batch,
            M2Types.Material material,
            M2Types.Texture texture,
            int mappedTexture) {
        return new MeshAppearance.Surface(
                batch.sectionIndex(),
                mappedTexture,
                (texture.flags() & 1) != 0,
                (texture.flags() & 2) != 0,
                material.blendMode() == 1);
    }

    private static boolean index(int value, int size) {
        return value >= 0 && value < size;
    }
}
