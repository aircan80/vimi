package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WalkControllerSwimTest {
    static final double DT = MovementParity.STEP_SECONDS;
    static final double SURFACE = -2;

    /**
     * A lake: a shore at height 0 west of x = -20 (toward -X), a 26.6 degree bed down to -10 at x =
     * 0, then a flat bed at -10. The water surface is at -2 from x = -40 to 100.
     */
    static final TriangleWorld LAKE =
            new TriangleWorld()
                    .floor(-100, -50, -20, 50, 0)
                    .quad(
                            new double[] {-20, -50, 0},
                            new double[] {0, -50, -10},
                            new double[] {0, 50, -10},
                            new double[] {-20, 50, 0})
                    .floor(0, -50, 100, 50, -10);

    static final LiquidSurface WATER =
            LiquidSurface.of(
                    List.of(
                            LiquidSurfaceTest.flat(
                                    -40, -50, 100, 50, SURFACE, LiquidSurface.Kind.WATER)));

    static WalkController.Input input(
            int forward, int strafe, int vertical, double yaw, double pitch) {
        return new WalkController.Input(forward, strafe, vertical, yaw, pitch, vertical > 0, 0);
    }

    static void run(WalkController body, WalkController.Input input, double seconds) {
        int steps = (int) Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            body.step(input, DT, LAKE, WATER);
        }
    }

    /** A body placed over the deep bed: it floats at the surface. */
    static WalkController floating(double x) {
        var body = new WalkController();
        body.place(x, 0, 5, 100, LAKE, WATER);
        return body;
    }

    @Test
    void theSwimConstantsArePinned() {
        assertEquals(4.722, MovementParity.SWIM_SPEED);
        assertEquals(2.5, MovementParity.SWIM_BACK_SPEED);
        assertEquals(.6, MovementParity.SWIM_DEPTH_FRACTION);
        assertEquals(.05, MovementParity.SWIM_EXIT_MARGIN);
        var body = new WalkController();
        assertEquals(MovementParity.DEFAULT_HEIGHT * .6, body.swimDepth(), 1e-12);
    }

    @Test
    void placedOverDeepWaterTheBodyFloatsAtTheSurface() {
        var body = floating(50);
        assertTrue(body.swimming());
        assertEquals(WalkController.Status.SWIMMING, body.status());
        assertEquals(SURFACE - body.swimDepth(), body.z(), 1e-9);
        assertEquals(LiquidSurface.Kind.WATER, body.liquid());
        // No gravity and no drift: it stays where it is while no key is held.
        run(body, input(0, 0, 0, 0, 0), 2);
        assertEquals(SURFACE - body.swimDepth(), body.z(), 1e-9);
        assertEquals(WalkController.Status.SWIMMING, body.status());
        // Placed below the surface, it stays at that depth.
        body = new WalkController();
        body.place(50, 0, -6, 100, LAKE, WATER);
        assertTrue(body.swimming());
        assertEquals(-6, body.z(), 1e-9);
    }

    @Test
    void swimmingUsesTheSwimSpeeds() {
        var body = floating(50);
        run(body, input(1, 0, 0, 0, 0), 1);
        assertEquals(50 + MovementParity.SWIM_SPEED, body.x(), .02);
        assertEquals(MovementParity.SWIM_SPEED, body.horizontalSpeed(), .02);
        body = floating(50);
        run(body, input(-1, 0, 0, 0, 0), 1);
        assertEquals(50 - MovementParity.SWIM_BACK_SPEED, body.x(), .02);
        body = floating(50);
        // Strafe right with yaw 0 (facing +X) moves toward -Y, at the swim speed.
        run(body, input(0, 1, 0, 0, 0), 1);
        assertEquals(-MovementParity.SWIM_SPEED, body.y(), .02);
        body = floating(50);
        run(body, input(1, 1, 0, 0, 0), 1);
        assertEquals(MovementParity.SWIM_SPEED, Math.hypot(body.x() - 50, body.y()), .02);
    }

    @Test
    void pitchSteersADiveAndRisingStopsAtTheSurface() {
        var body = floating(50);
        double top = body.z();
        run(body, input(1, 0, 0, 0, -.5), 1);
        assertEquals(50 + MovementParity.SWIM_SPEED * Math.cos(.5), body.x(), .02);
        assertEquals(top - MovementParity.SWIM_SPEED * Math.sin(.5), body.z(), .02);
        assertTrue(body.swimming());
        // Space rises straight up at the swim speed, and stops at the floating depth.
        double deep = body.z();
        run(body, input(0, 0, 1, 0, 0), .25);
        assertEquals(deep + MovementParity.SWIM_SPEED * .25, body.z(), .02);
        run(body, input(0, 0, 1, 0, 0), 2);
        assertEquals(top, body.z(), 1e-9, "never above the floating depth");
        // Looking up while swimming forward does not leave the water either.
        run(body, input(1, 0, 0, 0, 1), 1);
        assertEquals(top, body.z(), 1e-9);
        assertTrue(body.swimming());
        // The down key sinks; the bed stops it.
        run(body, input(0, 0, -1, 0, 0), 3);
        assertEquals(-10, body.z(), .05);
        assertTrue(body.swimming(), "still swimming on the bed of deep water");
    }

    @Test
    void walkingIntoTheLakeSwimsAndSwimmingBackWalksOut() {
        var body = new WalkController();
        body.place(-30, 0, 1, 10, LAKE, WATER);
        assertTrue(body.grounded());
        assertFalse(body.swimming(), "dry shore");
        int switches = 0;
        boolean was = false;
        for (int i = 0; i < 600; i++) {
            body.step(input(1, 0, 0, 0, 0), DT, LAKE, WATER);
            if (body.swimming() != was) {
                switches++;
                was = body.swimming();
                if (was) {
                    // It starts swimming where its feet first pass the swim depth on the bed.
                    assertEquals(SURFACE - body.swimDepth(), body.z(), .15);
                    double bedX = -20 + (-body.z()) * 2;
                    assertEquals(bedX, body.x(), .3);
                }
            }
        }
        assertEquals(1, switches, "one switch into the water, no flicker");
        assertEquals(WalkController.Status.SWIMMING, body.status());
        assertEquals(SURFACE - body.swimDepth(), body.z(), .15, "level at the surface");
        // Back to the shore: the rising bed lifts it out and it walks again.
        switches = 0;
        for (int i = 0; i < 900; i++) {
            body.step(input(1, 0, 0, Math.PI, 0), DT, LAKE, WATER);
            if (body.swimming() != was) {
                switches++;
                was = body.swimming();
            }
        }
        assertEquals(1, switches, "one switch out of the water, no flicker");
        assertFalse(body.swimming());
        assertNull(body.liquid());
        assertTrue(body.grounded(), "walking on the shore: " + body.status());
        assertTrue(body.x() < -20, "on the shore: " + body.x());
    }

    @Test
    void aFallIntoDeepWaterStopsAtTheSwimDepth() {
        var body = new WalkController();
        body.place(50, 0, 20, 0, LAKE, WATER);
        assertFalse(body.swimming(), "in the air above the lake");
        run(body, input(0, 0, 0, 0, 0), 4);
        assertTrue(body.swimming());
        assertEquals(0, body.verticalSpeed(), 1e-9, "the fall stops");
        double z = body.z();
        assertTrue(
                z < SURFACE - body.swimDepth() && z > SURFACE - body.swimDepth() - 1.2, "z " + z);
        run(body, input(0, 0, 0, 0, 0), 1);
        assertEquals(z, body.z(), 1e-9, "and stays");
    }

    @Test
    void whereTheLiquidEndsTheBodyFallsAgain() {
        // Water only over x < 60; beyond that the body leaves the liquid over the deep bed.
        var pond =
                LiquidSurface.of(
                        List.of(
                                LiquidSurfaceTest.flat(
                                        -40, -50, 60, 50, SURFACE, LiquidSurface.Kind.WATER)));
        var body = new WalkController();
        body.place(50, 0, 5, 100, LAKE, pond);
        assertTrue(body.swimming());
        for (int i = 0; i < 300; i++) {
            body.step(input(1, 0, 0, 0, 0), DT, LAKE, pond);
        }
        assertFalse(body.swimming());
        assertTrue(body.grounded(), "landed on the bed: " + body.z());
        assertEquals(-10, body.z(), .05);
    }

    @Test
    void flyingAndWalkingWithoutLiquidNeverSwim() {
        var body = floating(50);
        body.mode(WalkController.Mode.FLY);
        assertFalse(body.swimming());
        run(body, input(0, 0, -1, 0, 0), 1);
        assertEquals(WalkController.Status.FLYING, body.status());
        // The same lake without its liquid is a dry basin.
        var dry = new WalkController();
        dry.place(50, 0, 5, 100, LAKE);
        assertFalse(dry.swimming());
        assertEquals(-10, dry.z(), .05);
    }

    @Test
    void aSteepBankIsAWallToASwimmerAndDoesNotFlicker() {
        // Deep water against a 70 degree bank rising from the bed at x = 10.
        var bank = new TriangleWorld().floor(-100, -50, 10, 50, -10).ramp(10, -50, 50, -10, 70, 20);
        var body = new WalkController();
        body.place(0, 0, 5, 100, bank, WATER);
        assertTrue(body.swimming());
        double top = body.z();
        int switches = 0;
        boolean was = true;
        for (int i = 0; i < 600; i++) {
            body.step(input(1, 0, 0, 0, 0), DT, bank, WATER);
            if (body.swimming() != was) {
                switches++;
                was = body.swimming();
            }
            assertTrue(body.z() <= top + 1e-9, "never climbs out up the bank: " + body.z());
        }
        assertEquals(0, switches, "keeps swimming against the bank");
        double bankX = 10 + (top + 10) / Math.tan(Math.toRadians(70));
        assertTrue(body.x() < bankX, "stopped by the bank: " + body.x() + " " + bankX);
    }

    @Test
    void aFloatingBodyFollowsASlopingSurface() {
        // The surface rises one yard every 20 yards toward +X, as on a river.
        double cell = LiquidSurface.CELL;
        var builder = new LiquidSurface.Builder();
        for (long gx = (long) Math.floor(0 / cell); gx * cell < 100; gx++) {
            for (long gy = (long) Math.floor(-50 / cell); gy * cell < 50; gy++) {
                double north = (gx + 1) * cell, south = gx * cell;
                double hn = SURFACE + north / 20, hs = SURFACE + south / 20;
                builder.cell(north, (gy + 1) * cell, hn, hn, hs, hs, LiquidSurface.Kind.WATER);
            }
        }
        var river = LiquidSurface.of(List.of(builder.build()));
        var body = new WalkController();
        body.place(10, 0, 5, 100, LAKE, river);
        assertTrue(body.swimming());
        for (int i = 0; i < 600; i++) {
            body.step(input(1, 0, 0, 0, 0), DT, LAKE, river);
            assertEquals(
                    river.at(body.x(), body.y()).height() - body.swimDepth(),
                    body.z(),
                    .01,
                    "floats at the swim depth at x " + body.x());
        }
        assertTrue(body.x() > 50, "swam upstream: " + body.x());
        // And back downstream.
        for (int i = 0; i < 300; i++) {
            body.step(input(1, 0, 0, Math.PI, 0), DT, LAKE, river);
            assertEquals(river.at(body.x(), body.y()).height() - body.swimDepth(), body.z(), .01);
        }
    }

    @Test
    void aShortBodyOverABedJustDeeperThanItsExitDepthDoesNotFlicker() {
        // The murloc's capsule (#711): 1.29 yd tall, so it swims at 0.774 yd and walks out
        // above 0.71 yd. Every bed depth around those, walked into and stood on.
        var murloc = new CapsuleCollision.Shape(.45, 1.29);
        for (double depth = .70; depth <= .83; depth += .005) {
            var bed = new TriangleWorld().floor(-100, -50, 100, 50, SURFACE - depth);
            var body = new WalkController();
            body.shape(murloc);
            body.place(-30, 0, 5, 100, bed, WATER);
            int switches = 0;
            boolean was = body.swimming();
            for (int i = 0; i < 240; i++) {
                body.step(input(i < 120 ? 1 : 0, 0, 0, 0, 0), DT, bed, WATER);
                if (body.swimming() != was) {
                    switches++;
                    was = body.swimming();
                }
            }
            assertTrue(switches <= 1, "bed " + depth + " yd deep: " + switches + " switches");
        }
    }

    @Test
    void aShortBodyWalksIntoTheLakeAndOutWithOneSwitchEachWay() {
        var body = new WalkController();
        body.shape(new CapsuleCollision.Shape(.45, 1.29));
        body.place(-30, 0, 1, 10, LAKE, WATER);
        assertFalse(body.swimming());
        int switches = 0;
        boolean was = false;
        for (int i = 0; i < 600; i++) {
            body.step(input(1, 0, 0, 0, 0), DT, LAKE, WATER);
            if (body.swimming() != was) {
                switches++;
                was = body.swimming();
            }
            // Once in, at most a step's worth below the swim depth.
            if (was) {
                assertTrue(SURFACE - body.z() > body.swimDepth() - 1e-6, "swims at depth");
            }
        }
        assertEquals(1, switches);
        for (int i = 0; i < 900; i++) {
            body.step(input(1, 0, 0, Math.PI, 0), DT, LAKE, WATER);
            if (body.swimming() != was) {
                switches++;
                was = body.swimming();
            }
        }
        assertEquals(2, switches, "and one switch out");
        assertTrue(body.grounded());
    }
}
