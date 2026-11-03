package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.nio.FloatBuffer;
import java.util.Objects;

/** Shader-ready camera preserving the existing native-Z-up static-preview orientation/framing. */
public final class GpuCamera {
    private final int width;
    private final int height;
    private final double radius;
    private final double cameraDistance;
    private final double near;
    private final double far;
    private final float[] modelToClip;
    private final float[] lightDirection;

    private GpuCamera(
            GpuModelScene scene,
            int width,
            int height,
            StaticMeshRenderer.View view,
            boolean homogeneousDepth) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192) {
            throw new IllegalArgumentException("Preview image exceeds bounds");
        }
        this.width = width;
        this.height = height;
        double cx = (scene.minimum(0) + (double) scene.maximum(0)) * 0.5;
        double cy = (scene.minimum(1) + (double) scene.maximum(1)) * 0.5;
        double cz = (scene.minimum(2) + (double) scene.maximum(2)) * 0.5;
        double dx = scene.maximum(0) - (double) scene.minimum(0);
        double dy = scene.maximum(1) - (double) scene.minimum(1);
        double dz = scene.maximum(2) - (double) scene.minimum(2);
        radius = Math.max(0.001, Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5);
        cameraDistance = radius * 3.2 / view.zoom();
        near = radius * 0.05;
        // The centered bounding sphere extends at most radius along the view axis.
        far = cameraDistance + radius * 2;
        double focal = Math.min(width, height) * 1.25;
        double sy = Math.sin(view.yaw()), cyaw = Math.cos(view.yaw());
        double sp = Math.sin(view.pitch()), cp = Math.cos(view.pitch());
        double[] horizontal = {sy, cyaw, 0, -sy * cx - cyaw * cy};
        double[] vertical = {-cyaw * sp, sy * sp, cp, cyaw * sp * cx - sy * sp * cy - cp * cz};
        double[] distance = {
            -cyaw * cp, sy * cp, -sp, cameraDistance + cyaw * cp * cx - sy * cp * cy + sp * cz
        };
        double a = homogeneousDepth ? (far + near) / (far - near) : far / (far - near);
        double b = homogeneousDepth ? -2 * far * near / (far - near) : -far * near / (far - near);
        modelToClip = new float[16];
        for (int column = 0; column < 4; column++) {
            modelToClip[column * 4] = (float) (2 * focal / width * horizontal[column]);
            modelToClip[column * 4 + 1] =
                    (float) (2 * focal / height * vertical[column] - 0.06 * distance[column]);
            modelToClip[column * 4 + 2] = (float) (a * distance[column] + (column == 3 ? b : 0));
            modelToClip[column * 4 + 3] = (float) distance[column];
        }
        // Reproduce StaticMeshRenderer.light exactly, including its legacy pitch convention.
        // Do not normalize this vector: the CPU computes abs(dot)/length(normal), then clamps.
        lightDirection =
                new float[] {
                    (float) (-0.35 * sy - 0.6 * cyaw * sp + 0.72 * cyaw * cp),
                    (float) (-0.35 * cyaw + 0.6 * sy * sp - 0.72 * sy * cp),
                    (float) (0.6 * cp),
                    0
                };
    }

    public static GpuCamera create(
            GpuModelScene scene,
            int width,
            int height,
            StaticMeshRenderer.View view,
            boolean homogeneousDepth) {
        return new GpuCamera(
                Objects.requireNonNull(scene),
                width,
                height,
                Objects.requireNonNull(view),
                homogeneousDepth);
    }

    /** Column-major native-position to clip transform; clip W is positive camera distance. */
    public FloatBuffer modelToClip() {
        return FloatBuffer.wrap(modelToClip).asReadOnlyBuffer();
    }

    /** Native-space light XYZ and zero padding; shader intensity=.28+.72*min(1,abs(dot)/length). */
    public FloatBuffer lightDirection() {
        return FloatBuffer.wrap(lightDirection).asReadOnlyBuffer();
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
        return cameraDistance;
    }
}
