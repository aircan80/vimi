package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.rendering.world.TerrainCamera;
import org.junit.jupiter.api.Test;

class GlobalWmoNativeContractTest {
    @Test
    void standaloneLedgerIncludesQueuedCopiesAndActualFrameOwners() {
        // 512Ki vertices of 20 bytes plus 1.5Mi 32-bit indices; 8Mi RGBA8 pixels.
        assertEquals(524288L * 20 + 1572864L * 4, GlobalWmoNativeScene.GPU_MESH);
        assertEquals(GlobalWmoNativeScene.GPU_MESH, GlobalWmoNativeScene.CPU_MESH);
        assertEquals(8388608L * 4, GlobalWmoNativeScene.GPU_RGBA);
        assertEquals(256 * 256 * 4, GlobalWmoNativeScene.DIRECT_IMAGE);
        assertEquals(
                4 * (524288L * 20 + 1572864L * 4)
                        + 3 * 8388608L * 4
                        + 256 * 256 * 4
                        + 3 * 262_144
                        + 1_048_576
                        + 2_097_152
                        + GlobalWmoNativeScene.FRAME_OWNERS,
                GlobalWmoNativeScene.TOTAL);
        assertEquals(
                2 * 800 * 500 * 4
                        + 3 * 800 * 500 * 4
                        + 3 * 800 * 500 * 4
                        + 800 * 500 * 4
                        + 4_800_256,
                GlobalWmoNativeScene.FRAME_OWNERS);
    }

    @Test
    void globalCameraUsesGeographicRightAndInfiniteReversedDepth() {
        var camera = new GlobalWmoCamera(0, 0, 0, 0, 0);
        assertEquals(new GlobalWmoCamera(0, -3, 0, 0, 0), camera.move(0, 3, 0));
        var west = camera.look(Math.PI / 2, 0).move(5, 0, 0);
        assertEquals(5, west.y(), 1e-12);
        assertEquals(-1, camera.modelToEye().get(4));
        assertEquals(.5f, GlobalWmoCamera.projection(800, 500).get(14));
        assertEquals(0, GlobalWmoCamera.projection(800, 500).get(10));
        assertEquals(
                (float) (2 * TerrainCamera.FOCAL), GlobalWmoCamera.projection(320, 160).get(5));
        assertThrows(IllegalArgumentException.class, () -> GlobalWmoCamera.projection(801, 500));
        assertThrows(IllegalArgumentException.class, () -> GlobalWmoCamera.projection(800, 0));
        assertThrows(IllegalArgumentException.class, () -> camera.move(8193, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new GlobalWmoCamera(0, 0, 0, 0, Double.NaN));
    }

    @Test
    void boundsFrameIsInspectionOnlyAndFitsSeparateCameraLimit() {
        var camera = GlobalWmoCamera.initial(-4096, -4096, -4096, 4096, 4096, 4096);
        assertEquals(-8192, camera.x());
        assertEquals(0, camera.y());
        assertEquals(0, camera.z());
        // A 10 x 10 x 10 box backs off until its height (scaled to the 800x500 aspect) fits
        // the shared field of view: .5 * FOCAL * max(10, 16) yards from its near face.
        var small = GlobalWmoCamera.initial(0, 0, 0, 10, 10, 10);
        assertEquals(-.5 * TerrainCamera.FOCAL * 16, small.x(), 1e-9);
        assertEquals(5, small.y());
        assertThrows(
                IllegalArgumentException.class, () -> GlobalWmoCamera.initial(2, 0, 0, 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> GlobalWmoCamera.initial(0, 0, 0, 4097, 0, 0));
    }
}
