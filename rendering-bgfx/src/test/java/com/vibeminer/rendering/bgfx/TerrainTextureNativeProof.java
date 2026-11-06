package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainTextureBinding;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Authored source-independent point sampling, fog, transparency and source-order Vulkan oracles.
 */
public final class TerrainTextureNativeProof {
    private static final int SIDE = 256;
    private static int frames;

    private TerrainTextureNativeProof() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path scratch = Files.createTempDirectory(output, "run-");
        try (var gpu = new TerrainProductionAcceptance.Harness()) {
            for (float offset : new float[] {0, 100000}) {
                var scene = scene(1, offset);
                var camera = new TerrainCamera(offset - 256, offset - 256, 100, 0, -1.5);
                var annex = annex(scratch, "gradient-" + (int) offset, scene, true, false, false);
                try (var renderer = new BgfxTerrainRenderer(scene, annex, () -> false)) {
                    for (var view : List.of(camera, camera.look(.7, .2), camera.look(-.8, .3))) {
                        int[] image = gpu.render(renderer, view);
                        frames++;
                        require(
                                renderer.textureStagingBytes() == 0
                                        && renderer.textureStagingPeakBytes() == 1048576,
                                "bounded one-atlas direct staging must drain before rendering");
                        assertGradient(image, view, offset);
                        require(
                                renderer.textureStatus(TerrainTextureStatus.Code.NONE).state()
                                        == TerrainTextureStatus.State.READY,
                                "full atlas status");
                    }
                }
            }
            var fogScene = scene(1, 0);
            var fogCamera = new TerrainCamera(-256, -256, 800, 0, -1.5);
            try (var renderer =
                    new BgfxTerrainRenderer(
                            fogScene,
                            annex(scratch, "fog", fogScene, true, false, false),
                            () -> false)) {
                int[] image = gpu.render(renderer, fogCamera);
                frames++;
                assertGradient(image, fogCamera, 0);
            }
            var nearScene = scene(1, 0);
            var nearAtlas = annex(scratch, "near", nearScene, true, false, false);
            var nearCamera = new TerrainCamera(-256, -256, .6, 0, -1.5);
            try (var renderer = new BgfxTerrainRenderer(nearScene, nearAtlas, () -> false)) {
                assertGradient(gpu.render(renderer, nearCamera), nearCamera, 0);
                frames++;
            }
            var checks = new java.util.concurrent.atomic.AtomicInteger();
            try {
                new BgfxTerrainRenderer(nearScene, nearAtlas, () -> checks.incrementAndGet() > 5);
                throw new AssertionError("upload cancellation ignored");
            } catch (java.util.concurrent.CancellationException expected) {
                // A new renderer immediately reuses the same context, with no orphaned handles.
            }
            var scene = scene(1, 0);
            var camera = new TerrainCamera(-256, -256, 100, 0, -1.5);
            int[] height;
            try (var renderer = new BgfxTerrainRenderer(scene)) {
                height = gpu.render(renderer, camera);
                frames++;
            }
            var sparse = annex(scratch, "sparse", scene, false, true, false);
            try (var renderer = new BgfxTerrainRenderer(scene, sparse, () -> false)) {
                int[] image = gpu.render(renderer, camera);
                frames++;
                // At these rays the only covered header rectangle (0,0) is not visible.
                for (int y : new int[] {64, 128, 192}) {
                    for (int x : new int[] {64, 128, 192}) {
                        require(
                                image[y * SIDE + x] == height[y * SIDE + x],
                                "alpha-zero fallback changed original height result");
                    }
                }
                require(
                        renderer.textureStatus(TerrainTextureStatus.Code.NONE).state()
                                == TerrainTextureStatus.State.PARTIAL,
                        "sparse status");
            }
            var overlapping = scene(2, 0);
            for (boolean reverse : new boolean[] {false, true}) {
                var atlas = annex(scratch, "order-" + reverse, overlapping, false, false, reverse);
                try (var renderer = new BgfxTerrainRenderer(overlapping, atlas, () -> false)) {
                    int[] image = gpu.render(renderer, camera);
                    frames++;
                    int expected = reverse ? 0xff1339c7 : 0xffd32b11;
                    require(
                            image[128 * SIDE + 128] == expected,
                            "coplanar different textures did not preserve source first-wins: "
                                    + Integer.toHexString(image[128 * SIDE + 128]));
                }
            }
        }
        inlineCases(scratch);
        Files.writeString(
                scratch.resolve("terrain-texture-native.txt"),
                "PASS "
                        + frames
                        + " actual Vulkan renderer frames plus five actual child sessions/nine mapped frames: gradient axes at rebased cameras, original fog, near clipping, alpha0 legacy fallback, opaque/source-order coplanar tiles; valid/partial/checksum/missing/binding ACK fallback, resize, CLOSE/EOF.\n");
        System.out.println(
                "Terrain texture native proof PASS "
                        + frames
                        + " authored frames plus5children/9mappedframes");
    }

    private static void inlineCases(Path scratch) throws Exception {
        var report = new StringBuilder();
        Path geometry = scratch.resolve("gradient-0.terrain");
        Path valid = scratch.resolve("gradient-0.atlas");
        Path sparse = scratch.resolve("sparse.atlas");
        Path corrupt = scratch.resolve("corrupt.atlas");
        byte[] bad = Files.readAllBytes(valid);
        bad[56] ^= 1;
        Files.write(corrupt, bad);
        Path mismatched = scratch.resolve("mismatched.terrain");
        GpuTerrainSceneFile.writeBound(mismatched, scene(1, 100), () -> false);
        for (var selected : List.of(valid, sparse, corrupt, scratch.resolve("missing.atlas"))) {
            try (var child = new Inline(geometry, selected, scratch)) {
                child.send("WORLD 1 256 256 -256 -256 100 0 -1.5");
                var first = child.await(0, 1);
                child.awaitReady();
                int[] copied = new int[256 * 256];
                first.pixels().get(copied);
                if (selected.equals(valid)) {
                    assertGradient(copied, new TerrainCamera(-256, -256, 100, 0, -1.5), 0);
                } else {
                    require(
                            copied[128 * 256 + 128] == 0xff426b3e,
                            "optional fallback preserves independent flat-height color");
                }
                var status = child.textureStatus();
                TerrainTextureStatus.State expected =
                        selected.equals(valid)
                                ? TerrainTextureStatus.State.READY
                                : selected.equals(sparse)
                                        ? TerrainTextureStatus.State.PARTIAL
                                        : TerrainTextureStatus.State.UNAVAILABLE;
                require(status.state() == expected, "optional child texture state " + status);
                if (selected.equals(corrupt)) {
                    require(
                            status.code() == TerrainTextureStatus.Code.CHECKSUM,
                            "checksum fallback code");
                }
                if (selected.getFileName().toString().equals("missing.atlas")) {
                    require(
                            status.code() == TerrainTextureStatus.Code.INPUT_UNAVAILABLE,
                            "missing fallback code");
                }
                require(
                        child.status().indexOf("VIMI_GPU TEXTURES ")
                                < child.status().indexOf("VIMI_GPU READY "),
                        "texture ACK must precede frame readiness");
                child.send("WORLD 2 400 300 -256 -256 100 .2 -1.4");
                var resized = child.await(first.sequence(), 2);
                require(
                        resized.width() == 400 && resized.height() == 300,
                        "textured WORLD camera/resize");
                child.send("CLOSE");
                child.cleanExit();
                report.append(selected.getFileName())
                        .append(" pid=")
                        .append(child.process.pid())
                        .append(" ")
                        .append(status.protocolLine())
                        .append(" revisions=1,2 dimensions=256x256,400x300 CLOSED exit0\n");
            }
        }
        try (var child = new Inline(mismatched, valid, scratch)) {
            child.send("WORLD 1 256 256 -156 -156 100 0 -1.5");
            child.await(0, 1);
            child.awaitReady();
            require(
                    child.textureStatus().state() == TerrainTextureStatus.State.UNAVAILABLE
                            && child.textureStatus().code() == TerrainTextureStatus.Code.BINDING,
                    "geometry-pair mismatch keeps height renderer");
            child.control.close();
            child.cleanExit();
            report.append("mismatched pid=")
                    .append(child.process.pid())
                    .append(" ")
                    .append(child.textureStatus().protocolLine())
                    .append(" revision=1 EOF CLOSED exit0\n");
        }
        Files.writeString(scratch.resolve("inline-texture-status.txt"), report);
    }

    private static final class Inline implements AutoCloseable {
        private final GpuFrameFile frames;
        private final Process process;
        private final java.io.BufferedWriter control;
        private final StringBuilder output = new StringBuilder();
        private final Thread reader;
        private final Path mapping;

        Inline(Path geometry, Path textures, Path scratch) throws Exception {
            mapping = Files.createTempFile(scratch, "mapping-", ".frames");
            Files.delete(mapping);
            frames = GpuFrameFile.create(mapping, 800, 500);
            var command =
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            GpuViewerMain.class.getName(),
                            "--terrain-scene",
                            geometry.toString(),
                            "--terrain-textures",
                            textures.toString(),
                            "--frames",
                            mapping.toString(),
                            "--parent-pid",
                            Long.toString(ProcessHandle.current().pid()),
                            "--backend",
                            "vulkan");
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().remove("DISPLAY");
            builder.environment().remove("WAYLAND_DISPLAY");
            try {
                process = builder.start();
            } catch (Exception failure) {
                frames.close();
                Files.deleteIfExists(mapping);
                throw failure;
            }
            control = process.outputWriter();
            reader =
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () -> {
                                        try (var stream = process.getInputStream()) {
                                            byte[] buffer = new byte[1024];
                                            int count;
                                            while ((count = stream.read(buffer)) != -1) {
                                                synchronized (output) {
                                                    if (output.length() < 65536) {
                                                        output.append(
                                                                new String(
                                                                        buffer,
                                                                        0,
                                                                        Math.min(
                                                                                count,
                                                                                65536
                                                                                        - output
                                                                                                .length()),
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8));
                                                    }
                                                }
                                            }
                                        } catch (java.io.IOException ignored) {
                                        }
                                    });
        }

        String status() {
            synchronized (output) {
                return output.toString();
            }
        }

        TerrainTextureStatus textureStatus() {
            var lines =
                    status().lines().filter(line -> line.startsWith("VIMI_GPU TEXTURES ")).toList();
            require(lines.size() == 1, "exactly one texture ACK " + status());
            return TerrainTextureStatus.parse(lines.getFirst());
        }

        void send(String line) throws Exception {
            control.write(line);
            control.newLine();
            control.flush();
        }

        GpuFrameFile.Frame await(long sequence, long revision) throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                var frame = frames.readLatest(sequence);
                if (frame != null && frame.controlRevision() == revision) {
                    return frame;
                }
                require(process.isAlive(), "native child stopped: " + status());
                Thread.sleep(2);
            }
            throw new AssertionError("native textured mapped frame timeout " + status());
        }

        void awaitReady() throws Exception {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!status().contains("VIMI_GPU READY ") && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            require(
                    status().contains("VIMI_GPU READY "),
                    "native child missing READY: " + status());
        }

        void cleanExit() throws Exception {
            require(
                    process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS),
                    "bounded child shutdown");
            reader.join(2000);
            require(
                    process.exitValue() == 0
                            && status().contains("VIMI_GPU CLOSED")
                            && !status().contains("VIMI_GPU ERROR"),
                    "clean child exit " + status());
        }

        public void close() throws java.io.IOException {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    require(
                            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS),
                            "child drain");
                }
                reader.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Interrupted native proof cleanup", interrupted);
            }
            try {
                control.close();
            } catch (java.io.IOException ignored) {
            }
            frames.close();
            Files.deleteIfExists(mapping);
        }
    }

    private static void assertGradient(int[] image, TerrainCamera camera, float origin) {
        // Independent ground-plane ray/plane intersection. No renderer projection/sample helper.
        int checked = 0;
        // A 5x5 grid of view directions, in focal lengths from the center, so the sampled rays
        // stay on the authored ground whatever the shared field of view.
        double focal = TerrainCamera.focalPixels(256);
        double[] offsets = {-.16, -.08, 0, .08, .16};
        for (double rowOffset : offsets) {
            int y = (int) Math.round(128 + rowOffset * focal);
            for (double columnOffset : offsets) {
                int x = (int) Math.round(128 + columnOffset * focal);
                double right = (x + .5 - 128) / focal, up = (128 - y - .5) / focal;
                double vertical = Math.sin(camera.pitch()) + Math.cos(camera.pitch()) * up;
                double d = -camera.z() / vertical;
                double forward = Math.cos(camera.pitch()) - Math.sin(camera.pitch()) * up;
                double nativeX =
                        camera.x()
                                + d
                                        * (Math.cos(camera.yaw()) * forward
                                                + Math.sin(camera.yaw()) * right);
                double nativeY =
                        camera.y()
                                + d
                                        * (Math.sin(camera.yaw()) * forward
                                                - Math.cos(camera.yaw()) * right);
                double u = (origin - nativeY) * .96, v = (origin - nativeX) * .96;
                if (u < 1
                        || u > 510
                        || v < 1
                        || v > 510
                        || Math.abs(u - Math.rint(u)) < .04
                        || Math.abs(v - Math.rint(v)) < .04) {
                    continue;
                }
                // Fog uses original triangle maximum depth, not this fragment depth.
                // All test quads/cameras here with nonzero fog have yaw0 and both triangles
                // contain a max-nativeX corner; its far depth is independent of fragment XY.
                double maximumDepth =
                        -Math.sin(camera.pitch()) * camera.z()
                                + Math.cos(camera.pitch()) * (origin - camera.x());
                double fog = Math.clamp((maximumDepth - 600) / 1600, 0, .85);
                int red = (int) Math.floor(((int) Math.floor(u) & 255) * (1 - fog) + 153 * fog);
                int green = (int) Math.floor(((int) Math.floor(v) & 255) * (1 - fog) + 184 * fog);
                int blue = (int) Math.floor(31 * (1 - fog) + 196 * fog);
                int expected = 0xff000000 | (red << 16) | (green << 8) | blue;
                int actual = image[y * SIDE + x];
                require(
                        actual == expected,
                        "point atlas independent UV expected="
                                + Integer.toHexString(expected)
                                + " actual="
                                + Integer.toHexString(actual)
                                + " camera="
                                + camera
                                + " pixel="
                                + x
                                + ","
                                + y);
                checked++;
            }
        }
        require(checked >= 15, "insufficient analytic interior coverage");
    }

    private static GpuTerrainTextureAnnex.Decoded annex(
            Path scratch,
            String name,
            TerrainScene scene,
            boolean gradient,
            boolean sparse,
            boolean reverse)
            throws Exception {
        var bindings = new ArrayList<TerrainTextureBinding>();
        for (int ordinal = 0; ordinal < scene.tiles().size(); ordinal++) {
            var tile = scene.tiles().get(ordinal);
            float max = tile.vertices().get(0);
            long[] mask = sparse ? new long[] {1, 0, 0, 0} : new long[] {-1, -1, -1, -1};
            var meta =
                    TerrainTextureBinding.fromSnapshot(
                            ordinal,
                            tile,
                            max,
                            max,
                            mask,
                            new TerrainTextureBinding.Sources(
                                    1, "ab".repeat(16), 2, "cd".repeat(16)),
                            () -> false);
            byte[] rgba = new byte[TerrainTextureBinding.PIXEL_BYTES];
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    if (sparse && (x >= 32 || y >= 32)) {
                        continue;
                    }
                    int p = (y * 512 + x) * 4;
                    boolean first = (ordinal == 0) != reverse;
                    rgba[p] = (byte) (gradient ? x : first ? 211 : 19);
                    rgba[p + 1] = (byte) (gradient ? y : first ? 43 : 57);
                    rgba[p + 2] = (byte) (gradient ? 31 : first ? 17 : 199);
                    rgba[p + 3] = (byte) 255;
                }
            }
            bindings.add(
                    TerrainTextureBinding.borrow(meta, ByteBuffer.wrap(rgba).asReadOnlyBuffer()));
        }
        Path geometry = scratch.resolve(name + ".terrain"),
                annex = scratch.resolve(name + ".atlas");
        var digest = GpuTerrainSceneFile.writeBound(geometry, scene, () -> false);
        GpuTerrainTextureAnnex.write(annex, digest, scene, bindings, () -> false);
        return GpuTerrainTextureAnnex.read(
                annex, GpuTerrainSceneFile.readBound(geometry, () -> false), () -> false);
    }

    private static TerrainScene scene(int count, float offset) {
        float[] vertices = new float[256 * 145 * 3];
        int at = 0;
        for (int chunk = 0; chunk < 256; chunk++) {
            double ax = offset - (chunk / 16) * (1600.0 / 3 / 16),
                    ay = offset - (chunk % 16) * (1600.0 / 3 / 16);
            for (int row = 0; row < 17; row++) {
                for (int col = 0; col < (row % 2 == 0 ? 9 : 8); col++) {
                    vertices[at++] = (float) (ax - row * (1600.0 / 3 / 256));
                    vertices[at++] = (float) (ay - (col * 2 + row % 2) * (1600.0 / 3 / 256));
                    vertices[at++] = 0;
                }
            }
        }
        int a = 0, b = 15 * 145 + 8, c = 240 * 145 + 136, d = 255 * 145 + 144;
        var tiles = new ArrayList<TerrainScene.Tile>();
        for (int i = 0; i < count; i++) {
            tiles.add(TerrainScene.Tile.copyOf(i, 0, vertices, new int[] {a, b, c, b, d, c}, 256));
        }
        return new TerrainScene(tiles);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
