package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldStageTimings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldStreamFrameCullCountsTest {
    @AfterEach
    void restore() {
        WorldStageTimings.enable(false);
        WorldStageTimings.reset();
    }

    @Test
    void eachFrameRecordsWhatTheCullKeptAndSkipped() {
        WorldStageTimings.enable(true);
        WorldStreamNativeOwner.cullCounts(
                new BgfxWorldStreamRenderer.FrameCounts(
                        6500, 5600, 1200, 40, 4, 2304, 1900, 410, 900, 0, 3, 40));
        WorldStreamNativeOwner.cullCounts(
                new BgfxWorldStreamRenderer.FrameCounts(
                        6500, 5000, 1700, 60, 4, 2304, 1700, 600, 1500, 3, 2, 12));
        var snapshot = WorldStageTimings.snapshot();
        assertEquals(2, snapshot.get("child.frame.placements").count());
        assertEquals(13000, snapshot.get("child.frame.placements").sum());
        assertEquals(10600, snapshot.get("child.frame.placementsCulled").sum());
        assertEquals(2900, snapshot.get("child.frame.objectSubmits").sum());
        assertEquals(100, snapshot.get("child.frame.sortedSubmits").sum());
        assertEquals(4608, snapshot.get("child.frame.terrainChunks").sum());
        assertEquals(3600, snapshot.get("child.frame.terrainChunksCulled").sum());
        assertEquals(1010, snapshot.get("child.frame.terrainSubmits").sum());
        assertEquals(2400, snapshot.get("child.frame.instances").sum());
        assertEquals(3, snapshot.get("child.frame.instancesDropped").sum());
        assertEquals(5, snapshot.get("child.frame.portalWalks").sum());
        assertEquals(52, snapshot.get("child.frame.portalMeshesCulled").sum());
    }

    @Test
    void nothingIsRecordedWhileTimingsAreOff() {
        WorldStageTimings.enable(false);
        WorldStreamNativeOwner.cullCounts(
                new BgfxWorldStreamRenderer.FrameCounts(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1));
        assertTrue(WorldStageTimings.snapshot().series().isEmpty());
    }
}
