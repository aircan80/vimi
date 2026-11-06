package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/** The recorded sky mixed into the explorer's look: coverage, fog share, clamps and throttle. */
public final class WorldSkyLookTest {
    private WorldSkyLookTest() {}

    private static final WorldSkyLook.Look FALLBACK = new WorldSkyLook.Look(
            new float[] {150, 180, 200}, new float[] {90, 140, 170}, 600, 2200);

    private static WorldSky.Result result(double coverage, double fogEnd, double scaler,
            double fogShare) {
        var sample = new WorldSky.Sample(0x0a141e, 0, 0, 0, 0, 0x324664, fogEnd, scaler,
                fogShare);
        return new WorldSky.Result(WorldSky.State.READY, 0, 0, false, sample, coverage,
                List.of(), List.of());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(float[] actual, double[] expected, String what) {
        for (int i = 0; i < 3; i++)
            check(Math.abs(actual[i] - expected[i]) < 1e-3, what + " " + Arrays.toString(actual));
    }

    private static void mapsRecordedChannelsAndFog() {
        // Fully covered, all fog recorded: SkyFog is the horizon, SkyTop the top.
        var full = WorldSkyLook.of(result(1, 1000, 0.25, 1), FALLBACK);
        near(full.horizon(), new double[] {0x32, 0x46, 0x64}, "horizon from SkyFog");
        near(full.top(), new double[] {0x0a, 0x14, 0x1e}, "top from SkyTop");
        check(full.end() == 1000 && full.start() == 250, "fog " + full.start() + " " + full.end());

        // Half covered: halfway to the explorer's look, colours and fog alike.
        var half = WorldSkyLook.of(result(0.5, 1000, 0.25, 1), FALLBACK);
        near(half.top(), new double[] {50, 80, 100}, "half top");
        check(half.end() == 1600 && half.start() == 425, "half fog " + half.start() + " "
                + half.end());

        // No recorded fog here: recorded colours, the explorer's fog.
        var clear = WorldSkyLook.of(result(1, 0, 0, 0), FALLBACK);
        near(clear.top(), new double[] {0x0a, 0x14, 0x1e}, "clear top");
        check(clear.start() == 600 && clear.end() == 2200, "clear keeps the explorer's fog");

        // Half the value records fog: the distances move halfway.
        var some = WorldSkyLook.of(result(1, 1000, 0.25, 0.5), FALLBACK);
        check(some.end() == 1600 && some.start() == 425, "fog share " + some.end());

        // Unavailable: the explorer's look unchanged.
        var none = WorldSkyLook.of(new WorldSky.Result(WorldSky.State.UNAVAILABLE, 0, 0, false,
                null, 0, List.of(), List.of()), FALLBACK);
        check(none == FALLBACK, "unavailable keeps the fallback");
    }

    private static void clampsTheFogScaler() {
        var above = WorldSkyLook.of(result(1, 1000, 3, 1), FALLBACK);
        check(above.end() == 1000 && above.start() < 1000 && above.start() >= 989,
                "scaler above 1 " + above.start());
        var below = WorldSkyLook.of(result(1, 1000, -3, 1), FALLBACK);
        check(below.start() == 0 && below.end() == 1000, "negative scaler " + below.start());
    }

    private static void clockToHalfMinutes() {
        check(WorldSkyLook.halfMinutes(LocalTime.MIDNIGHT) == 0, "midnight");
        check(WorldSkyLook.halfMinutes(LocalTime.of(12, 0, 30)) == 1441, "noon and a half");
        check(WorldSkyLook.halfMinutes(LocalTime.of(23, 59, 59)) == WorldSky.DAY - 1, "last");
    }

    private static void throttlesLookups() {
        var throttle = new WorldSkyLook.Throttle(8, 1_000);
        check(throttle.due(0, 0, 0, 0, 0), "first lookup");
        check(!throttle.due(0, 5, 0, 0, 10), "a small move waits");
        check(throttle.due(0, 9, 0, 0, 20), "a move past the distance");
        check(!throttle.due(0, 9, 0, 0, 30), "no change waits");
        check(throttle.due(1, 9, 0, 0, 40), "another map");
        check(throttle.due(1, 9, 0, 0, 1_040), "time passed");
    }

    public static void main(String[] args) {
        mapsRecordedChannelsAndFog();
        clampsTheFogScaler();
        clockToHalfMinutes();
        throttlesLookups();
        System.out.println("World sky look: recorded channels, coverage and fog share blends,"
                + " scaler clamp, clock and throttle passed");
    }
}
