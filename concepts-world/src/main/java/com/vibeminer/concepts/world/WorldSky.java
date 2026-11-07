package com.vibeminer.concepts.world;

import com.vibeminer.access.Cancellation;
import com.vibeminer.formats.tables.BuildTables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sky colours and distance fog from the recorded Light, LightParams and LightData tables, for one
 * map position and time of day. This is a pure lookup over rows that {@link #load} has read through
 * reviewed layouts; it owns no renderer state.
 *
 * <p>What the tables decide: which Light rows belong to a map ({@code ContinentID}), each light's
 * centre and its inner and outer radius ({@code GameCoords}, {@code GameFalloffStart}, {@code
 * GameFalloffEnd}), the parameter set in {@code LightParamsID[0]}, the timed LightData keys of that
 * set, and a map's fixed time ({@code Map.TimeOfDayOverride}). The fixed time is assumed to count
 * the same half-minute steps as {@code LightData.Time} (0..2879); the tables don't record its unit.
 *
 * <p>Falloff matches the established reference behaviour: a light is a sphere around its centre,
 * with weight 1 inside the inner radius falling linearly to 0 at the outer radius over 3D distance
 * (the wowdev wiki's Light page; Noggit's {@code Skies::findSkyWeights}). Lights recorded deeper
 * below the terrain than their outer radius are consistent with that: they are underground volumes.
 *
 * <p>What is this module's model and not a recorded rule: when positional weights add up to more
 * than 1 they are scaled to sum to 1 (Noggit instead lets the smaller light take precedence and
 * scales the larger ones down); the map's single zero-position, zero-radius light takes the
 * remaining weight; channels interpolate linearly between the two keys around the requested time,
 * wrapping at midnight. Only parameter slot 0 is read, because the meaning of the other slots is
 * not recorded in the tables.
 */
public final class WorldSky {
    /** LightData and Map times count half-minutes; a day has 2880 of them. */
    public static final int DAY = 2880;

    public static final int NOON = DAY / 2;
    public static final int MAX_LIGHTS = 65536, MAX_KEYS = 65536, MAX_MAPS = 65536;
    public static final int MAX_CONTRIBUTIONS = 64;

    /**
     * The light a map without its own map-wide light falls back to, per the client's documented
     * rule ("If there is no (0,0,0) entry for a continent, use m_ID == 1 as fallback", the wowdev
     * wiki's Light structure).
     */
    public static final long FALLBACK_LIGHT = 1;

    private WorldSky() {}

    public enum State {
        /** Every contributing value came from complete tables. */
        READY,
        /** A value was produced, but some source rows or relationships were unavailable. */
        PARTIAL,
        /** No recorded light covers this position; callers keep their own fallback. */
        UNAVAILABLE
    }

    /** One Light row, reduced to the fields this module reads. */
    public record Light(
            long id,
            long mapId,
            double x,
            double y,
            double z,
            double falloffStart,
            double falloffEnd,
            long paramId) {
        public Light {
            if (id < 0
                    || mapId < 0
                    || paramId < 0
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Double.isFinite(falloffStart)
                    || !Double.isFinite(falloffEnd)) {
                throw new IllegalArgumentException("Invalid light row");
            }
        }

        /** The table's map-wide light: no position and no radius. */
        public boolean mapDefault() {
            return x == 0 && y == 0 && z == 0 && falloffStart == 0 && falloffEnd == 0;
        }
    }

    /**
     * Recorded light, 0xRRGGBB: {@code direct} is {@code DirectColor} (sun or moon light) and
     * {@code ambient} is {@code AmbientColor}.
     */
    public record Lighting(int direct, int ambient) {
        public Lighting {
            if (((direct | ambient) & 0xff00_0000) != 0) {
                throw new IllegalArgumentException("Colour is not 0xRRGGBB");
            }
        }
    }

    /**
     * Sky, fog and light channels; colours are recorded 0xRRGGBB values. A recorded {@code FogEnd}
     * of 0 (or less) means the row records no distance fog. {@code fogShare} is how much of this
     * value had recorded fog (0..1): fog distance and scaler are averaged over that share only, so
     * a key or light without fog never pulls the distance toward 0. A share of 0 means no recorded
     * fog here; callers keep their own fog, or blend it in by {@code 1 - fogShare}. {@code
     * lighting} and {@code lightShare} work the same way for the recorded light: {@code lighting}
     * is null exactly when {@code lightShare} is 0 (no recorded light here, for example when the
     * build's LightData has no light fields), and a key without light never pulls the light toward
     * black.
     */
    public record Sample(
            int skyTop,
            int skyMiddle,
            int skyBand1,
            int skyBand2,
            int skySmog,
            int skyFog,
            double fogEnd,
            double fogScaler,
            double fogShare,
            Lighting lighting,
            double lightShare) {
        /** Number of sky colour channels, in {@link #colours()} order. */
        static final int COLOURS = 6;

        public Sample {
            for (int colour : new int[] {skyTop, skyMiddle, skyBand1, skyBand2, skySmog, skyFog}) {
                if ((colour & 0xff00_0000) != 0) {
                    throw new IllegalArgumentException("Colour is not 0xRRGGBB");
                }
            }
            if (!Double.isFinite(fogEnd)
                    || !Double.isFinite(fogScaler)
                    || !(fogShare >= 0 && fogShare <= 1)) {
                throw new IllegalArgumentException("Invalid fog values");
            }
            if (!(lightShare >= 0 && lightShare <= 1) || (lighting == null) != (lightShare == 0)) {
                throw new IllegalArgumentException("Invalid light values");
            }
        }

        /** Sky and fog without recorded light. */
        public Sample(
                int skyTop,
                int skyMiddle,
                int skyBand1,
                int skyBand2,
                int skySmog,
                int skyFog,
                double fogEnd,
                double fogScaler,
                double fogShare) {
            this(
                    skyTop, skyMiddle, skyBand1, skyBand2, skySmog, skyFog, fogEnd, fogScaler,
                    fogShare, null, 0);
        }

        /** A recorded LightData row without light: see the light-bearing constructor. */
        public Sample(
                int skyTop,
                int skyMiddle,
                int skyBand1,
                int skyBand2,
                int skySmog,
                int skyFog,
                double fogEnd,
                double fogScaler) {
            this(skyTop, skyMiddle, skyBand1, skyBand2, skySmog, skyFog, null, fogEnd, fogScaler);
        }

        /**
         * A recorded LightData row: it has fog exactly when its {@code FogEnd} is positive, and
         * light exactly when {@code lighting} is not null.
         */
        public Sample(
                int skyTop,
                int skyMiddle,
                int skyBand1,
                int skyBand2,
                int skySmog,
                int skyFog,
                Lighting lighting,
                double fogEnd,
                double fogScaler) {
            this(
                    skyTop,
                    skyMiddle,
                    skyBand1,
                    skyBand2,
                    skySmog,
                    skyFog,
                    fogEnd > 0 ? fogEnd : 0,
                    fogEnd > 0 ? fogScaler : 0,
                    fogEnd > 0 ? 1 : 0,
                    lighting,
                    lighting == null ? 0 : 1);
        }

        private Sample(
                int[] c,
                double fogEnd,
                double fogScaler,
                double fogShare,
                int[] light,
                double lightShare) {
            this(
                    c[0],
                    c[1],
                    c[2],
                    c[3],
                    c[4],
                    c[5],
                    fogEnd,
                    fogScaler,
                    fogShare,
                    lightShare > 0 ? new Lighting(light[0], light[1]) : null,
                    lightShare > 0 ? Math.min(1, lightShare) : 0);
        }

        /** True when part of this value has recorded distance fog. */
        public boolean hasFog() {
            return fogShare > 0;
        }

        /** True when part of this value has recorded direct and ambient light. */
        public boolean hasLight() {
            return lightShare > 0;
        }

        private int[] colours() {
            return new int[] {skyTop, skyMiddle, skyBand1, skyBand2, skySmog, skyFog};
        }

        private int[] light() {
            return lighting == null
                    ? new int[2]
                    : new int[] {lighting.direct(), lighting.ambient()};
        }

        /** Weighted average; weights sum to 1 and each colour channel is rounded once. */
        static Sample blend(List<Sample> samples, double[] weights) {
            if (samples.size() == 1) {
                return samples.getFirst();
            }
            double[] sum = new double[COLOURS * 3];
            double[] lightSum = new double[2 * 3];
            double fogEnd = 0, fogScaler = 0, fogShare = 0, lightShare = 0;
            for (int i = 0; i < samples.size(); i++) {
                var s = samples.get(i);
                add(sum, s.colours(), weights[i]);
                // Fog and light average over the share of each sample that records them only.
                double fog = s.fogShare * weights[i];
                fogEnd += s.fogEnd * fog;
                fogScaler += s.fogScaler * fog;
                fogShare += fog;
                double lit = s.lightShare * weights[i];
                add(lightSum, s.light(), lit);
                lightShare += lit;
            }
            return new Sample(
                    rounded(sum, 1),
                    fogShare > 0 ? fogEnd / fogShare : 0,
                    fogShare > 0 ? fogScaler / fogShare : 0,
                    Math.min(1, fogShare),
                    lightShare > 0 ? rounded(lightSum, lightShare) : null,
                    lightShare);
        }

        private static void add(double[] sum, int[] colours, double weight) {
            for (int c = 0; c < colours.length; c++) {
                for (int b = 0; b < 3; b++) {
                    sum[c * 3 + b] += ((colours[c] >> (16 - 8 * b)) & 0xff) * weight;
                }
            }
        }

        private static int[] rounded(double[] sum, double share) {
            int[] out = new int[sum.length / 3];
            for (int c = 0; c < out.length; c++) {
                for (int b = 0; b < 3; b++) {
                    int value = Math.clamp(Math.round(sum[c * 3 + b] / share), 0, 255);
                    out[c] |= value << (16 - 8 * b);
                }
            }
            return out;
        }

        /**
         * Interpolates from {@code a} to {@code b}; a key without fog contributes no fog distance,
         * only a smaller fog share, and a key without light no light, only a smaller light share.
         */
        static Sample mix(Sample a, Sample b, double f) {
            double wa = (1 - f) * a.fogShare, wb = f * b.fogShare, share = wa + wb;
            int[] from = a.colours(), to = b.colours(), out = new int[COLOURS];
            for (int c = 0; c < COLOURS; c++) {
                out[c] = colour(from[c], to[c], f);
            }
            double la = (1 - f) * a.lightShare, lb = f * b.lightShare, lit = la + lb;
            // Light averages over the lit share of each key, like fog.
            double[] lightSum = new double[2 * 3];
            add(lightSum, a.light(), la);
            add(lightSum, b.light(), lb);
            int[] light = lit > 0 ? rounded(lightSum, lit) : null;
            return new Sample(
                    out,
                    share > 0 ? (a.fogEnd * wa + b.fogEnd * wb) / share : 0,
                    share > 0 ? (a.fogScaler * wa + b.fogScaler * wb) / share : 0,
                    Math.min(1, share),
                    light,
                    lit);
        }

        private static int colour(int a, int b, double f) {
            int result = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                int from = (a >> shift) & 0xff, to = (b >> shift) & 0xff;
                int value = (int) Math.round(from + (to - from) * f);
                result |= Math.clamp(value, 0, 255) << shift;
            }
            return result;
        }
    }

    /** One LightData row: a parameter set's channels at one time of day. */
    public record Key(long id, long paramId, int time, Sample sample) {
        public Key {
            Objects.requireNonNull(sample);
            if (id < 0 || paramId < 0 || time < 0 || time >= DAY) {
                throw new IllegalArgumentException("Invalid light data key");
            }
        }
    }

    /** A light's share of the result and the LightData rows its value was taken from. */
    public record Contribution(
            long lightId, long paramId, boolean mapDefault, double weight, List<Long> dataRows) {
        public Contribution {
            dataRows = List.copyOf(dataRows);
            if (!(weight > 0 && weight <= 1) || dataRows.isEmpty() || dataRows.size() > 2) {
                throw new IllegalArgumentException("Invalid light contribution");
            }
        }
    }

    /**
     * {@code sample} blends only the recorded lights that contribute. {@code coverage} (0..1) is
     * their total share of this position: below 1 where part of the position has no recorded light
     * (a positional light fading out on a map without a usable map-wide light, or a set without
     * LightData). Callers mix their own fallback in by {@code 1 - coverage}, so the sky never snaps
     * at a light's outer radius.
     */
    public record Result(
            State state,
            long mapId,
            int time,
            boolean timeFixedByMap,
            Sample sample,
            double coverage,
            List<Contribution> contributions,
            List<String> issues) {
        public Result {
            Objects.requireNonNull(state);
            contributions = List.copyOf(contributions);
            issues = List.copyOf(issues);
            if ((state == State.UNAVAILABLE) != (sample == null)
                    || !(coverage >= 0 && coverage <= 1)
                    || (state == State.UNAVAILABLE) != (coverage == 0)
                    || contributions.size() > MAX_CONTRIBUTIONS
                    || time < 0
                    || time >= DAY) {
                throw new IllegalArgumentException("Inconsistent sky result");
            }
        }

        /** Short source line for a status bar, without coordinates. */
        public String label() {
            if (state == State.UNAVAILABLE) {
                return "Sky: no recorded light here";
            }
            var top = contributions.getFirst();
            var text = new StringBuilder("Sky: Light ").append(top.lightId());
            if (contributions.size() > 1) {
                text.append(" (+").append(contributions.size() - 1).append(" blended)");
            }
            text.append(" · LightParams ").append(top.paramId()).append(" · ");
            text.append(
                    String.format(java.util.Locale.ROOT, "%02d:%02d", time / 120, time / 2 % 60));
            if (timeFixedByMap) {
                text.append(" (map time)");
            }
            if (coverage < 1) {
                text.append(
                        String.format(java.util.Locale.ROOT, " · %.0f%% covered", coverage * 100));
            }
            if (state == State.PARTIAL) {
                text.append(" · partial");
            }
            return text.toString();
        }
    }

    /** Lights grouped by map and keys grouped by parameter set, ready for repeated lookups. */
    public static final class Index {
        private final Map<Long, List<Light>> positional;
        private final Map<Long, List<Light>> defaults;
        private final Map<Long, List<Key>> keys;
        private final Map<Long, Integer> mapTimes;
        private final List<String> sourceIssues;
        private final Light fallback;

        private Index(
                Map<Long, List<Light>> positional,
                Map<Long, List<Light>> defaults,
                Map<Long, List<Key>> keys,
                Map<Long, Integer> mapTimes,
                List<String> sourceIssues,
                Light fallback) {
            this.positional = positional;
            this.defaults = defaults;
            this.keys = keys;
            this.mapTimes = mapTimes;
            this.sourceIssues = List.copyOf(sourceIssues);
            this.fallback = fallback;
        }

        /**
         * @param mapTimes recorded {@code Map.TimeOfDayOverride} values; negative means none
         * @param sourceIssues table-level gaps (for example a partial table) carried into every
         *     result
         */
        public static Index of(
                Collection<Light> lights,
                Collection<Key> keys,
                Map<Long, Integer> mapTimes,
                List<String> sourceIssues) {
            Objects.requireNonNull(lights);
            Objects.requireNonNull(keys);
            Objects.requireNonNull(mapTimes);
            Objects.requireNonNull(sourceIssues);
            if (lights.size() > MAX_LIGHTS
                    || keys.size() > MAX_KEYS
                    || mapTimes.size() > MAX_MAPS) {
                throw new IllegalArgumentException("Sky source exceeds bounds");
            }
            var issues = new ArrayList<>(sourceIssues);
            var positional = new HashMap<Long, List<Light>>();
            var defaults = new HashMap<Long, List<Light>>();
            var seenLights = new java.util.HashSet<Long>();
            int unusable = 0;
            Light fallback = null;
            for (var light : lights) {
                Objects.requireNonNull(light);
                if (!seenLights.add(light.id())) {
                    throw new IllegalArgumentException("Duplicate light row " + light.id());
                }
                if (light.id() == FALLBACK_LIGHT) {
                    fallback = light;
                }
                if (light.mapDefault()) {
                    defaults.computeIfAbsent(light.mapId(), id -> new ArrayList<>()).add(light);
                } else if (light.falloffEnd() > 0 && light.falloffStart() <= light.falloffEnd()) {
                    positional.computeIfAbsent(light.mapId(), id -> new ArrayList<>()).add(light);
                } else {
                    unusable++;
                }
            }
            if (unusable > 0) {
                issues.add(
                        unusable + " positional Light rows have no usable radius and are skipped.");
            }
            var byParam = new HashMap<Long, List<Key>>();
            var seenKeys = new java.util.HashSet<Long>();
            for (var key : keys) {
                Objects.requireNonNull(key);
                if (!seenKeys.add(key.id())) {
                    throw new IllegalArgumentException("Duplicate light data row " + key.id());
                }
                byParam.computeIfAbsent(key.paramId(), id -> new ArrayList<>()).add(key);
            }
            int duplicateTimes = 0;
            for (var entry : byParam.entrySet()) {
                var sorted = new ArrayList<>(entry.getValue());
                sorted.sort(Comparator.comparingInt(Key::time).thenComparingLong(Key::id));
                var unique = new ArrayList<Key>(sorted.size());
                for (var key : sorted) {
                    if (!unique.isEmpty() && unique.getLast().time() == key.time()) {
                        duplicateTimes++;
                        continue;
                    }
                    unique.add(key);
                }
                entry.setValue(List.copyOf(unique));
            }
            if (duplicateTimes > 0) {
                issues.add(
                        duplicateTimes
                                + " LightData rows repeat a time of their parameter set; the"
                                + " lowest row ID is kept.");
            }
            var times = new HashMap<Long, Integer>();
            for (var entry : mapTimes.entrySet()) {
                int value = Objects.requireNonNull(entry.getValue());
                if (value >= 0 && value < DAY) {
                    times.put(Objects.requireNonNull(entry.getKey()), value);
                } else if (value >= DAY) {
                    issues.add("Map " + entry.getKey() + " has an out-of-range fixed time.");
                }
            }
            freeze(positional);
            freeze(defaults);
            return new Index(
                    Map.copyOf(positional),
                    Map.copyOf(defaults),
                    Map.copyOf(byParam),
                    Map.copyOf(times),
                    issues,
                    fallback);
        }

        private static void freeze(Map<Long, List<Light>> map) {
            for (var entry : map.entrySet()) {
                var sorted = new ArrayList<>(entry.getValue());
                sorted.sort(Comparator.comparingLong(Light::id));
                entry.setValue(List.copyOf(sorted));
            }
        }

        public List<String> sourceIssues() {
            return sourceIssues;
        }

        /** The parameter set's channels at {@code time}, or null when the set has no keys. */
        public Sampled sample(long paramId, int time) {
            checkTime(time);
            var list = keys.get(paramId);
            if (list == null || list.isEmpty()) {
                return null;
            }
            if (list.size() == 1) {
                var only = list.getFirst();
                return new Sampled(only.sample(), List.of(only.id()));
            }
            int next = 0;
            while (next < list.size() && list.get(next).time() <= time) {
                next++;
            }
            var before = list.get((next - 1 + list.size()) % list.size());
            var after = list.get(next % list.size());
            int span = Math.floorMod(after.time() - before.time(), DAY);
            int into = Math.floorMod(time - before.time(), DAY);
            if (into == 0) {
                return new Sampled(before.sample(), List.of(before.id()));
            }
            return new Sampled(
                    Sample.mix(before.sample(), after.sample(), (double) into / span),
                    List.of(before.id(), after.id()));
        }

        /**
         * The blended sky at a native world position ({@code GameCoords} use the same axes as
         * terrain chunk origins) and a requested time of day; a map's recorded fixed time wins.
         */
        public Result at(long mapId, double x, double y, double z, int requestedTime) {
            checkTime(requestedTime);
            if (mapId < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid sky query");
            }
            Integer fixed = mapTimes.get(mapId);
            int time = fixed == null ? requestedTime : fixed;
            var issues = new LinkedHashSet<>(sourceIssues);
            var weighted = new ArrayList<Weighted>();
            double total = 0;
            for (var light : positional.getOrDefault(mapId, List.of())) {
                double w = weight(light, x, y, z);
                if (w > 0) {
                    weighted.add(new Weighted(light, w));
                    total += w;
                }
            }
            if (total > 1) {
                for (int i = 0; i < weighted.size(); i++) {
                    var item = weighted.get(i);
                    weighted.set(i, new Weighted(item.light(), item.weight() / total));
                }
                total = 1;
            } else if (total < 1) {
                var candidates = defaults.getOrDefault(mapId, List.of());
                if (candidates.size() == 1) {
                    weighted.add(new Weighted(candidates.getFirst(), 1 - total));
                } else if (candidates.size() > 1) {
                    issues.add(
                            "Map "
                                    + mapId
                                    + " records "
                                    + candidates.size()
                                    + " map-wide lights; which applies is not recorded here.");
                } else if (fallback != null) {
                    // The client's documented rule: a map without a map-wide light uses Light 1.
                    weighted.add(new Weighted(fallback, 1 - total));
                } else {
                    issues.add(
                            "Map "
                                    + mapId
                                    + " records no map-wide light, and Light "
                                    + FALLBACK_LIGHT
                                    + " (the fallback) is not recorded.");
                }
            }
            weighted.sort(
                    Comparator.comparingDouble(Weighted::weight)
                            .reversed()
                            .thenComparingLong(item -> item.light().id()));
            var parts = new ArrayList<Contribution>();
            var samples = new ArrayList<Sample>();
            double used = 0;
            for (var item : weighted) {
                if (parts.size() == MAX_CONTRIBUTIONS) {
                    issues.add(
                            "More than " + MAX_CONTRIBUTIONS + " lights overlap; extras skipped.");
                    break;
                }
                var light = item.light();
                var sampled = sample(light.paramId(), time);
                if (sampled == null) {
                    issues.add(
                            "LightParams "
                                    + light.paramId()
                                    + " of Light "
                                    + light.id()
                                    + " has no LightData rows.");
                    continue;
                }
                parts.add(
                        new Contribution(
                                light.id(),
                                light.paramId(),
                                light.mapDefault(),
                                item.weight(),
                                sampled.dataRows()));
                samples.add(sampled.sample());
                used += item.weight();
            }
            if (parts.isEmpty()) {
                return new Result(
                        State.UNAVAILABLE,
                        mapId,
                        time,
                        fixed != null,
                        null,
                        0,
                        List.of(),
                        List.copyOf(issues));
            }
            // The sample blends the contributing lights; missing shares (no usable map-wide light,
            // or a set without keys) are reported as uncovered, not hidden by renormalising.
            var weights = new double[parts.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = parts.get(i).weight() / used;
            }
            Sample blended = Sample.blend(samples, weights);
            return new Result(
                    issues.isEmpty() ? State.READY : State.PARTIAL,
                    mapId,
                    time,
                    fixed != null,
                    blended,
                    Math.min(1, used),
                    parts,
                    List.copyOf(issues));
        }
    }

    /** One parameter set sampled at one time, with the LightData rows used. */
    public record Sampled(Sample sample, List<Long> dataRows) {
        public Sampled {
            Objects.requireNonNull(sample);
            dataRows = List.copyOf(dataRows);
        }
    }

    private record Weighted(Light light, double weight) {}

    /** Linear falloff over 3D distance: 1 inside the inner radius, 0 beyond the outer radius. */
    static double weight(Light light, double x, double y, double z) {
        double dx = x - light.x(), dy = y - light.y(), dz = z - light.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= light.falloffStart()) {
            return 1;
        }
        if (distance >= light.falloffEnd()) {
            return 0;
        }
        return (light.falloffEnd() - distance) / (light.falloffEnd() - light.falloffStart());
    }

    private static void checkTime(int time) {
        if (time < 0 || time >= DAY) {
            throw new IllegalArgumentException("Time of day is outside 0.." + (DAY - 1));
        }
    }

    /** Reads the reviewed light tables; call from a worker, not the UI thread. */
    public static Index load(BuildTables tables, Cancellation token) {
        return WorldSkyTables.load(tables, token);
    }
}
