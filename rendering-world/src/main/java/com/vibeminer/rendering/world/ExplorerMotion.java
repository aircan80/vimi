package com.vibeminer.rendering.world;

import java.util.Objects;

/**
 * Deterministic explorer camera motion. Fly keeps the established free-flight controls; walk keeps
 * the eye a fixed height above the rendered terrain surface sampled by {@link TerrainGround}. No
 * collision, slope limit, swimming or WMO/doodad floor is evaluated. Where no qualified surface is
 * loaded, walk holds altitude and reports {@link Ground#UNAVAILABLE} instead of inventing a floor.
 */
public final class ExplorerMotion {
    public enum Mode {
        FLY,
        WALK
    }

    public enum Ground {
        FREE,
        FOLLOWING,
        UNAVAILABLE
    }

    /** Key state; each axis is clamped to -1..1 so overlapping keys never double speed. */
    public record Input(int forward, int strafe, int vertical, int turn, int tilt, boolean sprint) {
        public Input {
            forward = Integer.signum(forward);
            strafe = Integer.signum(strafe);
            vertical = Integer.signum(vertical);
            turn = Integer.signum(turn);
            tilt = Integer.signum(tilt);
        }

        public static final Input NONE = new Input(0, 0, 0, 0, 0, false);

        boolean translating() {
            return forward != 0 || strafe != 0 || vertical != 0;
        }
    }

    /**
     * {@code resting} is true once velocity has decayed and any downward settle has finished, so
     * callers can always present the final position without rendering every settling tick.
     */
    public record Step(
            TerrainRenderer.Camera camera, Ground ground, boolean moved, boolean resting) {}

    /** Explorer eye height in yards; a viewing convenience, not a race-specific client value. */
    public static final double EYE_HEIGHT = 2;

    /** Base walk speed in yards per second; an explorer default, not a client parity claim. */
    public static final double WALK_SPEED = 7;

    public static final double WALK_SPRINT = 2, FLY_SPRINT = 4;

    /** Velocity response and downward settle time constants for smoothed motion, seconds. */
    public static final double RESPONSE_SECONDS = .12, SETTLE_SECONDS = .15;

    private static final double LIMIT = 1_000_000, MAX_DT = .1, REST = 1e-3;

    private Mode mode = Mode.FLY;
    private boolean smooth = true;
    private double forwardVelocity, strafeVelocity, verticalVelocity;

    public Mode mode() {
        return mode;
    }

    public void mode(Mode next) {
        mode = Objects.requireNonNull(next);
        reset();
    }

    public boolean smooth() {
        return smooth;
    }

    public void smooth(boolean enabled) {
        smooth = enabled;
        reset();
    }

    /** Drops retained velocity, for example after focus loss or a hidden viewport. */
    public void reset() {
        forwardVelocity = strafeVelocity = verticalVelocity = 0;
    }

    /** True while smoothed velocity is still decaying after keys were released. */
    public boolean coasting() {
        return forwardVelocity != 0 || strafeVelocity != 0 || verticalVelocity != 0;
    }

    /** Effective horizontal speed for the current mode and sprint state. */
    public double speed(double flySpeed, boolean sprint) {
        return mode == Mode.WALK
                ? WALK_SPEED * (sprint ? WALK_SPRINT : 1)
                : flySpeed * (sprint ? FLY_SPRINT : 1);
    }

    public Step step(
            TerrainRenderer.Camera camera,
            Input input,
            double seconds,
            double flySpeed,
            TerrainGround ground) {
        Objects.requireNonNull(camera);
        Objects.requireNonNull(input);
        Objects.requireNonNull(ground);
        if (!Double.isFinite(seconds) || seconds < 0 || !Double.isFinite(flySpeed)) {
            throw new IllegalArgumentException("Invalid motion step");
        }
        double dt = Math.min(MAX_DT, seconds), speed = speed(flySpeed, input.sprint());
        boolean walking = mode == Mode.WALK;
        double targetForward = input.forward() * speed,
                targetStrafe = input.strafe() * speed,
                targetVertical = walking ? 0 : input.vertical() * speed;
        if (smooth) {
            double blend = dt == 0 ? 0 : 1 - Math.exp(-dt / RESPONSE_SECONDS);
            forwardVelocity = approach(forwardVelocity, targetForward, blend);
            strafeVelocity = approach(strafeVelocity, targetStrafe, blend);
            verticalVelocity = approach(verticalVelocity, targetVertical, blend);
        } else {
            forwardVelocity = targetForward;
            strafeVelocity = targetStrafe;
            verticalVelocity = targetVertical;
        }
        double forward = forwardVelocity * dt, strafe = strafeVelocity * dt;
        var next =
                camera(
                                camera.x()
                                        + Math.cos(camera.yaw()) * forward
                                        + Math.sin(camera.yaw()) * strafe,
                                camera.y()
                                        + Math.sin(camera.yaw()) * forward
                                        - Math.cos(camera.yaw()) * strafe,
                                camera.z() + verticalVelocity * dt,
                                camera)
                        .look(input.turn() * dt, input.tilt() * dt);
        var state = Ground.FREE;
        boolean settled = true;
        if (walking) {
            double height = ground.heightAt(next.x(), next.y());
            if (Double.isNaN(height)) {
                state = Ground.UNAVAILABLE;
            } else {
                state = Ground.FOLLOWING;
                next = settle(next, height + EYE_HEIGHT, dt);
                settled = next.z() == height + EYE_HEIGHT;
            }
        }
        return new Step(
                next,
                state,
                !next.equals(camera),
                settled && !coasting() && input.turn() == 0 && input.tilt() == 0);
    }

    /**
     * Wheel dolly. Fly moves along the full view direction, including pitch; walk moves along the
     * heading only and then follows the surface.
     */
    public Step dolly(TerrainRenderer.Camera camera, double distance, TerrainGround ground) {
        Objects.requireNonNull(camera);
        Objects.requireNonNull(ground);
        if (!Double.isFinite(distance)) {
            throw new IllegalArgumentException("Invalid dolly");
        }
        boolean walking = mode == Mode.WALK;
        double horizontal = walking ? distance : Math.cos(camera.pitch()) * distance;
        var next =
                camera(
                        camera.x() + Math.cos(camera.yaw()) * horizontal,
                        camera.y() + Math.sin(camera.yaw()) * horizontal,
                        walking ? camera.z() : camera.z() + Math.sin(camera.pitch()) * distance,
                        camera);
        var state = Ground.FREE;
        if (walking) {
            double height = ground.heightAt(next.x(), next.y());
            state = Double.isNaN(height) ? Ground.UNAVAILABLE : Ground.FOLLOWING;
            if (state == Ground.FOLLOWING) {
                next = settle(next, height + EYE_HEIGHT, 0);
            }
        }
        return new Step(next, state, !next.equals(camera), true);
    }

    /** Rising terrain lifts immediately; descending eases when smoothing is enabled. */
    private TerrainRenderer.Camera settle(TerrainRenderer.Camera camera, double eye, double dt) {
        double z = camera.z();
        if (z <= eye || !smooth) {
            z = eye;
        } else if (dt > 0) {
            z = eye + (z - eye) * Math.exp(-dt / SETTLE_SECONDS);
            if (z - eye < REST) {
                z = eye;
            }
        }
        return camera(camera.x(), camera.y(), z, camera);
    }

    private static double approach(double value, double target, double blend) {
        double next = value + (target - value) * blend;
        return target == 0 && Math.abs(next) < REST ? 0 : next;
    }

    /** Bounded position with the orientation of {@code view}; never constructs out of range. */
    private static TerrainRenderer.Camera camera(
            double x, double y, double z, TerrainRenderer.Camera view) {
        return new TerrainRenderer.Camera(
                Math.clamp(x, -LIMIT, LIMIT),
                Math.clamp(y, -LIMIT, LIMIT),
                Math.clamp(z, -LIMIT, LIMIT),
                view.yaw(),
                view.pitch());
    }
}
