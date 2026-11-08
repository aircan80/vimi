package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;
import com.vibeminer.rendering.world.TerrainCamera;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The recorded sky mixed into the explorer's look (coverage and fog share, the scaler clamp), the
 * clock, the throttle, and the feed that attaches it to World cameras. The renderer's composition
 * with its fog distances and the WDL horizon is tested in rendering-bgfx (WorldFogTest).
 */
public final class WorldSkyLookTest {
    private WorldSkyLookTest() {}

    private static final WorldStreamTransport.Sky EXPLORER = new WorldStreamTransport.Sky(
            new float[] {150, 180, 200}, new float[] {90, 140, 170}, 0, 0, 0);

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
        var full = WorldSkyLook.of(result(1, 1000, 0.25, 1), EXPLORER);
        near(full.horizon(), new double[] {0x32, 0x46, 0x64}, "horizon from SkyFog");
        near(full.top(), new double[] {0x0a, 0x14, 0x1e}, "top from SkyTop");
        check(full.fogWeight() == 1 && full.recordedStart() == 250 && full.recordedEnd() == 1000,
                "recorded fog " + full);

        // Half covered: colours halfway to the explorer's, fog weighed by the coverage.
        var half = WorldSkyLook.of(result(0.5, 1000, 0.25, 1), EXPLORER);
        near(half.top(), new double[] {50, 80, 100}, "half top");
        check(half.fogWeight() == 0.5, "half weight " + half.fogWeight());

        // Half the value records fog: the weight halves.
        check(WorldSkyLook.of(result(1, 1000, 0.25, 0.5), EXPLORER).fogWeight() == 0.5,
                "fog share");

        // No recorded fog here: recorded colours, no fog weight.
        var clear = WorldSkyLook.of(result(1, 0, 0, 0), EXPLORER);
        near(clear.top(), new double[] {0x0a, 0x14, 0x1e}, "clear top");
        check(clear.fogWeight() == 0, "clear has no recorded fog");

        // Unavailable: the explorer's look unchanged.
        var none = WorldSkyLook.of(new WorldSky.Result(WorldSky.State.UNAVAILABLE, 0, 0, false,
                null, 0, List.of(), List.of()), EXPLORER);
        check(none == EXPLORER, "unavailable keeps the explorer's look");
    }

    /** #177 step 2c: the recorded direct and ambient light ride the sky, weighed like the fog. */
    private static void carriesTheRecordedLight() {
        var sample = new WorldSky.Sample(0x0a141e, 0, 0, 0, 0, 0x324664,
                new WorldSky.Lighting(0xFFE0C0, 0x404060), 1000, 0.25);
        var lit = WorldSkyLook.of(new WorldSky.Result(WorldSky.State.READY, 0, 0, false, sample,
                0.5, List.of(), List.of()), EXPLORER);
        near(lit.direct(), new double[] {0xFF, 0xE0, 0xC0}, "direct from DirectColor");
        near(lit.ambient(), new double[] {0x40, 0x40, 0x60}, "ambient from AmbientColor");
        check(lit.lightWeight() == 0.5, "light weighed by the coverage " + lit.lightWeight());
        // No recorded light: weight 0, the renderer keeps its own light.
        check(WorldSkyLook.of(result(1, 1000, 0.25, 1), EXPLORER).lightWeight() == 0,
                "unlit sample carries no light");
        // No fog but light: the light is still carried.
        var clear = new WorldSky.Sample(0, 0, 0, 0, 0, 0, new WorldSky.Lighting(0x808080, 0x202020),
                0, 0);
        check(WorldSkyLook.of(new WorldSky.Result(WorldSky.State.READY, 0, 0, false, clear, 1,
                List.of(), List.of()), EXPLORER).lightWeight() == 1, "light without fog");
    }

    private static void clampsTheFogScaler() {
        var above = WorldSkyLook.of(result(1, 1000, 3, 1), EXPLORER);
        check(above.recordedEnd() == 1000 && above.recordedStart() == 999,
                "scaler above 1 keeps a yard of ramp " + above);
        // Short and huge recorded ends stay valid whole yards.
        var tiny = WorldSkyLook.of(result(1, 1.4, 1, 1), EXPLORER);
        check(tiny.recordedEnd() == 1 && tiny.recordedStart() == 0, "a one-yard fog " + tiny);
        check(WorldSkyLook.of(result(1, 0.4, 1, 1), EXPLORER).fogWeight() == 0,
                "under a yard is no fog");
        var huge = WorldSkyLook.of(result(1, 5e6, 0.5, 1), EXPLORER);
        check(huge.recordedEnd() == WorldStreamTransport.Sky.MAX_YARDS, "huge end " + huge);
        var below = WorldSkyLook.of(result(1, 1000, -3, 1), EXPLORER);
        check(below.recordedStart() == 0, "negative scaler " + below);
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

    private static WorldStreamTransport.Camera camera(long revision, double x) {
        return new WorldStreamTransport.Camera(revision, 1, 800, 500,
                new TerrainCamera(x, 0, 0, 0, 0));
    }

    /** One map-wide light whose sky top changes from midnight to noon. */
    private static WorldSky.Index index() {
        return WorldSky.Index.of(
                List.of(new WorldSky.Light(1, 7, 0, 0, 0, 0, 0, 100)),
                List.of(
                        new WorldSky.Key(1, 100, 0,
                                new WorldSky.Sample(0x101010, 0, 0, 0, 0, 0, 0, 0)),
                        new WorldSky.Key(2, 100, 1440,
                                new WorldSky.Sample(0xf0f0f0, 0, 0, 0, 0, 0, 900, 0.5))),
                Map.of(), List.of());
    }

    private static void feedsTheSkyOnCamerasOnceTheTablesAreLoaded() {
        check(!WorldSkyFeed.enabled(), "the recorded sky is off by default");

        var pending = new CompletableFuture<WorldSky.Index>();
        var nanos = new AtomicLong();
        var feed = new WorldSkyFeed(pending, 7, () -> LocalTime.NOON, nanos::get);
        check(feed.attach(camera(1, 0)).sky() == null, "no sky before the tables load");

        pending.complete(index());
        var first = feed.attach(camera(2, 0));
        check(first.sky() != null && first.sky().top()[0] == 0xf0, "noon sky " + first.sky());
        check(first.sky().recordedEnd() == 900, "recorded fog " + first.sky());
        check(feed.label() != null && feed.label().contains("Light 1"), "label " + feed.label());

        // A small move within the throttle reuses the last sky on the new camera.
        var near = feed.attach(camera(3, 2));
        check(near.revision() == 3 && near.sky() == first.sky(), "reused sky");

        var failed = new WorldSkyFeed(CompletableFuture.failedFuture(
                new IllegalStateException("tables")), 7, () -> LocalTime.NOON, nanos::get);
        check(failed.attach(camera(1, 0)).sky() == null, "failed tables keep the explorer's sky");
    }

    /**
     * With the camera still, the sky follows the clock: once the half-minute changes and the sky
     * differs, the feed asks for the camera again; a map with a fixed time never does.
     */
    private static void aStillCameraFollowsTheClock() {
        var time = new java.util.concurrent.atomic.AtomicReference<>(LocalTime.NOON);
        var nanos = new AtomicLong();
        var feed = new WorldSkyFeed(CompletableFuture.completedFuture(index()), 7, time::get,
                nanos::get);
        check(!feed.stale(), "nothing to resend before the first camera");
        var noon = feed.attach(camera(1, 0)).sky();
        check(!feed.stale(), "same time: not stale");
        time.set(LocalTime.of(12, 0, 20));
        check(!feed.stale(), "same half-minute: not stale");
        time.set(LocalTime.of(18, 0));
        check(feed.stale(), "evening differs from noon: resend");
        var evening = feed.attach(camera(2, 0)).sky();
        check(!evening.equals(noon), "the resent camera carries the evening sky");
        check(!feed.stale(), "after the resend: not stale");

        // A map whose time is fixed keeps its sky whatever the clock says.
        var fixed = WorldSky.Index.of(
                List.of(new WorldSky.Light(1, 7, 0, 0, 0, 0, 0, 100)),
                List.of(
                        new WorldSky.Key(1, 100, 0,
                                new WorldSky.Sample(0x101010, 0, 0, 0, 0, 0, 0, 0)),
                        new WorldSky.Key(2, 100, 1440,
                                new WorldSky.Sample(0xf0f0f0, 0, 0, 0, 0, 0, 0, 0))),
                Map.of(7L, 0), List.of());
        var still = new WorldSkyFeed(CompletableFuture.completedFuture(fixed), 7, time::get,
                nanos::get);
        time.set(LocalTime.NOON);
        still.attach(camera(1, 0));
        time.set(LocalTime.of(20, 0));
        check(!still.stale(), "a fixed map time never asks for a resend");
    }

    /**
     * A failing lookup never escapes the pulse's stale check, and costs at most one lookup (and
     * no resend) per half-minute: not one on every pulse.
     */
    private static void aFailingLookupNeitherBreaksNorFloodsThePulse() {
        var time = new java.util.concurrent.atomic.AtomicReference<>(LocalTime.NOON);
        var nanos = new AtomicLong();
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        var sky = index();
        WorldSkyFeed.Lookup lookup = (map, x, y, z, at) -> {
            lookups.incrementAndGet();
            if (fail.get()) throw new IllegalStateException("authored sky failure");
            return sky.at(map, x, y, z, at);
        };
        var feed = WorldSkyFeed.of(CompletableFuture.completedFuture(lookup), 7, time::get,
                nanos::get);
        var noon = feed.attach(camera(1, 0)).sky();
        check(noon != null && lookups.get() == 1, "the first camera looks the sky up");

        fail.set(true);
        time.set(LocalTime.of(18, 0));
        check(!WorldStreamSourceController.skyStale(feed), "a failing lookup is not stale");
        for (int pulse = 0; pulse < 100; pulse++) {
            check(!WorldStreamSourceController.skyStale(feed), "still not stale on pulse " + pulse);
        }
        check(lookups.get() == 2, "one failed lookup per half-minute, not one per pulse: "
                + lookups.get());
        check(!WorldStreamSourceController.skyStale(null), "no feed is never stale");

        // A camera sent meanwhile (the user moved) fails its lookup once; camera() logs that.
        nanos.addAndGet(WorldSkyFeed.NANOS);
        try {
            feed.attach(camera(2, 100));
            check(false, "the failing lookup is reported to camera()");
        } catch (IllegalStateException expected) {
            check(lookups.get() == 3, "one lookup for the moved camera");
        }
        var kept = feed.attach(camera(3, 100));
        check(noon.equals(kept.sky()), "the next camera keeps the last sky");
        check(lookups.get() == 3, "and is not retried within the same half-minute and place");

        // The next half-minute tries once more; once the lookup works the sky follows the clock.
        fail.set(false);
        time.set(LocalTime.of(18, 1));
        check(WorldStreamSourceController.skyStale(feed), "a working lookup is stale again");
        check(lookups.get() == 4, "one lookup for the new half-minute");
        // The resend never reached attach (streamCamera returned early): no resend every pulse.
        for (int pulse = 0; pulse < 100; pulse++) {
            check(!WorldStreamSourceController.skyStale(feed), "asked once per half-minute");
        }
        check(lookups.get() == 4, "no lookups while the half-minute lasts");
        var evening = feed.attach(camera(4, 100)).sky();
        check(!evening.equals(noon), "the resent camera carries the evening sky");
    }

    private static void pinsTheClockForOwnerChecks() {
        System.setProperty(WorldSkyFeed.TIME_PROPERTY, "21:30");
        try {
            check(WorldSkyFeed.clock().get().equals(LocalTime.of(21, 30)), "pinned time");
            System.setProperty(WorldSkyFeed.TIME_PROPERTY, "late");
            check(WorldSkyFeed.clock().get() != null, "an unreadable pin uses the clock");
        } finally {
            System.clearProperty(WorldSkyFeed.TIME_PROPERTY);
        }
    }

    public static void main(String[] args) {
        mapsRecordedChannelsAndFog();
        carriesTheRecordedLight();
        clampsTheFogScaler();
        clockToHalfMinutes();
        throttlesLookups();
        feedsTheSkyOnCamerasOnceTheTablesAreLoaded();
        aStillCameraFollowsTheClock();
        aFailingLookupNeitherBreaksNorFloodsThePulse();
        pinsTheClockForOwnerChecks();
        System.out.println("World sky look: recorded channels, coverage and fog share, scaler"
                + " clamp, recorded light, clock, throttle, camera feed, still-camera clock, failing lookups"
                + " and pinned time passed");
    }
}
