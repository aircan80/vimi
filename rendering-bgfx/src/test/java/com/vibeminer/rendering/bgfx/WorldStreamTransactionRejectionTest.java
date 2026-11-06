package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Issue #365: a surprise in one transaction rejects that transaction and the session keeps
 * streaming; session identity mismatches stay fatal.
 */
class WorldStreamTransactionRejectionTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(5, 9);

    private WorldStreamState.Lease lease;

    /** A state whose incoming lease was offered for (x, y) and is now decoded by the worker. */
    private WorldStreamState offered(long transaction, int x, int y) {
        var state = new WorldStreamState(NONCE);
        lease = state.credit();
        assertEquals(WorldStreamState.Result.OK, state.offer(NONCE, transaction, lease, x, y));
        return state;
    }

    @Test
    void aTileWhoseHeaderNamesOtherCoordinatesIsRejectedBeforeTheRendererSeesIt() {
        var state = offered(1, 7, 19);
        var began = new AtomicBoolean();
        var admitted =
                WorldStreamSession.admit(
                        state,
                        lease,
                        8,
                        19,
                        tile -> {
                            began.set(true);
                            return "upload";
                        },
                        "tile");
        assertNull(admitted.upload());
        assertNotNull(admitted.reason());
        assertFalse(began.get(), "the renderer never mutates a slot for a rejected tile");
        // The lease retired as an invalid packet; the session grants the next credit and
        // keeps streaming.
        var next = state.credit();
        assertNotNull(next);
        assertNotEquals(lease, next);
        assertEquals(WorldStreamState.Result.OK, state.offer(NONCE, 2, next, 7, 19));
    }

    @Test
    void anAcceptedTileBeginsItsUpload() {
        var state = offered(1, 7, 19);
        var admitted = WorldStreamSession.admit(state, lease, 7, 19, tile -> "upload:" + tile, "t");
        assertEquals("upload:t", admitted.upload());
        assertNull(admitted.reason());
        assertEquals(WorldStreamState.Phase.UPLOADING, state.phase());
    }

    @Test
    void aRendererRefusalAfterTheStateAcceptedTheTileStaysFatal() {
        // The decoder validated the header and the state accepted it, so a renderer lease
        // refusal is a broken invariant: it propagates and ends the session, as before #365.
        var state = offered(1, 7, 19);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldStreamSession.<String, String>admit(
                                state,
                                lease,
                                7,
                                19,
                                tile -> {
                                    throw new IllegalArgumentException(
                                            "Foreign, active or stale upload lease");
                                },
                                "tile"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        WorldStreamSession.<String, String>admit(
                                offered(1, 7, 19),
                                lease,
                                7,
                                19,
                                tile -> {
                                    throw new IllegalStateException("Renderer closed");
                                },
                                "tile"));
    }

    @Test
    void rejectionsAreRateLimitedOnStderr() {
        var rejections = new WorldStreamSession.Rejections();
        assertEquals(1, rejections.reject(1, "first"));
        for (int i = 2; i < 64; i++) {
            assertEquals(0, rejections.reject(i, "quiet"));
        }
        assertEquals(64, rejections.reject(64, "sampled"));
    }

    @Test
    void objectsRejectionsNameTheirReasonAndAreCountedPerSession() {
        var err = System.err;
        var captured = new java.io.ByteArrayOutputStream();
        System.setErr(
                new java.io.PrintStream(captured, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            var first = new WorldStreamSession.ObjectsRejections();
            String record = first.reject(12, new java.io.IOException("Placement transform"));
            assertTrue(record.contains("tile transaction 12"), record);
            assertTrue(record.contains("terrain kept"), record);
            assertTrue(record.contains("IOException: Placement transform"), record);
            assertTrue(record.contains("(1 objects set this session)"), record);
            for (int i = 2; i < 64; i++) {
                first.reject(i, new java.io.IOException("quiet"));
            }
            assertTrue(first.reject(64, new java.io.IOException("x")).contains("(64 objects sets"));
            // Another session starts its own count.
            assertTrue(
                    new WorldStreamSession.ObjectsRejections()
                            .reject(1, new java.io.IOException("y"))
                            .contains("(1 objects set this session)"));
        } finally {
            System.setErr(err);
        }
        String written = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
        // Sampled: the first and 64th of the first session, and the first of the second.
        assertEquals(3, written.lines().count(), written);
        assertFalse(written.contains("quiet"), written);
    }

    @Test
    void anInvalidCameraTransitionIsRefusedNotFatal() {
        var session = new WorldStreamEnvelope.Session(NONCE, "ab".repeat(32), 1);
        var gate = new WorldStreamParentFrameGate(session);
        var view = new TerrainCamera(0, 0, 0, 0, 0);
        gate.desire(new WorldStreamControl.Camera(NONCE, 5, 1, 800, 500, view));
        // The gate refuses an older revision and a resize without a policy change; the transport
        // turns that refusal into a logged, non-terminal rejection (see the transport test).
        assertThrows(
                IllegalArgumentException.class,
                () -> gate.desire(new WorldStreamControl.Camera(NONCE, 4, 1, 800, 500, view)));
        assertThrows(
                IllegalArgumentException.class,
                () -> gate.desire(new WorldStreamControl.Camera(NONCE, 6, 1, 400, 300, view)));
        // A closed or foreign gate is not a transition: it still throws from tryDesire.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        gate.tryDesire(
                                new WorldStreamControl.Camera(
                                        new WorldStreamState.Nonce(6, 9), 9, 1, 800, 500, view)));
        assertFalse(gate.tryDesire(new WorldStreamControl.Camera(NONCE, 4, 1, 800, 500, view)));
        // The gate keeps working: a valid transition after the refusals is accepted.
        gate.desire(new WorldStreamControl.Camera(NONCE, 7, 2, 400, 300, view));
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void theTransportRefusesAnInvalidCameraAndKeepsTheSession(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var child = new WorldStreamLaunchOwnerTest.Child();
        // Statuses never arrive and never end, so the session stays open until closed.
        var statuses = new java.io.PipedOutputStream();
        child.input = new java.io.PipedInputStream(statuses);
        var session =
                WorldStreamTransport.prepare(
                                directory,
                                new WorldStreamTransport.Spec(
                                        "cd".repeat(32), 1, WorldStreamTransport.Backend.VULKAN))
                        .launch(arguments -> child);
        try {
            var view = new TerrainCamera(0, 0, 0, 0, 0);
            assertTrue(session.camera(new WorldStreamTransport.Camera(5, 1, 800, 500, view)));
            assertFalse(session.camera(new WorldStreamTransport.Camera(4, 1, 800, 500, view)));
            assertFalse(session.camera(new WorldStreamTransport.Camera(6, 1, 400, 300, view)));
            assertEquals(2, session.rejectedCameras());
            assertTrue(session.camera(new WorldStreamTransport.Camera(7, 2, 400, 300, view)));
            assertFalse(session.completion().isDone(), "a refused camera never ends the session");
        } finally {
            session.closeAsync();
            statuses.close();
            try {
                session.terminatedAndDrained().get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException ended) {
                // The fake helper never says CLOSED; only the refusals above are under test.
            }
        }
    }
}
