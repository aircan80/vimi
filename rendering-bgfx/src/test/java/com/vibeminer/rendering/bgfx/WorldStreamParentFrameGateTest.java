package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldStreamParentFrameGateTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, 2);
    private static final WorldStreamEnvelope.Session SESSION =
            new WorldStreamEnvelope.Session(NONCE, "ab".repeat(32), 1);
    @TempDir Path directory;

    private static WorldStreamControl.Camera camera(long revision, long policy, int width) {
        return new WorldStreamControl.Camera(
                NONCE, revision, policy, width, 16, new TerrainCamera(0, 0, 1, 0, 0));
    }

    private static WorldStreamFrameGate.Frame frame(
            long sequence, long camera, long content, long policy, int width) {
        return new WorldStreamFrameGate.Frame(NONCE, sequence, camera, content, policy, width, 16);
    }

    private static void acknowledge(
            WorldStreamParentFrameGate gate, long tx, long previous, long policy) {
        gate.expectCommit(tx, previous, previous + 1, policy);
        assertTrue(
                gate.committed(new WorldStreamStatus.Committed(NONCE, tx, previous, previous + 1)));
    }

    @Test
    void realMappedPixelsWaitForBothAcknowledgementsAndResizeDoesNotReuseHistoricalCommitPolicy()
            throws Exception {
        var gate = new WorldStreamParentFrameGate(SESSION);
        gate.desire(camera(10, 1, 16));
        var path = directory.resolve("frames");
        try (var mapping = WorldFrameFileV2.create(path, NONCE, SESSION.scope())) {
            var pixels = ByteBuffer.allocate(16 * 16 * 4);
            while (pixels.hasRemaining()) {
                pixels.putInt(-1);
            }
            pixels.flip();
            mapping.write(frame(1, 10, 1, 1, 16), pixels);
            assertNull(gate.readLatest(mapping, 0));
            acknowledge(gate, 1, 0, 1);
            assertNull(
                    gate.readLatest(mapping, 0)); // desired policy is not an actual CAMERA_APPLIED
            assertTrue(gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 1, 16, 16)));
            assertNotNull(gate.readLatest(mapping, 0));
            gate.desire(camera(11, 2, 8));
            var small = ByteBuffer.allocate(8 * 16 * 4);
            while (small.hasRemaining()) {
                small.putInt(-1);
            }
            small.flip();
            mapping.write(frame(2, 11, 1, 2, 8), small);
            assertNull(gate.readLatest(mapping, 1));
            assertFalse(gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 1, 16, 16)));
            assertNull(gate.readLatest(mapping, 1));
            assertTrue(gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 2, 8, 16)));
            assertNotNull(
                    gate.readLatest(mapping, 1)); // content receipt is still the policy1 commit
        }
    }

    @Test
    void
            continuousDragAcceptsLaggingNewestWhilePolicyContentAndTerminalTransitionsRejectStaleFrames() {
        var gate = new WorldStreamParentFrameGate(SESSION);
        gate.desire(camera(1, 1, 16));
        gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 1, 16, 16));
        acknowledge(gate, 1, 0, 1);
        gate.desire(camera(2, 1, 16));
        gate.desire(camera(3, 1, 16));
        assertTrue(gate.presented(frame(1, 2, 1, 1, 16)));
        assertFalse(gate.current(frame(2, 1, 1, 1, 16)));
        assertFalse(gate.current(frame(2, 4, 1, 1, 16)));
        acknowledge(gate, 2, 1, 1);
        assertFalse(gate.current(frame(2, 3, 1, 1, 16)));
        assertTrue(gate.current(frame(2, 2, 2, 1, 16))); // stationary camera/new world
        gate.desire(camera(4, 2, 16));
        gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 2, 16, 16));
        assertFalse(gate.current(frame(3, 3, 2, 2, 16)));
        assertTrue(gate.current(frame(3, 4, 2, 2, 16)));
        gate.close();
        assertFalse(gate.current(frame(4, 4, 2, 2, 16)));
    }

    @Test
    void wrongSessionTransactionOrRevisionCannotCreateContentReceiptAndRejectionKeepsOldWorld() {
        var gate = new WorldStreamParentFrameGate(SESSION);
        gate.desire(camera(1, 1, 16));
        gate.applied(new WorldStreamStatus.CameraApplied(NONCE, 1, 16, 16));
        gate.expectCommit(1, 0, 1, 1);
        assertFalse(
                gate.committed(
                        new WorldStreamStatus.Committed(
                                new WorldStreamState.Nonce(2, 3), 1, 0, 1)));
        assertFalse(gate.committed(new WorldStreamStatus.Committed(NONCE, 2, 0, 1)));
        assertFalse(gate.committed(new WorldStreamStatus.Committed(NONCE, 1, 1, 2)));
        assertFalse(gate.current(frame(1, 1, 1, 1, 16)));
        assertTrue(gate.committed(new WorldStreamStatus.Committed(NONCE, 1, 0, 1)));
        gate.expectCommit(2, 1, 2, 1);
        assertTrue(
                gate.rejectCommit(
                        new WorldStreamStatus.Rejected(
                                NONCE, 2, WorldStreamStatus.Rejection.INVALID_COMMIT)));
        assertTrue(gate.current(frame(1, 1, 1, 1, 16)));
        assertFalse(gate.current(frame(2, 1, 2, 1, 16)));
    }
}
