package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.bgfx.GpuTerrainSpike.Triangle;
import com.vibeminer.rendering.bgfx.GpuTerrainSpike.Vertex;
import com.vibeminer.rendering.world.TerrainCamera;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Analytic authored acceptance; expected rays/UVs never use production camera or shader helpers.
 */
public final class WorldStreamSpikeAcceptance {
    private static long world, cameraRevision;
    private static int frames, samples;

    private WorldStreamSpikeAcceptance() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected authored artifact directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        try (var gpu = new WorldStreamSpike.Harness()) {
            gradientsAndSeams(gpu, output);
            slopedGradients(gpu);
            coplanarSourceOrder(gpu);
            nearFarDepth(gpu);
            require(
                    gpu.stats().outstandingReferences() == 0 && gpu.stats().liveUploadBytes() == 0,
                    "All upload references drain");
            require(gpu.stats().dynamicBuffers() == 10, "Precision cases reuse ten fixed buffers");
        }
        String result =
                "WORLD_STREAM_PRECISION_PASS Vulkan frames="
                        + frames
                        + " analyticSamples="
                        + samples
                        + " origins=0,10000,+999000,-999000 worldRevision="
                        + world
                        + " productionProtocolNotTested otherBackendRuntimeNotTested\n";
        Files.writeString(output.resolve("precision.txt"), result);
        System.out.print(result);
    }

    private static List<Triangle> quad(double origin) {
        var a = new Vertex(origin + 100, origin - 100, origin - 100);
        var b = new Vertex(origin + 100, origin + 100, origin - 100);
        var c = new Vertex(origin + 100, origin + 100, origin + 100);
        var d = new Vertex(origin + 100, origin - 100, origin + 100);
        return List.of(new Triangle(a, b, c, 200, 40, 60), new Triangle(a, c, d, 200, 40, 60));
    }

    private static ByteBuffer gradient() {
        var result = ByteBuffer.allocate(WorldStreamSpike.ATLAS_BYTES);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                result.put((byte) (x / 2)).put((byte) (y / 2)).put((byte) 37).put((byte) 255);
            }
        }
        return result.flip();
    }

    private static WorldStreamSpike.Resident upload(
            WorldStreamSpike.Harness gpu,
            int slot,
            List<Triangle> triangles,
            ByteBuffer rgba,
            float anchorX,
            float anchorY,
            boolean textured)
            throws Exception {
        var job = gpu.beginUpload(slot, triangles, rgba, anchorX, anchorY, textured);
        while (!job.finished()) {
            gpu.step(job);
            gpu.advance();
        }
        return gpu.resident(job);
    }

    private static void commit(
            WorldStreamSpike.Harness gpu, List<WorldStreamSpike.Resident> residents) {
        gpu.commit(world, world + 1, residents);
        world++;
    }

    private static int[] render(WorldStreamSpike.Harness gpu, TerrainCamera camera)
            throws Exception {
        long requested = ++cameraRevision;
        var result = gpu.render(camera, requested);
        require(
                result.worldRevision() == world && result.cameraRevision() == requested,
                "Frame provenance ticket");
        frames++;
        for (int pixel : result.pixels()) {
            require((pixel >>> 24) == 255, "Opaque output");
        }
        return result.pixels();
    }

    private static void gradientsAndSeams(WorldStreamSpike.Harness gpu, Path output)
            throws Exception {
        for (double origin : new double[] {0, 10000, 999000, -999000}) {
            commit(gpu, List.of());
            var triangles = quad(origin);
            var first =
                    upload(
                            gpu,
                            0,
                            List.of(triangles.get(0)),
                            gradient(),
                            (float) origin + 200,
                            (float) origin + 200,
                            true);
            var second =
                    upload(
                            gpu,
                            1,
                            List.of(triangles.get(1)),
                            gradient(),
                            (float) origin + 200,
                            (float) origin + 200,
                            true);
            commit(gpu, List.of(first, second));
            // .031/.032 cross a half-ULP rebase boundary near ±999000. Source vertices are fixed.
            for (double offset : new double[] {0, .030, .031, .032, .034, -.031, -.032}) {
                var camera =
                        new TerrainCamera(
                                origin + offset, origin + offset, origin + offset, .08, -.04);
                var pixels = render(gpu, camera);
                for (int y = 32; y <= 224; y++) {
                    for (int x = 32; x <= 224; x++) {
                        // Ray in world coordinates: forward + right*screenX + up*screenY.
                        double right = (x + .5 - 128) / 204.8, up = (128 - y - .5) / 204.8;
                        double cy = Math.cos(.08),
                                sy = Math.sin(.08),
                                cp = Math.cos(-.04),
                                sp = Math.sin(-.04);
                        double rayX = cy * cp + sy * right - cy * sp * up;
                        double rayY = sy * cp - cy * right - sy * sp * up;
                        double distance = (origin + 100 - camera.x()) / rayX;
                        double nativeY = camera.y() + distance * rayY;
                        double u = (origin + 200 - nativeY) * .001875;
                        double v = 100 * .001875;
                        int tx = Math.clamp((int) Math.floor(u * 512), 0, 511);
                        int ty = Math.clamp((int) Math.floor(v * 512), 0, 511);
                        pixel(pixels, x, y, 0xff000025 | (tx / 2) << 16 | (ty / 2) << 8);
                        samples++;
                    }
                }
                if (origin == 999000 && offset == .032) {
                    var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, 256, 256, pixels, 0, 256);
                    ImageIO.write(
                            image, "png", output.resolve("absolute-rebase-gradient.png").toFile());
                }
            }
        }
    }

    private static void slopedGradients(WorldStreamSpike.Harness gpu) throws Exception {
        for (double origin : new double[] {0, 10000, 999000, -999000}) {
            commit(gpu, List.of());
            // x - origin = 100 + .25 * (y - origin): both atlas coordinates vary.
            var a = new Vertex(origin + 75, origin - 100, origin - 100);
            var b = new Vertex(origin + 125, origin + 100, origin - 100);
            var c = new Vertex(origin + 125, origin + 100, origin + 100);
            var d = new Vertex(origin + 75, origin - 100, origin + 100);
            var first =
                    upload(
                            gpu,
                            0,
                            List.of(new Triangle(a, b, c, 200, 40, 60)),
                            gradient(),
                            (float) origin + 200,
                            (float) origin + 200,
                            true);
            var second =
                    upload(
                            gpu,
                            1,
                            List.of(new Triangle(a, c, d, 200, 40, 60)),
                            gradient(),
                            (float) origin + 200,
                            (float) origin + 200,
                            true);
            commit(gpu, List.of(first, second));
            for (double offset : new double[] {0, .030, .031, .032, .034, -.031, -.032}) {
                var camera =
                        new TerrainCamera(
                                origin + offset, origin - offset, origin + offset, .08, -.04);
                var pixels = render(gpu, camera);
                for (int y = 32; y <= 224; y++) {
                    for (int x = 32; x <= 224; x++) {
                        double right = (x + .5 - 128) / 204.8, up = (128 - y - .5) / 204.8;
                        double cy = Math.cos(.08),
                                sy = Math.sin(.08),
                                cp = Math.cos(-.04),
                                sp = Math.sin(-.04);
                        double rayX = cy * cp + sy * right - cy * sp * up;
                        double rayY = sy * cp - cy * right - sy * sp * up;
                        double distance =
                                (100 - (camera.x() - origin) + .25 * (camera.y() - origin))
                                        / (rayX - .25 * rayY);
                        double nativeX = camera.x() + distance * rayX;
                        double nativeY = camera.y() + distance * rayY;
                        int tx =
                                Math.clamp(
                                        (int) Math.floor((origin + 200 - nativeY) * .96), 0, 511);
                        int ty =
                                Math.clamp(
                                        (int) Math.floor((origin + 200 - nativeX) * .96), 0, 511);
                        pixel(pixels, x, y, 0xff000025 | (tx / 2) << 16 | (ty / 2) << 8);
                        samples++;
                    }
                }
            }
        }
    }

    private static void coplanarSourceOrder(WorldStreamSpike.Harness gpu) throws Exception {
        for (double origin : new double[] {0, 10000, 999000, -999000}) {
            commit(gpu, List.of());
            var first =
                    upload(
                            gpu,
                            0,
                            quad(origin),
                            WorldStreamSpike.rgba(210, 30, 50),
                            (float) origin + 200,
                            (float) origin + 200,
                            true);
            // Different anchor and atlas: identical geometry must still have identical depth math.
            var second =
                    upload(
                            gpu,
                            1,
                            quad(origin),
                            WorldStreamSpike.rgba(20, 220, 70),
                            (float) origin + 220,
                            (float) origin + 240,
                            true);
            for (double offset : new double[] {.030, .031, .032, .034}) {
                var camera =
                        new TerrainCamera(
                                origin + offset, origin - offset, origin + offset, .1, -.07);
                commit(gpu, List.of(first, second));
                pixel(render(gpu, camera), 128, 128, 0xffd21e32);
                commit(gpu, List.of(second, first));
                pixel(render(gpu, camera), 128, 128, 0xff14dc46);
            }
        }
    }

    private static Triangle panel(double depth, boolean red) {
        return new Triangle(
                new Vertex(depth, -.2 * depth, -.1 * depth),
                new Vertex(depth, .2 * depth, -.1 * depth),
                new Vertex(depth, 0, .2 * depth),
                red ? 200 : 20,
                red ? 40 : 220,
                red ? 60 : 80);
    }

    private static int[] flat(WorldStreamSpike.Harness gpu, List<Triangle> triangles)
            throws Exception {
        commit(gpu, List.of());
        var resident = upload(gpu, 0, triangles, WorldStreamSpike.rgba(0, 0, 0), 0, 0, false);
        commit(gpu, List.of(resident));
        return render(gpu, new TerrainCamera(0, 0, 0, 0, 0));
    }

    private static void nearFarDepth(WorldStreamSpike.Harness gpu) throws Exception {
        pixel(flat(gpu, List.of(panel(.49, true))), 128, 128, 0xff334455);
        pixel(flat(gpu, List.of(panel(.5, true))), 128, 128, 0xffc8283c);
        pixel(flat(gpu, List.of(panel(2200.1, true))), 128, 128, 0xff334455);
        pixel(flat(gpu, List.of(panel(2200, true))), 128, 128, 0xffa0a2af);
        var crossing =
                new Triangle(
                        new Vertex(.25, -.2, -.2),
                        new Vertex(1, .4, -.2),
                        new Vertex(1, 0, .4),
                        200,
                        40,
                        60);
        pixel(flat(gpu, List.of(crossing)), 128, 128, 0xffc8283c);
        for (double gap : new double[] {.01, .1}) {
            var near = panel(1999.9, true);
            var far = panel(1999.9 + gap, false);
            pixel(flat(gpu, List.of(near, far)), 128, 128, 0xffa0a2af);
            pixel(flat(gpu, List.of(far, near)), 128, 128, 0xffa0a2af);
        }
        var near =
                new Triangle(
                        new Vertex(2000, -800000, 0),
                        new Vertex(100000, 40000, 40000),
                        new Vertex(100000, 40000, -40000),
                        200,
                        40,
                        60);
        double scale = 1.00001;
        var far =
                new Triangle(
                        new Vertex(2000 * scale, -800000 * scale, 0),
                        new Vertex(100000 * scale, 40000 * scale, 40000 * scale),
                        new Vertex(100000 * scale, 40000 * scale, -40000 * scale),
                        20,
                        220,
                        80);
        pixel(flat(gpu, List.of(near, far)), 128, 128, 0xffa0a2af);
        pixel(flat(gpu, List.of(far, near)), 128, 128, 0xffa0a2af);
    }

    private static void pixel(int[] image, int x, int y, int expected) {
        int actual = image[y * 256 + x];
        for (int shift : new int[] {0, 8, 16, 24}) {
            if (Math.abs((actual >>> shift & 255) - (expected >>> shift & 255)) > 1) {
                throw new AssertionError(
                        "Pixel("
                                + x
                                + ","
                                + y
                                + ") expected="
                                + Integer.toHexString(expected)
                                + " actual="
                                + Integer.toHexString(actual)
                                + " frame="
                                + frames);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
