package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WalkControllerTest {
    static final double DT = MovementParity.STEP_SECONDS;
    static final WalkController.Input IDLE = input(0, 0, 0, 0, false);

    static WalkController.Input input(
            int forward, int strafe, int vertical, double yaw, boolean jump) {
        return new WalkController.Input(forward, strafe, vertical, yaw, 0, jump, 0);
    }

    static WalkController standing(CollisionWorld world, double x, double y, double z) {
        var body = new WalkController();
        body.place(x, y, z, 10, world);
        assertTrue(body.grounded(), "placed on the ground");
        return body;
    }

    /** Runs whole fixed steps for about {@code seconds}. */
    static void run(
            WalkController body, WalkController.Input input, double seconds, CollisionWorld world) {
        int steps = (int) Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            body.step(input, DT, world);
        }
    }

    @Test
    void theParityTableIsPinned() {
        assertEquals(7.0, MovementParity.RUN_SPEED);
        assertEquals(2.5, MovementParity.WALK_SPEED);
        assertEquals(4.5, MovementParity.BACKPEDAL_SPEED);
        assertEquals(4.722, MovementParity.SWIM_SPEED);
        assertEquals(2.5, MovementParity.SWIM_BACK_SPEED);
        assertEquals(Math.PI, MovementParity.TURN_RATE);
        assertEquals(19.291105, MovementParity.GRAVITY);
        assertEquals(60.148003, MovementParity.TERMINAL_VELOCITY);
        assertEquals(7.95554, MovementParity.JUMP_VELOCITY);
        assertEquals(32, MovementParity.TAXI_SPEED);
        assertEquals(50, MovementParity.SLOPE_LIMIT_DEGREES);
        assertEquals(1.0, MovementParity.STEP_HEIGHT);
        assertEquals(15, MovementParity.CAMERA_BASE_DISTANCE);
        assertEquals(39, FollowCamera.MAX_DISTANCE, 1e-9);
        assertEquals(1.0 / 60, MovementParity.STEP_SECONDS);
        // Jump apex v^2 / 2g.
        assertEquals(
                1.64,
                Math.pow(MovementParity.JUMP_VELOCITY, 2) / (2 * MovementParity.GRAVITY),
                .01);
    }

    @Test
    void placingStandsOnTheGroundBelow() {
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 10);
        var body = new WalkController();
        body.place(0, 0, 200, 500, world);
        assertTrue(body.grounded());
        assertEquals(10, body.z(), .02);
        assertEquals(WalkController.Status.STANDING, body.status());
    }

    @Test
    void runningBackpedallingAndStrafingUseTheirSpeeds() {
        var world = new TriangleWorld().floor(-100, -100, 100, 100, 0);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 1, world);
        assertEquals(MovementParity.RUN_SPEED, body.x(), .02);
        assertEquals(0, body.y(), 1e-9);
        assertEquals(WalkController.Status.MOVING, body.status());
        body = standing(world, 0, 0, 1);
        run(body, input(-1, 0, 0, 0, false), 1, world);
        assertEquals(-MovementParity.BACKPEDAL_SPEED, body.x(), .02);
        body = standing(world, 0, 0, 1);
        // Strafe right with yaw 0 (facing +X) moves toward -Y.
        run(body, input(0, 1, 0, 0, false), 1, world);
        assertEquals(-MovementParity.RUN_SPEED, body.y(), .02);
        body = standing(world, 0, 0, 1);
        run(body, input(1, 1, 0, 0, false), 1, world);
        assertEquals(
                MovementParity.RUN_SPEED,
                Math.hypot(body.x(), body.y()),
                .02,
                "diagonal is not faster");
    }

    @Test
    void aFallTakesTheTimeGravityAndTerminalVelocityGive() {
        double height = 150;
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 0);
        var body = new WalkController();
        body.place(0, 0, height, 0, world);
        int steps = 0;
        while (!body.grounded() && steps < 10_000) {
            body.step(IDLE, DT, world);
            steps++;
        }
        double g = MovementParity.GRAVITY, terminal = MovementParity.TERMINAL_VELOCITY;
        double reach = terminal / g, fallen = .5 * g * reach * reach;
        double expected = reach + (height - fallen) / terminal;
        assertEquals(expected, steps * DT, 3 * DT, "landing time");
        assertEquals(0, body.z(), .02);
        assertEquals(0, body.verticalSpeed());
    }

    @Test
    void aJumpRisesToItsApexAndLandsAfterTwiceTheRiseTime() {
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 0);
        var body = standing(world, 0, 0, 1);
        body.step(input(0, 0, 1, 0, true), DT, world);
        assertFalse(body.grounded());
        double apex = 0;
        int steps = 1;
        while (!body.grounded() && steps < 1000) {
            body.step(IDLE, DT, world);
            apex = Math.max(apex, body.z());
            steps++;
        }
        double v = MovementParity.JUMP_VELOCITY, g = MovementParity.GRAVITY;
        assertEquals(v * v / (2 * g), apex, .06);
        assertEquals(2 * v / g, steps * DT, 3 * DT);
    }

    @Test
    void aJumpKeepsItsTakeOffVelocity() {
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 0);
        var body = standing(world, 0, 0, 1);
        body.step(input(1, 0, 1, 0, true), DT, world);
        while (!body.grounded()) {
            // Letting go mid-air changes nothing: no air steering.
            body.step(IDLE, DT, world);
        }
        double air = 2 * MovementParity.JUMP_VELOCITY / MovementParity.GRAVITY;
        assertEquals(MovementParity.RUN_SPEED * air, body.x(), .2);
    }

    @Test
    void wallsStopTheBodyAndDiagonalsSlideAlongThem() {
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 0).wallX(5, -50, 50, -1, 20);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, world);
        assertEquals(
                5 - MovementParity.DEFAULT_RADIUS, body.x(), .03, "stops one radius from the wall");
        assertEquals(0, body.y(), 1e-6);
        body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, Math.PI / 4, false), 2, world);
        assertEquals(5 - MovementParity.DEFAULT_RADIUS, body.x(), .03);
        assertTrue(body.y() > 5, "slides along the wall: " + body.y());
    }

    @Test
    void gentleRampsAreWalkedUpAndSteepOnesAreNot() {
        var gentle = new TriangleWorld().floor(-50, -50, 2, 50, 0).ramp(2, -50, 50, 0, 30, 40);
        var body = standing(gentle, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, gentle);
        assertTrue(
                body.x() > 2 + 6 * Math.cos(Math.toRadians(30)),
                "climbs a 30 degree ramp: " + body.x());
        assertTrue(body.grounded());
        assertEquals(
                (body.x() - 2) * Math.tan(Math.toRadians(30)), body.z(), .2, "stays on the ramp");

        var steep = new TriangleWorld().floor(-50, -50, 2, 50, 0).ramp(2, -50, 50, 0, 65, 40);
        body = standing(steep, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, steep);
        assertTrue(body.x() < 2.5, "a 65 degree slope is a wall: " + body.x());
        assertTrue(body.z() < MovementParity.STEP_HEIGHT + .1, "and is not climbed: " + body.z());
    }

    @Test
    void steepGroundSlidesTheBodyDown() {
        var world = new TriangleWorld().floor(-50, -50, 0, 50, 0).ramp(0, -50, 50, 0, 65, 20);
        var body = new WalkController();
        // Standing on the slope 4 yd up.
        double x = 4 / Math.tan(Math.toRadians(65));
        body.place(x, 0, 6, 10, world);
        run(body, IDLE, 3, world);
        assertTrue(
                body.x() < x && body.z() < 1, "slid to the bottom: " + body.x() + ", " + body.z());
        assertTrue(body.grounded());
    }

    @Test
    void stepsUpToTheStepHeightAreClimbedAndTallerOnesBlock() {
        double low = .6;
        var world = new TriangleWorld().floor(-50, -50, 50, 50, 0).box(4, -50, 20, 50, 0, low);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, world);
        assertTrue(body.x() > 6, "walks up a low step: " + body.x());
        assertEquals(low, body.z(), .03);

        var tall = new TriangleWorld().floor(-50, -50, 50, 50, 0).box(4, -50, 20, 50, 0, 1.4);
        body = standing(tall, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, tall);
        assertEquals(4 - MovementParity.DEFAULT_RADIUS, body.x(), .05, "a 1.4 yd ledge blocks");
        assertEquals(0, body.z(), .03);
    }

    @Test
    void walkingOffALedgeFallsAndHolesAreOpen() {
        var ledge = new TriangleWorld().floor(-50, -50, 5, 50, 10).floor(5, -50, 60, 50, 0);
        var body = standing(ledge, 0, 0, 11);
        run(body, input(1, 0, 0, 0, false), 1.5, ledge);
        assertTrue(body.x() > 7, "kept running over the edge: " + body.x());
        run(body, IDLE, 2, ledge);
        assertTrue(body.grounded());
        assertEquals(0, body.z(), .03, "landed on the lower floor");

        // A floor with a 6 x 6 hole (like a terrain hole) over a cave floor 20 yd below.
        var holed =
                new TriangleWorld()
                        .floor(-50, -50, -3, 50, 0)
                        .floor(3, -50, 50, 50, 0)
                        .floor(-3, -50, 3, -3, 0)
                        .floor(-3, 3, 3, 50, 0)
                        .floor(-50, -50, 50, 50, -20);
        body = new WalkController();
        body.place(0, 0, 5, 100, holed);
        assertEquals(-20, body.z(), .03, "falls through the hole to the cave floor");
    }

    @Test
    void fallingOutOfTheWorldPutsTheBodyBack() {
        var world = new TriangleWorld().floor(-50, -50, 5, 50, 0);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 1.5, world);
        assertFalse(body.grounded());
        int steps = 0;
        while (body.status() != WalkController.Status.RETURNED && steps < 5_000) {
            body.step(IDLE, DT, world);
            steps++;
        }
        assertEquals(WalkController.Status.RETURNED, body.status());
        assertTrue(
                body.x() <= 5 + MovementParity.DEFAULT_RADIUS && body.x() > 3,
                "back where it last stood, at most a radius past the edge: " + body.x());
        // At the rim the rounded bottom dips at most r (1 - cos(slope limit)) below the floor.
        double dip =
                MovementParity.DEFAULT_RADIUS
                        * (1 - Math.cos(Math.toRadians(MovementParity.SLOPE_LIMIT_DEGREES)));
        assertEquals(0, body.z(), dip + .01);
    }

    @Test
    void unknownCollisionIsWaitedFor() {
        var world = new TriangleWorld().floor(-100, -100, 100, 100, 0).known(10);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 3, world);
        assertTrue(body.x() < 10 && body.x() > 8, "stops where collision is unknown: " + body.x());
        assertEquals(0, body.z(), .03);

        var floating = new WalkController();
        floating.place(50, 50, 30, 0, world);
        run(floating, IDLE, 1, world);
        assertEquals(30, floating.z(), 1e-9, "does not fall where its footprint is unknown");
        assertEquals(WalkController.Status.LOADING, floating.status());
    }

    @Test
    void flyingHasNoGravityAndSlidesAlongTheGround() {
        var world = new TriangleWorld().floor(-500, -500, 500, 500, 0);
        var body = new WalkController();
        body.mode(WalkController.Mode.FLY);
        body.place(0, 0, 50, 0, world);
        run(body, IDLE, 1, world);
        assertEquals(50, body.z(), 1e-9, "hovers");
        assertEquals(WalkController.Status.FLYING, body.status());
        var dive =
                new WalkController.Input(
                        1, 0, 0, 0, -Math.PI / 4, false, MovementParity.TAXI_SPEED);
        run(body, dive, 1, world);
        double along = MovementParity.TAXI_SPEED * Math.cos(Math.PI / 4);
        assertEquals(along, body.x(), .1);
        assertEquals(50 - along, body.z(), .1);
        run(body, dive, 3, world);
        assertTrue(
                body.z() >= -1e-6 && body.z() < .05,
                "skims the ground, never under it: " + body.z());
        assertTrue(body.x() > 3 * along, "and keeps going forward: " + body.x());
        var climb = new WalkController.Input(0, 0, 1, 0, 0, false, 200);
        run(body, climb, .5, world);
        assertEquals(100, body.z(), .5, "flight speed comes from the input");
    }

    @Test
    void invalidInputIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new WalkController.Input(0, 0, 0, Double.NaN, 0, false, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WalkController.Input(0, 0, 0, 0, 0, false, -1));
        assertThrows(IllegalArgumentException.class, () -> new CapsuleCollision.Shape(1, 1));
        var body = new WalkController();
        assertThrows(
                IllegalArgumentException.class, () -> body.step(IDLE, -1, CollisionWorld.NONE));
        assertFalse(body.step(IDLE, 1, CollisionWorld.NONE), "unplaced bodies do not move");
    }

    @Test
    void sweepsFindPlaneEdgeAndVertexContacts() {
        var collision = new CapsuleCollision();
        var shape = new CapsuleCollision.Shape(.5, 1);
        var world = new TriangleWorld().triangle(0, 0, 0, 10, 0, 0, 0, 10, 0);
        collision.gather(world, shape, 2, 2, 5, 0, 0, -10, 1);
        var down = collision.sweep(shape, 2, 2, 5, 0, 0, -10);
        assertNotNull(down);
        assertEquals(.5, down.t(), 1e-9, "the feet reach the plane after 5 of 10 yd");
        assertEquals(1, down.nz(), 1e-9);
        assertNull(collision.sweep(shape, 2, 2, 5, 0, 0, 10), "moving away never hits");
        // Toward the hypotenuse edge from outside the triangle, level with it.
        collision.gather(world, shape, 8, 8, -.5, -10, -10, 0, 1);
        var edge = collision.sweep(shape, 8, 8, -.5, -10, -10, 0);
        assertNotNull(edge);
        double toEdge = (16 / Math.sqrt(2) - 10 / Math.sqrt(2)) - .5;
        assertEquals(toEdge, edge.t() * Math.sqrt(200), 1e-6);
        assertEquals(Math.sqrt(.5), edge.nx(), 1e-6);
        // Toward the corner at the origin along the diagonal, level with it.
        collision.gather(world, shape, -5, -5, -.5, 10, 10, 0, 1);
        var corner = collision.sweep(shape, -5, -5, -.5, 10, 10, 0);
        assertNotNull(corner);
        assertEquals(Math.sqrt(50) - .5, corner.t() * Math.sqrt(200), 1e-6);
    }

    @Test
    void theSameTimeInDifferentFrameSplitsGivesTheSamePath() {
        var world = new TriangleWorld().floor(-100, -100, 2, 100, 0).ramp(2, -100, 100, 0, 30, 40);
        var a = standing(world, 0, 0, 1);
        var b = standing(world, 0, 0, 1);
        var in = input(1, 1, 0, .3, false);
        for (int i = 0; i < 120; i++) {
            a.step(in, DT, world);
        }
        // The same two seconds in uneven frames: 7 ms, 23 ms, 41 ms, repeating.
        double[] frames = {.007, .023, .041};
        double total = 0;
        for (int i = 0; total + 1e-12 < 2 - .041; i++) {
            b.step(in, frames[i % 3], world);
            total += frames[i % 3];
        }
        b.step(in, 2 - total, world);
        assertEquals(a.x(), b.x(), 1e-9);
        assertEquals(a.y(), b.y(), 1e-9);
        assertEquals(a.z(), b.z(), 1e-9);
    }

    @Test
    void fastFlightNeverPassesAThinWallFromEitherSide() {
        // One two-sided quad, no thickness, and 400 yd/s: about 6.7 yd per step.
        var world = new TriangleWorld().wallX(0, -50, 50, -50, 50);
        for (int side : new int[] {-1, 1}) {
            var body = new WalkController();
            body.mode(WalkController.Mode.FLY);
            body.place(side * 20, 0, 0, 0, world);
            var toward = new WalkController.Input(1, 0, 0, side > 0 ? Math.PI : 0, 0, false, 400);
            run(body, toward, 1, world);
            assertTrue(
                    side * body.x() >= MovementParity.DEFAULT_RADIUS - .02,
                    "stays on its side " + side + ": " + body.x());
        }
    }

    @Test
    void groundAtExactlyTheSlopeLimitIsWalkable() {
        double limit = MovementParity.SLOPE_LIMIT_DEGREES;
        var world = new TriangleWorld().floor(-50, -50, 2, 50, 0).ramp(2, -50, 50, 0, limit, 40);
        var body = standing(world, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, world);
        assertTrue(body.x() > 6, "walks up the limit slope: " + body.x());
        var steeper =
                new TriangleWorld().floor(-50, -50, 2, 50, 0).ramp(2, -50, 50, 0, limit + .5, 40);
        body = standing(steeper, 0, 0, 1);
        run(body, input(1, 0, 0, 0, false), 2, steeper);
        assertTrue(body.x() < 3, "half a degree steeper is a wall: " + body.x());
    }

    @Test
    void aBodyStartingInsideACornerIsPushedOutAndCannotPassTheWalls() {
        // Walls along x = 0 and y = 0 meeting in a corner; the body starts overlapping both.
        var world =
                new TriangleWorld()
                        .floor(-50, -50, 50, 50, 0)
                        .wallX(0, -50, 50, -1, 20)
                        .quad(
                                new double[] {-50, 0, -1},
                                new double[] {50, 0, -1},
                                new double[] {50, 0, 20},
                                new double[] {-50, 0, 20});
        var body = new WalkController();
        body.place(-.2, -.2, .01, 5, world);
        run(body, IDLE, .1, world);
        assertTrue(
                body.x() <= -MovementParity.DEFAULT_RADIUS + .02
                        && body.y() <= -MovementParity.DEFAULT_RADIUS + .02,
                "pushed out of both walls: " + body.x() + ", " + body.y());
        run(body, input(1, 0, 0, Math.PI / 4, false), 2, world);
        assertTrue(
                body.x() < 0 && body.y() < 0,
                "never through the corner: " + body.x() + ", " + body.y());
    }
}
