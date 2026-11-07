package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.formats.world.WorldStageTimings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStageTimingsDumpTest {
    @TempDir Path directory;

    @AfterEach
    void restore() {
        WorldStageTimings.enable(false);
        WorldStageTimings.reset();
        System.clearProperty(WorldStageTimingsDump.FILE_PROPERTY);
    }

    @Test
    void writesTheCurrentTableAndReplacesIt() throws Exception {
        WorldStageTimings.enable(true);
        var file = directory.resolve("child.txt");
        var dump = new WorldStageTimingsDump(file, 100);
        WorldStageTimings.value("child.frame.draws", 3);
        dump.maybeWrite(1_000);
        assertTrue(Files.readString(file).contains("child.frame.draws"));
        WorldStageTimings.value("child.frame.triangles", 9);
        dump.maybeWrite(1_050); // inside the interval: unchanged
        assertFalse(Files.readString(file).contains("child.frame.triangles"));
        dump.maybeWrite(1_100);
        assertTrue(Files.readString(file).contains("child.frame.triangles"));
        assertFalse(Files.exists(directory.resolve("child.txt.part")));
    }

    @Test
    void finalWriteIgnoresTheInterval() throws Exception {
        WorldStageTimings.enable(true);
        var file = directory.resolve("child.txt");
        var dump = new WorldStageTimingsDump(file, Long.MAX_VALUE);
        dump.maybeWrite(0);
        WorldStageTimings.add("child.frame.bgfxFrames", 1);
        dump.write();
        assertTrue(Files.readString(file).contains("child.frame.bgfxFrames"));
    }

    @Test
    void inertWithoutBothProperties() {
        WorldStageTimings.enable(true);
        assertFalse(WorldStageTimingsDump.configured().active());
        System.setProperty(WorldStageTimingsDump.FILE_PROPERTY, directory.resolve("x").toString());
        WorldStageTimings.enable(false);
        assertFalse(WorldStageTimingsDump.configured().active());
        WorldStageTimings.enable(true);
        assertTrue(WorldStageTimingsDump.configured().active());
    }

    @Test
    void unwritableTargetNeverThrows() {
        WorldStageTimings.enable(true);
        var dump = new WorldStageTimingsDump(directory.resolve("missing").resolve("x.txt"), 0);
        dump.maybeWrite(5);
        dump.write();
        assertEquals(false, Files.exists(directory.resolve("missing")));
    }
}
