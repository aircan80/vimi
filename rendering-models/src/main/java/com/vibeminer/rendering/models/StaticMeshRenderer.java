package com.vibeminer.rendering.models;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Bounded CPU perspective renderer: works without a platform-specific3D or GPU pipeline. */
public final class StaticMeshRenderer {
    public record View(
            double yaw, double pitch, double zoom, boolean wireframe, Set<Integer> parts) {
        public View {
            if (!Double.isFinite(yaw)
                    || !Double.isFinite(pitch)
                    || !Double.isFinite(zoom)
                    || zoom < 0.2
                    || zoom > 8) {
                throw new IllegalArgumentException("Invalid preview camera");
            }
            parts = Set.copyOf(parts);
        }

        public static View initial() {
            return new View(-0.3, 0, 1, false, Set.of());
        }
    }

    public record Frame(int width, int height, int[] argb) {
        public Frame {
            argb = argb.clone();
        }

        @Override
        public int[] argb() {
            return argb.clone();
        }
    }

    private StaticMeshRenderer() {}

    private static void check(BooleanSupplier cancel) {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    public static Frame render(
            PreviewMesh mesh, int width, int height, View view, BooleanSupplier cancel) {
        return render(mesh, null, width, height, view, cancel, false, false);
    }

    public static Frame render(
            MeshAppearance appearance, int width, int height, View view, BooleanSupplier cancel) {
        return render(appearance, width, height, view, cancel, false);
    }

    /**
     * Restricts a partial material preview to section ordinals with an admitted texture binding.
     * Native geoset IDs remain an independent visibility filter. Pass false for raw surface
     * diagnostics; existing overloads retain their neutral unbound-surface behavior.
     */
    public static Frame render(
            MeshAppearance appearance,
            int width,
            int height,
            View view,
            BooleanSupplier cancel,
            boolean qualifiedMaterialsOnly) {
        return render(appearance, width, height, view, cancel, qualifiedMaterialsOnly, true);
    }

    /** Material qualification remains in effect when diffuse texture display is disabled. */
    public static Frame render(
            MeshAppearance appearance,
            int width,
            int height,
            View view,
            BooleanSupplier cancel,
            boolean qualifiedMaterialsOnly,
            boolean texturesEnabled) {
        return render(
                appearance.mesh(),
                appearance,
                width,
                height,
                view,
                cancel,
                qualifiedMaterialsOnly,
                texturesEnabled);
    }

    private static Frame render(
            PreviewMesh mesh,
            MeshAppearance appearance,
            int width,
            int height,
            View view,
            BooleanSupplier cancel,
            boolean qualifiedMaterialsOnly,
            boolean texturesEnabled) {
        if (appearance != null && RichMeshRenderer.required(appearance)) {
            return RichMeshRenderer.render(
                    appearance,
                    width,
                    height,
                    view,
                    cancel,
                    qualifiedMaterialsOnly,
                    texturesEnabled);
        }
        if (width < 1 || height < 1 || width > 1600 || height > 1200) {
            throw new IllegalArgumentException("Preview image exceeds bounds");
        }
        check(cancel);
        int[] image = new int[width * height];
        float[] depth = new float[image.length];
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        for (int y = 0; y < height; y++) {
            int shade = 18 + (int) (10.0 * y / height);
            Arrays.fill(
                    image,
                    y * width,
                    (y + 1) * width,
                    0xff000000 | (shade << 16) | ((shade + 8) << 8) | (shade + 10));
        }
        double cx = (mesh.minimum(0) + mesh.maximum(0)) / 2.0,
                cy = (mesh.minimum(1) + mesh.maximum(1)) / 2.0,
                cz = (mesh.minimum(2) + mesh.maximum(2)) / 2.0;
        double dx = mesh.maximum(0) - mesh.minimum(0),
                dy = mesh.maximum(1) - mesh.minimum(1),
                dz = mesh.maximum(2) - mesh.minimum(2);
        double radius = Math.max(0.001, Math.sqrt(dx * dx + dy * dy + dz * dz) / 2),
                camera = radius * 3.2 / view.zoom(),
                focal = Math.min(width, height) * 1.25;
        double cos = Math.cos(view.yaw()),
                sin = Math.sin(view.yaw()),
                cp = Math.cos(view.pitch()),
                sp = Math.sin(view.pitch());
        double[] px = new double[mesh.vertexCount()],
                py = new double[px.length],
                pz = new double[px.length];
        double[] lighting =
                appearance != null && texturesEnabled && appearance.normals.length > 0
                        ? new double[mesh.vertexCount()]
                        : null;
        for (int i = 0; i < px.length; i++) {
            if ((i & 2047) == 0) {
                check(cancel);
            }
            double x = mesh.positions[i * 3] - cx,
                    y = mesh.positions[i * 3 + 1] - cy,
                    z = mesh.positions[i * 3 + 2] - cz;
            if (lighting != null) {
                lighting[i] =
                        light(
                                appearance.normals[i * 3],
                                appearance.normals[i * 3 + 1],
                                appearance.normals[i * 3 + 2],
                                cos,
                                sin,
                                cp,
                                sp);
            }
            double horizontal = y * cos + x * sin,
                    away = x * cos - y * sin,
                    vertical = z * cp - away * sp;
            double distance = camera - away * cp - z * sp;
            pz[i] = distance;
            px[i] = width * 0.5 + horizontal * focal / distance;
            py[i] = height * 0.53 - vertical * focal / distance;
        }
        long rasterBudget = 50_000_000L;
        int section = -1;
        for (var part : mesh.parts()) {
            section++;
            MeshAppearance.Surface surface =
                    appearance == null ? null : appearance.surfaces.get(section);
            if (qualifiedMaterialsOnly && surface == null) {
                continue;
            }
            if (!texturesEnabled) {
                surface = null;
            }
            if (!view.parts().isEmpty() && !view.parts().contains(part.id())) {
                continue;
            }
            for (int at = part.firstIndex(); at < part.firstIndex() + part.indexCount(); at += 3) {
                if ((at & 1023) == 0) {
                    check(cancel);
                }
                int a = mesh.indices[at], b = mesh.indices[at + 1], c = mesh.indices[at + 2];
                if (pz[a] < radius * 0.05 || pz[b] < radius * 0.05 || pz[c] < radius * 0.05) {
                    continue;
                }
                double area = edge(px[a], py[a], px[b], py[b], px[c], py[c]);
                if (Math.abs(area) < 0.001) {
                    continue;
                }
                int minX = (int) Math.max(0, Math.floor(Math.min(px[a], Math.min(px[b], px[c])))),
                        maxX =
                                (int)
                                        Math.min(
                                                width - 1,
                                                Math.ceil(Math.max(px[a], Math.max(px[b], px[c]))));
                int minY = (int) Math.max(0, Math.floor(Math.min(py[a], Math.min(py[b], py[c])))),
                        maxY =
                                (int)
                                        Math.min(
                                                height - 1,
                                                Math.ceil(Math.max(py[a], Math.max(py[b], py[c]))));
                int color = color(mesh, a, b, c, cos, sin, cp, sp);
                double lineThreshold = Math.max(0.008, 1.2 / Math.sqrt(Math.abs(area)));
                rasterBudget -= (long) Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1);
                if (rasterBudget < 0) {
                    throw new IllegalArgumentException("Preview raster work exceeds bounds");
                }
                for (int y = minY; y <= maxY; y++) {
                    if ((y & 31) == 0) {
                        check(cancel);
                    }
                    for (int x = minX; x <= maxX; x++) {
                        double wa = edge(px[b], py[b], px[c], py[c], x + 0.5, y + 0.5) / area;
                        double wb = edge(px[c], py[c], px[a], py[a], x + 0.5, y + 0.5) / area,
                                wc = 1 - wa - wb;
                        if (wa < 0 || wb < 0 || wc < 0) {
                            continue;
                        }
                        double z = 1 / (wa / pz[a] + wb / pz[b] + wc / pz[c]);
                        int pixel = y * width + x;
                        if (z >= depth[pixel]) {
                            continue;
                        }
                        int shaded = color;
                        if (surface != null) {
                            double u =
                                    (wa * appearance.uv[a * 2] / pz[a]
                                                    + wb * appearance.uv[b * 2] / pz[b]
                                                    + wc * appearance.uv[c * 2] / pz[c])
                                            * z;
                            double v =
                                    (wa * appearance.uv[a * 2 + 1] / pz[a]
                                                    + wb * appearance.uv[b * 2 + 1] / pz[b]
                                                    + wc * appearance.uv[c * 2 + 1] / pz[c])
                                            * z;
                            var texture = appearance.textures.get(surface.textureIndex());
                            int tx = coordinate(u, texture.width(), surface.wrapU()),
                                    ty = coordinate(v, texture.height(), surface.wrapV());
                            int sampled = texture.pixels[ty * texture.width() + tx];
                            if (surface.alphaKey() && (sampled >>> 24) < 128) {
                                continue;
                            }
                            double light =
                                    lighting == null
                                            ? (color >>> 16 & 255) / 190.0
                                            : (wa * lighting[a] / pz[a]
                                                            + wb * lighting[b] / pz[b]
                                                            + wc * lighting[c] / pz[c])
                                                    * z;
                            shaded =
                                    0xff000000
                                            | ((int) ((sampled >>> 16 & 255) * light) << 16)
                                            | ((int) ((sampled >>> 8 & 255) * light) << 8)
                                            | (int) ((sampled & 255) * light);
                        }
                        depth[pixel] = (float) z;
                        image[pixel] =
                                view.wireframe() && Math.min(wa, Math.min(wb, wc)) < lineThreshold
                                        ? 0xffabc5b1
                                        : shaded;
                    }
                }
            }
        }
        check(cancel);
        return new Frame(width, height, image);
    }

    private static int coordinate(double value, int size, boolean wrap) {
        double normalized = wrap ? value - Math.floor(value) : Math.max(0, Math.min(1, value));
        return Math.min(size - 1, (int) (normalized * size));
    }

    private static double edge(double ax, double ay, double bx, double by, double x, double y) {
        return (x - ax) * (by - ay) - (y - ay) * (bx - ax);
    }

    private static int color(
            PreviewMesh mesh, int a, int b, int c, double cos, double sin, double cp, double sp) {
        double abx = mesh.positions[b * 3] - mesh.positions[a * 3],
                aby = mesh.positions[b * 3 + 1] - mesh.positions[a * 3 + 1],
                abz = mesh.positions[b * 3 + 2] - mesh.positions[a * 3 + 2];
        double acx = mesh.positions[c * 3] - mesh.positions[a * 3],
                acy = mesh.positions[c * 3 + 1] - mesh.positions[a * 3 + 1],
                acz = mesh.positions[c * 3 + 2] - mesh.positions[a * 3 + 2];
        double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
        double intensity = light(nx, ny, nz, cos, sin, cp, sp);
        int r = (int) (190 * intensity), g = (int) (176 * intensity), bgr = (int) (153 * intensity);
        return 0xff000000 | (r << 16) | (g << 8) | bgr;
    }

    private static double light(
            double nx, double ny, double nz, double cos, double sin, double cp, double sp) {
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double value =
                len == 0
                        ? 0
                        : Math.abs(
                                        (ny * cos + nx * sin) * -0.35
                                                + (nz * cp - (nx * cos - ny * sin) * sp) * 0.6
                                                + (nx * cos - ny * sin) * cp * 0.72)
                                / len;
        return 0.28 + 0.72 * Math.min(1, value);
    }
}
