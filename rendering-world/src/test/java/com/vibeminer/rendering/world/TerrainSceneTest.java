package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ReadOnlyBufferException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TerrainSceneTest {
    @Test
    void snapshotOwnsGeometryAndKeepsNativeTileOrderAndReadOnlyIndependentViews() {
        float[] points = {999000, 2, 3, 999004, 2, 3, 999000, 6, 7};
        int[] indices = {0, 1, 2};
        var first = TerrainScene.Tile.copyOf(32, 17, points, indices, 1);
        points[0] = Float.NaN;
        indices[0] = 99;
        var second = TerrainScene.Tile.copyOf(31, 17, new float[] {998000, 1, 2}, new int[0], 1);
        var scene = new TerrainScene(List.of(first, second));
        assertEquals(List.of(first, second), scene.tiles());
        assertEquals(1, scene.triangleCount());
        assertEquals(999000, first.vertices().get(0));
        assertEquals(0, first.indices().get(0));
        assertEquals(0, first.vertices().position());
        first.vertices().position(3);
        assertEquals(0, first.vertices().position());
        assertThrows(ReadOnlyBufferException.class, () -> first.vertices().put(0, 3));
        assertThrows(ReadOnlyBufferException.class, () -> first.vertices().array());
        assertThrows(ReadOnlyBufferException.class, () -> first.indices().array());
        assertEquals(new TerrainScene.Origin(998502, 3.5, 4.5), scene.origin());
        assertEquals(60, scene.payloadBytes());
    }

    @Test
    void rejectsDuplicateTilesAndInvalidGeometryButPreservesFullyHoledTiles() {
        var empty = TerrainScene.Tile.copyOf(0, 0, new float[] {0, 0, 0}, new int[0], 1);
        assertEquals(0, new TerrainScene(List.of(empty)).triangleCount());
        assertThrows(IllegalArgumentException.class, () -> new TerrainScene(List.of(empty, empty)));
        assertThrows(IllegalArgumentException.class, () -> new TerrainScene(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainScene.Tile.copyOf(64, 0, new float[] {0, 0, 0}, new int[0], 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> TerrainScene.Tile.copyOf(0, 0, new float[] {Float.NaN, 0, 0}, new int[0], 1));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TerrainScene.Tile.copyOf(
                                0, 0, new float[] {0, 0, 0}, new int[] {0, 1, 0}, 1));
        assertEquals(589824, TerrainScene.MAX_TRIANGLES);
    }

    @Test
    void freeFlightAndReversedProjectionHaveIndependentNativeAxisAndAsymmetricFrameOracles() {
        var camera = new TerrainCamera(10, 20, 30, 0, 0);
        var eye = camera.eye(14, 22, 31);
        assertEquals(new TerrainCamera.Eye(-2, 1, 4), eye);
        assertEquals(40, TerrainCamera.screenX(eye, 400, 200));
        assertEquals(20, TerrainCamera.screenY(eye, 400, 200));
        var quarter = new TerrainCamera(10, 20, 30, Math.PI / 2, 0).eye(10, 24, 32);
        assertEquals(0, quarter.right(), 1e-12);
        assertEquals(2, quarter.up(), 1e-12);
        assertEquals(4, quarter.depth(), 1e-12);
        var matrix = TerrainCamera.reversedProjection(400, 200);
        assertEquals(1.6, matrix.get(0), 1e-6);
        assertEquals(3.2, matrix.get(5), 1e-6);
        assertEquals(.5, matrix.get(14));
        assertEquals(-1, matrix.get(11));
        assertEquals(0, matrix.get(10));
        assertTrue(.5 / 1999.9 > .5 / 1999.91);
        assertThrows(ReadOnlyBufferException.class, matrix::array);
        assertEquals(camera, TerrainCamera.from(camera.software()));
        assertEquals(new TerrainCamera(12, 17, 34, 0, 0), camera.move(2, 3, 4));
        assertEquals(1.5, camera.look(0, 2).pitch());
    }

    @Test
    void geographicRightAndForwardAgreeForMovementEyeAndGpuMatrixAtEveryCardinal() {
        // Independent geographic oracles: +X north, +Y west; screen right is east at north.
        double[][] directions = {{1, 0, 0, -1}, {0, 1, 1, 0}, {-1, 0, 0, 1}, {0, -1, -1, 0}};
        double[] yaws = {0, Math.PI / 2, Math.PI, -Math.PI / 2};
        var origin = new TerrainScene.Origin(0, 0, 0);
        for (int i = 0; i < yaws.length; i++) {
            var camera = new TerrainCamera(0, 0, 0, yaws[i], 0);
            var forward = camera.move(10, 0, 0);
            var right = camera.move(0, 4, 0);
            assertEquals(10 * directions[i][0], forward.x(), 1e-12);
            assertEquals(10 * directions[i][1], forward.y(), 1e-12);
            assertEquals(4 * directions[i][2], right.x(), 1e-12);
            assertEquals(4 * directions[i][3], right.y(), 1e-12);
            double x = forward.x() + right.x(), y = forward.y() + right.y();
            var eye = camera.eye(x, y, 2);
            assertEquals(4, eye.right(), 1e-12);
            assertEquals(10, eye.depth(), 1e-12);
            var matrix = camera.modelToEye(origin);
            assertEquals(4, matrix.get(0) * x + matrix.get(4) * y + matrix.get(12), 1e-6);
            assertEquals(-10, matrix.get(2) * x + matrix.get(6) * y + matrix.get(14), 1e-6);
            var software = camera.software().move(10, 4, 2);
            assertEquals(x, software.x(), 1e-12);
            assertEquals(y, software.y(), 1e-12);
        }
    }
}
