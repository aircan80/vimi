package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
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
                        assertTrue(++count <= 256, "reader exceeded bound");
                        return 'A';
                    }
                };
        assertThrows(IOException.class, () -> WorldStreamControl.read(endless));
        assertEquals(256, endless.count);
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
     * widest, still fits the control line bound (#177).
     */
    @Test
    void theLongestCameraWithASkyFitsTheLine() throws IOException {
        double longest = -1.2345678901234567E-300;
        assertEquals(24, Double.toString(longest).length());
        var sky =
                new WorldStreamTransport.Sky(
                        new float[] {255, 255, 255},
                        new float[] {255, 255, 255},
                        0.999,
                        WorldStreamTransport.Sky.MAX_YARDS - 1,
                        WorldStreamTransport.Sky.MAX_YARDS);
        var worst =
                new WorldStreamControl.Camera(
                        NONCE,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        800,
                        500,
                        new TerrainCamera(longest, longest, longest, longest, longest),
                        sky);
        byte[] encoded = WorldStreamControl.encode(worst);
        assertTrue(
                encoded.length <= WorldStreamControl.MAX_LINE_BYTES,
                "camera line is " + encoded.length + " bytes");
        assertEquals(worst, WorldStreamControl.decode(encoded, encoded.length));
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
}
