package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GpuWorldRequestTest {
    @Test
    void typedWorldControlsKeepNativeCoordinatesAndFreeFlightBounds() {
        var request = GpuWorldRequest.parse("WORLD 9 800 500 -999000 999000 300 1.2 -0.8");
        assertEquals(9, request.revision());
        assertEquals(800, request.width());
        assertEquals(new TerrainCamera(-999000, 999000, 300, 1.2, -.8), request.camera());
        assertTrue(
                GpuViewerMain.parseControl("WORLD 1 400 300 0 0 0 0 0", true)
                        instanceof GpuWorldRequest);
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuViewerMain.parseControl("WORLD 1 400 300 0 0 0 0 0", false));
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuViewerMain.parseControl("VIEW 1 400 300 0 0 1 2", true));
    }

    @Test
    void invalidDimensionsNonfiniteCameraAndExtraFieldsCannotReachNativeTerrain() {
        for (String invalid :
                new String[] {
                    "WORLD 0 800 500 0 0 0 0 0", "WORLD 1 801 500 0 0 0 0 0",
                            "WORLD 1 800 501 0 0 0 0 0",
                    "WORLD 1 800 500 NaN 0 0 0 0", "WORLD 1 800 500 0 Infinity 0 0 0",
                            "WORLD 1 800 500 0 0 1000001 0 0",
                    "WORLD 1 800 500 0 0 0 0 1.5001", "WORLD 1 800 500 0 0 0 0 0 extra",
                            "WORLD " + "0".repeat(256)
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> GpuWorldRequest.parse(invalid), invalid);
        }
    }

    @Test
    void exactlyOneSceneKindIsRequiredWithoutChangingModelSceneOptions() {
        assertTrue(
                GpuViewerMain.terrainMode(
                        Map.of(
                                "--terrain-scene",
                                "terrain",
                                "--frames",
                                "frames",
                                "--parent-pid",
                                "1")));
        assertFalse(
                GpuViewerMain.terrainMode(
                        Map.of("--scene", "model", "--frames", "frames", "--parent-pid", "1")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuViewerMain.terrainMode(
                                Map.of(
                                        "--scene",
                                        "model",
                                        "--terrain-scene",
                                        "terrain",
                                        "--frames",
                                        "frames",
                                        "--parent-pid",
                                        "1")));
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuViewerMain.terrainMode(Map.of("--frames", "frames", "--parent-pid", "1")));
    }
}
