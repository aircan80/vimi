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
        if (throttle.due(mapId, view.x(), view.y(), view.z(), nanos.getAsLong())) {
            var result =
                    sky.at(mapId, view.x(), view.y(), view.z(),
                            WorldSkyLook.halfMinutes(clock.get()));
            last = WorldSkyLook.of(result, WorldStreamTransport.Sky.EXPLORER);
            label = result.label();
        }
        return last == null ? camera : camera.withSky(last);
    }

    /** The source line of the last lookup, or null before the first. */
    synchronized String label() {
        return label;
    }
}
