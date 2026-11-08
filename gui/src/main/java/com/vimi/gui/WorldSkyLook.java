package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;
import com.vibeminer.rendering.bgfx.WorldStreamTransport;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Turns a recorded {@link WorldSky.Result} into the World view's sky and distance fog (#177-2a):
 * the horizon colour the fog fades into, the sky top colour, and the recorded fog. Colours are
 * mixed with the explorer's own by how much of the position the recorded lights cover. The fog is
 * kept as recorded values plus how much of the value records fog; the renderer composes it with
 * its own fog distances and the WDL horizon. Pure; no renderer state.
 *
 * <p>Channels: the horizon is {@code SkyFog} ("fog colour / background mountains", painted at and
 * below the horizon by reference sky domes) and the top is {@code SkyTop}. The fog end is {@code
 * FogEnd}, and the start is {@code FogEnd × FogScaler} with the scaler clamped to 0..1; neither
 * field's meaning is documented, so this reading is checked on real data before it is enabled.
 */
final class WorldSkyLook {
    private WorldSkyLook() {}

    /** The recorded sky mixed into {@code explorer}; the explorer's look when unavailable. */
    static WorldStreamTransport.Sky of(WorldSky.Result result, WorldStreamTransport.Sky explorer) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(explorer);
        if (result.state() == WorldSky.State.UNAVAILABLE) {
            return explorer;
        }
        var sample = result.sample();
        double cover = result.coverage();
        float[] horizon = mix(explorer.horizon(), rgb(sample.skyFog()), cover);
        float[] top = mix(explorer.top(), rgb(sample.skyTop()), cover);
        // The recorded direct and ambient light (#177 step 2c), weighted like the fog by how much
        // of the position records it; the renderer mixes it into its own light.
        double lit = sample.hasLight() ? Math.min(1, cover * sample.lightShare()) : 0;
        float[] direct = lit > 0 ? rgb(sample.lighting().direct()) : new float[3];
        float[] ambient = lit > 0 ? rgb(sample.lighting().ambient()) : new float[3];
        double fog = cover * sample.fogShare();
        if (!(fog > 0 && sample.fogEnd() > 0)) {
            return new WorldStreamTransport.Sky(horizon, top, 0, 0, 0, direct, ambient, lit);
        }
        // Whole yards, as the camera line carries them; the start stays at least a yard before
        // the end so the fog remains a ramp, and an end under a yard is no fog.
        long end = Math.min(Math.round(sample.fogEnd()), WorldStreamTransport.Sky.MAX_YARDS);
        if (end < 1) {
            return new WorldStreamTransport.Sky(horizon, top, 0, 0, 0, direct, ambient, lit);
        }
        long start =
                Math.min(Math.round(end * Math.clamp(sample.fogScaler(), 0, 1)), end - 1);
        return new WorldStreamTransport.Sky(
                horizon, top, Math.min(1, fog), start, end, direct, ambient, lit);
    }

    /** A local clock time as LightData half-minutes (0..2879). */
    static int halfMinutes(LocalTime time) {
        return (time.getHour() * 60 + time.getMinute()) * 2 + (time.getSecond() >= 30 ? 1 : 0);
    }

    private static float[] rgb(int colour) {
        return new float[] {(colour >> 16) & 0xff, (colour >> 8) & 0xff, colour & 0xff};
    }

    private static float[] mix(float[] from, float[] to, double t) {
        return new float[] {
            (float) (from[0] + (to[0] - from[0]) * t),
            (float) (from[1] + (to[1] - from[1]) * t),
            (float) (from[2] + (to[2] - from[2]) * t)
        };
    }

    /**
     * Decides when the camera has moved or time has passed enough to look the sky up again:
     * more than {@code yards} away from the last lookup, another map, or {@code nanos} later.
     */
    static final class Throttle {
        private final double yards;
        private final long nanos;
        private long map = -1, at;
        private double x, y, z;
        private boolean looked;

        Throttle(double yards, long nanos) {
            if (!(yards >= 0) || nanos < 0) {
                throw new IllegalArgumentException("Invalid sky throttle");
            }
            this.yards = yards;
            this.nanos = nanos;
        }

        /** True when a lookup is due; it is then recorded as made at this place and time. */
        boolean due(long mapId, double x, double y, double z, long now) {
            double dx = x - this.x, dy = y - this.y, dz = z - this.z;
            boolean due =
                    !looked
                            || mapId != map
                            || dx * dx + dy * dy + dz * dz > yards * yards
                            || now - at >= nanos;
            if (due) {
                looked = true;
                map = mapId;
                this.x = x;
                this.y = y;
                this.z = z;
                at = now;
            }
            return due;
        }
    }
}
