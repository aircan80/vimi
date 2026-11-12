package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BodyAnimationTest {
    static final Set<Integer> MURLOC = Set.of(0, 4, 5, 41, 42);
    static final Set<Integer> GRYPHON = Set.of(0, 4, 5, 135);

    static int walk(
            WalkController.Status status, double speed, boolean swimming, Set<Integer> available) {
        return BodyAnimation.choose(
                WalkController.Mode.WALK, status, speed, false, swimming, available);
    }

    @Test
    void theMovementStatePicksStandWalkRunOrSwim() {
        assertEquals(0, walk(WalkController.Status.STANDING, 0, false, MURLOC));
        assertEquals(
                5, walk(WalkController.Status.MOVING, MovementParity.RUN_SPEED, false, MURLOC));
        assertEquals(
                4, walk(WalkController.Status.MOVING, MovementParity.WALK_SPEED, false, MURLOC));
        assertEquals(
                5, walk(WalkController.Status.FALLING, MovementParity.RUN_SPEED, false, MURLOC));
        assertEquals(
                0, walk(WalkController.Status.FALLING, 0, false, MURLOC), "a straight drop stands");
        assertEquals(41, walk(WalkController.Status.STANDING, 0, true, MURLOC));
        assertEquals(42, walk(WalkController.Status.MOVING, 4, true, MURLOC));
        assertEquals(0, walk(WalkController.Status.LOADING, 0, false, MURLOC));
    }

    @Test
    void missingSequencesFallBack() {
        assertEquals(5, walk(WalkController.Status.MOVING, 4, true, GRYPHON), "no Swim: Run");
        assertEquals(
                0, walk(WalkController.Status.STANDING, 0, true, GRYPHON), "no SwimIdle: Stand");
        assertEquals(4, walk(WalkController.Status.MOVING, 7, false, Set.of(0, 4)), "no Run: Walk");
        assertEquals(0, walk(WalkController.Status.MOVING, 7, false, Set.of()), "nothing: Stand");
        assertEquals(
                135,
                BodyAnimation.choose(
                        WalkController.Mode.FLY,
                        WalkController.Status.FLYING,
                        32,
                        false,
                        false,
                        GRYPHON));
        assertEquals(
                5,
                BodyAnimation.choose(
                        WalkController.Mode.FLY,
                        WalkController.Status.FLYING,
                        32,
                        false,
                        false,
                        MURLOC));
        assertEquals(
                135,
                fly(WalkController.Status.FLYING, 0, GRYPHON),
                "still in the air with no idle-in-air sequence: Fly");
    }

    static int fly(WalkController.Status status, double speed, Set<Integer> available) {
        return BodyAnimation.choose(
                WalkController.Mode.FLY, status, speed, false, false, available);
    }

    @Test
    void theTaxiGryphonFliesAndRestsWithWhatItStores() {
        // Display 1149 (model 792) on 70862 stores these AnimationData IDs, and no FlyStand (229)
        // or Hover (193).
        var taxi = Set.of(0, 1, 4, 5, 8, 9, 10, 16, 126, 127, 135);
        assertEquals(135, fly(WalkController.Status.FLYING, 32, taxi), "moving: Fly");
        assertEquals(135, fly(WalkController.Status.FLYING, 0, taxi), "hovering: Fly");
        assertEquals(135, fly(WalkController.Status.LOADING, 0, taxi), "waiting in the air: Fly");
        assertEquals(0, fly(WalkController.Status.LANDED, 0, taxi), "resting on the ground: Stand");
        // A model that stores an idle-in-air sequence hovers with it: FlyStand, else Hover.
        assertEquals(229, fly(WalkController.Status.FLYING, 0, Set.of(0, 135, 193, 229)));
        assertEquals(193, fly(WalkController.Status.FLYING, 0, Set.of(0, 135, 193)));
        assertEquals(135, fly(WalkController.Status.FLYING, 32, Set.of(0, 135, 193, 229)));
    }

    static int back(WalkController.Status status, double speed, Set<Integer> available) {
        return BodyAnimation.choose(
                WalkController.Mode.WALK, status, speed, true, false, available);
    }

    @Test
    void backpedallingWalksBackwardsAndFallingKeepsTheLegsMoving() {
        var murloc = Set.of(0, 4, 5, 13, 41, 42);
        double back = MovementParity.BACKPEDAL_SPEED;
        assertEquals(13, back(WalkController.Status.MOVING, back, murloc));
        assertEquals(13, back(WalkController.Status.FALLING, back, murloc), "a backward jump");
        assertEquals(4, back(WalkController.Status.MOVING, back, MURLOC), "no WalkBackwards: Walk");
        assertEquals(5, back(WalkController.Status.MOVING, back, Set.of(0, 5)), "then Run");
        assertEquals(0, back(WalkController.Status.STANDING, 0, murloc));
        assertEquals(
                5,
                walk(WalkController.Status.FALLING, MovementParity.RUN_SPEED, false, murloc),
                "a running jump keeps running");
        assertEquals(
                4,
                walk(WalkController.Status.FALLING, MovementParity.WALK_SPEED, false, murloc),
                "a walking jump keeps walking");
        assertEquals(0, walk(WalkController.Status.FALLING, 0, false, murloc), "a drop stands");
        assertEquals(
                5, walk(WalkController.Status.SLIDING, MovementParity.RUN_SPEED, false, murloc));
    }

    @Test
    void theClockFollowsTheSpeedOverTheRecordedMoveSpeed() {
        assertEquals(1, BodyAnimation.rate(0, 7), 1e-12, "no recorded speed: its own pace");
        assertEquals(1, BodyAnimation.rate(3.5, 0), 1e-12, "at rest: its own pace");
        assertEquals(2, BodyAnimation.rate(3.5, 7), 1e-12);
        assertEquals(.5, BodyAnimation.rate(5, 2.5), 1e-12);
        assertEquals(BodyAnimation.MAX_RATE, BodyAnimation.rate(.1, 100), 1e-12);
        assertEquals(BodyAnimation.MIN_RATE, BodyAnimation.rate(100, 1), 1e-12);
    }

    @Test
    void theCapsuleComesFromTheModelBoxAndScale() {
        // Murloc (model 31) and gryphon (model 792) boxes on 70862.
        double[] murloc = {-0.6040779, -0.4966644, -0.028715078, 0.43333232, 0.5180552, 1.2852219};
        var shape = BodyAnimation.capsule(murloc, 1);
        assertEquals((0.5180552 + 0.4966644) / 2, shape.radius(), 1e-9);
        assertEquals(1.2852219, shape.height(), 1e-9);
        var big = BodyAnimation.capsule(murloc, 2);
        assertEquals(2 * shape.radius(), big.radius(), 1e-9);
        assertEquals(2 * 1.2852219, big.height(), 1e-9);
        double[] gryphon = {-2.5415769, -4.6910086, 0.002135992, 2.059098, 4.7960405, 5.346998};
        var flyer = BodyAnimation.capsule(gryphon, 1);
        assertEquals((2.059098 + 2.5415769) / 2, flyer.radius(), 1e-9);
        assertEquals(5.346998, flyer.height(), 1e-9);
        double[] flat = {-1, -1, 0, 1, 1, .5};
        assertEquals(
                2, BodyAnimation.capsule(flat, 1).height(), 1e-9, "never shorter than two radii");
        assertThrows(IllegalArgumentException.class, () -> BodyAnimation.capsule(new double[5], 1));
        assertThrows(IllegalArgumentException.class, () -> BodyAnimation.capsule(murloc, 0));
        assertEquals(
                BodyAnimation.capsule(murloc, 1.5 * .8).radius(),
                BodyAnimation.capsule(murloc, 1.5, .8).radius(),
                1e-12,
                "the display scale times the model scale");
    }
}
