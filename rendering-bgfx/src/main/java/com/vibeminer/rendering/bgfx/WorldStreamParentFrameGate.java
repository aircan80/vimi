package com.vibeminer.rendering.bgfx;

import java.io.IOException;
import java.util.Objects;

/** Parent acknowledgement metadata only; never a source qualification or a GUI callback. */
final class WorldStreamParentFrameGate {
    private record PendingCommit(long transaction, long expected, long next, long policy) {}

    private final WorldStreamEnvelope.Session session;
    private WorldStreamControl.Camera desired;
    private WorldStreamStatus.CameraApplied applied;
    private PendingCommit pending;
    private long content, policyFloor, presentedSequence, presentedCamera;
    private boolean closed;

    WorldStreamParentFrameGate(WorldStreamEnvelope.Session session) {
        this.session = Objects.requireNonNull(session);
    }

    synchronized void desire(WorldStreamControl.Camera camera) {
        Objects.requireNonNull(camera);
        if (closed
                || !session.nonce().equals(camera.nonce())
                || camera.policy() < session.policy()
                || desired != null
                        && (camera.revision() <= desired.revision()
                                || camera.policy() < desired.policy()
                                || camera.policy() == desired.policy()
                                        && (camera.width() != desired.width()
                                                || camera.height() != desired.height()))) {
            throw new IllegalArgumentException("Invalid parent camera transition");
        }
        if (desired == null || camera.policy() != desired.policy()) {
            policyFloor = camera.revision();
            applied = null;
        }
        desired = camera;
    }

    synchronized boolean applied(WorldStreamStatus.CameraApplied acknowledgement) {
        Objects.requireNonNull(acknowledgement);
        if (closed || desired == null || !session.nonce().equals(acknowledgement.nonce())) {
            return false;
        }
        if (acknowledgement.policy() != desired.policy()
                || acknowledgement.width() != desired.width()
                || acknowledgement.height() != desired.height()) {
            return false;
        }
        applied = acknowledgement;
        return true;
    }

    synchronized void expectCommit(long transaction, long expected, long next, long policy) {
        if (closed
                || pending != null
                || transaction <= 0
                || transaction == Long.MAX_VALUE
                || expected != content
                || expected == Long.MAX_VALUE
                || next != expected + 1
                || policy < session.policy()) {
            throw new IllegalArgumentException("Invalid parent commit expectation");
        }
        pending = new PendingCommit(transaction, expected, next, policy);
    }

    synchronized boolean committed(WorldStreamStatus.Committed acknowledgement) {
        Objects.requireNonNull(acknowledgement);
        if (closed
                || !session.nonce().equals(acknowledgement.nonce())
                || pending == null
                || acknowledgement.transaction() != pending.transaction()
                || acknowledgement.expected() != pending.expected()
                || acknowledgement.next() != pending.next()) {
            return false;
        }
        content = pending.next();
        pending = null;
        return true;
    }

    synchronized boolean rejectCommit(WorldStreamStatus.Rejected acknowledgement) {
        Objects.requireNonNull(acknowledgement);
        if (closed
                || pending == null
                || !session.nonce().equals(acknowledgement.nonce())
                || pending.transaction() != acknowledgement.transaction()) {
            return false;
        }
        pending = null;
        return true;
    }

    /** A camera-policy ACK and content ACK must both exist BEFORE frame pixel allocation. */
    WorldFrameFileV2.Frame readLatest(WorldFrameFileV2 file, long afterSequence)
            throws IOException {
        WorldFrameFileV2.Barrier snapshot;
        synchronized (this) {
            snapshot = barrier();
        }
        if (snapshot == null) {
            return null;
        }
        var frame = file.readLatest(afterSequence, snapshot);
        synchronized (this) {
            return frame != null && current(frame.metadata()) ? frame : null;
        }
    }

    synchronized boolean current(WorldStreamFrameGate.Frame frame) {
        var barrier = barrier();
        return barrier != null
                && session.nonce().equals(frame.nonce())
                && frame.sequence() > presentedSequence
                && frame.policy() == barrier.policy()
                && frame.width() == barrier.width()
                && frame.height() == barrier.height()
                && frame.camera() >= barrier.cameraFloor()
                && frame.camera() <= barrier.cameraCeiling()
                && frame.content() == content;
    }

    synchronized boolean presented(WorldStreamFrameGate.Frame frame) {
        if (!current(frame)) {
            return false;
        }
        presentedSequence = frame.sequence();
        presentedCamera = frame.camera();
        return true;
    }

    synchronized void close() {
        closed = true;
        pending = null;
        applied = null;
        desired = null;
    }

    private WorldFrameFileV2.Barrier barrier() {
        if (closed || desired == null || applied == null || content == 0) {
            return null;
        }
        return new WorldFrameFileV2.Barrier(
                desired.policy(),
                desired.width(),
                desired.height(),
                Math.max(policyFloor, presentedCamera),
                desired.revision(),
                content,
                content);
    }
}
