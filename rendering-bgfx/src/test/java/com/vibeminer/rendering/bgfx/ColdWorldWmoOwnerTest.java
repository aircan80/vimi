package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainScene;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ColdWorldWmoOwnerTest {
    @TempDir Path directory;

    @Test
    void failedInputSpendsAttemptAndKeepsChargeUntilContextDrain() throws Exception {
        var scene =
                new TerrainScene(
                        List.of(
                                TerrainScene.Tile.copyOf(
                                        1, 2, new float[] {0, 0, 0}, new int[0], 1)));
        var basePath = directory.resolve("base");
        GpuTerrainSceneFile.writeBound(basePath, scene, () -> false);
        var base = GpuTerrainSceneFile.readBound(basePath, () -> false);
        var states = new ArrayList<WorldWmoStatus>();
        var owner = new ColdWorldWmoOwner(states::add);
        owner.attempt(directory.resolve("missing"), base, () -> false);
        assertEquals(
                List.of(WorldWmoStatus.State.LOADING, WorldWmoStatus.State.UNAVAILABLE),
                states.stream().map(WorldWmoStatus::state).toList());
        assertEquals(ColdWorldWmoOwner.CHARGE_BYTES, owner.chargedBytes());
        owner.publishedFirstFrame();
        assertEquals(2, states.size());
        assertThrows(
                IllegalStateException.class,
                () -> owner.attempt(directory.resolve("missing"), base, () -> false));
        assertThrows(IllegalStateException.class, owner::afterContextShutdown);
        owner.close();
        owner.close();
        assertEquals(ColdWorldWmoOwner.CHARGE_BYTES, owner.chargedBytes());
        owner.afterContextShutdown();
        assertEquals(0, owner.chargedBytes());
        assertThrows(
                IllegalStateException.class,
                () -> owner.attempt(directory.resolve("missing"), base, () -> false));
    }

    @Test
    void cancellationAndUnexpectedCallbacksRemainFatalWithoutRetry() throws Exception {
        var scene =
                new TerrainScene(
                        List.of(
                                TerrainScene.Tile.copyOf(
                                        1, 2, new float[] {0, 0, 0}, new int[0], 1)));
        var path = directory.resolve("base");
        GpuTerrainSceneFile.writeBound(path, scene, () -> false);
        var base = GpuTerrainSceneFile.readBound(path, () -> false);
        var owner = new ColdWorldWmoOwner(ignored -> {});
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> owner.attempt(directory.resolve("missing"), base, () -> true));
        assertEquals(ColdWorldWmoOwner.CHARGE_BYTES, owner.chargedBytes());
        assertEquals(WorldWmoStatus.State.LOADING, owner.status().state());
        owner.close();
        owner.afterContextShutdown();
        var broken =
                new ColdWorldWmoOwner(
                        ignored -> {
                            throw new IllegalStateException("listener");
                        });
        assertThrows(
                IllegalStateException.class,
                () -> broken.attempt(directory.resolve("missing"), null, () -> false));
        assertEquals(ColdWorldWmoOwner.CHARGE_BYTES, broken.chargedBytes());
        broken.close();
        broken.afterContextShutdown();
    }

    @Test
    void fixedProtocolRejectsImpossibleClaimsAndExtraFields() {
        var ready = new WorldWmoStatus(WorldWmoStatus.State.READY, WorldWmoStatus.Code.NONE, 6, 6);
        assertEquals(ready, WorldWmoStatus.parse(ready.protocolLine()));
        assertNull(WorldWmoStatus.parse("VIMI_GPU READY Vulkan"));
        for (String bad :
                List.of(
                        "READY NONE 0 1",
                        "READY LOAD 1 1",
                        "UNAVAILABLE LOAD 1 0",
                        "LOADING NONE 1 1",
                        "LOADING NONE 7 0",
                        "READY NONE 1 1 extra")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldWmoStatus.parse("VIMI_GPU WORLD_WMO " + bad),
                    bad);
        }
    }

    @Test
    void recognizedStatusIsBoundedPrintableAsciiBeforeTokenization() {
        String prefix = "VIMI_GPU WORLD_WMO ";
        var oversized =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> WorldWmoStatus.parse(prefix + "READY NONE 1 1 " + "x".repeat(4096)));
        assertEquals("Cold WMO status line too long", oversized.getMessage());
        for (String bad :
                List.of(
                        "READY\tNONE 1 1",
                        "READY NONE 1 1\n",
                        "READY NONE 1 1\r",
                        "READY NONE 1 1\u0000",
                        "READY NONE 1 1\u007f",
                        "READY NONE 1 1é",
                        "READY  NONE 1 1",
                        "READY NONE 1 1 ")) {
            assertThrows(IllegalArgumentException.class, () -> WorldWmoStatus.parse(prefix + bad));
        }
        assertNull(WorldWmoStatus.parse("Other protocol " + "x".repeat(4096)));
    }

    @Test
    void annexRequiresColdTerrainAndAbsolutePath() {
        assertTrue(
                GpuViewerMain.terrainMode(
                        options("--terrain-scene", directory.resolve("wmo").toString())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuViewerMain.terrainMode(
                                options("--scene", directory.resolve("wmo").toString())));
        assertThrows(
                IllegalArgumentException.class,
                () -> GpuViewerMain.terrainMode(options("--terrain-scene", "relative.wmo")));
    }

    private static Map<String, String> options(String scene, String annex) {
        return Map.of(
                scene, "base", "--frames", "frames", "--parent-pid", "1", "--wmo-annex", annex);
    }
}
