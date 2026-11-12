package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiquidSurfaceTest {
    static final double CELL = LiquidSurface.CELL;

    /** Flat liquid at {@code height} over every terrain cell overlapping the native box. */
    static LiquidSurface.Tile flat(
            double x0, double y0, double x1, double y1, double height, LiquidSurface.Kind kind) {
        var builder = new LiquidSurface.Builder();
        for (long gx = (long) Math.floor(x0 / CELL); gx * CELL < x1; gx++) {
            for (long gy = (long) Math.floor(y0 / CELL); gy * CELL < y1; gy++) {
                builder.cell(
                        (gx + 1) * CELL, (gy + 1) * CELL, height, height, height, height, kind);
            }
        }
        return builder.build();
    }

    @Test
    void aPointTakesItsCellsHeightAndKind() {
        var surface =
                LiquidSurface.of(
                        List.of(flat(0, 0, 3 * CELL, 3 * CELL, 12.5, LiquidSurface.Kind.WATER)));
        var sample = surface.at(CELL * 1.5, CELL * .2);
        assertEquals(12.5, sample.height(), 1e-4);
        assertEquals(LiquidSurface.Kind.WATER, sample.kind());
        // Cell edges belong to the cell to their north-west's south-east: still inside.
        assertEquals(12.5, surface.at(0, 0).height(), 1e-4);
        assertNull(surface.at(-.01, CELL), "no liquid south of the cells");
        assertNull(surface.at(CELL, 3 * CELL + .01), "nor west of them");
        assertNull(LiquidSurface.empty().at(0, 0));
    }

    @Test
    void aSlopedCellIsSplitOnItsNeSwDiagonal() {
        // NW 0, NE 4, SW 4, SE 0: a ridge along NE--SW, which the drawn quad keeps.
        double north = 10 * CELL, west = 20 * CELL;
        var tile =
                new LiquidSurface.Builder()
                        .cell(north, west, 0, 4, 4, 0, LiquidSurface.Kind.OCEAN)
                        .build();
        var surface = LiquidSurface.of(List.of(tile));
        // u runs west to east, v north to south: the NW triangle, then the SE triangle.
        assertEquals(1, surface.at(north - .25 * CELL, west - 1e-6).height(), 1e-3);
        assertEquals(2, surface.at(north - .25 * CELL, west - .25 * CELL).height(), 1e-3);
        assertEquals(4, surface.at(north - .5 * CELL, west - .5 * CELL).height(), 1e-3);
        assertEquals(2, surface.at(north - .75 * CELL, west - .75 * CELL).height(), 1e-3);
        assertEquals(LiquidSurface.Kind.OCEAN, surface.at(north - 1, west - 1).kind());
    }

    @Test
    void overlappingLayersAnswerWithTheHighestSurface() {
        var low = flat(0, 0, CELL, CELL, 5, LiquidSurface.Kind.MAGMA);
        var high = flat(0, 0, CELL, CELL, 7, LiquidSurface.Kind.WATER);
        var surface = LiquidSurface.of(Arrays.asList(low, null, high));
        assertEquals(2, surface.tiles(), "null tiles carry no liquid");
        assertEquals(7, surface.at(1, 1).height(), 1e-4);
        assertEquals(LiquidSurface.Kind.WATER, surface.at(1, 1).kind());
        assertSame(LiquidSurface.empty(), LiquidSurface.of(Arrays.asList(null, null)));
    }

    @Test
    void realCoordinatesFindTheirCell() {
        // Native coordinates far from the origin (Elwynn Forest), as float corners.
        double x = -9_401.3, y = 9.7;
        var surface =
                LiquidSurface.of(
                        List.of(
                                flat(
                                        x - 20,
                                        y - 20,
                                        x + 20,
                                        y + 20,
                                        42,
                                        LiquidSurface.Kind.WATER)));
        for (double dx = -15; dx <= 15; dx += 0.7) {
            assertEquals(42, surface.at(x + dx, y - dx).height(), 1e-3);
        }
    }

    @Test
    void invalidCellsAndTooManyTilesAreRefused() {
        var builder = new LiquidSurface.Builder();
        assertNull(builder.build(), "no cells, no tile");
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.cell(Double.NaN, 0, 0, 0, 0, 0, LiquidSurface.Kind.WATER));
        var tile = flat(0, 0, CELL, CELL, 0, LiquidSurface.Kind.WATER);
        var many = new LiquidSurface.Tile[LiquidSurface.MAX_TILES + 1];
        Arrays.fill(many, tile);
        assertThrows(IllegalArgumentException.class, () -> LiquidSurface.of(List.of(many)));
    }
}
