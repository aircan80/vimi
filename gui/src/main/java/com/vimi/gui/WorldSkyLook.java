package com.vimi.gui;

import com.vibeminer.concepts.world.WorldSky;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Turns a recorded {@link WorldSky.Result} into the World view's sky and distance fog (#177-2a):
 * the horizon colour the fog fades into, the sky top colour, and the recorded fog. Colours are
 * mixed with the explorer's own by how much of the position the recorded lights cover. The fog is
 * kept as recorded values plus how much of the value records fog, and {@link Look#fog} composes
 * it with the explorer's defaults and, when the WDL horizon is drawn, with the horizon distance.
 * Pure; no renderer state.
 *
 * <p>Channels: the horizon is {@code SkyFog} ("fog colour / background mountains", painted at and
 * below the horizon by reference sky domes) and the top is {@code SkyTop}. The fog end is {@code
 * FogEnd}, and the start is {@code FogEnd × FogScaler} with the scaler clamped to 0..1; neither
 * field's meaning is documented, so this reading is checked on real data before it is enabled.
 */
final class WorldSkyLook {
    private WorldSkyLook() {}

    /** Fog distances in yards, as the renderer's fog takes them. */
    record Fog(float start, float end, float cut) {
        Fog {
            if (!(start >= 0) || !(end > start) || !(cut >= end)) {
                throw new IllegalArgumentException("Invalid fog distances");
            }
        }
    }

    /**
     * Sky and fog for one frame. Colours are 0..255 gamma red, green, blue. {@code fogWeight}
     * (0..1) is how much of this position records fog; {@code recordedStart} and {@code
     * recordedEnd} are that recorded fog, meaningful only when the weight is positive.
     */
    record Look(
            float[] horizon,
            float[] top,
            double fogWeight,
            float recordedStart,
            float recordedEnd) {
        Look {
            if (horizon.length != 3
                    || top.length != 3
                    || !(fogWeight >= 0 && fogWeight <= 1)
                    || fogWeight > 0 && !(recordedEnd > recordedStart && recordedStart >= 0)) {
                throw new IllegalArgumentException("Invalid sky look");
            }
            horizon = horizon.clone();
            top = top.clone();
        }

        /** The explorer's own colours, with no recorded fog. */
        static Look explorer(float[] horizon, float[] top) {
            return new Look(horizon, top, 0, 0, 0);
        }

        @Override
        public float[] horizon() {
            return horizon.clone();
        }

        @Override
        public float[] top() {
            return top.clone();
        }

        /**
         * The fog distances: the recorded fog mixed with the defaults by {@code fogWeight}. The
         * cut is the streamed-terrain cut, or {@code horizonEnd} when the WDL horizon is drawn
         * ({@code NaN} when it is not). With the horizon, the default end is the horizon, so a
         * place without recorded fog fades out at the horizon; a recorded end below it is kept,
         * so foggy zones stay foggy, and fog stays full from there to the cut. The end never
         * passes the cut.
         */
        Fog fog(float defaultStart, float defaultEnd, float streamCut, float horizonEnd) {
            boolean horizon = !Float.isNaN(horizonEnd);
            float cut = horizon ? horizonEnd : streamCut;
            float baseEnd = horizon ? horizonEnd : defaultEnd;
            double end = baseEnd + (recordedEnd - baseEnd) * fogWeight;
            double start = defaultStart + (recordedStart - defaultStart) * fogWeight;
            end = Math.min(end, cut);
            if (!(end > start)) {
                // A scaler of 1 (or a cut below the start) puts the start at the end: keep a
                // sliver so the fog stays a ramp.
                start = Math.max(0, end * 0.99);
            }
            return new Fog((float) start, (float) end, cut);
        }
    }

    /** The recorded sky mixed into {@code explorer}; the explorer's look when unavailable. */
    static Look of(WorldSky.Result result, Look explorer) {
        Objects.requireNonNull(result);
        Objects.requireNonNull(explorer);
        if (result.state() == WorldSky.State.UNAVAILABLE) {
            return explorer;
        }
        var sample = result.sample();
        double cover = result.coverage();
        float[] horizon = mix(explorer.horizon(), rgb(sample.skyFog()), cover);
        float[] top = mix(explorer.top(), rgb(sample.skyTop()), cover);
        double fog = cover * sample.fogShare();
        if (!(fog > 0 && sample.fogEnd() > 0)) {
            return new Look(horizon, top, 0, 0, 0);
        }
        float end = (float) sample.fogEnd();
        float start = (float) (sample.fogEnd() * Math.clamp(sample.fogScaler(), 0, 1));
        if (!(end > start)) {
            start = end * 0.99f;
        }
        return new Look(horizon, top, Math.min(1, fog), start, end);
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
