package com.vibeminer.rendering.bgfx;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Explicit authored-data proof of the actual offscreen sidecar and mapped-frame transport. */
public final class GpuInlineProof {
    private GpuInlineProof() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected artifact directory");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path temporary = Files.createTempDirectory("vimi-inline-proof-");
        Path scene = temporary.resolve("scene.bin");
        Path framePath = temporary.resolve("frames.bin");
        var mesh =
                new PreviewMesh(
                        new float[] {0, -1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1},
                        new int[] {0, 1, 2, 0, 2, 3},
                        List.of(new PreviewMesh.Part(42, 0, 6)));
        var appearance =
                new MeshAppearance(
                        mesh,
                        new float[] {0, 1, 1, 1, 1, 0, 0, 0},
                        List.of(new MeshAppearance.Surface(0, 0, false, false, false)),
                        List.of(
                                new MeshAppearance.Texture(
                                        2,
                                        2,
                                        new int[] {
                                            0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff
                                        })));
        GpuSceneFile.write(scene, mesh, appearance, Set.of(42));
        var status = new StringBuilder();
        var errors = new StringBuilder();
        Process child = null;
        try (var frames = GpuFrameFile.create(framePath, 960, 720)) {
            long started = System.nanoTime();
            var command =
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            GpuViewerMain.class.getName(),
                            "--scene",
                            scene.toString(),
                            "--frames",
                            framePath.toString(),
                            "--parent-pid",
                            Long.toString(ProcessHandle.current().pid()),
                            "--backend",
                            "vulkan");
            var builder = new ProcessBuilder(command);
            builder.environment().remove("DISPLAY");
            builder.environment().remove("WAYLAND_DISPLAY");
            child = builder.start();
            Thread stdout = drain(child.getInputStream(), status);
            Thread stderr = drain(child.getErrorStream(), errors);
            try (var control = new BufferedWriter(new OutputStreamWriter(child.getOutputStream()));
                    var reader = GpuFrameFile.open(framePath)) {
                send(control, 1, 640, 480, 0, 6);
                var first = await(frames, child, 0, 1);
                double coldMs = (System.nanoTime() - started) / 1e6;
                require(first.width() == 640 && first.height() == 480, "Initial dimensions");
                checkQuadrants(first);
                save(first, output.resolve("gpu-inline-orientation.png"));
                var latency = new ArrayList<Double>();
                var copies = new ArrayList<Double>();
                long sequence = first.sequence();
                long bytes = 0;
                long warmStart = System.nanoTime();
                for (int i = 0; i < 12; i++) {
                    long request = System.nanoTime();
                    send(control, i + 2, 960, 720, (i % 2) * .2, 6);
                    var frame = await(frames, child, sequence, i + 2);
                    latency.add((System.nanoTime() - request) / 1e6);
                    require(frame.width() == 960 && frame.height() == 720, "Resize target");
                    long copyStart = System.nanoTime();
                    // Read through a second mapping to measure the actual stable mapped copy.
                    {
                        var copy = reader.readLatest(sequence);
                        require(
                                copy != null && copy.controlRevision() == i + 2,
                                "Independent mapped copy");
                    }
                    copies.add((System.nanoTime() - copyStart) / 1e6);
                    sequence = frame.sequence();
                    bytes += frame.width() * frame.height() * 4L;
                }
                double warmSeconds = (System.nanoTime() - warmStart) / 1e9;
                send(control, 14, 960, 720, 0, 4);
                var plain = await(frames, child, sequence, 14);
                require(
                        plain.pixels().get(360 * 960 + 480) != first.pixels().get(240 * 640 + 320),
                        "Texture mode changes");
                for (int revision = 15; revision <= 114; revision++)
                    send(control, revision, 960, 720, .3, 6);
                send(control, 115, 640, 480, 0, 6);
                var stable = await(frames, child, plain.sequence(), 115);
                checkQuadrants(stable);
                Thread.sleep(150);
                require(
                        frames.readLatest(stable.sequence()) == null,
                        "Stable view publishes no redundant frames");
                control.write("CLOSE\n");
                control.flush();
                require(child.waitFor(10, TimeUnit.SECONDS), "Bounded CLOSE exit");
                require(child.exitValue() == 0, "Clean child exit: " + errors);
                stdout.join(2000);
                stderr.join(2000);
                require(
                        status.toString().contains("HELLO Vulkan")
                                && status.toString().contains("READY Vulkan")
                                && status.toString().contains("CLOSED"),
                        "Sidecar lifecycle: " + status);
                String report =
                        "{\n  \"backend\": \"Vulkan; software adapters rejected by sidecar\",\n"
                                + "  \"windowOpened\": false,\n  \"coldStartupMs\": "
                                + coldMs
                                + ",\n"
                                + "  \"warmRequestToCopiedFrameP50Ms\": "
                                + percentile(latency, .5)
                                + ",\n"
                                + "  \"warmRequestToCopiedFrameP95Ms\": "
                                + percentile(latency, .95)
                                + ",\n"
                                + "  \"independentMappedCopyP50Ms\": "
                                + percentile(copies, .5)
                                + ",\n"
                                + "  \"sampledFramesPerSecond\": "
                                + (12 / warmSeconds)
                                + ",\n"
                                + "  \"sampledPixelBytes\": "
                                + bytes
                                + ",\n"
                                + "  \"finalControlRevision\": 115,\n  \"fxPresentationMeasured\": false\n}\n";
                Files.writeString(output.resolve("gpu-inline-proof.json"), report);
                System.out.println(report);
            }
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(framePath);
            Files.deleteIfExists(scene);
            Files.deleteIfExists(temporary);
        }
    }

    private static void send(
            BufferedWriter output, long revision, int width, int height, double yaw, int flags)
            throws Exception {
        output.write(
                "VIEW " + revision + " " + width + " " + height + " " + yaw + " 0 1 " + flags
                        + "\n");
        output.flush();
    }

    private static GpuFrameFile.Frame await(
            GpuFrameFile frames, Process child, long after, long revision) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var frame = frames.readLatest(after);
            if (frame != null && frame.controlRevision() == revision) return frame;
            require(child.isAlive(), "Sidecar died awaiting revision " + revision);
            Thread.sleep(2);
        }
        throw new AssertionError("Timed out awaiting revision " + revision);
    }

    private static void checkQuadrants(GpuFrameFile.Frame frame) {
        var p = frame.pixels();
        int w = frame.width(), h = frame.height();
        int left = w * 42 / 100, right = w * 58 / 100, top = h * 45 / 100, bottom = h * 61 / 100;
        int red = p.get(top * w + left),
                green = p.get(top * w + right),
                blue = p.get(bottom * w + left),
                white = p.get(bottom * w + right);
        require((red >>> 16 & 255) > 100 && (red & 65535) == 0, "Top left red / BGRA byte order");
        require((green >>> 8 & 255) > 100 && (green & 0xff00ff) == 0, "Top right green");
        require((blue & 255) > 100 && (blue & 0xffff00) == 0, "Bottom left blue / vertical origin");
        require((white & 255) > 100 && (white >>> 16 & 255) > 100, "Bottom right white");
        require((red >>> 24) == 255, "Opaque ARGB_PRE");
    }

    private static void save(GpuFrameFile.Frame frame, Path path) throws Exception {
        var image = new BufferedImage(frame.width(), frame.height(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[frame.width() * frame.height()];
        frame.pixels().get(pixels);
        image.setRGB(0, 0, frame.width(), frame.height(), pixels, 0, frame.width());
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static Thread drain(InputStream input, StringBuilder result) {
        return Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (input) {
                                byte[] bytes = new byte[1024];
                                int n;
                                while ((n = input.read(bytes)) != -1)
                                    synchronized (result) {
                                        if (result.length() < 16384)
                                            result.append(
                                                    new String(
                                                            bytes,
                                                            0,
                                                            Math.min(n, 16384 - result.length()),
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                                    }
                            } catch (java.io.IOException ignored) {
                            }
                        });
    }

    private static double percentile(List<Double> values, double percentile) {
        var sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * percentile) - 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
