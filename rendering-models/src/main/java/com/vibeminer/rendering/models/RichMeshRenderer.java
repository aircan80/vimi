package com.vibeminer.rendering.models;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Typed material rasterization; legacy-only appearances continue through the original renderer. */
final class RichMeshRenderer {
    private RichMeshRenderer() {}

    private record Vertex(
            double x,
            double y,
            double depth,
            double u,
            double v,
            double eu,
            double ev,
            double light) {
        Vertex mix(Vertex b, double t) {
            return new Vertex(
                    x + (b.x - x) * t,
                    y + (b.y - y) * t,
                    depth + (b.depth - depth) * t,
                    u + (b.u - u) * t,
                    v + (b.v - v) * t,
                    eu + (b.eu - eu) * t,
                    ev + (b.ev - ev) * t,
                    light + (b.light - light) * t);
        }
    }

    static boolean required(MeshAppearance value) {
        return !value.billboards.isEmpty()
                || value.surfaces.values().stream()
                        .anyMatch(
                                surface ->
                                        surface.material().combiner()
                                                        != MeshAppearance.Combiner.DIFFUSE
                                                || surface.material().lighting()
                                                        != MeshAppearance.Lighting.LIT
                                                || surface.material().blend()
                                                        == MeshAppearance.Blend.ADDITIVE);
    }

    static StaticMeshRenderer.Frame render(
            MeshAppearance appearance,
            int width,
            int height,
            StaticMeshRenderer.View view,
            BooleanSupplier cancel,
            boolean qualified,
            boolean textured) {
        if (width < 1 || height < 1 || width > 1600 || height > 1200) {
            throw new IllegalArgumentException("Preview image exceeds bounds");
        }
        check(cancel);
        var camera =
                PreviewCamera.create(
                        PreviewCamera.Bounds.of(appearance), width, height, view, false);
        var mesh = appearance.mesh();
        var vertices = new Vertex[mesh.vertexCount()];
        for (int i = 0; i < vertices.length; i++) {
            if ((i & 2047) == 0) {
                check(cancel);
            }
            int at = i * 3, group = appearance.billboardGroups[i];
            var eye =
                    group < 0
                            ? camera.modelToEye(
                                    mesh.positions[at],
                                    mesh.positions[at + 1],
                                    mesh.positions[at + 2])
                            : camera.billboardToEye(
                                    appearance.billboards.get(group).modelPivot(),
                                    mesh.positions[at],
                                    mesh.positions[at + 1],
                                    mesh.positions[at + 2]);
            double light = 0;
            var env = new PreviewCamera.TexCoord(.5, .5);
            if (appearance.normals.length > 0 && textured) {
                double nx = appearance.normals[at],
                        ny = appearance.normals[at + 1],
                        nz = appearance.normals[at + 2];
                light = camera.lighting(nx, ny, nz);
                env = PreviewCamera.environment(eye, camera.normalToEye(nx, ny, nz));
            }
            vertices[i] =
                    new Vertex(
                            eye.x(),
                            eye.y(),
                            -eye.z(),
                            appearance.uv[i * 2],
                            appearance.uv[i * 2 + 1],
                            env.u(),
                            env.v(),
                            light);
        }
        int[] image = new int[width * height];
        float[] depths = new float[image.length];
        Arrays.fill(depths, Float.POSITIVE_INFINITY);
        for (int y = 0; y < height; y++) {
            int shade = 18 + (int) (10.0 * y / height);
            Arrays.fill(
                    image,
                    y * width,
                    (y + 1) * width,
                    0xff000000 | shade << 16 | (shade + 8) << 8 | shade + 10);
        }
        long budget = 50_000_000;
        for (int phase = 0; phase < (view.wireframe() ? 3 : 2); phase++) {
            for (int ordinal = 0; ordinal < mesh.parts().size(); ordinal++) {
                var part = mesh.parts().get(ordinal);
                var surface = appearance.surfaces.get(ordinal);
                if (qualified && surface == null
                        || !view.parts().isEmpty() && !view.parts().contains(part.id())) {
                    continue;
                }
                var material = textured && surface != null ? surface.material() : null;
                boolean additive =
                        material != null && material.blend() == MeshAppearance.Blend.ADDITIVE;
                if (phase < 2 && (phase == 1) != additive) {
                    continue;
                }
                for (int at = part.firstIndex();
                        at < part.firstIndex() + part.indexCount();
                        at += 3) {
                    if ((at & 1023) == 0) {
                        check(cancel);
                    }
                    Vertex a = vertices[mesh.indices[at]],
                            b = vertices[mesh.indices[at + 1]],
                            c = vertices[mesh.indices[at + 2]];
                    double abx = b.x - a.x, aby = b.y - a.y, abz = a.depth - b.depth;
                    double acx = c.x - a.x, acy = c.y - a.y, acz = a.depth - c.depth;
                    double flat =
                            camera.lightingEye(
                                    aby * acz - abz * acy,
                                    abz * acx - abx * acz,
                                    abx * acy - aby * acx);
                    var clipped = clip(a, b, c, camera.near());
                    for (int triangle = 1; triangle + 1 < clipped.length; triangle++) {
                        budget =
                                raster(
                                        clipped[0],
                                        clipped[triangle],
                                        clipped[triangle + 1],
                                        appearance,
                                        material,
                                        flat,
                                        camera,
                                        width,
                                        height,
                                        image,
                                        depths,
                                        budget,
                                        cancel,
                                        phase == 2);
                    }
                }
            }
        }
        check(cancel);
        return new StaticMeshRenderer.Frame(width, height, image);
    }

    private static Vertex[] clip(Vertex a, Vertex b, Vertex c, double near) {
        Vertex[] source = {a, b, c};
        Vertex[] out = new Vertex[4];
        int count = 0;
        Vertex previous = c;
        boolean wasInside = previous.depth >= near;
        for (Vertex current : source) {
            boolean inside = current.depth >= near;
            if (inside != wasInside) {
                out[count++] =
                        previous.mix(
                                current,
                                (near - previous.depth) / (current.depth - previous.depth));
            }
            if (inside) {
                out[count++] = current;
            }
            previous = current;
            wasInside = inside;
        }
        return Arrays.copyOf(out, count);
    }

    private static long raster(
            Vertex a,
            Vertex b,
            Vertex c,
            MeshAppearance appearance,
            MeshAppearance.Material material,
            double flat,
            PreviewCamera camera,
            int width,
            int height,
            int[] image,
            float[] depth,
            long budget,
            BooleanSupplier cancel,
            boolean edges) {
        double ax = camera.screenX(new PreviewCamera.Eye(a.x, a.y, -a.depth)),
                ay = camera.screenY(new PreviewCamera.Eye(a.x, a.y, -a.depth));
        double bx = camera.screenX(new PreviewCamera.Eye(b.x, b.y, -b.depth)),
                by = camera.screenY(new PreviewCamera.Eye(b.x, b.y, -b.depth));
        double cx = camera.screenX(new PreviewCamera.Eye(c.x, c.y, -c.depth)),
                cy = camera.screenY(new PreviewCamera.Eye(c.x, c.y, -c.depth));
        double area = Edge.of(ax, ay, bx, by).value(cx, cy);
        if (area < 0) {
            var swap = b;
            b = c;
            c = swap;
            double xSwap = bx;
            bx = cx;
            cx = xSwap;
            double ySwap = by;
            by = cy;
            cy = ySwap;
            area = -area;
        }
        var ab = Edge.of(ax, ay, bx, by);
        var bc = Edge.of(bx, by, cx, cy);
        var ca = Edge.of(cx, cy, ax, ay);
        if (!Double.isFinite(area) || Math.abs(area) < .001) {
            return budget;
        }
        int minX = (int) Math.max(0, Math.floor(Math.min(ax, Math.min(bx, cx)))),
                maxX = (int) Math.min(width - 1, Math.ceil(Math.max(ax, Math.max(bx, cx))));
        int minY = (int) Math.max(0, Math.floor(Math.min(ay, Math.min(by, cy)))),
                maxY = (int) Math.min(height - 1, Math.ceil(Math.max(ay, Math.max(by, cy))));
        budget -= (long) Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1);
        if (budget < 0) {
            throw new IllegalArgumentException("Preview raster work exceeds bounds");
        }
        double threshold = Math.max(.008, 1.2 / Math.sqrt(Math.abs(area)));
        boolean additive = material != null && material.blend() == MeshAppearance.Blend.ADDITIVE;
        for (int y = minY; y <= maxY; y++) {
            if ((y & 31) == 0) {
                check(cancel);
            }
            for (int x = minX; x <= maxX; x++) {
                double ea = bc.value(x + .5, y + .5),
                        eb = ca.value(x + .5, y + .5),
                        ec = ab.value(x + .5, y + .5);
                if (!bc.owns(ea) || !ca.owns(eb) || !ab.owns(ec)) {
                    continue;
                }
                double wa = ea / area, wb = eb / area, wc = 1 - wa - wb;
                if (edges && Math.min(wa, Math.min(wb, wc)) >= threshold) {
                    continue;
                }
                double z = 1 / (wa / a.depth + wb / b.depth + wc / c.depth);
                float incoming = (float) z;
                int pixel = y * width + x;
                if (edges || additive) {
                    if (incoming > depth[pixel]) {
                        continue;
                    }
                } else {
                    if (incoming >= depth[pixel]) {
                        continue;
                    }
                }
                double pa = wa / a.depth * z, pb = wb / b.depth * z, pc = wc / c.depth * z;
                int sample = 0xffffffff;
                double red, green, blue;
                if (material == null) {
                    red = (int) (190 * flat);
                    green = (int) (176 * flat);
                    blue = (int) (153 * flat);
                } else {
                    var first = material.stages().getFirst();
                    sample =
                            sample(
                                    appearance,
                                    first,
                                    pa * a.u + pb * b.u + pc * c.u,
                                    pa * a.v + pb * b.v + pc * c.v);
                    if (material.blend() == MeshAppearance.Blend.ALPHA_KEY
                            && (sample >>> 24) < 128) {
                        continue;
                    }
                    double light =
                            material.lighting() == MeshAppearance.Lighting.UNLIT
                                    ? 1
                                    : appearance.normals.length == 0
                                            ? (int) (190 * flat) / 190.0
                                            : pa * a.light + pb * b.light + pc * c.light;
                    red = (sample >>> 16 & 255) * light;
                    green = (sample >>> 8 & 255) * light;
                    blue = (sample & 255) * light;
                    if (material.combiner() == MeshAppearance.Combiner.OPAQUE_MOD2X_NA) {
                        int env =
                                sample(
                                        appearance,
                                        material.stages().get(1),
                                        pa * a.eu + pb * b.eu + pc * c.eu,
                                        pa * a.ev + pb * b.ev + pc * c.ev);
                        red *= 2 * (env >>> 16 & 255) / 255.0;
                        green *= 2 * (env >>> 8 & 255) / 255.0;
                        blue *= 2 * (env & 255) / 255.0;
                    }
                }
                if (edges) {
                    image[pixel] = 0xffabc5b1;
                    continue;
                }
                if (additive) {
                    double alpha = (sample >>> 24) / 255.0;
                    red = red * alpha + (image[pixel] >>> 16 & 255);
                    green = green * alpha + (image[pixel] >>> 8 & 255);
                    blue = blue * alpha + (image[pixel] & 255);
                } else {
                    depth[pixel] = incoming;
                }
                image[pixel] =
                        0xff000000 | channel(red) << 16 | channel(green) << 8 | channel(blue);
            }
        }
        return budget;
    }

    private static int sample(
            MeshAppearance appearance, MeshAppearance.TextureStage stage, double u, double v) {
        var texture = appearance.textures.get(stage.textureIndex());
        return texture.pixels[
                coordinate(v, texture.height(), stage.wrapV()) * texture.width()
                        + coordinate(u, texture.width(), stage.wrapU())];
    }

    private static int coordinate(double value, int size, boolean wrap) {
        double normalized = wrap ? value - Math.floor(value) : Math.max(0, Math.min(1, value));
        return Math.min(size - 1, (int) (normalized * size));
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, value));
    }

    /** Canonical line coefficients make reversed shared edges exactly antisymmetric. */
    private record Edge(double a, double b, double c, boolean inclusive) {
        static Edge of(double ax, double ay, double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            return new Edge(dy, -dx, ay * bx - ax * by, dy > 0 || dy == 0 && dx < 0);
        }

        double value(double x, double y) {
            return (a * x + b * y) + c;
        }

        boolean owns(double value) {
            return value > 0 || value == 0 && inclusive;
        }
    }

    private static void check(BooleanSupplier cancel) {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
