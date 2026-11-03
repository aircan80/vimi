package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** North-up recorded artwork; one active batch and one replaceable pending view. FX-owned. */
final class WorldMinimap extends VBox implements AutoCloseable {
    static final double SIZE = 180, CENTER = SIZE / 2, RADIUS = 86;
    private final Canvas canvas = new Canvas(SIZE, SIZE);
    private final Label message = new Label("Minimap artwork unavailable");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final LinkedHashMap<Long, Artwork> cache = new LinkedHashMap<>(16, .75f, true);
    private final ThreadPoolExecutor worker = executor("world-minimap-requests");
    private final ThreadPoolExecutor cancellation = executor("world-minimap-cancel");
    private View view;
    private Job active;
    private long generation;
    private boolean closed;

    private record View(
            BuildWorld source,
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            double originX,
            double originY,
            double x,
            double y,
            double yaw,
            List<WorldFormats.Tile> tiles) {}

    private record Artwork(Image image, String issue) {}

    private record Loaded(long id, WorldService.Minimap image) {}

    private static final class Job {
        final long generation;
        final Cancellation token = new Cancellation();
        final View view;
        final List<WorldFormats.Tile> tiles;
        boolean cancelRequested;

        Job(long generation, View view, List<WorldFormats.Tile> tiles) {
            this.generation = generation;
            this.view = view;
            this.tiles = List.copyOf(tiles);
        }
    }

    WorldMinimap() {
        // An obsolete queued cancellation belongs to an already completed older batch. Keep only
        // the latest retirement if a custom provider stalls inside its cancellation callback.
        cancellation.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        setId("world-minimap");
        setAlignment(Pos.TOP_CENTER);
        setSpacing(3);
        setMinSize(190, 210);
        setPrefSize(190, 210);
        setMaxSize(190, 210);
        setMouseTransparent(true);
        setStyle(
                "-fx-background-color: rgba(14,24,31,0.85); -fx-background-radius: 12; -fx-padding:"
                        + " 5;");
        canvas.setId("world-minimap-canvas");
        canvas.setAccessibleText("North-up minimap; player centered");
        activity.setId("world-minimap-activity");
        activity.setPrefSize(14, 14);
        activity.setMaxSize(14, 14);
        activity.setAccessibleText("Loading recorded minimap artwork");
        message.setId("world-minimap-message");
        message.setWrapText(true);
        message.setMaxWidth(156);
        message.setStyle("-fx-text-fill: white; -fx-font-size: 10px;");
        var state = new HBox(5, activity, message);
        state.setAlignment(Pos.CENTER);
        getChildren().addAll(canvas, state);
        hide();
    }

    private static ThreadPoolExecutor executor(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void update(
            BuildWorld source,
            WorldService.Inventory inventory,
            WorldFormats.Tile origin,
            double originX,
            double originY,
            double x,
            double y,
            double yaw) {
        if (closed) return;
        if (source == null
                || inventory == null
                || inventory.world() == null
                || origin == null
                || !Double.isFinite(originX)
                || !Double.isFinite(originY)
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(yaw)) {
            hide();
            return;
        }
        if (view == null || view.source() != source || view.inventory() != inventory) {
            generation++;
            cancelActive();
            cache.clear();
        }
        var nearby = new ArrayList<WorldFormats.Tile>(9);
        for (var tile : inventory.world().tiles()) {
            double left = screenY(maxY(tile, origin, originY), y);
            double top = screenX(maxX(tile, origin, originX), x);
            double side = 2 * RADIUS;
            if (left < CENTER + RADIUS
                    && left + side > CENTER - RADIUS
                    && top < CENTER + RADIUS
                    && top + side > CENTER - RADIUS) {
                if (nearby.size() == 9) break;
                nearby.add(tile);
            }
        }
        view =
                new View(
                        source,
                        inventory,
                        origin,
                        originX,
                        originY,
                        x,
                        y,
                        yaw,
                        List.copyOf(nearby));
        setVisible(true);
        setManaged(true);
        redraw();
        startMissing();
    }

    static double maxX(WorldFormats.Tile tile, WorldFormats.Tile origin, double originX) {
        return originX - (tile.x() - origin.x()) * WorldFormats.TILE_SIZE;
    }

    static double maxY(WorldFormats.Tile tile, WorldFormats.Tile origin, double originY) {
        return originY - (tile.y() - origin.y()) * WorldFormats.TILE_SIZE;
    }

    static double screenX(double nativeX, double playerX) {
        return CENTER - (nativeX - playerX) * (2 * RADIUS / WorldFormats.TILE_SIZE);
    }

    static double screenY(double nativeY, double playerY) {
        return CENTER - (nativeY - playerY) * (2 * RADIUS / WorldFormats.TILE_SIZE);
    }

    private void startMissing() {
        if (closed || active != null || view == null) return;
        var missing = new ArrayList<WorldFormats.Tile>();
        for (var tile : view.tiles()) {
            if (tile.minimapId() > 0
                    && !cache.containsKey(tile.minimapId())
                    && missing.stream().noneMatch(t -> t.minimapId() == tile.minimapId())) {
                missing.add(tile);
            }
        }
        if (missing.isEmpty()) {
            redraw();
            return;
        }
        var job = new Job(generation, view, missing);
        active = job;
        redraw();
        worker.execute(
                () -> {
                    var results = new ArrayList<Loaded>(job.tiles.size());
                    try {
                        for (var tile : job.tiles) {
                            job.token.check();
                            WorldService.Minimap result;
                            try {
                                result = job.view.source().minimap(tile, job.token).join();
                                if (result == null)
                                    result =
                                            WorldService.Minimap.unavailable(
                                                    "Minimap artwork unavailable");
                            } catch (RuntimeException failure) {
                                job.token.check();
                                result =
                                        WorldService.Minimap.unavailable(
                                                "Minimap artwork unavailable");
                            }
                            job.token.check();
                            results.add(new Loaded(tile.minimapId(), result));
                        }
                    } catch (java.util.concurrent.CancellationException ignored) {
                        // A retired provider may finish later; only the current generation can
                        // publish.
                    }
                    Platform.runLater(() -> completed(job, results));
                });
    }

    private void completed(Job job, List<Loaded> results) {
        if (active != job) return;
        active = null;
        if (!closed
                && view != null
                && job.generation == generation
                && job.view.source() == view.source()
                && job.view.inventory() == view.inventory()) {
            for (var result : results) {
                var data = result.image();
                Image image = null;
                if (data.pixels() != null) {
                    var writable = new WritableImage(data.width(), data.height());
                    writable.getPixelWriter()
                            .setPixels(
                                    0,
                                    0,
                                    data.width(),
                                    data.height(),
                                    PixelFormat.getIntArgbInstance(),
                                    data.pixels(),
                                    data.width());
                    image = writable;
                }
                if (!cache.containsKey(result.id()) && cache.size() == 9) {
                    cache.remove(cache.keySet().iterator().next());
                }
                cache.put(result.id(), new Artwork(image, data.issue()));
            }
            redraw();
        }
        startMissing();
    }

    private void redraw() {
        var g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        g.clearRect(0, 0, SIZE, SIZE);
        g.save();
        g.beginPath();
        g.arc(CENTER, CENTER, RADIUS, RADIUS, 0, 360);
        g.closePath();
        g.clip();
        g.setFill(Color.web("#223038"));
        g.fillRect(0, 0, SIZE, SIZE);
        int readable = 0, missing = 0;
        if (view != null) {
            for (var tile : view.tiles()) {
                var artwork = cache.get(tile.minimapId());
                if (artwork == null || artwork.image() == null) {
                    missing++;
                    continue;
                }
                readable++;
                double left = screenY(maxY(tile, view.origin(), view.originY()), view.y());
                double top = screenX(maxX(tile, view.origin(), view.originX()), view.x());
                g.drawImage(artwork.image(), left, top, 2 * RADIUS, 2 * RADIUS);
            }
        }
        g.restore();
        g.setStroke(Color.web("#a6b9b7"));
        g.setLineWidth(2);
        g.strokeOval(CENTER - RADIUS, CENTER - RADIUS, 2 * RADIUS, 2 * RADIUS);
        g.setFill(Color.WHITE);
        g.fillText("N", CENTER - 4, 16);
        double yaw = view == null ? 0 : view.yaw();
        double dx = -Math.sin(yaw), dy = -Math.cos(yaw);
        g.setFill(Color.web("#ffd66b"));
        g.setStroke(Color.web("#172129"));
        g.setLineWidth(2);
        double[] xs = {CENTER + dx * 14, CENTER - dx * 8 - dy * 6, CENTER - dx * 8 + dy * 6};
        double[] ys = {CENTER + dy * 14, CENTER - dy * 8 + dx * 6, CENTER - dy * 8 - dx * 6};
        g.fillPolygon(xs, ys, 3);
        g.strokePolygon(xs, ys, 3);
        g.setFill(Color.WHITE);
        g.fillOval(CENTER - 2, CENTER - 2, 4, 4);
        boolean loading =
                view != null
                        && view.tiles().stream()
                                .anyMatch(
                                        tile ->
                                                tile.minimapId() > 0
                                                        && !cache.containsKey(tile.minimapId()));
        activity.setVisible(loading);
        activity.setManaged(loading);
        message.setText(
                loading
                        ? "Loading minimap artwork…"
                        : readable == 0
                                ? "Minimap artwork unavailable"
                                : missing > 0 ? "North up · partial artwork" : "North up");
        setAccessibleText("North-up minimap. " + message.getText());
    }

    void hide() {
        generation++;
        view = null;
        cache.clear();
        cancelActive();
        activity.setVisible(false);
        activity.setManaged(false);
        setVisible(false);
        setManaged(false);
    }

    private void cancelActive() {
        if (active == null || active.cancelRequested) return;
        active.cancelRequested = true;
        var token = active.token;
        cancellation.execute(token::cancel);
    }

    @Override
    public void close() {
        if (closed) return;
        hide();
        closed = true;
        worker.shutdown();
        cancellation.shutdown();
    }
}
