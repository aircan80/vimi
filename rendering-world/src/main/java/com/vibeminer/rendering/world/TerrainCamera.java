package com.vibeminer.rendering.world;

import java.nio.FloatBuffer;
import java.util.Objects;

/** Native +X north/+Y west/+Z up camera; positive yaw turns left, positive strafe moves right. */
public record TerrainCamera(double x, double y, double z, double yaw, double pitch) {
    public static final double NEAR = .5;
    public static final double WHOLE_TRIANGLE_FAR = 2200;

    public record Eye(double right, double up, double depth) {}

    public TerrainCamera {
        for (double value : new double[] {x, y, z, yaw, pitch}) {
            if (!Double.isFinite(value) || Math.abs(value) > TerrainScene.MAX_COORDINATE) {
                throw new IllegalArgumentException("Invalid terrain camera");
            }
        }
        if (Math.abs(pitch) > 1.5) {
            throw new IllegalArgumentException("Invalid terrain camera pitch");
        }
    }

    public static TerrainCamera from(TerrainRenderer.Camera camera) {
        Objects.requireNonNull(camera);
        return new TerrainCamera(camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
    }

    public TerrainRenderer.Camera software() {
        return new TerrainRenderer.Camera(x, y, z, yaw, pitch);
    }

    public TerrainCamera move(double forward, double strafe, double vertical) {
        return new TerrainCamera(
                x + Math.cos(yaw) * forward + Math.sin(yaw) * strafe,
                y + Math.sin(yaw) * forward - Math.cos(yaw) * strafe,
                z + vertical,
                yaw,
                pitch);
    }

    public TerrainCamera look(double turn, double tilt) {
        return new TerrainCamera(x, y, z, yaw + turn, Math.clamp(pitch + tilt, -1.5, 1.5));
    }

    public Eye eye(double worldX, double worldY, double worldZ) {
        double dx = worldX - x, dy = worldY - y, dz = worldZ - z;
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double forward = cy * dx + sy * dy;
        return new Eye(sy * dx - cy * dy, cp * dz - sp * forward, sp * dz + cp * forward);
    }

    /** Column-major transform of coordinates already rebased around this immutable scene origin. */
    public FloatBuffer modelToEye(TerrainScene.Origin origin) {
        Objects.requireNonNull(origin);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        double dx = origin.x() - x, dy = origin.y() - y, dz = origin.z() - z;
        return FloatBuffer.wrap(
                        new float[] {
                            (float) sy,
                            (float) (-sp * cy),
                            (float) (-cp * cy),
                            0,
                            (float) -cy,
                            (float) (-sp * sy),
                            (float) (-cp * sy),
                            0,
                            0,
                            (float) cp,
                            (float) -sp,
                            0,
                            (float) (sy * dx - cy * dy),
                            (float) (cp * dz - sp * (cy * dx + sy * dy)),
                            (float) (-sp * dz - cp * (cy * dx + sy * dy)),
                            1
                        })
                .asReadOnlyBuffer();
    }

    /** Window depth=near/positiveEyeDepth; no finite far clipping plane. */
    public static FloatBuffer reversedProjection(int width, int height) {
        dimensions(width, height);
        return FloatBuffer.wrap(
                        new float[] {
                            1.6f,
                            0,
                            0,
                            0,
                            0,
                            1.6f * width / height,
                            0,
                            0,
                            0,
                            0,
                            0,
                            -1,
                            0,
                            0,
                            (float) NEAR,
                            0
                        })
                .asReadOnlyBuffer();
    }

    public static double screenX(Eye eye, int width, int height) {
        dimensions(width, height);
        return width * .5 + eye.right * width * .8 / eye.depth;
    }

    public static double screenY(Eye eye, int width, int height) {
        dimensions(width, height);
        return height * .5 - eye.up * width * .8 / eye.depth;
    }

    private static void dimensions(int width, int height) {
        if (!WorldFrameLimits.fits(width, height)) {
            throw new IllegalArgumentException("Invalid terrain frame dimensions");
        }
    }
}
