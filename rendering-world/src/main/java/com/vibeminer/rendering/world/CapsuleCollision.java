package com.vibeminer.rendering.world;

import java.util.Arrays;
import java.util.Objects;

/**
 * Swept upright-capsule collision against a triangle soup. The capsule stands on its feet point and
 * is modelled as a vertical stack of equal spheres from the bottom sphere (centre one radius above
 * the feet) to the top sphere (one radius below the head), at most one radius apart, so the bottom
 * and top are exact hemispheres and the sides deviate from a true capsule by less than 14% of the
 * radius between spheres. Triangles are two-sided. Each sphere is swept with the plane/vertex/edge
 * tests of Fauerby's "Improved Collision detection and Response" (2003), generalised to any radius;
 * the earliest contact of any sphere wins.
 *
 * <p>Not thread-safe: one instance per moving body, reusing its triangle buffer.
 */
public final class CapsuleCollision {
    /** An upright capsule: radius and full height in yards, feet at the origin. */
    public record Shape(double radius, double height) {
        public Shape {
            if (!Double.isFinite(radius)
                    || !Double.isFinite(height)
                    || radius <= 0
                    || height < 2 * radius
                    || height > 100) {
                throw new IllegalArgumentException("Invalid capsule");
            }
        }

        /** Sphere centre heights above the feet, bottom first. */
        double[] spheres() {
            double span = height - 2 * radius;
            int gaps = Math.max(0, (int) Math.ceil(span / radius - 1e-9));
            double[] out = new double[gaps + 1];
            for (int i = 0; i <= gaps; i++) {
                out[i] = radius + (gaps == 0 ? 0 : span * i / gaps);
            }
            return out;
        }
    }

    /**
     * The earliest contact of a sweep: fraction of the move, the unit contact normal (from the
     * touched point toward the sphere centre), and the vertical part of the touched triangle's own
     * normal turned toward the sphere. At an edge or corner shared by several triangles, the most
     * upward-facing one is reported, so the rim of a floor still reads as floor.
     */
    public record Hit(double t, double nx, double ny, double nz, double faceNz) {}

    /** Contacts this close in time count as the same contact. */
    private static final double TIE = 1e-7;

    // The best contact of the sweep in progress.
    private double bestT, bestX, bestY, bestZ, bestFace;
    private boolean found;

    /** Push-out passes; corners need several, each pass resolving every overlap it finds. */
    static final int DEPENETRATION_PASSES = 16;

    private double[] triangles = new double[9 * 256];
    private int count;

    /** Loads every triangle of {@code world} that may touch the box around the given feet path. */
    public CollisionWorld.Coverage gather(
            CollisionWorld world,
            Shape shape,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            double margin) {
        Objects.requireNonNull(world);
        double r = shape.radius() + margin;
        double x0 = Math.min(x, x + dx) - r, x1 = Math.max(x, x + dx) + r;
        double y0 = Math.min(y, y + dy) - r, y1 = Math.max(y, y + dy) + r;
        double z0 = Math.min(z, z + dz) - margin,
                z1 = Math.max(z, z + dz) + shape.height() + margin;
        count = 0;
        world.triangles(x0, y0, z0, x1, y1, z1, this::add);
        return world.coverage(x0, y0, x1, y1);
    }

    /** Triangles currently gathered. */
    public int triangles() {
        return count;
    }

    private void add(
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            double cx,
            double cy,
            double cz) {
        if (count * 9 + 9 > triangles.length) {
            if (triangles.length >= 9 * (1 << 20)) {
                return;
            }
            triangles = Arrays.copyOf(triangles, triangles.length * 2);
        }
        int at = count++ * 9;
        triangles[at] = ax;
        triangles[at + 1] = ay;
        triangles[at + 2] = az;
        triangles[at + 3] = bx;
        triangles[at + 4] = by;
        triangles[at + 5] = bz;
        triangles[at + 6] = cx;
        triangles[at + 7] = cy;
        triangles[at + 8] = cz;
    }

    /**
     * The earliest contact when the capsule's feet move by (dx, dy, dz) from (x, y, z) through the
     * gathered triangles, or null when the whole move is free. Contacts the capsule is moving away
     * from are ignored, so a body resting on a surface can always leave it.
     */
    public Hit sweep(Shape shape, double x, double y, double z, double dx, double dy, double dz) {
        Hit best = null;
        if (dx == 0 && dy == 0 && dz == 0) {
            return null;
        }
        for (double offset : shape.spheres()) {
            var hit =
                    sweepSphere(
                            x,
                            y,
                            z + offset,
                            dx,
                            dy,
                            dz,
                            shape.radius(),
                            best == null ? 1 : best.t());
            if (hit != null) {
                best = hit;
            }
        }
        return best;
    }

    /**
     * Pushes the capsule out of every gathered triangle it overlaps by more than {@code skin}, a
     * few passes at most; returns the corrected feet position.
     */
    public double[] depenetrate(Shape shape, double x, double y, double z, double skin) {
        double[] at = {x, y, z};
        double[] spheres = shape.spheres();
        double r = shape.radius();
        double[] closest = new double[3];
        for (int pass = 0; pass < DEPENETRATION_PASSES; pass++) {
            boolean moved = false;
            for (int i = 0; i < count; i++) {
                for (double offset : spheres) {
                    double cx = at[0], cy = at[1], cz = at[2] + offset;
                    closestPoint(i * 9, cx, cy, cz, closest);
                    double ex = cx - closest[0], ey = cy - closest[1], ez = cz - closest[2];
                    double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
                    if (d >= r - skin) {
                        continue;
                    }
                    double push = r - d;
                    if (d < 1e-9) {
                        double[] n = normal(i * 9);
                        if (n == null) {
                            continue;
                        }
                        // Exactly on the surface: leave on the side the capsule's middle is on,
                        // or the upward side when that is on the surface too.
                        double side =
                                n[0] * (at[0] - closest[0])
                                        + n[1] * (at[1] - closest[1])
                                        + n[2] * (at[2] + shape.height() / 2 - closest[2]);
                        double sign = Math.abs(side) > 1e-9 ? Math.signum(side) : n[2] < 0 ? -1 : 1;
                        ex = n[0] * sign;
                        ey = n[1] * sign;
                        ez = n[2] * sign;
                        d = 1;
                    }
                    at[0] += ex / d * push;
                    at[1] += ey / d * push;
                    at[2] += ez / d * push;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return at;
    }

    private Hit sweepSphere(
            double cx,
            double cy,
            double cz,
            double vx,
            double vy,
            double vz,
            double r,
            double limit) {
        bestT = limit;
        bestFace = -2;
        found = false;
        double vv = vx * vx + vy * vy + vz * vz;
        for (int i = 0; i < count; i++) {
            int at = i * 9;
            double[] t = triangles;
            double ax = t[at], ay = t[at + 1], az = t[at + 2];
            double bx = t[at + 3], by = t[at + 4], bz = t[at + 5];
            double qx = t[at + 6], qy = t[at + 7], qz = t[at + 8];
            double ux = bx - ax, uy = by - ay, uz = bz - az;
            double wx = qx - ax, wy = qy - ay, wz = qz - az;
            double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) {
                continue;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            double d0 = nx * (cx - ax) + ny * (cy - ay) + nz * (cz - az);
            if (d0 < 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
                d0 = -d0;
            }
            double nv = nx * vx + ny * vy + nz * vz;
            double t0;
            boolean embedded = d0 < r;
            if (embedded) {
                t0 = 0;
            } else {
                if (nv >= 0) {
                    continue;
                }
                t0 = (d0 - r) / -nv;
                if (t0 > bestT + TIE) {
                    continue;
                }
            }
            // Plane interior contact.
            double px = cx + t0 * vx - (embedded ? d0 : r) * nx;
            double py = cy + t0 * vy - (embedded ? d0 : r) * ny;
            double pz = cz + t0 * vz - (embedded ? d0 : r) * nz;
            if (inside(px, py, pz, ax, ay, az, bx, by, bz, qx, qy, qz, nx, ny, nz)) {
                if (nv < 0) {
                    offer(t0, nx, ny, nz, nz);
                }
                continue;
            }
            // Vertices.
            for (int k = 0; k < 3; k++) {
                double sx = t[at + k * 3], sy = t[at + k * 3 + 1], sz = t[at + k * 3 + 2];
                double ox = cx - sx, oy = cy - sy, oz = cz - sz;
                double root =
                        lowestRoot(
                                vv,
                                2 * (vx * ox + vy * oy + vz * oz),
                                ox * ox + oy * oy + oz * oz - r * r,
                                bestT + TIE);
                if (!Double.isNaN(root)) {
                    double ex = ox + root * vx, ey = oy + root * vy, ez = oz + root * vz;
                    if (ex * vx + ey * vy + ez * vz < 0) {
                        double el = Math.sqrt(ex * ex + ey * ey + ez * ez);
                        offer(root, ex / el, ey / el, ez / el, nz);
                    }
                }
            }
            // Edges.
            for (int k = 0; k < 3; k++) {
                int p = at + k * 3, q = at + (k + 1) % 3 * 3;
                double sx = t[p], sy = t[p + 1], sz = t[p + 2];
                double ex = t[q] - sx, ey = t[q + 1] - sy, ez = t[q + 2] - sz;
                double bx2 = sx - cx, by2 = sy - cy, bz2 = sz - cz;
                double ee = ex * ex + ey * ey + ez * ez;
                double ev = ex * vx + ey * vy + ez * vz;
                double eb = ex * bx2 + ey * by2 + ez * bz2;
                double a = ee * -vv + ev * ev;
                double b = ee * (2 * (vx * bx2 + vy * by2 + vz * bz2)) - 2 * ev * eb;
                double c = ee * (r * r - (bx2 * bx2 + by2 * by2 + bz2 * bz2)) + eb * eb;
                double root = lowestRoot(a, b, c, bestT + TIE);
                if (Double.isNaN(root)) {
                    continue;
                }
                double f = (ev * root - eb) / ee;
                if (f < 0 || f > 1) {
                    continue;
                }
                double kx = cx + root * vx - (sx + f * ex);
                double ky = cy + root * vy - (sy + f * ey);
                double kz = cz + root * vz - (sz + f * ez);
                if (kx * vx + ky * vy + kz * vz >= 0) {
                    continue;
                }
                double kl = Math.sqrt(kx * kx + ky * ky + kz * kz);
                offer(root, kx / kl, ky / kl, kz / kl, nz);
            }
        }
        return found ? new Hit(bestT, bestX, bestY, bestZ, bestFace) : null;
    }

    /** Keeps the earlier contact; between ties, the one whose triangle faces most upward. */
    private void offer(double t, double nx, double ny, double nz, double face) {
        if (found && !(t < bestT - TIE || t <= bestT + TIE && face > bestFace)) {
            return;
        }
        bestT = found ? Math.min(t, bestT) : t;
        bestX = nx;
        bestY = ny;
        bestZ = nz;
        bestFace = face;
        found = true;
    }

    /**
     * The smallest root of a t^2 + b t + c = 0 in [0, limit), 0 when t = 0 lies between the roots
     * (already overlapping), or NaN.
     */
    static double lowestRoot(double a, double b, double c, double limit) {
        if (Math.abs(a) < 1e-14) {
            return Double.NaN;
        }
        double disc = b * b - 4 * a * c;
        if (disc < 0) {
            return Double.NaN;
        }
        double sq = Math.sqrt(disc);
        double r1 = (-b - sq) / (2 * a), r2 = (-b + sq) / (2 * a);
        if (r1 > r2) {
            double swap = r1;
            r1 = r2;
            r2 = swap;
        }
        if (r1 >= 0 && r1 < limit) {
            return r1;
        }
        // Already overlapping at the start: contact now. Callers keep it only when the move goes
        // further in, so a body can always move out of a surface it touches.
        if (r1 < 0 && r2 > 0 && limit > 0) {
            return 0;
        }
        return Double.NaN;
    }

    private static boolean inside(
            double px,
            double py,
            double pz,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            double cx,
            double cy,
            double cz,
            double nx,
            double ny,
            double nz) {
        return side(px, py, pz, ax, ay, az, bx, by, bz, nx, ny, nz)
                        && side(px, py, pz, bx, by, bz, cx, cy, cz, nx, ny, nz)
                        && side(px, py, pz, cx, cy, cz, ax, ay, az, nx, ny, nz)
                || side(px, py, pz, bx, by, bz, ax, ay, az, nx, ny, nz)
                        && side(px, py, pz, cx, cy, cz, bx, by, bz, nx, ny, nz)
                        && side(px, py, pz, ax, ay, az, cx, cy, cz, nx, ny, nz);
    }

    private static boolean side(
            double px,
            double py,
            double pz,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            double nx,
            double ny,
            double nz) {
        double ex = bx - ax, ey = by - ay, ez = bz - az;
        double wx = px - ax, wy = py - ay, wz = pz - az;
        double cx = ey * wz - ez * wy, cy = ez * wx - ex * wz, cz = ex * wy - ey * wx;
        return cx * nx + cy * ny + cz * nz >= -1e-12;
    }

    private double[] normal(int at) {
        double[] t = triangles;
        double ux = t[at + 3] - t[at], uy = t[at + 4] - t[at + 1], uz = t[at + 5] - t[at + 2];
        double wx = t[at + 6] - t[at], wy = t[at + 7] - t[at + 1], wz = t[at + 8] - t[at + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return len < 1e-12 ? null : new double[] {nx / len, ny / len, nz / len};
    }

    /**
     * Closest point of gathered triangle {@code at} to p (Ericson, Real-Time Collision Detection
     * 5.1.5).
     */
    private void closestPoint(int at, double px, double py, double pz, double[] out) {
        double[] t = triangles;
        double ax = t[at], ay = t[at + 1], az = t[at + 2];
        double abx = t[at + 3] - ax, aby = t[at + 4] - ay, abz = t[at + 5] - az;
        double acx = t[at + 6] - ax, acy = t[at + 7] - ay, acz = t[at + 8] - az;
        double apx = px - ax, apy = py - ay, apz = pz - az;
        double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            set(out, ax, ay, az);
            return;
        }
        double bpx = px - t[at + 3], bpy = py - t[at + 4], bpz = pz - t[at + 5];
        double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            set(out, t[at + 3], t[at + 4], t[at + 5]);
            return;
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double v = d1 / (d1 - d3);
            set(out, ax + v * abx, ay + v * aby, az + v * abz);
            return;
        }
        double cpx = px - t[at + 6], cpy = py - t[at + 7], cpz = pz - t[at + 8];
        double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            set(out, t[at + 6], t[at + 7], t[at + 8]);
            return;
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double w = d2 / (d2 - d6);
            set(out, ax + w * acx, ay + w * acy, az + w * acz);
            return;
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && d4 - d3 >= 0 && d5 - d6 >= 0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            double bx = t[at + 3], by = t[at + 4], bz = t[at + 5];
            set(
                    out,
                    bx + w * (t[at + 6] - bx),
                    by + w * (t[at + 7] - by),
                    bz + w * (t[at + 8] - bz));
            return;
        }
        double denom = 1 / (va + vb + vc);
        double v = vb * denom, w = vc * denom;
        set(out, ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
    }

    private static void set(double[] out, double x, double y, double z) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
    }
}
