package com.vibeminer.rendering.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BodyAnimatorTest {
    // The murloc (display 441) on 70862: loop lengths and move speeds.
    static BodyAnimator murloc() {
        return new BodyAnimator(
                Map.of(0, 1333L, 4, 534L, 5, 533L, 41, 1666L, 42, 1667L),
                Map.of(0, 0f, 4, 2.5f, 5, 8.333333f, 41, 0f, 42, 2.5f));
    }

    static final WalkController.Mode WALK = WalkController.Mode.WALK;

    @Test
    void standingPlaysStandAtItsOwnPaceAndLoops() {
        var body = murloc();
        assertEquals(0, body.sequence());
        body.step(WALK, WalkController.Status.STANDING, 0, false, false, .5);
        assertEquals(0, body.sequence());
        assertEquals(500, body.millis());
        body.step(WALK, WalkController.Status.STANDING, 0, false, false, 1);
        assertEquals(1500 - 1333, body.millis(), "it loops");
    }

    @Test
    void runningScalesTheClockByTheSpeedOverTheRecordedMoveSpeed() {
        var body = murloc();
        body.step(WALK, WalkController.Status.MOVING, 7, false, false, 0);
        assertEquals(5, body.sequence());
        assertEquals(0, body.millis(), "a new sequence starts from its beginning");
        body.step(WALK, WalkController.Status.MOVING, 7, false, false, .05);
        assertEquals((long) (50 * 7 / 8.333333), body.millis());
    }

    @Test
    void backpedallingWithoutWalkBackwardsPlaysTheWalkInReverse() {
        var body = murloc();
        body.step(WALK, WalkController.Status.MOVING, 4.5, true, false, 0);
        assertEquals(4, body.sequence());
        body.step(WALK, WalkController.Status.MOVING, 4.5, true, false, .1);
        assertEquals(534 - (long) Math.ceil(100 * 4.5 / 2.5), body.millis(), 1);
        var backwards =
                new BodyAnimator(Map.of(0, 1000L, 4, 500L, 13, 500L), Map.of(13, 4.5f, 4, 2.5f));
        backwards.step(WALK, WalkController.Status.MOVING, 4.5, true, false, .1);
        assertEquals(13, backwards.sequence());
        assertEquals(100, backwards.millis(), "its own WalkBackwards plays forward");
    }

    @Test
    void aBodyNeedsStand() {
        assertThrows(
                IllegalArgumentException.class, () -> new BodyAnimator(Map.of(4, 500L), Map.of()));
    }
}
