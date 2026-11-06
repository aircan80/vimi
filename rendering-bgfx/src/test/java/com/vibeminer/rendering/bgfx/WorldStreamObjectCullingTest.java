package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import org.junit.jupiter.api.Test;

/** Bounding spheres and per-placement visibility of exterior objects; GPU- and FX-free. */
class WorldStreamObjectCullingTest {
    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int WIDTH = 800, HEIGHT = 500;

    /** Looks along native +X from the origin, level. */
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
                        new short[] {0, 1, 0});
        assertArrayEquals(new double[] {1, 0, 0, 2}, spheres, 1e-9);
    }

    @Test
    void modelThatDrawsNothingHasNoSphereAndIsNeverVisible() {
        var spheres =
                WorldStreamObjectCulling.modelSpheres(
                        new int[] {0, 0}, new int[0], new int[0], new float[0], new short[0]);
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
        // Radius is scaled, with a margin for the validated orthonormality tolerance.
        assertTrue(placed[3] >= 3 && placed[3] < 3.001, "radius " + placed[3]);
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

    @Test
    void sidePlanesFollowTheExplorerProjection() {
        var view = view(LEVEL);
        // Horizontal half-extent at depth d is d / 1.6 (eye right is native -Y at yaw 0).
        double edge = 100 / 1.6;
        assertTrue(view.visible(sphere(100, -(edge + 0.5), 0, 1), 0), "touching right edge");
        assertFalse(view.visible(sphere(100, -(edge + 3), 0, 1), 0), "beyond right edge");
        assertTrue(view.visible(sphere(100, edge + 0.5, 0, 1), 0), "touching left edge");
        assertFalse(view.visible(sphere(100, edge + 3, 0, 1), 0), "beyond left edge");
        // Vertical half-extent is d * height / (1.6 * width).
        double top = 100.0 * HEIGHT / (1.6 * WIDTH);
        assertTrue(view.visible(sphere(100, 0, top + 0.5, 1), 0), "touching top");
        assertFalse(view.visible(sphere(100, 0, top + 3, 1), 0), "beyond top");
        assertFalse(view.visible(sphere(100, 0, -(top + 3), 1), 0), "beyond bottom");
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
    void smallSpheresAreCulledOnceTheyProjectUnderTheMinimumPixelRadius() {
        var view = view(LEVEL);
        double focal = 0.8 * WIDTH;
        // A 1 yd sphere projects to focal / d pixels of radius.
        double limit = focal / WorldStreamObjectCulling.MIN_PIXEL_RADIUS;
        assertTrue(view.visible(sphere(limit - 5, 0, 0, 1), 0));
        assertFalse(view.visible(sphere(limit + 5, 0, 0, 1), 0));
        // Twice the size, twice the distance.
        assertTrue(view.visible(sphere(2 * limit - 10, 0, 0, 2), 0));
        // The distance is Euclidean, so turning the camera never changes the size decision.
        var turned = view(new TerrainCamera(0, 0, 0, Math.PI / 2, 0));
        assertFalse(turned.visible(sphere(0, limit + 5, 0, 1), 0));
        assertTrue(turned.visible(sphere(0, limit - 5, 0, 1), 0));
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
    void wideFramesWidenTheVerticalCullAndRaiseTheSizeLimit() {
        var small = WorldStreamObjectCulling.View.of(LEVEL, 400, 400);
        var large = WorldStreamObjectCulling.View.of(LEVEL, 800, 400);
        // At 400 x 400 the vertical half-extent at depth 100 is 62.5; at 800 x 400 it is 31.25.
        assertTrue(small.visible(sphere(100, 0, 50, 1), 0));
        assertFalse(large.visible(sphere(100, 0, 50, 1), 0));
        // Twice the frame width doubles the focal length in pixels.
        double limit = 0.8 * 400 / WorldStreamObjectCulling.MIN_PIXEL_RADIUS;
        assertFalse(small.visible(sphere(limit + 5, 0, 0, 1), 0));
        assertTrue(large.visible(sphere(limit + 5, 0, 0, 1), 0));
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

    private static double[] sphere(double x, double y, double z, double r) {
        return new double[] {x, y, z, r};
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
