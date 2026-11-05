package com.vimi.gui;

import java.time.Duration;

/**
 * Detects a stalled visible World explorer: frames are expected, yet none is presented for a
 * threshold. Pure metadata and an injected monotonic clock; the explorer's animation pulse drives
 * it on the FX thread, and it never waits, dumps or kills anything.
 *
 * <p>Frames are expected only while the caller reports the view eligible (explorer visible, window
 * shown and not minimized, GPU stream READY, no instance switch, retirement or reload in progress)
 * and a camera revision was requested that no presented frame covers yet. An idle camera requests
 * nothing, so it never alerts. Any ineligible poll, or a gap between polls longer than {@link
 * #POLL_GAP_NANOS} (the pulse stops while the explorer is inactive), restarts the clock instead of
 * counting the pause as a stall.
 *
 * <p>A stall alerts once per episode. The next presented frame ends the episode and re-arms.
 */
final class WorldStallWatchdog {
    static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(5);
    static final long POLL_GAP_NANOS = Duration.ofSeconds(1).toNanos();

    /** What a stall alert reports about the frames, beside the caller's own evidence. */
    record Stall(
            long waitedNanos,
            long requestedRevision,
            long presentedRevision,
            long presentedSequence,
            long lastPresentedAgeNanos) {}

    private final long threshold;
    private boolean polled;
    private long lastPoll;
    // Start of the current eligible stretch; frames are only owed within one.
    private boolean eligible;
    private long eligibleSince;
    private long requestedRevision, presentedRevision, presentedSequence;
    private long requestedAt;
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
        polled = eligible = presentedEver = alerted = false;
        requestedRevision = presentedRevision = presentedSequence = 0;
    }

    /** A camera revision was sent to the renderer, which owes a frame that covers it. */
    void requested(long revision, long now) {
        if (revision <= requestedRevision) return;
        if (!pending()) requestedAt = now;
        requestedRevision = revision;
    }

    /** A new frame for {@code cameraRevision} was presented: progress, ending any episode. */
    void presented(long cameraRevision, long sequence, long now) {
        presentedRevision = Math.max(presentedRevision, cameraRevision);
        presentedSequence = Math.max(presentedSequence, sequence);
        presentedEver = true;
        presentedAt = now;
        alerted = false;
        if (pending()) requestedAt = now;
    }

    /** One pulse. Returns the stall to report when this poll begins an episode, else null. */
    Stall poll(long now, boolean eligibleNow) {
        boolean gap = !polled || now - lastPoll > POLL_GAP_NANOS;
        polled = true;
        lastPoll = now;
        if (!eligibleNow) {
            eligible = false;
            return null;
        }
        if (!eligible || gap) {
            eligible = true;
            eligibleSince = now;
        }
        if (!pending() || alerted) return null;
        // Subtraction, not ordering, so System.nanoTime's signed wrap is harmless.
        long since = eligibleSince - requestedAt > 0 ? eligibleSince : requestedAt;
        if (now - since < threshold) return null;
        alerted = true;
        return new Stall(
                now - since,
                requestedRevision,
                presentedRevision,
                presentedSequence,
                presentedEver ? now - presentedAt : -1);
    }

    /** The frame half of a stall record; fixed wording, numbers only. */
    static String describe(Stall stall) {
        return String.format(
                java.util.Locale.ROOT,
                "World explorer stalled: no new frame for %.1f s while frames were expected;"
                        + " camera revision requested %d, presented %d; last frame sequence %d, %s",
                stall.waitedNanos() / 1e9,
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

    private boolean pending() {
        return requestedRevision > presentedRevision;
    }
}
