package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wire codecs and the child mailbox lane of late BASIC_EXTERIOR objects. */
class WorldStreamLateObjectsCodecTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, -2);
    private static final WorldStreamState.Lease LEASE = new WorldStreamState.Lease(9, 23, 37);
    private static final String PREFIX = "WS2 0000000000000001fffffffffffffffe ";

    @Test
    void objectsCommandRoundTripsWithinTheLineBound() throws IOException {
        var command =
                new WorldStreamControl.LateObjects(
                        NONCE, 4, LEASE, WorldStreamObjectsFile.MAX_BYTES, "a".repeat(64));
        byte[] encoded = WorldStreamControl.encode(command);
        assertEquals(
                PREFIX + "OBJECTS 4 37 9 23 tile-4.wso 16777216 " + "a".repeat(64) + "\n",
                new String(encoded, StandardCharsets.US_ASCII));
        assertEquals(command, WorldStreamControl.decode(encoded, encoded.length));
        var widest =
                new WorldStreamControl.LateObjects(
                        NONCE,
                        Long.MAX_VALUE - 1,
                        new WorldStreamState.Lease(9, Long.MAX_VALUE, Long.MAX_VALUE),
                        WorldStreamObjectsFile.MAX_BYTES,
                        "f".repeat(64));
        assertTrue(WorldStreamControl.encode(widest).length <= WorldStreamControl.MAX_LINE_BYTES);
    }

    @Test
    void objectsCommandRefusesForeignNamesLengthsAndArity() {
        String sha = "a".repeat(64);
        for (String tail :
                List.of(
                        "OBJECTS 4 37 9 23 tile-5.wso 4096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wst 4096 " + sha,
                        "OBJECTS 4 37 9 23 ../tile-4.wso 4096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 255 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 16777217 " + sha,
                        "OBJECTS 0 37 9 23 tile-0.wso 4096 " + sha,
                        "OBJECTS 4 37 26 23 tile-4.wso 4096 " + sha,
                        "OBJECTS 4 0 9 23 tile-4.wso 4096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + "A".repeat(64),
                        "OBJECTS 4 37 9 23 tile-4.wso 04096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096")) {
            byte[] line = (PREFIX + tail + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class, () -> WorldStreamControl.decode(line, line.length), tail);
        }
    }

    @Test
    void objectsDoneDecodesEveryOutcomeAndRefusesOthers() throws IOException {
        for (var outcome : WorldStreamStatus.LateOutcome.values()) {
            var done = (WorldStreamStatus.ObjectsDone) status("OBJECTS_DONE 4 37 9 23 " + outcome);
            assertEquals(4, done.transaction());
            assertEquals(LEASE, done.lease());
            assertSame(outcome, done.outcome());
        }
        for (String body :
                List.of(
                        "OBJECTS_DONE 4 37 9 23",
                        "OBJECTS_DONE 4 37 9 23 DRAWN",
                        "OBJECTS_DONE 4 37 9 23 attached",
                        "OBJECTS_DONE 0 37 9 23 ATTACHED",
                        "OBJECTS_DONE 4 37 9 0 ATTACHED",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED x")) {
            assertThrows(IOException.class, () -> status(body), body);
        }
    }

    @Test
    void mailboxKeepsObjectsInTheirOwnOneDeepLane() {
        var mailbox = new WorldControlMailbox(NONCE);
        var late = new WorldStreamControl.LateObjects(NONCE, 4, LEASE, 4096, "a".repeat(64));
        for (int i = 0; i < WorldControlMailbox.TRANSACTIONS; i++) {
            assertEquals(
                    WorldControlMailbox.Result.ACCEPTED,
                    mailbox.offer(new WorldStreamControl.Commit(NONCE, 10 + i, "b".repeat(64))));
        }
        // A full transaction queue does not refuse the objects lane, and objects never enter it.
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(late));
        assertEquals(10, ((WorldStreamControl.Commit) mailbox.takeTransaction()).transaction());
        assertSame(late, mailbox.takeObjects());
        assertNull(mailbox.takeObjects());
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(late));
        // A second in-flight objects command is a parent credit violation.
        assertEquals(WorldControlMailbox.Result.OVERFLOW, mailbox.offer(late));
        assertTrue(mailbox.failed() && mailbox.closing());
        assertNull(mailbox.takeObjects());
        assertNull(mailbox.takeTransaction());

        var closing = new WorldControlMailbox(NONCE);
        assertEquals(WorldControlMailbox.Result.ACCEPTED, closing.offer(late));
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                closing.offer(new WorldStreamControl.Close(NONCE)));
        assertNull(closing.takeObjects());
        assertEquals(WorldControlMailbox.Result.CLOSED, closing.offer(late));
    }

    private static WorldStreamStatus.Message status(String body) throws IOException {
        byte[] bytes = (PREFIX + body + "\n").getBytes(StandardCharsets.US_ASCII);
        return WorldStreamStatus.decode(bytes, bytes.length);
    }
}
