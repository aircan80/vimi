package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Detached authored pixels only; no real source files, windows or user settings. */
public final class WorldMinimapUiTest {
    private WorldMinimapUiTest() {}

    private static final WorldFormats.Tile ORIGIN = new WorldFormats.Tile(20, 30, 0, 0, 91);
    private static final double SIDE = WorldFormats.TILE_SIZE;
    private static final double X = 1000, Y = 2000;
    private static Stage stage;
    private static WorldMinimap map;

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.complete(null);
                });
        started.get(10, TimeUnit.SECONDS);
        try {
            fx(
                    () -> {
                        map = new WorldMinimap();
                        stage = new Stage();
                        stage.setScene(new Scene(map, 200, 230));
                        stage.show();
                    });
            orientationAndNeighborPlacement();
            cameraForwardCardinalLandmarks();
            delayedLatestSourceAndHide();
            synchronousProviderCancellation();
            fx(
                    () -> {
                        map.close();
                        stage.close();
                    });
            System.out.println(
                    "WorldMinimapUiTest PASS: axes, artwork, facing, bounded"
                            + " latest/source/hide/close/cancel");
        } finally {
            Platform.exit();
        }
    }

    private static void cameraForwardCardinalLandmarks() throws Exception {
        // Authored cardinal crosses, tied to native X/Y rather than a copied yaw formula.
        int[] argb = new int[256 * 256];
        java.util.Arrays.fill(argb, 0xff223038);
        int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff};
        int[][] centers = {{128, 64}, {64, 128}, {128, 192}, {192, 128}};
        for (int i = 0; i < centers.length; i++) {
            for (int dy = -8; dy <= 8; dy++) {
                for (int dx = -8; dx <= 8; dx++) {
                    argb[(centers[i][1] + dy) * 256 + centers[i][0] + dx] = colors[i];
                }
            }
        }
        var source = new Provider();
        source.immediate = new WorldService.Minimap(256, 256, IntBuffer.wrap(argb), null);
        var inventory = inventory(List.of(ORIGIN));
        fx(() -> { map.hide(); update(source, inventory, X - SIDE / 2, Y - SIDE / 2, 0); });
        await(() -> !busy(), "cardinal landmark artwork");
        fx(() -> {
            var orientation = (ChoiceBox<?>) map.lookup("#world-minimap-orientation");
            orientation.getSelectionModel().select(1);
            var origin = new com.vibeminer.rendering.world.TerrainCamera(
                    X - SIDE / 2, Y - SIDE / 2, 0, 0, 0);
            double[] headings = {0, Math.PI / 2, Math.PI, -Math.PI / 2};
            String[] names = {"north", "west", "south", "east"};
            for (int i = 0; i < headings.length; i++) {
                var camera = origin.look(headings[i], 0);
                var ahead = camera.move(SIDE / 4, 0, 0);
                int sourceU = (int) Math.round((Y - ahead.y()) / SIDE * 256);
                int sourceV = (int) Math.round((X - ahead.x()) / SIDE * 256);
                require(argb[sourceV * 256 + sourceU] == colors[i], "actual camera reaches " + names[i]);
                update(source, inventory, camera.x(), camera.y(), camera.yaw());
                require(snapshot().getPixelReader().getArgb(90, 50) == colors[i],
                        "actual camera forward landmark stays ahead: " + names[i]);
                saveScreenshot("camera-forward-" + names[i] + ".png");
            }
            orientation.getSelectionModel().select(0);
        });
    }

    private static void orientationAndNeighborPlacement() throws Exception {
        var source = new Provider();
        source.immediate = image(0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff);
        var inventory = inventory(List.of(ORIGIN));
        fx(() -> update(source, inventory, X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> !busy(), "authored image completion");
        fx(
                () -> {
                    var pixels = snapshot().getPixelReader();
                    require(pixels.getArgb(55, 55) == 0xffff0000, "top-left red");
                    require(pixels.getArgb(125, 55) == 0xff00ff00, "top-right green");
                    require(pixels.getArgb(55, 125) == 0xff0000ff, "bottom-left blue");
                    require(pixels.getArgb(125, 125) == 0xffffffff, "bottom-right white");
                    require(pixels.getArgb(90, 85) == 0xffffd66b, "yaw zero arrow north");
                    update(source, inventory, X - SIDE / 2, Y - SIDE / 2, Math.PI / 2);
                    require(
                            snapshot().getPixelReader().getArgb(85, 90) == 0xffffd66b,
                            "yaw pi/2 arrow west");
                    saveScreenshot("facing-west.png");
                    var orientation = (ChoiceBox<?>) map.lookup("#world-minimap-orientation");
                    require(!map.isMouseTransparent() && !orientation.isDisabled(),
                            "Orientation control accepts user input");
                    orientation.getSelectionModel().select(1);
                    pixels = snapshot().getPixelReader();
                    require(pixels.getArgb(125, 55) == 0xffff0000, "heading west rotates northwest red to northeast");
                    require(pixels.getArgb(125, 125) == 0xff00ff00, "heading west rotates northeast green to southeast");
                    require(pixels.getArgb(55, 55) == 0xff0000ff, "heading west rotates southwest blue to northwest");
                    require(pixels.getArgb(55, 125) == 0xffffffff, "heading west rotates southeast white to southwest");
                    require(pixels.getArgb(90, 85) == 0xffffd66b, "heading-up arrow stays north on screen");
                    require(source.calls.get() == 1, "Orientation change reuses artwork without source reads");
                    update(source, inventory, X - SIDE / 2, Y - SIDE / 2, Math.PI);
                    pixels = snapshot().getPixelReader();
                    require(pixels.getArgb(125, 125) == 0xffff0000, "heading south rotates artwork halfway");
                    require(pixels.getArgb(90, 85) == 0xffffd66b, "heading south arrow still points up");
                    orientation.getSelectionModel().select(0);
                    require(snapshot().getPixelReader().getArgb(55, 55) == 0xffff0000,
                            "North-up toggle restores original orientation");
                    require(
                            WorldMinimap.maxX(new WorldFormats.Tile(21, 30, 0, 0, 1), ORIGIN, X)
                                    == X - SIDE,
                            "filename X tracks native X");
                    require(
                            WorldMinimap.maxY(new WorldFormats.Tile(20, 31, 0, 0, 1), ORIGIN, Y)
                                    == Y - SIDE,
                            "filename Y tracks native Y");
                });
        var neighbors = new ArrayList<WorldFormats.Tile>();
        for (int x = 19; x <= 21; x++) {
            for (int y = 29; y <= 31; y++) {
                neighbors.add(new WorldFormats.Tile(x, y, 0, 0, 100 + (x - 19) * 3 + y - 29));
            }
        }
        var solid =
                new Provider() {
                    @Override
                    public CompletableFuture<WorldService.Minimap> minimap(
                            WorldFormats.Tile tile, Cancellation token) {
                        require(
                                !Platform.isFxApplicationThread(),
                                "Provider invocation stays off FX");
                        calls.incrementAndGet();
                        int color =
                                switch ((int) tile.minimapId()) {
                                    case 100 -> 0xffffff00;
                                    case 101 -> 0xff0000ff;
                                    case 103 -> 0xff00ff00;
                                    case 104 -> 0xffff0000;
                                    default ->
                                            throw new AssertionError(
                                                    "Unexpected neighboring tile " + tile.key());
                                };
                        return CompletableFuture.completedFuture(image(color, color, color, color));
                    }
                };
        var grid = inventory(neighbors);
        fx(() -> update(solid, grid, X - .1 * SIDE, Y - .1 * SIDE, 0));
        await(() -> !busy(), "neighbor artwork completion");
        require(solid.calls.get() == 4, "only four intersecting recorded tiles near corner");
        fx(
                () -> {
                    var p = snapshot().getPixelReader();
                    require(p.getArgb(55, 55) == 0xffffff00, "northwest diagonal artwork");
                    require(p.getArgb(125, 55) == 0xff0000ff, "filename X-1 is north");
                    require(p.getArgb(55, 125) == 0xff00ff00, "filename Y-1 is west");
                    require(p.getArgb(125, 125) == 0xffff0000, "southeast current artwork");
                    saveScreenshot("north-up-neighbors.png");
                });
    }

    private static void delayedLatestSourceAndHide() throws Exception {
        var old = new Provider();
        var first = inventory(List.of(ORIGIN));
        fx(() -> update(old, first, X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> old.calls.get() == 1 && busy(), "pending image activity");
        var next = new Provider();
        next.immediate = image(0xff113355, 0xff113355, 0xff113355, 0xff113355);
        var second = inventory(List.of(new WorldFormats.Tile(20, 30, 0, 0, 92)));
        fx(
                () -> {
                    for (int i = 0; i < 500; i++)
                        update(next, second, X - SIDE / 2, Y - SIDE / 2, i * .001);
                    require(busy(), "waiting newer source remains visibly pending");
                });
        require(next.calls.get() == 0, "no unbounded provider work behind old active read");
        old.gate.complete(image(0xffff0000, 0xffff0000, 0xffff0000, 0xffff0000));
        await(
                () -> next.calls.get() == 1 && !busy(),
                "latest source admitted after old retirement");
        fx(
                () ->
                        require(
                                snapshot().getPixelReader().getArgb(55, 55) == 0xff113355,
                                "old source cannot overwrite"));
        var hidden = new Provider();
        fx(() -> update(hidden, first, X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> hidden.calls.get() == 1, "hidden pending invocation");
        fx(map::hide);
        hidden.gate.complete(image(0xffff0000, 0xffff0000, 0xffff0000, 0xffff0000));
        await(() -> !map.isVisible() && !busy(), "hidden completion stays retired");
        fx(() -> update(next, second, X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> map.isVisible() && !busy(), "hide permits subsequent resume");
        fx(
                () -> {
                    var unavailable = new Provider();
                    unavailable.immediate = WorldService.Minimap.unavailable("No recorded artwork");
                    update(unavailable, inventory(List.of(ORIGIN)), X - SIDE / 2, Y - SIDE / 2, 0);
                });
        await(
                () ->
                        !busy()
                                && ((Label) map.lookup("#world-minimap-message"))
                                        .getText()
                                        .contains("unavailable"),
                "unavailable fallback");
    }

    private static void synchronousProviderCancellation() throws Exception {
        var canceled = new CompletableFuture<Void>();
        var source =
                new Provider() {
                    @SuppressWarnings("try")
                    @Override
                    public CompletableFuture<WorldService.Minimap> minimap(
                            WorldFormats.Tile tile, Cancellation token) {
                        require(
                                !Platform.isFxApplicationThread(),
                                "Provider invocation stays off FX");
                        calls.incrementAndGet();
                        try (var registration = token.onCancel(() -> canceled.complete(null))) {
                            canceled.join();
                            token.check();
                            return gate;
                        }
                    }
                };
        fx(() -> update(source, inventory(List.of(ORIGIN)), X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> source.calls.get() == 1, "synchronously blocked source entered");
        fx(map::hide);
        canceled.get(5, TimeUnit.SECONDS);
        var late = new Provider();
        fx(() -> update(late, inventory(List.of(ORIGIN)), X - SIDE / 2, Y - SIDE / 2, 0));
        await(() -> late.calls.get() == 1, "canceled source released worker");
        fx(map::close);
        late.gate.complete(image(0xffff0000, 0xffff0000, 0xffff0000, 0xffff0000));
        fx(
                () -> {
                    update(late, inventory(List.of(ORIGIN)), X - SIDE / 2, Y - SIDE / 2, 0);
                    require(
                            !map.isVisible() && !busy(),
                            "close rejects late completion and resume");
                });
    }

    private static void update(
            BuildWorld source, WorldService.Inventory inventory, double x, double y, double yaw) {
        map.update(source, inventory, ORIGIN, X, Y, x, y, yaw);
    }

    private static WorldService.Inventory inventory(List<WorldFormats.Tile> tiles) {
        return new WorldService.Inventory(
                new WorldService.MapInfo(0, "Authored", "Authored", 0, null),
                new WorldFormats.World(tiles, false),
                null);
    }

    private static WorldService.Minimap image(int a, int b, int c, int d) {
        return new WorldService.Minimap(2, 2, IntBuffer.wrap(new int[] {a, b, c, d}), null);
    }

    private static boolean busy() {
        return ((ProgressIndicator) map.lookup("#world-minimap-activity")).isVisible();
    }

    private static WritableImage snapshot() {
        return ((Canvas) map.lookup("#world-minimap-canvas")).snapshot(null, null);
    }

    private static void saveScreenshot(String name) {
        try {
            var image = map.snapshot(null, null);
            int[] argb = new int[(int) image.getWidth() * (int) image.getHeight()];
            image.getPixelReader()
                    .getPixels(
                            0,
                            0,
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                            argb,
                            0,
                            (int) image.getWidth());
            var png =
                    new java.awt.image.BufferedImage(
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, png.getWidth(), png.getHeight(), argb, 0, png.getWidth());
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "vimi-minimap-authored"));
            javax.imageio.ImageIO.write(
                    png, "png", Path.of(System.getProperty("java.io.tmpdir"), "vimi-minimap-authored").resolve(name).toFile());
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var result = new CompletableFuture<Boolean>();
            Platform.runLater(
                    () -> {
                        try {
                            result.complete(condition.getAsBoolean());
                        } catch (Throwable failure) {
                            result.completeExceptionally(failure);
                        }
                    });
            if (result.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static void fx(Runnable action) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        result.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static class Provider implements BuildWorld {
        final AtomicInteger calls = new AtomicInteger();
        final CompletableFuture<WorldService.Minimap> gate = new CompletableFuture<>();
        WorldService.Minimap immediate;

        public CompletableFuture<WorldService.Minimap> minimap(
                WorldFormats.Tile tile, Cancellation token) {
            require(!Platform.isFxApplicationThread(), "Provider invocation stays off FX");
            calls.incrementAndGet();
            return immediate == null ? gate : CompletableFuture.completedFuture(immediate);
        }

        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            throw new AssertionError();
        }

        public CompletableFuture<WorldService.Inventory> inventory(
                WorldService.MapInfo map, Cancellation token) {
            throw new AssertionError();
        }

        public CompletableFuture<WorldService.LoadedTile> tile(
                WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
            throw new AssertionError();
        }

        public CompletableFuture<Void> closeAsync() {
            throw new AssertionError("Borrowed source must not close");
        }
    }
}
