package com.vibeminer.rendering.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
