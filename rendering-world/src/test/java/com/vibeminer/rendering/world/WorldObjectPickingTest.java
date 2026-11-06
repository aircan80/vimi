package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Authored quads with recorded transforms; every expected hit is computed independently. */
class WorldObjectPickingTest {
    /** A quarter turn about z: local (x, y, z) to world (-y, x, z). */
    static final double[] QUARTER = {0, -1, 0, 1, 0, 0, 0, 0, 1};

    static final TerrainCamera EYE = new TerrainCamera(0, 0, 0, 0, 0);

    /** Local quad in the plane y = 0 over x, z in [-1, 1]; uv = ((x + 1) / 2, (z + 1) / 2). */
    static final float[] QUAD = {
        -1, 0, -1, 0, 1, 0, 0, 0, //
        1, 0, -1, 0, 1, 0, 1, 0, //
        1, 0, 1, 0, 1, 0, 1, 1, //
        -1, 0, 1, 0, 1, 0, 0, 1
    };

    static final short[] TWO_TRIANGLES = {0, 1, 2, 0, 2, 3};

    /**
     * 2x2 RGBA: alpha 0 in column 0 and 255 in column 1, then its 1x1 mip level with alpha 0, which
     * picking must ignore: it tests the top level only.
     */
    static final byte[] HALF_ALPHA = {
        1, 2, 3, 0, 1, 2, 3, (byte) 255, 1, 2, 3, 0, 1, 2, 3, (byte) 255, 1, 2, 3, 0
    };

    /** One model of one quad mesh drawn by one range of {@code kind} over {@code indexCount}. */
    static WorldObjectPicking.Tile tile(int kind, int indexCount, double[]... placements) {
        var models = new int[placements.length];
        var table = new double[placements.length * 13];
        for (int p = 0; p < placements.length; p++) {
            System.arraycopy(placements[p], 0, table, p * 13, 13);
        }
        return WorldObjectPicking.Tile.of(
                new int[] {0, 1},
                new int[] {0, 4, 0, 6, 0, 1},
                new int[] {0, indexCount, 0, kind},
                models,
                table,
                new int[] {2, 2},
                QUAD,
                TWO_TRIANGLES,
                HALF_ALPHA);
    }

    static double[] placement(double x, double y, double z, double scale, double[] rotation) {
        var row = new double[13];
        row[0] = x;
        row[1] = y;
        row[2] = z;
        row[3] = scale;
        System.arraycopy(rotation, 0, row, 4, 9);
        return row;
    }

    /**
     * The quad placed at x = 100, scale 2, quarter turn: the world plane x = 100, |y|, |z| <= 2.
     */
    static double[] wall(double x) {
        return placement(x, 0, 0, 2, QUARTER);
    }

    /** Ray from the eye through world (x, y, z). */
    static Optional<WorldObjectPicking.Hit> aim(
            WorldObjectPicking.Tile tile, double x, double y, double z, double max) {
        return WorldObjectPicking.pick(tile, EYE, x, y, z, max);
    }

    @Test
    void hitsTheRecordedTransformExactly() {
        var tile = tile(WorldObjectPicking.OPAQUE, 6, wall(100));
        // World (100, 1, 0.5) is local (0.5, 0, 0.25): the first triangle (x >= z).
        var hit = aim(tile, 100, 1, .5, 2200).orElseThrow();
        assertEquals(0, hit.placement());
        assertEquals(0, hit.triangle());
        assertEquals(100, hit.x(), 1e-9);
        assertEquals(1, hit.y(), 1e-9);
        assertEquals(.5, hit.z(), 1e-9);
        assertEquals(Math.sqrt(100 * 100 + 1 + .25), hit.distance(), 1e-9);
        assertEquals(100, hit.depth(), 1e-9);
        // Local (-0.5, 0.25) lies in the second triangle.
        assertEquals(1, aim(tile, 100, -1, .5, 2200).orElseThrow().triangle());
        // Just outside the scaled edge |y| <= 2.
        assertTrue(aim(tile, 100, 2.001, 0, 2200).isEmpty());
        assertTrue(aim(tile, 100, 1.999, 0, 2200).isPresent());
    }

    @Test
    void boundsAreNeverAHit() {
        // Only the first triangle is drawn; its box still spans the whole quad.
        var tile = tile(WorldObjectPicking.OPAQUE, 3, wall(100));
        assertTrue(aim(tile, 100, 1, .5, 2200).isPresent());
        assertTrue(aim(tile, 100, -1, .5, 2200).isEmpty(), "inside the box, outside the triangle");
    }

    @Test
    void nearestPlacementWinsWithinTheLimit() {
        var tile = tile(WorldObjectPicking.OPAQUE, 6, wall(100), wall(50));
        assertEquals(1, aim(tile, 1, 0, 0, 2200).orElseThrow().placement());
        assertEquals(1, aim(tile, 1, 0, 0, 60).orElseThrow().placement());
        assertTrue(aim(tile, 1, 0, 0, 40).isEmpty());
        assertTrue(aim(tile, -1, 0, 0, 2200).isEmpty(), "behind the eye");
    }

    @Test
    void depthBeyondTheShaderDiscardIsNotDrawn() {
        assertTrue(aim(tile(WorldObjectPicking.OPAQUE, 6, wall(2201)), 1, 0, 0, 1e6).isEmpty());
        assertTrue(aim(tile(WorldObjectPicking.OPAQUE, 6, wall(2199)), 1, 0, 0, 1e6).isPresent());
    }

    @Test
    void offAxisObjectsAreClippedByEyeDepthNotRayDistance() {
        // The wall at x = 2100, y = 2100: eye depth 2100, but about 2970 yards along the ray.
        var tile = tile(WorldObjectPicking.OPAQUE, 6, placement(2100, 2100, 0, 2, QUARTER));
        var hit = aim(tile, 1, 1, 0, Double.POSITIVE_INFINITY).orElseThrow();
        assertEquals(2100, hit.depth(), 1e-6);
        assertEquals(2100 * Math.sqrt(2), hit.distance(), 1e-6);
        assertTrue(aim(tile, 1, 1, 0, 2200).isEmpty(), "a ray-distance limit is stricter");
        var beyond = tile(WorldObjectPicking.OPAQUE, 6, placement(2300, 100, 0, 2, QUARTER));
        assertTrue(
                aim(beyond, 23, 1, 0, Double.POSITIVE_INFINITY).isEmpty(),
                "depth 2300 is discarded");
    }

    @Test
    void cutoutAndBlendedFollowTheSampledAlpha() {
        // World y = 4u - 2 on the wall, at z = 0 (v = 0.5).
        var cutout = tile(WorldObjectPicking.CUTOUT, 6, wall(100));
        var blended = tile(WorldObjectPicking.BLENDED, 6, wall(100));
        var opaque = tile(WorldObjectPicking.OPAQUE, 6, wall(100));
        double transparent = 4 * .25 - 2, solid = 4 * .75 - 2;
        assertTrue(aim(cutout, 100, transparent, 0, 2200).isEmpty());
        assertTrue(aim(cutout, 100, solid, 0, 2200).isPresent());
        // Bilinear alpha crosses the shader's 0.5 reference at u = 0.5.
        assertTrue(aim(cutout, 100, 4 * .51 - 2, .5, 2200).isPresent());
        assertTrue(aim(cutout, 100, 4 * .49 - 2, .5, 2200).isEmpty());
        assertTrue(aim(blended, 100, transparent, 0, 2200).isEmpty());
        assertTrue(aim(blended, 100, 4 * .26 - 2, 0, 2200).isPresent());
        assertTrue(aim(opaque, 100, transparent, 0, 2200).isPresent());
        assertEquals(
                WorldObjectPicking.CUTOUT,
                aim(cutout, 100, solid, 0, 2200).orElseThrow().material());
    }

    @Test
    void cornersAreTheDrawnBoundsThroughTheTransform() {
        var tile = tile(WorldObjectPicking.OPAQUE, 6, wall(100));
        var corners = tile.corners(0).orElseThrow();
        // Corner 0: local (-1, 0, -1) to world (100, -2, -2); corner 7: local (1, 0, 1).
        assertArrayEquals(new double[] {100, -2, -2}, java.util.Arrays.copyOf(corners, 3), 1e-12);
        assertArrayEquals(
                new double[] {100, 2, 2}, java.util.Arrays.copyOfRange(corners, 21, 24), 1e-12);
        assertTrue(tile.retainedBytes() > 0);
        assertEquals(100, tile.transform(0)[0]);
    }

    @Test
    void malformedTablesAreRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldObjectPicking.Tile.of(
                                new int[] {0, 1},
                                new int[] {0, 4, 0, 6, 0, 1},
                                new int[] {0, 6, 1, 0},
                                new int[] {0},
                                wall(100),
                                new int[] {2, 2},
                                QUAD,
                                TWO_TRIANGLES,
                                HALF_ALPHA));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        WorldObjectPicking.Tile.of(
                                new int[] {0, 1},
                                new int[] {0, 4, 0, 6, 0, 1},
                                new int[] {0, 6, 0, 0},
                                new int[] {1},
                                wall(100),
                                new int[] {2, 2},
                                QUAD,
                                TWO_TRIANGLES,
                                HALF_ALPHA));
    }
}
