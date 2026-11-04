package com.vibeminer.concepts.locations;

import com.vibeminer.access.Cancellation;
import com.vibeminer.polestar.PolestarRepository;
import com.vibeminer.polestar.PolestarTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Declared NPC spawn points recorded in world yards on a client Map ID, indexed for bounded nearby
 * queries.
 *
 * <p>Only a source that records native world yards ({@code x} north, {@code y} west, optional
 * {@code z} up) on a client Map ID is admitted. Area percentages (Questie) and player-interaction
 * observations (EG Link) are not world placements and are never converted here. A creature
 * definition alone is not a spawn. Phase, patrol paths, respawn state and server visibility are not
 * recorded by the admitted source and remain unknown.
 */
public final class WorldSpawns {
    /** Source record bounds; a larger table keeps a readable prefix with an explicit issue. */
    public static final int MAX_SPAWNS = 262_144, MAX_NPCS = 65_536;

    /** Query bounds; callers cannot request unbounded neighborhoods. */
    public static final int MAX_NEARBY = 512;

    public static final double MAX_RADIUS = 2_000;

    /** Half the 64-tile terrain grid span in yards; points outside cannot lie on a terrain map. */
    public static final double WORLD_EXTENT = 32 * 1600.0 / 3.0;

    static final double CELL = 128;
    private static final double MAX_HEIGHT = 20_000;

    private WorldSpawns() {}

    /** Recorded height evidence. {@code ABSENT} points have planar evidence only. */
    public enum Height {
        RECORDED,
        ABSENT
    }

    /** Published creature facts used for labels; every field except the ID may be unknown. */
    public record Npc(
            long id,
            String name,
            String subname,
            Integer levelMin,
            Integer levelMax,
            Integer rank,
            LocationTypes.Evidence evidence) {
        public Npc {
            LocationTypes.validId(id, false);
            Objects.requireNonNull(evidence);
        }
    }

    /** One declared point. Coordinates are native world yards on {@code mapId}. */
    public record Spawn(
            long npcId,
            long mapId,
            long point,
            double x,
            double y,
            Double z,
            LocationTypes.Evidence evidence) {
        public Spawn {
            LocationTypes.validId(npcId, false);
            LocationTypes.validId(mapId, true);
            LocationTypes.validId(point, false);
            Objects.requireNonNull(evidence);
            if (!coordinate(x) || !coordinate(y) || z != null && !validHeight(z)) {
                throw new IllegalArgumentException("Spawn coordinate outside the world grid");
            }
        }

        public Height height() {
            return z == null ? Height.ABSENT : Height.RECORDED;
        }
    }

    /**
     * Nearest-first placed points with recorded height, plus counts that a caller must disclose:
     * points without height in range and recorded points beyond the requested limit.
     */
    public record Nearby(
            long mapId,
            double radius,
            List<Spawn> placed,
            int heightless,
            int omitted,
            List<String> issues) {
        public Nearby {
            placed = List.copyOf(placed);
            issues = List.copyOf(issues);
        }
    }

    /** Immutable index over one provider snapshot. */
    public static final class Index {
        private final LocationTypes.State state;
        private final LocationTypes.Source source;
        private final Map<Long, Map<Long, List<Spawn>>> cells;
        private final Map<Long, Integer> mapCounts;
        private final Map<Long, Npc> npcs;
        private final int spawns, heightless;
        private final List<String> issues;

        private Index(
                LocationTypes.State state,
                LocationTypes.Source source,
                Map<Long, Map<Long, List<Spawn>>> cells,
                Map<Long, Integer> mapCounts,
                Map<Long, Npc> npcs,
                int spawns,
                int heightless,
                List<String> issues) {
            this.state = Objects.requireNonNull(state);
            this.source = source;
            this.cells = cells;
            this.mapCounts = Map.copyOf(mapCounts);
            this.npcs = Map.copyOf(npcs);
            this.spawns = spawns;
            this.heightless = heightless;
            this.issues = List.copyOf(issues);
        }

        public LocationTypes.State state() {
            return state;
        }

        /** Empty when no source is admitted; otherwise original provider provenance. */
        public Optional<LocationTypes.Source> source() {
            return Optional.ofNullable(source);
        }

        public int spawns() {
            return spawns;
        }

        public int heightless() {
            return heightless;
        }

        public int count(long mapId) {
            return mapCounts.getOrDefault(mapId, 0);
        }

        public Optional<Npc> npc(long id) {
            return Optional.ofNullable(npcs.get(id));
        }

        public List<String> issues() {
            return issues;
        }

        /**
         * Points on {@code mapId} within horizontal {@code radius} yards of (x, y), nearest first
         * with a stable NPC/point tie-break. Points without recorded height are counted, never
         * placed at a guessed floor.
         */
        public Nearby near(long mapId, double x, double y, double radius, int limit) {
            LocationTypes.validId(mapId, true);
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !(radius > 0)
                    || radius > MAX_RADIUS
                    || limit < 0
                    || limit > MAX_NEARBY) {
                throw new IllegalArgumentException("Invalid nearby spawn query");
            }
            var map = cells.get(mapId);
            if (map == null) {
                return new Nearby(mapId, radius, List.of(), 0, 0, List.of());
            }
            var placed = new ArrayList<Spawn>();
            int withoutHeight = 0;
            long minX = cell(x - radius), maxX = cell(x + radius);
            long minY = cell(y - radius), maxY = cell(y + radius);
            double squared = radius * radius;
            for (long cx = minX; cx <= maxX; cx++) {
                for (long cy = minY; cy <= maxY; cy++) {
                    var points = map.get(key(cx, cy));
                    if (points == null) {
                        continue;
                    }
                    for (var spawn : points) {
                        double dx = spawn.x() - x, dy = spawn.y() - y;
                        if (dx * dx + dy * dy > squared) {
                            continue;
                        }
                        if (spawn.z() == null) {
                            withoutHeight++;
                        } else {
                            placed.add(spawn);
                        }
                    }
                }
            }
            placed.sort(
                    Comparator.comparingDouble((Spawn s) -> distance(s, x, y))
                            .thenComparingLong(Spawn::npcId)
                            .thenComparingLong(Spawn::point));
            int omitted = Math.max(0, placed.size() - limit);
            return new Nearby(
                    mapId,
                    radius,
                    placed.subList(0, placed.size() - omitted),
                    withoutHeight,
                    omitted,
                    List.of());
        }
    }

    /** No admitted world-yard source; the reason is shown instead of markers. */
    public static Index unavailable(LocationTypes.State state, String reason) {
        Objects.requireNonNull(reason);
        if (state == LocationTypes.State.READY || state == LocationTypes.State.PARTIAL) {
            throw new IllegalArgumentException("An unavailable index cannot be ready");
        }
        return new Index(state, null, Map.of(), Map.of(), Map.of(), 0, 0, List.of(reason));
    }

    /** Local reads of an already prepared Polestar snapshot; never acquires or prepares data. */
    public static Index polestar(
            PolestarRepository repository, PolestarTypes.Snapshot snapshot, Cancellation token) {
        Objects.requireNonNull(repository);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(token);
        var source = LocationSources.polestarSource(snapshot);
        var spawns =
                LocationSources.allRows(
                        repository, snapshot.id(), PolestarTypes.Table.SPAWN, MAX_SPAWNS, token);
        token.check();
        var npcs =
                LocationSources.allRows(
                        repository, snapshot.id(), PolestarTypes.Table.NPC, MAX_NPCS, token);
        token.check();
        var issues = new ArrayList<String>();
        for (var issue : spawns.issues()) {
            LocationAdapters.issue(issues, "Spawns: " + issue);
        }
        for (var issue : npcs.issues()) {
            LocationAdapters.issue(issues, "NPC names: " + issue);
        }
        return polestar(source, spawns.rows(), npcs.rows(), issues, token);
    }

    /** Reviewed Polestar {@code spawn}/{@code npc} wire shapes; testable without SQLite. */
    public static Index polestar(
            LocationTypes.Source source,
            List<Map<String, Object>> spawnRows,
            List<Map<String, Object>> npcRows,
            List<String> readIssues,
            Cancellation token) {
        Objects.requireNonNull(source);
        if (source.provider() != LocationTypes.Provider.POLESTAR) {
            throw new IllegalArgumentException("Provider profile mismatch");
        }
        var issues = new ArrayList<>(readIssues);
        var declared = new LinkedHashMap<String, Spawn>();
        var conflicted = new HashSet<String>();
        int rows = 0;
        for (var row : spawnRows) {
            if ((++rows & 4095) == 0) {
                token.check();
            }
            if (declared.size() >= MAX_SPAWNS) {
                LocationAdapters.issue(issues, "Spawn index bound reached; readable prefix kept.");
                break;
            }
            try {
                if (!"npc".equals(row.get("kind"))) {
                    if (!"object".equals(row.get("kind"))) {
                        LocationAdapters.issue(issues, "Some spawn rows have an unknown kind.");
                    }
                    continue;
                }
                long id = LocationAdapters.integer(row.get("id"), false);
                long map = LocationAdapters.integer(row.get("map"), true);
                long point = LocationAdapters.integer(row.get("n"), false);
                double x = number(row.get("x")), y = number(row.get("y"));
                Double z = null;
                if (row.get("z") != null) {
                    try {
                        z = number(row.get("z"));
                        if (!validHeight(z)) {
                            z = null;
                            LocationAdapters.issue(
                                    issues, "Some spawn heights are unsupported; not placed.");
                        }
                    } catch (IllegalArgumentException failure) {
                        LocationAdapters.issue(
                                issues, "Some spawn heights are unsupported; not placed.");
                    }
                }
                if (!coordinate(x) || !coordinate(y)) {
                    LocationAdapters.issue(issues, "Some spawn points lie outside the world grid.");
                    continue;
                }
                String record = "npc:" + id + ":" + map + ":" + point;
                var spawn =
                        new Spawn(
                                id,
                                map,
                                point,
                                x,
                                y,
                                z,
                                new LocationTypes.Evidence(source, "spawn", record, "x,y,z"));
                if (conflicted.contains(record)) {
                    continue;
                }
                var previous = declared.putIfAbsent(record, spawn);
                if (previous != null && !same(previous, spawn)) {
                    declared.remove(record);
                    conflicted.add(record);
                    LocationAdapters.issue(
                            issues, "Conflicting duplicate spawn points were omitted.");
                }
            } catch (IllegalArgumentException failure) {
                LocationAdapters.issue(issues, "Some spawn rows are unsupported.");
            }
        }
        var npcs = new HashMap<Long, Npc>();
        for (var row : npcRows) {
            if (npcs.size() >= MAX_NPCS) {
                LocationAdapters.issue(issues, "NPC name bound reached; some labels unknown.");
                break;
            }
            try {
                long id = LocationAdapters.integer(row.get("id"), false);
                var npc =
                        new Npc(
                                id,
                                text(row.get("name")),
                                text(row.get("subname")),
                                small(row.get("level_min")),
                                small(row.get("level_max")),
                                small(row.get("rank")),
                                new LocationTypes.Evidence(
                                        source, "npc", Long.toString(id), "name"));
                if (npcs.putIfAbsent(id, npc) != null) {
                    LocationAdapters.issue(issues, "Duplicate NPC records keep the first.");
                }
            } catch (IllegalArgumentException failure) {
                LocationAdapters.issue(issues, "Some NPC records are unsupported.");
            }
        }
        token.check();
        var cells = new HashMap<Long, Map<Long, List<Spawn>>>();
        var counts = new HashMap<Long, Integer>();
        int heightless = 0;
        for (var spawn : declared.values()) {
            cells.computeIfAbsent(spawn.mapId(), ignored -> new HashMap<>())
                    .computeIfAbsent(
                            key(cell(spawn.x()), cell(spawn.y())), ignored -> new ArrayList<>())
                    .add(spawn);
            counts.merge(spawn.mapId(), 1, Integer::sum);
            if (spawn.z() == null) {
                heightless++;
            }
        }
        var frozen = new HashMap<Long, Map<Long, List<Spawn>>>();
        cells.forEach(
                (map, grid) -> {
                    var copy = new HashMap<Long, List<Spawn>>();
                    grid.forEach((key, points) -> copy.put(key, List.copyOf(points)));
                    frozen.put(map, Map.copyOf(copy));
                });
        return new Index(
                LocationAdapters.state(issues),
                source,
                Map.copyOf(frozen),
                counts,
                npcs,
                declared.size(),
                heightless,
                issues);
    }

    private static boolean same(Spawn left, Spawn right) {
        return Double.compare(left.x(), right.x()) == 0
                && Double.compare(left.y(), right.y()) == 0
                && Objects.equals(left.z(), right.z());
    }

    private static double distance(Spawn spawn, double x, double y) {
        double dx = spawn.x() - x, dy = spawn.y() - y;
        return dx * dx + dy * dy;
    }

    private static boolean coordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= WORLD_EXTENT;
    }

    private static boolean validHeight(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_HEIGHT;
    }

    private static long cell(double value) {
        return (long) Math.floor(value / CELL);
    }

    private static long key(long x, long y) {
        return (x << 32) ^ (y & 0xffff_ffffL);
    }

    private static double number(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Invalid coordinate");
        }
        return number.doubleValue();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > 512) {
            throw new IllegalArgumentException("Invalid text");
        }
        return text;
    }

    private static Integer small(Object value) {
        if (value == null) {
            return null;
        }
        long result = LocationAdapters.integer(value, true);
        if (result > 1_000) {
            throw new IllegalArgumentException("Value out of range");
        }
        return (int) result;
    }
}
