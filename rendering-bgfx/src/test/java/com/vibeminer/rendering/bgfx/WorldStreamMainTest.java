package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStreamMainTest {
    @TempDir Path directory;

    private String[] args() {
        return new String[] {
            "--world-session-v2",
            directory.toString(),
            "--session-sha256",
            "ab".repeat(32),
            "--frames",
            directory.resolve("frames").toString(),
            "--parent-pid",
            Long.toString(ProcessHandle.current().pid()),
            "--backend",
            "vulkan"
        };
    }

    @Test
    void onlyExplicitWorldFlagDispatchesAndParsesIndependentMode() {
        var options = WorldStreamMain.options(args());
        assertEquals(directory, options.directory());
        assertEquals(directory.resolve("frames"), options.frames());
        assertTrue(WorldStreamMain.requested(args()));
        assertFalse(WorldStreamMain.requested(new String[] {"--scene", "--world-session-v2"}));
        assertFalse(WorldStreamMain.requested(new String[] {"--terrain-scene", "terrain"}));
    }

    @Test
    void legacyMixedDuplicateUnknownMissingAndOddArgumentsRefuse() {
        for (var suffix :
                List.of(
                        List.of("--scene", "model"),
                        List.of("--terrain-scene", "terrain"),
                        List.of("--headless-smoke", "out"),
                        List.of("--frames", "duplicate"),
                        List.of("--unknown", "value"),
                        List.of("--backend"))) {
            var values = new ArrayList<>(List.of(args()));
            values.addAll(suffix);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorldStreamMain.options(values.toArray(String[]::new)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamMain.options(
                                new String[] {"--world-session-v2", directory.toString()}));
    }

    @Test
    void pathsChecksumsParentsAndBackendRemainCanonicalAndBounded() {
        for (var replacement :
                List.of("relative", directory.resolve("..").resolve("escape").toString())) {
            var values = args();
            values[1] = replacement;
            assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(values));
        }
        for (var replacement : List.of("00", "AB".repeat(32), "g0".repeat(32))) {
            var values = args();
            values[3] = replacement;
            assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(values));
        }
        for (var replacement : List.of("0", "-1", "01", "9999999999999999999")) {
            var values = args();
            values[7] = replacement;
            assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(values));
        }
        var values = args();
        values[5] = directory.resolve("other").resolve("frames").toString();
        assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(values));
        values[5] = directory.resolve("session.wss").toString();
        assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(values));
        var badBackend = args();
        badBackend[9] = "opengl";
        assertThrows(IllegalArgumentException.class, () -> WorldStreamMain.options(badBackend));
    }

    @Test
    void badBootstrapFailsBeforeNativeInitializationOrFrameCreation() throws Exception {
        Files.write(directory.resolve("session.wss"), new byte[256]);
        assertEquals(1, WorldStreamMain.execute(args()));
        assertFalse(Files.exists(directory.resolve("frames")));
        Files.delete(directory.resolve("session.wss"));
        Path target = directory.resolve("target");
        Files.write(target, new byte[256]);
        if (System.getProperty("os.name").startsWith("Windows")) {
            return; // Creating a Windows symlink may require a privilege absent in hosted CI.
        }
        Files.createSymbolicLink(directory.resolve("session.wss"), target);
        assertEquals(1, WorldStreamMain.execute(args()));
        assertFalse(Files.exists(directory.resolve("frames")));
    }
}
