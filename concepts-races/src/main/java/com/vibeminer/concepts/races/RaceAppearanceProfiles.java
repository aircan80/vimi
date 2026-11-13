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

    /** M2 texture type 20: character jewelry and accessories (Shadowlands and later). */
    static final long ACCESSORY_TEXTURE_TYPE = 20;

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
        if (texture.type() == RaceAppearancePlan.CAPE_TEXTURE_TYPE) {
            return capeDraw(batch, texture, material);
        }
        if (texture.type() == 0 || texture.type() == ACCESSORY_TEXTURE_TYPE) {
            // Accessories (facial jewelry, piercings, necklaces: geoset groups 16, 35, 36) in a
            // file the model names, or the jewelry canvas (type 20): a plain single-texture
            // draw, opaque or alpha-keyed, one- or two-sided (material flag 0x4, wowdev M2).
            return batch.shaderId() == 0
                    && (material.flags() == 0 || material.flags() == 4)
                    && material.blendMode() <= 1;
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

    /**
     * A two-texture draw with shader effect 0 ({@code 0x8000}: {@code
     * Combiners_Opaque_Mod2xNA_Alpha} over {@code Diffuse_T1_Env}). Its first stage is the UV0
     * diffuse, which the static preview draws; its second, a sphere-mapped environment reflection
     * weighted by the diffuse alpha, is not drawn. Earthen bodies use it.
     */
    static boolean environmentFirstStage(M2Types.Batch batch) {
        return batch.textureCount() == 2 && batch.shaderId() == 0x8000;
    }

    /** M2 texture type 19: character eyes (Shadowlands and later). */
    static final long EYES_TEXTURE_TYPE = 19;

    /**
     * A character eye draw (#385): two textures, the first of type 19, whose shader ID (bit 0x8000
     * clear) decodes to pixel shader {@code Combiners_Mod_Add} and vertex shader {@code
     * Diffuse_T1_T2}, as WebWowViewerCpp's {@code getPixelShaderId}/{@code getVertexShaderId}
     * (wowViewerLib/src/engine/objects/m2/m2Object.cpp) decode it: two textures with bits 0x70 set
     * and {@code shaderId & 7} 3 give {@code Combiners_Mod_Add}; bit 0x80 and bit 8 clear give
     * {@code Diffuse_T1_T2}. Its first stage is the eye colour (type 19, filled from the NPC's eye
     * customization like the hair from type 6), modulated as any diffuse; the second is added on
     * top through the second UV set, a highlight the static preview does not draw. The eyes geoset
     * is group 33 (wowdev Character_Customization, Shadowlands 9.0.1).
     */
    static boolean eyesFirstStage(M2Types.Batch batch, M2Types.Texture first) {
        int shader = batch.shaderId();
        return batch.textureCount() == 2
                && first.type() == EYES_TEXTURE_TYPE
                && (shader & 0x8000) == 0
                && (shader & 0x70) != 0
                && (shader & 7) == 3
                && (shader & 0x80) == 0
                && (shader & 8) == 0;
    }

    /**
     * A one-texture draw whose shader ID (bit 0x8000 clear) decodes to pixel shader {@code
     * Combiners_Mod} over vertex shader {@code Diffuse_T1}, as WebWowViewerCpp's {@code
     * getPixelShaderId}/{@code getVertexShaderId} decode it (wowViewerLib/src/engine/objects/m2/
     * m2Object.cpp): with one texture, any of bits 0x70 set gives {@code Combiners_Mod}, and bits
     * 0x80 and 0x4000 clear give {@code Diffuse_T1}. {@code Combiners_Mod} is the texture's colour
     * times the mesh colour, discarded by the texture's alpha (wowViewerLib/shaders/slang/common/
     * commonM2Material.slang), which is what an alpha-keyed surface draws.
     */
    static boolean modDiffuse(M2Types.Batch batch) {
        int shader = batch.shaderId();
        return batch.textureCount() == 1
                && (shader & 0x8000) == 0
                && (shader & 0x70) != 0
                && (shader & 0x80) == 0
                && (shader & 0x4000) == 0;
    }

    /**
     * An NPC cape's draw (texture type 2, {@link RaceAppearancePlan#CAPE_TEXTURE_TYPE}; geoset
     * group 15): one texture, one- or two-sided (material flag 0x4, wowdev M2), either shader 0,
     * opaque or alpha-keyed, or {@link #modDiffuse} with the alpha-key blend (1), which is how the
     * character models record their capes (70862: shader 16, blend 1).
     */
    static boolean capeDraw(
            M2Types.Batch batch, M2Types.Texture texture, M2Types.Material material) {
        return texture.type() == RaceAppearancePlan.CAPE_TEXTURE_TYPE
                && batch.textureCount() == 1
                && (material.flags() == 0 || material.flags() == 4)
                && (batch.shaderId() == 0 && material.blendMode() <= 1
                        || modDiffuse(batch) && material.blendMode() == 1);
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
