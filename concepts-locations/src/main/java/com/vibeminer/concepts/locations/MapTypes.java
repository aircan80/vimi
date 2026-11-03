package com.vibeminer.concepts.locations;

import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;

/** Selected-build map facts; base artwork does not imply current character phase or visibility. */
public final class MapTypes {
    private MapTypes() {}

    public enum State {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED
    }

    public enum Basis {
        RECORDED_UI_MAP,
        AREA_ASSIGNMENT,
        WORLD_ASSIGNMENT
    }

    public record TableSource(
            String table, long rowId, String column, String value, String contentKey) {}

    public record MapInfo(
            long id, String name, Long parentId, Integer type, Long visibilityConditionId) {}

    public record Candidate(
            MapInfo map,
            Double x,
            Double y,
            Long assignmentId,
            Basis basis,
            List<TableSource> sources,
            String message) {
        public Candidate {
            sources = List.copyOf(sources);
        }
    }

    public record Resolution(
            State state,
            BuildRef build,
            String locale,
            LocationTypes.Location location,
            List<Candidate> candidates,
            List<String> issues,
            String message) {
        public Resolution {
            candidates = List.copyOf(candidates);
            issues = List.copyOf(issues);
        }
    }

    public record ArtLink(long rowId, long artId, long phaseId) {}

    public record Layer(
            long rowId, int index, int width, int height, int tileWidth, int tileHeight) {}

    /**
     * Visible width/height crop edge tiles; full texture placement stays at
     * col*tileWidth,row*tileHeight.
     */
    public record Tile(
            long rowId,
            long fileDataId,
            int row,
            int column,
            int x,
            int y,
            int visibleWidth,
            int visibleHeight) {}

    public record Artwork(
            State state,
            BuildRef build,
            String locale,
            MapInfo map,
            Long artId,
            Layer layer,
            List<ArtLink> links,
            List<Tile> tiles,
            List<TableSource> sources,
            List<String> issues,
            String message) {
        public Artwork {
            links = List.copyOf(links);
            tiles = List.copyOf(tiles);
            sources = List.copyOf(sources);
            issues = List.copyOf(issues);
        }
    }

    public record TileImage(
            State state,
            Tile tile,
            BlpImageTypes.Result image,
            List<String> issues,
            String message) {
        public TileImage {
            issues = List.copyOf(issues);
        }
    }
}
