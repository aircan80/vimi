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
        var raw = bytes(partial);
        var b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
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
}
