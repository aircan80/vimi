package com.vibeminer.concepts.locations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reviewed provider wire shapes; malformed points never discard neighboring readable points. */
public final class LocationAdapters {
    static final int MAX_POINTS = 4096, MAX_STARTERS = 256;

    private LocationAdapters() {}

    public static LocationTypes.Locations questieLocations(
            LocationTypes.Entity entity, Map<String, Object> fields, LocationTypes.Source source) {
        require(source, LocationTypes.Provider.QUESTIE);
        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
            return item(entity);
        }
        if (fields == null || fields.get("spawns") == null) {
            return unknown(entity, "No recorded Questie spawn field.");
        }
        var issues = new ArrayList<String>();
        var result = new ArrayList<LocationTypes.Location>();
        try {
            for (var area : entries(fields.get("spawns"), 1024)) {
                try {
                    long areaId = id(area.getKey(), false);
                    for (var point : entries(area.getValue(), MAX_POINTS)) {
                        if (result.size() >= MAX_POINTS) {
                            issue(
                                    issues,
                                    "Questie location bound reached; remaining points are not assessed.");
                            break;
                        }
                        try {
                            id(point.getKey(), false);
                            Object tuple = point.getValue();
                            double x = decimal(slot(tuple, 1)), y = decimal(slot(tuple, 2));
                            Long phase = null;
                            try {
                                phase =
                                        slot(tuple, 3) == null
                                                ? null
                                                : integer(slot(tuple, 3), true);
                            } catch (IllegalArgumentException failure) {
                                issue(
                                        issues,
                                        "Some Questie phases are unsupported; readable coordinates are retained.");
                            }
                            var coordinate =
                                    new LocationTypes.Coordinate(
                                            new LocationTypes.MapRef(
                                                    LocationTypes.MapKind.AREA, areaId),
                                            LocationTypes.Units.PERCENT,
                                            x,
                                            y,
                                            null);
                            result.add(
                                    new LocationTypes.Location(
                                            entity,
                                            LocationTypes.Origin.DECLARED_SPAWN,
                                            List.of(coordinate),
                                            phase,
                                            evidence(
                                                    source,
                                                    entity,
                                                    "spawns["
                                                            + areaId
                                                            + "]["
                                                            + point.getKey()
                                                            + "]")));
                        } catch (IllegalArgumentException failure) {
                            issue(issues, "Some Questie coordinates/phase values are unsupported.");
                        }
                    }
                } catch (IllegalArgumentException failure) {
                    issue(issues, "Some Questie area groups are unsupported.");
                }
            }
        } catch (IllegalArgumentException failure) {
            issue(issues, "Questie spawn container is unsupported or exceeds bounds.");
        }
        return new LocationTypes.Locations(state(issues), entity, result, issues);
    }

    public static LocationTypes.Starters questieStarters(
            long questId, Map<String, Object> fields, LocationTypes.Source source) {
        require(source, LocationTypes.Provider.QUESTIE);
        LocationTypes.validId(questId, false);
        if (fields == null || fields.get("startedBy") == null) {
            return new LocationTypes.Starters(
                    LocationTypes.State.UNKNOWN,
                    questId,
                    List.of(),
                    List.of("No recorded Questie startedBy field."));
        }
        var issues = new ArrayList<String>();
        var result = new ArrayList<LocationTypes.Starter>();
        for (int group = 1; group <= 3; group++) {
            try {
                var values = slot(fields.get("startedBy"), group);
                if (values == null) {
                    continue;
                }
                var kind =
                        switch (group) {
                            case 1 -> LocationTypes.EntityKind.NPC;
                            case 2 -> LocationTypes.EntityKind.OBJECT;
                            default -> LocationTypes.EntityKind.ITEM;
                        };
                for (var entry : entries(values, MAX_STARTERS)) {
                    try {
                        if (result.size() >= MAX_STARTERS) {
                            issue(issues, "Questie starter bound reached.");
                            break;
                        }
                        result.add(
                                new LocationTypes.Starter(
                                        questId,
                                        new LocationTypes.Entity(
                                                kind, integer(entry.getValue(), false)),
                                        new LocationTypes.Evidence(
                                                source,
                                                "QUEST",
                                                Long.toString(questId),
                                                "startedBy["
                                                        + group
                                                        + "]["
                                                        + entry.getKey()
                                                        + "]")));
                    } catch (IllegalArgumentException failure) {
                        issue(issues, "Some Questie starter IDs are unsupported.");
                    }
                }
            } catch (IllegalArgumentException failure) {
                issue(issues, "Some Questie starter groups are unsupported.");
            }
        }
        return new LocationTypes.Starters(state(issues), questId, result, issues);
    }

    public static LocationTypes.Locations polestarLocations(
            LocationTypes.Entity entity,
            List<Map<String, Object>> rows,
            LocationTypes.Source source) {
        require(source, LocationTypes.Provider.POLESTAR);
        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
            return item(entity);
        }
        var issues = new ArrayList<String>();
        var result = new ArrayList<LocationTypes.Location>();
        for (var row : rows) {
            if (result.size() >= MAX_POINTS) {
                issue(issues, "Polestar location bound reached.");
                break;
            }
            try {
                var recorded = entity(row.get("kind"), row.get("id"));
                if (!recorded.equals(entity)) {
                    continue;
                }
                long map = integer(row.get("map"), true), n = integer(row.get("n"), false);
                Double height = null;
                try {
                    height = row.get("z") == null ? null : decimal(row.get("z"));
                } catch (IllegalArgumentException failure) {
                    issue(issues, "Some Polestar heights are unsupported; world x/y are retained.");
                }
                var coordinate =
                        new LocationTypes.Coordinate(
                                new LocationTypes.MapRef(LocationTypes.MapKind.WORLD_MAP, map),
                                LocationTypes.Units.WORLD_YARDS,
                                decimal(row.get("x")),
                                decimal(row.get("y")),
                                height);
                result.add(
                        new LocationTypes.Location(
                                entity,
                                LocationTypes.Origin.DECLARED_SPAWN,
                                List.of(coordinate),
                                null,
                                new LocationTypes.Evidence(
                                        source,
                                        "spawn",
                                        entity.kind().name().toLowerCase(java.util.Locale.ROOT)
                                                + ":"
                                                + entity.id()
                                                + ":"
                                                + map
                                                + ":"
                                                + n,
                                        "x,y,z")));
            } catch (IllegalArgumentException failure) {
                issue(issues, "Some Polestar spawn rows are unsupported.");
            }
        }
        return new LocationTypes.Locations(state(issues), entity, result, issues);
    }

    public static LocationTypes.Starters polestarStarters(
            long questId, List<Map<String, Object>> rows, LocationTypes.Source source) {
        require(source, LocationTypes.Provider.POLESTAR);
        LocationTypes.validId(questId, false);
        var result = new ArrayList<LocationTypes.Starter>();
        var issues = new ArrayList<String>();
        for (var row : rows) {
            try {
                if (integer(row.get("quest"), false) != questId) {
                    continue;
                }
                if (result.size() >= MAX_STARTERS) {
                    issue(issues, "Polestar starter bound reached.");
                    break;
                }
                var entity = entity(row.get("kind"), row.get("id"));
                result.add(
                        new LocationTypes.Starter(
                                questId,
                                entity,
                                new LocationTypes.Evidence(
                                        source,
                                        "quest_start",
                                        questId + ":" + entity.kind() + ":" + entity.id(),
                                        "kind,id")));
            } catch (IllegalArgumentException failure) {
                issue(issues, "Some Polestar starter rows are unsupported.");
            }
        }
        return new LocationTypes.Starters(state(issues), questId, result, issues);
    }

    public static LocationTypes.Frames polestarFrames(
            List<Map<String, Object>> rows, LocationTypes.Source source) {
        require(source, LocationTypes.Provider.POLESTAR);
        var result = new ArrayList<LocationTypes.WorldFrame>();
        var issues = new ArrayList<String>();
        for (var row : rows) {
            if (result.size() >= 8192) {
                issue(issues, "Polestar frame bound reached.");
                break;
            }
            try {
                long uiMap = integer(row.get("ui_map"), false);
                result.add(
                        new LocationTypes.WorldFrame(
                                uiMap,
                                integer(row.get("map"), true),
                                decimal(row.get("left")),
                                decimal(row.get("right")),
                                decimal(row.get("top")),
                                decimal(row.get("bottom")),
                                new LocationTypes.Evidence(
                                        source,
                                        "ui_map",
                                        Long.toString(uiMap),
                                        "map,left,right,top,bottom")));
            } catch (IllegalArgumentException failure) {
                issue(issues, "Some Polestar map frames are unsupported.");
            }
        }
        return new LocationTypes.Frames(state(issues), result, issues);
    }

    public static LocationTypes.Locations egLocations(
            LocationTypes.Entity entity, Map<String, Object> fields, LocationTypes.Source source) {
        require(source, LocationTypes.Provider.EG_LINK);
        if (entity.kind() == LocationTypes.EntityKind.ITEM) {
            return item(entity);
        }
        if (fields == null || fields.get("positions") == null) {
            return unknown(entity, "No recorded EG interaction positions.");
        }
        var result = new ArrayList<LocationTypes.Location>();
        var issues = new ArrayList<String>();
        if (!(fields.get("positions") instanceof List<?>)) {
            return new LocationTypes.Locations(
                    LocationTypes.State.PARTIAL,
                    entity,
                    List.of(),
                    List.of("EG interaction positions must use the reviewed array shape."));
        }
        try {
            for (var position : entries(fields.get("positions"), MAX_POINTS)) {
                try {
                    if (!(position.getValue() instanceof Map<?, ?> point)) {
                        throw new IllegalArgumentException("Position must be object");
                    }
                    var alternatives = new ArrayList<LocationTypes.Coordinate>();
                    if (point.containsKey("uiMap")
                            && point.containsKey("mapX")
                            && point.containsKey("mapY")) {
                        try {
                            long uiMap = integer(point.get("uiMap"), true);
                            if (uiMap > 0) {
                                alternatives.add(
                                        new LocationTypes.Coordinate(
                                                new LocationTypes.MapRef(
                                                        LocationTypes.MapKind.UI_MAP, uiMap),
                                                LocationTypes.Units.NORMALIZED,
                                                decimal(point.get("mapX")),
                                                decimal(point.get("mapY")),
                                                null));
                            }
                        } catch (IllegalArgumentException failure) {
                            issue(issues, "Some EG normalized map coordinates are unsupported.");
                        }
                    }
                    if (point.containsKey("instance")
                            && point.containsKey("worldX")
                            && point.containsKey("worldY")) {
                        try {
                            alternatives.add(
                                    new LocationTypes.Coordinate(
                                            new LocationTypes.MapRef(
                                                    LocationTypes.MapKind.WORLD_MAP,
                                                    integer(point.get("instance"), true)),
                                            LocationTypes.Units.WORLD_YARDS,
                                            decimal(point.get("worldX")),
                                            decimal(point.get("worldY")),
                                            null));
                        } catch (IllegalArgumentException failure) {
                            issue(issues, "Some EG world coordinate alternatives are unsupported.");
                        }
                    }
                    if (alternatives.isEmpty()) {
                        issue(issues, "EG position has no supported coordinate alternative.");
                        continue;
                    }
                    result.add(
                            new LocationTypes.Location(
                                    entity,
                                    LocationTypes.Origin.OBSERVED_INTERACTION,
                                    alternatives,
                                    null,
                                    evidence(
                                            source,
                                            entity,
                                            "positions[" + position.getKey() + "]")));
                } catch (IllegalArgumentException failure) {
                    issue(issues, "Some EG interaction positions are unsupported.");
                }
            }
        } catch (IllegalArgumentException failure) {
            issue(issues, "EG position container is unsupported or exceeds bounds.");
        }
        return new LocationTypes.Locations(state(issues), entity, result, issues);
    }

    /** Only offers establishes a pickup relationship; takes/approached/objectives are ignored. */
    public static List<LocationTypes.Starter> egOffers(
            LocationTypes.Entity entity,
            Map<String, Object> fields,
            LocationTypes.Source source,
            List<String> issues) {
        require(source, LocationTypes.Provider.EG_LINK);
        if (entity.kind() == LocationTypes.EntityKind.ITEM
                || fields == null
                || fields.get("offers") == null) {
            return List.of();
        }
        var result = new ArrayList<LocationTypes.Starter>();
        if (!(fields.get("offers") instanceof Map<?, ?>)) {
            issue(issues, "EG offers must use the reviewed quest-ID keyed object shape.");
            return List.of();
        }
        try {
            for (var entry : entries(fields.get("offers"), MAX_STARTERS)) {
                try {
                    if (!(entry.getValue() instanceof Map<?, ?>)) {
                        throw new IllegalArgumentException("Invalid offer object");
                    }
                    long quest = id(entry.getKey(), false);
                    result.add(
                            new LocationTypes.Starter(
                                    quest,
                                    entity,
                                    evidence(source, entity, "offers[" + quest + "]")));
                } catch (IllegalArgumentException failure) {
                    issue(issues, "Some EG offered quest IDs are unsupported.");
                }
            }
        } catch (IllegalArgumentException failure) {
            issue(issues, "EG offers container is unsupported or exceeds bounds.");
        }
        return List.copyOf(result);
    }

    static LocationTypes.Entity entity(Object kind, Object id) {
        var type =
                switch (String.valueOf(kind)) {
                    case "npc" -> LocationTypes.EntityKind.NPC;
                    case "object" -> LocationTypes.EntityKind.OBJECT;
                    case "item" -> LocationTypes.EntityKind.ITEM;
                    default -> throw new IllegalArgumentException("Unknown entity kind");
                };
        return new LocationTypes.Entity(type, integer(id, false));
    }

    static Object slot(Object value, int index) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return index <= list.size() ? list.get(index - 1) : null;
        }
        if (value instanceof Map<?, ?> map) {
            return map.get(Integer.toString(index));
        }
        throw new IllegalArgumentException("Expected positional table");
    }

    static List<Map.Entry<String, Object>> entries(Object value, int bound) {
        var result = new ArrayList<Map.Entry<String, Object>>();
        boolean truncated = false;
        if (value instanceof List<?> list) {
            truncated = list.size() > bound;
            for (int i = 0; i < Math.min(list.size(), bound); i++) {
                if (list.get(i) != null) {
                    result.add(Map.entry(Integer.toString(i + 1), list.get(i)));
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            int count = 0;
            for (var entry : map.entrySet()) {
                if (count++ >= bound) {
                    truncated = true;
                    break;
                }
                if (entry.getValue() == null) {
                    continue;
                }
                if (!(entry.getKey() instanceof String key)) {
                    result.add(Map.entry("__unsupported_key__", Boolean.TRUE));
                    continue;
                }
                result.add(Map.entry(key, entry.getValue()));
            }
        } else {
            throw new IllegalArgumentException("Expected table");
        }
        if (truncated) {
            result.add(Map.entry("__truncated__", Boolean.TRUE));
        }
        return result;
    }

    static long integer(Object value, boolean zero) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Expected numeric ID");
        }
        try {
            long result = new BigDecimal(number.toString()).longValueExact();
            LocationTypes.validId(result, zero);
            return result;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid numeric ID", failure);
        }
    }

    private static long id(String value, boolean zero) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException("Invalid ID key");
        }
        long result = Long.parseLong(value);
        LocationTypes.validId(result, zero);
        return result;
    }

    private static double decimal(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("Invalid coordinate");
        }
        return number.doubleValue();
    }

    private static LocationTypes.Evidence evidence(
            LocationTypes.Source source, LocationTypes.Entity entity, String field) {
        String dataset =
                source.provider() == LocationTypes.Provider.EG_LINK
                        ? "npcs/"
                                + (entity.kind() == LocationTypes.EntityKind.NPC
                                        ? "npcs"
                                        : "objects")
                        : entity.kind().name();
        return new LocationTypes.Evidence(source, dataset, Long.toString(entity.id()), field);
    }

    private static void require(LocationTypes.Source source, LocationTypes.Provider provider) {
        if (source.provider() != provider) {
            throw new IllegalArgumentException("Provider profile mismatch");
        }
    }

    static void issue(List<String> issues, String value) {
        if (issues.size() < 32 && !issues.contains(value)) {
            issues.add(value);
        }
    }

    static LocationTypes.State state(List<String> issues) {
        return issues.isEmpty() ? LocationTypes.State.READY : LocationTypes.State.PARTIAL;
    }

    static LocationTypes.Locations item(LocationTypes.Entity entity) {
        return new LocationTypes.Locations(
                LocationTypes.State.UNSUPPORTED,
                entity,
                List.of(),
                List.of(
                        "An item starter has no intrinsic map position; acquisition locations are a separate relationship."));
    }

    static LocationTypes.Locations unknown(LocationTypes.Entity entity, String message) {
        return new LocationTypes.Locations(
                LocationTypes.State.UNKNOWN, entity, List.of(), List.of(message));
    }
}
