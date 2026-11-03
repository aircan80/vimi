package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class GpuViewRequestTest {
    @Test
    void smallControlUpdatesPreserveQualifiedVisibilityAndModeSemantics() {
        var view = GpuViewRequest.parse("VIEW 9 960 720 -0.3 0 1.15 3");
        assertEquals(9, view.revision());
        assertTrue(view.textures());
        assertTrue(view.camera(Set.of(7)).wireframe());
        assertEquals(Set.of(7), view.camera(Set.of(7)).parts());
        assertEquals(Set.of(Integer.MIN_VALUE), view.camera(Set.of()).parts());
        var all = GpuViewRequest.parse("VIEW 10 640 480 0 0 1 4");
        assertFalse(all.textures());
        assertFalse(all.qualifiedMaterialsOnly());
        assertTrue(GpuViewRequest.parse("VIEW 11 640 480 0 0 1 10").qualifiedMaterialsOnly());
        assertTrue(GpuViewRequest.parse("VIEW 12 640 480 0 0 1 8").qualifiedMaterialsOnly());
        assertFalse(GpuViewRequest.parse("VIEW 13 640 480 0 0 1 14").qualifiedMaterialsOnly());
        assertEquals(Set.of(), all.camera(Set.of(7)).parts());
    }

    @Test
    void invalidControlFramingDimensionsOrCameraCannotReachNativeRendering() {
        for (String invalid :
                new String[] {
                    "VIEW 0 640 480 0 0 1 2", "VIEW 1 1281 480 0 0 1 2",
                    "VIEW 1 640 961 0 0 1 2", "VIEW 1 640 480 NaN 0 1 2",
                    "VIEW 1 640 480 0 Infinity 1 2", "VIEW 1 640 480 0 0 4 2",
                    "VIEW 1 640 480 0 0 1 16", "VIEW 1 640 480 0 0 1 2 extra",
                    "CLOSE", "VIEW " + "0".repeat(256)
                }) {
            assertThrows(
                    IllegalArgumentException.class, () -> GpuViewRequest.parse(invalid), invalid);
        }
    }
}
