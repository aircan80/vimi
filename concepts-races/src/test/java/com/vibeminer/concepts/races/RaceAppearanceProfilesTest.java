package com.vibeminer.concepts.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.AssetSource;
import com.vibeminer.formats.models.M2Appearance;
import com.vibeminer.formats.models.M2ModelReader;
import com.vibeminer.formats.models.M2Types;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RaceAppearanceProfilesTest {
    // Reuse the repository's authored constant-opacity M2, never a game asset.
    private static byte[] bytes(boolean partial) throws Exception {
        try (var in =
                RaceAppearanceProfilesTest.class.getResourceAsStream(
                        partial
                                ? "/golden/dwarf-partial-opacity.m2"
                                : "/golden/dwarf-opacity.m2")) {
            return in.readAllBytes();
        }
    }

    private record Fixture(M2Types.Model model, M2Appearance.ModelFacts facts) {}

    private static Fixture fixture(
            boolean hair,
            boolean partial,
            int version,
            int materialFlags,
            int blend,
            int textureFlags,
            int transform,
            boolean coordinates)
            throws Exception {
        return fixture(
                hair,
                partial,
                version,
                materialFlags,
                blend,
                textureFlags,
                transform,
                coordinates,
                false);
    }

    /** With {@code external}, the one sequence's keys are marked stored outside the model. */
    private static Fixture fixture(
            boolean hair,
            boolean partial,
            int version,
            int materialFlags,
            int blend,
            int textureFlags,
            int transform,
            boolean coordinates,
            boolean external)
            throws Exception {
        var raw = bytes(partial);
        var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (external) {
            int sequence = b.getInt(8 + 32) + 8;
            b.putInt(sequence + 12, b.getInt(sequence + 12) & ~0x20);
        }
        b.putInt(12, version);
        int tex = b.getInt(8 + 84) + 8;
        b.putInt(tex, hair ? 6 : 1);
        b.putInt(tex + 4, textureFlags);
        int material = b.getInt(8 + 116) + 8;
        b.putShort(material, (short) materialFlags).putShort(material + 2, (short) blend);
        b.putShort(b.getInt(8 + 156) + 8, (short) transform);
        if (coordinates) {
            b.putInt(8 + 136, 1).putInt(8 + 140, b.getInt(8 + 132));
        }
        var model = M2ModelReader.read(AssetSource.bytes(raw)).join();
        return new Fixture(
                model,
                M2Appearance.readModel(AssetSource.bytes(raw), model, Cancellation.NONE).join());
    }

    private static M2Types.Batch batch(int shader) {
        return new M2Types.Batch(16, 0, shader, 0, 0, -1, 0, 0, 1, 0, 65535, 0, 0);
    }

    private static boolean admit(RaceAppearanceProfiles.Profile profile, Fixture f, int shader) {
        return RaceAppearanceProfiles.dwarfDraw(profile, f.model(), batch(shader), f.facts(), 1);
    }

    @Test
    void exactBodyAndSexSpecificHairMatrixUsesExistingOpaqueAndCutoutMaterials() throws Exception {
        var male = RaceAppearanceProfiles.Profile.DWARF_MALE;
        var female = RaceAppearanceProfiles.Profile.DWARF_FEMALE;
        var body = fixture(false, false, 272, 0, 0, 0, 65535, false);
        assertTrue(admit(male, body, 0));
        assertTrue(admit(female, body, 0));
        var beard = fixture(true, false, 272, 4, 1, 3, 65535, false);
        assertTrue(admit(male, beard, 16));
        assertFalse(admit(female, beard, 16));
        assertFalse(admit(male, beard, 0));
        var hair = fixture(true, false, 272, 4, 0, 3, 65535, false);
        assertTrue(admit(female, hair, 0));
        assertFalse(admit(male, hair, 0));
        assertFalse(admit(RaceAppearanceProfiles.Profile.LEGACY, beard, 16));
        assertFalse(admit(null, beard, 16));
    }

    @Test
    void nightElfOpaqueProfilesRejectCutoutAndUnprovenDrawState() throws Exception {
        for (var profile :
                List.of(
                        RaceAppearanceProfiles.Profile.NIGHT_ELF_MALE,
                        RaceAppearanceProfiles.Profile.NIGHT_ELF_FEMALE)) {
            var body = fixture(false, false, 272, 0, 0, 0, 65535, false);
            var hair = fixture(true, false, 272, 4, 0, 3, 65535, false);
            assertTrue(
                    RaceAppearanceProfiles.reviewedDraw(
                            profile, body.model(), batch(0), body.facts(), 1));
            assertTrue(
                    RaceAppearanceProfiles.reviewedDraw(
                            profile, hair.model(), batch(0), hair.facts(), 1));
            assertFalse(
                    RaceAppearanceProfiles.dwarfDraw(
                            profile, hair.model(), batch(0), hair.facts(), 1));
            for (var invalid :
                    List.of(
                            fixture(true, false, 274, 4, 0, 3, 65535, false),
                            fixture(true, false, 272, 0, 0, 3, 65535, false),
                            fixture(true, false, 272, 5, 0, 3, 65535, false),
                            fixture(true, false, 272, 4, 1, 3, 65535, false),
                            fixture(true, false, 272, 4, 0, 1, 65535, false),
                            fixture(true, false, 272, 4, 0, 3, 0, false),
                            fixture(true, false, 272, 4, 0, 3, 65535, true),
                            fixture(true, true, 272, 4, 0, 3, 65535, false))) {
                assertFalse(
                        RaceAppearanceProfiles.reviewedDraw(
                                profile, invalid.model(), batch(0), invalid.facts(), 1));
            }
            assertFalse(
                    RaceAppearanceProfiles.reviewedDraw(
                            profile, hair.model(), batch(16), hair.facts(), 1));
            assertFalse(
                    RaceAppearanceProfiles.reviewedDraw(
                            profile, hair.model(), batch(0), body.facts(), 1));
            assertFalse(
                    RaceAppearanceProfiles.reviewedDraw(
                            profile, hair.model(), batch(0), hair.facts(), 2));
            for (int field : new int[] {0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12}) {
                int[] values = {16, 0, 0, 0, 0, -1, 0, 0, 1, 0, 65535, 0, 0};
                values[field] = field == 5 || field == 10 ? 0 : values[field] + 1;
                var invalid =
                        new M2Types.Batch(
                                values[0],
                                values[1],
                                values[2],
                                values[3],
                                values[4],
                                values[5],
                                values[6],
                                values[7],
                                values[8],
                                values[9],
                                values[10],
                                values[11],
                                values[12]);
                assertFalse(
                        RaceAppearanceProfiles.reviewedDraw(
                                profile, hair.model(), invalid, hair.facts(), 1));
            }
        }
        var hair = fixture(true, false, 272, 4, 0, 3, 65535, false);
        assertFalse(
                RaceAppearanceProfiles.reviewedDraw(null, hair.model(), batch(0), hair.facts(), 1));
        assertFalse(
                RaceAppearanceProfiles.reviewedDraw(
                        RaceAppearanceProfiles.Profile.LEGACY,
                        hair.model(),
                        batch(0),
                        hair.facts(),
                        1));
    }

    @Test
    void externallyStoredWeightsAreOpaqueOnlyWhereTheAssumptionIsStated() throws Exception {
        var profile = RaceAppearanceProfiles.Profile.NIGHT_ELF_MALE;
        var external = fixture(true, false, 272, 4, 0, 3, 65535, false, true);
        assertEquals(List.of(false), external.facts().staticWeights());
        assertEquals(List.of(true), external.facts().externalWeights());
        assertFalse(
                RaceAppearanceProfiles.reviewedDraw(
                        profile, external.model(), batch(0), external.facts(), 1));
        assertFalse(
                RaceAppearanceProfiles.reviewedDraw(
                        profile, external.model(), batch(0), external.facts(), 1, false));
        assertTrue(
                RaceAppearanceProfiles.reviewedDraw(
                        profile, external.model(), batch(0), external.facts(), 1, true));
        assertTrue(RaceAppearanceProfiles.opaqueWeight(external.facts(), 0, true));
        assertFalse(RaceAppearanceProfiles.opaqueWeight(external.facts(), 0, false));
        assertFalse(RaceAppearanceProfiles.opaqueWeight(external.facts(), 1, true));
        assertFalse(RaceAppearanceProfiles.opaqueWeight(null, 0, true));
        // A track proven partial by a key stored in the model is never assumed opaque.
        var partial = fixture(true, true, 272, 4, 0, 3, 65535, false);
        assertEquals(List.of(false), partial.facts().externalWeights());
        assertFalse(
                RaceAppearanceProfiles.reviewedDraw(
                        profile, partial.model(), batch(0), partial.facts(), 1, true));
        // A proven full track needs no assumption.
        var proven = fixture(true, false, 272, 4, 0, 3, 65535, false);
        assertTrue(RaceAppearanceProfiles.opaqueWeight(proven.facts(), 0, false));
        // The static character preview states the assumption; an NPC plan never does.
        var preview =
                new RaceAppearancePlan.Plan(
                        List.of(), List.of(), List.of(), RaceAppearanceProfiles.Profile.LEGACY);
        var npc =
                new RaceAppearancePlan.Plan(
                        List.of(),
                        List.of(),
                        List.of(),
                        RaceAppearanceProfiles.Profile.LEGACY,
                        true);
        assertTrue(RaceAppearance.assumesExternalWeights(preview));
        assertFalse(RaceAppearance.assumesExternalWeights(npc));
    }

    @Test
    void onlyEnvironmentEffectZeroWithTwoTexturesIsDrawnByItsFirstStage() {
        assertTrue(RaceAppearanceProfiles.environmentFirstStage(twoTextures(2, 0x8000)));
        assertFalse(RaceAppearanceProfiles.environmentFirstStage(twoTextures(1, 0x8000)));
        assertFalse(RaceAppearanceProfiles.environmentFirstStage(twoTextures(3, 0x8000)));
        assertFalse(RaceAppearanceProfiles.environmentFirstStage(twoTextures(2, 0x8001)));
        assertFalse(RaceAppearanceProfiles.environmentFirstStage(twoTextures(2, 0)));
    }

    private static M2Types.Batch twoTextures(int count, int shader) {
        return new M2Types.Batch(16, 0, shader, 0, 0, -1, 0, 0, count, 0, 65535, 0, 0);
    }

    @Test
    void admittedMaleHairCutoutDiscards127WithoutDepthAndAccepts128BeforeBody() throws Exception {
        var hair = fixture(true, false, 272, 4, 1, 3, 65535, false);
        var body = fixture(false, false, 272, 0, 0, 0, 65535, false);
        assertTrue(admit(RaceAppearanceProfiles.Profile.DWARF_MALE, hair, 16));
        assertTrue(admit(RaceAppearanceProfiles.Profile.DWARF_MALE, body, 0));
        var hairSurface =
                RaceAppearanceProfiles.surface(
                        batch(16),
                        hair.model().materials().getFirst(),
                        hair.model().textures().getFirst(),
                        0);
        var bodyBatch = new M2Types.Batch(16, 0, 0, 1, 0, -1, 0, 0, 1, 0, 65535, 0, 0);
        var bodySurface =
                RaceAppearanceProfiles.surface(
                        bodyBatch,
                        body.model().materials().getFirst(),
                        body.model().textures().getFirst(),
                        1);
        var geometry =
                new PreviewMesh(
                        new float[] {
                            .2f, -1, -1, .2f, 1, -1, .2f, 0, 1, -.2f, -1, -1, -.2f, 1, -1, -.2f, 0,
                            1
                        },
                        new int[] {0, 1, 2, 3, 4, 5},
                        List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 3, 3)));
        for (int alpha : new int[] {127, 128}) {
            var appearance =
                    new MeshAppearance(
                            geometry,
                            new float[12],
                            List.of(hairSurface, bodySurface),
                            List.of(
                                    new MeshAppearance.Texture(
                                            1, 1, new int[] {alpha << 24 | 0x00ff00}),
                                    new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
            int pixel =
                    StaticMeshRenderer.render(
                                    appearance,
                                    200,
                                    200,
                                    new StaticMeshRenderer.View(0, 0, 1, false, Set.of(0)),
                                    () -> false)
                            .argb()[100 * 200 + 100];
            assertTrue(((pixel >>> (alpha == 127 ? 16 : 8)) & 255) > 100);
            assertEquals(0, pixel & (alpha == 127 ? 0x00ffff : 0xff00ff));
        }
    }

    @Test
    void admittedNightElfOpaqueHairIgnoresSourceAlphaAndKeepsItsDepth() throws Exception {
        var hair = fixture(true, false, 272, 4, 0, 3, 65535, false);
        assertTrue(
                RaceAppearanceProfiles.reviewedDraw(
                        RaceAppearanceProfiles.Profile.NIGHT_ELF_MALE,
                        hair.model(),
                        batch(0),
                        hair.facts(),
                        1));
        var hairSurface =
                RaceAppearanceProfiles.surface(
                        batch(0),
                        hair.model().materials().getFirst(),
                        hair.model().textures().getFirst(),
                        0);
        var geometry =
                new PreviewMesh(
                        new float[] {
                            .2f, -1, -1, .2f, 1, -1, .2f, 0, 1, -.2f, -1, -1, -.2f, 1, -1, -.2f, 0,
                            1
                        },
                        new int[] {0, 1, 2, 3, 4, 5},
                        List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 3, 3)));
        var appearance =
                new MeshAppearance(
                        geometry,
                        new float[12],
                        List.of(hairSurface, new MeshAppearance.Surface(1, 1, false, false, false)),
                        List.of(
                                new MeshAppearance.Texture(1, 1, new int[] {0x0000ff00}),
                                new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
        int pixel =
                StaticMeshRenderer.render(
                                appearance,
                                200,
                                200,
                                new StaticMeshRenderer.View(0, 0, 1, false, Set.of(0)),
                                () -> false)
                        .argb()[100 * 200 + 100];
        assertTrue(((pixel >>> 8) & 255) > 100);
        assertEquals(0, pixel & 0xff00ff);
        assertEquals(255, pixel >>> 24);
    }

    @Test
    void opacityMustBeFullAndBoundToTheIdenticalParsedModel() throws Exception {
        var profile = RaceAppearanceProfiles.Profile.DWARF_MALE;
        var full = fixture(true, false, 272, 4, 1, 3, 65535, false);
        var other = fixture(true, false, 272, 4, 1, 3, 65535, false);
        assertFalse(
                RaceAppearanceProfiles.dwarfDraw(
                        profile, full.model(), batch(16), other.facts(), 1));
        assertFalse(RaceAppearanceProfiles.dwarfDraw(profile, full.model(), batch(16), null, 1));
        assertFalse(admit(profile, fixture(true, true, 272, 4, 1, 3, 65535, false), 16));
        assertFalse(
                RaceAppearanceProfiles.dwarfDraw(
                        profile, full.model(), batch(16), full.facts(), 2));
    }

    @Test
    void unsupportedVersionFlagsBlendCoordinatesAndTransformsRemainUnbound() throws Exception {
        var profile = RaceAppearanceProfiles.Profile.DWARF_MALE;
        for (var f :
                new Fixture[] {
                    fixture(true, false, 274, 4, 1, 3, 65535, false),
                    fixture(true, false, 272, 5, 1, 3, 65535, false),
                    fixture(true, false, 272, 4, 2, 3, 65535, false),
                    fixture(true, false, 272, 4, 1, 1, 65535, false),
                    fixture(true, false, 272, 4, 1, 3, 0, false),
                    fixture(true, false, 272, 4, 1, 3, 65535, true)
                }) {
            assertFalse(admit(profile, f, 16));
        }
        var f = fixture(true, false, 272, 4, 1, 3, 65535, false);
        var original = batch(16);
        for (int field : new int[] {0, 1, 4, 5, 6, 7, 8, 9, 10, 11, 12}) {
            int[] values = {16, 0, 16, 0, 0, -1, 0, 0, 1, 0, 65535, 0, 0};
            values[field] =
                    switch (field) {
                        case 5 -> 0;
                        case 10 -> 0;
                        default -> values[field] + 1;
                    };
            var changed =
                    new M2Types.Batch(
                            values[0],
                            values[1],
                            values[2],
                            values[3],
                            values[4],
                            values[5],
                            values[6],
                            values[7],
                            values[8],
                            values[9],
                            values[10],
                            values[11],
                            values[12]);
            assertFalse(
                    RaceAppearanceProfiles.dwarfDraw(profile, f.model(), changed, f.facts(), 1),
                    "field " + field);
        }
        assertTrue(RaceAppearanceProfiles.dwarfDraw(profile, f.model(), original, f.facts(), 1));
    }

    private static M2Types.Batch eyeBatch(int shader, int count) {
        return new M2Types.Batch(16, 0, shader, 0, 0, -1, 0, 0, count, 0, 65535, 0, 0);
    }

    @Test
    void theEyeDrawIsTwoTexturesOfTypeNineteenModAddOverT1T2() {
        var eyes = new M2Types.Texture(0, 19, 0, null, null);
        // 0x4013 (16403), as the race models' eyes record it: Combiners_Mod_Add over Diffuse_T1_T2.
        assertTrue(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x4013, 2), eyes));
        assertTrue(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x0013, 2), eyes));
        // Another texture type, one texture, another combiner or vertex shader, or a table
        // effect (bit 0x8000): not the eye draw.
        assertFalse(
                RaceAppearanceProfiles.eyesFirstStage(
                        eyeBatch(0x4013, 2), new M2Types.Texture(0, 6, 0, null, null)));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x4013, 1), eyes));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x4011, 2), eyes));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x4003, 2), eyes));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x4093, 2), eyes));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x401B, 2), eyes));
        assertFalse(RaceAppearanceProfiles.eyesFirstStage(eyeBatch(0x8013, 2), eyes));
    }
}
