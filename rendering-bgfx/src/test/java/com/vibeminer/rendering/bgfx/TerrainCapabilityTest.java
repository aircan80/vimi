package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.bgfx.BGFX.BGFX_CAPS_INSTANCING;

import com.vibeminer.rendering.world.TerrainScene;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class TerrainCapabilityTest {
    @Test
    void unqualifiedDepthAndMissingNativeFeaturesDeclineRatherThanChangingVisualSemantics() {
        assertDoesNotThrow(
                () -> BgfxTerrainRenderer.requireCapabilities(BGFX_CAPS_INSTANCING, false, true));
        assertThrows(
                IOException.class,
                () -> BgfxTerrainRenderer.requireCapabilities(BGFX_CAPS_INSTANCING, true, true));
        assertThrows(
                IOException.class, () -> BgfxTerrainRenderer.requireCapabilities(0, false, true));
        assertThrows(
                IOException.class,
                () -> BgfxTerrainRenderer.requireCapabilities(BGFX_CAPS_INSTANCING, false, false));
    }

    @Test
    void alreadyCanceledUploadRequiresNoNativeContext() {
        var scene =
                new TerrainScene(
                        List.of(
                                TerrainScene.Tile.copyOf(
                                        0, 0, new float[] {0, 0, 0}, new int[0], 1)));
        assertThrows(CancellationException.class, () -> new BgfxTerrainRenderer(scene, () -> true));
    }
}
