package com.vibeminer.formats.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldLiquidGridTest {
    private static TerrainGrid.Chunk chunk(
            int ordinal, int x, int y, float north, float west, float base) {
        float[] heights = new float[145];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = i * .125f;
        }
        return new TerrainGrid.Chunk(
                ordinal,
                x,
                y,
                0,
                north,
                west,
                base,
                heights,
                0,
                TerrainGrid.ColorState.ABSENT,
                new byte[0]);
    }

    @Test
    void asymmetricHeaderRectangleAndShuffledChunkOrderHaveIndependentAxisOracle() {
        var decoy = chunk(0, 11, 4, -111, 222, 300);
        var actual = chunk(1, 4, 11, 1000, -2000, 200);
        var grid = new TerrainGrid(List.of(decoy, actual));
        var mapping = WorldLiquidGrid.rectangle(grid, 11 * 16 + 4, 1, 2, 2, 3);
        assertEquals(4, mapping.chunkX());
        assertEquals(11, mapping.chunkY());
        // Explicit expected world axes, not a second invocation of the mapping arithmetic.
        var nw = mapping.vertex(0, 0, 17.25f);
        assertEquals(991.6667f, nw.x(), .0001f);
        assertEquals(-2004.1666f, nw.y(), .0002f);
        assertEquals(17.25f, nw.z());
        var east = mapping.vertex(0, 1, 17.25f);
        var south = mapping.vertex(1, 0, 17.25f);
        assertEquals(nw.x(), east.x());
        assertTrue(east.y() < nw.y());
        assertTrue(south.x() < nw.x());
        assertEquals(nw.y(), south.y());
        // Asymmetric mask bit0=(row0,col0),bit5=(row2,col1) at width2.
        var far = mapping.vertex(2, 1, 17.25f);
        assertEquals(983.3333f, far.x(), .0002f);
        assertEquals(-2008.3334f, far.y(), .0002f);
        assertEquals(17.25f, far.z()); // Not terrain base200 plus a guessed/local liquid height.
        for (int row = 0; row <= 3; row++) {
            for (int col = 0; col <= 2; col++) {
                var ground = actual.position(2 * (2 + row), 1 + col);
                var liquid = mapping.vertex(row, col, 17.25f);
                assertEquals(
                        Float.floatToRawIntBits(ground.x()), Float.floatToRawIntBits(liquid.x()));
                assertEquals(
                        Float.floatToRawIntBits(ground.y()), Float.floatToRawIntBits(liquid.y()));
            }
        }
    }

    @Test
    void neighboringSourceChunksMeetOnRecordedNativeEdgesWithoutHeightBaseAddition() {
        float step = (float) (WorldFormats.TILE_SIZE / 16);
        var west = chunk(0, 4, 11, 1000, -2000, 100);
        var east = chunk(1, 5, 11, 1000, -2000 - step, 400);
        var south = chunk(2, 4, 12, 1000 - step, -2000, -300);
        var grid = new TerrainGrid(List.of(west, east, south));
        var a = WorldLiquidGrid.rectangle(grid, 11 * 16 + 4, 0, 0, 8, 8);
        var b = WorldLiquidGrid.rectangle(grid, 11 * 16 + 5, 0, 0, 8, 8);
        var c = WorldLiquidGrid.rectangle(grid, 12 * 16 + 4, 0, 0, 8, 8);
        for (int i = 0; i <= 8; i++) {
            var ae = a.vertex(i, 8, 0);
            var bw = b.vertex(i, 0, 0);
            assertEquals(ae.x(), bw.x(), .0002f);
            assertEquals(ae.y(), bw.y(), .0002f);
            assertEquals(0, bw.z());
            var as = a.vertex(8, i, 0);
            var cn = c.vertex(0, i, 0);
            assertEquals(as.x(), cn.x(), .0002f);
            assertEquals(as.y(), cn.y(), .0002f);
            assertEquals(0, cn.z());
        }
    }

    @Test
    void invalidMissingRectanglesAndNonfiniteHeightsDoNotGuessAnchors() {
        var grid = new TerrainGrid(List.of(chunk(0, 4, 11, 1000, -2000, 200)));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquidGrid.rectangle(grid, 4 * 16 + 11, 0, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquidGrid.rectangle(grid, 256, 0, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquidGrid.rectangle(grid, 180, 7, 0, 2, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorldLiquidGrid.rectangle(grid, 180, 0, 0, 0, 1));
        var mapping = WorldLiquidGrid.rectangle(grid, 180, 0, 0, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> mapping.vertex(2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> mapping.vertex(0, 0, Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> mapping.vertex(0, 0, Float.POSITIVE_INFINITY));
    }
}
