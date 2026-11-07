package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.WmoPortalTraversal;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The camera and view planes of #383 step 3b, moved into a placement's WMO-local axes. */
final class WorldStreamPortalCullingTest {
    private static final int WIDTH = 1600, HEIGHT = 900;

    private static double value(double[] planes, int i, double x, double y, double z) {
        return planes[i * 4] * x
                + planes[i * 4 + 1] * y
                + planes[i * 4 + 2] * z
                + planes[i * 4 + 3];
    }

    /**
     * The view's planes hold exactly the points the explorer's eye convention puts inside the side
     * planes and before the cut, whatever their depth: there is no near plane, so a point just in
     * front of the eye is inside and a point behind it is not.
     */
    @Test
    void viewPlanesAreTheSidesAndTheCutOfTheEyeConvention() {
        var camera = new TerrainCamera(100, -50, 30, 0.7, -0.3);
        double cut = 2000;
        var view = WorldStreamObjectCulling.View.of(camera, WIDTH, HEIGHT, cut);
        var planes = new double[20];
        assertEquals(5, view.portalPlanes(planes));
        double kx = 1 / TerrainCamera.FOCAL, ky = HEIGHT / (TerrainCamera.FOCAL * WIDTH);
        var random = new Random(383);
        int inside = 0;
        for (int i = 0; i < 20_000; i++) {
            double x = camera.x() + (random.nextDouble() - 0.5) * 4000;
            double y = camera.y() + (random.nextDouble() - 0.5) * 4000;
            double z = camera.z() + (random.nextDouble() - 0.5) * 4000;
            var eye = camera.eye(x, y, z);
            boolean expected =
                    Math.abs(eye.right()) <= kx * eye.depth()
                            && Math.abs(eye.up()) <= ky * eye.depth()
                            && eye.depth() <= cut;
            boolean held = true;
            for (int p = 0; p < 5; p++) {
                held &= value(planes, p, x, y, z) >= 0;
            }
            // Points within a hair of a plane can round either way.
            double margin =
                    Math.min(
                            Math.min(
                                    kx * eye.depth() - Math.abs(eye.right()),
                                    ky * eye.depth() - Math.abs(eye.up())),
                            cut - eye.depth());
            if (Math.abs(margin) > 1e-6) {
                assertEquals(expected, held, "point " + x + ", " + y + ", " + z);
            }
            inside += expected ? 1 : 0;
        }
        assertTrue(inside > 100, "the sample reaches inside the frustum: " + inside);
        // Closer than the near distance, straight ahead: inside (no near plane). Behind: outside.
        var ahead = new double[] {Math.cos(0.7) * Math.cos(-0.3), Math.sin(0.7) * Math.cos(-0.3)};
        double near = TerrainCamera.NEAR / 4;
        double sz = camera.z() + Math.sin(-0.3) * near;
        boolean close = true, behind = true;
        for (int p = 0; p < 5; p++) {
            close &=
                    value(planes, p, camera.x() + ahead[0] * near, camera.y() + ahead[1] * near, sz)
                            >= 0;
            behind &=
                    value(
                                    planes,
                                    p,
                                    camera.x() - ahead[0] * 10,
                                    camera.y() - ahead[1] * 10,
                                    camera.z())
                            >= 0;
        }
        assertTrue(close, "a point nearer than the near distance is inside");
        assertFalse(behind, "a point behind the eye is outside");
    }

    /**
     * A placement maps local q to t + s R q. Its local camera maps back to the world camera, and a
     * local plane's value at a local point is the world plane's value there, divided by a positive
     * factor: inside stays inside.
     */
    @Test
    void cameraAndPlanesMoveIntoThePlacementsAxes() {
        double s = 2.5;
        double angle = 0.9;
        // Row-major rotation about z by angle, then a tilt about x: a proper rotation.
        double c = Math.cos(angle), sn = Math.sin(angle), ct = Math.cos(0.3), st = Math.sin(0.3);
        double[][] rz = {{c, -sn, 0}, {sn, c, 0}, {0, 0, 1}};
        double[][] rx = {{1, 0, 0}, {0, ct, -st}, {0, st, ct}};
        var rotation = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    rotation[i * 3 + j] += rz[i][k] * rx[k][j];
                }
            }
        }
        var placements = new double[WorldStreamObjectsFile.PLACEMENT_DOUBLES];
        placements[0] = 1000;
        placements[1] = -2000;
        placements[2] = 50;
        placements[3] = s;
        System.arraycopy(rotation, 0, placements, 4, 9);
        var view =
                WorldStreamObjectCulling.View.of(
                        new TerrainCamera(1010, -1990, 60, 2.0, -0.2), WIDTH, HEIGHT, 3000);
        var world = new double[20];
        int count = view.portalPlanes(world);
        var camera = new double[3];
        var local = new double[20];
        WorldStreamPortalCulling.toLocal(
                placements, 0, view.x(), view.y(), view.z(), world, count, camera, local);
        var back = toWorld(placements, camera[0], camera[1], camera[2]);
        assertEquals(view.x(), back[0], 1e-9);
        assertEquals(view.y(), back[1], 1e-9);
        assertEquals(view.z(), back[2], 1e-9);
        var random = new Random(3);
        for (int i = 0; i < 2000; i++) {
            double qx = (random.nextDouble() - 0.5) * 400;
            double qy = (random.nextDouble() - 0.5) * 400;
            double qz = (random.nextDouble() - 0.5) * 400;
            var w = toWorld(placements, qx, qy, qz);
            for (int p = 0; p < count; p++) {
                double inWorld = value(world, p, w[0], w[1], w[2]);
                double inLocal = value(local, p, qx, qy, qz);
                if (Math.abs(inWorld) > 1e-6) {
                    assertEquals(Math.signum(inWorld), Math.signum(inLocal), "plane " + p);
                }
            }
        }
        // Local planes are unit length.
        for (int p = 0; p < count; p++) {
            double a = local[p * 4], b = local[p * 4 + 1], cc = local[p * 4 + 2];
            assertEquals(1, Math.sqrt(a * a + b * b + cc * cc), 1e-12);
        }
    }

    private static double[] toWorld(double[] placements, double qx, double qy, double qz) {
        double s = placements[3];
        var out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] =
                    placements[i]
                            + s
                                    * (placements[4 + i * 3] * qx
                                            + placements[4 + i * 3 + 1] * qy
                                            + placements[4 + i * 3 + 2] * qz);
        }
        return out;
    }

    /**
     * An authored room pair placed with identity rotation: from inside room A looking at the door,
     * the walk reaches B; looking away it does not, and B's interior meshes would be culled.
     * Without a view (culling off) nothing is walked.
     */
    @Test
    void aWalkFromTheCameraReachesOnlyTheRoomsInView() {
        var traversal =
                WmoPortalTraversal.of(
                        List.of(
                                new WmoPortalTraversal.Group(
                                        WmoPortalTraversal.INTERIOR, 0, -5, 0, 10, 5, 5, 0, 1),
                                new WmoPortalTraversal.Group(
                                        WmoPortalTraversal.INTERIOR, 10, -5, 0, 20, 5, 5, 1, 1)),
                        List.of(
                                new WmoPortalTraversal.Portal(
                                        new double[] {10, -1, 0, 10, 1, 0, 10, 1, 2, 10, -1, 2},
                                        1,
                                        0,
                                        0,
                                        -10)),
                        List.of(
                                new WmoPortalTraversal.Ref(0, 1, -1),
                                new WmoPortalTraversal.Ref(0, 0, 1)));
        var placements = new double[WorldStreamObjectsFile.PLACEMENT_DOUBLES];
        placements[0] = 500;
        placements[1] = 700;
        placements[2] = 10;
        placements[3] = 1;
        placements[4] = 1;
        placements[8] = 1;
        placements[12] = 1;
        var culling = new WorldStreamPortalCulling();
        var into = new BitSet();
        // In room A (local 5, 0, 1), facing +x (yaw 0) towards the door at local x = 10.
        culling.frame(
                WorldStreamObjectCulling.View.of(
                        new TerrainCamera(505, 700, 11, 0, 0), WIDTH, HEIGHT, 3000));
        assertTrue(culling.walk(traversal, placements, 0, into));
        assertTrue(into.get(0) && into.get(1), "B through the door: " + into);
        // Facing -x, away from the door.
        culling.frame(
                WorldStreamObjectCulling.View.of(
                        new TerrainCamera(505, 700, 11, Math.PI, 0), WIDTH, HEIGHT, 3000));
        assertTrue(culling.walk(traversal, placements, 0, into));
        assertTrue(into.get(0) && !into.get(1), "B behind the camera: " + into);
        culling.frame(null);
        assertFalse(culling.walk(traversal, placements, 0, into), "No view, no walk");
    }
}
