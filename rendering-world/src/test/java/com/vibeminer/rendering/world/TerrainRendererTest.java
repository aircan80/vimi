package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldFormats;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class TerrainRendererTest {
    @Test
    void perspectiveClipsTriangleCrossingNearPlane() {
        var terrain =
                new WorldFormats.Terrain(
                        new float[] {-.5f, -1, -1, 5, -2, -1, 5, 2, 2}, new int[] {0, 1, 2}, 1);
        var scene = new TerrainRenderer.Scene(List.of(terrain));
        var camera = new TerrainRenderer.Camera(0, 0, 0, 0, 0);
        var frame = TerrainRenderer.render(scene, camera, 320, 240, () -> false);
        assertTrue(frame.triangles() > 0);
        assertEquals(320 * 240, frame.pixels().length);
        assertFalse(
                java.util.Arrays.equals(
                        frame.pixels(),
                        TerrainRenderer.render(
                                        new TerrainRenderer.Scene(List.of()),
                                        camera,
                                        320,
                                        240,
                                        () -> false)
                                .pixels()));
    }

    @Test
    void cameraMotionKeepsNativeZVertical() {
        var c = new TerrainRenderer.Camera(0, 0, 0, 0, 0).move(2, 3, 4);
        assertEquals(2, c.x(), 1e-8);
        assertEquals(3, c.y(), 1e-8);
        assertEquals(4, c.z(), 1e-8);
        assertEquals(1.5, c.look(0, 100).pitch());
    }

    @Test
    void renderingAndAllocationAreBoundedAndCancelable() {
        var scene =
                new TerrainRenderer.Scene(
                        List.of(
                                new WorldFormats.Terrain(
                                        new float[] {2, 0, 0, 3, 1, 0, 4, 0, 1},
                                        new int[] {0, 1, 2},
                                        1)));
        var c = new TerrainRenderer.Camera(0, 0, 0, 0, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainRenderer.render(scene, c, 1201, 800, () -> false));
        assertThrows(
                CancellationException.class,
                () -> TerrainRenderer.render(scene, c, 320, 240, () -> true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainRenderer.Camera(Double.NaN, 0, 0, 0, 0));
    }
}
