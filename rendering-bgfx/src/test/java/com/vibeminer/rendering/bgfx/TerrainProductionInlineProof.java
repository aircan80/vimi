package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainScene;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Actual authored WORLD sidecar/mmap proof, including failure protocol and bounded cleanup. */
public final class TerrainProductionInlineProof {
    private TerrainProductionInlineProof() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected authored artifact directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        double cold, warm;
        try (var session = new Session(800, 500, false)) {
            long start = System.nanoTime();
            session.send("WORLD 1 256 256 0 0 0 0 0");
            var first = session.await(0, 1);
            cold = (System.nanoTime() - start) / 1e6;
            require(
                    first.pixels().get(128 * 256 + 128) == 0xff2c4729,
                    "Native +X flat terrain center");
            require(
                    first.pixels().get(0) == 0xff5b8db1,
                    "Integer-row sky through BGRA frame transport");
            var image =
                    new BufferedImage(first.width(), first.height(), BufferedImage.TYPE_INT_ARGB);
            int[] pixels = new int[first.width() * first.height()];
            first.pixels().get(pixels);
            image.setRGB(0, 0, first.width(), first.height(), pixels, 0, first.width());
            ImageIO.write(image, "png", output.resolve("terrain-inline-authored.png").toFile());
            start = System.nanoTime();
            session.send("WORLD 2 400 300 0 0 0 0.1 0.05");
            var resized = session.await(first.sequence(), 2);
            warm = (System.nanoTime() - start) / 1e6;
            require(
                    resized.width() == 400 && resized.height() == 300,
                    "WORLD resize uses same child");
            for (int revision = 3; revision <= 40; revision++) {
                session.send("WORLD " + revision + " 800 500 0 0 0 0.2 0");
            }
            var latest = session.await(resized.sequence(), 40);
            require(latest.width() == 800 && latest.height() == 500, "Latest bounded camera burst");
            session.send("WORLD 41 800 500 0 0 0 0 0");
            session.send("CLOSE");
            session.exit(true);
            require(
                    session.status().contains("VIMI_GPU HELLO Vulkan")
                            && session.status().contains("VIMI_GPU READY Vulkan"),
                    "Structured Vulkan readiness");
        }
        try (var session = new Session(800, 500, false)) {
            session.send("WORLD 1 256 256 0 0 0 0 0");
            session.await(0, 1);
            session.control.close();
            session.exit(true);
        }
        try (var session = new Session(800, 500, false)) {
            session.send("WORLD 1 256 256 0 0 0 0 0");
            session.await(0, 1);
            session.send("VIEW 2 256 256 0 0 1 2");
            session.exit(false);
        }
        try (var session = new Session(800, 500, false)) {
            session.send("WORLD 1 801 500 0 0 0 0 0");
            session.exit(false);
        }
        for (int[] bound : List.of(new int[] {960, 400}, new int[] {800, 501})) {
            try (var session = new Session(bound[0], bound[1], false)) {
                session.exit(false);
                require(
                        !session.status().contains("HELLO"),
                        "Oversized bridge declined before GPU initialization");
            }
        }
        try (var session = new Session(800, 500, true)) {
            session.exit(false);
            require(
                    !session.status().contains("HELLO"),
                    "Mixed scene kinds declined before GPU initialization");
        }
        String report =
                "{\"backend\":\"Vulkan\",\"coldRequestToFrameMs\":"
                        + cold
                        + ",\"warmCameraResizeMs\":"
                        + warm
                        + ",\"windowOpened\":false,\"cases\":[\"WORLD-frame-color\",\"camera-resize\",\"latest-burst\",\"pending-CLOSE\",\"EOF\",\"wrong-VIEW\",\"oversized-control\",\"mapping-cap\",\"mixed-scenes\"]}\n";
        Files.writeString(output.resolve("terrain-inline-proof.json"), report);
        System.out.println("Production terrain inline proof PASS: " + report);
    }

    private static final class Session implements AutoCloseable {
        private final Path directory;
        private final GpuFrameFile frames;
        private final Process child;
        private final BufferedWriter control;
        private final StringBuilder status = new StringBuilder(), errors = new StringBuilder();
        private final Thread stdout, stderr;

        Session(int width, int height, boolean mixed) throws Exception {
            directory = Files.createTempDirectory("vimi-terrain-inline-proof-");
            Path scene = directory.resolve("scene"), framePath = directory.resolve("frames");
            var terrain =
                    new TerrainScene(
                            List.of(
                                    TerrainScene.Tile.copyOf(
                                            32,
                                            32,
                                            new float[] {4, -2, -1, 4, 2, -1, 4, 0, 2},
                                            new int[] {0, 1, 2},
                                            1)));
            GpuTerrainSceneFile.write(scene, terrain);
            GpuFrameFile mapped = GpuFrameFile.create(framePath, width, height);
            Process process = null;
            try {
                boolean windows =
                        System.getProperty("os.name")
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains("win");
                var command =
                        new ArrayList<>(
                                List.of(
                                        Path.of(
                                                        System.getProperty("java.home"),
                                                        "bin",
                                                        windows ? "java.exe" : "java")
                                                .toString(),
                                        "--enable-native-access=ALL-UNNAMED",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        GpuViewerMain.class.getName(),
                                        "--terrain-scene",
                                        scene.toString(),
                                        "--frames",
                                        framePath.toString(),
                                        "--parent-pid",
                                        Long.toString(ProcessHandle.current().pid()),
                                        "--backend",
                                        "vulkan"));
                if (mixed) {
                    command.add("--scene");
                    command.add(scene.toString());
                }
                var builder = new ProcessBuilder(command);
                builder.environment().remove("DISPLAY");
                builder.environment().remove("WAYLAND_DISPLAY");
                process = builder.start();
                frames = mapped;
                child = process;
                control = child.outputWriter();
                stdout = drain(child.getInputStream(), status);
                stderr = drain(child.getErrorStream(), errors);
            } catch (Exception | Error failure) {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                mapped.close();
                cleanup(directory);
                throw failure;
            }
        }

        void send(String line) throws IOException {
            control.write(line);
            control.newLine();
            control.flush();
        }

        GpuFrameFile.Frame await(long sequence, long revision) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                var frame = frames.readLatest(sequence);
                if (frame != null && frame.controlRevision() == revision) {
                    return frame;
                }
                require(
                        child.isAlive(),
                        "Terrain child died awaiting revision "
                                + revision
                                + ": "
                                + status()
                                + " "
                                + errors());
                Thread.sleep(2);
            }
            throw new AssertionError("Terrain mapped frame timeout " + revision + ": " + status());
        }

        void exit(boolean clean) throws Exception {
            require(child.waitFor(10, TimeUnit.SECONDS), "Bounded terrain child exit");
            stdout.join(2000);
            stderr.join(2000);
            if (clean) {
                require(
                        child.exitValue() == 0
                                && status().contains("VIMI_GPU CLOSED")
                                && !status().contains("VIMI_GPU ERROR"),
                        "Normal terrain shutdown: " + status() + " " + errors());
            } else {
                require(
                        child.exitValue() != 0
                                && status().contains("VIMI_GPU ERROR")
                                && !status().contains("VIMI_GPU CLOSED"),
                        "Terrain failure cannot masquerade as normal CLOSED: "
                                + status()
                                + " "
                                + errors());
            }
        }

        String status() {
            synchronized (status) {
                return status.toString();
            }
        }

        String errors() {
            synchronized (errors) {
                return errors.toString();
            }
        }

        @Override
        public void close() throws IOException {
            if (child.isAlive()) {
                child.destroyForcibly();
                try {
                    child.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted child cleanup; private scratch retained: " + directory,
                            interrupted);
                }
            }
            require(
                    !child.isAlive(),
                    "Unterminated terrain child; retaining its private scratch: " + directory);
            try {
                control.close();
            } catch (IOException ignored) {
                /* Dead child may have closed its input. */
            }
            frames.close();
            cleanup(directory);
        }
    }

    private static void cleanup(Path directory) throws IOException {
        Files.deleteIfExists(directory.resolve("frames"));
        Files.deleteIfExists(directory.resolve("scene"));
        Files.deleteIfExists(directory);
    }

    private static Thread drain(InputStream stream, StringBuilder output) {
        return Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (stream) {
                                byte[] bytes = new byte[1024];
                                int count;
                                while ((count = stream.read(bytes)) != -1) {
                                    synchronized (output) {
                                        if (output.length() < 16384) {
                                            output.append(
                                                    new String(
                                                            bytes,
                                                            0,
                                                            Math.min(
                                                                    count, 16384 - output.length()),
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                                        }
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        });
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
