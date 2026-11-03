package com.vibeminer.concepts.locations;

import java.util.List;

/** Explicit source-frame projection only. AREA IDs never silently become UiMap IDs. */
public final class LocationProjection {
    private LocationProjection() {}

    public record Result(
            LocationTypes.State state,
            LocationTypes.Coordinate coordinate,
            List<LocationTypes.Evidence> evidence,
            String message) {
        public Result {
            evidence = List.copyOf(evidence);
        }
    }

    public static Result directUiMap(LocationTypes.Location location) {
        var coordinates =
                location.coordinates().stream()
                        .filter(
                                c ->
                                        c.map().kind() == LocationTypes.MapKind.UI_MAP
                                                && c.units() == LocationTypes.Units.NORMALIZED)
                        .toList();
        if (coordinates.size() != 1) {
            return new Result(
                    LocationTypes.State.UNSUPPORTED,
                    null,
                    List.of(location.evidence()),
                    "A reviewed AreaID/world-to-UiMap relationship is required.");
        }
        return new Result(
                LocationTypes.State.READY,
                coordinates.getFirst(),
                List.of(location.evidence()),
                null);
    }

    public static Result throughFrame(
            LocationTypes.Location location, LocationTypes.WorldFrame frame) {
        if (!location.evidence().source().equals(frame.evidence().source())) {
            return new Result(
                    LocationTypes.State.UNSUPPORTED,
                    null,
                    List.of(location.evidence(), frame.evidence()),
                    "Cross-snapshot frame compatibility has not been established.");
        }
        var coordinates =
                location.coordinates().stream()
                        .filter(
                                c ->
                                        c.map().kind() == LocationTypes.MapKind.WORLD_MAP
                                                && c.map().id() == frame.worldMapId()
                                                && c.units() == LocationTypes.Units.WORLD_YARDS)
                        .toList();
        if (coordinates.size() != 1) {
            return new Result(
                    LocationTypes.State.UNSUPPORTED,
                    null,
                    List.of(location.evidence(), frame.evidence()),
                    "No unique world point matches this frame's Map ID.");
        }
        var world = coordinates.getFirst();
        double x = (world.y() - frame.left()) / (frame.right() - frame.left());
        double y = (world.x() - frame.top()) / (frame.bottom() - frame.top());
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1) {
            return new Result(
                    LocationTypes.State.UNKNOWN,
                    null,
                    List.of(location.evidence(), frame.evidence()),
                    "World point is outside this recorded map frame.");
        }
        return new Result(
                LocationTypes.State.READY,
                new LocationTypes.Coordinate(
                        new LocationTypes.MapRef(LocationTypes.MapKind.UI_MAP, frame.uiMapId()),
                        LocationTypes.Units.NORMALIZED,
                        x,
                        y,
                        null),
                List.of(location.evidence(), frame.evidence()),
                null);
    }
}
