package com.vibeminer.rendering.bgfx;

import java.util.Objects;

/** Metadata-only parent presentation oracle for World v2; no pixel allocation or UI upload. */
final class WorldStreamFrameGate {
    static final long MAX_SEQUENCE = Long.MAX_VALUE >>> 1;

    record Frame(
            WorldStreamState.Nonce nonce,
            long sequence,
            long camera,
            long content,
            long policy,
            int width,
            int height) {}

    private final WorldStreamState.Nonce nonce;
    private long sentCamera,
            acknowledgedContent,
            policy,
            sequence,
            presentedCamera,
            presentedContent;
    private long policyCameraFloor;
    private int width, height;
    private boolean policyAcknowledged, closed;

    WorldStreamFrameGate(WorldStreamState.Nonce nonce) {
        this.nonce = Objects.requireNonNull(nonce);
    }

    boolean cameraSent(long revision, long epoch, int width, int height) {
        if (closed
                || revision <= sentCamera
                || epoch <= 0
                || epoch < policy
                || width < 1
                || width > 800
                || height < 1
                || height > 500) {
            return false;
        }
        if (epoch == policy && (this.width != width || this.height != height)) {
            return false;
        }
        if (epoch != policy) {
            policyAcknowledged = false;
            policyCameraFloor = revision;
        }
        sentCamera = revision;
        policy = epoch;
        this.width = width;
        this.height = height;
        return true;
    }

    boolean cameraApplied(long epoch, int width, int height) {
        if (closed || epoch != policy || this.width != width || this.height != height) {
            return false;
        }
        policyAcknowledged = true;
        return true;
    }

    boolean committed(long revision) {
        if (closed
                || acknowledgedContent == Long.MAX_VALUE
                || revision != acknowledgedContent + 1) {
            return false;
        }
        acknowledgedContent = revision;
        return true;
    }

    boolean accept(Frame frame) {
        if (closed
                || frame == null
                || !nonce.equals(frame.nonce())
                || !policyAcknowledged
                || frame.policy() != policy
                || frame.width() != width
                || frame.height() != height
                || frame.sequence() <= sequence
                || frame.sequence() > MAX_SEQUENCE
                || frame.camera() < policyCameraFloor
                || frame.camera() < 1
                || frame.camera() > sentCamera
                || frame.camera() < presentedCamera
                || frame.content() < presentedContent
                || frame.content() > acknowledgedContent) {
            return false;
        }
        sequence = frame.sequence();
        presentedCamera = frame.camera();
        presentedContent = frame.content();
        return true;
    }

    void close() {
        closed = true;
    }
}
