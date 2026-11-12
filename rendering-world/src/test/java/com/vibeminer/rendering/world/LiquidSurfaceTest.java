package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A flat local liquid at {@code height} over local [0, 10] x [0, 10]. */
    static LiquidSurface.Local square(double height) {
        return new LiquidSurface.Local() {
            @Override
            public double heightAt(double x, double y) {
                return x >= 0 && x <= 10 && y >= 0 && y <= 10 ? height : Double.NaN;
            }

            @Override
            public double[] bounds() {
                return new double[] {0, 0, 10, 10};
            }

            @Override
            public double minHeight() {
                return height;
            }

            @Override
            public double maxHeight() {
                return height;
            }
        };
    }

    // A quarter turn about +Z: local (a, b) -> (-b, a).
    static final double[] QUARTER = {0, -1, 0, 1, 0, 0, 0, 0, 1};

    @Test
    void aPlacedLiquidIsFoundThroughItsBuildingsTransform() {
        var placed =
                new LiquidSurface.Placed(
                        "pool", square(3), LiquidSurface.Kind.WATER, QUARTER, 2, 100, 200, 50);
        var surface = LiquidSurface.empty().with(List.of(placed));
        assertEquals(1, surface.placed());
        // Local (2, 5) -> native (100 - 2 * 5, 200 + 2 * 2); height 50 + 2 * 3.
        var sample = surface.at(90, 204);
        assertEquals(56, sample.height(), 1e-9);
        assertEquals(LiquidSurface.Kind.WATER, sample.kind());
        assertNull(surface.at(101, 204), "local a < 0: outside");
        assertNull(surface.at(90, 221), "local a > 10: outside");
    }

    @Test
    void theHighestOfTerrainAndBuildingLiquidAnswersAndAKeyCountsOnce() {
        var placed =
                new LiquidSurface.Placed(
                        "pool", square(3), LiquidSurface.Kind.OCEAN, QUARTER, 2, 100, 200, 50);
        var low = LiquidSurface.of(List.of(flat(80, 195, 100, 225, 40, LiquidSurface.Kind.WATER)));
        var high = LiquidSurface.of(List.of(flat(80, 195, 100, 225, 60, LiquidSurface.Kind.MAGMA)));
        assertEquals(LiquidSurface.Kind.OCEAN, low.with(List.of(placed)).at(90, 204).kind());
        assertEquals(56, low.with(List.of(placed)).at(90, 204).height(), 1e-9);
        assertEquals(
                LiquidSurface.Kind.MAGMA, high.with(List.of(placed, placed)).at(90, 204).kind());
        assertEquals(1, high.with(List.of(placed, placed)).placed(), "the same key once");
        assertEquals(
                1,
                high.with(List.of(placed)).with(List.of(placed)).placed(),
                "replaced, not added");
        assertEquals(40, low.at(90, 204).height(), 1e-4, "the terrain surface alone is unchanged");
    }

    @Test
    void onlyAnUprightPlacementIsAdmitted() {
        double s = Math.sin(.3), c = Math.cos(.3);
        double[] pitched = {1, 0, 0, 0, c, -s, 0, s, c};
        assertFalse(LiquidSurface.upright(pitched));
        assertTrue(LiquidSurface.upright(QUARTER));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LiquidSurface.Placed(
                                "x", square(0), LiquidSurface.Kind.WATER, pitched, 1, 0, 0, 0));
    }
}
