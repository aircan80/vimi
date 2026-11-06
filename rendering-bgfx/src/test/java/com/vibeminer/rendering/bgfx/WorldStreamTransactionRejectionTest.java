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
                        NONCE,
                        1,
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
    void aRendererRefusalRetiresTheLeaseAndTheSessionContinues() {
        var state = offered(1, 7, 19);
        var admitted =
                WorldStreamSession.<String, String>admit(
                        state,
                        NONCE,
                        1,
                        lease,
                        7,
                        19,
                        tile -> {
                            throw new IllegalArgumentException(
                                    "Foreign, active or stale upload lease");
                        },
                        "tile");
        assertNull(admitted.upload());
        assertTrue(admitted.reason().contains("renderer refused"), admitted.reason());
        var next = state.credit();
        assertNotNull(next);
        assertNotEquals(lease, next);
    }

    @Test
    void anAcceptedTileBeginsItsUpload() {
        var state = offered(1, 7, 19);
        var admitted =
                WorldStreamSession.admit(
                        state, NONCE, 1, lease, 7, 19, tile -> "upload:" + tile, "t");
        assertEquals("upload:t", admitted.upload());
        assertNull(admitted.reason());
        assertEquals(WorldStreamState.Phase.UPLOADING, state.phase());
    }

    @Test
    void otherRendererFailuresStayFatal() {
        // Only the renderer's per-transaction argument refusal is a rejection; an internal
        // failure (a broken invariant or a native error) still ends the session.
        var state = offered(1, 7, 19);
        assertThrows(
                IllegalStateException.class,
                () ->
                        WorldStreamSession.<String, String>admit(
                                state,
                                NONCE,
                                1,
                                lease,
                                7,
                                19,
                                tile -> {
                                    throw new IllegalStateException("Renderer closed");
                                },
                                "tile"));
    }

    @Test
    void aSessionIdentityMismatchStaysFatal() {
        var state = offered(1, 7, 19);
        var foreign = new WorldStreamState.Nonce(6, 9);
        assertThrows(
                IllegalStateException.class,
                () ->
                        WorldStreamSession.<String, String>admit(
                                state,
                                foreign,
                                1,
                                lease,
                                7,
                                19,
                                tile -> {
                                    throw new IllegalArgumentException("Foreign upload lease");
                                },
                                "tile"));
        // A command or commit naming another session is refused by the state itself.
        assertEquals(WorldStreamState.Result.WRONG_SESSION, state.cancel(foreign, 1, lease));
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
    void aRejectedObjectsSetNamesItsReasonAndKeepsTheTerrain() {
        String record =
                WorldStreamSession.objectsRejected(
                        12, new java.io.IOException("Placement transform"));
        assertTrue(record.contains("tile transaction 12"), record);
        assertTrue(record.contains("terrain kept"), record);
        assertTrue(record.contains("IOException: Placement transform"), record);
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
