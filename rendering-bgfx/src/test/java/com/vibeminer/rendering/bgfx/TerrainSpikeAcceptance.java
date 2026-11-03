package com.vibeminer.rendering.bgfx;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Independent authored native acceptance. Expected pixels never use renderer projection/color
 * helpers.
 */
public final class TerrainSpikeAcceptance {
    private static final GpuTerrainSpike.Camera ZERO = new GpuTerrainSpike.Camera(0, 0, 0, 0, 0);
    private static int frames;

    private TerrainSpikeAcceptance() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected private authored artifact directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        try (var gpu = new GpuTerrainSpike.Harness()) {
            axesAndProjection(gpu, output);
            nativeFlatColor(gpu);
            clippingAndFarAdmission(gpu);
            independentDepth(gpu, output);
            persistentScene(gpu);
        }
        Files.writeString(
                output.resolve("terrain-independent-acceptance.txt"),
                "PASS "
                        + frames
                        + " authored native frames; projection/color/depth expectations independent of renderer helpers\n");
        System.out.println("Independent terrain acceptance PASS: " + frames + " native frames");
    }

    private static GpuTerrainSpike.Vertex v(double x, double y, double z) {
        return new GpuTerrainSpike.Vertex(x, y, z);
    }

    private static GpuTerrainSpike.Triangle triangle(
            GpuTerrainSpike.Vertex a,
            GpuTerrainSpike.Vertex b,
            GpuTerrainSpike.Vertex c,
            boolean red) {
        return new GpuTerrainSpike.Triangle(a, b, c, red ? 200 : 20, red ? 40 : 220, red ? 60 : 80);
    }

    private static GpuTerrainSpike.Triangle panel(double depth, boolean red) {
        // Fixed angular footprint, independent of distance; same center sample is covered.
        return triangle(
                v(depth, -.2 * depth, -.1 * depth),
                v(depth, .2 * depth, -.1 * depth),
                v(depth, 0, .2 * depth),
                red);
    }

    private static int[] render(
            GpuTerrainSpike.Harness gpu,
            List<GpuTerrainSpike.Triangle> triangles,
            GpuTerrainSpike.Camera camera)
            throws IOException {
        int[] result = gpu.render(triangles, camera);
        frames++;
        if (result.length != 256 * 256) {
            throw new AssertionError("Frame dimensions");
        }
        for (int p : result) {
            if ((p >>> 24) != 255) {
                throw new AssertionError("Nonopaque frame pixel=" + Integer.toHexString(p));
            }
        }
        return result;
    }

    private static void pixel(int[] frame, int x, int y, int rgb) {
        int actual = frame[y * 256 + x];
        for (int shift : new int[] {0, 8, 16}) {
            if (Math.abs((actual >> shift & 255) - (rgb >> shift & 255)) > 1) {
                throw new AssertionError(
                        "Pixel ("
                                + x
                                + ","
                                + y
                                + ") expected "
                                + Integer.toHexString(rgb)
                                + " actual "
                                + Integer.toHexString(actual));
            }
        }
    }

    private static void exactPixel(int[] frame, int x, int y, int rgb) {
        int actual = frame[y * 256 + x];
        if (actual != rgb) {
            throw new AssertionError(
                    "Exact sky pixel ("
                            + x
                            + ","
                            + y
                            + ") expected "
                            + Integer.toHexString(rgb)
                            + " actual "
                            + Integer.toHexString(actual));
        }
    }

    private static void projectedPanel(int[] image) {
        // Analytic screen corners: (87.04,148.48),(168.96,148.48),(128,87.04).
        for (int[] p : new int[][] {{128, 128}, {100, 140}, {155, 140}, {128, 100}}) {
            pixel(image, p[0], p[1], 0xffc8283c);
        }
        pixel(image, 80, 128, 0xff83abc3); // row128: sky (131,171,195), not pixel-center gradient
        pixel(image, 128, 80, 0xff749fbc); // row80: (116,159,188) -- pinned below
    }

    private static void axesAndProjection(GpuTerrainSpike.Harness gpu, Path output)
            throws Exception {
        var normal = panel(10, true);
        int[] original = render(gpu, List.of(normal), ZERO);
        var initial = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        initial.setRGB(0, 0, 256, 256, original, 0, 256);
        ImageIO.write(
                initial, "png", output.resolve("terrain-independent-first-frame.png").toFile());
        projectedPanel(original);
        var rotated = triangle(v(2, 10, -1), v(-2, 10, -1), v(0, 10, 2), true);
        projectedPanel(
                render(gpu, List.of(rotated), new GpuTerrainSpike.Camera(0, 0, 0, Math.PI / 2, 0)));
        var pitched =
                triangle(
                        v(9.160254037844386, -2, 4.133974596215562),
                        v(9.160254037844386, 2, 4.133974596215562),
                        v(7.660254037844386, 0, 6.732050807568877),
                        true);
        projectedPanel(
                render(gpu, List.of(pitched), new GpuTerrainSpike.Camera(0, 0, 0, 0, Math.PI / 6)));
        var translated =
                triangle(
                        v(999010, -999002, 998999),
                        v(999010, -998998, 998999),
                        v(999010, -999000, 999002),
                        true);
        projectedPanel(
                render(
                        gpu,
                        List.of(translated),
                        new GpuTerrainSpike.Camera(999000, -999000, 999000, 0, 0)));
        var reversed = triangle(normal.c(), normal.b(), normal.a(), true);
        projectedPanel(render(gpu, List.of(reversed), ZERO));
        var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 256, 256, original, 0, 256);
        ImageIO.write(image, "png", output.resolve("terrain-independent-axes.png").toFile());
    }

    private static void nativeFlatColor(GpuTerrainSpike.Harness gpu) throws Exception {
        // +X normal -> abs(dot)=.3 -> light .615; mean altitude0, no fog => (44,71,41).
        var simple = GpuTerrainSpike.Triangle.shaded(v(10, -2, -1), v(10, 2, -1), v(10, 0, 2));
        pixel(render(gpu, List.of(simple), ZERO), 128, 128, 0xff2c4729);
        // Mean native altitude300: rock .25; raw base (54.27375,72.8775,50.58375).
        // Max original depth1000 gives fog .25; only FINAL conversion truncates -> (78,100,86).
        var altitude =
                GpuTerrainSpike.Triangle.shaded(
                        v(1000, -200, 200), v(1000, 200, 200), v(1000, 0, 500));
        pixel(
                render(gpu, List.of(altitude), new GpuTerrainSpike.Camera(0, 0, 300, 0, 0)),
                128,
                128,
                0xff4e6456);
    }

    private static void clippingAndFarAdmission(GpuTerrainSpike.Harness gpu) throws Exception {
        var crossing = triangle(v(.25, -.1, -.1), v(2, .6, -.1), v(2, 0, .6), true);
        pixel(render(gpu, List.of(crossing), ZERO), 128, 128, 0xffc8283c);
        var varyingDepth = triangle(v(.25, -.1, -.1), v(1000, 300, -50), v(1000, 0, 300), true);
        // Original MAX depth1000 -> fog .25 even at shallow interior samples.
        pixel(render(gpu, List.of(varyingDepth), ZERO), 128, 128, 0xffbc4c5e);
        int[] sky = render(gpu, List.of(panel(.49, true)), ZERO);
        pixel(sky, 128, 128, 0xff83abc3);
        exactPixel(sky, 0, 0, 0xff5b8db1);
        exactPixel(sky, 0, 3, 0xff5b8db1); // y+0.5 would incorrectly produce red92.
        exactPixel(sky, 255, 255, 0xffaac8d5);
        pixel(render(gpu, List.of(panel(2200.1, true)), ZERO), 128, 128, 0xff83abc3);
        pixel(render(gpu, List.of(panel(2200, true)), ZERO), 128, 128, 0xffa0a2af);
    }

    private static void independentDepth(GpuTerrainSpike.Harness gpu, Path output)
            throws Exception {
        for (double separation : new double[] {.01, .1}) {
            var near = panel(1999.9, true);
            var far = panel(1999.9 + separation, false);
            for (var list : List.of(List.of(near, far), List.of(far, near))) {
                pixel(render(gpu, list, ZERO), 128, 128, 0xffa0a2af);
            }
        }
        // Original A survives min-depth rejection; center depth is ~95333, well beyond2200.
        var farRegion =
                triangle(
                        v(2000, -800000, 0),
                        v(100000, 40000, 40000),
                        v(100000, 40000, -40000),
                        true);
        double s = 1.00001;
        var farther =
                triangle(
                        v(2000 * s, -800000 * s, 0),
                        v(100000 * s, 40000 * s, 40000 * s),
                        v(100000 * s, 40000 * s, -40000 * s),
                        false);
        for (var list : List.of(List.of(farRegion, farther), List.of(farther, farRegion))) {
            pixel(render(gpu, list, ZERO), 128, 128, 0xffa0a2af);
        }
        for (double depth : new double[] {.5, 10, 1999.9}) {
            var red = panel(depth, true);
            var green = panel(depth, false);
            int expected = depth < 600 ? 0xffc8283c : 0xffa0a2af;
            pixel(render(gpu, List.of(red, green), ZERO), 128, 128, expected);
            pixel(
                    render(gpu, List.of(green, red), ZERO),
                    128,
                    128,
                    depth < 600 ? 0xff14dc50 : 0xff85bdb2);
        }
    }

    private static void persistentScene(GpuTerrainSpike.Harness gpu) throws Exception {
        var scene = gpu.upload(List.of(panel(10, true)));
        try {
            for (var camera :
                    List.of(ZERO, new GpuTerrainSpike.Camera(1, .5, .25, .1, .05), ZERO)) {
                int[] image = gpu.render(scene, camera);
                frames++;
                for (int pixel : image) {
                    if ((pixel >>> 24) != 255) {
                        throw new AssertionError("Persistent frame not opaque");
                    }
                }
                if (scene.uploadCount() != 1
                        || scene.triangleCount() != 1
                        || scene.uploadBytes() != 48) {
                    throw new AssertionError("Camera motion changed persistent upload");
                }
            }
        } finally {
            scene.close();
        }
        try {
            gpu.render(scene, ZERO);
            throw new AssertionError("Closed scene rendered");
        } catch (IllegalArgumentException expected) {
            // Native resources are retired; the owner cannot reuse their handles.
        }
    }
}
