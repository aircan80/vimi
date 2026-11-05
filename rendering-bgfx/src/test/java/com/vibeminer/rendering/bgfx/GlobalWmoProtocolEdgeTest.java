package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic malformed/mixed/overflow/partial-line/exhaustion gates for partition 1. */
class GlobalWmoProtocolEdgeTest {
    @TempDir Path directory;
    private static final String NONCE = "1".repeat(32),
            SCOPE = "2".repeat(64),
            SHA = "3".repeat(64);

    private List<String> args() {
        return new ArrayList<>(
                List.of(
                        "--global-scene",
                        directory.resolve("input/global.wgs").toString(),
                        "--global-nonce",
                        NONCE,
                        "--global-scope",
                        SCOPE,
                        "--global-sha256",
                        SHA,
                        "--global-length",
                        "2048",
                        "--frames",
                        directory.resolve("output/frames.vgpu").toString(),
                        "--global-frame-nonce",
                        "00000000000000a1",
                        "--parent-pid",
                        "1"));
    }

    private static void rejects(List<String> argv) {
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobalWmoLaunch.parse(argv.toArray(String[]::new)));
    }

    private List<String> with(String key, String value) {
        var argv = args();
        argv.set(argv.indexOf(key) + 1, value);
        return argv;
    }

    @Test
    void launchEncoderRoundTripsExactlyWithAndWithoutBackend() {
        var launch = GlobalWmoLaunch.parse(args().toArray(String[]::new));
        assertEquals(args(), launch.arguments());
        assertEquals(launch, GlobalWmoLaunch.parse(launch.arguments().toArray(String[]::new)));
        var argv = args();
        argv.addAll(List.of("--backend", "d3d11"));
        var explicit = GlobalWmoLaunch.parse(argv.toArray(String[]::new));
        assertEquals(argv, explicit.arguments());
    }

    @Test
    void launchRejectsNoncanonicalOrNestedPaths() {
        String root = directory.toString();
        rejects(with("--global-scene", root + "/input//global.wgs"));
        rejects(with("--global-scene", root + "/input/global.wgs/"));
        rejects(with("--frames", root + "/output/./frames.vgpu"));
        rejects(with("--frames", root + "/input/out/frames.vgpu"));
        rejects(with("--frames", root + "/input/frames.vgpu"));
        var reverse = with("--frames", root + "/output/frames.vgpu");
        reverse.set(reverse.indexOf("--global-scene") + 1, root + "/output/in/global.wgs");
        rejects(reverse);
        rejects(with("--frames", "relative/frames.vgpu"));
        rejects(with("--global-scene", root + "/input/glo\0bal.wgs"));
        rejects(with("--global-scene", root + "/input/other.wgs"));
    }

    @Test
    void launchRejectsMalformedArgvBackendIdentityAndLength() {
        var odd = args();
        odd.add("--backend");
        rejects(odd);
        for (String backend : List.of("default", "opengl", "VULKAN", "")) {
            var argv = args();
            argv.addAll(List.of("--backend", backend));
            rejects(argv);
        }
        var duplicate = args();
        duplicate.addAll(List.of("--parent-pid", "2"));
        rejects(duplicate);
        var extra = args();
        extra.addAll(List.of("--scene", "/x"));
        rejects(extra);
        for (String key : List.of("--global-nonce", "--global-scope", "--global-sha256")) {
            String value = args().get(args().indexOf(key) + 1);
            rejects(with(key, "A".repeat(value.length())));
            rejects(with(key, value.substring(1)));
            rejects(with(key, value + "0"));
        }
        for (String length :
                List.of(
                        "0",
                        "01",
                        "1023",
                        "-2048",
                        "+2048",
                        Integer.toString(WorldGlobalLayout.MAX_ENCODED_BYTES + 1),
                        "9999999999")) {
            rejects(with("--global-length", length));
        }
        rejects(with("--global-frame-nonce", "0000000000000000"));
        rejects(with("--parent-pid", "0"));
        rejects(with("--parent-pid", "9223372036854775808"));
    }

    private static GlobalWmoControl.Command parse(String line, long previous) {
        return GlobalWmoControl.parse(line, NONCE, previous);
    }

    private static void rejects(String line) {
        assertThrows(IllegalArgumentException.class, () -> parse(line, 0));
    }

    @Test
    void controlRejectsFieldCountsRevisionsDimensionsAndCameraBounds() {
        String cam = "GLOBAL_CAM " + NONCE + " ";
        assertInstanceOf(
                GlobalWmoControl.Camera.class, parse(cam + "1 800 500 0.0 0.0 0.0 0.0 0.0", 0));
        rejects(cam + "1 800 500 0.0 0.0 0.0 0.0");
        rejects(cam + "1 800 500 0.0 0.0 0.0 0.0 0.0 0.0");
        rejects("GLOBAL_CLOSE " + NONCE + " extra");
        rejects("GLOBAL_CLOSE " + NONCE + " ");
        rejects("GLOBAL_CLOSE " + "2".repeat(32));
        rejects("GLOBAL_CLOSE");
        rejects(cam + "0 800 500 0.0 0.0 0.0 0.0 0.0");
        rejects(cam + "9223372036854775808 800 500 0.0 0.0 0.0 0.0 0.0");
        rejects(cam + "1 0 500 0.0 0.0 0.0 0.0 0.0");
        rejects(cam + "1 800 501 0.0 0.0 0.0 0.0 0.0");
        rejects(cam + "1 800 500 0.0 0.0 0.0 0.0 1.5000000000000002");
        rejects(cam + "1 800 500 8192.000000000002 0.0 0.0 0.0 0.0");
        rejects(cam + "1 800 500 0.0 0.0 0.0 1.0E300 0.0");
        rejects(cam + "1 800 500 0.0 0.0 0.0 0.0 0.00000000000000000000001");
        rejects("WORLD " + NONCE);
        rejects("VIEW " + NONCE);
    }

    @Test
    void controlRejectsEveryNoncanonicalNumberSpelling() {
        String prefix = "GLOBAL_CAM " + NONCE + " 1 800 500 ";
        for (String number :
                List.of(
                        "+0.0",
                        "1e0",
                        "1.0d",
                        "1.0f",
                        "Infinity",
                        "NaN",
                        "-0",
                        "0",
                        "1.",
                        "0x1p0")) {
            rejects(prefix + number + " 0.0 0.0 0.0 0.0");
        }
        rejects(prefix + " 0.0 0.0 0.0 0.0 0.0");
        rejects(prefix + "0.0  0.0 0.0 0.0 0.0");
        rejects(prefix + "0.0 0.0 0.0 0.0 0.0 ");
        assertInstanceOf(GlobalWmoControl.Camera.class, parse(prefix + "-0.0 0.0 0.0 0.0 -0.0", 0));
    }

    @Test
    void revisionExhaustionRejectsEverySuccessor() {
        String prefix = "GLOBAL_CAM " + NONCE + " ";
        String tail = " 800 500 0.0 0.0 0.0 0.0 0.0";
        long max = Long.MAX_VALUE;
        assertInstanceOf(GlobalWmoControl.Camera.class, parse(prefix + max + tail, max - 1));
        assertThrows(IllegalArgumentException.class, () -> parse(prefix + max + tail, max));
        assertThrows(IllegalArgumentException.class, () -> parse(prefix + "1" + tail, max));
        assertInstanceOf(GlobalWmoControl.Close.class, parse("GLOBAL_CLOSE " + NONCE, max));
    }

    private static String read(String bytes) throws IOException {
        return GlobalWmoControl.readLine(
                new ByteArrayInputStream(bytes.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void readerSeparatesEmptyLinesCarriageReturnsAndCleanEof() throws Exception {
        assertEquals("", read("\n"));
        rejects("");
        assertNull(read(""));
        assertThrows(IOException.class, () -> read("GLOBAL_CLOSE\r\n"));
        assertThrows(IOException.class, () -> read("\r"));
        assertThrows(EOFException.class, () -> read("G"));
        assertThrows(IOException.class, () -> read("Gé\n"));
    }

    @Test
    void frameSelectionRespectsSequenceIdentityClosureAndDimensions() throws Exception {
        var frames = GpuFrameFile.create(directory.resolve("frames.vgpu"), 800, 500);
        try {
            assertThrows(IOException.class, () -> frames.requireGlobalSession(0));
            var selection = new GpuFrameFile.GlobalSelection(1, 10, 2, 2);
            assertNull(frames.readLatestGlobal(0, selection));
            assertThrows(
                    IllegalArgumentException.class, () -> frames.readLatestGlobal(-1, selection));
            assertThrows(NullPointerException.class, () -> frames.readLatestGlobal(0, null));
            long nonce = frames.sessionNonce();
            frames.close();
            assertThrows(IllegalStateException.class, () -> frames.requireGlobalSession(nonce));
            assertThrows(Exception.class, () -> frames.readLatestGlobal(0, selection));
        } finally {
            frames.close();
        }
        try (var small = GpuFrameFile.create(directory.resolve("small.vgpu"), 400, 500)) {
            assertThrows(
                    IOException.class,
                    () -> small.readLatestGlobal(0, new GpuFrameFile.GlobalSelection(1, 1, 1, 1)));
        }
        assertThrows(
                IllegalArgumentException.class, () -> new GpuFrameFile.GlobalSelection(0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new GpuFrameFile.GlobalSelection(2, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GpuFrameFile.GlobalSelection(1, 1, 801, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new GpuFrameFile.GlobalSelection(1, 1, 1, 501));
    }
}
