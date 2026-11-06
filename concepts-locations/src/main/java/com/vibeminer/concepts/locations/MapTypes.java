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

    /**
     * One unscoped UiMapAssignment row as a recorded linear frame between a UiMap's normalized
     * rectangle ({@code uiMin*}/{@code uiMax*}) and a world-yard region ({@code Region[0,1]} to
     * {@code Region[3,4]}, x north and y west). {@link #toUi} is the selected-build world
     * projection the resolver applies; {@link #toWorld} is its exact inverse over the same row.
     * Neither guesses a height.
     */
    public record WorldFrame(
            long assignmentId,
            long uiMapId,
            long worldMapId,
            double uiMinX,
            double uiMinY,
            double uiMaxX,
            double uiMaxY,
            double minX,
            double minY,
            double maxX,
            double maxY) {
        public WorldFrame {
            for (double value :
                    new double[] {uiMinX, uiMinY, uiMaxX, uiMaxY, minX, minY, maxX, maxY}) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Non-finite frame value");
                }
            }
        }

        /** Positive, finite world extent and a UI rectangle with distinct edges. */
        public boolean invertible() {
            return maxX - minX > 0 && maxY - minY > 0 && uiMaxX != uiMinX && uiMaxY != uiMinY;
        }

        /**
         * Whether a normalized UiMap point lies inside this row's UI rectangle (edges included).
         */
        public boolean containsUi(double uiX, double uiY) {
            return uiX >= Math.min(uiMinX, uiMaxX)
                    && uiX <= Math.max(uiMinX, uiMaxX)
                    && uiY >= Math.min(uiMinY, uiMaxY)
                    && uiY <= Math.max(uiMinY, uiMaxY);
        }

        /** World yards to the normalized UiMap point, as the resolver records it; null outside. */
        public double[] toUi(double worldX, double worldY) {
            double sizeX = maxX - minX, sizeY = maxY - minY;
            if (!(sizeX > 0)
                    || !(sizeY > 0)
                    || worldX < minX
                    || worldX > maxX
                    || worldY < minY
                    || worldY > maxY) {
                return null;
            }
            double relativeX = (worldX - minX) / sizeX, relativeY = (worldY - minY) / sizeY;
            return new double[] {
                relativeY * uiMinX + (1 - relativeY) * uiMaxX,
                relativeX * uiMinY + (1 - relativeX) * uiMaxY
            };
        }

        /**
         * The exact inverse of {@link #toUi}: world x/y (no height), or null when not invertible.
         */
        public double[] toWorld(double uiX, double uiY) {
            if (!invertible() || !containsUi(uiX, uiY)) {
                return null;
            }
            double relativeY = (uiMaxX - uiX) / (uiMaxX - uiMinX);
            double relativeX = (uiMaxY - uiY) / (uiMaxY - uiMinY);
            return new double[] {
                minX + relativeX * (maxX - minX), minY + relativeY * (maxY - minY)
            };
        }
    }

    /** The unscoped assignment frames recorded for one world map. */
    public record WorldFrames(
            State state,
            long worldMapId,
            List<WorldFrame> frames,
            List<String> issues,
            String message) {
        public static final int MAX_FRAMES = 4096;

        public WorldFrames {
            java.util.Objects.requireNonNull(state);
            frames = List.copyOf(frames);
            issues = List.copyOf(issues);
            if (frames.size() > MAX_FRAMES) {
                throw new IllegalArgumentException("World frames exceed their limit");
            }
        }

        public static WorldFrames unsupported(long worldMapId) {
            return new WorldFrames(
                    State.UNSUPPORTED,
                    worldMapId,
                    List.of(),
                    List.of(),
                    "World frames are unsupported by this map source.");
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

    /**
     * One UiMapAssignment row scoped to a WMO group ({@code WMOGroupID != 0}) on one world map.
     * {@code map} is null when the referenced UiMap record is unavailable. Values are recorded,
     * never matched or inferred here.
     */
    public record WmoGroupAssignment(
            long assignmentId,
            long uiMapId,
            MapInfo map,
            long wmoGroupId,
            long wmoDoodadPlacementId,
            List<TableSource> sources) {
        public WmoGroupAssignment {
            if (wmoGroupId == 0) {
                throw new IllegalArgumentException("A group assignment needs a WMO group ID");
            }
            sources = List.copyOf(sources);
        }
    }

    /**
     * Every readable group-scoped assignment for one world map, with the UiMap records their
     * parents name (one level). {@code unreadable} counts rows for the map whose group, placement
     * or UiMap fields could not be read; {@code overLimit} counts readable rows beyond {@link
     * #MAX_ASSIGNMENTS}. Neither is ever matched.
     */
    public record WmoGroupAssignments(
            State state,
            BuildRef build,
            String locale,
            long worldMapId,
            List<WmoGroupAssignment> assignments,
            List<MapInfo> parents,
            int unreadable,
            int overLimit,
            List<String> issues,
            String message) {
        public static final int MAX_ASSIGNMENTS = 1024;

        public WmoGroupAssignments {
            java.util.Objects.requireNonNull(state);
            assignments = List.copyOf(assignments);
            parents = List.copyOf(parents);
            issues = List.copyOf(issues);
            if (assignments.size() > MAX_ASSIGNMENTS || unreadable < 0 || overLimit < 0) {
                throw new IllegalArgumentException("Group assignments exceed their limits");
            }
        }

        public static WmoGroupAssignments unsupported(long worldMapId) {
            return new WmoGroupAssignments(
                    State.UNSUPPORTED,
                    null,
                    null,
                    worldMapId,
                    List.of(),
                    List.of(),
                    0,
                    0,
                    List.of(),
                    "Group-scoped UiMap assignments are not readable from this source.");
        }
    }
}
