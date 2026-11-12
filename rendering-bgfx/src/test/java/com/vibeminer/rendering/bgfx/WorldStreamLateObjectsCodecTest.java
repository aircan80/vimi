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
                PREFIX
                        + "OBJECTS 4 37 9 23 tile-4.wso "
                        + WorldStreamObjectsFile.MAX_BYTES
                        + " "
                        + "a".repeat(64)
                        + " 0\n",
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
        var widestReplacement =
                new WorldStreamControl.LateObjects(
                        NONCE,
                        Long.MAX_VALUE - 1,
                        new WorldStreamState.Lease(9, Long.MAX_VALUE, Long.MAX_VALUE),
                        WorldStreamObjectsFile.MAX_BYTES,
                        "f".repeat(64),
                        true);
        assertTrue(
                WorldStreamControl.encode(widestReplacement).length
                        <= WorldStreamControl.MAX_LINE_BYTES);
        // With the widest epoch and an asset pack (#380 stage 4).
        var widestPack =
                new WorldStreamControl.LateObjects(
                        NONCE,
                        Long.MAX_VALUE - 1,
                        new WorldStreamState.Lease(9, Long.MAX_VALUE, Long.MAX_VALUE),
                        WorldStreamObjectsFile.MAX_BYTES,
                        "f".repeat(64),
                        true,
                        Long.MAX_VALUE,
                        WorldStreamObjectsFile.MAX_BYTES,
                        "e".repeat(64));
        byte[] packed = WorldStreamControl.encode(widestPack);
        assertTrue(packed.length <= WorldStreamControl.MAX_LINE_BYTES);
        assertEquals(widestPack, WorldStreamControl.decode(packed, packed.length));
    }

    @Test
    void objectsCommandCarriesItsAssetEpochAndPack() throws IOException {
        var command =
                new WorldStreamControl.LateObjects(
                        NONCE, 4, LEASE, 4096, "a".repeat(64), false, 3, 512, "b".repeat(64));
        byte[] encoded = WorldStreamControl.encode(command);
        assertEquals(
                PREFIX
                        + "OBJECTS 4 37 9 23 tile-4.wso 4096 "
                        + "a".repeat(64)
                        + " 3 assets-4.wsa 512 "
                        + "b".repeat(64)
                        + "\n",
                new String(encoded, StandardCharsets.US_ASCII));
        assertEquals(command, WorldStreamControl.decode(encoded, encoded.length));
        assertTrue(command.hasPack());
        String sha = "a".repeat(64), pack = "b".repeat(64);
        for (String tail :
                List.of(
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha + " 3 assets-5.wsa 512 " + pack,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha + " 3 assets-4.wsa 255 " + pack,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha + " 3 assets-4.wsa 512",
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha + " -1",
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + sha + " 03")) {
            byte[] line = (PREFIX + tail + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class, () -> WorldStreamControl.decode(line, line.length), tail);
        }
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new WorldStreamControl.LateObjects(
                                NONCE, 4, LEASE, 4096, sha, false, 0, 512, null));
    }

    @Test
    void replacementCommandRoundTripsUnderItsOwnName() throws IOException {
        var command =
                new WorldStreamControl.LateObjects(NONCE, 4, LEASE, 4096, "a".repeat(64), true);
        byte[] encoded = WorldStreamControl.encode(command);
        assertEquals(
                PREFIX + "OBJECTS_REPLACE 4 37 9 23 tile-4.wso 4096 " + "a".repeat(64) + " 0\n",
                new String(encoded, StandardCharsets.US_ASCII));
        assertEquals(command, WorldStreamControl.decode(encoded, encoded.length));
        byte[] foreign =
                (PREFIX + "OBJECTS_REPLACE 4 37 9 23 tile-5.wso 4096 " + "a".repeat(64) + " 0\n")
                        .getBytes(StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> WorldStreamControl.decode(foreign, foreign.length));
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
                        "OBJECTS 4 37 9 23 tile-4.wso "
                                + (WorldStreamObjectsFile.MAX_BYTES + 1)
                                + " "
                                + sha,
                        "OBJECTS 0 37 9 23 tile-0.wso 4096 " + sha,
                        "OBJECTS 4 37 26 23 tile-4.wso 4096 " + sha,
                        "OBJECTS 4 0 9 23 tile-4.wso 4096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 " + "A".repeat(64),
                        "OBJECTS 4 37 9 23 tile-4.wso 04096 " + sha,
                        "OBJECTS 4 37 9 23 tile-4.wso 4096 0")) {
            byte[] line = (PREFIX + tail + "\n").getBytes(StandardCharsets.US_ASCII);
            assertThrows(
                    IOException.class, () -> WorldStreamControl.decode(line, line.length), tail);
        }
    }

    @Test
    void objectsDoneDecodesEveryOutcomeAndRefusesOthers() throws IOException {
        String store = " 2 0 0 5 1234 00000000000000ff";
        for (var outcome : WorldStreamStatus.LateOutcome.values()) {
            var done =
                    (WorldStreamStatus.ObjectsDone)
                            status("OBJECTS_DONE 4 37 9 23 " + outcome + store);
            assertEquals(4, done.transaction());
            assertEquals(LEASE, done.lease());
            assertSame(outcome, done.outcome());
            assertEquals(
                    new WorldStreamAssetStore.Report(2, false, 0, 5, 1234, 255), done.assets());
        }
        // The widest report fits the status line, and reads back.
        var widest =
                new WorldStreamAssetStore.Report(
                        Long.MAX_VALUE,
                        true,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        Long.MAX_VALUE,
                        -1);
        var done =
                (WorldStreamStatus.ObjectsDone)
                        status(
                                "OBJECTS_DONE 9223372036854775806 9223372036854775807 25"
                                        + " 9223372036854775807 ATTACHED "
                                        + widest.text());
        assertEquals(widest, done.assets());
        for (String body :
                List.of(
                        "OBJECTS_DONE 4 37 9 23",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED",
                        "OBJECTS_DONE 4 37 9 23 DRAWN" + store,
                        "OBJECTS_DONE 4 37 9 23 attached" + store,
                        "OBJECTS_DONE 0 37 9 23 ATTACHED" + store,
                        "OBJECTS_DONE 4 37 9 0 ATTACHED" + store,
                        "OBJECTS_DONE 4 37 9 23 ATTACHED" + store + " x",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED 2 2 0 5 1234 00000000000000ff",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED 2 0 1 5 1234 00000000000000ff",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED 2 0 0 5 1234 ff",
                        "OBJECTS_DONE 4 37 9 23 ATTACHED 2 0 0 5 1234 00000000000000FF",
                        "OBJECTS_DONE 4 37 9 23 MISSING 2 1 0 5 1234 00000000000000ff")) {
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
