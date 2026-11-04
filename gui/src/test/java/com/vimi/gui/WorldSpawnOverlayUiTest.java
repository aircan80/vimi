package com.vimi.gui;

import static com.vimi.gui.WorldSpawnOverlayTest.index;
import static com.vimi.gui.WorldSpawnOverlayTest.require;
import static com.vimi.gui.WorldSpawnOverlayTest.spawn;

import com.vibeminer.concepts.locations.WorldSpawns;
import com.vibeminer.rendering.world.TerrainRenderer;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Authored async lifecycle: off-FX indexing, stale revisions, hidden views and close. */
public final class WorldSpawnOverlayUiTest {
    private WorldSpawnOverlayUiTest() {}

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run() throws Exception {
        var calls = new AtomicInteger();
        var gates = List.of(new CompletableFuture<WorldSpawns.Index>(), new CompletableFuture<WorldSpawns.Index>(),
                new CompletableFuture<WorldSpawns.Index>());
        var authored = index(List.of(
                spawn(1, 1, 60, 0, 0.0), spawn(1, 2, 80, -12, 0.0), spawn(1, 3, 70, 8, null)));
        var revision = new AtomicReference<Object>(1L);
        var view = new AtomicReference<WorldSpawnOverlay.View>(
                new WorldSpawnOverlay.View(new TerrainRenderer.Camera(0, 0, 4, 0, -.05), 0, 800, 500));
        var started = new CountDownLatch(1);
        Platform.startup(started::countDown);
        started.await();
        var overlay = new AtomicReference<WorldSpawnOverlay>();
        var stage = new AtomicReference<Stage>();
        fx(() -> {
            Platform.setImplicitExit(false);
            var created = new WorldSpawnOverlay(token -> {
                require(!Platform.isFxApplicationThread(), "Spawn indexing runs off FX");
                return gates.get(calls.getAndIncrement()).get(10, TimeUnit.SECONDS);
            }, revision::get);
            created.bind(view::get);
            overlay.set(created);
            var frame = new Rectangle(800, 500, Color.rgb(64, 92, 72));
            var viewport = new StackPane(frame, created);
            viewport.setPrefSize(800, 500);
            var window = new Stage();
            window.setScene(new Scene(new VBox(viewport, created.controls()), 800, 540));
            window.show();
            stage.set(window);
            require(created.status().getText().contains("off"), "Markers start off");
            created.toggle().setSelected(true);
        });
        await(() -> calls.get() == 1, "Enabling starts one index load");
        fx(() -> require(overlay.get().status().getText().contains("Indexing"), "Loading is visible"));
        // A source revision change before the first load completes retires it.
        fx(() -> revision.set(2L));
        Thread.sleep(200);
        require(calls.get() == 1, "A newer revision waits for the running load instead of stacking work");
        fx(() -> require(overlay.get().status().getText().contains("Indexing"), "Reload stays visible"));
        gates.get(0).complete(index(List.of(spawn(1, 9, 50, 0, 0.0))));
        await(() -> calls.get() == 2, "Replacement load starts after the retired load drains");
        fx(() -> require(overlay.get().markers().isEmpty(), "Retired revision never publishes markers"));
        gates.get(1).complete(authored);
        await(() -> fxValue(() -> overlay.get().markers().size() == 2), "Recorded-height points are placed");
        fx(() -> {
            var markers = overlay.get().markers();
            var expected = WorldSpawnOverlay.project(authored.near(0, 0, 0, 300, 256).placed(),
                    view.get().camera(), 800, 500, 800, 500);
            require(markers.equals(expected), "Markers use the presented camera and frame fit");
            String status = overlay.get().status().getText();
            require(status.contains("2 in view of 2 placed") && status.contains("1 without recorded height"), status);
            var top = markers.getLast();
            var picked = overlay.get().pick(top.x(), top.headY()).orElseThrow();
            require(picked.spawn().equals(top.spawn()) && picked.npc() != null
                    && "Authored guard".equals(picked.npc().name()), "Clicking a drawn marker picks its record");
            require(overlay.get().pick(5, 5).isEmpty(), "Clicking away from markers picks none");
        });
        snapshot(stage.get());
        fx(() -> view.set(null));
        await(() -> fxValue(() -> overlay.get().markers().isEmpty()), "Hidden explorer clears markers");
        fx(() -> view.set(new WorldSpawnOverlay.View(new TerrainRenderer.Camera(0, 0, 4, Math.PI, 0), 0, 800, 500)));
        await(() -> fxValue(() -> overlay.get().markers().isEmpty()
                && overlay.get().status().getText().contains("0 in view of 2 placed")), "Points behind the camera are culled");
        fx(() -> overlay.get().suspend());
        fx(() -> view.set(new WorldSpawnOverlay.View(new TerrainRenderer.Camera(0, 0, 4, 0, -.05), 0, 800, 500)));
        Thread.sleep(150);
        fx(() -> require(overlay.get().markers().isEmpty(), "Suspended overlay does not poll"));
        fx(() -> overlay.get().resume());
        await(() -> fxValue(() -> overlay.get().markers().size() == 2), "Resumed overlay redraws");
        fx(() -> overlay.get().toggle().setSelected(false));
        fx(() -> require(overlay.get().status().getText().contains("off"), "Disabling clears markers"));
        fx(() -> require(overlay.get().pick(400, 250).isEmpty(), "Hidden markers are never picked"));
        fx(() -> {
            overlay.get().toggle().setSelected(true);
            revision.set(3L);
        });
        await(() -> calls.get() == 3, "New revision reloads");
        var before = new AtomicReference<String>();
        fx(() -> {
            before.set(overlay.get().status().getText());
            overlay.get().close();
        });
        gates.get(2).complete(authored);
        Thread.sleep(200);
        fx(() -> {
            require(overlay.get().markers().isEmpty(), "Closed overlay never publishes");
            require(overlay.get().status().getText().equals(before.get()), "Closed overlay keeps its status");
            stage.get().close();
        });
        System.out.println("World spawn overlay UI: off-FX load, stale revision, culling and close passed");
        Platform.exit();
    }

    private static void snapshot(Stage stage) throws Exception {
        String destination = System.getProperty("vimi.worldSpawns.screenshot");
        if (destination == null) return;
        var image = new AtomicReference<javafx.scene.image.WritableImage>();
        fx(() -> image.set(stage.getScene().snapshot(null)));
        var fx = image.get();
        int width = (int) fx.getWidth(), height = (int) fx.getHeight();
        int[] pixels = new int[width * height];
        fx.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, width);
        var bitmap = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bitmap.setRGB(0, 0, width, height, pixels, 0, width);
        javax.imageio.ImageIO.write(bitmap, "png", new java.io.File(destination));
    }

    private static boolean fxValue(BooleanSupplier value) {
        var result = new AtomicReference<Boolean>();
        try {
            fx(() -> result.set(value.getAsBoolean()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return result.get();
    }

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError(message);
    }
}
