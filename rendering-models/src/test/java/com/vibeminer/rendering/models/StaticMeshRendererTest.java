package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class StaticMeshRendererTest {
    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, -1, -1, 0, 1, -1, 0, 0, 1},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(0, 0, 3)));
    }

    @Test
    void rendersRealTriangleAndViewChangesItsProjection() {
        var frame =
                StaticMeshRenderer.render(
                        mesh(), 200, 200, StaticMeshRenderer.View.initial(), () -> false);
        int center = frame.argb()[100 * 200 + 100];
        assertTrue(center != frame.argb()[0]);
        var hidden =
                StaticMeshRenderer.render(
                        mesh(),
                        200,
                        200,
                        new StaticMeshRenderer.View(0, 0, 1, false, Set.of(99)),
                        () -> false);
        assertTrue(center != hidden.argb()[100 * 200 + 100]);
        var turn =
                StaticMeshRenderer.render(
                        mesh(),
                        200,
                        200,
                        new StaticMeshRenderer.View(Math.PI / 2, 0, 1, false, Set.of()),
                        () -> false);
        assertTrue(
                java.util.Arrays.stream(turn.argb()).filter(pixel -> pixel == center).count()
                        < java.util.Arrays.stream(frame.argb())
                                .filter(pixel -> pixel == center)
                                .count());
    }

    @Test
    void refusesInvalidGeometryCameraAndOversizeFramesAndHonorsCancellation() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewMesh(
                                new float[] {Float.NaN, 0, 0},
                                new int[] {0, 0, 0},
                                List.of(new PreviewMesh.Part(0, 0, 3))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new PreviewMesh(
                                new float[] {0, 0, 0},
                                new int[] {0, 1, 0},
                                List.of(new PreviewMesh.Part(0, 0, 3))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StaticMeshRenderer.render(
                                mesh(),
                                2000,
                                2000,
                                StaticMeshRenderer.View.initial(),
                                () -> false));
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () ->
                        StaticMeshRenderer.render(
                                mesh(), 200, 200, StaticMeshRenderer.View.initial(), () -> true));
    }

    @Test
    void textureCoordinatesSampleDiffuseAndRespectAlphaKey() {
        var geometry = mesh();
        float[] uv = {0, 0, 1, 0, 0.5f, 1};
        var binding = new MeshAppearance.Surface(0, 0, false, false, false);
        var red =
                new MeshAppearance(
                        geometry,
                        uv,
                        List.of(binding),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
        var frame =
                StaticMeshRenderer.render(
                        red, 200, 200, StaticMeshRenderer.View.initial(), () -> false);
        int center = frame.argb()[100 * 200 + 100];
        assertTrue((center >>> 16 & 255) > 0);
        assertEquals(0, center & 0xffff);
        var smooth =
                new MeshAppearance(
                        geometry,
                        uv,
                        new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                        List.of(binding),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
        var smoothFrame =
                StaticMeshRenderer.render(
                        smooth, 200, 200, StaticMeshRenderer.View.initial(), () -> false);
        assertTrue(center != smoothFrame.argb()[100 * 200 + 100]);
        var cutout =
                new MeshAppearance(
                        geometry,
                        uv,
                        List.of(new MeshAppearance.Surface(0, 0, false, false, true)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0x00ff0000})));
        var hidden =
                StaticMeshRenderer.render(
                        cutout, 200, 200, StaticMeshRenderer.View.initial(), () -> false);
        assertTrue(center != hidden.argb()[100 * 200 + 100]);
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeshAppearance(geometry, new float[2], List.of(), List.of()));
    }

    @Test
    void qualifiedMaterialsUseSectionOrdinalsWhenEveryNativePartIdIsZero() {
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
                        List.of(new MeshAppearance.Surface(1, 0, false, false, false)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
        var view = new StaticMeshRenderer.View(0, 0, 1, false, Set.of(0));
        var legacy = StaticMeshRenderer.render(appearance, 200, 200, view, () -> false);
        var explicitRaw = StaticMeshRenderer.render(appearance, 200, 200, view, () -> false, false);
        assertArrayEquals(legacy.argb(), explicitRaw.argb());
        var qualified = StaticMeshRenderer.render(appearance, 200, 200, view, () -> false, true);
        int at = 100 * 200 + 100, red = qualified.argb()[at];
        assertTrue((red >>> 16 & 255) > 100);
        assertEquals(0, red & 0xffff);
        assertNotEquals(legacy.argb()[at], red, "Unbound foreground stays neutral in legacy mode");
        var boundGeometry =
                new PreviewMesh(
                        geometry.positions(),
                        new int[] {3, 4, 5},
                        List.of(new PreviewMesh.Part(0, 0, 3)));
        assertArrayEquals(
                StaticMeshRenderer.render(boundGeometry, 200, 200, view, () -> false).argb(),
                StaticMeshRenderer.render(appearance, 200, 200, view, () -> false, true, false)
                        .argb(),
                "Textures off preserves qualified ordinals and uses neutral flat rendering");
        assertArrayEquals(
                StaticMeshRenderer.render(geometry, 200, 200, view, () -> false).argb(),
                StaticMeshRenderer.render(appearance, 200, 200, view, () -> false, false, false)
                        .argb(),
                "Raw textures-off diagnostics preserve plain mesh rendering");
        // Empty ID selection still means all native IDs; it does not bypass material admission.
        var allIds = new StaticMeshRenderer.View(0, 0, 1, false, Set.of());
        assertArrayEquals(
                qualified.argb(),
                StaticMeshRenderer.render(appearance, 200, 200, allIds, () -> false, true).argb());
        var hidden = new StaticMeshRenderer.View(0, 0, 1, false, Set.of(99));
        var noBindings = new MeshAppearance(geometry, new float[12], List.of(), List.of());
        assertArrayEquals(
                StaticMeshRenderer.render(appearance, 200, 200, hidden, () -> false, true).argb(),
                StaticMeshRenderer.render(noBindings, 200, 200, view, () -> false, true).argb());
    }

    @Test
    void qualifiedMaterialsKeepCutoutThresholdAndDepthBeforeBackgroundSurface() {
        var geometry =
                new PreviewMesh(
                        new float[] {
                            .2f, -1, -1, .2f, 1, -1, .2f, 0, 1, -.2f, -1, -1, -.2f, 1, -1, -.2f, 0,
                            1, .4f, -1, -1, .4f, 1, -1, .4f, 0, 1
                        },
                        new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8},
                        List.of(
                                new PreviewMesh.Part(0, 0, 3),
                                new PreviewMesh.Part(0, 3, 3),
                                new PreviewMesh.Part(0, 6, 3)));
        var view = new StaticMeshRenderer.View(0, 0, 1, false, Set.of(0));
        for (int alpha : new int[] {127, 128}) {
            var appearance =
                    new MeshAppearance(
                            geometry,
                            new float[18],
                            List.of(
                                    new MeshAppearance.Surface(0, 0, false, false, true),
                                    new MeshAppearance.Surface(1, 1, false, false, false)),
                            List.of(
                                    new MeshAppearance.Texture(
                                            1, 1, new int[] {alpha << 24 | 0x00ff00}),
                                    new MeshAppearance.Texture(1, 1, new int[] {0xffff0000})));
            int center =
                    StaticMeshRenderer.render(appearance, 200, 200, view, () -> false, true)
                            .argb()[100 * 200 + 100];
            if (alpha == 127) {
                assertTrue(
                        (center >>> 16 & 255) > 100,
                        "Discarded cutout cannot occlude red behind it");
                assertEquals(0, center & 0xffff);
            } else {
                assertTrue(
                        (center >>> 8 & 255) > 100,
                        "Accepted cutout writes depth before red behind it");
                assertEquals(0, center & 0xff00ff);
            }
        }
    }

    @Test
    void appearanceDerivativeRoundTripChecksAllGeometryUvTextureLengths() {
        var geometry = mesh();
        var original =
                new MeshAppearance(
                        geometry,
                        new float[] {0, 0, 1, 0, 0, 1},
                        List.of(new MeshAppearance.Surface(0, 0, true, false, true)),
                        List.of(new MeshAppearance.Texture(1, 1, new int[] {0xff123456})));
        byte[] bytes = MeshAppearanceCodec.encode(original);
        var restored = MeshAppearanceCodec.decode(bytes);
        assertEquals(original.surfaces(), restored.surfaces());
        assertEquals(0xff123456, restored.textures().getFirst().argb()[0]);
        assertThrows(
                IllegalArgumentException.class,
                () -> MeshAppearanceCodec.decode(java.util.Arrays.copyOf(bytes, bytes.length - 1)));
        bytes[8] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> MeshAppearanceCodec.decode(bytes));
    }

    @Test
    void derivativeRoundTripPreservesBoundedPositionsTopologyAndSections() {
        var original = mesh();
        var bytes = PreviewMeshCodec.encode(original);
        var restored = PreviewMeshCodec.decode(bytes);
        assertEquals(original.vertexCount(), restored.vertexCount());
        assertEquals(original.parts(), restored.parts());
        bytes[4] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> PreviewMeshCodec.decode(bytes));
    }
}
