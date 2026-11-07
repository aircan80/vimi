package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;

import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Attaches the recorded sky (#177-2a) to World cameras, behind {@code
 * -Dvimi.world.sky=recorded} until the owner check. Lookups are throttled by distance, map and
 * time; between them the last sky rides along, so a coalesced camera never loses it. Until the
 * light tables are loaded, or if they fail to load, cameras carry no sky and the renderer keeps
 * the explorer's own.
 */
final class WorldSkyFeed {
    static final String PROPERTY = "vimi.world.sky", TIME_PROPERTY = "vimi.world.sky.time";
    static final double YARDS = 8;
    static final long NANOS = TimeUnit.SECONDS.toNanos(1);

    private final CompletableFuture<WorldSky.Index> index;
    private final long mapId;
    private final Supplier<LocalTime> clock;
    private final LongSupplier nanos;
    private final WorldSkyLook.Throttle throttle = new WorldSkyLook.Throttle(YARDS, NANOS);
    private WorldStreamTransport.Sky last;
    private String label;
    // Where and when (LightData half-minutes) the last sky was looked up.
    private int lastTime = -1;
    private double lastX, lastY, lastZ;

    WorldSkyFeed(
            CompletableFuture<WorldSky.Index> index,
            long mapId,
            Supplier<LocalTime> clock,
            LongSupplier nanos) {
        this.index = Objects.requireNonNull(index);
        this.mapId = mapId;
        this.clock = Objects.requireNonNull(clock);
        this.nanos = Objects.requireNonNull(nanos);
    }

    /** True when {@code -Dvimi.world.sky=recorded}. */
    static boolean enabled() {
        return "recorded".equals(System.getProperty(PROPERTY));
    }

    /**
     * The local clock, or the time pinned by {@code -Dvimi.world.sky.time=HH:MM} for owner
     * checks. An unreadable pin is ignored.
     */
    static Supplier<LocalTime> clock() {
        String pinned = System.getProperty(TIME_PROPERTY);
        if (pinned != null) {
            try {
                var time = LocalTime.parse(pinned);
                return () -> time;
            } catch (java.time.format.DateTimeParseException unreadable) {
                /* Falls back to the local clock. */
            }
        }
        return LocalTime::now;
    }

    /** {@code camera} with the recorded sky at its position, when the tables are loaded. */
    synchronized WorldStreamTransport.Camera attach(WorldStreamTransport.Camera camera) {
        Objects.requireNonNull(camera);
        WorldSky.Index sky;
        try {
            sky = index.getNow(null);
        } catch (CompletionException | CancellationException unavailable) {
            return camera;
        }
        if (sky == null) {
            return camera;
        }
        var view = camera.view();
        int time = WorldSkyLook.halfMinutes(clock.get());
        boolean moved = throttle.due(mapId, view.x(), view.y(), view.z(), nanos.getAsLong());
        if (moved || time != lastTime) {
            var result = sky.at(mapId, view.x(), view.y(), view.z(), time);
            last = WorldSkyLook.of(result, WorldStreamTransport.Sky.EXPLORER);
            label = result.label();
            lastTime = time;
            lastX = view.x();
            lastY = view.y();
            lastZ = view.z();
        }
        return last == null ? camera : camera.withSky(last);
    }

    /**
     * True when the time of day has moved on (LightData keys are half-minutes, so at most every
     * 30 s) and the sky at the last camera's place now differs, so a still camera should be sent
     * again. A map with a fixed time, or a sky that did not change, asks for nothing.
     */
    synchronized boolean stale() {
        if (last == null) {
            return false;
        }
        int time = WorldSkyLook.halfMinutes(clock.get());
        if (time == lastTime) {
            return false;
        }
        WorldSky.Index sky;
        try {
            sky = index.getNow(null);
        } catch (CompletionException | CancellationException unavailable) {
            return false;
        }
        if (sky == null) {
            return false;
        }
        var next =
                WorldSkyLook.of(
                        sky.at(mapId, lastX, lastY, lastZ, time),
                        WorldStreamTransport.Sky.EXPLORER);
        if (next.equals(last)) {
            lastTime = time;
            return false;
        }
        return true;
    }

    /** The source line of the last lookup, or null before the first. */
    synchronized String label() {
        return label;
    }
}
