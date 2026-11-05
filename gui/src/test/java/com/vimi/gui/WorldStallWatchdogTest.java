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
        resetForgetsAnEpisode();
        thresholdIsConfigurable();
        describeHasFixedWordingAndNumbersOnly();
        System.out.println("WorldStallWatchdogTest PASS");
    }

    private static WorldStallWatchdog watchdog() {
        return new WorldStallWatchdog(Duration.ofSeconds(5));
    }

    /** Polls every pulse from {@code from} up to {@code until}; returns the number of alerts. */
    private static int pulses(WorldStallWatchdog watchdog, long from, long until, boolean eligible) {
        int alerts = 0;
        for (long now = from; now <= until; now += PULSE) {
            if (watchdog.poll(now, eligible) != null) alerts++;
        }
        return alerts;
    }

    private static void idleCameraNeverAlerts() {
        var watchdog = watchdog();
        watchdog.requested(1, 0);
        watchdog.presented(1, 1, 100 * MS);
        require(pulses(watchdog, 0, 60_000 * MS, true) == 0, "An idle camera owes no frame");
    }

    private static void hiddenOrNotReadyNeverAlerts() {
        var watchdog = watchdog();
        watchdog.requested(1, 0);
        // Hidden tab, minimized window, phase not READY or a switch in progress: all ineligible.
        require(pulses(watchdog, 0, 60_000 * MS, false) == 0, "Ineligible view never alerts");
        // Eligibility starts the clock; it does not inherit the hidden minute.
        long shown = 60_016 * MS;
        require(pulses(watchdog, shown, shown + 4_900 * MS, true) == 0, "Clock restarts when shown");
        require(pulses(watchdog, shown + 4_916 * MS, shown + 5_100 * MS, true) == 1,
                "A stall after becoming eligible still alerts");
    }

    private static void pausedPulseRestartsTheClock() {
        var watchdog = watchdog();
        watchdog.requested(1, 0);
        require(pulses(watchdog, 0, 4_000 * MS, true) == 0, "Below threshold");
        // The explorer pulse stops (inactive explorer) for 30 s, then resumes.
        long resumed = 34_000 * MS;
        require(watchdog.poll(resumed, true) == null, "A pause is not a stall");
        require(pulses(watchdog, resumed, resumed + 4_900 * MS, true) == 0,
                "The clock restarts after a pulse gap");
        require(pulses(watchdog, resumed + 4_916 * MS, resumed + 5_100 * MS, true) == 1,
                "A real stall after the pause alerts");
    }

    private static void realStallAlertsExactlyOnce() {
        var watchdog = watchdog();
        watchdog.requested(1, 0);
        watchdog.presented(1, 1, 50 * MS);
        watchdog.requested(2, 1_000 * MS);
        require(pulses(watchdog, 0, 5_984 * MS, true) == 0, "Not before the threshold");
        var stall = watchdog.poll(6_000 * MS, true);
        require(stall != null, "Alerts at the threshold");
        require(stall.requestedRevision() == 2 && stall.presentedRevision() == 1, "Revisions");
        require(stall.presentedSequence() == 1, "Last frame sequence");
        require(stall.waitedNanos() == 5_000 * MS, "Waited since the request");
        require(stall.lastPresentedAgeNanos() == 5_950 * MS, "Last frame age");
        // Further requests and pulses in the same episode stay silent.
        watchdog.requested(3, 7_000 * MS);
        require(pulses(watchdog, 6_016 * MS, 120_000 * MS, true) == 0, "Once per episode");
        require(watchdog.alerted(), "Episode still open");
    }

    private static void presentationRecoversAndReArms() {
        var watchdog = watchdog();
        watchdog.requested(1, 0);
        require(pulses(watchdog, 0, 5_008 * MS, true) == 1, "First episode");
        watchdog.presented(1, 4, 5_500 * MS);
        require(!watchdog.alerted(), "A frame ends the episode");
        require(pulses(watchdog, 5_504 * MS, 20_000 * MS, true) == 0, "Recovered and idle");
        watchdog.requested(2, 20_000 * MS);
        require(pulses(watchdog, 20_000 * MS, 25_008 * MS, true) == 1, "Re-armed: second episode");
    }

    private static void frameProgressForOlderRevisionsIsNotAStall() {
        var watchdog = watchdog();
        int alerts = 0;
        long revision = 0, sequence = 0;
        // Continuous motion: each frame trails the newest camera revision, but frames keep coming.
        for (long now = 0; now <= 60_000 * MS; now += PULSE) {
            watchdog.requested(++revision, now);
            if (now % (200 * MS) == 0) watchdog.presented(revision - 3, ++sequence, now);
            if (watchdog.poll(now, true) != null) alerts++;
        }
        require(alerts == 0, "Trailing frames are progress");
    }

    private static void resetForgetsAnEpisode() {
        var watchdog = watchdog();
        watchdog.requested(9, 0);
        require(pulses(watchdog, 0, 5_008 * MS, true) == 1, "Stalled");
        watchdog.reset();
        require(!watchdog.alerted(), "Reset ends the episode");
        // A new stream restarts camera revisions at 1.
        require(pulses(watchdog, 5_024 * MS, 30_000 * MS, true) == 0, "Nothing owed after reset");
        watchdog.requested(1, 30_000 * MS);
        require(pulses(watchdog, 30_000 * MS, 35_008 * MS, true) == 1, "New stream stalls alert");
    }

    private static void thresholdIsConfigurable() {
        require(WorldStallWatchdog.configured(null).equals(Duration.ofSeconds(5)), "Default");
        require(WorldStallWatchdog.configured(" 2500 ").equals(Duration.ofMillis(2500)), "Set");
        require(WorldStallWatchdog.configured("10").equals(Duration.ofSeconds(5)), "Too small");
        require(WorldStallWatchdog.configured("x").equals(Duration.ofSeconds(5)), "Invalid");
        var quick = new WorldStallWatchdog(Duration.ofMillis(2500));
        quick.requested(1, 0);
        require(pulses(quick, 0, 2_496 * MS, true) == 0, "Below 2.5 s");
        require(quick.poll(2_512 * MS, true) != null, "At 2.5 s");
        try {
            new WorldStallWatchdog(Duration.ZERO);
            throw new AssertionError("Zero threshold accepted");
        } catch (IllegalArgumentException expected) {
            // Rejected.
        }
    }

    private static void describeHasFixedWordingAndNumbersOnly() {
        String text = WorldStallWatchdog.describe(
                new WorldStallWatchdog.Stall(5_250 * MS, 12, 9, 40, 6_000 * MS));
        require(text.equals("World explorer stalled: no new frame for 5.3 s while frames were"
                + " expected; camera revision requested 12, presented 9; last frame sequence 40,"
                + " 6.0 s ago"), text);
        require(WorldStallWatchdog.describe(new WorldStallWatchdog.Stall(5_000 * MS, 1, 0, 0, -1))
                .endsWith("none presented this stream"), "No frame yet");
    }
}
