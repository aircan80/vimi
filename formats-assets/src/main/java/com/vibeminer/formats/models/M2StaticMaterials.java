package com.vibeminer.formats.models;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.models.M2Types.Batch;
import com.vibeminer.formats.models.M2Types.Material;
import com.vibeminer.formats.models.M2Types.Model;
import com.vibeminer.formats.models.M2Types.Skin;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class M2StaticMaterials {
    public static final String MATERIAL_POLICY = "m2-opaque-shader0-flags0-uv0-absent-channels-v1";

    private M2StaticMaterials() {}

    public enum TextureReferenceKind {
        FILE_DATA_ID,
        CREATURE_REPLACEMENT
    }

    public record Draw(
            int batchOrdinal,
            int sectionIndex,
            int materialIndex,
            int textureOrdinal,
            TextureReferenceKind referenceKind,
            Long fileDataId,
            long replacementType,
            boolean wrapU,
            boolean wrapV,
            Batch batch,
            Material material) {}

    public record DrawSet(List<Draw> draws) {
        public DrawSet {
            draws = List.copyOf(draws);
        }
    }

    private static ModelFormatException unsupported(String text) {
        return M2Input.error("MATERIAL_UNSUPPORTED", text);
    }

    public static DrawSet admitOpaque(Model model, Skin skin, Cancellation token) {
        token.check();
        if (model != skin.model()) {
            throw M2Input.error("INVALID", "The SKIN belongs to another parsed model.");
        }
        if (model.flags() != 0) {
            throw unsupported("Model global flags are outside reviewed material profile.");
        }
        if (model.skeletonFileDataId() != 0
                || model.boneFileDataIds().stream().anyMatch(v -> v != 0)) {
            throw unsupported("External skeleton/bone semantics are not admitted.");
        }
        if (model.bones().stream().anyMatch(b -> (b.flags() & 0x78) != 0)) {
            throw unsupported("Billboard bones require a supported pose/view policy.");
        }
        if (model.declarations().stream()
                .anyMatch(
                        d ->
                                d.count() != 0
                                        && Set.of("lights", "ribbons", "particles")
                                                .contains(d.name()))) {
            throw unsupported("Model light/emitter effects are outside static profile.");
        }
        for (var chunk : model.chunks()) {
            token.check();
            if (!Set.of("MD21", "SFID", "TXID", "SKID", "AFID", "BFID").contains(chunk.id())) {
                throw unsupported(
                        "Chunk "
                                + chunk.id()
                                + " has no reviewed static-appearance interpretation.");
            }
        }
        if (!skin.shadowBatches().isEmpty()) {
            throw unsupported("Shadow batches are not admitted.");
        }
        if (skin.batches().isEmpty()) {
            throw unsupported("Selected SKIN has no explicit draw batches.");
        }
        var budget = new M2Input.Budget(skin.limits().maxOutputBytes(), skin.retainedBytes());
        budget.add(skin.batches().size(), 128);
        budget.add(skin.sections().size(), 1);
        boolean[] seen = new boolean[skin.sections().size()];
        List<Draw> draws = new ArrayList<>();
        for (int i = 0; i < skin.batches().size(); i++) {
            token.check();
            Batch b = skin.batches().get(i);
            Material m = model.materials().get(b.materialIndex());
            if (b.flags() != 0
                    || b.priority() != 0
                    || b.shaderId() != 0
                    || b.flags2() != 0
                    || b.colorIndex() != -1
                    || b.materialLayer() != 0
                    || b.textureCount() != 1
                    || m.flags() != 0
                    || m.blendMode() != 0) {
                throw unsupported(
                        "Batch "
                                + i
                                + " requires a shader/flag/color/layer/blend outside opaque profile.");
            }
            if (seen[b.sectionIndex()]) {
                throw unsupported("Multiple batches on a section require a layering policy.");
            }
            seen[b.sectionIndex()] = true;
            if (b.textureCoordinateComboIndex() >= model.textureCoordinates().size()) {
                throw unsupported("Batch has no texture-coordinate entry saying UV0.");
            }
            if (model.textureCoordinates().get(b.textureCoordinateComboIndex()) != 0
                    || model.textureWeights().get(b.textureWeightComboIndex()) != 65535
                    || model.textureTransforms().get(b.textureTransformComboIndex()) != 65535) {
                throw unsupported(
                        "Batch requires active opacity/UV-transform/environment channel.");
            }
            int ordinal = model.textureCombos().get(b.textureComboIndex());
            var texture = model.textures().get(ordinal);
            if ((texture.flags() & ~3L) != 0) {
                throw unsupported("Texture flags exceed U/V wrap bits.");
            }
            TextureReferenceKind kind;
            if (texture.type() == 0) {
                if (texture.fileDataId() == null || texture.fileDataId() == 0) {
                    throw unsupported("Type-0 texture needs an exact positive TXID.");
                }
                kind = TextureReferenceKind.FILE_DATA_ID;
            } else if (texture.type() >= 11 && texture.type() <= 13) {
                kind = TextureReferenceKind.CREATURE_REPLACEMENT;
            } else {
                throw unsupported("Replacement type has no admitted creature binding.");
            }
            var section = skin.sections().get(b.sectionIndex());
            if (section.triangleCount() == 0) {
                throw unsupported("Explicit batch has no static surface triangles.");
            }
            for (long n = section.triangleStart();
                    n < section.triangleStart() + section.triangleCount();
                    n++) {
                if ((n & 4095) == 0) {
                    token.check();
                }
                var normal =
                        model.vertices()
                                .get(skin.vertexLookup().get(skin.triangleLookup().get((int) n)))
                                .normal();
                float length =
                        normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z();
                if (length <= 0 || !Float.isFinite(length)) {
                    throw unsupported("Lit preview requires finite nonzero source normals.");
                }
            }
            draws.add(
                    new Draw(
                            i,
                            b.sectionIndex(),
                            b.materialIndex(),
                            ordinal,
                            kind,
                            kind == TextureReferenceKind.FILE_DATA_ID ? texture.fileDataId() : null,
                            kind == TextureReferenceKind.CREATURE_REPLACEMENT ? texture.type() : 0,
                            (texture.flags() & 1) != 0,
                            (texture.flags() & 2) != 0,
                            b,
                            m));
        }
        for (int i = 0; i < seen.length; i++) {
            token.check();
            if (!seen[i] && skin.sections().get(i).triangleCount() != 0) {
                throw unsupported("A surface section lacks an explicit supported batch.");
            }
        }
        return new DrawSet(draws);
    }
}
