package com.vibeminer.rendering.world;

import java.util.Objects;
import java.util.Set;

/**
 * Which sequence a body plays, from what it is doing, and its capsule from the model's recorded
 * box. Sequences are AnimationData IDs (wowdev M2/AnimationList): Stand 0, Walk 4, Run 5, SwimIdle
 * 41, Swim 42, Fly 135. A wanted sequence the body's model does not have falls back to the nearest
 * one it has, and finally to Stand.
 */
public final class BodyAnimation {
    public static final int STAND = 0, WALK = 4, RUN = 5, SWIM_IDLE = 41, SWIM = 42, FLY = 135;

    /** At or below this speed a moving body walks; above it, it runs (yards per second). */
    public static final double WALK_LIMIT =
            (MovementParity.WALK_SPEED + MovementParity.BACKPEDAL_SPEED) / 2;

    private BodyAnimation() {}

    /**
     * The sequence for a body in {@code mode} with {@code status}, moving at {@code speed} yards
     * per second, {@code swimming} when deep in water, from the sequences its model has.
     */
    public static int choose(
            WalkController.Mode mode,
            WalkController.Status status,
            double speed,
            boolean swimming,
            Set<Integer> available) {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(status);
        Objects.requireNonNull(available);
        boolean moving = speed > .05;
        int[] wanted;
        if (mode == WalkController.Mode.FLY) {
            wanted = new int[] {moving ? FLY : STAND, RUN, STAND};
        } else if (swimming) {
            wanted = moving ? new int[] {SWIM, RUN, WALK, STAND} : new int[] {SWIM_IDLE, STAND};
        } else if (status == WalkController.Status.STANDING || !moving) {
            wanted = new int[] {STAND};
        } else if (status == WalkController.Status.FALLING) {
            // No jump or fall sequence is read yet: keep the legs moving as they were.
            wanted = new int[] {speed > WALK_LIMIT ? RUN : WALK, RUN, WALK, STAND};
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
     * The capsule of a model from its recorded box (CreatureModelData GeoBox: minimum x, y, z then
     * maximum x, y, z, in model yards) times the display's scale: the radius is half the narrower
     * horizontal side, the height the box's top, raised to two radii when shorter. On 70862
     * CollisionWidth and CollisionHeight read the same for every model probed, so the box is the
     * body's own size.
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
