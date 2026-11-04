package com.vibeminer.concepts.locations;

import com.vibeminer.builds.BuildTypes.BuildRef;
import java.util.Locale;
import java.util.Objects;

/** Bounded descriptive map facts. These values do not authenticate tables or qualify a schema. */
public final class MapNavigationTypes {
    public static final String REVISION = "recorded-map-navigation-v1";
    public static final String UI_MAP_LAYOUT = "DB51D55F", ASSIGNMENT_LAYOUT = "C9CC8DFB";
    public static final int MAX_NODES = 8192, MAX_ASSIGNMENTS = 16_384;

    private MapNavigationTypes() {}

    public record UiMapId(long value) {
        public UiMapId {
            id(value, false);
        }
    }

    public record WorldMapId(long value) {
        public WorldMapId {
            id(value, true);
        }
    }

    public enum Table {
        UI_MAP,
        ASSIGNMENT,
        LINK,
        ART_RELATION,
        ART,
        ART_STYLE,
        ART_TILE,
        GROUP,
        GROUP_MEMBER
    }

    public enum ReadState {
        READY,
        PARTIAL,
        UNAVAILABLE,
        UNSUPPORTED
    }

    /** READY means the source owner completed a qualified, stable, untruncated table scan. */
    public record TableRead(Table table, String contentKey, String layoutHash, ReadState state) {
        public TableRead {
            Objects.requireNonNull(table);
            Objects.requireNonNull(state);
            if ((contentKey == null) != (layoutHash == null)
                    || contentKey != null && (!hex(contentKey, 32) || !hex(layoutHash, 8))
                    || readable(state) && contentKey == null) {
                throw new IllegalArgumentException("Invalid map table evidence");
            }
            if (contentKey != null) {
                contentKey = contentKey.toLowerCase(Locale.ROOT);
                layoutHash = layoutHash.toUpperCase(Locale.ROOT);
            }
        }

        public boolean readable() {
            return readable(state);
        }

        private static boolean readable(ReadState state) {
            return state == ReadState.READY || state == ReadState.PARTIAL;
        }
    }

    public record RowSource(TableRead table, long rowId) {
        public RowSource {
            Objects.requireNonNull(table);
            id(rowId, true);
            if (!table.readable()) {
                throw new IllegalArgumentException("Unreadable row evidence");
            }
        }
    }

    public record Scope(BuildRef build, String locale, TableRead maps, TableRead assignments) {
        public Scope {
            Objects.requireNonNull(build);
            Objects.requireNonNull(maps);
            Objects.requireNonNull(assignments);
            if (locale == null
                    || locale.isBlank()
                    || locale.length() > 16
                    || maps.table() != Table.UI_MAP
                    || !maps.readable()
                    || !UI_MAP_LAYOUT.equals(maps.layoutHash())
                    || assignments.table() != Table.ASSIGNMENT
                    || assignments.readable()
                            && !ASSIGNMENT_LAYOUT.equals(assignments.layoutHash())) {
                throw new IllegalArgumentException("Unsupported map navigation source scope");
            }
        }
    }

    /** Unknown is distinct from a verified root (known=true, id=null). */
    public record Parent(boolean known, UiMapId id) {
        public Parent {
            if (!known && id != null) {
                throw new IllegalArgumentException("Unknown parent has no ID");
            }
        }

        public static Parent unknown() {
            return new Parent(false, null);
        }

        public static Parent root() {
            return new Parent(true, null);
        }

        public static Parent of(UiMapId id) {
            return new Parent(true, Objects.requireNonNull(id));
        }
    }

    public record Node(
            UiMapId id,
            String name,
            Parent parent,
            Integer system,
            Integer type,
            Long flags,
            Long visibilityConditionId,
            RowSource source) {
        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(parent);
            Objects.requireNonNull(source);
            if (name != null && name.length() > 256
                    || system != null && (system < 0 || system > 255)
                    || type != null && (type < 0 || type > 255)
                    || flags != null && (flags < 0 || flags > 0xffff_ffffL)
                    || visibilityConditionId != null
                            && (visibilityConditionId < 0 || visibilityConditionId > 0xffff_ffffL)
                    || source.table().table() != Table.UI_MAP
                    || source.rowId() != id.value()) {
                throw new IllegalArgumentException("Invalid map node");
            }
        }

        public String displayName() {
            return name == null || name.isBlank() ? "Map #" + id.value() : name;
        }
    }

    public record Rect(double minX, double minY, double maxX, double maxY) {
        public Rect {
            finite(minX);
            finite(minY);
            finite(maxX);
            finite(maxY);
            if (minX < 0 || minY < 0 || maxX > 1 || maxY > 1 || minX >= maxX || minY >= maxY) {
                throw new IllegalArgumentException("Invalid normalized map rectangle");
            }
        }

        public boolean fullExtent() {
            return minX == 0 && minY == 0 && maxX == 1 && maxY == 1;
        }

        /** Adjacent rectangles share no hit except the outer image's right/bottom edge. */
        public boolean contains(double x, double y) {
            return Double.isFinite(x)
                    && Double.isFinite(y)
                    && x >= minX
                    && y >= minY
                    && (x < maxX || x == 1 && maxX == 1)
                    && (y < maxY || y == 1 && maxY == 1);
        }
    }

    /** Ordered native world X/Y bounds; UI X follows reversed world Y, UI Y reversed world X. */
    public record WorldRect(double minX, double minY, double maxX, double maxY) {
        public WorldRect {
            for (double value : new double[] {minX, minY, maxX, maxY}) {
                finite(value);
                if (Math.abs(value) > 1_000_000) {
                    throw new IllegalArgumentException("World bound");
                }
            }
            if (minX >= maxX || minY >= maxY) {
                throw new IllegalArgumentException("Degenerate world map rectangle");
            }
        }
    }

    public record Assignment(
            UiMapId uiMap,
            WorldMapId worldMap,
            WorldRect world,
            Rect ui,
            boolean unscoped,
            RowSource source) {
        public Assignment {
            Objects.requireNonNull(uiMap);
            Objects.requireNonNull(worldMap);
            Objects.requireNonNull(world);
            Objects.requireNonNull(ui);
            Objects.requireNonNull(source);
            if (source.table().table() != Table.ASSIGNMENT) {
                throw new IllegalArgumentException("Invalid assignment evidence");
            }
        }
    }

    public record View(double centerX, double centerY, double zoom) {
        public View {
            finite(centerX);
            finite(centerY);
            finite(zoom);
            if (centerX < 0 || centerX > 1 || centerY < 0 || centerY > 1 || zoom < 1 || zoom > 32) {
                throw new IllegalArgumentException("Invalid map view");
            }
        }

        public static View fit() {
            return new View(.5, .5, 1);
        }
    }

    static void id(long value, boolean zero) {
        if (value < (zero ? 0 : 1) || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid map identifier");
        }
    }

    static void finite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Nonfinite map value");
        }
    }

    static boolean hex(String value, int length) {
        return value != null && value.length() == length && value.matches("[0-9a-fA-F]+$");
    }
}
