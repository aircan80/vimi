package com.vibeminer.rendering.world;

import java.util.Objects;

/**
 * A camera that orbits a body's eye at a zoom distance behind it, along the view direction. A small
 * sphere is swept from the eye toward the wanted place, so terrain and other blocking geometry pull
 * the camera in instead of letting it clip through; it moves back out smoothly once the way is
 * clear. At zero distance the view is first person, from the eye.
 *
 * <p>Not thread-safe; one instance per view.
 */
public final class FollowCamera {
    /** Radius of the camera's own collision sphere, yards. */
    static final double RADIUS = .2;

    /** Below this distance the camera sits at the eye (first person). */
    public static final double FIRST_PERSON = .25;

    /** Seconds to ease most of the way back out after a collision pulled the camera in. */
    static final double EASE_OUT_SECONDS = .25;

    public static final double MAX_DISTANCE =
            MovementParity.CAMERA_BASE_DISTANCE * MovementParity.CAMERA_MAX_FACTOR;

    private static final CapsuleCollision.Shape SPHERE =
            new CapsuleCollision.Shape(RADIUS, 2 * RADIUS);

    private final CapsuleCollision collision = new CapsuleCollision();
    private double distance = MovementParity.CAMERA_START_DISTANCE;
    private double shown = -1;

    /** The wanted distance, yards; zero is first person. */
    public double distance() {
        return distance;
    }

    public void distance(double next) {
        if (!Double.isFinite(next)) {
            throw new IllegalArgumentException("Invalid camera distance");
        }
        distance = Math.clamp(next, 0, MAX_DISTANCE);
    }

    /** True when the view is from the eye. */
    public boolean firstPerson() {
        return distance < FIRST_PERSON;
    }

    /** Forgets the eased distance, so the next frame starts at the collision-limited distance. */
    public void reset() {
        shown = -1;
    }

    /**
     * The camera looking along (yaw, pitch) at the eye ({@code ex}, {@code ey}, {@code ez}) from up
     * to the wanted distance behind it, {@code seconds} after the previous frame.
     */
    public TerrainRenderer.Camera place(
            double ex,
            double ey,
            double ez,
            double yaw,
            double pitch,
            double seconds,
            CollisionWorld world) {
        Objects.requireNonNull(world);
        double cp = Math.cos(pitch);
        double fx = Math.cos(yaw) * cp, fy = Math.sin(yaw) * cp, fz = Math.sin(pitch);
        double allowed = distance < FIRST_PERSON ? 0 : clear(ex, ey, ez, -fx, -fy, -fz, world);
        if (shown < 0 || allowed <= shown || !(seconds > 0)) {
            shown = allowed;
        } else {
            shown += (allowed - shown) * (1 - Math.exp(-seconds / EASE_OUT_SECONDS));
            if (allowed - shown < 1e-3) {
                shown = allowed;
            }
        }
        return new TerrainRenderer.Camera(
                ex - fx * shown, ey - fy * shown, ez - fz * shown, yaw, pitch);
    }

    /** The free distance from the eye along the unit direction, up to the wanted distance. */
    private double clear(
            double ex,
            double ey,
            double ez,
            double dx,
            double dy,
            double dz,
            CollisionWorld world) {
        double feet = ez - RADIUS;
        collision.gather(
                world, SPHERE, ex, ey, feet, dx * distance, dy * distance, dz * distance, RADIUS);
        var hit =
                collision.sweep(SPHERE, ex, ey, feet, dx * distance, dy * distance, dz * distance);
        return hit == null ? distance : Math.max(0, hit.t() * distance - .05);
    }
}
