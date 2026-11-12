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
        return BodyAnimation.choose(WalkController.Mode.WALK, status, speed, swimming, available);
    }

    @Test
    void theMovementStatePicksStandWalkRunOrSwim() {
        assertEquals(0, walk(WalkController.Status.STANDING, 0, false, MURLOC));
        assertEquals(
                5, walk(WalkController.Status.MOVING, MovementParity.RUN_SPEED, false, MURLOC));
        assertEquals(
                4, walk(WalkController.Status.MOVING, MovementParity.WALK_SPEED, false, MURLOC));
        assertEquals(
                5,
                walk(WalkController.Status.MOVING, MovementParity.BACKPEDAL_SPEED, false, MURLOC),
                "backpedal speed is above the walk limit");
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
                        WalkController.Mode.FLY, WalkController.Status.FLYING, 32, false, GRYPHON));
        assertEquals(
                5,
                BodyAnimation.choose(
                        WalkController.Mode.FLY, WalkController.Status.FLYING, 32, false, MURLOC));
        assertEquals(
                0,
                BodyAnimation.choose(
                        WalkController.Mode.FLY, WalkController.Status.FLYING, 0, false, GRYPHON));
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
    }
}
