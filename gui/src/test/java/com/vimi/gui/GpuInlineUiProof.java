package com.vimi.gui;

import com.vibeminer.rendering.bgfx.GpuSceneFile;
import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

/** Opt-in real Vulkan/JavaFX proof using only private authored geometry and temporary files. */
public final class GpuInlineUiProof {
    private GpuInlineUiProof() {}

    public static void main(String[] args) throws Exception {
        Path output =
                Path.of(
                                System.getProperty(
                                        "vimi.gpuProofPng", "build/ui-review/gpu-inline-proof.png"))
                        .toAbsolutePath();
        Path temporary = Files.createTempDirectory("vimi-real-inline-proof-");
        var processes = new CopyOnWriteArrayList<Process>();
        var scenePaths = new CopyOnWriteArrayList<Path>();
        var service =
                new GpuPreviewService(
                        temporary,
                        GpuSceneFile::write,
                        scene -> {
                            if (processes.stream().anyMatch(Process::isAlive))
                                throw new AssertionError("Renderer overlap");
                            var command = new ArrayList<String>();
                            command.add(RendererChildren.launcherPath());
                            command.add("--enable-native-access=ALL-UNNAMED");
                            command.add("-cp");
                            command.add(System.getProperty("java.class.path"));
                            command.add("com.vibeminer.rendering.bgfx.GpuViewerMain");
                            command.addAll(
                                    List.of(
                                            "--scene",
                                            scene.toAbsolutePath().toString(),
                                            "--frames",
                                            scene.resolveSibling("frames.vgpu")
                                                    .toAbsolutePath()
                                                    .toString(),
                                            "--parent-pid",
                                            Long.toString(ProcessHandle.current().pid()),
                                            "--backend",
                                            "vulkan"));
                            var child = new ProcessBuilder(command).start();
                            processes.add(child);
                            scenePaths.add(scene);
                            return child;
                        },
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(3));
        var view = new AtomicReference<ModelPreviewView>();
        var stage = new AtomicReference<Stage>();
        var started = new CompletableFuture<Void>();
        long begin = System.nanoTime();
        Platform.startup(
                () -> {
                    try {
                        Platform.setImplicitExit(false);
                        var pane = new ModelPreviewView(service);
                        var window = new Stage();
                        window.setTitle("Authored inline GPU proof");
                        window.setScene(new Scene(pane, 720, 600));
                        var mesh =
                                new PreviewMesh(
                                        new float[] {-1, 0, -1, 1, 0, -1, 0, 0, 1, 0, .9f, 0},
                                        new int[] {0, 1, 2, 0, 2, 3, 1, 3, 2},
                                        List.of(new PreviewMesh.Part(7, 0, 9)));
                        var appearance =
                                new MeshAppearance(
                                        mesh,
                                        new float[] {0, 0, 1, 0, .5f, 1, .5f, .5f},
                                        List.of(
                                                new MeshAppearance.Surface(
                                                        0, 0, false, false, false)),
                                        List.of(
                                                new MeshAppearance.Texture(
                                                        2,
                                                        2,
                                                        new int[] {
                                                            0xffe34e45,
                                                            0xff53b96c,
                                                            0xff448edf,
                                                            0xffe7bf49
                                                        })));
                        pane.setMesh(mesh, Set.of(7));
                        pane.setAppearance(appearance, Set.of(7));
                        window.show();
                        pane.applyCss();
                        pane.layout();
                        view.set(pane);
                        stage.set(window);
                        started.complete(null);
                    } catch (Throwable failure) {
                        started.completeExceptionally(failure);
                    }
                });
        try {
            started.get(5, TimeUnit.SECONDS);
            waitFor(
                    () -> gpuStatus(view.get()).startsWith("Inline GPU ·"),
                    25,
                    "Real inline GPU frame");
            System.out.printf(
                    java.util.Locale.ROOT,
                    "Initial real inline frame %.1f ms: %s%n",
                    (System.nanoTime() - begin) / 1_000_000.0,
                    gpuStatus(view.get()));
            require(
                    processes.size() == 1 && processes.getFirst().isAlive(),
                    "One live offscreen renderer");
            long before = imageHash(view.get());
            var latencies = new ArrayList<Double>();
            for (int i = 0; i < 8; i++) {
                long prior = before, requested = System.nanoTime();
                fx(
                        () ->
                                view.get()
                                        .lookup("#model-preview-viewport")
                                        .fireEvent(
                                                new KeyEvent(
                                                        KeyEvent.KEY_PRESSED,
                                                        "",
                                                        "",
                                                        KeyCode.LEFT,
                                                        false,
                                                        false,
                                                        false,
                                                        false)));
                waitFor(
                        () -> imageHash(view.get()) != prior,
                        4,
                        "Camera request produces a different inline frame");
                latencies.add((System.nanoTime() - requested) / 1_000_000.0);
                before = imageHash(view.get());
            }
            require(processes.size() == 1, "Camera controls reuse one renderer");
            int[][] screenshot = new int[1][];
            int[] size = new int[2];
            fx(
                    () -> {
                        var bitmap = view.get().snapshot(null, null);
                        size[0] = (int) bitmap.getWidth();
                        size[1] = (int) bitmap.getHeight();
                        screenshot[0] = new int[size[0] * size[1]];
                        bitmap.getPixelReader()
                                .getPixels(
                                        0,
                                        0,
                                        size[0],
                                        size[1],
                                        PixelFormat.getIntArgbInstance(),
                                        screenshot[0],
                                        0,
                                        size[0]);
                    });
            var png = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, size[0], size[1], screenshot[0], 0, size[0]);
            Files.createDirectories(output.getParent());
            ImageIO.write(png, "png", output.toFile());
            System.out.println(
                    "Camera request-to-observed-image ms "
                            + latencies
                            + "; "
                            + gpuStatus(view.get()));
            fx(() -> view.get().setVisible(false));
            waitFor(
                    () -> processes.stream().noneMatch(Process::isAlive),
                    8,
                    "Hidden viewport stops renderer");
            waitFor(
                    () -> scenePaths.stream().noneMatch(Files::exists),
                    4,
                    "Hidden renderer scratch is reclaimed");
            fx(() -> view.get().setVisible(true));
            waitFor(
                    () -> processes.size() == 2 && processes.getLast().isAlive(),
                    20,
                    "Visible viewport restarts once");
            waitFor(
                    () -> gpuStatus(view.get()).startsWith("Inline GPU ·"),
                    20,
                    "Reattached GPU frame");
            processes.getLast().destroyForcibly();
            waitFor(
                    () ->
                            gpuStatus(view.get())
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains("software"),
                    8,
                    "Unexpected GPU exit falls back");
            Thread.sleep(350);
            require(processes.size() == 2, "Failure does not create a crash loop");
            System.out.println(
                    "Real inline proof passed: visibility drain/restart, failure fallback, no"
                        + " overlap; PNG "
                            + output);
        } finally {
            fx(
                    () -> {
                        if (view.get() != null) view.get().close();
                        if (stage.get() != null) stage.get().hide();
                    });
            service.closeAsync().get(10, TimeUnit.SECONDS);
            for (var child : processes)
                if (child.isAlive()) {
                    child.destroyForcibly();
                    child.waitFor(3, TimeUnit.SECONDS);
                }
            Platform.exit();
            try (var files = Files.walk(temporary)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
    }

    private static String gpuStatus(ModelPreviewView view) throws Exception {
        var text = new AtomicReference<String>();
        fx(() -> text.set(((Label) view.lookup("#model-gpu-status")).getText()));
        return text.get();
    }

    private static long imageHash(ModelPreviewView view) throws Exception {
        var hash = new java.util.concurrent.atomic.AtomicLong();
        fx(
                () -> {
                    var bitmap = ((ImageView) view.lookup("#model-preview-image")).getImage();
                    if (bitmap == null) return;
                    int width = (int) bitmap.getWidth(), height = (int) bitmap.getHeight();
                    int[] pixels = new int[width * height];
                    bitmap.getPixelReader()
                            .getPixels(
                                    0,
                                    0,
                                    width,
                                    height,
                                    PixelFormat.getIntArgbInstance(),
                                    pixels,
                                    0,
                                    width);
                    hash.set(Arrays.hashCode(pixels));
                });
        return hash.get();
    }

    @FunctionalInterface
    private interface Check {
        boolean get() throws Exception;
    }

    private static void waitFor(Check check, int seconds, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!check.get() && System.nanoTime() < deadline) Thread.sleep(10);
        require(check.get(), message);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void fx(Runnable operation) throws Exception {
        var complete = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        operation.run();
                        complete.complete(null);
                    } catch (Throwable failure) {
                        complete.completeExceptionally(failure);
                    }
                });
        complete.get(5, TimeUnit.SECONDS);
    }
}
