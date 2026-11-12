package com.vibeminer.rendering.world;

import java.util.Objects;

/**
 * Walking or flying as a body: a {@link WalkController} with a {@link FollowCamera}. The view's yaw
 * turns the body; pitch only tilts the camera (and aims flight). The camera handed out is the
 * follow camera's; when someone else moves the camera (a bookmark or a jump), the body is placed
 * under the new camera on the next step.
 *
 * <p>Not thread-safe; one instance per view.
 */
public final class BodyMotion {
    /** Eye height as a share of the capsule height. */
    static final double EYE = .9;

    /** How far below the camera a body looks for ground when it is placed. */
    static final double PLACE_DROP = 2_000;

    /** Result of one step: the camera to show, whether it changed, and the body's status. */
    public record Step(
            TerrainRenderer.Camera camera, boolean moved, WalkController.Status status) {}

    private final WalkController body = new WalkController();
    private final FollowCamera follow = new FollowCamera();
    private double yaw, pitch;
    private TerrainRenderer.Camera shown;

    public WalkController body() {
        return body;
    }

    public FollowCamera camera() {
        return follow;
    }

    public WalkController.Mode mode() {
        return body.mode();
    }

    /** Switches walking and flying; the body stays where it is. */
    public void mode(WalkController.Mode next) {
        body.mode(next);
    }

    /** True once a camera has been handed out for the current placement. */
    public boolean placed() {
        return shown != null;
    }

    /** The camera this motion last handed out, or null before the first step. */
    public TerrainRenderer.Camera shown() {
        return shown;
    }

    /** Forgets the placement: the next step places the body under that step's camera. */
    public void release() {
        shown = null;
    }

    /** Turns (positive left) and tilts (positive up) the view, radians; pitch stays in +-1.5. */
    public void look(double turn, double tilt) {
        yaw += turn;
        pitch = Math.clamp(pitch + tilt, -1.5, 1.5);
    }

    /** Changes the wanted follow distance by {@code yards}; zero distance is first person. */
    public void zoom(double yards) {
        follow.distance(follow.distance() + yards);
    }

    public double yaw() {
        return yaw;
    }

    /**
     * Integrates the keys for {@code seconds} and returns the camera. A camera other than the one
     * this motion last handed out (or the first one) places the body at its position: walking
     * stands on the ground below it, flying stays at its height.
     */
    public Step step(
            TerrainRenderer.Camera current,
            int forward,
            int strafe,
            int vertical,
            int turn,
            int tilt,
            double flySpeed,
            double seconds,
            CollisionWorld world) {
        Objects.requireNonNull(current);
        Objects.requireNonNull(world);
        boolean replaced = !current.equals(shown);
        if (replaced) {
            yaw = current.yaw();
            pitch = current.pitch();
            body.place(current.x(), current.y(), current.z() - eye(), PLACE_DROP, world);
            follow.reset();
        }
        look(
                turn * MovementParity.TURN_RATE * seconds,
                tilt * (MovementParity.TURN_RATE / 2) * seconds);
        body.step(
                new WalkController.Input(
                        forward, strafe, vertical, yaw, pitch, vertical > 0, flySpeed),
                seconds,
                world);
        var next = follow.place(body.x(), body.y(), body.z() + eye(), yaw, pitch, seconds, world);
        boolean moved = replaced || !next.equals(current);
        shown = next;
        return new Step(next, moved, body.status());
    }

    private double eye() {
        return body.shape().height() * EYE;
    }
}
