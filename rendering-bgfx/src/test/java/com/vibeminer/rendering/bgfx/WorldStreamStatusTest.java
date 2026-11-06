package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldStreamStatusTest {
    private static final String PREFIX = "WS2 " + "ab".repeat(16) + " ";

    private static WorldStreamStatus.Message parse(String body) throws IOException {
        byte[] bytes = (PREFIX + body + "\n").getBytes(StandardCharsets.US_ASCII);
        return WorldStreamStatus.decode(bytes, bytes.length);
    }

    @Test
    void allPinnedStatusesDecodeIntoDetachedTypedMetadata() throws Exception {
        assertInstanceOf(WorldStreamStatus.Session.class, parse("SESSION TERRAIN_BASIC_V1"));
        var credit = (WorldStreamStatus.Credit) parse("CREDIT 12 9 18");
        assertEquals(new WorldStreamState.Lease(9, 18, 12), credit.lease());
        assertEquals(
                new WorldStreamState.Nonce(0xababababababababL, 0xababababababababL),
                credit.nonce());
        var camera = (WorldStreamStatus.CameraApplied) parse("CAMERA_APPLIED 3 800 500");
        assertEquals(3, camera.policy());
        assertEquals(800, camera.width());
        assertEquals(500, camera.height());
        assertEquals(
                WorldStreamStatus.InputKind.TILE,
                ((WorldStreamStatus.InputConsumed) parse("INPUT_CONSUMED 1 TILE")).kind());
        assertEquals(
                WorldStreamStatus.InputKind.COMMIT,
                ((WorldStreamStatus.InputConsumed) parse("INPUT_CONSUMED 2 COMMIT")).kind());
        var staged = (WorldStreamStatus.Staged) parse("STAGED 9 12 9 18 1 256");
        assertEquals(256, staged.coverage());
        assertEquals(0, ((WorldStreamStatus.Staged) parse("STAGED 9 12 9 18 2 0")).coverage());
        assertEquals(1, ((WorldStreamStatus.Committed) parse("COMMITTED 10 0 1")).next());
        assertEquals(9, ((WorldStreamStatus.Canceled) parse("CANCELED 9 12 9 18")).transaction());
        assertInstanceOf(WorldStreamStatus.Closed.class, parse("CLOSED"));
        assertEquals(7, ((WorldStreamStatus.FramePublished) parse("FRAME 7")).sequence());
        var presented = (WorldStreamStatus.Presented) parse("PRESENTED 42 3");
        assertEquals(42, presented.camera());
        assertEquals(3, presented.content());
        assertEquals(0, ((WorldStreamStatus.Presented) parse("PRESENTED 1 0")).content());
        assertEquals(
                WorldFrameFileV2.MAX_SEQUENCE,
                ((WorldStreamStatus.FramePublished) parse("FRAME " + WorldFrameFileV2.MAX_SEQUENCE))
                        .sequence());
        for (var reason : WorldStreamStatus.Rejection.values()) {
            assertEquals(
                    reason, ((WorldStreamStatus.Rejected) parse("REJECTED 1 " + reason)).reason());
        }
        assertEquals(
                0, ((WorldStreamStatus.Rejected) parse("REJECTED 0 PROTOCOL_ERROR")).transaction());
    }

    @Test
    void malformedArityCountersDomainsAndWhitespaceRefuse() {
        for (String body :
                List.of(
                        "",
                        "CLOSED ",
                        " CLOSED",
                        "CLOSED ignored",
                        "SESSION OTHER",
                        "SESSION",
                        "CREDIT 01 0 1",
                        "CREDIT 1 26 1",
                        "CREDIT 0 0 1",
                        "CREDIT 1 0 0",
                        "CREDIT +1 0 1",
                        "CREDIT 9223372036854775808 0 1",
                        "CREDIT 1 2147483648 1",
                        "CAMERA_APPLIED 0 800 500",
                        "CAMERA_APPLIED 1 801 500",
                        "CAMERA_APPLIED 1 1 501",
                        "CAMERA_APPLIED 1 0 1",
                        "CAMERA_APPLIED 1 1 -1",
                        "PRESENTED",
                        "PRESENTED 1",
                        "PRESENTED 0 1",
                        "PRESENTED 01 1",
                        "PRESENTED 1 -1",
                        "PRESENTED 1 2 3",
                        "FRAME",
                        "FRAME 0",
                        "FRAME 01",
                        "FRAME 1 2",
                        "FRAME " + (WorldFrameFileV2.MAX_SEQUENCE + 1),
                        "INPUT_CONSUMED 1 tile",
                        "INPUT_CONSUMED 0 TILE",
                        "INPUT_CONSUMED 1 TILE extra",
                        "STAGED 1 1 0 1 1 0",
                        "STAGED 1 1 0 1 1 257",
                        "STAGED 1 1 0 1 2 1",
                        "STAGED 1 1 0 1 3 0",
                        "COMMITTED 1 1 3",
                        "COMMITTED 0 0 1",
                        "COMMITTED 1 9223372036854775807 1",
                        "REJECTED 0 INVALID_PACKET",
                        "REJECTED 1 UNKNOWN",
                        "REJECTED 9223372036854775807 BUSY",
                        "CANCELED 0 1 0 1",
                        "CREDIT 1  0 1",
                        "UNKNOWN")) {
            assertThrows(IOException.class, () -> parse(body), body);
        }
    }

    @Test
    void framingIsAsciiCanonicalAndNeverReadsByte257ToRecover() throws Exception {
        var reads = new AtomicInteger();
        InputStream endless =
                new InputStream() {
                    @Override
                    public int read() {
                        if (reads.incrementAndGet() > 256) {
                            throw new AssertionError("Read beyond fixed limit");
                        }
                        return 'a';
                    }
                };
        assertThrows(IOException.class, () -> WorldStreamStatus.read(endless));
        assertEquals(256, reads.get());
        assertNull(WorldStreamStatus.read(new ByteArrayInputStream(new byte[0])));
        for (String text :
                List.of(
                        PREFIX + "CLOSED",
                        PREFIX + "CLOSED\r\n",
                        PREFIX + "CLO\tSED\n",
                        PREFIX.toUpperCase() + "CLOSED\n",
                        "WS2 " + "0".repeat(32) + " CLOSED\n",
                        "WS2 " + "ab".repeat(15) + " CLOSED\n")) {
            assertThrows(
                    IOException.class,
                    () ->
                            WorldStreamStatus.read(
                                    new ByteArrayInputStream(
                                            text.getBytes(StandardCharsets.US_ASCII))));
        }
        byte[] invalid = (PREFIX + "CLOSED\n").getBytes(StandardCharsets.US_ASCII);
        invalid[40] = (byte) 255;
        assertThrows(IOException.class, () -> WorldStreamStatus.decode(invalid, invalid.length));
        var stream =
                new ByteArrayInputStream(
                        (PREFIX + "CLOSED\n" + PREFIX + "CREDIT 1 0 1\n")
                                .getBytes(StandardCharsets.US_ASCII));
        assertInstanceOf(WorldStreamStatus.Closed.class, WorldStreamStatus.read(stream));
        assertInstanceOf(WorldStreamStatus.Credit.class, WorldStreamStatus.read(stream));
        assertNull(WorldStreamStatus.read(stream));
    }
}
