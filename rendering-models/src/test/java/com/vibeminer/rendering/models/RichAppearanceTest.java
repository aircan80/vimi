package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RichAppearanceTest {
    private static MeshAppearance.Material additive() {
        return new MeshAppearance.Material(
                MeshAppearance.Combiner.DIFFUSE,
                List.of(
                        new MeshAppearance.TextureStage(
                                0, MeshAppearance.Coordinate.UV0, false, false)),
                MeshAppearance.Lighting.UNLIT,
                MeshAppearance.Blend.ADDITIVE);
    }

    @Test
    void rigidGroupsAreImmutableAndHaveConservativeRotationBounds() {
        var mesh =
                new PreviewMesh(
                        new float[] {2, -1, -1, 2, 1, -1, 2, 1, 1, 2, -1, 1},
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        int[] members = {0, 1, 2, 3};
        var card = new MeshAppearance.RigidBillboard(3, new MeshAppearance.Vec3(2, 0, 0), members);
        members[0] = 99;
        card.vertexIndices()[0] = 99;
        var value =
                new MeshAppearance(
                        mesh,
                        new float[8],
                        new float[0],
                        List.of(new MeshAppearance.Surface(0, additive())),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffffffff})),
                        List.of(card));
        assertEquals(0, value.billboardGroup(0));
        assertTrue(value.minimum(0) < 2 - Math.sqrt(2));
        assertTrue(value.maximum(0) > 2 + Math.sqrt(2));
        assertArrayEquals(new int[] {0, 1, 2, 3}, card.vertexIndices());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance(
                                mesh,
                                new float[8],
                                new float[0],
                                List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                                value.textures(),
                                List.of(card)));
    }

    @Test
    void environmentNormalValidationOnlyExaminesConsumedVertices() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1, 0, 4, 4},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        var material =
                new MeshAppearance.Material(
                        MeshAppearance.Combiner.OPAQUE_MOD2X_NA,
                        List.of(
                                new MeshAppearance.TextureStage(
                                        0, MeshAppearance.Coordinate.UV0, false, false),
                                new MeshAppearance.TextureStage(
                                        0, MeshAppearance.Coordinate.ENV_SPHERE, false, false)),
                        MeshAppearance.Lighting.LIT,
                        MeshAppearance.Blend.OPAQUE);
        float[] normal = {1, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0};
        var textures = List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffffffff}));
        new MeshAppearance(
                mesh,
                new float[8],
                normal,
                List.of(new MeshAppearance.Surface(0, material)),
                textures);
        normal[0] = 0;
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance(
                                mesh,
                                new float[8],
                                normal,
                                List.of(new MeshAppearance.Surface(0, material)),
                                textures));
    }

    @Test
    void materialMatrixRejectsPlausibleButUnreviewedCombinations() {
        var uv = new MeshAppearance.TextureStage(0, MeshAppearance.Coordinate.UV0, false, false);
        var env =
                new MeshAppearance.TextureStage(
                        1, MeshAppearance.Coordinate.ENV_SPHERE, false, false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance.Material(
                                MeshAppearance.Combiner.DIFFUSE,
                                List.of(uv),
                                MeshAppearance.Lighting.LIT,
                                MeshAppearance.Blend.ADDITIVE));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new MeshAppearance.Material(
                                MeshAppearance.Combiner.OPAQUE_MOD2X_NA,
                                List.of(uv, env),
                                MeshAppearance.Lighting.UNLIT,
                                MeshAppearance.Blend.OPAQUE));
        var legacy = new MeshAppearance.Surface(2, 0, true, false, true);
        assertEquals(MeshAppearance.Blend.ALPHA_KEY, legacy.material().blend());
        assertTrue(legacy.wrapU());
        assertTrue(legacy.alphaKey());
    }
}
