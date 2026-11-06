package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExplorerMotionTest {
    private static final TerrainGround GROUND =
            TerrainGround.of(List.of(TerrainGround.Tile.of(TerrainGroundTest.tile(false))));
    private static final ExplorerMotion.Input FORWARD =
            new ExplorerMotion.Input(1, 0, 0, 0, 0, false);

    private static ExplorerMotion exact(ExplorerMotion.Mode mode) {
        var motion = new ExplorerMotion();
        motion.smooth(false);
        motion.mode(mode);
        return motion;
    }

    /** A point well inside the authored tile, heading toward native -X (yaw pi). */
    private static TerrainRenderer.Camera inside(double z) {
        return new TerrainRenderer.Camera(
                TerrainGroundTest.ORIGIN_X - 100, TerrainGroundTest.ORIGIN_Y - 100, z, Math.PI, 0);
    }

    @Test
    void exactFlyPreservesEstablishedDisplacementAndSprint() {
        var motion = exact(ExplorerMotion.Mode.FLY);
        var start = new TerrainRenderer.Camera(10, 20, 500, 0, 0);
        var up = motion.step(start, new ExplorerMotion.Input(0, 0, 5, 0, 0, false), .1, 80, GROUND);
        assertEquals(508, up.camera().z(), 1e-9);
        assertEquals(ExplorerMotion.Ground.FREE, up.ground());
        var sprint =
                motion.step(start, new ExplorerMotion.Input(1, 0, 0, 0, 0, true), .1, 80, GROUND);
        assertEquals(10 + 32, sprint.camera().x(), 1e-9);
        var clampedDt = motion.step(start, FORWARD, 3, 80, GROUND);
        assertEquals(18, clampedDt.camera().x(), 1e-9);
        var still = motion.step(start, ExplorerMotion.Input.NONE, .1, 80, GROUND);
        assertFalse(still.moved());
        assertFalse(motion.coasting());
    }

    @Test
    void smoothFlyAcceleratesThenCoastsToRest() {
        var motion = new ExplorerMotion();
        var camera = new TerrainRenderer.Camera(0, 0, 500, 0, 0);
        double first = motion.step(camera, FORWARD, 1 / 60.0, 80, GROUND).camera().x();
        assertTrue(first > 0 && first < 80 / 60.0, "first frame eases in: " + first);
        for (int i = 0; i < 120; i++) {
            camera = motion.step(camera, FORWARD, 1 / 60.0, 80, GROUND).camera();
        }
        double before = camera.x();
        camera = motion.step(camera, FORWARD, 1 / 60.0, 80, GROUND).camera();
        assertEquals(80 / 60.0, camera.x() - before, 1e-6, "converges to the target speed");
        double released = camera.x();
        int frames = 0;
        while (motion.coasting() && frames < 600) {
            camera = motion.step(camera, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND).camera();
            frames++;
        }
        assertFalse(motion.coasting(), "coasting terminates");
        assertTrue(camera.x() > released && camera.x() - released < 80, "bounded coast");
        motion.step(camera, FORWARD, 1 / 60.0, 80, GROUND);
        motion.reset();
        assertFalse(motion.coasting());
    }

    @Test
    void walkKeepsEyeAboveRenderedSurfaceAndIgnoresVerticalKeys() {
        var motion = exact(ExplorerMotion.Mode.WALK);
        var camera = inside(-1000);
        for (int i = 0; i < 40; i++) {
            var step =
                    motion.step(
                            camera, new ExplorerMotion.Input(1, 1, 1, 0, 0, false), .1, 80, GROUND);
            camera = step.camera();
            assertEquals(ExplorerMotion.Ground.FOLLOWING, step.ground());
            assertEquals(
                    GROUND.heightAt(camera.x(), camera.y()) + ExplorerMotion.EYE_HEIGHT,
                    camera.z(),
                    1e-9);
        }
        assertEquals(7, motion.speed(80, false));
        assertEquals(14, motion.speed(80, true));
        var start = inside(0);
        double moved = motion.step(start, FORWARD, .1, 80, GROUND).camera().x() - start.x();
        assertEquals(-.7, moved, 1e-9, "walk speed is independent of flight speed");
    }

    @Test
    void smoothWalkLiftsImmediatelyAndSettlesDownward() {
        var motion = new ExplorerMotion();
        motion.mode(ExplorerMotion.Mode.WALK);
        var high = inside(400);
        double eye = GROUND.heightAt(high.x(), high.y()) + ExplorerMotion.EYE_HEIGHT;
        var settling = motion.step(high, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND);
        assertTrue(settling.moved());
        assertTrue(settling.camera().z() < 400 && settling.camera().z() > eye);
        var camera = settling.camera();
        for (int i = 0; i < 200; i++) {
            camera = motion.step(camera, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND).camera();
        }
        assertEquals(eye, camera.z(), 1e-9);
        assertFalse(motion.step(camera, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND).moved());
        var buried = motion.step(inside(-50), ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND);
        assertEquals(eye, buried.camera().z(), 1e-9, "never below the surface eye height");
    }

    @Test
    void walkWithoutQualifiedGroundHoldsAltitude() {
        var motion = exact(ExplorerMotion.Mode.WALK);
        var outside = new TerrainRenderer.Camera(5000, 5000, 77, 0, 0);
        var step = motion.step(outside, FORWARD, .1, 80, GROUND);
        assertEquals(ExplorerMotion.Ground.UNAVAILABLE, step.ground());
        assertEquals(77, step.camera().z());
        var empty = motion.step(inside(77), FORWARD, .1, 80, TerrainGround.empty());
        assertEquals(ExplorerMotion.Ground.UNAVAILABLE, empty.ground());
        assertEquals(77, empty.camera().z());
    }

    @Test
    void dollyFollowsViewInFlightAndHeadingWhileWalking() {
        var fly = exact(ExplorerMotion.Mode.FLY);
        var view = new TerrainRenderer.Camera(0, 0, 100, 0, -.5);
        var flown = fly.dolly(view, 10, GROUND).camera();
        assertEquals(Math.cos(-.5) * 10, flown.x(), 1e-9);
        assertEquals(100 + Math.sin(-.5) * 10, flown.z(), 1e-9);
        var walk = exact(ExplorerMotion.Mode.WALK);
        var start =
                new TerrainRenderer.Camera(
                        TerrainGroundTest.ORIGIN_X - 100,
                        TerrainGroundTest.ORIGIN_Y - 100,
                        0,
                        Math.PI,
                        -.5);
        var walked = walk.dolly(start, 10, GROUND);
        assertEquals(start.x() - 10, walked.camera().x(), 1e-9);
        assertEquals(ExplorerMotion.Ground.FOLLOWING, walked.ground());
        assertEquals(
                GROUND.heightAt(walked.camera().x(), walked.camera().y())
                        + ExplorerMotion.EYE_HEIGHT,
                walked.camera().z(),
                1e-9);
    }

    @Test
    void boundsAndInvalidStepsAreRejectedOrClamped() {
        var motion = exact(ExplorerMotion.Mode.FLY);
        var edge = new TerrainRenderer.Camera(999_999, 0, 0, 0, 0);
        assertEquals(1_000_000, motion.step(edge, FORWARD, .1, 800, GROUND).camera().x());
        assertThrows(
                IllegalArgumentException.class,
                () -> motion.step(edge, FORWARD, Double.NaN, 80, GROUND));
        assertThrows(
                IllegalArgumentException.class, () -> motion.step(edge, FORWARD, -1, 80, GROUND));
        assertThrows(IllegalArgumentException.class, () -> motion.dolly(edge, Double.NaN, GROUND));
        assertEquals(1, new ExplorerMotion.Input(5, -3, 0, 0, 0, false).forward());
        assertEquals(-1, new ExplorerMotion.Input(5, -3, 0, 0, 0, false).strafe());
    }

    @Test
    void restingMarksOnlyTheFinalSettledStep() {
        var motion = new ExplorerMotion();
        motion.mode(ExplorerMotion.Mode.WALK);
        var camera = inside(400);
        var first = motion.step(camera, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND);
        assertTrue(first.moved() && !first.resting(), "settling ticks are not resting");
        camera = first.camera();
        ExplorerMotion.Step last = first;
        for (int i = 0; i < 400 && !last.resting(); i++) {
            last = motion.step(camera, ExplorerMotion.Input.NONE, 1 / 60.0, 80, GROUND);
            camera = last.camera();
        }
        assertTrue(last.resting() && last.moved(), "the final settling step moves and rests");
        var turning =
                motion.step(camera, new ExplorerMotion.Input(0, 0, 0, 1, 0, false), .1, 80, GROUND);
        assertFalse(turning.resting(), "turning is not resting");
        var fly = new ExplorerMotion();
        assertFalse(fly.step(camera, FORWARD, .1, 80, GROUND).resting());
    }

    @Test
    void keyboardTurnsHalfATurnPerSecondAndTiltsAtHalfThat() {
        var motion = exact(ExplorerMotion.Mode.FLY);
        var start = new TerrainRenderer.Camera(0, 0, 50, 0, 0);
        var left =
                motion.step(start, new ExplorerMotion.Input(0, 0, 0, 1, 0, false), .1, 80, GROUND);
        assertEquals(Math.PI * .1, left.camera().yaw(), 1e-12);
        var right =
                motion.step(start, new ExplorerMotion.Input(0, 0, 0, -1, 0, false), .1, 80, GROUND);
        assertEquals(-Math.PI * .1, right.camera().yaw(), 1e-12);
        var up = motion.step(start, new ExplorerMotion.Input(0, 0, 0, 0, 1, false), .1, 80, GROUND);
        assertEquals(Math.PI / 2 * .1, up.camera().pitch(), 1e-12);
        assertEquals(0, up.camera().yaw());
        // A long frame is capped at 0.1 s, so one hitch never spins the view.
        var hitch =
                motion.step(start, new ExplorerMotion.Input(0, 0, 0, 1, 0, false), 2, 80, GROUND);
        assertEquals(ExplorerMotion.TURN_RATE * .1, hitch.camera().yaw(), 1e-12);
    }
}
