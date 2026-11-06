package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** Bounding spheres and per-placement visibility of exterior objects; GPU- and FX-free. */
class WorldStreamObjectCullingTest {
    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int WIDTH = 800, HEIGHT = 500;
    private static final double SLACK = WorldStreamObjectCulling.RADIUS_SLACK;

    /** Looks along native +X from the origin, level: eye right is -Y, eye up is +Z. */
    private static final TerrainCamera LEVEL = new TerrainCamera(0, 0, 0, 0, 0);

    private static float[] vertex(float[] into, int at, double x, double y, double z) {
        into[at * 8] = (float) x;
        into[at * 8 + 1] = (float) y;
        into[at * 8 + 2] = (float) z;
        return into;
    }

    @Test
    void modelSphereSpansOnlyTheVerticesItsDrawnRangesReference() {
        // One mesh of three vertices; its single range draws vertices 0 and 1 only.
        var vertices = new float[3 * 8];
        vertex(vertices, 0, -1, 0, 0);
        vertex(vertices, 1, 3, 0, 0);
        vertex(vertices, 2, 1000, 1000, 1000);
        var spheres =
                WorldStreamObjectCulling.modelSpheres(
                        new int[] {0, 1},
                        new int[] {0, 3, 0, 3, 0, 1},
                        new int[] {0, 2, 0, WorldStreamObjectsFile.OPAQUE},
                        vertices,
                        new short[] {0, 1, 0},
                        () -> false);
        assertArrayEquals(new double[] {1, 0, 0, 2}, spheres, 1e-9);
    }

    @Test
    void modelSpheresStopWhenCanceled() {
        assertThrows(
                CancellationException.class,
                () ->
                        WorldStreamObjectCulling.modelSpheres(
                                new int[] {0, 0},
                                new int[0],
                                new int[0],
                                new float[0],
                                new short[0],
                                () -> true));
    }

    @Test
    void modelThatDrawsNothingHasNoSphereAndIsNeverVisible() {
        var spheres =
                WorldStreamObjectCulling.modelSpheres(
                        new int[] {0, 0},
                        new int[0],
                        new int[0],
                        new float[0],
                        new short[0],
                        () -> false);
        assertEquals(-1, spheres[3]);
        var placed =
                WorldStreamObjectCulling.placementSpheres(
                        new int[] {0}, placement(5, 0, 0, 1), spheres);
        assertFalse(view(LEVEL).visible(placed, 0));
    }

    @Test
    void placementSphereAppliesScaleRotationAndPosition() {
        var model = new double[] {1, 0, 0, 2};
        // Quarter turn about +Z: local +X maps to world +Y.
        double[] quarter = {0, -1, 0, 1, 0, 0, 0, 0, 1};
        var placements = new double[13];
        placements[0] = 100;
        placements[1] = 200;
        placements[2] = 300;
        placements[3] = 1.5;
        System.arraycopy(quarter, 0, placements, 4, 9);
        var placed = WorldStreamObjectCulling.placementSpheres(new int[] {0}, placements, model);
        assertEquals(100, placed[0], 1e-9);
        assertEquals(201.5, placed[1], 1e-9);
        assertEquals(300, placed[2], 1e-9);
        // Scaled radius plus the orthonormality margin and the absolute float slack.
        assertTrue(placed[3] >= 3 + SLACK && placed[3] < 3.001 + SLACK, "radius " + placed[3]);
    }

    @Test
    void sphereInFrontIsVisibleAndBehindIsCulled() {
        var view = view(LEVEL);
        assertTrue(view.visible(sphere(50, 0, 0, 1), 0));
        assertFalse(view.visible(sphere(-50, 0, 0, 1), 0));
    }

    @Test
    void sphereStraddlingTheNearPlaneStaysVisible() {
        var view = view(LEVEL);
        assertTrue(view.visible(sphere(-0.5, 0, 0, 1.2), 0));
        assertFalse(view.visible(sphere(-0.8, 0, 0, 1.2), 0));
    }

    /**
     * The four side planes, independently of the culling code: each frustum edge is where {@link
     * TerrainCamera#reversedProjection} maps eye space to NDC +-1. A sphere whose tangent point
     * projects exactly onto that edge is the boundary; just inside it is visible, just outside it
     * is culled.
     */
    @Test
    void sidePlanesAreTheEdgesOfTheExplorerProjection() {
        var matrix = new float[16];
        TerrainCamera.reversedProjection(WIDTH, HEIGHT).get(matrix);
        double depth = 100, r = 2;
        var view = view(LEVEL);
        // Column-major: clip.x = m[0] * right, clip.y = m[5] * up, clip.w = depth.
        for (boolean horizontal : new boolean[] {true, false}) {
            double k = 1 / matrix[horizontal ? 0 : 5];
            double norm = Math.sqrt(1 + k * k);
            for (int sign : new int[] {1, -1}) {
                for (double margin : new double[] {-0.05, 0.05}) {
                    // Center offset from the plane by r + margin along its outward normal.
                    double lateral = k * depth + (r + SLACK + margin) * norm;
                    double centerDepth = depth;
                    // The tangent point (center minus radius along the normal) projects to NDC.
                    double tangentLateral = lateral - (r + SLACK) / norm;
                    double tangentDepth = centerDepth + (r + SLACK) * k / norm;
                    double ndc =
                            (horizontal ? matrix[0] : matrix[5]) * tangentLateral / tangentDepth;
                    if (margin < 0) {
                        assertTrue(ndc < 1, "tangent inside the edge: " + ndc);
                    } else {
                        assertTrue(ndc > 1, "tangent outside the edge: " + ndc);
                    }
                    double right = horizontal ? sign * lateral : 0;
                    double up = horizontal ? 0 : sign * lateral;
                    // Eye right is native -Y, eye up native +Z at the level camera.
                    boolean visible = view.visible(sphere(centerDepth, -right, up, r), 0);
                    assertEquals(
                            margin < 0,
                            visible,
                            (horizontal ? "horizontal" : "vertical")
                                    + " sign "
                                    + sign
                                    + " margin "
                                    + margin);
                }
            }
        }
    }

    @Test
    void spheresNearEveryScreenCornerAreCulledOnlyOutside() {
        var matrix = new float[16];
        TerrainCamera.reversedProjection(WIDTH, HEIGHT).get(matrix);
        double depth = 300, r = 1;
        double kx = 1 / matrix[0], ky = 1 / matrix[5];
        var view = view(LEVEL);
        for (int sx : new int[] {1, -1}) {
            for (int sy : new int[] {1, -1}) {
                // Just inside both edges of the corner, then just outside one or the other.
                double right = sx * (kx * depth - 2), up = sy * (ky * depth - 2);
                assertTrue(view.visible(sphere(depth, -right, up, r), 0), "inside " + sx + sy);
                double outsideRight = sx * (kx * depth + 3 * Math.sqrt(1 + kx * kx));
                double outsideUp = sy * (ky * depth + 3 * Math.sqrt(1 + ky * ky));
                assertFalse(view.visible(sphere(depth, -outsideRight, up, r), 0));
                assertFalse(view.visible(sphere(depth, -right, outsideUp, r), 0));
                assertFalse(view.visible(sphere(depth, -outsideRight, outsideUp, r), 0));
            }
        }
    }

    @Test
    void cameraInsideALargeSphereAlwaysSeesIt() {
        for (double yaw : new double[] {0, 1, Math.PI, -2.5}) {
            for (double pitch : new double[] {-1.5, -0.4, 0, 0.7, 1.5}) {
                var view = view(new TerrainCamera(10, 20, 30, yaw, pitch));
                assertTrue(view.visible(sphere(12, 18, 31, 40), 0), yaw + " " + pitch);
                // Also when the center is behind the camera but the camera is inside.
                double cy = Math.cos(yaw), sy = Math.sin(yaw);
                assertTrue(
                        view.visible(sphere(10 - 20 * cy, 20 - 20 * sy, 30, 25), 0),
                        "center behind " + yaw + " " + pitch);
            }
        }
    }

    @Test
    void sphereEntirelyBeyondTheFarDiscardDepthIsCulled() {
        var view = view(LEVEL);
        double far = TerrainCamera.WHOLE_TRIANGLE_FAR;
        // Large enough that the size rule never applies here.
        assertTrue(view.visible(sphere(far + 50, 0, 0, 60), 0));
        assertFalse(view.visible(sphere(far + 70, 0, 0, 60), 0));
    }

    @Test
    void smallSpheresAreCulledBelowHalfAPixelOfProjectedRadius() {
        var view = view(LEVEL);
        double focal = TerrainCamera.focalPixels(WIDTH);
        double r = 0.25;
        // Projected radius focal * r / sqrt(depth^2 - r^2) equals the limit at this depth.
        double limit =
                Math.sqrt(
                        Math.pow(focal * r / WorldStreamObjectCulling.MIN_PIXEL_RADIUS, 2) + r * r);
        // The tested radius includes the slack, so compare against the slackened sphere.
        double rs = r - SLACK;
        assertTrue(view.visible(sphere(limit - 2, 0, 0, rs), 0));
        assertFalse(view.visible(sphere(limit + 2, 0, 0, rs), 0));
        // Twice the size, twice the distance.
        double r2 = 2 * r - SLACK;
        assertTrue(view.visible(sphere(2 * limit - 4, 0, 0, r2), 0));
        assertFalse(view.visible(sphere(2 * limit + 4, 0, 0, r2), 0));
    }

    @Test
    void sizeCullUsesEyeDepthSoCornerObjectsAreNotCulledEarly() {
        var view = view(LEVEL);
        double focal = TerrainCamera.focalPixels(WIDTH);
        double r = 0.25;
        double depth = focal * r / WorldStreamObjectCulling.MIN_PIXEL_RADIUS - 5;
        double kx = 1 / TerrainCamera.FOCAL, ky = HEIGHT / (TerrainCamera.FOCAL * WIDTH);
        // Near the screen corner the Euclidean distance is much larger than the eye depth; the
        // decision follows the depth, as the projection does.
        double right = 0.95 * kx * depth, up = 0.95 * ky * depth;
        assertTrue(depth < Math.sqrt(depth * depth + right * right + up * up) - 50);
        assertTrue(view.visible(sphere(depth, -right, up, r - SLACK), 0));
    }

    @Test
    void yawAndPitchMoveTheFrustumWithTheCamera() {
        // Yaw a quarter turn left: the camera looks along native +Y.
        var west = view(new TerrainCamera(10, 20, 30, Math.PI / 2, 0));
        assertTrue(west.visible(sphere(10, 120, 30, 1), 0));
        assertFalse(west.visible(sphere(110, 20, 30, 1), 0));
        // Pitch down: a point straight below is in view, one straight above is not.
        var down = view(new TerrainCamera(0, 0, 100, 0, -1.4));
        assertTrue(down.visible(sphere(10, 0, 0, 1), 0));
        assertFalse(down.visible(sphere(10, 0, 200, 1), 0));
    }

    @Test
    void frameAspectSetsTheVerticalExtentAndWidthTheSizeLimit() {
        var square = WorldStreamObjectCulling.View.of(LEVEL, 400, 400);
        var wide = WorldStreamObjectCulling.View.of(LEVEL, 800, 400);
        double ky = 1 / TerrainCamera.FOCAL;
        // Square: vertical half-extent at depth 100 equals the horizontal one; wide: half of it.
        assertTrue(square.visible(sphere(100, 0, 0.8 * ky * 100, 1), 0));
        assertFalse(wide.visible(sphere(100, 0, 0.8 * ky * 100, 1), 0));
        // Twice the frame width doubles the focal length in pixels.
        double r = 0.25 - SLACK;
        double limit =
                TerrainCamera.focalPixels(400) * 0.25 / WorldStreamObjectCulling.MIN_PIXEL_RADIUS;
        assertFalse(square.visible(sphere(limit + 5, 0, 0, r), 0));
        assertTrue(wide.visible(sphere(limit + 5, 0, 0, r), 0));
    }

    @Test
    void visibilityReadsTheRequestedPlacement() {
        var spheres = new double[] {-50, 0, 0, 1, 50, 0, 0, 1};
        var view = view(LEVEL);
        assertFalse(view.visible(spheres, 0));
        assertTrue(view.visible(spheres, 1));
    }

    private static WorldStreamObjectCulling.View view(TerrainCamera camera) {
        return WorldStreamObjectCulling.View.of(camera, WIDTH, HEIGHT);
    }

    /** A placement sphere as placementSpheres would build it: the radius includes the slack. */
    private static double[] sphere(double x, double y, double z, double r) {
        return new double[] {x, y, z, r + SLACK};
    }

    private static double[] placement(double x, double y, double z, double scale) {
        var p = new double[13];
        p[0] = x;
        p[1] = y;
        p[2] = z;
        p[3] = scale;
        System.arraycopy(IDENTITY, 0, p, 4, 9);
        return p;
    }
}
