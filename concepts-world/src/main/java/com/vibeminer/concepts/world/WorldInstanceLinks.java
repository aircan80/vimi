package com.vibeminer.concepts.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Recorded links from dungeon and raid maps to the open world: the instance's Map.AreaTableID and
 * its Map corpse position (CorpseMapID with Corpse x/y), which the client records at the instance's
 * entrance on the parent map. Native World coordinates are the Map corpse coordinates unchanged.
 * Nothing is inferred: an instance whose corpse record is unset, zero, outside the map grid or
 * points at itself has no entrance, and says why.
 */
public final class WorldInstanceLinks {
    /** Half the 64-tile map grid: the largest native coordinate a map position can take. */
    public static final double MAX_COORDINATE = 51200.0 / 3;

    public static final int MAX_LINKS = 10000, MAX_ISSUES = 64;

    public enum Kind {
        DUNGEON,
        RAID
    }

    /** A recorded entrance position on another map, in native World coordinates. */
    public record Entrance(long mapId, double x, double y) {
        public Entrance {
            if (mapId < 0
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || Math.abs(x) > MAX_COORDINATE
                    || Math.abs(y) > MAX_COORDINATE) {
                throw new IllegalArgumentException("Invalid recorded entrance");
            }
        }
    }

    /**
     * One instance map. {@code areaId} is the recorded Map.AreaTableID, or null when unset. {@code
     * entrance} is null exactly when {@code entranceIssue} says why there is none.
     */
    public record Link(
            long mapId,
            String name,
            Kind kind,
            Long areaId,
            Entrance entrance,
            String entranceIssue) {
        public Link {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            if ((entrance == null) == (entranceIssue == null)) {
                throw new IllegalArgumentException("An entrance or its absence reason is required");
            }
            if (entrance != null && entrance.mapId() == mapId) {
                throw new IllegalArgumentException("An instance cannot be its own entrance map");
            }
        }

        @Override
        public String toString() {
            return name + " · " + (kind == Kind.RAID ? "raid" : "dungeon");
        }
    }

    private final List<Link> links;
    private final List<String> issues;

    public WorldInstanceLinks(List<Link> links, List<String> issues) {
        if (links.size() > MAX_LINKS || issues.size() > MAX_ISSUES) {
            throw new IllegalArgumentException("Instance link bounds");
        }
        this.links = List.copyOf(links);
        this.issues = List.copyOf(issues);
    }

    public static WorldInstanceLinks unavailable(String issue) {
        return new WorldInstanceLinks(List.of(), List.of(Objects.requireNonNull(issue)));
    }

    public List<Link> links() {
        return links;
    }

    public List<String> issues() {
        return issues;
    }

    public Optional<Link> link(long mapId) {
        return links.stream().filter(l -> l.mapId() == mapId).findFirst();
    }

    /** Instances whose recorded entrance lies on this map, nearest to (x, y) first. */
    public List<Link> entrancesOn(long mapId, double x, double y) {
        var result = new ArrayList<Link>();
        for (var link : links) {
            if (link.entrance() != null && link.entrance().mapId() == mapId) {
                result.add(link);
            }
        }
        result.sort(
                Comparator.comparingDouble((Link l) -> distance(l, x, y))
                        .thenComparingLong(Link::mapId));
        return result;
    }

    /** Horizontal distance from (x, y) to the link's recorded entrance. */
    public static double distance(Link link, double x, double y) {
        var entrance = Objects.requireNonNull(link.entrance());
        return Math.hypot(entrance.x() - x, entrance.y() - y);
    }

    /**
     * Qualifies one Map row. Returns null when the row is not a dungeon (InstanceType 1) or raid
     * (InstanceType 2). Each field is the recorded value, or null when it was unreadable.
     */
    public static Link qualify(
            long mapId,
            String name,
            Long instanceType,
            Long areaId,
            Long corpseMapId,
            Double corpseX,
            Double corpseY) {
        if (instanceType == null || instanceType != 1 && instanceType != 2) {
            return null;
        }
        String label = name == null || name.isBlank() ? "Map #" + mapId : name;
        var kind = instanceType == 1 ? Kind.DUNGEON : Kind.RAID;
        Long area = areaId == null || areaId <= 0 ? null : areaId;
        String issue = null;
        if (corpseMapId == null) {
            issue = "the recorded entrance map (Map.CorpseMapID) is unreadable";
        } else if (corpseMapId < 0) {
            issue = "no entrance is recorded (Map.CorpseMapID is unset)";
        } else if (corpseMapId == mapId) {
            issue = "the recorded entrance map is the instance itself";
        } else if (corpseX == null || corpseY == null) {
            issue = "the recorded entrance position (Map.Corpse) is unreadable";
        } else if (!Double.isFinite(corpseX) || !Double.isFinite(corpseY)) {
            issue = "the recorded entrance position is not a number";
        } else if (corpseX == 0 && corpseY == 0) {
            issue = "no entrance position is recorded (Map.Corpse is 0, 0)";
        } else if (Math.abs(corpseX) > MAX_COORDINATE || Math.abs(corpseY) > MAX_COORDINATE) {
            issue = "the recorded entrance position lies outside the map grid";
        }
        return new Link(
                mapId,
                label,
                kind,
                area,
                issue == null ? new Entrance(corpseMapId, corpseX, corpseY) : null,
                issue);
    }
}
