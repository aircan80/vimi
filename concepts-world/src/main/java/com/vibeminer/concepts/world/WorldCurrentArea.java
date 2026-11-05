package com.vibeminer.concepts.world;

import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure lookup of recorded terrain-area rectangles and exact catalog parent chains; no WMO volumes.
 */
public final class WorldCurrentArea {
    public static final int MAX_AREAS = 100000, MAX_DEPTH = 64, MAX_TILES = 12;

    private WorldCurrentArea() {}

    public enum State {
        KNOWN,
        UNAVAILABLE,
        AMBIGUOUS,
        HIERARCHY_UNAVAILABLE
    }

    public enum Reason {
        NONE,
        NO_LOADED_METADATA,
        NO_COVERAGE,
        OVERLAP,
        UNKNOWN_AREA,
        DUPLICATE_ID,
        UNKNOWN_PARENT,
        CYCLE,
        MAP_MISMATCH,
        DEPTH_LIMIT
    }

    public static final class Index {
        private final Map<Long, WorldService.Area> areas;
        private final Set<Long> duplicates;

        private Index(Map<Long, WorldService.Area> areas, Set<Long> duplicates) {
            this.areas = Map.copyOf(areas);
            this.duplicates = Set.copyOf(duplicates);
        }

        public static Index of(List<WorldService.Area> source) {
            Objects.requireNonNull(source);
            if (source.size() > MAX_AREAS) {
                throw new IllegalArgumentException("Area catalog count exceeds bound");
            }
            var areas = new HashMap<Long, WorldService.Area>();
            var duplicates = new HashSet<Long>();
            for (var area : source) {
                Objects.requireNonNull(area);
                if (areas.putIfAbsent(area.id(), area) != null) {
                    duplicates.add(area.id());
                }
            }
            return new Index(areas, duplicates);
        }
    }

    public record Result(State state, Reason reason, long areaId, List<WorldService.Area> chain) {
        public Result {
            Objects.requireNonNull(state);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(chain);
            if (chain.size() > MAX_DEPTH) {
                throw new IllegalArgumentException("Area chain exceeds bounds");
            }
            chain = List.copyOf(chain);
        }

        public String label() {
            if (state != State.KNOWN) {
                return "Terrain area unavailable";
            }
            return "Terrain area: "
                    + chain.stream()
                            .map(
                                    area -> {
                                        String name = area.toString();
                                        return name.length() > 256
                                                ? name.substring(0, 256) + "…"
                                                : name;
                                    })
                            .collect(java.util.stream.Collectors.joining(" · "));
        }
    }

    public static Result locate(
            long mapId,
            double nativeX,
            double nativeY,
            Collection<WorldService.LoadedTile> loaded,
            Index index) {
        Objects.requireNonNull(loaded);
        var metadata = new ArrayList<WorldFormats.AreaMetadata>(loaded.size());
        for (var tile : loaded) {
            metadata.add(tile.areas());
        }
        return locateIn(mapId, nativeX, nativeY, metadata, index);
    }

    /**
     * The same lookup over retained per-tile area metadata; null entries are tiles without recorded
     * metadata.
     */
    public static Result locateIn(
            long mapId,
            double nativeX,
            double nativeY,
            Collection<WorldFormats.AreaMetadata> loaded,
            Index index) {
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(index);
        if (!Double.isFinite(nativeX)
                || !Double.isFinite(nativeY)
                || mapId < 0
                || loaded.size() > MAX_TILES) {
            throw new IllegalArgumentException("Invalid terrain-area query bounds");
        }
        WorldFormats.AreaChunk selected = null;
        boolean metadata = false;
        for (var areas : loaded) {
            if (areas == null) {
                continue;
            }
            metadata = true;
            for (var chunk : areas.chunks()) {
                // The same float endpoint arithmetic as the original 17-row geometry grid.
                float minX = (float) (chunk.originX() - 16 * (WorldFormats.TILE_SIZE / 256));
                float minY = (float) (chunk.originY() - 8 * (WorldFormats.TILE_SIZE / 128));
                if (nativeX > minX
                        && nativeX <= chunk.originX()
                        && nativeY > minY
                        && nativeY <= chunk.originY()) {
                    if (selected != null) {
                        return failure(State.AMBIGUOUS, Reason.OVERLAP, 0);
                    }
                    selected = chunk;
                }
            }
        }
        if (selected == null) {
            return failure(
                    State.UNAVAILABLE,
                    metadata ? Reason.NO_COVERAGE : Reason.NO_LOADED_METADATA,
                    0);
        }
        return resolve(mapId, selected.areaId(), index);
    }

    /**
     * Resolves an already observed terrain area ID without retaining geometry or area metadata. The
     * caller determines spatial coverage inside its checked source borrow; this lookup alone makes
     * no claim about the camera's position or current source validity.
     */
    public static Result resolve(long mapId, long recordedAreaId, Index index) {
        Objects.requireNonNull(index);
        if (mapId < 0 || recordedAreaId < 0) {
            throw new IllegalArgumentException("Invalid recorded area identity");
        }
        long id = recordedAreaId, at = id;
        var chain = new ArrayList<WorldService.Area>();
        var seen = new HashSet<Long>();
        while (true) {
            if (!seen.add(at)) {
                return failure(State.HIERARCHY_UNAVAILABLE, Reason.CYCLE, id);
            }
            if (chain.size() == MAX_DEPTH) {
                return failure(State.HIERARCHY_UNAVAILABLE, Reason.DEPTH_LIMIT, id);
            }
            if (index.duplicates.contains(at)) {
                return failure(State.HIERARCHY_UNAVAILABLE, Reason.DUPLICATE_ID, id);
            }
            var area = index.areas.get(at);
            if (area == null) {
                return failure(
                        State.HIERARCHY_UNAVAILABLE,
                        at == id ? Reason.UNKNOWN_AREA : Reason.UNKNOWN_PARENT,
                        id);
            }
            if (area.mapId() != mapId) {
                return failure(State.HIERARCHY_UNAVAILABLE, Reason.MAP_MISMATCH, id);
            }
            if (!area.hierarchyKnown() || area.parentId() < 0) {
                return failure(State.HIERARCHY_UNAVAILABLE, Reason.UNKNOWN_PARENT, id);
            }
            chain.add(area);
            if (area.parentId() == 0) {
                break;
            }
            at = area.parentId();
        }
        Collections.reverse(chain);
        return new Result(State.KNOWN, Reason.NONE, id, chain);
    }

    private static Result failure(State state, Reason reason, long id) {
        return new Result(state, reason, id, List.of());
    }
}
