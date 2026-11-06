package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Explicit actual-hardware authored FX proof. Never opens user stores, assets or existing windows.
 */
public final class WorldGpuUiTest {
    private WorldGpuUiTest() {}

    private static final AtomicReference<WorldView> VIEW = new AtomicReference<>();
    private static final AtomicReference<Stage> STAGE = new AtomicReference<>();
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();
    private static final AtomicReference<String> BACKEND = new AtomicReference<>("");
    private static GpuPreviewService service;

    /**
     * The service's ready status, followed by the backend the child reports (the platform default,
     * Direct3D 11 on Windows), so the proof accepts whichever backend the platform qualifies.
     */
    private static final String GPU_READY = "Inline GPU renderer ready · ";

    public static void main(String[] args) throws Exception {
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    try {
                        start();
                    } catch (Throwable error) {
                        DONE.completeExceptionally(error);
                    }
                });
        try {
            DONE.get(45, TimeUnit.SECONDS);
        } finally {
            var disposed = new CompletableFuture<Void>();
            Platform.runLater(
                    () -> {
                        if (VIEW.get() != null) {
                            VIEW.get().close();
                        }
                        if (STAGE.get() != null) {
                            STAGE.get().close();
                        }
                        disposed.complete(null);
                    });
            disposed.get(5, TimeUnit.SECONDS);
            if (service != null) {
                service.closeAsync().get(10, TimeUnit.SECONDS);
            }
            Platform.exit();
        }
        require(children().isEmpty(), "Owned renderer exited after FX close");
        String expected = expectedBackend();
        require(
                expected == null || expected.equals(BACKEND.get()),
                "Platform default backend: expected " + expected + ", got " + BACKEND.get());
        System.out.println(
                "World actual GPU FX: "
                        + BACKEND.get()
                        + " accepted pixel upload, camera/resize persistence,"
                        + " hide/resume, software toggle and owner drain passed");
    }

    private static void start() {
        var map = new WorldService.MapInfo(0, "Authored GPU terrain", "Authored", 0, 1L);
        var tile = new WorldFormats.Tile(17, 21, 777, 0, 0);
        var first = new CompletableFuture<WorldService.LoadedTile>();
        var borrowedCloses = new AtomicInteger();
        BuildWorld handle =
                new BuildWorld() {
                    public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
                        require(!Platform.isFxApplicationThread(), "Catalog off FX");
                        return CompletableFuture.completedFuture(
                                new WorldService.Catalog(List.of(map), List.of(), List.of()));
                    }

                    public CompletableFuture<WorldService.Inventory> inventory(
                            WorldService.MapInfo selected, Cancellation token) {
                        require(!Platform.isFxApplicationThread(), "Inventory off FX");
                        return CompletableFuture.completedFuture(
                                new WorldService.Inventory(
                                        map, new WorldFormats.World(List.of(tile), false), null));
                    }

                    public CompletableFuture<WorldService.LoadedTile> tile(
                            WorldService.MapInfo selected,
                            WorldFormats.Tile requested,
                            Cancellation token) {
                        require(!Platform.isFxApplicationThread(), "Tile acquisition off FX");
                        require(tile.equals(requested), "Exact tile identity");
                        return first;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        borrowedCloses.incrementAndGet();
                        return CompletableFuture.completedFuture(null);
                    }
                };
        service = GpuPreviewService.application();
        var view = new WorldView(() -> handle, () -> 0, service);
        VIEW.set(view);
        var stage = new Stage();
        STAGE.set(stage);
        stage.setScene(new Scene(view, 1100, 800));
        stage.setTitle("Authored World inline GPU proof");
        stage.show();
        view.ensureLoaded();
        await(
                () ->
                        view.lookup("#world-maps") instanceof ListView<?> maps
                                && !maps.getItems().isEmpty(),
                () -> {
                    ((ListView<?>) view.lookup("#world-maps")).getSelectionModel().selectFirst();
                    await(
                            () -> !((Button) view.lookup("#world-open")).isDisabled(),
                            () -> {
                                ((Button) view.lookup("#world-open")).fire();
                                require(
                                        view.lookup("#world-first-frame-activity").isVisible(),
                                        "Initial acquisition keeps first-frame activity");
                                first.complete(new WorldService.LoadedTile(tile, terrain(), null));
                                await(
                                        () -> ready(view) && children().size() == 1,
                                        () -> {
                                            require(
                                                    !view.lookup("#world-first-frame-activity")
                                                            .isVisible(),
                                                    "Only accepted GPU upload ends first-frame"
                                                        + " activity");
                                            var image = (ImageView) view.lookup("#world-frame");
                                            require(
                                                    image.getImage().getWidth() <= 800
                                                            && image.getImage().getHeight() <= 500,
                                                    "Production terrain mapping cap");
                                            long pid = children().iterator().next();
                                            System.out.println(
                                                    "WORLD_GPU_FIRST pid="
                                                            + pid
                                                            + " frame="
                                                            + image.getImage().getWidth()
                                                            + "x"
                                                            + image.getImage().getHeight()
                                                            + " backend="
                                                            + ((Label)
                                                                            view.lookup(
                                                                                    "#world-gpu-status"))
                                                                    .getText());
                                            long original = hash(image);
                                            var viewport = view.lookup("#world-viewport");
                                            snapshot(stage.getScene(), "first");
                                            viewport.fireEvent(
                                                    key(KeyEvent.KEY_PRESSED, KeyCode.W));
                                            await(
                                                    () -> hash(image) != original,
                                                    () -> {
                                                        viewport.fireEvent(
                                                                key(
                                                                        KeyEvent.KEY_RELEASED,
                                                                        KeyCode.W));
                                                        require(
                                                                children().equals(Set.of(pid)),
                                                                "Camera updates preserve the native"
                                                                    + " child");
                                                        double oldW = image.getImage().getWidth(),
                                                                oldH = image.getImage().getHeight();
                                                        stage.setWidth(900);
                                                        stage.setHeight(640);
                                                        await(
                                                                () ->
                                                                        ready(view)
                                                                                && (image.getImage()
                                                                                                        .getWidth()
                                                                                                != oldW
                                                                                        || image.getImage()
                                                                                                        .getHeight()
                                                                                                != oldH),
                                                                () -> {
                                                                    require(
                                                                            children()
                                                                                    .equals(
                                                                                            Set.of(
                                                                                                    pid)),
                                                                            "Resize updates"
                                                                                + " preserve the"
                                                                                + " native child");
                                                                    System.out.println(
                                                                            "WORLD_GPU_CAMERA_RESIZE"
                                                                                + " samePid="
                                                                                    + pid
                                                                                    + " frame="
                                                                                    + image.getImage()
                                                                                            .getWidth()
                                                                                    + "x"
                                                                                    + image.getImage()
                                                                                            .getHeight());
                                                                    stage.hide();
                                                                    await(
                                                                            () ->
                                                                                    children()
                                                                                            .isEmpty(),
                                                                            () -> {
                                                                                require(
                                                                                        image
                                                                                                        .getImage()
                                                                                                != null,
                                                                                        "Hidden"
                                                                                            + " terrain"
                                                                                            + " retains"
                                                                                            + " the last"
                                                                                            + " readable"
                                                                                            + " frame");
                                                                                stage.show();
                                                                                await(
                                                                                        () ->
                                                                                                ready(
                                                                                                                view)
                                                                                                        && children()
                                                                                                                        .size()
                                                                                                                == 1
                                                                                                        && !children()
                                                                                                                .contains(
                                                                                                                        pid),
                                                                                        () -> {
                                                                                            long
                                                                                                    resumed =
                                                                                                            children()
                                                                                                                    .iterator()
                                                                                                                    .next();
                                                                                            ((CheckBox)
                                                                                                            view
                                                                                                                    .lookup(
                                                                                                                            "#world-gpu-enabled"))
                                                                                                    .fire();
                                                                                            await(
                                                                                                    () ->
                                                                                                            children()
                                                                                                                            .isEmpty()
                                                                                                                    && ((Label)
                                                                                                                                    view
                                                                                                                                            .lookup(
                                                                                                                                                    "#world-gpu-status"))
                                                                                                                            .getText()
                                                                                                                            .contains(
                                                                                                                                    "Software"),
                                                                                                    () -> {
                                                                                                        require(
                                                                                                                image
                                                                                                                                .getImage()
                                                                                                                        != null,
                                                                                                                "Explicit"
                                                                                                                    + " software"
                                                                                                                    + " fallback"
                                                                                                                    + " keeps"
                                                                                                                    + " a readable"
                                                                                                                    + " image");
                                                                                                        ((CheckBox)
                                                                                                                        view
                                                                                                                                .lookup(
                                                                                                                                        "#world-gpu-enabled"))
                                                                                                                .fire();
                                                                                                        await(
                                                                                                                () ->
                                                                                                                        ready(
                                                                                                                                        view)
                                                                                                                                && children()
                                                                                                                                                .size()
                                                                                                                                        == 1
                                                                                                                                && !children()
                                                                                                                                        .contains(
                                                                                                                                                resumed),
                                                                                                                () -> {
                                                                                                                    snapshot(
                                                                                                                            stage
                                                                                                                                    .getScene(),
                                                                                                                            "resumed");
                                                                                                                    teleportOutside(
                                                                                                                            view);
                                                                                                                    await(
                                                                                                                            () ->
                                                                                                                                    children()
                                                                                                                                                    .isEmpty()
                                                                                                                                            && ((Label)
                                                                                                                                                            view
                                                                                                                                                                    .lookup(
                                                                                                                                                                            "#world-position"))
                                                                                                                                                    .getText()
                                                                                                                                                    .contains(
                                                                                                                                                            "0 tiles"
                                                                                                                                                                + " loaded")
                                                                                                                                            && ((Label)
                                                                                                                                                            view
                                                                                                                                                                    .lookup(
                                                                                                                                                                            "#world-gpu-status"))
                                                                                                                                                    .getText()
                                                                                                                                                    .contains(
                                                                                                                                                            "no loaded"
                                                                                                                                                                + " nearby"
                                                                                                                                                                + " tiles")
                                                                                                                                            && sky(
                                                                                                                                                    image),
                                                                                                                            () -> {
                                                                                                                                service.closeAsync()
                                                                                                                                        .whenComplete(
                                                                                                                                                (unused,
                                                                                                                                                        error) ->
                                                                                                                                                        Platform
                                                                                                                                                                .runLater(
                                                                                                                                                                        () -> {
                                                                                                                                                                            try {
                                                                                                                                                                                if (error
                                                                                                                                                                                        != null) {
                                                                                                                                                                                    throw new AssertionError(
                                                                                                                                                                                            error);
                                                                                                                                                                                }
                                                                                                                                                                                require(
                                                                                                                                                                                        sky(
                                                                                                                                                                                                image),
                                                                                                                                                                                        "Retired"
                                                                                                                                                                                            + " queued"
                                                                                                                                                                                            + " GPU frames"
                                                                                                                                                                                            + " cannot"
                                                                                                                                                                                            + " overwrite"
                                                                                                                                                                                            + " the empty-neighborhood"
                                                                                                                                                                                            + " CPU sky");
                                                                                                                                                                                require(
                                                                                                                                                                                        children()
                                                                                                                                                                                                .isEmpty(),
                                                                                                                                                                                        "Empty"
                                                                                                                                                                                            + " neighborhood"
                                                                                                                                                                                            + " retires"
                                                                                                                                                                                            + " its GPU"
                                                                                                                                                                                            + " child");
                                                                                                                                                                                snapshot(
                                                                                                                                                                                        stage
                                                                                                                                                                                                .getScene(),
                                                                                                                                                                                        "empty-neighborhood");
                                                                                                                                                                                view
                                                                                                                                                                                        .close();
                                                                                                                                                                                stage
                                                                                                                                                                                        .close();
                                                                                                                                                                                require(
                                                                                                                                                                                        borrowedCloses
                                                                                                                                                                                                        .get()
                                                                                                                                                                                                == 0,
                                                                                                                                                                                        "View never"
                                                                                                                                                                                            + " closes"
                                                                                                                                                                                            + " the borrowed"
                                                                                                                                                                                            + " world"
                                                                                                                                                                                            + " handle");
                                                                                                                                                                                DONE
                                                                                                                                                                                        .complete(
                                                                                                                                                                                                null);
                                                                                                                                                                            } catch (
                                                                                                                                                                                    Throwable
                                                                                                                                                                                            failure) {
                                                                                                                                                                                DONE
                                                                                                                                                                                        .completeExceptionally(
                                                                                                                                                                                                failure);
                                                                                                                                                                            }
                                                                                                                                                                        }));
                                                                                                                            });
                                                                                                                });
                                                                                                    });
                                                                                        });
                                                                            });
                                                                });
                                                    });
                                        });
                            });
                });
    }

    private static void teleportOutside(WorldView view) {
        try {
            var camera = WorldView.class.getDeclaredField("camera");
            camera.setAccessible(true);
            camera.set(
                    view,
                    new com.vibeminer.rendering.world.TerrainRenderer.Camera(
                            -5000, -5000, 1000, Math.PI, -.8));
            var nearby = WorldView.class.getDeclaredMethod("loadNext");
            nearby.setAccessible(true);
            nearby.invoke(view);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static boolean sky(ImageView view) {
        var image = view.getImage();
        if (image == null) {
            return false;
        }
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        for (int y = 0; y < height; y += 31) {
            double t = (double) y / height;
            int expected =
                    0xff000000
                            | (int) (91 + 80 * t) << 16
                            | (int) (141 + 60 * t) << 8
                            | (int) (177 + 37 * t);
            for (int x = 0; x < width; x += 29) {
                if (image.getPixelReader().getArgb(x, y) != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * bgfx's name for this platform's default backend (GpuViewerMain), or null when the {@code
     * vimi.gpu.backend} / {@code VIMI_GPU_BACKEND} override chooses another.
     */
    private static String expectedBackend() {
        String override = System.getProperty("vimi.gpu.backend");
        if (override == null) {
            override = System.getenv("VIMI_GPU_BACKEND");
        }
        if (override != null && !override.isBlank() && !override.strip().equalsIgnoreCase("default")) {
            return null;
        }
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        return os.contains("win") ? "Direct3D 11" : os.contains("mac") ? "Metal" : "Vulkan";
    }

    private static boolean ready(WorldView view) {
        boolean ready =
                view.lookup("#world-frame") instanceof ImageView image
                        && image.getImage() != null
                        && view.lookup("#world-gpu-status") instanceof Label label
                        && label.getText().startsWith(GPU_READY)
                        && !view.lookup("#world-gpu-activity").isVisible();
        if (ready) {
            BACKEND.set(((Label) view.lookup("#world-gpu-status")).getText().substring(GPU_READY.length()));
        }
        return ready;
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, KeyCode code) {
        return new KeyEvent(type, "", "", code, false, false, false, false);
    }

    private static Set<Long> children() {
        return RendererChildren.live();
    }

    private static long hash(ImageView image) {
        if (image.getImage() == null) {
            return 0;
        }
        var source = image.getImage();
        long result = 1;
        for (int y = 0; y < (int) source.getHeight(); y += 11) {
            for (int x = 0; x < (int) source.getWidth(); x += 11) {
                result = 31 * result + source.getPixelReader().getArgb(x, y);
            }
        }
        return result;
    }

    private static void await(BooleanSupplier ready, Runnable action) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var timer = new javafx.animation.Timeline();
        timer.getKeyFrames()
                .add(
                        new javafx.animation.KeyFrame(
                                javafx.util.Duration.millis(20),
                                event -> {
                                    try {
                                        if (ready.getAsBoolean()) {
                                            timer.stop();
                                            action.run();
                                        } else if (System.nanoTime() > deadline) {
                                            throw new AssertionError(
                                                    "Actual World GPU stage timed out; childPids="
                                                            + children()
                                                            + " image="
                                                            + ((ImageView)
                                                                            VIEW.get()
                                                                                    .lookup(
                                                                                            "#world-frame"))
                                                                    .getImage()
                                                            + " status="
                                                            + (VIEW.get()
                                                                                    .lookup(
                                                                                            "#world-gpu-status")
                                                                            instanceof Label label
                                                                    ? label.getText()
                                                                    : "unattached"));
                                        }
                                    } catch (Throwable error) {
                                        timer.stop();
                                        DONE.completeExceptionally(error);
                                    }
                                }));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timer.play();
    }

    private static WorldFormats.Terrain terrain() {
        float[] vertices = new float[81 * 3];
        int[] indices = new int[64 * 6];
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                int at = (y * 9 + x) * 3;
                vertices[at] = 533.333f - y * 533.333f / 8;
                vertices[at + 1] = 533.333f - x * 533.333f / 8;
                vertices[at + 2] = (float) (30 + Math.sin(x * .6) * Math.cos(y * .6) * 35);
            }
        }
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int at = (y * 8 + x) * 6, a = y * 9 + x;
                int[] triangle = {a, a + 1, a + 9, a + 1, a + 10, a + 9};
                System.arraycopy(triangle, 0, indices, at, 6);
            }
        }
        return new WorldFormats.Terrain(vertices, indices, 1);
    }

    private static void snapshot(Scene scene, String suffix) {
        String directory = System.getProperty("vimi.world.gpu.artifacts");
        if (directory == null) {
            return;
        }
        try {
            Path output = Path.of(directory);
            Files.createDirectories(output);
            var image = scene.snapshot(null);
            int w = (int) image.getWidth(), h = (int) image.getHeight();
            int[] pixels = new int[w * h];
            image.getPixelReader()
                    .getPixels(
                            0,
                            0,
                            w,
                            h,
                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                            pixels,
                            0,
                            w);
            var bitmap =
                    new java.awt.image.BufferedImage(
                            w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            bitmap.setRGB(0, 0, w, h, pixels, 0, w);
            javax.imageio.ImageIO.write(
                    bitmap, "png", output.resolve("world-inline-gpu-" + suffix + ".png").toFile());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
