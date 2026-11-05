package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobalWmoProtocolTest {
    @TempDir Path directory;
    private static final String NONCE = "1".repeat(32),
            SCOPE = "2".repeat(64),
            SHA = "3".repeat(64);

    private String[] args(String nonce) {
        return new String[] {
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
            nonce,
            "--parent-pid",
            "1"
        };
    }

    @Test
    void exactLaunchMappingNonceAndFixedDimensionsAreIndependentFromSceneIdentity()
            throws Exception {
        try (var frames = GpuFrameFile.create(directory.resolve("frames.vgpu"), 800, 500)) {
            var launch =
                    GlobalWmoLaunch.parse(
                            args(java.util.HexFormat.of().toHexDigits(frames.sessionNonce())));
            assertEquals(
                    new WorldGlobalStatus.Identity(NONCE, SCOPE, SHA),
                    launch.expected().identity());
            launch.verifyMapping(frames);
            assertEquals(GlobalWmoLaunch.Backend.DEFAULT, launch.backend());
            var wrong =
                    GlobalWmoLaunch.parse(
                            args(java.util.HexFormat.of().toHexDigits(frames.sessionNonce() ^ 1L)));
            assertThrows(IOException.class, () -> wrong.verifyMapping(frames));
            var backend =
                    new ArrayList<>(
                            List.of(
                                    args(
                                            java.util.HexFormat.of()
                                                    .toHexDigits(frames.sessionNonce()))));
            backend.addAll(List.of("--backend", "vulkan"));
            assertEquals(
                    GlobalWmoLaunch.Backend.VULKAN,
                    GlobalWmoLaunch.parse(backend.toArray(String[]::new)).backend());
        }
        try (var wrongSize = GpuFrameFile.create(directory.resolve("small"), 799, 500)) {
            assertThrows(
                    IOException.class,
                    () ->
                            GlobalWmoLaunch.parse(
                                            args(
                                                    java.util.HexFormat.of()
                                                            .toHexDigits(wrongSize.sessionNonce())))
                                    .verifyMapping(wrongSize));
        }
    }

    @Test
    void launchRejectsMixedModesDuplicatesUnboundedAndNoncanonicalFieldsBeforeIo() {
        var valid = args("ffffffffffffffff");
        assertEquals(-1L, GlobalWmoLaunch.parse(valid).frameNonce());
        for (int index : new int[] {0, 2, 4, 6, 8, 10, 12, 14}) {
            var missing = valid.clone();
            missing[index] = "--terrain-scene";
            assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(missing));
        }
        for (String text : List.of("0", "01", "+1", "-1", "9223372036854775808", "1\n")) {
            var malformed = valid.clone();
            malformed[15] = text;
            assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(malformed));
        }
        for (String text : List.of("", "0".repeat(16), "A".repeat(16), "f".repeat(17))) {
            var malformed = valid.clone();
            malformed[13] = text;
            assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(malformed));
        }
        var duplicate = new ArrayList<>(List.of(valid));
        duplicate.addAll(List.of("--frames", valid[11]));
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobalWmoLaunch.parse(duplicate.toArray(String[]::new)));
        for (String path :
                List.of(
                        "relative/global.wgs",
                        directory.resolve("../global.wgs").toString(),
                        directory.resolve("input/other.wgs").toString(),
                        "/" + "a".repeat(4096) + "/global.wgs")) {
            var malformed = valid.clone();
            malformed[1] = path;
            assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(malformed));
        }
        var shared = valid.clone();
        shared[11] = directory.resolve("input/frames.vgpu").toString();
        assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(shared));
        var tooLarge = valid.clone();
        tooLarge[9] = Integer.toString(WorldGlobalLayout.MAX_ENCODED_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> GlobalWmoLaunch.parse(tooLarge));
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobalWmoLaunch.parse(new String[] {"--global-scene"}));
    }

    @Test
    void controlsRoundtripExactCameraBitsAndRejectStaleMixedAndNoncanonicalInput()
            throws Exception {
        var camera =
                new GlobalWmoControl.Camera(
                        NONCE,
                        new GlobalWmoFrameRequest(
                                Long.MAX_VALUE,
                                new GlobalWmoCamera(-0.0, 8192, -8192, Math.PI, 1.5),
                                800,
                                500));
        byte[] encoded = GlobalWmoControl.encode(camera);
        assertTrue(encoded.length <= 256);
        assertEquals('\n', encoded[encoded.length - 1]);
        String line = GlobalWmoControl.readLine(new ByteArrayInputStream(encoded));
        assertEquals(camera, GlobalWmoControl.parse(line, NONCE, Long.MAX_VALUE - 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobalWmoControl.parse(line, NONCE, Long.MAX_VALUE));
        assertEquals(
                new GlobalWmoControl.Close(NONCE),
                GlobalWmoControl.parse("GLOBAL_CLOSE " + NONCE, NONCE, Long.MAX_VALUE));
        String valid = "GLOBAL_CAM " + NONCE + " 1 317 163 0.0 -0.0 0.0 0.0 0.0";
        assertNotNull(GlobalWmoControl.parse(valid, NONCE, 0));
        for (String bad :
                List.of(
                        valid + " ",
                        " " + valid,
                        valid.replace("GLOBAL_CAM", "WORLD"),
                        valid.replace(NONCE, "4".repeat(32)),
                        valid.replace(" 1 317", " 01 317"),
                        valid.replace("317 163", "801 163"),
                        valid.replace("0.0 -0.0", "NaN -0.0"),
                        valid.replace("0.0 -0.0", "0x0.0p0 -0.0"),
                        valid.replace("0.0 -0.0", "0 -0.0"),
                        valid.replace("0.0 -0.0", "8192.1 -0.0"),
                        valid + "\r",
                        valid + "é",
                        "x".repeat(256))) {
            assertThrows(
                    IllegalArgumentException.class, () -> GlobalWmoControl.parse(bad, NONCE, 0));
        }
    }

    @Test
    void controlReaderHasExactAsciiLfBoundAndDoesNotAcceptPartialEof() throws Exception {
        assertEquals("x".repeat(255), GlobalWmoControl.readLine(stream("x".repeat(255) + "\n")));
        assertThrows(
                IOException.class, () -> GlobalWmoControl.readLine(stream("x".repeat(256) + "\n")));
        assertThrows(
                EOFException.class,
                () -> GlobalWmoControl.readLine(stream("GLOBAL_CLOSE " + NONCE)));
        assertThrows(IOException.class, () -> GlobalWmoControl.readLine(stream("bad\r\n")));
        assertThrows(
                IOException.class,
                () ->
                        GlobalWmoControl.readLine(
                                new ByteArrayInputStream(new byte[] {(byte) 0x80, 10})));
        assertNull(GlobalWmoControl.readLine(stream("")));
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void closedStatusHasExactIdentityAndBoundWithoutChangingExistingReady() {
        var identity = new WorldGlobalStatus.Identity(NONCE, SCOPE, SHA);
        var closed = new WorldGlobalStatus.Closed(identity);
        assertEquals(177, closed.protocolLine().length() + 1);
        assertEquals(closed, WorldGlobalStatus.parse(closed.protocolLine()));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldGlobalStatus.parse(closed.protocolLine() + " x"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldGlobalStatus.parse(
                                closed.protocolLine()
                                        .replace(NONCE, NONCE.toUpperCase().replace('1', 'A'))));
        var ready = new WorldGlobalStatus.Ready(identity, 3, 1, 1, 1, 1, 1);
        assertEquals(ready, WorldGlobalStatus.parse(ready.protocolLine()));
    }

    private static ByteBuffer pixels(int width, int height) {
        var result = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        while (result.hasRemaining()) {
            result.putInt(0xff123456);
        }
        return result.flip();
    }

    @Test
    void frameSelectionAllowsCameraLagButFiltersResizeFloorFutureAndWrongDimensions()
            throws Exception {
        try (var frames = GpuFrameFile.create(directory.resolve("selection"), 800, 500)) {
            frames.write(1, 7, 4, 2, pixels(4, 2));
            frames.write(2, 9, 2, 1, pixels(2, 1));
            var selected =
                    frames.readLatestGlobal(0, new GpuFrameFile.GlobalSelection(5, 10, 4, 2));
            assertNotNull(selected);
            assertEquals(1, selected.sequence());
            assertEquals(7, selected.controlRevision());
            assertNull(frames.readLatestGlobal(0, new GpuFrameFile.GlobalSelection(8, 10, 4, 2)));
            assertNull(frames.readLatestGlobal(0, new GpuFrameFile.GlobalSelection(1, 6, 4, 2)));
            assertEquals(2, frames.readLatest(0).sequence());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GpuFrameFile.GlobalSelection(10, 9, 4, 2));
        }
    }

    @Test
    void rejectedLargeFrameIsFilteredBeforePixelArrayAllocation() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean instanceof com.sun.management.ThreadMXBean);
        var allocation = (com.sun.management.ThreadMXBean) bean;
        Assumptions.assumeTrue(allocation.isThreadAllocatedMemorySupported());
        if (!allocation.isThreadAllocatedMemoryEnabled()) {
            allocation.setThreadAllocatedMemoryEnabled(true);
        }
        try (var frames = GpuFrameFile.create(directory.resolve("allocation"), 800, 500)) {
            frames.write(1, 1, 800, 500, pixels(800, 500));
            var rejected = new GpuFrameFile.GlobalSelection(2, 3, 800, 500);
            for (int n = 0; n < 20; n++) {
                assertNull(frames.readLatestGlobal(0, rejected));
            }
            long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int n = 0; n < 5; n++) {
                assertNull(frames.readLatestGlobal(0, rejected));
            }
            long bytes =
                    allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
            assertTrue(bytes < 1_000_000, "Rejected frame copied pixel payload: " + bytes);
            assertNotNull(
                    frames.readLatestGlobal(0, new GpuFrameFile.GlobalSelection(1, 3, 800, 500)));
        }
    }
}
