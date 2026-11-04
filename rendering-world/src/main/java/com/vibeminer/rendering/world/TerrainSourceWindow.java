package com.vibeminer.rendering.world;

import java.util.List;
import java.util.Objects;

/** Complete bounded descriptor window, including explicit absent/unavailable source outcomes. */
public final class TerrainSourceWindow {
    public static final int MAX_SOURCES = 49;
    private final int anchorX, anchorY, firstX, firstY, rows, columns;
    private final List<CompactTerrainSource> sources;
    private final CompactTerrainSource[] ordered;

    private TerrainSourceWindow(int x, int y, List<CompactTerrainSource> input) {
        anchorX = x;
        anchorY = y;
        firstX = Math.max(0, x - 3);
        firstY = Math.max(0, y - 3);
        rows = Math.min(63, x + 3) - firstX + 1;
        columns = Math.min(63, y + 3) - firstY + 1;
        if (input.size() != rows * columns) {
            throw new IllegalArgumentException(
                    "Descriptor window must explicitly cover every slot");
        }
        sources = List.copyOf(input);
        ordered = new CompactTerrainSource[rows * columns];
        for (var source : sources) {
            var d = source.descriptor();
            int r = d.x() - firstX, c = d.y() - firstY;
            if (r < 0 || r >= rows || c < 0 || c >= columns || ordered[r * columns + c] != null) {
                throw new IllegalArgumentException("Duplicate/outside source descriptor");
            }
            ordered[r * columns + c] = source;
        }
    }

    public static TerrainSourceWindow of(int x, int y, List<CompactTerrainSource> sources) {
        Objects.requireNonNull(sources);
        if (x < 0 || x >= 64 || y < 0 || y >= 64 || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("Invalid source window bounds");
        }
        return new TerrainSourceWindow(x, y, sources);
    }

    public int anchorX() {
        return anchorX;
    }

    public int anchorY() {
        return anchorY;
    }

    public int firstX() {
        return firstX;
    }

    public int firstY() {
        return firstY;
    }

    public int rows() {
        return rows;
    }

    public int columns() {
        return columns;
    }

    public List<CompactTerrainSource> sources() {
        return sources;
    }

    CompactTerrainSource source(int ordinal) {
        return ordered[ordinal];
    }

    int count() {
        return ordered.length;
    }
}
