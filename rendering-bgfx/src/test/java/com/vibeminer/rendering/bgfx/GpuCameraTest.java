package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.nio.FloatBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class GpuCameraTest {
    private static GpuModelScene scene() {
        return GpuModelScene.create(
                new PreviewMesh(
                        new float[] {10, 18, 27, 14, 18, 27, 12, 24, 35},
                        new int[] {0, 1, 2},
                        List.of(new PreviewMesh.Part(9, 0, 3))),
                null);
    }

    private static double[] transform(FloatBuffer matrix, double x, double y, double z) {
        double[] point = {x, y, z, 1};
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                result[row] += matrix.get(column * 4 + row) * point[column];
            }
        }
        return result;
    }

    @Test
    void columnMajorTransformMatchesCpuCameraAtMultipleAnglesSizesAndDepthConventions() {
        var scene = scene();
        for (boolean homogeneous : List.of(false, true)) {
            for (double yaw : new double[] {-.3, 0, 1.7}) {
                for (double pitch : new double[] {-1.1, 0, .8}) {
                    for (int width : new int[] {320, 900}) {
                        var view = new StaticMeshRenderer.View(yaw, pitch, 1.15, false, Set.of());
                        int height = 600;
                        var camera = GpuCamera.create(scene, width, height, view, homogeneous);
                        double focal = Math.min(width, height) * 1.25;
                        for (double[] position :
                                List.of(
                                        new double[] {10, 18, 27},
                                        new double[] {14, 18, 27},
                                        new double[] {12, 24, 35},
                                        new double[] {12, 21, 31})) {
                            double x = position[0] - 12, y = position[1] - 21, z = position[2] - 31;
                            double horizontal = y * Math.cos(yaw) + x * Math.sin(yaw);
                            double away = x * Math.cos(yaw) - y * Math.sin(yaw);
                            double vertical = z * Math.cos(pitch) - away * Math.sin(pitch);
                            double distance =
                                    camera.cameraDistance()
                                            - away * Math.cos(pitch)
                                            - z * Math.sin(pitch);
                            var clip =
                                    transform(
                                            camera.modelToClip(),
                                            position[0],
                                            position[1],
                                            position[2]);
                            assertEquals(distance, clip[3], 0.00001);
                            assertEquals(
                                    width * .5 + horizontal * focal / distance,
                                    (clip[0] / clip[3] + 1) * width * .5,
                                    .001);
                            assertEquals(
                                    height * .53 - vertical * focal / distance,
                                    (1 - clip[1] / clip[3]) * height * .5,
                                    .001);
                            assertTrue(clip[2] / clip[3] >= (homogeneous ? -1 : 0));
                            assertTrue(clip[2] / clip[3] <= 1);
                        }
                    }
                }
            }
        }
    }

    @Test
    void lightingDirectionReproducesLegacyAbsDotPitchConventionWithoutNormalization() {
        for (double yaw : new double[] {-.3, 0, 2.5}) {
            for (double pitch : new double[] {-1, 0, 1}) {
                var camera =
                        GpuCamera.create(
                                scene(),
                                320,
                                200,
                                new StaticMeshRenderer.View(yaw, pitch, 1, false, Set.of()),
                                false);
                var light = camera.lightDirection();
                for (double[] normal :
                        List.of(
                                new double[] {1, 0, 0},
                                new double[] {0, 0, 1},
                                new double[] {1, 2, 3},
                                new double[] {-1, -2, -3})) {
                    double nx = normal[0], ny = normal[1], nz = normal[2];
                    double away = nx * Math.cos(yaw) - ny * Math.sin(yaw);
                    double expected =
                            (ny * Math.cos(yaw) + nx * Math.sin(yaw)) * -.35
                                    + (nz * Math.cos(pitch) - away * Math.sin(pitch)) * .6
                                    + away * Math.cos(pitch) * .72;
                    assertEquals(
                            expected,
                            nx * light.get(0) + ny * light.get(1) + nz * light.get(2),
                            0.000001);
                }
            }
        }
    }

    @Test
    void nearAndFarUseSelectedClipDepthRangeAndUniformViewsAreImmutable() {
        for (boolean homogeneous : List.of(false, true)) {
            var camera =
                    GpuCamera.create(
                            scene(),
                            320,
                            200,
                            new StaticMeshRenderer.View(0, 0, 1, false, Set.of()),
                            homogeneous);
            // yaw=0,pitch=0 makes distance = cameraDistance - centered X.
            for (double distance : new double[] {camera.near(), camera.far()}) {
                var clip =
                        transform(
                                camera.modelToClip(),
                                12 + camera.cameraDistance() - distance,
                                21,
                                31);
                double expected = distance == camera.near() ? (homogeneous ? -1 : 0) : 1;
                assertEquals(expected, clip[2] / clip[3], 0.00005);
            }
            assertThrows(ReadOnlyBufferException.class, () -> camera.modelToClip().put(0, 0));
            assertThrows(ReadOnlyBufferException.class, () -> camera.lightDirection().put(0, 0));
            camera.modelToClip().position(3);
            assertEquals(0, camera.modelToClip().position());
        }
        var retina =
                GpuCamera.create(scene(), 1920, 1440, StaticMeshRenderer.View.initial(), false);
        assertEquals(1920, retina.width());
        assertEquals(1440, retina.height());
        GpuCamera.create(scene(), 8192, 8192, StaticMeshRenderer.View.initial(), false);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuCamera.create(
                                scene(), 8193, 1440, StaticMeshRenderer.View.initial(), false));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GpuCamera.create(
                                scene(), 1920, 8193, StaticMeshRenderer.View.initial(), false));
    }
}
