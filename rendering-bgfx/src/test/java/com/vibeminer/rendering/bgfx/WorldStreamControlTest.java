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
                        "OFFER 1 37 9 23 tile-1.wst 2907265 " + "a".repeat(64),
                        "COMMIT 1 commit-1.wsc " + "A".repeat(64),
                        "COMMIT 1 commit-01.wsc " + "a".repeat(64),
                        "CAM 1 1 2561 500 0.0 0.0 0.0 0.0 0.0",
                        "CAM 1 1 800 1601 0.0 0.0 0.0 0.0 0.0",
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
}
