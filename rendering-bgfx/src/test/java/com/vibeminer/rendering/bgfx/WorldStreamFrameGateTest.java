package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WorldStreamFrameGateTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(41, 77);

    @Test
    void movingCameraAcceptsOlderThanDesiredRevisionsWhileDecoderAndUploadsAreHeld() {
        var gate = new WorldStreamFrameGate(NONCE);
        var child = new WorldStreamState(NONCE);
        var lease = child.credit();
        child.offer(NONCE, 1, lease, 17, 21);
        assertTrue(gate.cameraSent(1, 1, 800, 500));
        assertTrue(gate.cameraApplied(1, 800, 500));
        for (int revision = 2; revision < 200; revision++) {
            assertTrue(gate.cameraSent(revision, 1, 800, 500));
            assertTrue(gate.accept(frame(revision, revision - 1, 0, 1, 800, 500)));
            assertNull(child.credit(), "Camera presentation does not wait for held decode");
        }
        child.decoded(lease, true);
        child.submittedReference(lease);
        child.staged(lease);
        assertEquals(
                WorldStreamState.Result.OK, child.commit(NONCE, 2, 0, 1, java.util.List.of(lease)));
        assertFalse(
                gate.accept(frame(201, 199, 1, 1, 800, 500)),
                "Frame-before-ACK is dropped without allocation");
        assertTrue(gate.committed(1));
        assertTrue(
                gate.accept(frame(202, 199, 1, 1, 800, 500)),
                "Stationary commit presents without a new camera");
        assertNull(
                child.credit(),
                "Camera and committed content progress while release callback is held");
        assertFalse(gate.accept(frame(203, 199, 0, 1, 800, 500)), "Delayed old-content readback");
        assertFalse(gate.accept(frame(204, 198, 1, 1, 800, 500)), "Delayed old-camera readback");
        child.consumedReference(lease);
        assertNotNull(child.credit());
    }

    @Test
    void policyDimensionsNonceSequenceAndCloseAreIndependentBarriers() {
        var gate = new WorldStreamFrameGate(NONCE);
        assertTrue(gate.cameraSent(1, 1, 800, 500));
        assertFalse(gate.accept(frame(1, 1, 0, 1, 800, 500)), "Policy not acknowledged");
        assertTrue(gate.cameraApplied(1, 800, 500));
        assertTrue(gate.accept(frame(1, 1, 0, 1, 800, 500)));
        assertFalse(gate.accept(frame(1, 1, 0, 1, 800, 500)), "Replay");
        assertFalse(
                gate.accept(
                        new WorldStreamFrameGate.Frame(
                                new WorldStreamState.Nonce(1, 2), 2, 1, 0, 1, 800, 500)));
        assertFalse(gate.cameraSent(2, 1, 799, 500), "Dimensions require a new policy");
        assertTrue(gate.cameraSent(2, 2, 799, 499));
        assertFalse(gate.cameraApplied(1, 800, 500));
        assertFalse(gate.accept(frame(2, 2, 0, 2, 799, 499)));
        assertTrue(gate.cameraApplied(2, 799, 499));
        assertFalse(gate.accept(frame(2, 1, 0, 1, 800, 500)));
        assertFalse(gate.accept(frame(2, 3, 0, 2, 799, 499)), "Unsent camera");
        assertFalse(gate.accept(frame(2, 2, -1, 2, 799, 499)));
        assertFalse(gate.accept(frame(Long.MAX_VALUE, 2, 0, 2, 799, 499)));
        assertTrue(gate.accept(frame(WorldStreamFrameGate.MAX_SEQUENCE, 2, 0, 2, 799, 499)));
        assertFalse(gate.committed(2), "No skipped content ACK");
        assertTrue(gate.committed(1));
        gate.close();
        assertFalse(gate.cameraSent(3, 2, 799, 499));
        assertFalse(gate.cameraApplied(2, 799, 499));
        assertFalse(gate.committed(2));
        assertFalse(gate.accept(frame(4, 2, 1, 2, 799, 499)));
    }

    private static WorldStreamFrameGate.Frame frame(
            long sequence, long camera, long content, long policy, int width, int height) {
        return new WorldStreamFrameGate.Frame(
                NONCE, sequence, camera, content, policy, width, height);
    }
}
