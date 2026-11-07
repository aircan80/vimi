package com.vimi.gui;

import java.time.Duration;

/**
 * Detects a stalled visible World explorer: frames are owed, yet none that makes progress is
 * presented for a threshold. Pure metadata and an injected monotonic clock; the explorer's
 * animation pulse drives it on the FX thread, and it never waits, dumps or kills anything.
 *
 * <p>The caller reports each pulse whether the view is eligible (explorer visible, window shown
 * and not minimized, stream READY or PRESENTING, no instance switch, retirement or reload in
 * progress) and whether the stream is PRESENTING. A frame is owed when the stream is PRESENTING
 * (new content waits for accepted pixels, which the helper draws on its own cadence; this covers
 * the first frame and every frame after a commit), or when a camera revision was requested that no
 * presented frame covers yet. An idle camera in READY owes nothing, so it never alerts. Any
 * ineligible poll, or a gap between polls longer than {@link #POLL_GAP_NANOS} (the pulse stops
 * while the explorer is inactive), restarts the clock instead of counting the pause as a stall.
 *
 * <p>Only a presented frame for a newer camera revision is progress. A helper that keeps redrawing
 * an old camera while requests advance is still stalled for the camera. A stall alerts once per
 * episode; the episode ends, re-arming the alert, when progress is presented or nothing is owed.
 */
final class WorldStallWatchdog {
    static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(5);
    static final long POLL_GAP_NANOS = Duration.ofSeconds(1).toNanos();

    /** What a stall alert reports about the frames, beside the caller's own evidence. */
    record Stall(
            long waitedNanos,
            boolean presenting,
            long requestedRevision,
            long presentedRevision,
            long presentedSequence,
            long lastPresentedAgeNanos) {}

    private final long threshold;
    private boolean polled;
    private long lastPoll;
    // Start of the current owed stretch: frames are owed since then without progress.
    private boolean waiting;
    private long waitingSince;
    private long requestedRevision, presentedRevision, presentedSequence;
    private boolean presentedEver;
    private long presentedAt;
    private boolean alerted;

    WorldStallWatchdog(Duration threshold) {
        if (threshold.isNegative() || threshold.isZero()) {
            throw new IllegalArgumentException("Stall threshold must be positive");
        }
        this.threshold = threshold.toNanos();
    }

    /** The configured threshold, or the default when the property is absent or invalid. */
    static Duration configured(String property) {
        if (property == null || property.isBlank()) return DEFAULT_THRESHOLD;
        try {
            long millis = Long.parseLong(property.strip());
            return millis >= 1000 && millis <= 600_000 ? Duration.ofMillis(millis) : DEFAULT_THRESHOLD;
        } catch (NumberFormatException invalid) {
            return DEFAULT_THRESHOLD;
        }
    }

    /** A new stream (or none): forget every request, presentation and episode. */
    void reset() {
        polled = waiting = presentedEver = alerted = false;
        requestedRevision = presentedRevision = presentedSequence = 0;
    }

    /** A camera revision was sent to the renderer, which owes a frame that covers it. */
    void requested(long revision) {
        if (revision > requestedRevision) requestedRevision = revision;
    }

    /**
     * A new frame was presented. Only a newer camera revision is progress: it restarts the clock
     * and ends any episode. Returns whether it ended an alerted episode.
     */
    boolean presented(long cameraRevision, long sequence, long now) {
        presentedSequence = Math.max(presentedSequence, sequence);
        presentedEver = true;
        presentedAt = now;
        if (cameraRevision <= presentedRevision) return false;
        presentedRevision = cameraRevision;
        if (waiting) waitingSince = now;
        boolean recovered = alerted;
        alerted = false;
        return recovered;
    }

    /** One pulse. Returns the stall to report when this poll begins an episode, else null. */
    Stall poll(long now, boolean eligible, boolean presenting) {
        boolean gap = !polled || now - lastPoll > POLL_GAP_NANOS;
        polled = true;
        lastPoll = now;
        if (!eligible) {
            waiting = false;
            return null;
        }
        boolean owed = presenting || cameraPending();
        if (!owed) {
            waiting = false;
            alerted = false;
            return null;
        }
        if (!waiting || gap) {
            waiting = true;
            waitingSince = now;
        }
        if (alerted || now - waitingSince < threshold) return null;
        alerted = true;
        return new Stall(
                now - waitingSince,
                presenting,
                requestedRevision,
                presentedRevision,
                presentedSequence,
                presentedEver ? now - presentedAt : -1);
    }

    /** The frame half of a stall record; fixed wording, numbers only. */
    static String describe(Stall stall) {
        return String.format(
                java.util.Locale.ROOT,
                "World explorer stalled: no new frame for %.1f s while %s;"
                        + " camera revision requested %d, presented %d; last frame sequence %d, %s",
                stall.waitedNanos() / 1e9,
                stall.presenting()
                        ? "new content awaited its first frame"
                        : "a camera change awaited its frame",
                stall.requestedRevision(),
                stall.presentedRevision(),
                stall.presentedSequence(),
                stall.lastPresentedAgeNanos() < 0
                        ? "none presented this stream"
                        : String.format(
                                java.util.Locale.ROOT,
                                "%.1f s ago",
                                stall.lastPresentedAgeNanos() / 1e9));
    }

    boolean alerted() {
        return alerted;
    }

    private boolean cameraPending() {
        return requestedRevision > presentedRevision;
    }
}
