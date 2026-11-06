package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.world.TerrainCamera;
import com.vibeminer.rendering.world.TerrainScene;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Actual authored WORLD sidecar/mmap proof, including failure protocol and bounded cleanup. */
public final class WorldWmoColdChildProof {
    // Terrain sample 32 px below center at the original 204.8 px focal length, as a ray.
    private static final int BELOW = (int) Math.round(128 + .156 * TerrainCamera.focalPixels(256));

    private WorldWmoColdChildProof() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected authored artifact directory");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        for (String mode : List.of("valid", "malformed", "missing", "absent")) {
            try (var session = new Session(mode)) {
                session.send("WORLD 1 256 256 0 0 0 0 0");
                var first = session.await(0, 1);
                int center = first.pixels().get(128 * 256 + 128);
                if (mode.equals("valid")) {
                    require(
                            (center >>> 16 & 255) > 180 && (center >>> 8 & 255) < 10,
                            "Authored WMO red pixel through actual cold child: "
                                    + Integer.toHexString(center));
                    require(
                            first.pixels().get(BELOW * 256 + 128) == 0xff2c4729,
                            "Same mapped frame retains surrounding terrain");
                } else {
                    require(
                            center == 0xff2c4729,
                            "Terrain remains usable after optional input refusal/absence");
                }
                session.send("WORLD 2 400 300 0 0 0 0.1 0.05");
                var resized = session.await(first.sequence(), 2);
                require(
                        resized.width() == 400 && resized.height() == 300,
                        "Same child camera/resize");
                if (mode.equals("valid")) {
                    session.control.close();
                } else {
                    session.send("CLOSE");
                }
                session.exit(true);
                var lines =
                        session.status()
                                .lines()
                                .filter(line -> line.startsWith("VIMI_GPU WORLD_WMO "))
                                .toList();
                require(lines.size() <= 10, "Fixed bounded startup lines");
                if (mode.equals("valid")) {
                    var states = lines.stream().map(WorldWmoStatus::parse).toList();
                    require(
                            states.getFirst().state() == WorldWmoStatus.State.LOADING
                                    && states.get(states.size() - 2).state()
                                            == WorldWmoStatus.State.INITIALIZED
                                    && states.getLast().state() == WorldWmoStatus.State.READY,
                            "Initialization precedes first published frame: " + states);
                    require(
                            session.status().indexOf("WORLD_WMO READY")
                                    < session.status().indexOf("VIMI_GPU READY"),
                            "WMO ready accompanies first publication before generic ready");
                } else if (mode.equals("absent")) {
                    require(lines.isEmpty(), "No optional protocol without flag");
                } else {
                    require(
                            lines.size() == 2
                                    && WorldWmoStatus.parse(lines.getLast()).state()
                                            == WorldWmoStatus.State.UNAVAILABLE,
                            "Expected IO fallback cannot claim initialized/ready");
                }
                Files.writeString(
                        output.resolve(mode + ".log"), session.status() + session.errors());
            }
        }
        System.out.println(
                "Cold WMO owned-child proof PASS: valid pixels, camera/resize, EOF, CLOSE, malformed/missing fallback, absent protocol");
    }

    private static final class Session implements AutoCloseable {
        private final Path directory;
        private final GpuFrameFile frames;
        private final Process child;
        private final BufferedWriter control;
        private final StringBuilder status = new StringBuilder(), errors = new StringBuilder();
        private final Thread stdout, stderr;

        Session(String mode) throws Exception {
            int width = 800, height = 500;
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
            var written = GpuTerrainSceneFile.writeBound(scene, terrain, () -> false);
            Path annex = directory.resolve("annex");
            if (mode.equals("valid")) {
                var data = new WorldWmoAuthoredData(terrain, 2, false, false, false, false);
                for (int vertex = 0; vertex < 3; vertex++) {
                    data.positions[vertex * 3 + 1] *= .05f;
                    data.positions[vertex * 3 + 2] *= .05f;
                }
                GpuWorldWmoAnnex.write(annex, written, terrain, data, () -> false);
            } else if (mode.equals("malformed")) {
                Files.writeString(annex, "bad");
            }
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
                if (!mode.equals("absent")) {
                    command.add("--wmo-annex");
                    command.add(annex.toString());
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
        Files.deleteIfExists(directory.resolve("annex"));
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
