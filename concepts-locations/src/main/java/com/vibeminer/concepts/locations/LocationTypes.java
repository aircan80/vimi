package com.vibeminer.concepts.locations;

import java.util.List;
import java.util.Objects;

/** Native source coordinates and explicit quest starters; no implicit map-ID conversion. */
public final class LocationTypes {
    private LocationTypes() {}

    public enum Provider {
        QUESTIE,
        POLESTAR,
        EG_LINK
    }

    public enum EntityKind {
        NPC,
        OBJECT,
        ITEM
    }

    public enum MapKind {
        AREA,
        UI_MAP,
        WORLD_MAP
    }

    public enum Units {
        PERCENT,
        NORMALIZED,
        WORLD_YARDS
    }

    public enum Origin {
        DECLARED_SPAWN,
        OBSERVED_INTERACTION
    }

    public enum State {
        READY,
        PARTIAL,
        UNKNOWN,
        UNSUPPORTED
    }

    public record Entity(EntityKind kind, long id) {
        public Entity {
            Objects.requireNonNull(kind);
            validId(id, false);
        }
    }

    public record MapRef(MapKind kind, long id) {
        public MapRef {
            Objects.requireNonNull(kind);
            validId(id, kind == MapKind.WORLD_MAP);
        }
    }

    public record Source(
            Provider provider,
            String snapshot,
            String product,
            String build,
            String locale,
            String license) {
        public Source {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(snapshot);
            if (snapshot.isBlank() || snapshot.length() > 512) {
                throw new IllegalArgumentException("Invalid source identity");
            }
        }
    }

    public record Evidence(Source source, String dataset, String record, String field) {
        public Evidence {
            Objects.requireNonNull(source);
            Objects.requireNonNull(dataset);
            Objects.requireNonNull(record);
            Objects.requireNonNull(field);
        }
    }

    public record Coordinate(MapRef map, Units units, double x, double y, Double z) {
        public Coordinate {
            Objects.requireNonNull(map);
            Objects.requireNonNull(units);
            if (!Double.isFinite(x) || !Double.isFinite(y) || z != null && !Double.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite coordinate");
            }
            if (units == Units.PERCENT && (x < 0 || x > 100 || y < 0 || y > 100)
                    || units == Units.NORMALIZED && (x < 0 || x > 1 || y < 0 || y > 1)
                    || units == Units.WORLD_YARDS && map.kind() != MapKind.WORLD_MAP
                    || units != Units.WORLD_YARDS && map.kind() == MapKind.WORLD_MAP
                    || units != Units.WORLD_YARDS && z != null) {
                throw new IllegalArgumentException("Coordinate space mismatch");
            }
        }
    }

    /** Coordinate alternatives describe ONE observation; do not render each alternative twice. */
    public record Location(
            Entity entity,
            Origin origin,
            List<Coordinate> coordinates,
            Long phase,
            Evidence evidence) {
        public Location {
            Objects.requireNonNull(entity);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(evidence);
            coordinates = List.copyOf(coordinates);
            if (coordinates.isEmpty()) {
                throw new IllegalArgumentException("Coordinate required");
            }
            if (phase != null) {
                validId(phase, true);
            }
        }
    }

    public record Starter(long questId, Entity entity, Evidence evidence) {
        public Starter {
            validId(questId, false);
            Objects.requireNonNull(entity);
            Objects.requireNonNull(evidence);
        }
    }

    /** Signed edge order is source data: left/right are WORLD Y, top/bottom are WORLD X. */
    public record WorldFrame(
            long uiMapId,
            long worldMapId,
            double left,
            double right,
            double top,
            double bottom,
            Evidence evidence) {
        public WorldFrame {
            validId(uiMapId, false);
            validId(worldMapId, true);
            Objects.requireNonNull(evidence);
            if (!Double.isFinite(left)
                    || !Double.isFinite(right)
                    || !Double.isFinite(top)
                    || !Double.isFinite(bottom)
                    || left == right
                    || top == bottom
                    || !Double.isFinite(right - left)
                    || !Double.isFinite(bottom - top)) {
                throw new IllegalArgumentException("Invalid world frame");
            }
        }
    }

    public record Locations(
            State state, Entity entity, List<Location> locations, List<String> issues) {
        public Locations {
            locations = List.copyOf(locations);
            issues = List.copyOf(issues);
        }
    }

    public record Starters(State state, long questId, List<Starter> starters, List<String> issues) {
        public Starters {
            starters = List.copyOf(starters);
            issues = List.copyOf(issues);
        }
    }

    public record Frames(State state, List<WorldFrame> frames, List<String> issues) {
        public Frames {
            frames = List.copyOf(frames);
            issues = List.copyOf(issues);
        }
    }

    public record Pickup(Entity entity, List<Starter> evidence, Locations locations) {
        public Pickup {
            evidence = List.copyOf(evidence);
        }
    }

    public record Pickups(State state, long questId, List<Pickup> pickups, List<String> issues) {
        public Pickups {
            pickups = List.copyOf(pickups);
            issues = List.copyOf(issues);
        }
    }

    static void validId(long id, boolean zero) {
        if (id < (zero ? 0 : 1) || id > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid identifier");
        }
    }
}
