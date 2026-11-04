package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GlobalWmoNativeContractTest {
    @Test
    void standaloneLedgerIncludesQueuedCopiesAndActualFrameOwners() {
        assertEquals(41_154_816, GlobalWmoNativeScene.TOTAL);
        assertEquals(32768 * 20 + 98304 * 2, GlobalWmoNativeScene.GPU_MESH);
        assertEquals(32768 * 20 + 98304 * 4, GlobalWmoNativeScene.CPU_MESH);
        assertEquals(1179648 * 4, GlobalWmoNativeScene.GPU_RGBA);
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
        assertEquals(3.2f, GlobalWmoCamera.projection(320, 160).get(5));
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
        assertThrows(
                IllegalArgumentException.class, () -> GlobalWmoCamera.initial(2, 0, 0, 1, 0, 0));
        assertThrows(
                IllegalArgumentException.class, () -> GlobalWmoCamera.initial(0, 0, 0, 4097, 0, 0));
    }
}
