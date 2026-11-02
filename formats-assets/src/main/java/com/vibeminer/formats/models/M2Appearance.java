package com.vibeminer.formats.models;

import static com.vibeminer.formats.models.M2Input.error;
import static com.vibeminer.formats.models.M2Input.i16;
import static com.vibeminer.formats.models.M2Input.u16;
import static com.vibeminer.formats.models.M2Input.u32;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.AssetTasks;
import com.vibeminer.formats.models.M2Combiners.Combiner;
import com.vibeminer.formats.models.M2Combiners.Coordinate;
import com.vibeminer.formats.models.M2Input.Span;
import com.vibeminer.formats.models.M2Types.ArrayDeclaration;
import com.vibeminer.formats.models.M2Types.Batch;
import com.vibeminer.formats.models.M2Types.GeometrySkin;
import com.vibeminer.formats.models.M2Types.Limits;
import com.vibeminer.formats.models.M2Types.Material;
import com.vibeminer.formats.models.M2Types.Model;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/** Static appearance proofs only. Does not fetch textures, pick a display or evaluate animation. */
public final class M2Appearance {
    public static final String READER_REVISION = "m2-appearance-batches-v1",
            MATERIAL_POLICY = "m2-one-or-two-stage-combiners-v1";
    public static final int ALPHA_REFERENCE = 128;

    private M2Appearance() {}

    public enum Refusal {
        MULTI_TEXTURE,
        BLEND_UNSUPPORTED,
        TEXTURE_WEIGHT,
        TEXTURE_TRANSFORM,
        MATERIAL_UNSUPPORTED,
        COMBINER_UNSUPPORTED
    }

    public enum TextureBinding {
        FILE_DATA_ID,
        CREATURE_VARIATION
    }

    public record Decline(
            Refusal reason, Integer batchOrdinal, Integer textureOrdinal, String message) {}

    public record Texture(
            int ordinal,
            long type,
            TextureBinding binding,
            Integer variation,
            long fileDataId,
            boolean wrapU,
            boolean wrapV) {}

    public record Draw(
            int batchOrdinal,
            int sectionIndex,
            int textureOrdinal,
            boolean alphaKey,
            boolean twoSided,
            boolean depthTest,
            boolean depthWrite,
            Combiner combiner,
            Coordinate coordinate,
            Integer secondTextureOrdinal,
            Coordinate secondCoordinate) {}

    public record Effects(
            int particles,
            int ribbons,
            int lights,
            int shadowBatches,
            int unbatchedSections,
            int blendedBatches,
            int envTransforms) {}

    public static final class ModelFacts {
        private final Model model;
        private final List<Boolean> staticWeights;

        ModelFacts(Model model, List<Boolean> weights) {
            this.model = model;
            this.staticWeights = List.copyOf(weights);
        }

        public Model model() {
            return model;
        }

        public List<Boolean> staticWeights() {
            return staticWeights;
        }
    }

    public record Plan(List<Draw> draws, List<Texture> textures, Decline decline, Effects effects) {
        public Plan {
            draws = List.copyOf(draws);
            textures = List.copyOf(textures);
        }
    }

    public static CompletableFuture<Plan> read(
            AssetSource model,
            Model parsedModel,
            AssetSource skin,
            GeometrySkin parsedSkin,
            Cancellation token) {
        return readModel(model, parsedModel, token)
                .thenCompose(facts -> admit(skin, parsedSkin, facts, token));
    }

    public static CompletableFuture<ModelFacts> readModel(
            AssetSource source, Model model, Cancellation token) {
        return AssetTasks.run(() -> readModelCore(source, model, token));
    }

    private record SliceKey(long count, long offset, boolean global) {}

    private record KeysKey(long count, long offset) {}

    private static ModelFacts readModelCore(AssetSource source, Model model, Cancellation token) {
        token.check();
        M2Input input = new M2Input(source, model.limits(), token);
        if (input.length != model.sourceBytes()) {
            throw error("INVALID", "Appearance model differs from parsed geometry model.");
        }
        int header = (model.flags() & 8) != 0 ? 312 : 304;
        Limits limits = model.limits();
        ArrayDeclaration sequences = declaration(model, "sequences");
        List<Long> flags = new ArrayList<>();
        input.records(
                input.span(
                        "sequences",
                        sequences.count(),
                        sequences.offset(),
                        64,
                        limits.maxMetadataEntries(),
                        model.baseOffset(),
                        model.baseBytes(),
                        header),
                (row, i) -> flags.add(u32(row, 12)));
        ArrayDeclaration weightTracks = declaration(model, "weights");
        long globals = declaration(model, "globalSequences").count();
        List<long[]> tracks = new ArrayList<>();
        input.records(
                input.span(
                        "weights",
                        weightTracks.count(),
                        weightTracks.offset(),
                        20,
                        limits.maxMetadataEntries(),
                        model.baseOffset(),
                        model.baseBytes(),
                        header),
                (row, i) -> tracks.add(new long[] {i16(row, 2), u32(row, 12), u32(row, 16)}));
        List<Boolean> proofs = new ArrayList<>();
        int slicesLeft = 65536, keysLeft = 65536;
        Map<SliceKey, Boolean> sliceArrays = new HashMap<>();
        Map<KeysKey, Boolean> keyArrays = new HashMap<>();
        for (long[] track : tracks) {
            token.check();
            int global = (int) track[0];
            long count = track[1], offset = track[2];
            if (global >= globals) {
                proofs.add(false);
                continue;
            }
            SliceKey key = new SliceKey(count, count == 0 ? 0 : offset, global >= 0);
            if (sliceArrays.containsKey(key)) {
                proofs.add(sliceArrays.get(key));
                continue;
            }
            boolean result = false;
            if (count <= slicesLeft) {
                slicesLeft -= (int) count;
                result = true;
                List<long[]> slices = new ArrayList<>();
                input.records(
                        input.span(
                                "weight values",
                                count,
                                offset,
                                8,
                                limits.maxMetadataEntries(),
                                model.baseOffset(),
                                model.baseBytes(),
                                header),
                        (row, i) -> slices.add(new long[] {u32(row), u32(row, 4)}));
                for (int i = 0; result && i < slices.size(); i++) {
                    token.check();
                    long keyCount = slices.get(i)[0], keyOffset = slices.get(i)[1];
                    if (keyCount == 0) {
                        continue;
                    }
                    if (global < 0 && (i >= flags.size() || (flags.get(i) & 0x20) == 0)) {
                        result = false;
                        break;
                    }
                    KeysKey k = new KeysKey(keyCount, keyOffset);
                    if (keyArrays.containsKey(k)) {
                        result = keyArrays.get(k);
                        continue;
                    }
                    if (keyCount > keysLeft) {
                        result = false;
                        break;
                    }
                    keysLeft -= (int) keyCount;
                    boolean[] full = {true};
                    input.records(
                            input.span(
                                    "weight keys",
                                    keyCount,
                                    keyOffset,
                                    2,
                                    65536,
                                    model.baseOffset(),
                                    model.baseBytes(),
                                    header),
                            (row, n) -> {
                                if (u16(row) != 32767) {
                                    full[0] = false;
                                }
                            });
                    keyArrays.put(k, full[0]);
                    result = full[0];
                }
            }
            sliceArrays.put(key, result);
            proofs.add(result);
        }
        input.finish();
        return new ModelFacts(model, proofs);
    }

    public static CompletableFuture<Plan> admit(
            AssetSource source, GeometrySkin skin, ModelFacts facts, Cancellation token) {
        return AssetTasks.run(() -> admitCore(source, skin, facts, token));
    }

    private static Plan admitCore(
            AssetSource source, GeometrySkin skin, ModelFacts facts, Cancellation token) {
        token.check();
        Model model = facts.model();
        if (skin.model() != model) {
            throw error("INVALID", "SKIN belongs to another parsed model.");
        }
        M2Input input = new M2Input(source, skin.limits(), token);
        if (input.length != skin.sourceBytes()) {
            throw error("INVALID", "Appearance SKIN differs from parsed geometry SKIN.");
        }
        ArrayDeclaration bd = skin.batchDeclaration();
        if (bd.stride() != 24) {
            throw error("INVALID", "SKIN batch records are not24 bytes.");
        }
        if (bd.count() > model.limits().maxBatches()) {
            throw error("LIMIT", "SKIN batch count exceeds policy.");
        }
        List<Batch> batches = new ArrayList<>();
        input.records(
                new Span("batches", bd.offset(), (int) bd.count(), 24),
                (row, i) ->
                        batches.add(
                                new Batch(
                                        row[0] & 255,
                                        row[1],
                                        u16(row, 2),
                                        u16(row, 4),
                                        u16(row, 6),
                                        i16(row, 8),
                                        u16(row, 10),
                                        u16(row, 12),
                                        u16(row, 14),
                                        u16(row, 16),
                                        u16(row, 18),
                                        u16(row, 20),
                                        u16(row, 22))));
        input.finish();
        long weightTracks = declaration(model, "weights").count(),
                transformTracks = declaration(model, "transforms").count(),
                colors = declaration(model, "colors").count();
        boolean[] batched = new boolean[skin.sections().size()],
                drawn = new boolean[batched.length];
        List<Draw> draws = new ArrayList<>();
        Map<Integer, Texture> textures = new TreeMap<>();
        int blended = 0, envTransforms = 0;
        Integer firstBlended = null;
        Decline decline = null;
        for (int i = 0; i < batches.size() && decline == null; i++) {
            token.check();
            Batch b = batches.get(i);
            if (b.sectionIndex() >= skin.sections().size()
                    || b.materialIndex() >= model.materials().size()
                    || b.colorIndex() < -1
                    || b.colorIndex() >= colors) {
                decline =
                        new Decline(
                                Refusal.MATERIAL_UNSUPPORTED,
                                i,
                                null,
                                "Batch names an undeclared section, material or color.");
                break;
            }
            Material material = model.materials().get(b.materialIndex());
            if (material.blendMode() > 7) {
                decline =
                        new Decline(
                                Refusal.BLEND_UNSUPPORTED,
                                i,
                                null,
                                "Batch blend mode " + material.blendMode() + " has no M2 meaning.");
                break;
            }
            batched[b.sectionIndex()] = true;
            if (material.blendMode() >= 2) {
                blended++;
                if (skin.sections().get(b.sectionIndex()).triangleCount() != 0
                        && firstBlended == null) {
                    firstBlended = i;
                }
                continue;
            }
            if (b.textureCount() == 0 || b.textureCount() > 2) {
                decline =
                        new Decline(
                                Refusal.MULTI_TEXTURE,
                                i,
                                null,
                                "Batch combines "
                                        + b.textureCount()
                                        + " stages; one or two are drawn.");
                break;
            }
            int stages = b.textureCount();
            String outside = outsideProfile(material, b, stages);
            if (outside != null) {
                decline = new Decline(Refusal.MATERIAL_UNSUPPORTED, i, null, outside);
                break;
            }
            var admission = M2Combiners.admit(stages, b.shaderId());
            var profile = admission.profile();
            if (profile == null) {
                decline =
                        new Decline(
                                Refusal.COMBINER_UNSUPPORTED,
                                i,
                                null,
                                "Batch combines textures with "
                                        + admission.label()
                                        + ", which is not drawn.");
                break;
            }
            if (b.textureComboIndex() + stages > model.textureCombos().size()) {
                decline =
                        new Decline(
                                Refusal.MATERIAL_UNSUPPORTED,
                                i,
                                null,
                                "Batch texture lookup names no declared texture.");
                break;
            }
            int[] ordinals = new int[stages];
            boolean badOrdinal = false;
            for (int s = 0; s < stages; s++) {
                ordinals[s] = model.textureCombos().get(b.textureComboIndex() + s);
                if (ordinals[s] >= model.textures().size()) {
                    badOrdinal = true;
                }
            }
            if (badOrdinal) {
                decline =
                        new Decline(
                                Refusal.MATERIAL_UNSUPPORTED,
                                i,
                                null,
                                "Batch texture lookup names no declared texture.");
                break;
            }
            if (!model.textureCoordinates().isEmpty()) {
                for (int s = 0; s < stages; s++) {
                    int expected =
                            switch (profile.stages().get(s)) {
                                case UV0 -> 0;
                                case UV1 -> 1;
                                case ENV -> -1;
                            };
                    if (b.textureCoordinateComboIndex() + s >= model.textureCoordinates().size()
                            || model.textureCoordinates().get(b.textureCoordinateComboIndex() + s)
                                    != expected) {
                        decline =
                                new Decline(
                                        Refusal.MATERIAL_UNSUPPORTED,
                                        i,
                                        ordinals[s],
                                        "Coordinate lookup disagrees with combiner coordinates.");
                        break;
                    }
                }
                if (decline != null) {
                    break;
                }
            }
            if (b.textureWeightComboIndex() >= model.textureWeights().size()
                    || b.textureTransformComboIndex() + stages > model.textureTransforms().size()) {
                decline =
                        new Decline(
                                Refusal.MATERIAL_UNSUPPORTED,
                                i,
                                ordinals[0],
                                "Batch opacity/UV-transform lookup lies outside model.");
                break;
            }
            for (int s = 0; s < stages; s++) {
                if (b.textureWeightComboIndex() + s >= model.textureWeights().size()) {
                    break;
                }
                int weight = model.textureWeights().get(b.textureWeightComboIndex() + s);
                if (weight != 65535
                        && (weight >= weightTracks
                                || weight >= facts.staticWeights().size()
                                || !facts.staticWeights().get(weight))) {
                    decline =
                            new Decline(
                                    Refusal.TEXTURE_WEIGHT,
                                    i,
                                    ordinals[s],
                                    "Texture weight is not proven constant full opacity.");
                    break;
                }
            }
            if (decline != null) {
                break;
            }
            int animated = 0;
            for (int s = 0; s < stages; s++) {
                int transform = model.textureTransforms().get(b.textureTransformComboIndex() + s);
                if (transform == 65535 || transform >= transformTracks) {
                    continue;
                }
                if (profile.stages().get(s) == Coordinate.ENV) {
                    animated++;
                } else {
                    decline =
                            new Decline(
                                    Refusal.TEXTURE_TRANSFORM,
                                    i,
                                    ordinals[s],
                                    "Batch animates its texture coordinates.");
                    break;
                }
            }
            if (decline != null) {
                break;
            }
            List<Texture> references = new ArrayList<>();
            for (int s = 0; s < stages; s++) {
                var texture = model.textures().get(ordinals[s]);
                if ((texture.flags() & ~3L) != 0) {
                    decline =
                            new Decline(
                                    Refusal.MATERIAL_UNSUPPORTED,
                                    i,
                                    ordinals[s],
                                    "Texture flags exceed U/V wrap bits.");
                    break;
                }
                TextureBinding kind;
                Integer variation;
                long id;
                if (texture.type() == 0) {
                    kind = TextureBinding.FILE_DATA_ID;
                    variation = null;
                    id = texture.fileDataId() == null ? 0 : texture.fileDataId();
                } else if (texture.type() >= 11 && texture.type() <= 13) {
                    kind = TextureBinding.CREATURE_VARIATION;
                    variation = (int) texture.type() - 11;
                    id = 0;
                } else {
                    decline =
                            new Decline(
                                    Refusal.MATERIAL_UNSUPPORTED,
                                    i,
                                    ordinals[s],
                                    "Texture type " + texture.type() + " has no creature binding.");
                    break;
                }
                references.add(
                        new Texture(
                                ordinals[s],
                                texture.type(),
                                kind,
                                variation,
                                id,
                                (texture.flags() & 1) != 0,
                                (texture.flags() & 2) != 0));
            }
            if (decline != null) {
                break;
            }
            if (drawn[b.sectionIndex()]) {
                decline =
                        new Decline(
                                Refusal.MATERIAL_UNSUPPORTED,
                                i,
                                ordinals[0],
                                "Several opaque/alpha-key batches layer one section.");
                break;
            }
            drawn[b.sectionIndex()] = true;
            envTransforms += animated;
            for (Texture reference : references) {
                textures.put(reference.ordinal(), reference);
            }
            if (skin.sections().get(b.sectionIndex()).triangleCount() != 0) {
                draws.add(
                        new Draw(
                                i,
                                b.sectionIndex(),
                                ordinals[0],
                                material.blendMode() == 1 && profile.textureAlpha(),
                                (material.flags() & 4) != 0,
                                (material.flags() & 8) == 0,
                                (material.flags() & 0x10) == 0,
                                profile.combiner(),
                                profile.stages().getFirst(),
                                stages == 2 ? ordinals[1] : null,
                                stages == 2 ? profile.stages().get(1) : null));
            }
        }
        if (decline == null && draws.isEmpty()) {
            decline =
                    firstBlended != null
                            ? new Decline(
                                    Refusal.BLEND_UNSUPPORTED,
                                    firstBlended,
                                    null,
                                    "Every surface batch is blended; no opaque/alpha-key surface remains.")
                            : new Decline(
                                    Refusal.MATERIAL_UNSUPPORTED,
                                    null,
                                    null,
                                    "No batch draws a declared surface triangle.");
        }
        int unbatched = 0;
        for (int i = 0; i < batched.length; i++) {
            if (!batched[i] && skin.sections().get(i).triangleCount() != 0) {
                unbatched++;
            }
        }
        Effects effects =
                new Effects(
                        (int) declaration(model, "particles").count(),
                        (int) declaration(model, "ribbons").count(),
                        (int) declaration(model, "lights").count(),
                        (int) skin.shadowBatchDeclaration().count(),
                        decline == null ? unbatched : 0,
                        decline == null ? blended : 0,
                        decline == null ? envTransforms : 0);
        return decline == null
                ? new Plan(draws, new ArrayList<>(textures.values()), null, effects)
                : new Plan(List.of(), List.of(), decline, effects);
    }

    private static String outsideProfile(Material material, Batch b, int stages) {
        long render = material.flags() & ~0x1dL;
        if (render != 0) {
            return String.format(
                    java.util.Locale.ROOT,
                    "Material render flags0x%02X are outside appearance profile.",
                    render);
        }
        if (b.flags() != 0
                && b.flags() != 0x10
                && !(stages == 2 && (b.flags() == 0x80 || b.flags() == 0x90))) {
            return String.format(
                    java.util.Locale.ROOT,
                    "Batch flags0x%02X are outside appearance profile.",
                    b.flags());
        }
        if (b.flags2() != 0) {
            return "Second raw batch field is " + b.flags2() + "; only0 is drawn.";
        }
        if (b.materialLayer() != 0) {
            return "Material layer is " + b.materialLayer() + "; only0 is drawn.";
        }
        return null;
    }

    private static ArrayDeclaration declaration(Model model, String name) {
        return model.declarations().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> error("INVALID", "Parsed model lacks its " + name + " declaration."));
    }
}
