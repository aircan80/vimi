package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BodyMotionTest {
    static final double DT = MovementParity.STEP_SECONDS;
    static final double EYE = MovementParity.DEFAULT_HEIGHT * BodyMotion.EYE;

    /** Steps one frame at a time for about {@code seconds}; returns the last step. */
    static BodyMotion.Step hold(
            BodyMotion motion,
            TerrainRenderer.Camera from,
            int forward,
            int turn,
            double flySpeed,
            double seconds,
            CollisionWorld world) {
        BodyMotion.Step step = null;
        var at = from;
        for (int i = 0; i < Math.round(seconds / DT); i++) {
            step = motion.step(at, forward, 0, 0, turn, 0, flySpeed, DT, world);
            at = step.camera();
        }
        return step;
    }

    @Test
    void theFirstCameraPlacesTheBodyOnTheGroundBelowIt() {
        var world = new TriangleWorld().floor(-100, -100, 100, 100, 3);
        var motion = new BodyMotion();
        var step =
                motion.step(
                        new TerrainRenderer.Camera(0, 0, 80, 0, 0), 0, 0, 0, 0, 0, 0, DT, world);
        assertTrue(motion.body().grounded());
        assertEquals(3, motion.body().z(), .02);
        assertTrue(step.moved());
        // Third person: the camera sits the start distance behind the eye, level with it.
        assertEquals(-MovementParity.CAMERA_START_DISTANCE, step.camera().x(), 1e-6);
        assertEquals(3 + EYE, step.camera().z(), .02);
        assertEquals(WalkController.Status.STANDING, step.status());
    }

    @Test
    void ownCamerasKeepTheBodyAndForeignOnesMoveIt() {
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0);
        var motion = new BodyMotion();
        var shown =
                motion.step(new TerrainRenderer.Camera(0, 0, 10, 0, 0), 0, 0, 0, 0, 0, 0, DT, world)
                        .camera();
        var walked = hold(motion, shown, 1, 0, 0, 1, world);
        assertEquals(MovementParity.RUN_SPEED, motion.body().x(), .05);
        assertTrue(walked.moved());
        var still = motion.step(walked.camera(), 0, 0, 0, 0, 0, 0, DT, world);
        assertFalse(still.moved(), "nothing held, nothing moves");
        // A bookmark jump hands in a different camera: the body goes there.
        motion.step(new TerrainRenderer.Camera(200, 100, 50, 1, 0), 0, 0, 0, 0, 0, 0, DT, world);
        assertEquals(200, motion.body().x(), 1e-6);
        assertEquals(100, motion.body().y(), 1e-6);
        assertEquals(0, motion.body().z(), .02);
        assertEquals(1, motion.yaw(), 1e-9);
    }

    @Test
    void theViewTurnsTheBodyAndTheCameraFollowsBehind() {
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0);
        var motion = new BodyMotion();
        var shown =
                motion.step(new TerrainRenderer.Camera(0, 0, 10, 0, 0), 0, 0, 0, 0, 0, 0, DT, world)
                        .camera();
        motion.look(Math.PI / 2, 0);
        var step = hold(motion, shown, 1, 0, 0, 1, world);
        assertEquals(
                MovementParity.RUN_SPEED,
                motion.body().y(),
                .05,
                "facing +Y after a quarter turn left");
        assertEquals(
                motion.body().y() - MovementParity.CAMERA_START_DISTANCE, step.camera().y(), .01);
        // Keyboard turning at the turn rate.
        hold(motion, step.camera(), 0, 1, 0, .5, world);
        assertEquals(Math.PI / 2 + MovementParity.TURN_RATE * .5, motion.yaw(), 1e-9);
    }

    @Test
    void theCameraIsPulledInByGeometryAndEasesBackOut() {
        // A wall 3 yd behind the body.
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0).wallX(-3, -50, 50, -1, 30);
        var motion = new BodyMotion();
        var step =
                motion.step(
                        new TerrainRenderer.Camera(0, 0, 10, 0, 0), 0, 0, 0, 0, 0, 0, DT, world);
        assertTrue(
                step.camera().x() > -3 + FollowCamera.RADIUS - 1e-6,
                "in front of the wall: " + step.camera().x());
        assertTrue(step.camera().x() < -2, "but as far back as it fits: " + step.camera().x());
        // Looking steeply down from above puts the camera high; looking up pulls it in above the
        // ground.
        motion.look(0, 1.4);
        step = motion.step(step.camera(), 0, 0, 0, 0, 0, 0, DT, world);
        assertTrue(
                step.camera().z() >= FollowCamera.RADIUS,
                "never under the ground: " + step.camera().z());
        // Walking away from the wall: the camera eases back to its distance.
        motion.look(0, -1.4);
        var at = step.camera();
        for (int i = 0; i < 120; i++) {
            at = motion.step(at, 1, 0, 0, 0, 0, 0, DT, world).camera();
        }
        assertEquals(motion.body().x() - MovementParity.CAMERA_START_DISTANCE, at.x(), .05);
    }

    @Test
    void zoomingToZeroIsFirstPerson() {
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0);
        var motion = new BodyMotion();
        var shown =
                motion.step(new TerrainRenderer.Camera(0, 0, 10, 0, 0), 0, 0, 0, 0, 0, 0, DT, world)
                        .camera();
        motion.zoom(-100);
        assertTrue(motion.camera().firstPerson());
        var step = motion.step(shown, 0, 0, 0, 0, 0, 0, DT, world);
        assertEquals(0, step.camera().x(), 1e-9);
        assertEquals(EYE, step.camera().z(), .02);
        motion.zoom(1_000);
        assertEquals(FollowCamera.MAX_DISTANCE, motion.camera().distance());
    }

    @Test
    void flyingFollowsThePitch() {
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0);
        var motion = new BodyMotion();
        motion.mode(WalkController.Mode.FLY);
        var shown =
                motion.step(
                                new TerrainRenderer.Camera(0, 0, 50, 0, .5),
                                0,
                                0,
                                0,
                                0,
                                0,
                                0,
                                DT,
                                world)
                        .camera();
        assertEquals(
                50 - EYE, motion.body().z(), 1e-6, "a flying body stays at the camera's height");
        hold(motion, shown, 1, 0, MovementParity.TAXI_SPEED, 1, world);
        assertEquals(50 - EYE + MovementParity.TAXI_SPEED * Math.sin(.5), motion.body().z(), .1);
    }
}
