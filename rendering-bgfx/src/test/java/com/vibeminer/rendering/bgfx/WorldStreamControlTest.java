package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.WorldFrameLimits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldStreamControlTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, -2);
    private static final WorldStreamState.Lease LEASE = new WorldStreamState.Lease(9, 23, 37);
    private static final String PREFIX = "WS2 0000000000000001fffffffffffffffe ";

    @Test
    void roundTripsEveryCommandAndKeepsSubsequentLineUnread() throws IOException {
        var commands =
                List.of(
                        new WorldStreamControl.Camera(
                                NONCE,
                                1,
                                2,
                                800,
                                500,
                                new TerrainCamera(-0.0, 1_000_000, -1_000_000, 3.14159, -1.5)),
                        // A camera carrying the recorded sky (#177).
                        new WorldStreamControl.Camera(
                                NONCE,
                                3,
                                2,
                                800,
                                500,
                                new TerrainCamera(1, 2, 3, 0.5, -0.25),
                                new WorldStreamTransport.Sky(
                                        new float[] {50, 70, 100},
                                        new float[] {10, 20, 30.5f},
                                        0.75,
                                        250,
                                        1000)),
                        new WorldStreamControl.Offer(NONCE, 4, LEASE, 2_284_544, "a".repeat(64)),
                        new WorldStreamControl.Commit(NONCE, 5, "b".repeat(64)),
                        new WorldStreamControl.Cancel(NONCE, 4, 9, 23),
                        new WorldStreamControl.CancelUnused(NONCE, 6, LEASE),
                        new WorldStreamControl.Close(NONCE));
        for (var command : commands) {
            byte[] encoded = WorldStreamControl.encode(command);
            assertTrue(encoded.length <= 256);
            assertEquals(command, WorldStreamControl.decode(encoded, encoded.length));
            byte[] joined = new byte[encoded.length + 1];
            System.arraycopy(encoded, 0, joined, 0, encoded.length);
            joined[encoded.length] = 'X';
            var input = new ByteArrayInputStream(joined);
            assertEquals(command, WorldStreamControl.read(input));
            assertEquals('X', input.read());
        }
        assertNull(WorldStreamControl.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsNoncanonicalPathsCountersNumbersAndExtraFields() {
        for (String tail :
                List.of(
                        "CLOSE ",
                        " CLOSE",
                        "CLOSE x",
                        "CLOSE\r",
                        "UNKNOWN",
                        "CANCEL 01 9 23",
                        "CANCEL +1 9 23",
                        "CANCEL 9223372036854775808 9 23",
                        "CANCEL 1 26 23",
                        "CANCEL 0 9 23",
                        "CANCEL 1 4294967296 23",
                        "CANCEL 1 9 0",
                        "CANCEL_UNUSED 1 0 9 23",
                        "OFFER 1 37 9 23 ../tile-1.wst 4096 " + "a".repeat(64),
                        "OFFER 1 37 9 23 tile-2.wst 4096 " + "a".repeat(64),
                        "OFFER 1 37 9 23 tile-1.wst "
                                + (WorldStreamEnvelope.MAX_PACKET_BYTES + 1)
                                + " "
                                + "a".repeat(64),
                        "COMMIT 1 commit-1.wsc " + "A".repeat(64),
                        "COMMIT 1 commit-01.wsc " + "a".repeat(64),
                        "CAM 1 1 2561 500 0.0 0.0 0.0 0.0 0.0",
                        "CAM 1 1 800 1601 0.0 0.0 0.0 0.0 0.0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKX 0a141e324664 0 0 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 500 9 3",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e32466 0 0 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0A141E324664 0 0 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 1001 0 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 0 0 1000001",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 0 1e300 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 0 01 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 SKY 0a141e324664 0 0",
                        "CAM 1 1 800 500 NaN 0.0 0.0 0.0 0.0",
                        "CAM 1 1 800 500 0 0.0 0.0 0.0 0.0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 1.6",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 1e-1")) {
            byte[] line = (PREFIX + tail + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class, () -> WorldStreamControl.decode(line, line.length), tail);
        }
    }

    @Test
    void stopsOversizedInputAtBoundWithoutReadingOrAllocatingTheRest() {
        var endless =
                new InputStream() {
                    int count;

                    @Override
                    public int read() {
                        assertTrue(
                                ++count <= WorldStreamControl.MAX_LINE_BYTES,
                                "reader exceeded bound");
                        return 'A';
                    }
                };
        assertThrows(IOException.class, () -> WorldStreamControl.read(endless));
        assertEquals(WorldStreamControl.MAX_LINE_BYTES, endless.count);
    }

    @Test
    void rejectsTruncationControlsVersionsAndZeroNonce() {
        for (byte[] bytes :
                List.of(
                        (PREFIX + "CLOSE").getBytes(StandardCharsets.US_ASCII),
                        (PREFIX + "CLO\0SE\n").getBytes(StandardCharsets.US_ASCII),
                        (PREFIX.replace("WS2", "WS1") + "CLOSE\n")
                                .getBytes(StandardCharsets.US_ASCII),
                        ("WS2 " + "0".repeat(32) + " CLOSE\n").getBytes(StandardCharsets.US_ASCII),
                        new byte[] {(byte) 0xff, '\n'})) {
            assertThrows(
                    IOException.class,
                    () -> WorldStreamControl.read(new ByteArrayInputStream(bytes)));
        }
    }

    @Test
    void longCanonicalCameraWithMaximumCountersFitsWireBound() {
        var camera =
                new WorldStreamControl.Camera(
                        NONCE,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        800,
                        500,
                        new TerrainCamera(
                                -0.00000000000000012345678901234567,
                                -0.00000000000000012345678901234567,
                                -0.00000000000000012345678901234567,
                                -0.00000000000000012345678901234567,
                                -0.00000000000000012345678901234567));
        assertTrue(WorldStreamControl.encode(camera).length <= 256);
    }

    @Test
    void horizonCommandsRoundTripOutsideTheTransactionSequence() throws IOException {
        var command = new WorldStreamControl.Horizon(NONCE, 4_096, "c".repeat(64));
        byte[] line = WorldStreamControl.encode(command);
        assertEquals(
                PREFIX + "HORIZON 1 horizon-1.wsh 4096 " + "c".repeat(64) + "\n",
                new String(line, StandardCharsets.US_ASCII));
        assertEquals(command, WorldStreamControl.decode(line, line.length));
        for (String body :
                new String[] {
                    "HORIZON 2 horizon-1.wsh 4096 " + "c".repeat(64),
                    "HORIZON 1 horizon-2.wsh 4096 " + "c".repeat(64),
                    "HORIZON 1 horizon-1.wsh 11 " + "c".repeat(64),
                    "HORIZON 1 horizon-1.wsh 8388609 " + "c".repeat(64),
                    "HORIZON 1 horizon-1.wsh 4096 " + "C".repeat(64),
                    "HORIZON 1 horizon-1.wsh 4096"
                }) {
            byte[] bad = (PREFIX + body + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(IOException.class, () -> WorldStreamControl.decode(bad, bad.length), body);
        }
    }

    /**
     * The longest possible camera line, every field at its longest text and the sky tail at its
     * widest (a weight or opacity of 1 encodes as {@code 1000}, the largest frame is 2560 x 1600),
     * still fits the control line bound (#177).
     */
    @Test
    void theLongestCameraWithASkyFitsTheLine() throws IOException {
        double longest = -1.2345678901234567E-300;
        assertEquals(24, Double.toString(longest).length());
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {255, 255, 255},
                        new float[] {255, 255, 255},
                        1,
                        WorldStreamTransport.Sky.MAX_YARDS - 1,
                        WorldStreamTransport.Sky.MAX_YARDS,
                        new float[] {255, 255, 255},
                        new float[] {255, 255, 255},
                        1,
                        filled(12, 255),
                        filled(4, 1),
                        1);
        var worst =
                new WorldStreamControl.Camera(
                        NONCE,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        WorldFrameLimits.MAX_WIDTH,
                        WorldFrameLimits.MAX_HEIGHT,
                        new TerrainCamera(longest, longest, longest, longest, longest),
                        sky);
        byte[] encoded = WorldStreamControl.encode(worst);
        String line = new String(encoded, StandardCharsets.US_ASCII);
        assertTrue(line.contains(" SKY ffffffffffff 1000 "), line);
        assertTrue(
                line.contains(
                        " LIGHT ffffffffffff 1000 WATER "
                                + "ff".repeat(12)
                                + " 1000 1000 1000 1000 1000"),
                line);
        // With its newline.
        assertEquals(333, encoded.length, line);
        assertTrue(encoded.length <= WorldStreamControl.MAX_LINE_BYTES);
        assertEquals(worst, WorldStreamControl.decode(encoded, encoded.length));
        // The widest body tail on top: the longest line of all, pinned to the limit's reason.
        var body =
                new WorldStreamTransport.Body(
                        -WorldStreamTransport.Body.MAX_YARDS,
                        -WorldStreamTransport.Body.MAX_YARDS,
                        -WorldStreamTransport.Body.MAX_YARDS,
                        6.2831,
                        0xffff,
                        com.vibeminer.rendering.models.PreviewAnimation.MAX_DURATION_MILLIS,
                        true);
        var widest =
                new WorldStreamControl.Camera(
                        NONCE,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        WorldFrameLimits.MAX_WIDTH,
                        WorldFrameLimits.MAX_HEIGHT,
                        new TerrainCamera(longest, longest, longest, longest, longest),
                        sky,
                        body);
        byte[] all = WorldStreamControl.encode(widest);
        String text = new String(all, StandardCharsets.US_ASCII);
        assertTrue(
                text.endsWith(" BODY -100000000 -100000000 -100000000 62831 65535 600000 1\n"),
                text);
        assertEquals(392, all.length, text);
        assertTrue(all.length <= WorldStreamControl.MAX_LINE_BYTES);
        assertEquals(widest, WorldStreamControl.decode(all, all.length));
    }

    @Test
    void theBodyTailRoundTripsQuantizedAndRefusesMalformedTails() throws IOException {
        var body = new WorldStreamTransport.Body(-150.12345, 260.0004, 10.5, -0.5, 4, 133, false);
        assertEquals(-150.123, body.x(), 0);
        assertEquals(260.0, body.y(), 0);
        assertEquals(2 * Math.PI - .5, body.yaw(), 1e-4, "the yaw is kept in [0, 2 pi)");
        for (var sky : new WorldStreamTransport.Sky[] {null, WorldStreamTransport.Sky.EXPLORER}) {
            var camera =
                    new WorldStreamControl.Camera(
                            NONCE, 7, 2, 800, 500, new TerrainCamera(1, 2, 3, 0, 0), sky, body);
            byte[] line = WorldStreamControl.encode(camera);
            assertEquals(camera, WorldStreamControl.decode(line, line.length));
            assertTrue(
                    new String(line, StandardCharsets.US_ASCII)
                            .endsWith(" BODY -150123 260000 10500 57832 4 133 0\n"));
        }
        var model = new WorldStreamControl.BodyModel(NONCE, 4096, "a".repeat(64));
        byte[] line = WorldStreamControl.encode(model);
        assertEquals(
                PREFIX + "BODY_MODEL 1 body-1.wsb 4096 " + "a".repeat(64) + "\n",
                new String(line, StandardCharsets.US_ASCII));
        assertEquals(model, WorldStreamControl.decode(line, line.length));
        for (String tail :
                List.of(
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 1 2 3 4 5 6 2",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 01 2 3 4 5 6 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY -0 2 3 4 5 6 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 1 2 3 62832 5 6 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 1 2 3 4 65536 6 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 1 2 3 4 5 600001 0",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 1 2 3 4 5 6",
                        "CAM 1 1 800 500 0.0 0.0 0.0 0.0 0.0 BODY 100000001 2 3 4 5 6 0",
                        "BODY_MODEL 1 body-2.wsb 4096 " + "a".repeat(64),
                        "BODY_MODEL 1 body-1.wsb 15 " + "a".repeat(64))) {
            byte[] bytes = (PREFIX + tail + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class, () -> WorldStreamControl.decode(bytes, bytes.length), tail);
        }
    }

    /** Sky values are checked whatever the weight, and quantized to what the line carries. */
    @Test
    void skyValuesAreValidatedAndQuantized() {
        float[] grey = {10, 10, 10};
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTransport.Sky(grey, grey, 0, 1e30f, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTransport.Sky(grey, grey, 0, 0, Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldStreamTransport.Sky(grey, new float[] {0, 0, 256}, 0, 0, 0));
        var quantized =
                new WorldStreamTransport.Sky(
                        new float[] {10.4f, 10.6f, 0}, grey, 0.12345, 250.4f, 999.6f);
        assertEquals(10, quantized.horizon()[0]);
        assertEquals(11, quantized.horizon()[1]);
        assertEquals(0.123, quantized.fogWeight());
        assertEquals(250, quantized.recordedStart());
        assertEquals(1000, quantized.recordedEnd());
    }

    /** #177 step 2c: a sky's recorded light rides the camera line exactly, and only with weight. */
    @Test
    void aSkysRecordedLightRoundTripsAndAZeroWeightLightIsRefused() throws IOException {
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {10, 20, 30},
                        new float[] {40, 50, 60},
                        0.5,
                        100,
                        900,
                        new float[] {255, 224, 192},
                        new float[] {64, 64, 96},
                        0.75);
        var camera =
                new WorldStreamControl.Camera(
                        NONCE, 7, 1, 800, 500, new TerrainCamera(1, 2, 3, 0, -0.5), sky);
        byte[] encoded = WorldStreamControl.encode(camera);
        String line = new String(encoded, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(line.contains(" LIGHT ffe0c0404060 750"), line);
        assertEquals(camera, WorldStreamControl.decode(encoded, encoded.length));
        // A sky without recorded light writes no LIGHT tail.
        var dry =
                new WorldStreamControl.Camera(
                        NONCE,
                        8,
                        1,
                        800,
                        500,
                        new TerrainCamera(1, 2, 3, 0, -0.5),
                        new WorldStreamTransport.Sky(
                                new float[] {10, 20, 30}, new float[] {40, 50, 60}, 0, 0, 0));
        assertFalse(
                new String(
                                WorldStreamControl.encode(dry),
                                java.nio.charset.StandardCharsets.US_ASCII)
                        .contains("LIGHT"));
        byte[] zero =
                line.replace(" LIGHT ffe0c0404060 750", " LIGHT ffe0c0404060 0")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> WorldStreamControl.decode(zero, zero.length));
    }

    /** A light weight that rounds to 0 keeps no light colours, so the sky round-trips. */
    @Test
    void aLightWeightThatRoundsToZeroKeepsNoColours() throws IOException {
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {10, 20, 30},
                        new float[] {40, 50, 60},
                        0,
                        0,
                        0,
                        new float[] {255, 224, 192},
                        new float[] {64, 64, 96},
                        0.0004);
        assertEquals(0, sky.lightWeight());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[3], sky.direct());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[3], sky.ambient());
        var camera =
                new WorldStreamControl.Camera(
                        NONCE, 9, 1, 800, 500, new TerrainCamera(1, 2, 3, 0, -0.5), sky);
        byte[] encoded = WorldStreamControl.encode(camera);
        assertEquals(camera, WorldStreamControl.decode(encoded, encoded.length));
    }

    private static float[] filled(int length, float value) {
        float[] out = new float[length];
        java.util.Arrays.fill(out, value);
        return out;
    }

    /** #171: a sky's recorded water light rides the camera line exactly, after any light. */
    @Test
    void aSkysRecordedWaterLightRoundTripsWithAndWithoutLight() throws IOException {
        float[] water = new float[12];
        for (int i = 0; i < 12; i++) {
            water[i] = i * 20;
        }
        float[] alphas = {0.25f, 1, 0.5f, 0.75f};
        for (double light : new double[] {0, 0.5}) {
            var sky =
                    new WorldStreamTransport.Sky(
                            new float[] {10, 20, 30},
                            new float[] {40, 50, 60},
                            0,
                            0,
                            0,
                            new float[] {255, 224, 192},
                            new float[] {64, 64, 96},
                            light,
                            water,
                            alphas,
                            0.6);
            var camera =
                    new WorldStreamControl.Camera(
                            NONCE, 11, 1, 800, 500, new TerrainCamera(1, 2, 3, 0, -0.5), sky);
            byte[] encoded = WorldStreamControl.encode(camera);
            String line = new String(encoded, java.nio.charset.StandardCharsets.US_ASCII);
            assertTrue(line.contains(" WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 600"), line);
            assertEquals(camera, WorldStreamControl.decode(encoded, encoded.length));
        }
    }

    /** A camera line with a recorded light and water light tail, as text. */
    private static String litWaterLine() {
        float[] water = new float[12];
        for (int i = 0; i < 12; i++) {
            water[i] = i * 20;
        }
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {10, 20, 30},
                        new float[] {40, 50, 60},
                        0,
                        0,
                        0,
                        new float[] {255, 224, 192},
                        new float[] {64, 64, 96},
                        0.5,
                        water,
                        new float[] {0.25f, 1, 0.5f, 0.75f},
                        0.6);
        var camera =
                new WorldStreamControl.Camera(
                        NONCE, 11, 1, 800, 500, new TerrainCamera(1, 2, 3, 0, -0.5), sky);
        return new String(WorldStreamControl.encode(camera), StandardCharsets.US_ASCII);
    }

    /**
     * #171: the WATER tail is exact. A zero weight, a colour token of the wrong length or case,
     * WATER before LIGHT, an opacity past 1000, a non-canonical number, a missing or extra field or
     * a misspelled keyword is refused.
     */
    @Test
    void malformedWaterTailsAreRefused() throws IOException {
        String line = litWaterLine();
        String tail = " LIGHT ffe0c0404060 500 WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 600";
        assertTrue(line.contains(tail), line);
        byte[] good = line.getBytes(StandardCharsets.US_ASCII);
        WorldStreamControl.decode(good, good.length);
        String water = " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 600";
        String light = " LIGHT ffe0c0404060 500";
        // The water parser itself refuses these, not only the canonical re-encoding that follows
        // it (a zero weight or upper-case colours would otherwise re-encode differently).
        for (String bad :
                List.of(
                        // zero water weight
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 0",
                        // colour token one digit short, one long, four short, upper case
                        light + " WATER 0014283c5064788ca0b4c8d 250 1000 500 750 600",
                        light + " WATER 0014283c5064788ca0b4c8dc0 250 1000 500 750 600",
                        light + " WATER 0014283c5064788ca0b4 250 1000 500 750 600",
                        light + " WATER 0014283C5064788CA0B4C8DC 250 1000 500 750 600",
                        // misspelled keyword
                        light + " water 0014283c5064788ca0b4c8dc 250 1000 500 750 600")) {
            byte[] encoded = line.replace(tail, bad).getBytes(StandardCharsets.US_ASCII);
            var refused =
                    assertThrows(
                            IOException.class,
                            () -> WorldStreamControl.decode(encoded, encoded.length),
                            bad);
            assertEquals("Invalid camera water light", refused.getCause().getMessage(), bad);
        }
        for (String bad :
                List.of(
                        // WATER before LIGHT
                        water + light,
                        // an opacity or weight past 1000
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1001 500 750 600",
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 1001",
                        // non-canonical numbers
                        light + " WATER 0014283c5064788ca0b4c8dc 0250 1000 500 750 600",
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 +500 750 600",
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 600.0",
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 -750 600",
                        // a missing field, an extra one
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750",
                        light + " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 600 1",
                        light + " WATER 0014283c5064788ca0b4c8dc",
                        // repeated tails
                        light + water + water,
                        light + light + water)) {
            byte[] encoded = line.replace(tail, bad).getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class,
                    () -> WorldStreamControl.decode(encoded, encoded.length),
                    bad);
        }
        // Without LIGHT, the WATER tail alone is accepted, and its zero weight still refused.
        byte[] alone = line.replace(tail, water).getBytes(StandardCharsets.US_ASCII);
        var camera = (WorldStreamControl.Camera) WorldStreamControl.decode(alone, alone.length);
        assertEquals(0.6, camera.sky().waterWeight());
        byte[] zero =
                line.replace(tail, " WATER 0014283c5064788ca0b4c8dc 250 1000 500 750 0")
                        .getBytes(StandardCharsets.US_ASCII);
        var refused =
                assertThrows(IOException.class, () -> WorldStreamControl.decode(zero, zero.length));
        assertEquals("Invalid camera water light", refused.getCause().getMessage());
    }

    /** A water weight that rounds to 0 keeps no water colours or opacities, so it round-trips. */
    @Test
    void aWaterWeightThatRoundsToZeroKeepsNoValues() throws IOException {
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {10, 20, 30},
                        new float[] {40, 50, 60},
                        0,
                        0,
                        0,
                        new float[] {255, 224, 192},
                        new float[] {64, 64, 96},
                        0.5,
                        filled(12, 200),
                        filled(4, 0.8f),
                        0.0004);
        assertEquals(0, sky.waterWeight());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[12], sky.water());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new float[4], sky.waterAlphas());
        var camera =
                new WorldStreamControl.Camera(
                        NONCE, 12, 1, 800, 500, new TerrainCamera(1, 2, 3, 0, -0.5), sky);
        byte[] encoded = WorldStreamControl.encode(camera);
        assertFalse(new String(encoded, StandardCharsets.US_ASCII).contains("WATER"));
        assertEquals(camera, WorldStreamControl.decode(encoded, encoded.length));
    }
}
