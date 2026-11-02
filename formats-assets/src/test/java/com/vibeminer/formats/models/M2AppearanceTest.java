package com.vibeminer.formats.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class M2AppearanceTest {
    private record Parsed(
            byte[] modelBytes, byte[] skinBytes, M2Types.Model model, M2Types.GeometrySkin skin) {}

    private static Parsed parsed(String name) {
        byte[] mb = M2ReadersTest.golden("appearance-" + name + ".m2"),
                sb = M2ReadersTest.golden("appearance-" + name + ".skin");
        var model = M2ModelReader.read(AssetSource.bytes(mb)).join();
        var skin =
                M2GeometrySkinReader.read(AssetSource.bytes(sb), model, null, Cancellation.NONE)
                        .join();
        return new Parsed(mb, sb, model, skin);
    }

    private static M2Appearance.Plan plan(Parsed p) {
        return M2Appearance.read(
                        AssetSource.bytes(p.modelBytes),
                        p.model,
                        AssetSource.bytes(p.skinBytes),
                        p.skin,
                        Cancellation.NONE)
                .join();
    }

    @ParameterizedTest
    @ValueSource(strings = {"simple", "no-keys"})
    void csharpGoldenConstantAndNoKeyWeightsAdmitCreatureVariation(String name) {
        Parsed p = parsed(name);
        var facts =
                M2Appearance.readModel(AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                        .join();
        assertEquals(List.of(true), facts.staticWeights());
        var plan = plan(p);
        assertNull(plan.decline());
        assertEquals(1, plan.draws().size());
        var texture = plan.textures().getFirst();
        assertEquals(M2Appearance.TextureBinding.CREATURE_VARIATION, texture.binding());
        assertEquals(0, texture.variation());
        assertEquals(0, texture.fileDataId());
        assertTrue(texture.wrapU());
        assertTrue(texture.wrapV());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero-weight", "external-weight"})
    void partialOpacityAndExternalAnimationRemainUnproven(String name) {
        Parsed p = parsed(name);
        var facts =
                M2Appearance.readModel(AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                        .join();
        assertEquals(List.of(false), facts.staticWeights());
        var plan = plan(p);
        assertEquals(M2Appearance.Refusal.TEXTURE_WEIGHT, plan.decline().reason());
        assertTrue(plan.draws().isEmpty());
        assertTrue(plan.textures().isEmpty());
    }

    @Test
    void csharpGoldenTwoStageEnvCombinerAllowsOneWeightEntry() {
        Parsed p = parsed("two-stage");
        var plan = plan(p);
        assertNull(plan.decline());
        var draw = plan.draws().getFirst();
        assertEquals(M2Combiners.Combiner.OPAQUE_MOD2X_NA_ALPHA, draw.combiner());
        assertEquals(M2Combiners.Coordinate.UV0, draw.coordinate());
        assertEquals(M2Combiners.Coordinate.ENV, draw.secondCoordinate());
        assertEquals(1, draw.secondTextureOrdinal());
        assertEquals(1234, plan.textures().getFirst().fileDataId());
        assertEquals(2, plan.textures().size());
    }

    @Test
    void csharpGoldenAlphaKeyUsesTextureAlphaAndRenderBits() {
        var plan = plan(parsed("alpha-key"));
        assertNull(plan.decline());
        var draw = plan.draws().getFirst();
        assertEquals(M2Combiners.Combiner.MOD, draw.combiner());
        assertTrue(draw.alphaKey());
        assertTrue(draw.twoSided());
        assertTrue(draw.depthTest());
        assertTrue(draw.depthWrite());
        assertEquals(128, M2Appearance.ALPHA_REFERENCE);
    }

    @Test
    void blendedOnlySurfaceAndUnknownRenderBitsRemainDistinctDeclines() {
        var blend = plan(parsed("blended"));
        assertEquals(M2Appearance.Refusal.BLEND_UNSUPPORTED, blend.decline().reason());
        assertTrue(blend.draws().isEmpty());
        var render = plan(parsed("bad-render"));
        assertEquals(M2Appearance.Refusal.MATERIAL_UNSUPPORTED, render.decline().reason());
        assertTrue(render.decline().message().contains("0x02"));
    }

    @Test
    void sharedWeightSliceArraysReuseProofWhileDistinctArraysExhaustGlobalBound() {
        for (String name : List.of("shared-empty-slices", "unique-empty-slices")) {
            Parsed p = parsed(name);
            var facts =
                    M2Appearance.readModel(
                                    AssetSource.bytes(p.modelBytes), p.model, Cancellation.NONE)
                            .join();
            assertEquals(32, facts.staticWeights().size());
            for (int i = 0; i < 32; i++) {
                assertEquals(name.startsWith("shared") || i < 16, facts.staticWeights().get(i));
            }
            assertNull(plan(p).decline());
        }
    }

    @Test
    void textureLookupMustNameADeclaredTexture() {
        Parsed base = parsed("two-stage");
        byte[] model = base.modelBytes.clone();
        int combo = M2ReadersTest.get(model, 8 + 132) + 8;
        M2ReadersTest.word(model, combo, 99);
        var parsedModel = M2ModelReader.read(AssetSource.bytes(model)).join();
        var skin =
                M2GeometrySkinReader.read(
                                AssetSource.bytes(base.skinBytes),
                                parsedModel,
                                null,
                                Cancellation.NONE)
                        .join();
        var result =
                M2Appearance.read(
                                AssetSource.bytes(model),
                                parsedModel,
                                AssetSource.bytes(base.skinBytes),
                                skin,
                                Cancellation.NONE)
                        .join();
        assertEquals(M2Appearance.Refusal.MATERIAL_UNSUPPORTED, result.decline().reason());
        assertFalse(result.decline().message().isBlank());
    }
}
