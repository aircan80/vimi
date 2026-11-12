package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class ObjectCollisionTest {
    /** A unit cube's six faces (12 triangles) from (0,0,0) to (1,1,1); counts its queries. */
    static final class Cube implements ObjectCollision.Mesh {
        static final float[] V = {
            0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1
        };
        static final int[] T = {
            0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7,
            6, 3, 0, 4, 3, 4, 7
        };
        int queries;
        int positions;

        @Override
        public int triangles() {
            return 12;
        }

        @Override
        public int corner(int triangle, int corner) {
            return T[triangle * 3 + corner];
        }

        @Override
        public float position(int vertex, int axis) {
            positions++;
            return V[vertex * 3 + axis];
        }

        @Override
        public void query(
                float x0, float y0, float z0, float x1, float y1, float z1, IntConsumer triangle) {
            queries++;
            for (int t = 0; t < 12; t++) {
                triangle.accept(t);
            }
        }

        @Override
        public long bytes() {
            return 100;
        }
    }

    static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    static List<double[]> triangles(
            ObjectCollision c, double x0, double y0, double z0, double x1, double y1, double z1) {
        var out = new ArrayList<double[]>();
        c.triangles(
                x0,
                y0,
                z0,
                x1,
                y1,
                z1,
                (ax, ay, az, bx, by, bz, cx, cy, cz) ->
                        out.add(new double[] {ax, ay, az, bx, by, bz, cx, cy, cz}));
        return out;
    }

    @Test
    void placementsAreRotatedScaledAndMoved() {
        // A quarter turn about Z, scale 2, moved to (100, 50, 10): local (1, 0, 0) lands at (100,
        // 52, 10).
        double[] quarter = {0, -1, 0, 1, 0, 0, 0, 0, 1};
        var c =
                ObjectCollision.of(
                        List.of(
                                new ObjectCollision.Placement(
                                        "a", new Cube(), quarter, 2, 100, 50, 10)));
        assertArrayEquals(new double[] {98, 50, 10, 100, 52, 12}, c.bounds(), 1e-9);
        var found = triangles(c, 90, 40, 0, 110, 60, 20);
        assertEquals(12, found.size());
        boolean corner = false;
        for (var t : found) {
            for (int k = 0; k < 3; k++) {
                corner |=
                        Math.abs(t[k * 3] - 100) < 1e-9
                                && Math.abs(t[k * 3 + 1] - 52) < 1e-9
                                && Math.abs(t[k * 3 + 2] - 10) < 1e-9;
            }
        }
        assertTrue(corner, "local (1, 0, 0) at (100, 52, 10)");
    }

    @Test
    void queriesSkipPlacementsAwayFromTheBox() {
        var near = new Cube();
        var far = new Cube();
        var c =
                ObjectCollision.of(
                        List.of(
                                new ObjectCollision.Placement("near", near, IDENTITY, 1, 0, 0, 0),
                                new ObjectCollision.Placement(
                                        "far", far, IDENTITY, 1, 500, 500, 0)));
        assertEquals(12, triangles(c, -1, -1, -1, 2, 2, 2).size());
        assertEquals(1, near.queries);
        assertEquals(0, far.queries, "a placement in another grid cell is never asked");
        assertEquals(0, triangles(c, 0, 0, 50, 1, 1, 60).size(), "above the placement's box");
    }

    @Test
    void theSamePlacementListedTwiceCountsOnce() {
        var mesh = new Cube();
        var c =
                ObjectCollision.of(
                        List.of(
                                new ObjectCollision.Placement(
                                        "building", mesh, IDENTITY, 1, 0, 0, 0),
                                new ObjectCollision.Placement(
                                        "building", mesh, IDENTITY, 1, 0, 0, 0)));
        assertEquals(1, c.placements());
        assertEquals(12, triangles(c, -1, -1, -1, 2, 2, 2).size());
        assertEquals(100 + 160 + 16 + 4, c.bytes(), 64, "the mesh is counted once");
        assertNull(ObjectCollision.empty().bounds());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ObjectCollision.Placement("x", mesh, IDENTITY, 0, 0, 0, 0));
    }

    @Test
    void aWalkingBodyStandsOnAndIsStoppedByPlacedObjects() {
        // A flat terrain-like floor plus a 4 x 4 x 0.6 platform and a 4 x 4 x 3 block, as objects.
        var floor = new TriangleWorld().floor(-100, -100, 100, 100, 0);
        var objects =
                ObjectCollision.of(
                        List.of(
                                new ObjectCollision.Placement(
                                        "step",
                                        new Cube(),
                                        new double[] {4, 0, 0, 0, 4, 0, 0, 0, .6},
                                        1,
                                        4,
                                        -2,
                                        0),
                                new ObjectCollision.Placement(
                                        "wall",
                                        new Cube(),
                                        new double[] {4, 0, 0, 0, 4, 0, 0, 0, 3},
                                        1,
                                        -8,
                                        -2,
                                        0)));
        CollisionWorld world =
                new CollisionWorld() {
                    @Override
                    public void triangles(
                            double x0,
                            double y0,
                            double z0,
                            double x1,
                            double y1,
                            double z1,
                            TerrainGround.TriangleSink sink) {
                        floor.triangles(x0, y0, z0, x1, y1, z1, sink);
                        objects.triangles(x0, y0, z0, x1, y1, z1, sink);
                    }

                    @Override
                    public Coverage coverage(double x0, double y0, double x1, double y1) {
                        return floor.coverage(x0, y0, x1, y1);
                    }
                };
        var body = WalkControllerTest.standing(world, 0, 0, 1);
        WalkControllerTest.run(body, WalkControllerTest.input(1, 0, 0, 0, false), 1, world);
        assertEquals(.6, body.z(), .03, "walked up onto the platform");
        body = WalkControllerTest.standing(world, 0, 0, 1);
        WalkControllerTest.run(body, WalkControllerTest.input(1, 0, 0, Math.PI, false), 2, world);
        assertEquals(-4 + MovementParity.DEFAULT_RADIUS, body.x(), .05, "stopped by the block");
    }

    @Test
    void preparedPartsMergeWithoutTransformingAgain() {
        var mesh = new Cube();
        var a =
                ObjectCollision.prepare(
                        List.of(new ObjectCollision.Placement("a", mesh, IDENTITY, 1, 0, 0, 0)));
        var b =
                ObjectCollision.prepare(
                        List.of(
                                new ObjectCollision.Placement("a", mesh, IDENTITY, 1, 0, 0, 0),
                                new ObjectCollision.Placement("b", mesh, IDENTITY, 1, 40, 0, 0)));
        int after = mesh.positions;
        var merged = ObjectCollision.merge(List.of(a, b));
        ObjectCollision.merge(List.of(b, a));
        assertEquals(after, mesh.positions, "merging reuses the prepared boxes");
        assertEquals(2, merged.placements(), "the shared key counts once");
        assertEquals(41, merged.bounds()[3], 1e-9);
    }
}
