package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RichRendererTest {
    private static final StaticMeshRenderer.View VIEW =
            new StaticMeshRenderer.View(0, 0, 1, false, Set.of());

    private static PreviewMesh triangle() {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
    }

    private static MeshAppearance.Material material(
            MeshAppearance.Lighting light, MeshAppearance.Blend blend) {
        return new MeshAppearance.Material(
                MeshAppearance.Combiner.DIFFUSE,
                List.of(
                        new MeshAppearance.TextureStage(
                                0, MeshAppearance.Coordinate.UV0, false, false)),
                light,
                blend);
    }

    private static MeshAppearance.Material env() {
        return new MeshAppearance.Material(
                MeshAppearance.Combiner.OPAQUE_MOD2X_NA,
                List.of(
                        new MeshAppearance.TextureStage(
                                0, MeshAppearance.Coordinate.UV0, false, false),
                        new MeshAppearance.TextureStage(
                                1, MeshAppearance.Coordinate.ENV_SPHERE, false, false)),
                MeshAppearance.Lighting.LIT,
                MeshAppearance.Blend.OPAQUE);
    }

    private static int center(MeshAppearance appearance) {
        return StaticMeshRenderer.render(appearance, 256, 256, VIEW, () -> false)
                .argb()[128 * 256 + 128];
    }

    @Test
    void opaqueEnvironmentIgnoresBothAlphasAndClampsAfterLighting() {
        var appearance =
                new MeshAppearance(
                        triangle(),
                        new float[6],
                        new float[] {.72f, -.35f, .6f, .72f, -.35f, .6f, .72f, -.35f, .6f},
                        List.of(new MeshAppearance.Surface(0, env())),
                        List.of(
                                new MeshAppearance.Texture(1, 1, new int[] {0x0064503c}),
                                new MeshAppearance.Texture(1, 1, new int[] {0x008040ff})));
        assertEquals(0xff642878, center(appearance));
        var deferred =
                new MeshAppearance(
                        triangle(),
                        new float[6],
                        new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0},
                        List.of(new MeshAppearance.Surface(0, env())),
                        List.of(
                                new MeshAppearance.Texture(1, 1, new int[] {0xffcccccc}),
                                new MeshAppearance.Texture(1, 1, new int[] {0xffcccccc})));
        int sample = center(deferred);
        assertEquals(173, sample & 255);
        assertNotEquals(135, sample & 255);
    }

    @Test
    void unlitOpaqueDoesNotUseSourceNormalsAndIgnoresAlpha() {
        for (float[] normal :
                new float[][] {{1, 0, 0, 1, 0, 0, 1, 0, 0}, {0, 1, 0, 0, 1, 0, 0, 1, 0}}) {
            var appearance =
                    new MeshAppearance(
                            triangle(),
                            new float[6],
                            normal,
                            List.of(
                                    new MeshAppearance.Surface(
                                            0,
                                            material(
                                                    MeshAppearance.Lighting.UNLIT,
                                                    MeshAppearance.Blend.OPAQUE))),
                            List.of(new MeshAppearance.Texture(1, 1, new int[] {0x0064503c})));
            assertEquals(0xff64503c, center(appearance));
        }
    }

    private static MeshAppearance layered(
            float additiveDepth, float opaqueDepth, int alpha, boolean twoGlows) {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            additiveDepth,
                            -1,
                            -1,
                            additiveDepth,
                            1,
                            -1,
                            additiveDepth,
                            0,
                            1,
                            opaqueDepth,
                            -1,
                            -1,
                            opaqueDepth,
                            1,
                            -1,
                            opaqueDepth,
                            0,
                            1,
                            additiveDepth - .05f,
                            -1,
                            -1,
                            additiveDepth - .05f,
                            1,
                            -1,
                            additiveDepth - .05f,
                            0,
                            1
                        },
                        new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8},
                        List.of(
                                new PreviewMesh.Part(0, 0, 3),
                                new PreviewMesh.Part(0, 3, 3),
                                new PreviewMesh.Part(0, 6, 3)));
        var add = material(MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.ADDITIVE);
        var opaque =
                new MeshAppearance.Material(
                        MeshAppearance.Combiner.DIFFUSE,
                        List.of(
                                new MeshAppearance.TextureStage(
                                        1, MeshAppearance.Coordinate.UV0, false, false)),
                        MeshAppearance.Lighting.UNLIT,
                        MeshAppearance.Blend.OPAQUE);
        return new MeshAppearance(
                mesh,
                new float[18],
                List.of(
                        new MeshAppearance.Surface(0, add),
                        new MeshAppearance.Surface(1, opaque),
                        new MeshAppearance.Surface(2, twoGlows ? add : opaque)),
                List.of(
                        new MeshAppearance.Texture(1, 1, new int[] {alpha << 24 | 0x0064503c}),
                        new MeshAppearance.Texture(1, 1, new int[] {0xff0a141e})));
    }

    @Test
    void additiveRunsAfterOpaqueUsesFractionalAlphaAndDoesNotWriteDepth() {
        assertEquals(0xff3c3c3c, center(layered(.2f, 0, 128, false)));
        assertEquals(
                0xff0a141e,
                center(layered(-.2f, 0, 128, false)),
                "Opaque geometry occludes glow behind it");
        assertEquals(
                0xff3c3c3c, center(layered(0, 0, 128, false)), "Coplanar additive uses LEQUAL");
        assertEquals(0xff0a141e, center(layered(.2f, 0, 0, false)));
        assertEquals(0xff23282d, center(layered(.2f, 0, 64, false)), "Low alpha is not a cutout");
        assertEquals(
                0xff6e645a,
                center(layered(.2f, 0, 128, true)),
                "Near glow cannot occlude a second farther glow");
    }

    @Test
    void equalDepthOpaqueMaterialsKeepFirstSectionOrdinalRatherThanSurfaceListOrder() {
        var mesh =
                new PreviewMesh(
                        triangle().positions(),
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3), new PreviewMesh.Part(0, 0, 3)));
        var red = material(MeshAppearance.Lighting.UNLIT, MeshAppearance.Blend.OPAQUE);
        var blue =
                new MeshAppearance.Material(
                        MeshAppearance.Combiner.DIFFUSE,
                        List.of(
                                new MeshAppearance.TextureStage(
                                        1, MeshAppearance.Coordinate.UV0, false, false)),
                        MeshAppearance.Lighting.UNLIT,
                        MeshAppearance.Blend.OPAQUE);
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[6],
                        List.of(
                                new MeshAppearance.Surface(1, blue),
                                new MeshAppearance.Surface(0, red)),
                        List.of(
                                new MeshAppearance.Texture(1, 1, new int[] {0xffff0000}),
                                new MeshAppearance.Texture(1, 1, new int[] {0xff0000ff})));
        assertEquals(
                0xffff0000,
                center(appearance),
                "Legacy LESS preserves first section ordinal at equal depth");
    }

    @Test
    void texturesOffMakesAdditiveOpaqueNeutralInsteadOfWhiteAdditiveBlocks() {
        var appearance = layered(.2f, 0, 0, true);
        int diagnostic =
                StaticMeshRenderer.render(appearance, 256, 256, VIEW, () -> false, true, false)
                        .argb()[128 * 256 + 128];
        assertEquals(255, diagnostic >>> 24);
        assertTrue((diagnostic >>> 16 & 255) > 100 && (diagnostic & 255) > 80);
        assertNotEquals(
                0xff0a141e, diagnostic, "Alpha zero does not hide texture-off diagnostic geometry");
    }

    @Test
    void rendererInterpolatesVertexEnvironmentCoordinatesRatherThanFragmentNormals() {
        var mesh =
                new PreviewMesh(
                        new float[] {0, -1, -1, 1, 1, -1, -1, 0, 1},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        float h = (float) Math.sqrt(.5);
        float[] normals = {1, 0, 0, h, h, 0, h, 0, h};
        int[] gradient = new int[256 * 256];
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                gradient[y * 256 + x] = 0xff000000 | x << 16 | y << 8 | 0x40;
            }
        }
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[6],
                        normals,
                        List.of(new MeshAppearance.Surface(0, env())),
                        List.of(
                                new MeshAppearance.Texture(1, 1, new int[] {0xff808080}),
                                new MeshAppearance.Texture(256, 256, gradient)));
        double distance = Math.sqrt(3) * 3.2, focal = 320;
        double[] xs = {128 - 320 / distance, 128 + 320 / (distance - 1), 128};
        double[] ys = {
            135.68 + 320 / distance, 135.68 + 320 / (distance - 1), 135.68 - 320 / (distance + 1)
        };
        int x = (int) ((xs[0] + xs[1] + xs[2]) / 3), y = (int) ((ys[0] + ys[1] + ys[2]) / 3);
        double denominator = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2]);
        double a =
                ((ys[1] - ys[2]) * (x + .5 - xs[2]) + (xs[2] - xs[1]) * (y + .5 - ys[2]))
                        / denominator;
        double
                b =
                        ((ys[2] - ys[0]) * (x + .5 - xs[2]) + (xs[0] - xs[2]) * (y + .5 - ys[2]))
                                / denominator,
                c = 1 - a - b;
        double sum = a / distance + b / (distance - 1) + c / (distance + 1);
        double[] q = {a / distance / sum, b / (distance - 1) / sum, c / (distance + 1) / sum};
        double[][] p = {{-1, -1, -distance}, {1, -1, 1 - distance}, {0, 1, -1 - distance}};
        double[][] n = {{0, 0, 1}, {h, 0, h}, {0, h, h}};
        double u = 0, v = 0, light = 0;
        for (int i = 0; i < 3; i++) {
            double[] uv = sphere(p[i], n[i]);
            u += q[i] * uv[0];
            v += q[i] * uv[1];
            double nx = normals[i * 3], ny = normals[i * 3 + 1], nz = normals[i * 3 + 2];
            light +=
                    q[i]
                            * (.28
                                    + .72
                                            * Math.min(
                                                    1,
                                                    Math.abs(.72 * nx - .35 * ny + .6 * nz)
                                                            / Math.sqrt(
                                                                    nx * nx + ny * ny + nz * nz)));
        }
        int expected =
                0xff000000
                        | (int) (2 * 128 * (int) (u * 256) / 255.0 * light) << 16
                        | (int) (2 * 128 * (int) (v * 256) / 255.0 * light) << 8
                        | (int) (2 * 128 * 64 / 255.0 * light);
        int actual =
                StaticMeshRenderer.render(appearance, 256, 256, VIEW, () -> false)
                        .argb()[y * 256 + x];
        assertEquals(
                expected,
                actual,
                "Independent analytic vertex UVs and perspective barycentrics determine the texel");
    }

    @Test
    void cameraFacingCardsKeepCornerUvsAndPivotAcrossCameraChanges() {
        var mesh =
                new PreviewMesh(
                        new float[] {
                            .302f, -.4f, .1f, .302f, 1.2f, .1f, .302f, 1.2f, .9f, .302f, -.4f, .9f
                        },
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(0, 0, 6)));
        var card =
                new MeshAppearance.RigidBillboard(
                        3, new MeshAppearance.Vec3(.3f, .4f, .5f), new int[] {0, 1, 2, 3});
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 1, 1, 1, 1, 0, 0, 0},
                        new float[0],
                        List.of(
                                new MeshAppearance.Surface(
                                        0,
                                        material(
                                                MeshAppearance.Lighting.UNLIT,
                                                MeshAppearance.Blend.ADDITIVE))),
                        List.of(
                                new MeshAppearance.Texture(
                                        2,
                                        2,
                                        new int[] {
                                            0xff320000, 0xff003200, 0xff000032, 0xff323232
                                        })),
                        List.of(card));
        for (double yaw : new double[] {0, .7, -2.5}) {
            for (double pitch : new double[] {0, .6, -.8}) {
                var view = new StaticMeshRenderer.View(yaw, pitch, 1, false, Set.of());
                int[] frame =
                        StaticMeshRenderer.render(appearance, 256, 256, view, () -> false).argb();
                int top = 126, bottom = 146, left = 117, right = 139;
                int topBase = 18 + (int) (10.0 * top / 256),
                        bottomBase = 18 + (int) (10.0 * bottom / 256);
                assertEquals(topBase + 50, frame[top * 256 + left] >>> 16 & 255);
                assertEquals(topBase + 8, frame[top * 256 + left] >>> 8 & 255);
                assertEquals(topBase + 8 + 50, frame[top * 256 + right] >>> 8 & 255);
                assertEquals(bottomBase + 10 + 50, frame[bottom * 256 + left] & 255);
                assertEquals(bottomBase + 50, frame[bottom * 256 + right] >>> 16 & 255);
            }
        }
        var edges = new StaticMeshRenderer.View(.7, .6, 1, true, Set.of());
        assertTrue(
                java.util.Arrays.stream(
                                StaticMeshRenderer.render(appearance, 256, 256, edges, () -> false)
                                        .argb())
                        .anyMatch(pixel -> pixel == 0xffabc5b1),
                "Edges use opaque diagnostic color after additive fill");
    }

    @Test
    void richTrianglesClipAtNearPlaneWithoutChangingLegacyWholeTriangleSkip() {
        var mesh =
                new PreviewMesh(
                        new float[] {-1, -1, -1, 1, 1, -1, 1, 0, 1},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[6],
                        List.of(
                                new MeshAppearance.Surface(
                                        0,
                                        material(
                                                MeshAppearance.Lighting.UNLIT,
                                                MeshAppearance.Blend.OPAQUE))),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
        var close = new StaticMeshRenderer.View(0, 0, 8, false, Set.of());
        int[] rich = StaticMeshRenderer.render(appearance, 256, 256, close, () -> false).argb();
        assertTrue(
                java.util.Arrays.stream(rich).anyMatch(pixel -> pixel == 0xffff0000),
                "Clipped visible polygon remains readable");
        var hidden = new StaticMeshRenderer.View(0, 0, 8, false, Set.of(99));
        assertArrayEquals(
                StaticMeshRenderer.render(mesh, 256, 256, hidden, () -> false).argb(),
                StaticMeshRenderer.render(mesh, 256, 256, close, () -> false).argb(),
                "Legacy ordinary path still omits the whole near-crossing triangle");
    }

    private static double[] sphere(double[] p, double[] n) {
        double lp = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]),
                ln = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        double dot = (p[0] * n[0] + p[1] * n[1] + p[2] * n[2]) / lp / ln;
        double rx = p[0] / lp - 2 * n[0] / ln * dot,
                ry = p[1] / lp - 2 * n[1] / ln * dot,
                rz = p[2] / lp - 2 * n[2] / ln * dot;
        double m = 2 * Math.sqrt(rx * rx + ry * ry + (rz + 1) * (rz + 1));
        return new double[] {.5 + rx / m, .5 + ry / m};
    }
}
