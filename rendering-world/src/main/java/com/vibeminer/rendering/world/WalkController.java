package com.vibeminer.rendering.world;

import java.util.Objects;

/**
 * A body moving through {@link CollisionWorld} geometry with an upright capsule, in fixed {@link
 * MovementParity#STEP_SECONDS} steps. Walking has gravity, jumping, a slope limit, step-up and
 * sliding along walls; flying has no gravity and slides along every surface. Speeds and limits come
 * from {@link MovementParity}. Velocity changes instantly (the client has no ramp), and a jump
 * keeps its take-off velocity until landing (no air steering yet: unmeasured).
 *
 * <p>A walking body whose feet sink {@link MovementParity#SWIM_DEPTH_FRACTION} of its height below
 * the terrain's liquid surface ({@link LiquidSurface}) swims: no gravity, swim speeds, forward
 * follows the view's pitch (diving or rising), the up key (jump) rises and the down key sinks. It
 * never rises above that floating depth, so at the surface it floats, and it stays at its depth
 * when no key is held; a floating body follows a sloping surface; only walkable ground lifts it out
 * of the water (a steep bank is a wall). It walks again once walkable ground under it lifts its
 * feet above the floating depth by {@link MovementParity#SWIM_EXIT_MARGIN} of its height (the
 * shore), or falls where the liquid ends. Water in buildings (WMO liquid) is not known, so a body
 * walks through it.
 *
 * <p>Where the box around the body still has no collision data a walking body waits: it does not
 * move into the unknown area, and does not fall while its own footprint is unknown. A flying body
 * keeps going, colliding with what is known, and reports {@link Status#LOADING}. A body that falls
 * through a terrain hole into nothing for {@link #FALL_OUT} yards is put back where it last stood.
 *
 * <p>Not thread-safe; one instance per body.
 */
public final class WalkController {
    public enum Mode {
        WALK,
        FLY
    }

    /** What the body is doing after the last step. */
    public enum Status {
        /** On walkable ground, not moving. */
        STANDING,
        /** On walkable ground, moving. */
        MOVING,
        /** In the air: jumping or falling. */
        FALLING,
        /** On ground too steep to stand on, sliding down. */
        SLIDING,
        /** Flying. */
        FLYING,
        /** Waiting for collision data around the body. */
        LOADING,
        /** Fell out of the known world and was put back where it last stood. */
        RETURNED,
        /** Swimming in liquid deeper than {@link MovementParity#SWIM_DEPTH_FRACTION}. */
        SWIMMING
    }

    /**
     * One step's intent: forward/back, strafe right/left and up/down as -1, 0 or 1, the heading and
     * pitch in radians, whether jump is held, and the flight speed in yards per second.
     */
    public record Input(
            int forward,
            int strafe,
            int vertical,
            double yaw,
            double pitch,
            boolean jump,
            double flySpeed) {
        public Input {
            forward = Integer.signum(forward);
            strafe = Integer.signum(strafe);
            vertical = Integer.signum(vertical);
            if (!Double.isFinite(yaw)
                    || !Double.isFinite(pitch)
                    || !Double.isFinite(flySpeed)
                    || flySpeed < 0) {
                throw new IllegalArgumentException("Invalid walk input");
            }
        }

        boolean moving() {
            return forward != 0 || strafe != 0;
        }
    }

    /** Yards fallen below the last standing place before the body is put back there. */
    public static final double FALL_OUT = 500;

    /** Gap kept between the capsule and what it touches, yards. */
    static final double SKIN = .01;

    /** Most fixed steps one call integrates (0.2 s); longer gaps are dropped, not caught up. */
    static final int MAX_STEPS = 12;

    private static final double LIMIT = 1_000_000;

    private final CapsuleCollision collision = new CapsuleCollision();
    // Ground at exactly the slope limit is walkable; the margin absorbs rounding in its normal.
    private final double walkable =
            Math.cos(Math.toRadians(MovementParity.SLOPE_LIMIT_DEGREES)) - 1e-9;
    private CapsuleCollision.Shape shape =
            new CapsuleCollision.Shape(
                    MovementParity.DEFAULT_RADIUS, MovementParity.DEFAULT_HEIGHT);
    private Mode mode = Mode.WALK;
    private Status status = Status.LOADING;
    private double x, y, z, vx, vy, vz, carry;
    private boolean grounded, placed, hasSafe, firm, swimming;
    private LiquidSurface liquids = LiquidSurface.empty();
    private LiquidSurface.Kind liquid;
    private double safeX, safeY, safeZ;
    // Normal of the last surface a slide touched, or null.
    private double[] touched;

    public CapsuleCollision.Shape shape() {
        return shape;
    }

    /** Changes the capsule; the feet stay where they are. */
    public void shape(CapsuleCollision.Shape next) {
        shape = Objects.requireNonNull(next);
    }

    public Mode mode() {
        return mode;
    }

    /** Switches walking and flying; flying drops any fall, walking starts in the air. */
    public void mode(Mode next) {
        mode = Objects.requireNonNull(next);
        vx = vy = vz = 0;
        grounded = false;
        swimming = false;
        status = next == Mode.FLY ? Status.FLYING : Status.FALLING;
    }

    public Status status() {
        return status;
    }

    public boolean grounded() {
        return grounded;
    }

    /** True while the body swims ({@link Status#SWIMMING}, or waiting for collision in liquid). */
    public boolean swimming() {
        return swimming;
    }

    /** The liquid the body swims in, or null when it does not swim. */
    public LiquidSurface.Kind liquid() {
        return swimming ? liquid : null;
    }

    /** Feet depth below the surface at which this body swims and floats, yards. */
    public double swimDepth() {
        return shape.height() * MovementParity.SWIM_DEPTH_FRACTION;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    /** Feet height. */
    public double z() {
        return z;
    }

    public double verticalSpeed() {
        return vz;
    }

    /** Horizontal speed of the last step, yards per second. */
    public double horizontalSpeed() {
        return Math.hypot(vx, vy);
    }

    /**
     * Puts the feet at (x, y, z) at rest. Walking then looks straight down for ground within {@code
     * drop} yards and stands on it; with none known yet the body waits or falls from there.
     */
    public void place(double x, double y, double z, double drop, CollisionWorld world) {
        place(x, y, z, drop, world, LiquidSurface.empty());
    }

    /**
     * As {@link #place(double, double, double, double, CollisionWorld)}; a walking body whose
     * ground lies deeper than {@link #swimDepth} below the liquid surface floats at that depth,
     * swimming, unless it was placed below it.
     */
    public void place(
            double x,
            double y,
            double z,
            double drop,
            CollisionWorld world,
            LiquidSurface liquids) {
        Objects.requireNonNull(world);
        this.liquids = Objects.requireNonNull(liquids);
        swimming = false;
        this.x = clamp(x);
        this.y = clamp(y);
        this.z = clamp(z);
        vx = vy = vz = carry = 0;
        grounded = false;
        placed = true;
        hasSafe = false;
        status = mode == Mode.FLY ? Status.FLYING : Status.FALLING;
        if (mode == Mode.FLY || !(drop > 0)) {
            return;
        }
        collision.gather(world, shape, this.x, this.y, this.z, 0, 0, -drop, SKIN);
        var hit = collision.sweep(shape, this.x, this.y, this.z, 0, 0, -drop);
        if (hit != null && hit.faceNz() >= walkable) {
            this.z = this.z - hit.t() * drop + SKIN;
            grounded = true;
            status = Status.STANDING;
            remember();
        }
        var surface = liquids.at(this.x, this.y);
        if (surface != null && surface.height() - this.z > swimDepth()) {
            // Float at the surface rather than stand on the bed, unless placed deeper than that.
            this.z = Math.max(this.z, Math.min(clamp(z), surface.height() - swimDepth()));
            swim(surface);
        }
    }

    /**
     * Integrates {@code seconds} in whole fixed steps (the remainder carries to the next call).
     * Returns true when the body moved.
     */
    public boolean step(Input input, double seconds, CollisionWorld world) {
        return step(input, seconds, world, LiquidSurface.empty());
    }

    /** As {@link #step(Input, double, CollisionWorld)}, swimming in {@code liquids}. */
    public boolean step(Input input, double seconds, CollisionWorld world, LiquidSurface liquids) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(world);
        this.liquids = Objects.requireNonNull(liquids);
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Invalid walk step");
        }
        if (!placed) {
            return false;
        }
        carry += seconds;
        int steps = (int) Math.floor(carry / MovementParity.STEP_SECONDS + 1e-9);
        carry -= steps * MovementParity.STEP_SECONDS;
        if (steps > MAX_STEPS) {
            steps = MAX_STEPS;
        }
        double ox = x, oy = y, oz = z;
        for (int i = 0; i < steps; i++) {
            if (mode == Mode.FLY) {
                fly(input, world);
            } else if (swimming) {
                swim(input, world);
            } else {
                walk(input, world);
                var surface = liquids.at(x, y);
                if (status != Status.LOADING
                        && surface != null
                        && surface.height() - z > swimDepth()) {
                    swim(surface);
                }
            }
        }
        return x != ox || y != oy || z != oz;
    }

    private void walk(Input input, CollisionWorld world) {
        double dt = MovementParity.STEP_SECONDS;
        if (grounded) {
            double speed =
                    input.forward() < 0 ? MovementParity.BACKPEDAL_SPEED : MovementParity.RUN_SPEED;
            double[] dir = heading(input);
            vx = dir[0] * speed;
            vy = dir[1] * speed;
            vz = 0;
            if (input.jump()) {
                vz = MovementParity.JUMP_VELOCITY;
                grounded = false;
            }
        }
        if (!grounded) {
            vz = Math.max(vz - MovementParity.GRAVITY * dt, -MovementParity.TERMINAL_VELOCITY);
        }
        double dx = vx * dt, dy = vy * dt, dz = grounded ? 0 : vz * dt;
        double r = shape.radius();
        if (world.coverage(x - r, y - r, x + r, y + r) == CollisionWorld.Coverage.LOADING) {
            vz = 0;
            status = Status.LOADING;
            return;
        }
        if (world.coverage(
                        Math.min(x, x + dx) - r,
                        Math.min(y, y + dy) - r,
                        Math.max(x, x + dx) + r,
                        Math.max(y, y + dy) + r)
                == CollisionWorld.Coverage.LOADING) {
            // Stay out of the unknown; keep falling or standing where the footprint is known.
            dx = dy = 0;
            vx = vy = 0;
        }
        collision.gather(world, shape, x, y, z, dx, dy, dz, MovementParity.STEP_HEIGHT + 1);
        unstick();
        if (grounded) {
            ground(dx, dy);
        } else {
            air(dx, dy, dz);
        }
        if (grounded) {
            if (firm) {
                remember();
            }
            status = input.moving() ? Status.MOVING : Status.STANDING;
        } else if (hasSafe && z < safeZ - FALL_OUT) {
            x = safeX;
            y = safeY;
            z = safeZ;
            vx = vy = vz = 0;
            grounded = true;
            status = Status.RETURNED;
        } else if (status != Status.SLIDING) {
            status = Status.FALLING;
        }
    }

    /**
     * One grounded step: step up, slide, step down; a ledge starts a fall, steep ground a slide.
     */
    private void ground(double dx, double dy) {
        double step = MovementParity.STEP_HEIGHT;
        double climb = free(0, 0, step);
        double[] moved = slide(x, y, z + climb, dx, dy, 0, true);
        double drop = climb + step;
        var below = collision.sweep(shape, moved[0], moved[1], moved[2], 0, 0, -drop);
        if (below != null && supports(below, moved[2] - below.t() * drop)) {
            land(below, moved[0], moved[1], moved[2] - below.t() * drop + SKIN);
            return;
        }
        // Stepping up landed on steep ground, too high or on nothing: walk at this height instead.
        double[] level = climb > 0 ? slide(x, y, z, dx, dy, 0, true) : moved;
        if (climb > 0) {
            below = collision.sweep(shape, level[0], level[1], level[2], 0, 0, -step);
            if (below != null && supports(below, level[2] - below.t() * step)) {
                land(below, level[0], level[1], level[2] - below.t() * step + SKIN);
                return;
            }
        }
        set(level[0], level[1], level[2]);
        grounded = false;
        vz = 0;
        status = below != null ? Status.SLIDING : Status.FALLING;
        if (below != null) {
            // Rest on the steep ground and let gravity take the body down along it.
            z -= below.t() * step - SKIN;
        }
    }

    /**
     * Whether a downward contact can carry the body: a walkable triangle touched from above, at
     * most a step above the feet the body had before this step. The rounded bottom can touch the
     * rim of a ledge with its side; the rim's height, not the rounding, decides the step.
     */
    private boolean supports(CapsuleCollision.Hit hit, double feet) {
        double touchedZ = feet + shape.radius() * (1 - hit.nz());
        return hit.faceNz() >= walkable
                && hit.nz() > 0
                && touchedZ - z <= MovementParity.STEP_HEIGHT + 1e-6;
    }

    /** Stands on a supporting contact; only firm footing (not a rim) is remembered as safe. */
    private void land(CapsuleCollision.Hit hit, double nx, double ny, double nz) {
        set(nx, ny, nz);
        firm = hit.nz() >= walkable;
    }

    /** One airborne step: slide along everything; walkable ground on the way down lands. */
    private void air(double dx, double dy, double dz) {
        touched = null;
        double[] moved = slide(x, y, z, dx, dy, dz, false);
        set(moved[0], moved[1], moved[2]);
        status = Status.FALLING;
        if (touched != null) {
            double nx = touched[0], ny = touched[1], nz = touched[2];
            double into = vx * nx + vy * ny + vz * nz;
            if (into < 0) {
                vx -= nx * into;
                vy -= ny * into;
                vz -= nz * into;
            }
            if (nz > 0 && nz < walkable) {
                status = Status.SLIDING;
            }
        }
        if (vz <= 0) {
            double probe = .05;
            var below = collision.sweep(shape, x, y, z, 0, 0, -probe);
            if (below != null && below.faceNz() >= walkable) {
                z -= below.t() * probe - SKIN;
                grounded = true;
                firm = below.nz() >= walkable;
                vz = 0;
            } else if (below != null) {
                status = Status.SLIDING;
            }
        }
    }

    /** Starts swimming where the body is: any fall stops. */
    private void swim(LiquidSurface.Sample surface) {
        swimming = true;
        liquid = surface.kind();
        grounded = false;
        vx = vy = vz = 0;
        status = Status.SWIMMING;
    }

    /**
     * One swimming step: forward along the view (pitch dives or rises), strafe level, the up and
     * down keys straight up and down, at the swim speed (backwards at the swim-back speed), with no
     * gravity, sliding along everything, and never above the floating depth.
     */
    private void swim(Input input, CollisionWorld world) {
        double dt = MovementParity.STEP_SECONDS;
        double r = shape.radius();
        if (world.coverage(x - r, y - r, x + r, y + r) == CollisionWorld.Coverage.LOADING) {
            vx = vy = vz = 0;
            status = Status.LOADING;
            return;
        }
        double cp = Math.cos(input.pitch()), sp = Math.sin(input.pitch());
        double cy = Math.cos(input.yaw()), sy = Math.sin(input.yaw());
        double fx = input.forward() * cp * cy + input.strafe() * sy;
        double fy = input.forward() * cp * sy - input.strafe() * cy;
        double fz = input.forward() * sp + input.vertical();
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double speed =
                input.forward() < 0 ? MovementParity.SWIM_BACK_SPEED : MovementParity.SWIM_SPEED;
        if (len > 1e-9) {
            fx = fx / len * speed;
            fy = fy / len * speed;
            fz = fz / len * speed;
        } else {
            fx = fy = fz = 0;
        }
        double dx = fx * dt, dy = fy * dt, dz = fz * dt;
        if (world.coverage(
                        Math.min(x, x + dx) - r,
                        Math.min(y, y + dy) - r,
                        Math.max(x, x + dx) + r,
                        Math.max(y, y + dy) + r)
                == CollisionWorld.Coverage.LOADING) {
            dx = dy = 0;
        }
        // Rising stops at the floating depth: at the surface the body floats.
        var ahead = liquids.at(x + dx, y + dy);
        double top = ahead == null ? Double.POSITIVE_INFINITY : ahead.height() - swimDepth();
        var here = liquids.at(x, y);
        boolean floating = here != null && z >= here.height() - swimDepth() - 1e-3;
        if (floating && ahead != null && dz >= 0) {
            // A floating body follows the surface up and down a sloping river or lake.
            double most = MovementParity.SWIM_SPEED * dt;
            dz = Math.clamp(top - z, -most, most);
        } else {
            dz = Math.min(dz, Math.max(0, top - z));
        }
        collision.gather(world, shape, x, y, z, dx, dy, dz, 1);
        unstick();
        double ox = x, oy = y, oz = z;
        double[] moved = slide(x, y, z, dx, dy, dz, false);
        if (moved[2] > Math.max(oz, top) + 1e-9) {
            // Sliding up a bank out of the water: only walkable ground lifts the body, as when
            // walking; a steep bank is a wall.
            moved = slide(x, y, z, dx, dy, dz, true);
        }
        set(moved[0], moved[1], moved[2]);
        vx = (x - ox) / dt;
        vy = (y - oy) / dt;
        vz = (z - oz) / dt;
        var surface = liquids.at(x, y);
        double exit =
                shape.height()
                        * (MovementParity.SWIM_DEPTH_FRACTION - MovementParity.SWIM_EXIT_MARGIN);
        if (surface == null) {
            // The liquid ended: walk again, landing or falling.
            swimming = false;
            grounded = false;
            vz = 0;
            status = Status.FALLING;
            return;
        }
        if (surface.height() - z < exit) {
            // The shore lifted the feet out: walk again where the body can stand. Over a steep
            // bank or a rim it keeps swimming, so it does not fall back in and switch every step.
            double probe = .1;
            var below = collision.sweep(shape, x, y, z, 0, 0, -probe);
            double standing = below == null ? z : z - (below.t() * probe - SKIN);
            // Decided on the feet where the body would stand: the snap down to the ground must
            // not put them back below the swim depth (a short body's margin is under the probe).
            if (below != null
                    && below.faceNz() >= walkable
                    && below.nz() > 0
                    && surface.height() - standing < exit) {
                z = standing;
                swimming = false;
                grounded = true;
                firm = below.nz() >= walkable;
                vz = 0;
                status = input.moving() ? Status.MOVING : Status.STANDING;
                return;
            }
        }
        liquid = surface.kind();
        status = Status.SWIMMING;
    }

    private void fly(Input input, CollisionWorld world) {
        double dt = MovementParity.STEP_SECONDS;
        double cp = Math.cos(input.pitch()), sp = Math.sin(input.pitch());
        double cy = Math.cos(input.yaw()), sy = Math.sin(input.yaw());
        double fx = input.forward() * cp * cy + input.strafe() * sy;
        double fy = input.forward() * cp * sy - input.strafe() * cy;
        double fz = input.forward() * sp + input.vertical();
        double len = Math.sqrt(fx * fx + fy * fy + fz * fz);
        double speed = input.flySpeed();
        if (len > 1e-9) {
            fx = fx / len * speed;
            fy = fy / len * speed;
            fz = fz / len * speed;
        }
        vx = fx;
        vy = fy;
        vz = fz;
        grounded = false;
        status = Status.FLYING;
        double dx = fx * dt, dy = fy * dt, dz = fz * dt;
        // Flight never waits: it collides with what is known and says when that is not all.
        if (collision.gather(world, shape, x, y, z, dx, dy, dz, 1)
                == CollisionWorld.Coverage.LOADING) {
            status = Status.LOADING;
        }
        unstick();
        double[] moved = slide(x, y, z, dx, dy, dz, false);
        set(moved[0], moved[1], moved[2]);
    }

    /**
     * How far the capsule can move straight by (dx, dy, dz), as a fraction of 1 times the length.
     */
    private double free(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var hit = collision.sweep(shape, x, y, z, dx, dy, dz);
        return hit == null ? length : Math.max(0, hit.t() * length - SKIN);
    }

    /**
     * Collide and slide from (px, py, pz) by the move, up to four contacts. With {@code walls},
     * surfaces steeper than the slope limit (and ceilings) push back only horizontally, so a
     * grounded body never climbs them.
     */
    private double[] slide(
            double px, double py, double pz, double dx, double dy, double dz, boolean walls) {
        for (int i = 0; i < 4; i++) {
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length < 1e-9) {
                break;
            }
            var hit = collision.sweep(shape, px, py, pz, dx, dy, dz);
            if (hit == null) {
                px += dx;
                py += dy;
                pz += dz;
                break;
            }
            double t = Math.max(0, hit.t() - SKIN / length);
            px += dx * t;
            py += dy * t;
            pz += dz * t;
            dx *= 1 - t;
            dy *= 1 - t;
            dz *= 1 - t;
            double nx = hit.nx(), ny = hit.ny(), nz = hit.nz();
            touched = new double[] {nx, ny, nz};
            if (walls && nz < walkable) {
                double h = Math.hypot(nx, ny);
                if (h < 1e-9) {
                    break;
                }
                nx /= h;
                ny /= h;
                nz = 0;
            }
            double into = dx * nx + dy * ny + dz * nz;
            dx -= nx * into;
            dy -= ny * into;
            dz -= nz * into;
        }
        return new double[] {px, py, pz};
    }

    private void unstick() {
        double[] at = collision.depenetrate(shape, x, y, z, 1e-4);
        set(at[0], at[1], at[2]);
    }

    private void set(double nx, double ny, double nz) {
        x = clamp(nx);
        y = clamp(ny);
        z = clamp(nz);
    }

    private void remember() {
        safeX = x;
        safeY = y;
        safeZ = z;
        hasSafe = true;
    }

    /** Unit horizontal direction of the movement keys, or zero. */
    private static double[] heading(Input input) {
        double c = Math.cos(input.yaw()), s = Math.sin(input.yaw());
        double hx = input.forward() * c + input.strafe() * s;
        double hy = input.forward() * s - input.strafe() * c;
        double len = Math.hypot(hx, hy);
        return len < 1e-9 ? new double[] {0, 0} : new double[] {hx / len, hy / len};
    }

    private static double clamp(double value) {
        return Math.clamp(value, -LIMIT, LIMIT);
    }
}
