package com.vibeminer.rendering.models;

import java.nio.FloatBuffer;

/** Shared native-Z-up bind/rest camera, eye-space material coordinates and projection. */
public final class PreviewCamera {
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public Bounds {
            if (!Float.isFinite(minX)
                    || !Float.isFinite(minY)
                    || !Float.isFinite(minZ)
                    || !Float.isFinite(maxX)
                    || !Float.isFinite(maxY)
                    || !Float.isFinite(maxZ)
                    || minX > maxX
                    || minY > maxY
                    || minZ > maxZ) {
                throw new IllegalArgumentException("Invalid preview bounds");
            }
        }

        public static Bounds of(PreviewMesh mesh) {
            return new Bounds(
                    mesh.minimum(0),
                    mesh.minimum(1),
                    mesh.minimum(2),
                    mesh.maximum(0),
                    mesh.maximum(1),
                    mesh.maximum(2));
        }

        public static Bounds of(MeshAppearance appearance) {
            return new Bounds(
                    appearance.minimum(0),
                    appearance.minimum(1),
                    appearance.minimum(2),
                    appearance.maximum(0),
                    appearance.maximum(1),
                    appearance.maximum(2));
        }
    }

    public record Eye(double x, double y, double z) {}

    public record TexCoord(double u, double v) {}

    private final int width, height;
    private final double cx, cy, cz, sy, cyaw, sp, cp, focal, radius, distance, near, far;
    private final float[] eyeMatrix = new float[16],
            projectionMatrix = new float[16],
            clipMatrix = new float[16],
            nativeLight = new float[4];

    private PreviewCamera(
            Bounds bounds,
            int width,
            int height,
            StaticMeshRenderer.View view,
            boolean homogeneousDepth) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192) {
            throw new IllegalArgumentException("Preview image exceeds bounds");
        }
        this.width = width;
        this.height = height;
        // Preserve legacy CPU center/delta rounding for appearances without card-bound expansion.
        cx = (bounds.minX() + bounds.maxX()) / 2.0;
        cy = (bounds.minY() + bounds.maxY()) / 2.0;
        cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        double dx = bounds.maxX() - bounds.minX(),
                dy = bounds.maxY() - bounds.minY(),
                dz = bounds.maxZ() - bounds.minZ();
        radius = Math.max(.001, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2);
        distance = radius * 3.2 / view.zoom();
        near = radius * .05;
        far = distance + radius * 2;
        focal = Math.min(width, height) * 1.25;
        sy = Math.sin(view.yaw());
        cyaw = Math.cos(view.yaw());
        sp = Math.sin(view.pitch());
        cp = Math.cos(view.pitch());
        double[] horizontal = {sy, cyaw, 0, -sy * cx - cyaw * cy};
        double[] vertical = {-cyaw * sp, sy * sp, cp, cyaw * sp * cx - sy * sp * cy - cp * cz};
        double[] depth = {
            -cyaw * cp, sy * cp, -sp, distance + cyaw * cp * cx - sy * cp * cy + sp * cz
        };
        double a = homogeneousDepth ? (far + near) / (far - near) : far / (far - near);
        double b = homogeneousDepth ? -2 * far * near / (far - near) : -far * near / (far - near);
        for (int column = 0; column < 4; column++) {
            eyeMatrix[column * 4] = (float) horizontal[column];
            eyeMatrix[column * 4 + 1] = (float) vertical[column];
            eyeMatrix[column * 4 + 2] = (float) -depth[column];
            eyeMatrix[column * 4 + 3] = column == 3 ? 1 : 0;
            clipMatrix[column * 4] = (float) (2 * focal / width * horizontal[column]);
            clipMatrix[column * 4 + 1] =
                    (float) (2 * focal / height * vertical[column] - .06 * depth[column]);
            clipMatrix[column * 4 + 2] = (float) (a * depth[column] + (column == 3 ? b : 0));
            clipMatrix[column * 4 + 3] = (float) depth[column];
        }
        projectionMatrix[0] = (float) (2 * focal / width);
        projectionMatrix[5] = (float) (2 * focal / height);
        projectionMatrix[9] = .06f;
        projectionMatrix[10] = (float) -a;
        projectionMatrix[11] = -1;
        projectionMatrix[14] = (float) b;
        nativeLight[0] = (float) (-.35 * sy - .6 * cyaw * sp + .72 * cyaw * cp);
        nativeLight[1] = (float) (-.35 * cyaw + .6 * sy * sp - .72 * sy * cp);
        nativeLight[2] = (float) (.6 * cp);
    }

    public static PreviewCamera create(
            Bounds bounds,
            int width,
            int height,
            StaticMeshRenderer.View view,
            boolean homogeneousDepth) {
        return new PreviewCamera(
                java.util.Objects.requireNonNull(bounds),
                width,
                height,
                java.util.Objects.requireNonNull(view),
                homogeneousDepth);
    }

    public Eye modelToEye(double x, double y, double z) {
        x -= cx;
        y -= cy;
        z -= cz;
        return new Eye(
                y * cyaw + x * sy,
                z * cp - (x * cyaw - y * sy) * sp,
                (x * cyaw - y * sy) * cp + z * sp - distance);
    }

    public Eye normalToEye(double x, double y, double z) {
        return new Eye(
                y * cyaw + x * sy,
                z * cp - (x * cyaw - y * sy) * sp,
                (x * cyaw - y * sy) * cp + z * sp);
    }

    public Eye billboardToEye(MeshAppearance.Vec3 pivot, double x, double y, double z) {
        var center = modelToEye(pivot.x(), pivot.y(), pivot.z());
        return new Eye(
                center.x() + y - pivot.y(), center.y() + z - pivot.z(), center.z() - x + pivot.x());
    }

    /** Per-vertex GL sphere-map coordinates; interpolate these UVs, never interpolated normals. */
    public static TexCoord environment(Eye position, Eye normal) {
        double pp =
                position.x() * position.x()
                        + position.y() * position.y()
                        + position.z() * position.z();
        double nn = normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z();
        if (!Double.isFinite(pp) || !Double.isFinite(nn) || pp <= 1e-12 || nn <= 1e-12) {
            return new TexCoord(.5, .5);
        }
        double p = Math.sqrt(pp),
                n = Math.sqrt(nn),
                ux = position.x() / p,
                uy = position.y() / p,
                uz = position.z() / p;
        double nx = normal.x() / n,
                ny = normal.y() / n,
                nz = normal.z() / n,
                dot = nx * ux + ny * uy + nz * uz;
        double fx = ux - 2 * nx * dot, fy = uy - 2 * ny * dot, fz = uz - 2 * nz * dot;
        double q = fx * fx + fy * fy + (fz + 1) * (fz + 1);
        if (!Double.isFinite(q) || q <= 1e-12) {
            return new TexCoord(.5, .5);
        }
        double m = 2 * Math.sqrt(q);
        return new TexCoord(.5 + fx / m, .5 + fy / m);
    }

    public double screenX(Eye eye) {
        return width * .5 + eye.x() * focal / -eye.z();
    }

    public double screenY(Eye eye) {
        return height * .53 - eye.y() * focal / -eye.z();
    }

    public double lighting(double nx, double ny, double nz) {
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double value =
                length == 0
                        ? 0
                        : Math.abs(
                                        (ny * cyaw + nx * sy) * -.35
                                                + (nz * cp - (nx * cyaw - ny * sy) * sp) * .6
                                                + (nx * cyaw - ny * sy) * cp * .72)
                                / length;
        return .28 + .72 * Math.min(1, value);
    }

    /** Diagnostic face lighting for geometry already transformed into the eye basis. */
    public double lightingEye(double nx, double ny, double nz) {
        return lighting(
                sy * nx - cyaw * sp * ny + cyaw * cp * nz,
                cyaw * nx + sy * sp * ny - sy * cp * nz,
                cp * ny + sp * nz);
    }

    public FloatBuffer modelToEye() {
        return FloatBuffer.wrap(eyeMatrix).asReadOnlyBuffer();
    }

    public FloatBuffer projection() {
        return FloatBuffer.wrap(projectionMatrix).asReadOnlyBuffer();
    }

    public FloatBuffer modelToClip() {
        return FloatBuffer.wrap(clipMatrix).asReadOnlyBuffer();
    }

    public FloatBuffer lightDirection() {
        return FloatBuffer.wrap(nativeLight).asReadOnlyBuffer();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double radius() {
        return radius;
    }

    public double near() {
        return near;
    }

    public double far() {
        return far;
    }

    public double cameraDistance() {
        return distance;
    }
}
