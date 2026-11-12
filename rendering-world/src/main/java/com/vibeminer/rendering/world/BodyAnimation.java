package com.vibeminer.rendering.world;

import java.util.Objects;
import java.util.Set;

/**
 * Which sequence a body plays, from what it is doing, how fast its clock runs, and its capsule from
 * the model's recorded box. Sequences are AnimationData IDs (wowdev M2/AnimationList): Stand 0,
 * Walk 4, Run 5, WalkBackwards 13, SwimIdle 41, Swim 42, Fly 135, Hover 193, FlyStand 229. A wanted
 * sequence the body's model does not have falls back to the nearest one it has, and finally to
 * Stand.
 */
public final class BodyAnimation {
    public static final int STAND = 0,
            WALK = 4,
            RUN = 5,
            WALK_BACKWARDS = 13,
            SWIM_IDLE = 41,
            SWIM = 42,
            FLY = 135,
            HOVER = 193,
            FLY_STAND = 229;

    /** The sequences a walking, swimming or flying body may play, in no particular order. */
    public static final int[] SEQUENCES = {
        STAND, WALK, RUN, WALK_BACKWARDS, SWIM_IDLE, SWIM, FLY, HOVER, FLY_STAND
    };

    /**
     * A body moving forward at or below this speed walks (the client's walk toggle); faster, it
     * runs (yards per second).
     */
    public static final double WALK_LIMIT = MovementParity.WALK_SPEED + .01;

    /** Bounds of {@link #rate}: a sequence never plays slower or faster than this. */
    public static final double MIN_RATE = .25, MAX_RATE = 4;

    private BodyAnimation() {}

    /**
     * The sequence for a body in {@code mode} with {@code status}, moving at {@code speed} yards
     * per second, {@code backward} when it moves against its facing (backpedalling), {@code
     * swimming} when deep in water, from the sequences its model has. Backpedalling plays
     * WalkBackwards, as the client does, whatever the speed; moving forward runs, or walks at walk
     * speed.
     */
    public static int choose(
            WalkController.Mode mode,
            WalkController.Status status,
            double speed,
            boolean backward,
            boolean swimming,
            Set<Integer> available) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(available);
        boolean moving = speed > .05;
        int[] wanted;
        if (mode == WalkController.Mode.FLY) {
            // Moving flies; still in the air, an idle-in-air sequence if the model has one (the
            // taxi gryphon has neither), else Fly; resting on the ground, Stand. Never a pose the
            // model does not store.
            wanted =
                    moving
                            ? new int[] {FLY, RUN, STAND}
                            : status == WalkController.Status.LANDED
                                    ? new int[] {STAND}
                                    : new int[] {FLY_STAND, HOVER, FLY, STAND};
        } else if (swimming) {
            wanted = moving ? new int[] {SWIM, RUN, WALK, STAND} : new int[] {SWIM_IDLE, STAND};
        } else if (status == WalkController.Status.STANDING || !moving) {
            wanted = new int[] {STAND};
        } else if (backward) {
            // Falling too: no jump or fall sequence is read yet, so the legs keep moving.
            wanted = new int[] {WALK_BACKWARDS, WALK, RUN, STAND};
        } else {
            wanted =
                    speed > WALK_LIMIT
                            ? new int[] {RUN, WALK, STAND}
                            : new int[] {WALK, RUN, STAND};
        }
        for (int id : wanted) {
            if (available.contains(id)) {
                return id;
            }
        }
        return STAND;
    }

    /**
     * How fast a sequence's clock runs for a body moving at {@code speed} yards per second: speed
     * over the sequence's recorded move speed (M2Sequence movespeed), so the feet keep pace with
     * the ground, within {@link #MIN_RATE} and {@link #MAX_RATE}. A sequence that records no move
     * speed (Stand, SwimIdle), or a body at rest, plays at its own pace: 1.
     */
    public static double rate(double moveSpeed, double speed) {
        if (!(moveSpeed > 0) || !(speed > .05) || !Double.isFinite(speed)) {
            return 1;
        }
        return Math.clamp(speed / moveSpeed, MIN_RATE, MAX_RATE);
    }

    /**
     * The capsule of a model from its recorded box (CreatureModelData GeoBox: minimum x, y, z then
     * maximum x, y, z, in model yards) times the body's scale, CreatureDisplayInfo
     * CreatureModelScale times CreatureModelData ModelScale: the radius is half the narrower
     * horizontal side, the height the box's top, raised to two radii when shorter. On 70862
     * CollisionWidth and CollisionHeight read the same for every model probed, so the box is the
     * body's own size.
     */
    public static CapsuleCollision.Shape capsule(
            double[] geoBox, double creatureModelScale, double modelScale) {
        return capsule(geoBox, creatureModelScale * modelScale);
    }

    /**
     * As {@link #capsule(double[], double, double)} with the two scales already multiplied:
     * CreatureDisplayInfo.CreatureModelScale times CreatureModelData.ModelScale.
     */
    public static CapsuleCollision.Shape capsule(double[] geoBox, double scale) {
        if (geoBox == null || geoBox.length != 6 || !(scale > 0) || !Double.isFinite(scale)) {
            throw new IllegalArgumentException("Invalid model box");
        }
        for (double v : geoBox) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Invalid model box");
            }
        }
        double width = geoBox[3] - geoBox[0], depth = geoBox[4] - geoBox[1];
        double radius = Math.min(width, depth) / 2 * scale;
        double height = geoBox[5] * scale;
        if (!(radius > .05) || !(height > .1)) {
            throw new IllegalArgumentException("Model box too small for a body");
        }
        return new CapsuleCollision.Shape(radius, Math.min(100, Math.max(height, 2 * radius)));
    }
}
