package com.vimi.gui;

import java.time.Duration;

/** Stalled-frame detection with an injected clock; no JavaFX, renderer or helper process. */
public final class WorldStallWatchdogTest {
    private static final long MS = 1_000_000L;
    private static final long PULSE = 16 * MS;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        idleCameraNeverAlerts();
        hiddenOrNotReadyNeverAlerts();
        pausedPulseRestartsTheClock();
        realStallAlertsExactlyOnce();
        presentationRecoversAndReArms();
        frameProgressForOlderRevisionsIsNotAStall();
        firstFrameThatNeverArrivesAlertsOnce();
        presentingThatEndsIsNotAStall();
        staleFramesDoNotMaskALostCamera();
        resetForgetsAnEpisode();
        thresholdIsConfigurable();
        describeHasFixedWordingAndNumbersOnly();
        System.out.println("WorldStallWatchdogTest PASS");
    }

    private static WorldStallWatchdog watchdog() {
        return new WorldStallWatchdog(Duration.ofSeconds(5));
    }

    /** Polls every pulse from {@code from} up to {@code until}; returns the number of alerts. */
    private static int pulses(
            WorldStallWatchdog watchdog, long from, long until, boolean eligible, boolean presenting) {
        int alerts = 0;
        for (long now = from; now <= until; now += PULSE) {
            if (watchdog.poll(now, eligible, presenting) != null) alerts++;
        }
        return alerts;
    }

    /** READY: eligible, not presenting. */
    private static int ready(WorldStallWatchdog watchdog, long from, long until) {
        return pulses(watchdog, from, until, true, false);
    }

    private static void idleCameraNeverAlerts() {
        var watchdog = watchdog();
        watchdog.requested(1);
        watchdog.presented(1, 1, 100 * MS);
        require(ready(watchdog, 0, 60_000 * MS) == 0, "An idle camera owes no frame");
    }

    private static void hiddenOrNotReadyNeverAlerts() {
        var watchdog = watchdog();
        watchdog.requested(1);
        // Hidden tab, minimized window, a phase that owes nothing, a switch: all ineligible.
        require(pulses(watchdog, 0, 60_000 * MS, false, false) == 0, "Ineligible never alerts");
        require(pulses(watchdog, 0, 60_000 * MS, false, true) == 0, "Even when presenting");
        // Eligibility starts the clock; it does not inherit the hidden minute.
        long shown = 60_016 * MS;
        require(ready(watchdog, shown, shown + 4_900 * MS) == 0, "Clock restarts when shown");
        require(ready(watchdog, shown + 4_916 * MS, shown + 5_100 * MS) == 1,
                "A stall after becoming eligible still alerts");
    }

    private static void pausedPulseRestartsTheClock() {
        var watchdog = watchdog();
        watchdog.requested(1);
        require(ready(watchdog, 0, 4_000 * MS) == 0, "Below threshold");
        // The explorer pulse stops (inactive explorer) for 30 s, then resumes.
        long resumed = 34_000 * MS;
        require(watchdog.poll(resumed, true, false) == null, "A pause is not a stall");
        require(ready(watchdog, resumed, resumed + 4_900 * MS) == 0,
                "The clock restarts after a pulse gap");
        require(ready(watchdog, resumed + 4_916 * MS, resumed + 5_100 * MS) == 1,
                "A real stall after the pause alerts");
    }

    private static void realStallAlertsExactlyOnce() {
        var watchdog = watchdog();
        watchdog.requested(1);
        watchdog.presented(1, 1, 50 * MS);
        require(ready(watchdog, 0, 992 * MS) == 0, "Idle");
        watchdog.requested(2);
        require(ready(watchdog, 1_008 * MS, 5_992 * MS) == 0, "Not before the threshold");
        var stall = watchdog.poll(6_008 * MS, true, false);
        require(stall != null, "Alerts at the threshold");
        require(!stall.presenting(), "Camera stall");
        require(stall.requestedRevision() == 2 && stall.presentedRevision() == 1, "Revisions");
        require(stall.presentedSequence() == 1, "Last frame sequence");
        require(stall.waitedNanos() == 5_000 * MS, "Waited since the first owed poll");
        require(stall.lastPresentedAgeNanos() == 5_958 * MS, "Last frame age");
        // Further requests and pulses in the same episode stay silent.
        watchdog.requested(3);
        require(ready(watchdog, 6_024 * MS, 120_000 * MS) == 0, "Once per episode");
        require(watchdog.alerted(), "Episode still open");
    }

    private static void presentationRecoversAndReArms() {
        var watchdog = watchdog();
        watchdog.requested(1);
        require(ready(watchdog, 0, 5_008 * MS) == 1, "First episode");
        require(watchdog.presented(1, 4, 5_500 * MS), "Progress ends the alerted episode");
        require(!watchdog.alerted(), "A frame ends the episode");
        require(ready(watchdog, 5_504 * MS, 20_000 * MS) == 0, "Recovered and idle");
        watchdog.requested(2);
        require(ready(watchdog, 20_000 * MS, 25_008 * MS) == 1, "Re-armed: second episode");
        require(!watchdog.presented(1, 5, 25_100 * MS), "An old camera is no recovery");
        require(watchdog.alerted(), "Still stalled for camera 2");
    }

    private static void frameProgressForOlderRevisionsIsNotAStall() {
        var watchdog = watchdog();
        int alerts = 0;
        long revision = 0, sequence = 0;
        // Continuous motion: each frame trails the newest camera, but each covers a newer one.
        for (long now = 0; now <= 60_000 * MS; now += PULSE) {
            watchdog.requested(++revision);
            if (now % (200 * MS) == 0) watchdog.presented(revision - 3, ++sequence, now);
            if (watchdog.poll(now, true, false) != null) alerts++;
        }
        require(alerts == 0, "Trailing frames for newer cameras are progress");
    }

    private static void firstFrameThatNeverArrivesAlertsOnce() {
        var watchdog = watchdog();
        // PRESENTING with no camera change: the helper owes the first frame on its own cadence.
        require(pulses(watchdog, 0, 4_992 * MS, true, true) == 0, "Not before the threshold");
        var stall = watchdog.poll(5_008 * MS, true, true);
        require(stall != null, "A first frame that never arrives alerts");
        require(stall.presenting() && stall.lastPresentedAgeNanos() == -1, "Presenting, no frame");
        require(pulses(watchdog, 5_024 * MS, 60_000 * MS, true, true) == 0, "Once per episode");
        // The same after a commit: READY, then PRESENTING the new content forever.
        var afterCommit = watchdog();
        afterCommit.requested(1);
        afterCommit.presented(1, 1, 0);
        require(ready(afterCommit, 0, 10_000 * MS) == 0, "Idle while READY");
        require(pulses(afterCommit, 10_016 * MS, 15_032 * MS, true, true) == 1,
                "Committed content that is never drawn alerts");
    }

    private static void presentingThatEndsIsNotAStall() {
        var watchdog = watchdog();
        // Commit, then the helper's next frame is accepted and the stream returns to READY.
        for (int commit = 0; commit < 20; commit++) {
            long start = commit * 3_000L * MS;
            require(pulses(watchdog, start, start + 1_000 * MS, true, true) == 0, "Short present");
            require(ready(watchdog, start + 1_016 * MS, start + 2_984 * MS) == 0, "READY");
        }
        require(!watchdog.alerted(), "Never alerted");
    }

    private static void staleFramesDoNotMaskALostCamera() {
        var watchdog = watchdog();
        watchdog.requested(1);
        watchdog.presented(1, 1, 0);
        int alerts = 0;
        long revision = 1, sequence = 1;
        // The helper keeps redrawing camera 1 with new sequences while requests advance.
        for (long now = 16 * MS; now <= 60_000 * MS; now += PULSE) {
            watchdog.requested(++revision);
            if (now % (96 * MS) == 0) watchdog.presented(1, ++sequence, now);
            if (watchdog.poll(now, true, false) != null) alerts++;
        }
        require(alerts == 1, "A camera the helper ignores alerts once, got " + alerts);
    }

    private static void resetForgetsAnEpisode() {
        var watchdog = watchdog();
        watchdog.requested(9);
        require(ready(watchdog, 0, 5_008 * MS) == 1, "Stalled");
        watchdog.reset();
        require(!watchdog.alerted(), "Reset ends the episode");
        // A new stream restarts camera revisions at 1.
        require(ready(watchdog, 5_024 * MS, 30_000 * MS) == 0, "Nothing owed after reset");
        watchdog.requested(1);
        require(ready(watchdog, 30_016 * MS, 35_024 * MS) == 1, "New stream stalls alert");
    }

    private static void thresholdIsConfigurable() {
        require(WorldStallWatchdog.configured(null).equals(Duration.ofSeconds(5)), "Default");
        require(WorldStallWatchdog.configured(" 2500 ").equals(Duration.ofMillis(2500)), "Set");
        require(WorldStallWatchdog.configured("10").equals(Duration.ofSeconds(5)), "Too small");
        require(WorldStallWatchdog.configured("x").equals(Duration.ofSeconds(5)), "Invalid");
        var quick = new WorldStallWatchdog(Duration.ofMillis(2500));
        quick.requested(1);
        require(ready(quick, 0, 2_496 * MS) == 0, "Below 2.5 s");
        require(quick.poll(2_512 * MS, true, false) != null, "At 2.5 s");
        try {
            new WorldStallWatchdog(Duration.ZERO);
            throw new AssertionError("Zero threshold accepted");
        } catch (IllegalArgumentException expected) {
            // Rejected.
        }
    }

    private static void describeHasFixedWordingAndNumbersOnly() {
        String text = WorldStallWatchdog.describe(
                new WorldStallWatchdog.Stall(5_250 * MS, false, 12, 9, 40, 6_000 * MS));
        require(text.equals("World explorer stalled: no new frame for 5.3 s while a camera change"
                + " awaited its frame; camera revision requested 12, presented 9; last frame"
                + " sequence 40, 6.0 s ago"), text);
        String first = WorldStallWatchdog.describe(
                new WorldStallWatchdog.Stall(5_000 * MS, true, 1, 0, 0, -1));
        require(first.contains("while new content awaited its first frame")
                && first.endsWith("none presented this stream"), first);
    }
}
