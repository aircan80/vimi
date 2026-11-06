package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * The recorded sky mixed into the explorer's look: coverage and fog share blends, the scaler
 * clamp, composition with the WDL horizon, the clock and the throttle.
 */
public final class WorldSkyLookTest {
    private WorldSkyLookTest() {}

    private static final WorldSkyLook.Look EXPLORER = WorldSkyLook.Look.explorer(
            new float[] {150, 180, 200}, new float[] {90, 140, 170});
    private static final float START = 600, END = 2200, CUT = 2400, HORIZON = 3200;

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

    private static WorldSkyLook.Fog fog(WorldSkyLook.Look look) {
        return look.fog(START, END, CUT, Float.NaN);
    }

    private static void mapsRecordedChannelsAndFog() {
        // Fully covered, all fog recorded: SkyFog is the horizon, SkyTop the top.
        var full = WorldSkyLook.of(result(1, 1000, 0.25, 1), EXPLORER);
        near(full.horizon(), new double[] {0x32, 0x46, 0x64}, "horizon from SkyFog");
        near(full.top(), new double[] {0x0a, 0x14, 0x1e}, "top from SkyTop");
        check(fog(full).equals(new WorldSkyLook.Fog(250, 1000, CUT)), "fog " + fog(full));

        // Half covered: halfway to the explorer's look, colours and fog alike.
        var half = WorldSkyLook.of(result(0.5, 1000, 0.25, 1), EXPLORER);
        near(half.top(), new double[] {50, 80, 100}, "half top");
        check(fog(half).equals(new WorldSkyLook.Fog(425, 1600, CUT)), "half " + fog(half));

        // No recorded fog here: recorded colours, the explorer's fog.
        var clear = WorldSkyLook.of(result(1, 0, 0, 0), EXPLORER);
        near(clear.top(), new double[] {0x0a, 0x14, 0x1e}, "clear top");
        check(fog(clear).equals(new WorldSkyLook.Fog(START, END, CUT)), "clear " + fog(clear));

        // Half the value records fog: the distances move halfway.
        var some = WorldSkyLook.of(result(1, 1000, 0.25, 0.5), EXPLORER);
        check(fog(some).equals(new WorldSkyLook.Fog(425, 1600, CUT)), "fog share " + fog(some));

        // A recorded end past the cut stops at the cut; the cut never moves in.
        var far = WorldSkyLook.of(result(1, 9000, 0.25, 1), EXPLORER);
        check(fog(far).end() == CUT && fog(far).cut() == CUT, "far " + fog(far));

        // Unavailable: the explorer's look unchanged.
        var none = WorldSkyLook.of(new WorldSky.Result(WorldSky.State.UNAVAILABLE, 0, 0, false,
                null, 0, List.of(), List.of()), EXPLORER);
        check(none == EXPLORER, "unavailable keeps the explorer's look");
    }

    /**
     * With the WDL horizon drawn the cut moves out to the horizon. A recorded end below it is
     * kept, so foggy zones stay foggy; without recorded fog the fog ends at the horizon.
     */
    private static void composesWithTheHorizon() {
        var foggy = WorldSkyLook.of(result(1, 800, 0.25, 1), EXPLORER)
                .fog(START, END, CUT, HORIZON);
        check(foggy.equals(new WorldSkyLook.Fog(200, 800, HORIZON)), "foggy zone " + foggy);

        var plain = WorldSkyLook.of(result(1, 0, 0, 0), EXPLORER).fog(START, END, CUT, HORIZON);
        check(plain.equals(new WorldSkyLook.Fog(START, HORIZON, HORIZON)), "no recorded fog "
                + plain);

        // Half recorded: halfway between the recorded end and the horizon.
        var half = WorldSkyLook.of(result(1, 800, 0.25, 0.5), EXPLORER)
                .fog(START, END, CUT, HORIZON);
        check(half.end() == 2000 && half.cut() == HORIZON, "half with horizon " + half);

        // A recorded end beyond the horizon stops at it.
        var far = WorldSkyLook.of(result(1, 9000, 0.25, 1), EXPLORER)
                .fog(START, END, CUT, HORIZON);
        check(far.end() == HORIZON && far.cut() == HORIZON, "far with horizon " + far);
    }

    private static void clampsTheFogScaler() {
        var above = fog(WorldSkyLook.of(result(1, 1000, 3, 1), EXPLORER));
        check(above.end() == 1000 && above.start() < 1000 && above.start() >= 989,
                "scaler above 1 " + above);
        var below = fog(WorldSkyLook.of(result(1, 1000, -3, 1), EXPLORER));
        check(below.start() == 0 && below.end() == 1000, "negative scaler " + below);
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
        composesWithTheHorizon();
        clampsTheFogScaler();
        clockToHalfMinutes();
        throttlesLookups();
        System.out.println("World sky look: recorded channels, coverage and fog share blends,"
                + " horizon composition, scaler clamp, clock and throttle passed");
    }
}
