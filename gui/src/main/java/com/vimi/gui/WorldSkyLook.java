package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Turns a recorded {@link WorldSky.Result} into the World view's sky and distance fog (#177-2a):
 * the horizon colour the fog fades into, the sky top colour, and the fog start and end distances.
 * The recorded values are mixed with the explorer's own look by how much of the position the
 * recorded lights cover, and the fog distances also by how much of that value records fog, so a
 * place without recorded fog keeps the explorer's fog. Pure; no renderer state.
 *
 * <p>Channels: the horizon is {@code SkyFog} ("fog colour / background mountains", painted at and
 * below the horizon by reference sky domes) and the top is {@code SkyTop}. The fog end is {@code
 * FogEnd}, and the start is {@code FogEnd × FogScaler} with the scaler clamped to 0..1; neither
 * field's meaning is documented, so this reading is checked on real data before it is enabled.
 */
final class WorldSkyLook {
    private WorldSkyLook() {}

    /** Sky and fog for one frame. Colours are 0..255 gamma red, green, blue; distances in yards. */
    record Look(float[] horizon, float[] top, float start, float end) {
        Look {
            if (horizon.length != 3 || top.length != 3 || !(start >= 0) || !(end > start)) {
                throw new IllegalArgumentException("Invalid sky look");
            }
            horizon = horizon.clone();
            top = top.clone();
        }

        @Override
        public float[] horizon() {
            return horizon.clone();
        }

        @Override
        public float[] top() {
            return top.clone();
        }
    }

    /** The recorded sky mixed with {@code fallback}; {@code fallback} itself when unavailable. */
    static Look of(WorldSky.Result result, Look fallback) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(fallback);
        if (result.state() == WorldSky.State.UNAVAILABLE) {
            return fallback;
        }
        var sample = result.sample();
        double cover = result.coverage();
        float[] horizon = mix(fallback.horizon(), rgb(sample.skyFog()), cover);
        float[] top = mix(fallback.top(), rgb(sample.skyTop()), cover);
        float start = fallback.start(), end = fallback.end();
        double fog = cover * sample.fogShare();
        if (fog > 0 && sample.fogEnd() > 0) {
            double recordedEnd = sample.fogEnd();
            double recordedStart = recordedEnd * Math.clamp(sample.fogScaler(), 0, 1);
            end = (float) (end + (recordedEnd - end) * fog);
            start = (float) (start + (recordedStart - start) * fog);
            if (!(end > start)) {
                // A scaler of 1 puts the start at the end: keep a sliver so fog stays a ramp.
                start = Math.max(0, end * 0.99f);
            }
        }
        return new Look(horizon, top, start, end);
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
