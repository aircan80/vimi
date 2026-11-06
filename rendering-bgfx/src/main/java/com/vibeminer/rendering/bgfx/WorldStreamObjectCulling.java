package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.Objects;

/**
 * CPU visibility of exterior object placements, decided once per placement per frame before any
 * draw call. Each model gets a bounding sphere over the vertices its drawn ranges reference, and
 * each placement a world sphere. A placement is skipped when its sphere lies wholly outside the
 * explorer's view frustum (near plane, the four side planes of {@link
 * TerrainCamera#reversedProjection}, and the {@link TerrainCamera#WHOLE_TRIANGLE_FAR} depth past
 * which the object fragment shader discards everything), all derived from {@link
 * TerrainCamera#FOCAL}. The frustum culls are conservative: such a placement draws no pixel.
 *
 * <p>The size cull is deliberate and not conservative: a placement whose sphere projects under
 * {@link #MIN_PIXEL_RADIUS} pixels of radius is skipped although it could still have touched a
 * pixel or two, so a very small object far away can pop in or out at that threshold. Pure; no bgfx
 * calls.
 */
final class WorldStreamObjectCulling {
    /**
     * Placements whose sphere's projected radius, at its center's eye depth, is under this many
     * pixels are skipped: a sphere under one pixel wide.
     */
    static final double MIN_PIXEL_RADIUS = 0.5;

    /** Margin on placement radii for the validated rotation orthonormality tolerance (1e-6). */
    private static final double ROTATION_MARGIN = 1 + 1e-5;

    /**
     * Absolute radius slack in yards: the GPU transforms in float relative to a float origin while
     * this test runs in double, which differs by far less than this at the far depth.
     */
    static final double RADIUS_SLACK = 0.01;

    private WorldStreamObjectCulling() {}

    /**
     * Per model: local center x, y, z and radius over the vertices its drawn ranges reference, from
     * the validated objects tables (per model first mesh and mesh count; per mesh first vertex,
     * vertex count, first index, index count, first range, range count; per range mesh-relative
     * first index, count, image, class; eight floats per vertex; mesh-relative u16 indices). The
     * center is the box center. A model that draws nothing gets radius -1.
     */
    static double[] modelSpheres(
            int[] models,
            int[] meshes,
            int[] ranges,
            float[] vertices,
            short[] indices,
            java.util.function.BooleanSupplier canceled) {
        int count = models.length / 2;
        var result = new double[count * 4];
        int stride = WorldStreamObjectsFile.RANGE_INTS;
        int floats = WorldStreamObjectsFile.VERTEX_FLOATS;
        for (int m = 0; m < count; m++) {
            if (canceled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            int first = models[m * 2], last = first + models[m * 2 + 1];
            for (int pass = 0; pass < 2; pass++) {
                double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
                double radius = 0;
                for (int mesh = first; mesh < last; mesh++) {
                    int at = mesh * 6;
                    int vertexBase = meshes[at], indexBase = meshes[at + 2];
                    for (int r = meshes[at + 4]; r < meshes[at + 4] + meshes[at + 5]; r++) {
                        int start = indexBase + ranges[r * stride];
                        int end = start + ranges[r * stride + 1];
                        for (int i = start; i < end; i++) {
                            int v = (vertexBase + (indices[i] & 0xffff)) * floats;
                            double x = vertices[v], y = vertices[v + 1], z = vertices[v + 2];
                            if (pass == 0) {
                                minX = Math.min(minX, x);
                                minY = Math.min(minY, y);
                                minZ = Math.min(minZ, z);
                                maxX = Math.max(maxX, x);
                                maxY = Math.max(maxY, y);
                                maxZ = Math.max(maxZ, z);
                            } else {
                                double dx = x - cx, dy = y - cy, dz = z - cz;
                                radius = Math.max(radius, dx * dx + dy * dy + dz * dz);
                            }
                        }
                    }
                }
                if (pass == 0 && !(minX <= maxX)) {
                    result[m * 4 + 3] = -1;
                    break;
                }
                if (pass == 1) {
                    result[m * 4] = cx;
                    result[m * 4 + 1] = cy;
                    result[m * 4 + 2] = cz;
                    result[m * 4 + 3] = Math.sqrt(radius);
                }
            }
        }
        return result;
    }

    /**
     * Per placement: world center x, y, z and radius, applying its position, uniform scale and
     * row-major rotation (the same transform {@link BgfxWorldStreamRenderer#objectMatrices} draws)
     * to its model's sphere. A model without a sphere gives radius -1.
     */
    static double[] placementSpheres(
            int[] placementModels, double[] placements, double[] modelSpheres) {
        var result = new double[placementModels.length * 4];
        for (int p = 0; p < placementModels.length; p++) {
            int at = p * WorldStreamObjectsFile.PLACEMENT_DOUBLES, m = placementModels[p] * 4;
            double radius = modelSpheres[m + 3];
            if (radius < 0) {
                result[p * 4 + 3] = -1;
                continue;
            }
            double s = placements[at + 3];
            double cx = modelSpheres[m], cy = modelSpheres[m + 1], cz = modelSpheres[m + 2];
            for (int row = 0; row < 3; row++) {
                int r = at + 4 + row * 3;
                result[p * 4 + row] =
                        placements[at + row]
                                + s
                                        * (placements[r] * cx
                                                + placements[r + 1] * cy
                                                + placements[r + 2] * cz);
            }
            result[p * 4 + 3] = s * radius * ROTATION_MARGIN + RADIUS_SLACK;
        }
        return result;
    }

    /** One frame's camera and projection, in the explorer's eye convention. Immutable. */
    static final class View {
        private final double x, y, z, cy, sy, cp, sp;
        private final double kx, ky, nx, ny, focal;

        private View(TerrainCamera camera, int width, int height) {
            x = camera.x();
            y = camera.y();
            z = camera.z();
            cy = Math.cos(camera.yaw());
            sy = Math.sin(camera.yaw());
            cp = Math.cos(camera.pitch());
            sp = Math.sin(camera.pitch());
            // TerrainCamera.reversedProjection scales eye right by FOCAL and eye up by FOCAL *
            // width
            // / height, so the frustum's half-extents per unit depth are their inverses.
            kx = 1 / TerrainCamera.FOCAL;
            ky = height / (TerrainCamera.FOCAL * width);
            nx = Math.sqrt(1 + kx * kx);
            ny = Math.sqrt(1 + ky * ky);
            focal = TerrainCamera.focalPixels(width);
        }

        static View of(TerrainCamera camera, int width, int height) {
            Objects.requireNonNull(camera);
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Frame dimensions");
            }
            return new View(camera, width, height);
        }

        /** Whether sphere i of {@code spheres} (x, y, z, radius per sphere) may draw a pixel. */
        boolean visible(double[] spheres, int i) {
            int at = i * 4;
            double radius = spheres[at + 3];
            if (radius < 0) {
                return false;
            }
            double dx = spheres[at] - x, dy = spheres[at + 1] - y, dz = spheres[at + 2] - z;
            // TerrainCamera.eye, inlined so a frame allocates nothing per placement.
            double forward = cy * dx + sy * dy;
            double right = sy * dx - cy * dy;
            double up = cp * dz - sp * forward;
            double depth = sp * dz + cp * forward;
            if (depth + radius < TerrainCamera.NEAR
                    || depth - radius > TerrainCamera.WHOLE_TRIANGLE_FAR
                    || right - kx * depth > radius * nx
                    || -right - kx * depth > radius * nx
                    || up - ky * depth > radius * ny
                    || -up - ky * depth > radius * ny) {
                return false;
            }
            // Projected radius at the center's eye depth: focal * r / sqrt(depth^2 - r^2), exact on
            // the view axis and an underestimate of the off-axis extent. Nearer than its radius,
            // the sphere fills the view.
            double squared = depth * depth - radius * radius;
            return squared <= 0 || radius * focal >= MIN_PIXEL_RADIUS * Math.sqrt(squared);
        }
    }
}
