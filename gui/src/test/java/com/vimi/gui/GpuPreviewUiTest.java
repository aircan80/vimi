package com.vimi.gui;

import com.vibeminer.rendering.models.PreviewMesh;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Authored UI only: pending inline GPU and failure fallback; starts no native renderer. */
public final class GpuPreviewUiTest {
    private GpuPreviewUiTest() {}

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("vimi-gpu-ui-test-");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var selectedParts = new java.util.concurrent.atomic.AtomicReference<Set<Integer>>();
        var service =
                new GpuPreviewService(
                        temporary,
                        (file, mesh, appearance, parts) -> {
                            selectedParts.set(parts);
                            Files.writeString(file, "authored");
                        },
                        scene -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IOException("Fixture gate timed out");
                            } catch (InterruptedException error) {
                                throw new IOException(error);
                            }
                            throw new IOException("Authored unavailable GPU");
                        },
                        Duration.ofSeconds(2),
                        Duration.ofMillis(10));
        var ready = new CompletableFuture<ModelPreviewView>();
        Platform.startup(
                () -> {
                    try {
                        Platform.setImplicitExit(false);
                        var view = new ModelPreviewView(service);
                        var stage = new Stage();
                        stage.setScene(new Scene(view, 640, 560));
                        stage.show();
                        view.applyCss();
                        view.layout();
                        var action = (CheckBox) view.lookup("#model-gpu-preview");
                        require(action.isDisabled(), "No GPU launch before a scene exists");
                        view.setMesh(
                                new PreviewMesh(
                                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                        new int[] {0, 1, 2},
                                        List.of(new PreviewMesh.Part(1, 0, 3))),
                                Set.of(1));
                        require(
                                !action.isDisabled(),
                                "Recorded mesh enables inline GPU preference");
                        ((javafx.scene.control.CheckBox) view.lookup("#model-all-surfaces"))
                                .setSelected(true);
                        require(action.isSelected(), "GPU is preferred automatically");
                        ready.complete(view);
                    } catch (Throwable failure) {
                        ready.completeExceptionally(failure);
                    }
                });
        ModelPreviewView view = null;
        try {
            view = ready.get(5, TimeUnit.SECONDS);
            require(entered.await(3, TimeUnit.SECONDS), "GPU launch remains pending in worker");
            require(
                    Set.of(1).equals(selectedParts.get()),
                    "Scene retains qualified part IDs; All is a control flag");
            final var selected = view;
            fx(
                    () -> {
                        var activity = (ProgressIndicator) selected.lookup("#model-gpu-activity");
                        require(
                                activity.isVisible() && activity.isManaged(),
                                "Pending inline renderer has visible activity");
                        require(
                                activity.getAccessibleText().contains("activity"),
                                "Busy state is accessible");
                        require(
                                ((CheckBox) selected.lookup("#model-gpu-preview")).isSelected(),
                                "GPU preference can be disabled while preparing");
                    });
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
            fx(
                    () -> {
                        require(
                                !((ProgressIndicator) selected.lookup("#model-gpu-activity"))
                                        .isVisible(),
                                "Failure clears busy state");
                        require(
                                ((Label) selected.lookup("#model-gpu-status"))
                                        .getText()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .contains("software"),
                                "Failure explicitly preserves inline preview");
                        require(
                                ((CheckBox) selected.lookup("#model-gpu-preview")).isSelected(),
                                "Failure preserves user preference without restarting repeatedly");
                        selected.close();
                    });
            var ownerStage = (Stage) selected.getScene().getWindow();
            inlineFramesAndVisibility(temporary, selected);
            terminalFailureRejectsQueuedFrame(temporary, ownerStage);
        } finally {
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
            final var selected = view;
            fx(
                    () -> {
                        if (selected != null) selected.close();
                    });
            Platform.exit();
            try (var files = Files.walk(temporary)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        System.out.println(
                "GPU preview authored UI: automatic inline preference, visible activity and"
                        + " software fallback passed");
    }

    private static void inlineFramesAndVisibility(Path temporary, ModelPreviewView oldView)
            throws Exception {
        var child = new GpuPreviewServiceTest.FakeProcess(true);
        var frames = new java.util.concurrent.ConcurrentLinkedQueue<GpuPreviewService.Frame>();
        var launches = new java.util.concurrent.atomic.AtomicInteger();
        var renderer =
                new GpuPreviewService(
                        temporary,
                        (file, mesh, appearance, parts) -> Files.writeString(file, "authored"),
                        (scene, mapping) -> {
                            launches.incrementAndGet();
                            return child;
                        },
                        file ->
                                new GpuPreviewService.Frames() {
                                    public GpuPreviewService.Frame readLatest(long after) {
                                        return frames.poll();
                                    }

                                    public void close() {}
                                },
                        Duration.ofSeconds(3),
                        Duration.ofMillis(20));
        var viewRef = new java.util.concurrent.atomic.AtomicReference<ModelPreviewView>();
        try {
            fx(
                    () -> {
                        var stage = (Stage) oldView.getScene().getWindow();
                        var view = new ModelPreviewView(renderer);
                        stage.setScene(new Scene(view, 640, 560));
                        view.applyCss();
                        view.layout();
                        view.setMesh(
                                new PreviewMesh(
                                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                        new int[] {0, 1, 2},
                                        List.of(new PreviewMesh.Part(1, 0, 3))),
                                Set.of(1));
                        viewRef.set(view);
                    });
            require(
                    child.started.await(3, TimeUnit.SECONDS),
                    "Visible viewport automatically launches renderer");
            child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            String[] first = latestView(child, 0);
            long firstRevision = Long.parseLong(first[1]);
            publish(frames, 1, first, 0xff123456);
            waitFor(() -> pixel(viewRef.get()) == 0xff123456, "GPU pixels display inline");
            fx(
                    () -> {
                        var viewport = viewRef.get().lookup("#model-preview-viewport");
                        for (int i = 0; i < 2; i++)
                            viewport.fireEvent(
                                    new javafx.scene.input.KeyEvent(
                                            javafx.scene.input.KeyEvent.KEY_PRESSED,
                                            "",
                                            "",
                                            javafx.scene.input.KeyCode.LEFT,
                                            false,
                                            false,
                                            false,
                                            false));
                    });
            String[] camera = latestView(child, firstRevision);
            // A new camera request does not invalidate an already completed newer camera frame.
            long cameraRevision = Long.parseLong(camera[1]);
            fx(
                    () ->
                            viewRef.get()
                                    .lookup("#model-preview-viewport")
                                    .fireEvent(
                                            new javafx.scene.input.KeyEvent(
                                                    javafx.scene.input.KeyEvent.KEY_PRESSED,
                                                    "",
                                                    "",
                                                    javafx.scene.input.KeyCode.LEFT,
                                                    false,
                                                    false,
                                                    false,
                                                    false)));
            String[] finalCamera = latestView(child, cameraRevision);
            publish(frames, 2, camera, 0xff234567);
            waitFor(
                    () -> pixel(viewRef.get()) == 0xff234567,
                    "Lagging camera frame remains presentable during interaction");
            fx(() -> ((CheckBox) viewRef.get().lookup("#model-all-surfaces")).fire());
            String[] policy = latestView(child, Long.parseLong(finalCamera[1]));
            publish(frames, 3, camera, 0xff345678);
            Thread.sleep(100);
            require(pixel(viewRef.get()) == 0xff234567, "Older policy frame is rejected");
            publish(frames, 4, policy, 0xff456789);
            waitFor(() -> pixel(viewRef.get()) == 0xff456789, "Current policy frame displays");
            require(launches.get() == 1, "Camera and display controls do not restart renderer");
            fx(() -> viewRef.get().setVisible(false));
            waitFor(() -> !child.isAlive(), "Hidden viewport releases renderer");
        } finally {
            fx(
                    () -> {
                        if (viewRef.get() != null) viewRef.get().close();
                    });
            renderer.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    private static void terminalFailureRejectsQueuedFrame(Path temporary, Stage stage)
            throws Exception {
        var child = new GpuPreviewServiceTest.FakeProcess(true);
        var frames = new java.util.concurrent.ConcurrentLinkedQueue<GpuPreviewService.Frame>();
        var scheduled = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        var renderer =
                new GpuPreviewService(
                        temporary,
                        (file, mesh, appearance, parts) -> Files.writeString(file, "authored"),
                        (scene, mapping) -> child,
                        file ->
                                new GpuPreviewService.Frames() {
                                    public GpuPreviewService.Frame readLatest(long after) {
                                        return frames.poll();
                                    }

                                    public void close() {}
                                },
                        Duration.ofSeconds(3),
                        Duration.ofMillis(20));
        var view = new java.util.concurrent.atomic.AtomicReference<ModelPreviewView>();
        try {
            fx(
                    () -> {
                        var panel = new ModelPreviewView(renderer, scheduled::add);
                        stage.setScene(new Scene(panel, 640, 560));
                        panel.applyCss();
                        panel.layout();
                        panel.setMesh(
                                new PreviewMesh(
                                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                        new int[] {0, 1, 2},
                                        List.of(new PreviewMesh.Part(1, 0, 3))),
                                Set.of(1));
                        view.set(panel);
                    });
            require(child.started.await(3, TimeUnit.SECONDS), "Terminal fixture starts renderer");
            child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            var command = latestView(child, 0);
            Thread.sleep(50);
            fx(
                    () -> {
                        Runnable task;
                        while ((task = scheduled.poll()) != null) task.run();
                    });
            publish(frames, 1, command, 0xff123456);
            waitFor(() -> !scheduled.isEmpty(), "GPU frame queued before terminal status");
            Runnable queuedFrame = scheduled.remove();
            child.send("VIMI_GPU ERROR GPU_UNAVAILABLE Authored failure\n");
            waitFor(
                    () -> !child.isAlive() && !scheduled.isEmpty(),
                    "Failure status precedes held GPU delivery");
            fx(
                    () -> {
                        Runnable task;
                        while ((task = scheduled.poll()) != null) task.run();
                        queuedFrame.run();
                        var status = ((Label) view.get().lookup("#model-gpu-status")).getText();
                        require(
                                status.toLowerCase(java.util.Locale.ROOT).contains("software"),
                                "Queued GPU frame cannot restore GPU-ready status after fallback");
                    });
            require(
                    pixel(view.get()) != 0xff123456,
                    "Queued GPU pixels are retired by terminal failure");
        } finally {
            fx(
                    () -> {
                        if (view.get() != null) view.get().close();
                    });
            renderer.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    private static String[] latestView(GpuPreviewServiceTest.FakeProcess child, long after)
            throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<String[]>();
        waitFor(
                () -> {
                    for (var line : child.controls.toString().split("\n")) {
                        var fields = line.split(" ");
                        if (fields.length == 8
                                && fields[0].equals("VIEW")
                                && Long.parseLong(fields[1]) > after) result.set(fields);
                    }
                    return result.get() != null;
                },
                "Latest viewport control reaches renderer");
        return result.get();
    }

    private static void publish(
            java.util.Queue<GpuPreviewService.Frame> frames,
            long sequence,
            String[] command,
            int color) {
        int width = Integer.parseInt(command[2]), height = Integer.parseInt(command[3]);
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, color);
        frames.add(
                new GpuPreviewService.Frame(
                        sequence,
                        Long.parseLong(command[1]),
                        width,
                        height,
                        java.nio.IntBuffer.wrap(pixels).asReadOnlyBuffer()));
    }

    private static int pixel(ModelPreviewView view) throws Exception {
        var result = new java.util.concurrent.atomic.AtomicInteger();
        fx(
                () -> {
                    var bitmap =
                            ((javafx.scene.image.ImageView) view.lookup("#model-preview-image"))
                                    .getImage();
                    result.set(bitmap == null ? 0 : bitmap.getPixelReader().getArgb(0, 0));
                });
        return result.get();
    }

    @FunctionalInterface
    private interface Check {
        boolean get() throws Exception;
    }

    private static void waitFor(Check condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.get() && System.nanoTime() < deadline) Thread.sleep(10);
        require(condition.get(), message);
    }

    private static void fx(Runnable action) throws Exception {
        var complete = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        complete.complete(null);
                    } catch (Throwable failure) {
                        complete.completeExceptionally(failure);
                    }
                });
        complete.get(3, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
