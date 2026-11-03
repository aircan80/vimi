package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Selected-build map inventory, terrain drill-down and bounded free-fly explorer. */
final class WorldView extends BorderPane implements AutoCloseable {
    private final java.util.function.Supplier<BuildWorld> handles;
    private final java.util.function.LongSupplier revisions;
    private final ListView<WorldService.MapInfo> maps = new ListView<>();
    private final ListView<WorldService.Area> zones = new ListView<>();
    private final TextField search = new TextField();
    private final CheckBox allMaps = new CheckBox("Include instances and other maps");
    private final Label heading = new Label("World"),
            status = new Label("Choose a map"),
            position = new Label();
    private final Canvas tiles = new Canvas(512, 512);
    private final BorderPane content = new BorderPane();
    private final ImageView image = new ImageView();
    private final StackPane viewport = new StackPane(image);
    private final Button open = new Button("Open explorer"), back = new Button("Back to map");
    private final Set<KeyCode> keys = new HashSet<>();
    private final LinkedHashMap<String, WorldService.LoadedTile> loaded = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private Cancellation token = new Cancellation();
    private BuildWorld handle;
    private WorldService.Catalog catalog;
    private WorldService.Inventory inventory;
    private WorldFormats.Tile selected, origin;
    private TerrainRenderer.Camera camera;
    private TerrainRenderer.Scene terrainScene;
    private double originX, originY, mouseX, mouseY, speed = 80;
    private boolean explorer, loadingTile, catalogLoading;
    private volatile boolean closed;
    private boolean active = true;
    private long revision = -1, selection, lastTick, lastRender;
    private final AnimationTimer timer;
    private final AtomicLong frameGeneration = new AtomicLong();
    private final AtomicReference<Render> latest = new AtomicReference<>();
    private final AtomicBoolean rendering = new AtomicBoolean();

    private record Render(
            long generation,
            TerrainRenderer.Scene scene,
            TerrainRenderer.Camera camera,
            int width,
            int height) {}

    WorldView(BuildPreparation.Job job) {
        this(
                () -> job.work() instanceof NativePreparationWork w ? w.worldHandle() : null,
                () -> job.work() instanceof NativePreparationWork w ? w.revision() : 0);
    }

    WorldView(
            java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions) {
        this.handles = handles;
        this.revisions = revisions;
        setPadding(new javafx.geometry.Insets(16));
        search.setPromptText("Find a continent or map");
        search.setAccessibleText("Search world maps");
        maps.setId("world-maps");
        viewport.setId("world-viewport");
        image.setId("world-frame");
        open.setId("world-open");
        back.setId("world-back");
        maps.setAccessibleText("World maps and continents");
        maps.setPlaceholder(new Label("No matching maps"));
        maps.setPrefWidth(240);
        var reload = new Button("Reload maps");
        reload.setOnAction(
                e -> {
                    invalidate();
                    ensureLoaded();
                });
        var sidebar = new VBox(10, new Label("MAPS & CONTINENTS"), search, allMaps, reload, maps);
        VBox.setVgrow(maps, Priority.ALWAYS);
        heading.getStyleClass().add("h2");
        status.setWrapText(true);
        position.setWrapText(true);
        zones.setPrefHeight(180);
        zones.setAccessibleText("Areas in selected map");
        zones.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            if (b != null)
                                status.setText(
                                        b
                                                + " · Area #"
                                                + b.id()
                                                + " · Choose a terrain tile below to explore");
                        });
        maps.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            if (b != null) selectMap(b);
                        });
        search.textProperty().addListener((o, a, b) -> filter());
        allMaps.setOnAction(e -> filter());
        open.setDisable(true);
        open.setOnAction(e -> openExplorer());
        back.setOnAction(
                e -> {
                    cancelRequests();
                    showInventory();
                });
        var split = new SplitPane(sidebar, content);
        split.setDividerPositions(.23);
        setCenter(split);
        tiles.setOnMouseClicked(
                e -> {
                    if (inventory == null || inventory.world() == null) return;
                    int x = (int) (e.getX() / 8), y = (int) (e.getY() / 8);
                    selected =
                            inventory.world().tiles().stream()
                                    .filter(t -> t.x() == x && t.y() == y)
                                    .findFirst()
                                    .orElse(null);
                    drawTiles();
                    open.setDisable(selected == null);
                    if (selected != null)
                        status.setText(
                                "Selected terrain tile "
                                        + selected.key()
                                        + " · Open explorer to load this location");
                });
        viewport.setFocusTraversable(true);
        viewport.setMinSize(240, 240);
        viewport.setPrefSize(800, 540);
        image.fitWidthProperty().bind(viewport.widthProperty());
        image.fitHeightProperty().bind(viewport.heightProperty());
        image.setSmooth(true);
        viewport.setOnKeyPressed(
                e -> {
                    keys.add(e.getCode());
                    e.consume();
                });
        viewport.setOnKeyReleased(
                e -> {
                    keys.remove(e.getCode());
                    e.consume();
                });
        viewport.focusedProperty()
                .addListener(
                        (o, a, b) -> {
                            if (!b) keys.clear();
                        });
        viewport.setOnMousePressed(
                e -> {
                    viewport.requestFocus();
                    mouseX = e.getX();
                    mouseY = e.getY();
                });
        viewport.setOnMouseDragged(
                e -> {
                    if (camera != null) {
                        camera =
                                camera.look((e.getX() - mouseX) * .006, (mouseY - e.getY()) * .006);
                        mouseX = e.getX();
                        mouseY = e.getY();
                        requestFrame();
                    }
                });
        viewport.setOnScroll(
                e -> {
                    speed = Math.clamp(speed * (e.getDeltaY() > 0 ? 1.2 : .8), 10, 800);
                    e.consume();
                });
        viewport.widthProperty().addListener((o, a, b) -> requestFrame());
        viewport.heightProperty().addListener((o, a, b) -> requestFrame());
        timer =
                new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (!active || !explorer || camera == null) return;
                        double dt = lastTick == 0 ? 0 : Math.min(.1, (now - lastTick) / 1e9);
                        lastTick = now;
                        double step = speed * dt * (keys.contains(KeyCode.SHIFT) ? 4 : 1),
                                f =
                                        (keys.contains(KeyCode.W) ? 1 : 0)
                                                - (keys.contains(KeyCode.S) ? 1 : 0),
                                s =
                                        (keys.contains(KeyCode.D) ? 1 : 0)
                                                - (keys.contains(KeyCode.A) ? 1 : 0),
                                z =
                                        (keys.contains(KeyCode.E) ? 1 : 0)
                                                - (keys.contains(KeyCode.Q) ? 1 : 0);
                        double
                                turn =
                                        ((keys.contains(KeyCode.RIGHT) ? 1 : 0)
                                                        - (keys.contains(KeyCode.LEFT) ? 1 : 0))
                                                * dt,
                                tilt =
                                        ((keys.contains(KeyCode.UP) ? 1 : 0)
                                                        - (keys.contains(KeyCode.DOWN) ? 1 : 0))
                                                * dt;
                        if (f != 0 || s != 0 || z != 0 || turn != 0 || tilt != 0) {
                            camera = camera.move(f * step, s * step, z * step).look(turn, tilt);
                            if (now - lastRender > 100_000_000) {
                                lastRender = now;
                                requestFrame();
                                loadNext();
                            }
                        }
                    }
                };
        showInventory();
    }

    void ensureLoaded() {
        if (closed) return;
        if (revision != revisions.getAsLong()) {
            invalidate();
            revision = revisions.getAsLong();
            handle = handles.get();
        }
        if (catalog != null || catalogLoading || handle == null) return;
        catalogLoading = true;
        long request = selection;
        status.setText("Reading build map records…");
        handle.catalog(token)
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            catalogLoading = false;
                                            if (error != null) {
                                                status.setText(
                                                        "Map records unavailable; use Reload after"
                                                                + " preparation");
                                                return;
                                            }
                                            catalog = data;
                                            filter();
                                            status.setText(
                                                    data.maps().size()
                                                            + " map records · "
                                                            + String.join(" · ", data.issues()));
                                        }));
    }

    void invalidate() {
        selection++;
        token.cancel();
        token = new Cancellation();
        catalog = null;
        catalogLoading = false;
        loadingTile = false;
        inventory = null;
        loaded.clear();
        attempted.clear();
        explorer = false;
        timer.stop();
        camera = null;
        terrainScene = null;
        latest.set(null);
        frameGeneration.incrementAndGet();
        image.setImage(null);
        maps.getItems().clear();
        heading.setText("World");
        showInventory();
    }

    private boolean stale(long request) {
        return closed || request != selection;
    }

    private void filter() {
        if (catalog == null) return;
        if (!allMaps.isSelected()
                && catalog.maps().stream().noneMatch(m -> Integer.valueOf(0).equals(m.type())))
            allMaps.setSelected(true);
        String q = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
        maps.getItems()
                .setAll(
                        catalog.maps().stream()
                                .filter(
                                        m ->
                                                allMaps.isSelected()
                                                        || Integer.valueOf(0).equals(m.type()))
                                .filter(
                                        m ->
                                                (m.toString() + " " + m.id())
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .contains(q))
                                .sorted(Comparator.comparing(WorldService.MapInfo::toString))
                                .toList());
    }

    private void cancelRequests() {
        selection++;
        token.cancel();
        token = new Cancellation();
        catalogLoading = false;
        loadingTile = false;
        keys.clear();
        timer.stop();
        frameGeneration.incrementAndGet();
        latest.set(null);
        attempted.retainAll(loaded.keySet());
    }

    void deactivate() {
        active = false;
        cancelRequests();
    }

    void activate() {
        active = true;
        ensureLoaded();
        if (explorer && inventory != null) {
            if (camera == null) openExplorer();
            else {
                lastTick = 0;
                timer.start();
                requestFrame();
                loadNext();
            }
        }
    }

    private void selectMap(WorldService.MapInfo map) {
        selection++;
        token.cancel();
        token = new Cancellation();
        long request = selection;
        explorer = false;
        timer.stop();
        inventory = null;
        selected = null;
        camera = null;
        terrainScene = null;
        loaded.clear();
        attempted.clear();
        loadingTile = false;
        frameGeneration.incrementAndGet();
        latest.set(null);
        heading.setText(map + " · Map #" + map.id());
        zones.getItems()
                .setAll(
                        catalog.areas().stream()
                                .filter(a -> a.mapId() == map.id() && a.parentId() == 0)
                                .sorted(Comparator.comparing(WorldService.Area::toString))
                                .toList());
        showInventory();
        open.setDisable(true);
        status.setText("Reading terrain tile inventory…");
        handle.inventory(map, token)
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            if (error != null || data.world() == null) {
                                                status.setText(
                                                        data == null
                                                                ? "Terrain inventory unavailable"
                                                                : data.issue());
                                                return;
                                            }
                                            inventory = data;
                                            selected =
                                                    data.world().tiles().stream()
                                                            .min(
                                                                    Comparator.comparingDouble(
                                                                            t ->
                                                                                    Math.hypot(
                                                                                            t.x()
                                                                                                    - 32,
                                                                                            t.y()
                                                                                                    - 32)))
                                                            .orElse(null);
                                            open.setDisable(selected == null);
                                            drawTiles();
                                            status.setText(
                                                    data.world().tiles().size()
                                                            + " terrain tiles · Select a tile and"
                                                            + " open the explorer"
                                                            + (data.world().globalModel()
                                                                    ? " · Global world-model map"
                                                                    : ""));
                                        }));
    }

    private void showInventory() {
        explorer = false;
        timer.stop();
        keys.clear();
        content.setTop(new VBox(10, heading, status));
        content.setCenter(
                new javafx.scene.control.ScrollPane(
                        new VBox(
                                12,
                                new Label("AREAS / ZONES"),
                                zones,
                                new Label("TERRAIN TILES · click to choose a starting location"),
                                tiles)));
        content.setBottom(new HBox(10, open));
        drawTiles();
    }

    private void drawTiles() {
        var g = tiles.getGraphicsContext2D();
        g.setFill(javafx.scene.paint.Color.web("#e5ebe6"));
        g.fillRect(0, 0, 512, 512);
        if (inventory == null || inventory.world() == null) return;
        g.setFill(javafx.scene.paint.Color.web("#6c9a7c"));
        for (var t : inventory.world().tiles()) g.fillRect(t.x() * 8, t.y() * 8, 7, 7);
        if (selected != null) {
            g.setStroke(javafx.scene.paint.Color.web("#e0ae3d"));
            g.setLineWidth(2);
            g.strokeRect(selected.x() * 8, selected.y() * 8, 8, 8);
        }
    }

    private void openExplorer() {
        if (selected == null || inventory == null) return;
        cancelRequests();
        explorer = true;
        origin = selected;
        loaded.clear();
        attempted.clear();
        camera = null;
        image.setImage(null);
        content.setCenter(viewport);
        content.setBottom(
                new VBox(
                        8,
                        new HBox(
                                10,
                                back,
                                new Label(
                                        "WASD move · Q/E down/up · drag or arrows to look · Shift"
                                                + " faster · scroll changes speed")),
                        position));
        status.setText("Loading terrain tile " + selected.key() + "…");
        timer.start();
        loadTile(selected, true);
        viewport.requestFocus();
    }

    private void loadTile(WorldFormats.Tile tile, boolean initial) {
        loadingTile = true;
        attempted.add(tile.key());
        long request = selection;
        handle.tile(inventory.map(), tile, token)
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request) || !explorer) return;
                                            loadingTile = false;
                                            if (error != null || data.terrain() == null) {
                                                status.setText(
                                                        data == null
                                                                ? "Terrain tile unavailable"
                                                                : data.issue());
                                                if (!initial) loadNext();
                                                return;
                                            }
                                            loaded.put(tile.key(), data);
                                            if (initial) {
                                                float[] v = data.terrain().vertices();
                                                double minX = Double.POSITIVE_INFINITY,
                                                        minY = minX,
                                                        maxX = -minX,
                                                        maxY = -minX,
                                                        maxZ = -minX;
                                                for (int i = 0; i < v.length; i += 3) {
                                                    minX = Math.min(minX, v[i]);
                                                    minY = Math.min(minY, v[i + 1]);
                                                    maxX = Math.max(maxX, v[i]);
                                                    maxY = Math.max(maxY, v[i + 1]);
                                                    maxZ = Math.max(maxZ, v[i + 2]);
                                                }
                                                double ground = maxZ,
                                                        nearest = Double.POSITIVE_INFINITY;
                                                for (int at = 0; at < v.length; at += 3) {
                                                    double distance =
                                                            Math.hypot(
                                                                    v[at] - (minX + maxX) / 2,
                                                                    v[at + 1] - (minY + maxY) / 2);
                                                    if (distance < nearest) {
                                                        nearest = distance;
                                                        ground = v[at + 2];
                                                    }
                                                }
                                                originX = maxX;
                                                originY = maxY;
                                                camera =
                                                        new TerrainRenderer.Camera(
                                                                (minX + maxX) / 2,
                                                                (minY + maxY) / 2,
                                                                ground + 100,
                                                                Math.PI,
                                                                -.8);
                                            }
                                            trimLoaded();
                                            rebuildScene();
                                            requestFrame();
                                            loadNext();
                                        }));
    }

    private int tileX() {
        return origin.x() + (int) Math.floor((originY - camera.y()) / WorldFormats.TILE_SIZE);
    }

    private int tileY() {
        return origin.y() + (int) Math.floor((originX - camera.x()) / WorldFormats.TILE_SIZE);
    }

    private void loadNext() {
        if (!active || !explorer || loadingTile || camera == null || inventory == null) return;
        int x = tileX(), y = tileY();
        if (trimLoaded()) rebuildScene();
        var next =
                inventory.world().tiles().stream()
                        .filter(
                                t ->
                                        Math.abs(t.x() - x) <= 1
                                                && Math.abs(t.y() - y) <= 1
                                                && !loaded.containsKey(t.key())
                                                && !attempted.contains(t.key()))
                        .min(
                                Comparator.comparingInt(
                                        t -> Math.abs(t.x() - x) + Math.abs(t.y() - y)))
                        .orElse(null);
        if (next != null) loadTile(next, false);
    }

    private boolean trimLoaded() {
        if (camera == null) return false;
        int x = tileX(), y = tileY();
        var removed = new ArrayList<String>();
        loaded.forEach(
                (key, value) -> {
                    if (Math.abs(value.tile().x() - x) > 1 || Math.abs(value.tile().y() - y) > 1)
                        removed.add(key);
                });
        removed.forEach(
                key -> {
                    loaded.remove(key);
                    attempted.remove(key);
                });
        return !removed.isEmpty();
    }

    private void rebuildScene() {
        terrainScene =
                new TerrainRenderer.Scene(
                        loaded.values().stream().map(WorldService.LoadedTile::terrain).toList());
    }

    private void requestFrame() {
        if (!active || !explorer || camera == null || terrainScene == null || closed) return;
        int w = (int) Math.clamp(viewport.getWidth(), 320, 800),
                h = (int) Math.clamp(viewport.getHeight(), 240, 500);
        long frame = frameGeneration.incrementAndGet();
        latest.set(new Render(frame, terrainScene, camera, w, h));
        position.setText(
                String.format(
                        java.util.Locale.ROOT,
                        "Tile %d,%d · x %.1f / y %.1f / altitude %.1f · %.0f yards/s · %d tiles"
                                + " loaded",
                        tileX(),
                        tileY(),
                        camera.x(),
                        camera.y(),
                        camera.z(),
                        speed,
                        loaded.size()));
        kick();
    }

    private void kick() {
        if (!rendering.compareAndSet(false, true)) return;
        Thread.startVirtualThread(
                () -> {
                    try {
                        Render work;
                        while ((work = latest.getAndSet(null)) != null && !closed) {
                            var current = work;
                            try {
                                var frame =
                                        TerrainRenderer.render(
                                                work.scene(),
                                                work.camera(),
                                                work.width(),
                                                work.height(),
                                                () ->
                                                        closed
                                                                || frameGeneration.get()
                                                                        != current.generation());
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || frameGeneration.get()
                                                            != current.generation()) return;
                                            var bitmap =
                                                    new WritableImage(
                                                            frame.width(), frame.height());
                                            bitmap.getPixelWriter()
                                                    .setPixels(
                                                            0,
                                                            0,
                                                            frame.width(),
                                                            frame.height(),
                                                            PixelFormat.getIntArgbInstance(),
                                                            frame.pixels(),
                                                            0,
                                                            frame.width());
                                            image.setImage(bitmap);
                                            status.setText(
                                                    "Terrain explorer · height shading · buildings,"
                                                        + " water and collision are not rendered"
                                                            + (frame.budgetReached()
                                                                    ? " · rendering budget reached"
                                                                    : ""));
                                        });
                            } catch (java.util.concurrent.CancellationException ignored) {
                            }
                        }
                    } finally {
                        rendering.set(false);
                        if (latest.get() != null && !closed) kick();
                    }
                });
    }

    @Override
    public void close() {
        closed = true;
        invalidate();
    }
}
