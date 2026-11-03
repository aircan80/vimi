package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.PreviewCamera;
import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.nio.FloatBuffer;
import java.util.Objects;

/** Shader matrices and legacy light vectors delegated to the shared preview camera contract. */
public final class GpuCamera {
    private final PreviewCamera camera;
    private final int width, height;

    private GpuCamera(
            GpuModelScene scene,
            int width,
            int height,
            StaticMeshRenderer.View view,
            boolean homogeneousDepth) {
        this.width = width;
        this.height = height;
        camera =
                PreviewCamera.create(
                        new PreviewCamera.Bounds(
                                scene.minimum(0),
                                scene.minimum(1),
                                scene.minimum(2),
                                scene.maximum(0),
                                scene.maximum(1),
                                scene.maximum(2)),
                        width,
                        height,
                        view,
                        homogeneousDepth);
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

    public FloatBuffer modelToClip() {
        return camera.modelToClip();
    }

    public FloatBuffer modelToEye() {
        return camera.modelToEye();
    }

    public FloatBuffer projection() {
        return camera.projection();
    }

    public FloatBuffer lightDirection() {
        return camera.lightDirection();
    }

    public FloatBuffer eyeLightDirection() {
        var light = camera.lightDirection();
        var eye = camera.normalToEye(light.get(0), light.get(1), light.get(2));
        return FloatBuffer.wrap(new float[] {(float) eye.x(), (float) eye.y(), (float) eye.z(), 0})
                .asReadOnlyBuffer();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double radius() {
        return camera.radius();
    }

    public double near() {
        return camera.near();
    }

    public double far() {
        return camera.far();
    }

    public double cameraDistance() {
        return camera.cameraDistance();
    }
}
