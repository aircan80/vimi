package com.vibeminer.rendering.world;

import com.vibeminer.formats.world.WorldFormats;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded perspective CPU fallback for terrain exploration, independent of JavaFX3D support. */
public final class TerrainRenderer {
    private TerrainRenderer() {}

    public record Camera(double x, double y, double z, double yaw, double pitch) {
        public Camera {
            for (double v : new double[] {x, y, z, yaw, pitch}) {
                if (!Double.isFinite(v) || Math.abs(v) > 1_000_000) {
                    throw new IllegalArgumentException("Invalid camera");
                }
            }
        }

        public Camera move(double forward, double strafe, double vertical) {
            return new Camera(
                    x + Math.cos(yaw) * forward - Math.sin(yaw) * strafe,
                    y + Math.sin(yaw) * forward + Math.cos(yaw) * strafe,
                    z + vertical,
                    yaw,
                    pitch);
        }

        public Camera look(double turn, double tilt) {
            return new Camera(x, y, z, yaw + turn, Math.clamp(pitch + tilt, -1.5, 1.5));
        }
    }

    private record Surface(float[] vertices, int[] triangles) {}

    public static final class Scene {
        private final List<Surface> surfaces;

        public Scene(List<WorldFormats.Terrain> tiles) {
            if (tiles.size() > 9) {
                throw new IllegalArgumentException("Too many active terrain tiles");
            }
            var data = new ArrayList<Surface>();
            for (var tile : tiles) {
                data.add(new Surface(tile.vertices(), tile.triangles()));
            }
            surfaces = List.copyOf(data);
        }
    }

    public record Frame(
            int width, int height, int[] pixels, long triangles, boolean budgetReached) {}

    public static Frame render(
            Scene scene, Camera camera, int width, int height, BooleanSupplier canceled) {
        if (width < 1 || height < 1 || width > 1200 || height > 800) {
            throw new IllegalArgumentException("Frame exceeds world preview bounds");
        }
        int[] pixels = new int[width * height];
        float[] depth = new float[pixels.length];
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        for (int y = 0; y < height; y++) {
            Arrays.fill(pixels, y * width, (y + 1) * width, sky(y, height));
        }
        double cy = Math.cos(camera.yaw()),
                sy = Math.sin(camera.yaw()),
                cp = Math.cos(camera.pitch()),
                sp = Math.sin(camera.pitch()),
                focal = width * .8;
        long triangles = 0, work = 0;
        var clipped = new double[12];
        var original = new double[9];
        for (var surface : scene.surfaces) {
            var vertices = surface.vertices;
            var indices = surface.triangles;
            double[] transformed = new double[vertices.length];
            for (int at = 0; at < vertices.length; at += 3) {
                if ((at & 1023) == 0) {
                    check(canceled);
                }
                double dx = vertices[at] - camera.x(),
                        dy = vertices[at + 1] - camera.y(),
                        dz = vertices[at + 2] - camera.z(),
                        forward = cy * dx + sy * dy;
                transformed[at] = -sy * dx + cy * dy;
                transformed[at + 1] = cp * dz - sp * forward;
                transformed[at + 2] = sp * dz + cp * forward;
            }
            for (int at = 0; at < indices.length; at += 3) {
                if ((at & 1023) == 0) {
                    check(canceled);
                }
                int a = indices[at] * 3, b = indices[at + 1] * 3, c = indices[at + 2] * 3;
                double far =
                        Math.max(
                                transformed[a + 2],
                                Math.max(transformed[b + 2], transformed[c + 2]));
                if (far < .5
                        || Math.min(
                                        transformed[a + 2],
                                        Math.min(transformed[b + 2], transformed[c + 2]))
                                > 2200) {
                    continue;
                }
                System.arraycopy(transformed, a, original, 0, 3);
                System.arraycopy(transformed, b, original, 3, 3);
                System.arraycopy(transformed, c, original, 6, 3);
                int count = clip(original, clipped);
                if (count < 3) {
                    continue;
                }
                int color = color(vertices, a, b, c, far);
                for (int t = 1; t < count - 1; t++) {
                    double ax = width * .5 + clipped[0] * focal / clipped[2],
                            ay = height * .5 - clipped[1] * focal / clipped[2];
                    double bx = width * .5 + clipped[t * 3] * focal / clipped[t * 3 + 2],
                            by = height * .5 - clipped[t * 3 + 1] * focal / clipped[t * 3 + 2];
                    double
                            cx =
                                    width * .5
                                            + clipped[(t + 1) * 3]
                                                    * focal
                                                    / clipped[(t + 1) * 3 + 2],
                            cz =
                                    height * .5
                                            - clipped[(t + 1) * 3 + 1]
                                                    * focal
                                                    / clipped[(t + 1) * 3 + 2];
                    int
                            minX =
                                    (int)
                                            Math.clamp(
                                                    Math.floor(Math.min(ax, Math.min(bx, cx))),
                                                    0,
                                                    width - 1),
                            maxX =
                                    (int)
                                            Math.clamp(
                                                    Math.ceil(Math.max(ax, Math.max(bx, cx))),
                                                    0,
                                                    width - 1);
                    int
                            minY =
                                    (int)
                                            Math.clamp(
                                                    Math.floor(Math.min(ay, Math.min(by, cz))),
                                                    0,
                                                    height - 1),
                            maxY =
                                    (int)
                                            Math.clamp(
                                                    Math.ceil(Math.max(ay, Math.max(by, cz))),
                                                    0,
                                                    height - 1);
                    double area = edge(ax, ay, bx, by, cx, cz);
                    if (Math.abs(area) < 1e-8) {
                        continue;
                    }
                    work += (long) (maxX - minX + 1) * (maxY - minY + 1);
                    if (work > 30_000_000) {
                        return new Frame(width, height, pixels, triangles, true);
                    }
                    for (int y = minY; y <= maxY; y++) {
                        if ((y & 31) == 0) {
                            check(canceled);
                        }
                        for (int x = minX; x <= maxX; x++) {
                            double wa = edge(bx, by, cx, cz, x + .5, y + .5) / area,
                                    wb = edge(cx, cz, ax, ay, x + .5, y + .5) / area,
                                    wc = 1 - wa - wb;
                            if (wa < 0 || wb < 0 || wc < 0) {
                                continue;
                            }
                            double inverse =
                                    wa / clipped[2]
                                            + wb / clipped[t * 3 + 2]
                                            + wc / clipped[(t + 1) * 3 + 2];
                            float z = (float) (1 / inverse);
                            int p = y * width + x;
                            if (z < depth[p]) {
                                depth[p] = z;
                                pixels[p] = color;
                            }
                        }
                    }
                    triangles++;
                }
            }
        }
        return new Frame(width, height, pixels, triangles, false);
    }

    private static int clip(double[] input, double[] output) {
        int count = 0;
        for (int current = 0; current < 3; current++) {
            int previous = (current + 2) % 3, a = previous * 3, b = current * 3;
            boolean insideA = input[a + 2] >= .5, insideB = input[b + 2] >= .5;
            if (insideA != insideB) {
                double fraction = (.5 - input[a + 2]) / (input[b + 2] - input[a + 2]);
                for (int k = 0; k < 3; k++) {
                    output[count * 3 + k] = input[a + k] + fraction * (input[b + k] - input[a + k]);
                }
                count++;
            }
            if (insideB) {
                System.arraycopy(input, b, output, count * 3, 3);
                count++;
            }
        }
        return count;
    }

    private static double edge(double ax, double ay, double bx, double by, double x, double y) {
        return (x - ax) * (by - ay) - (y - ay) * (bx - ax);
    }

    private static int color(float[] v, int a, int b, int c, double distance) {
        double ux = v[b] - v[a],
                uy = v[b + 1] - v[a + 1],
                uz = v[b + 2] - v[a + 2],
                vx = v[c] - v[a],
                vy = v[c + 1] - v[a + 1],
                vz = v[c + 2] - v[a + 2];
        double nx = uy * vz - uz * vy,
                ny = uz * vx - ux * vz,
                nz = ux * vy - uy * vx,
                length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double
                light =
                        length == 0
                                ? .7
                                : .45 + .55 * Math.abs((nx * .3 + ny * .4 + nz * .866) / length),
                altitude = (v[a + 2] + v[b + 2] + v[c + 2]) / 3.0;
        double rock = Math.clamp((altitude - 150) / 600, 0, 1),
                fog = Math.clamp((distance - 600) / 1600, 0, .85);
        int r = (int) (((72 + rock * 65) * light) * (1 - fog) + 153 * fog),
                g = (int) (((116 + rock * 10) * light) * (1 - fog) + 184 * fog),
                bColor = (int) (((68 + rock * 57) * light) * (1 - fog) + 196 * fog);
        return 0xff000000 | r << 16 | g << 8 | bColor;
    }

    private static int sky(int y, int height) {
        double t = (double) y / height;
        return 0xff000000
                | (int) (91 + 80 * t) << 16
                | (int) (141 + 60 * t) << 8
                | (int) (177 + 37 * t);
    }

    private static void check(BooleanSupplier canceled) {
        if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }
}
