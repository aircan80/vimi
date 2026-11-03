package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Authored world only; verifies map selection, stale inventory suppression and actual frame
 * delivery.
 */
public final class WorldUiTest {
    private WorldUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        var closes = new AtomicInteger();
        Platform.startup(
                () ->
                        Platform.runLater(
                                () -> {
                                    var stage = new Stage();
                                    try {
                                        var continent =
                                                new WorldService.MapInfo(
                                                        0, "Authored continent", "Authored", 0, 1L);
                                        var second =
                                                new WorldService.MapInfo(
                                                        1, "Other continent", "Other", 0, 2L);
                                        var tile = new WorldFormats.Tile(32, 32, 10, 0, 0);
                                        var delayed =
                                                new CompletableFuture<WorldService.Inventory>();
                                        var oldTile =
                                                new CompletableFuture<WorldService.LoadedTile>();
                                        var neighbor =
                                                new CompletableFuture<WorldService.LoadedTile>();
                                        var tileCalls = new AtomicInteger();
                                        var neighborToken =
                                                new java.util.concurrent.atomic.AtomicReference<
                                                        Cancellation>();
                                        BuildWorld handle =
                                                new BuildWorld() {
                                                    public CompletableFuture<WorldService.Catalog>
                                                            catalog(Cancellation t) {
                                                        return CompletableFuture.completedFuture(
                                                                new WorldService.Catalog(
                                                                        List.of(continent, second),
                                                                        List.of(
                                                                                new WorldService
                                                                                        .Area(
                                                                                        100,
                                                                                        "Authored"
                                                                                            + " zone",
                                                                                        0,
                                                                                        0)),
                                                                        List.of(
                                                                                "Authored partial"
                                                                                    + " fixture")));
                                                    }

                                                    public CompletableFuture<WorldService.Inventory>
                                                            inventory(
                                                                    WorldService.MapInfo map,
                                                                    Cancellation t) {
                                                        return map.id() == 1
                                                                ? delayed
                                                                : CompletableFuture.completedFuture(
                                                                        new WorldService.Inventory(
                                                                                map,
                                                                                new WorldFormats
                                                                                        .World(
                                                                                        List.of(
                                                                                                tile,
                                                                                                new WorldFormats
                                                                                                        .Tile(
                                                                                                        33,
                                                                                                        32,
                                                                                                        11,
                                                                                                        0,
                                                                                                        0)),
                                                                                        false),
                                                                                null));
                                                    }

                                                    public CompletableFuture<
                                                                    WorldService.LoadedTile>
                                                            tile(
                                                                    WorldService.MapInfo map,
                                                                    WorldFormats.Tile location,
                                                                    Cancellation t) {
                                                        int call = tileCalls.getAndIncrement();
                                                        if (call == 0) return oldTile;
                                                        if (call == 2) {
                                                            neighborToken.set(t);
                                                            return neighbor;
                                                        }
                                                        return CompletableFuture.completedFuture(
                                                                new WorldService.LoadedTile(
                                                                        location, terrain(), null));
                                                    }

                                                    public CompletableFuture<Void> closeAsync() {
                                                        closes.incrementAndGet();
                                                        return CompletableFuture.completedFuture(
                                                                null);
                                                    }
                                                };
                                        var view = new WorldView(() -> handle, () -> 1);
                                        var scene = new Scene(view, 1320, 850);
                                        scene.getStylesheets()
                                                .add(
                                                        VimiApp.class
                                                                .getResource("/vimi.css")
                                                                .toExternalForm());
                                        stage.setScene(scene);
                                        stage.show();
                                        view.ensureLoaded();
                                        await(
                                                () ->
                                                        ((ListView<?>) view.lookup("#world-maps"))
                                                                        .getItems()
                                                                        .size()
                                                                == 2,
                                                () -> {
                                                    var maps =
                                                            (ListView<?>)
                                                                    view.lookup("#world-maps");
                                                    maps.getSelectionModel().select(1);
                                                    maps.getSelectionModel().select(0);
                                                    delayed.complete(
                                                            new WorldService.Inventory(
                                                                    second,
                                                                    new WorldFormats.World(
                                                                            List.of(), false),
                                                                    null));
                                                    await(
                                                            () ->
                                                                    !((Button)
                                                                                    view.lookup(
                                                                                            "#world-open"))
                                                                            .isDisabled(),
                                                            () -> {
                                                                ((Button)
                                                                                view.lookup(
                                                                                        "#world-open"))
                                                                        .fire();
                                                                ((Button)
                                                                                view.lookup(
                                                                                        "#world-back"))
                                                                        .fire();
                                                                ((Button)
                                                                                view.lookup(
                                                                                        "#world-open"))
                                                                        .fire();
                                                                var high = terrain().vertices();
                                                                for (int at = 2;
                                                                        at < high.length;
                                                                        at += 3) high[at] = 900;
                                                                oldTile.complete(
                                                                        new WorldService.LoadedTile(
                                                                                tile,
                                                                                new WorldFormats
                                                                                        .Terrain(
                                                                                        high,
                                                                                        terrain()
                                                                                                .triangles(),
                                                                                        1),
                                                                                null));
                                                                await(
                                                                        () ->
                                                                                ((ImageView)
                                                                                                        view
                                                                                                                .lookup(
                                                                                                                        "#world-frame"))
                                                                                                .getImage()
                                                                                        != null,
                                                                        () -> {
                                                                            require(
                                                                                    scene.lookup(
                                                                                                    "#world-viewport")
                                                                                            != null,
                                                                                    "Explorer"
                                                                                        + " viewport"
                                                                                        + " shown");
                                                                            require(
                                                                                    view
                                                                                            .lookupAll(
                                                                                                    ".label")
                                                                                            .stream()
                                                                                            .filter(
                                                                                                    javafx
                                                                                                                    .scene
                                                                                                                    .control
                                                                                                                    .Label
                                                                                                                    .class
                                                                                                            ::isInstance)
                                                                                            .map(
                                                                                                    javafx
                                                                                                                    .scene
                                                                                                                    .control
                                                                                                                    .Label
                                                                                                                    .class
                                                                                                            ::cast)
                                                                                            .noneMatch(
                                                                                                    l ->
                                                                                                            l.getText()
                                                                                                                    .contains(
                                                                                                                            "altitude"
                                                                                                                                + " 1000")),
                                                                                    "Old explorer"
                                                                                        + " session"
                                                                                        + " cannot"
                                                                                        + " reset"
                                                                                        + " the new"
                                                                                        + " camera");
                                                                            require(
                                                                                    neighborToken
                                                                                                    .get()
                                                                                            != null,
                                                                                    "Neighbor"
                                                                                        + " request"
                                                                                        + " has started");
                                                                            view.deactivate();
                                                                            require(
                                                                                    neighborToken
                                                                                            .get()
                                                                                            .isCanceled(),
                                                                                    "Leaving World"
                                                                                        + " cancels"
                                                                                        + " background"
                                                                                        + " tile"
                                                                                        + " acquisition");
                                                                            neighbor.complete(
                                                                                    new WorldService
                                                                                            .LoadedTile(
                                                                                            new WorldFormats
                                                                                                    .Tile(
                                                                                                    33,
                                                                                                    32,
                                                                                                    11,
                                                                                                    0,
                                                                                                    0),
                                                                                            terrain(),
                                                                                            null));
                                                                            view.activate();
                                                                            snapshot(scene);
                                                                            view.close();
                                                                            require(
                                                                                    closes.get()
                                                                                            == 0,
                                                                                    "View borrows"
                                                                                        + " the workspace"
                                                                                        + " handle");
                                                                            stage.close();
                                                                            done.complete(null);
                                                                            Platform.exit();
                                                                        },
                                                                        done);
                                                            },
                                                            done);
                                                },
                                                done);
                                    } catch (Throwable failure) {
                                        stage.close();
                                        done.completeExceptionally(failure);
                                        Platform.exit();
                                    }
                                }));
        try {
            done.get(30, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
        System.out.println(
                "World UI: continent drill-down, stale inventory, terrain frame and borrowed"
                    + " lifetime passed");
    }

    private static void await(
            BooleanSupplier ready, Runnable action, CompletableFuture<Void> done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var timer = new javafx.animation.Timeline();
        timer.getKeyFrames()
                .add(
                        new javafx.animation.KeyFrame(
                                javafx.util.Duration.millis(20),
                                e -> {
                                    try {
                                        if (ready.getAsBoolean()) {
                                            timer.stop();
                                            action.run();
                                        } else if (System.nanoTime() > deadline) {
                                            throw new AssertionError(
                                                    "World UI did not become ready");
                                        }
                                    } catch (Throwable failure) {
                                        timer.stop();
                                        done.completeExceptionally(failure);
                                        Platform.exit();
                                    }
                                }));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timer.play();
    }

    private static WorldFormats.Terrain terrain() {
        float[] vertices = new float[9 * 9 * 3];
        int[] indices = new int[8 * 8 * 6];
        for (int y = 0; y < 9; y++)
            for (int x = 0; x < 9; x++) {
                int at = (y * 9 + x) * 3;
                vertices[at] = (float) (533.333 - y * 533.333 / 8);
                vertices[at + 1] = (float) (533.333 - x * 533.333 / 8);
                vertices[at + 2] = (float) (30 + Math.sin(x * .6) * Math.cos(y * .6) * 35);
            }
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int at = (y * 8 + x) * 6, a = y * 9 + x;
                int[] triangle = {a, a + 1, a + 9, a + 1, a + 10, a + 9};
                System.arraycopy(triangle, 0, indices, at, 6);
            }
        return new WorldFormats.Terrain(vertices, indices, 1);
    }

    private static void snapshot(Scene scene) {
        String destination = System.getProperty("vimi.world.screenshot");
        if (destination == null) return;
        try {
            var image = scene.snapshot(null);
            int[] pixels = new int[(int) image.getWidth() * (int) image.getHeight()];
            image.getPixelReader()
                    .getPixels(
                            0,
                            0,
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                            pixels,
                            0,
                            (int) image.getWidth());
            var bitmap =
                    new java.awt.image.BufferedImage(
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
            bitmap.setRGB(
                    0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());
            javax.imageio.ImageIO.write(bitmap, "png", Path.of(destination).toFile());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
