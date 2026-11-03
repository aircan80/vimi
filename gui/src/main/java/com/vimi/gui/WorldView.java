package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaNavigation;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;
import com.vibeminer.rendering.world.TerrainScene;
import com.vibeminer.rendering.world.TerrainCamera;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Selected-build map inventory, terrain drill-down and bounded free-fly explorer. */
final class WorldView extends BorderPane implements AutoCloseable {
    private final java.util.function.Supplier<BuildWorld> handles;
    private final java.util.function.LongSupplier revisions;
    private final ListView<WorldService.MapInfo> maps = new ListView<>();
    private final ListView<WorldService.Area> zones = new ListView<>();
    private final ListView<WorldAreaNavigation.Match> areaMatches = new ListView<>();
    private final Button findArea = new Button("Find terrain"),
            cancelArea = new Button("Cancel scan");
    private final Label areaStatus =
            new Label("Select an area, then Find terrain. Missing files may be downloaded.");
    private final ProgressIndicator areaActivity = activity("world-area-activity", 18);
    private final ThreadPoolExecutor areaRequests = worker("world-area-requests"),
            areaCancellation = worker("world-area-cancellation");
    private Cancellation areaToken;
    private CompletableFuture<WorldAreaNavigation.Result> areaFuture;
    private CompletableFuture<Void> areaDrain = CompletableFuture.completedFuture(null);
    private long areaGeneration;
    private boolean areaScanning, areaCancelRequested, areaDrainMessage;
    private WorldAreaNavigation.Match areaEntry;
    private final TextField search = new TextField();
    private final CheckBox allMaps = new CheckBox("Include instances and other maps");
    private final Label heading = new Label("World"),
            status = new Label("Choose a map"),
            position = new Label();
    private final Canvas tiles = new Canvas(512, 512);
    private final Label inventoryMessage = new Label("Choose a map to see its terrain inventory.");
    private final StackPane tileInventory = new StackPane(tiles, inventoryMessage);
    private final BorderPane content = new BorderPane();
    private final ImageView image = new ImageView();
    private final StackPane viewport = new StackPane(image);
    private final WorldMinimap minimap = new WorldMinimap();
    private final TerrainGpuPreview gpu;
    private final CheckBox gpuEnabled = new CheckBox("GPU terrain");
    private final Button retryGpu = new Button("Retry GPU");
    private final Label gpuStatus = new Label();
    private final ProgressIndicator gpuActivity = activity("world-gpu-activity", 18);
    private TerrainScene gpuScene;
    private TerrainRenderer.Scene presentedScene;
    private boolean gpuVisible;
    private final Button open = new Button("Open explorer"), back = new Button("Back to map");
    private final ProgressIndicator catalogActivity = activity("world-catalog-activity", 18),
            localActivity = activity("world-local-activity", 18),
            firstFrameActivity = activity("world-first-frame-activity", 32);
    private final Label localStage = new Label();
    private final HBox localLoading = new HBox(8, localActivity, localStage);
    private final ThreadPoolExecutor requests = worker("world-requests"),
            preparation = worker("world-preparation");
    private CompletableFuture<?> invocation, sceneTask;
    private long sceneGeneration;
    private boolean scenePreparing, waveActive, waveDirty;
    private PendingPrepared pendingPrepared;
    private record PendingPrepared(Prepared value, long scene, long request, Runnable ready) {}

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
    private long revision = -1, selection, lastTick, lastRender, lastNearby;
    private final AnimationTimer timer;
    private final AtomicLong frameGeneration = new AtomicLong();
    private final AtomicReference<Render> latest = new AtomicReference<>();
    private final AtomicBoolean rendering = new AtomicBoolean();

    private record Prepared(
            TerrainRenderer.Scene scene, TerrainScene gpuScene,
            TerrainRenderer.Camera camera, double x, double y) {}

    private static ThreadPoolExecutor worker(String name) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ProgressIndicator activity(String id, int size) {
        var indicator = new ProgressIndicator(-1);
        indicator.setId(id);
        indicator.setPrefSize(size, size);
        indicator.setMaxSize(size, size);
        indicator.setAccessibleText(id.replace('-', ' '));
        indicator.setVisible(false);
        indicator.setManaged(false);
        return indicator;
    }

    private static void show(ProgressIndicator indicator, boolean visible) {
        indicator.setVisible(visible);
        indicator.setManaged(visible);
    }

    private void busy(String message) {
        boolean visible = message != null;
        localStage.setText(visible ? message : "");
        show(localActivity, visible);
        localLoading.setVisible(visible);
        localLoading.setManaged(visible);
    }

    private <T> CompletableFuture<T> invoke(
            java.util.function.Supplier<CompletableFuture<T>> action) {
        if (invocation != null) invocation.cancel(false);
        requests.getQueue().clear();
        try {
            var call = CompletableFuture.supplyAsync(action, requests).thenCompose(value -> value);
            invocation = call;
            return call;
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private record Render(
            long generation,
            long selection,
            TerrainRenderer.Scene scene,
            TerrainRenderer.Camera camera,
            int width,
            int height) {}

    WorldView(BuildPreparation.Job job) {
        this(
                () -> job.work() instanceof NativePreparationWork w ? w.worldHandle() : null,
                () -> job.work() instanceof NativePreparationWork w ? w.revision() : 0,
                GpuPreviewService.application());
    }

    WorldView(
            java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions) {
        this(handles, revisions, null);
    }

    WorldView(java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions, GpuPreviewService gpuService) {
        gpu = gpuService == null ? null : new TerrainGpuPreview(gpuService, Platform::runLater,
                update -> {
                    show(gpuActivity, update.busy());
                    gpuStatus.setText(update.message());
                    retryGpu.setDisable(update.busy() || update.ready());
                    publishPrepared();
                }, this::presentGpuFrame, this::requestFrame);
        gpuEnabled.setSelected(gpu != null);
        gpuEnabled.setDisable(gpu == null);
        gpuEnabled.setId("world-gpu-enabled");
        gpuStatus.setId("world-gpu-status");
        retryGpu.setId("world-gpu-retry");
        gpuStatus.setWrapText(true);
        retryGpu.setDisable(true);
        gpuEnabled.setOnAction(e -> {
            if (gpu != null && !gpuEnabled.isSelected()) {
                gpu.hide();
                show(gpuActivity, false);
                gpuStatus.setText("Software terrain");
            } else if (gpu != null) {
                gpu.retry();
            }
            publishPrepared();
            requestFrame();
        });
        retryGpu.setOnAction(e -> {
            if (gpu != null) {
                gpu.retry();
                gpuEnabled.setSelected(true);
                requestFrame();
            }
        });
        this.handles = handles;
        this.revisions = revisions;
        setPadding(new javafx.geometry.Insets(16));
        search.setPromptText("Find a continent or map");
        search.setAccessibleText("Search world maps");
        maps.setId("world-maps");
        viewport.setId("world-viewport");
        image.setId("world-frame");
        position.setId("world-position");
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
        var sidebar =
                new VBox(
                        10,
                        new Label("MAPS & CONTINENTS"),
                        search,
                        allMaps,
                        new HBox(8, reload, catalogActivity),
                        maps);
        localLoading.setVisible(false);
        localLoading.setManaged(false);
        firstFrameActivity.setMouseTransparent(true);
        viewport.getChildren().addAll(firstFrameActivity, minimap);
        StackPane.setAlignment(minimap, Pos.TOP_RIGHT);
        StackPane.setMargin(minimap, new Insets(12));
        VBox.setVgrow(maps, Priority.ALWAYS);
        heading.getStyleClass().add("h2");
        status.setWrapText(true);
        position.setWrapText(true);
        zones.setPrefHeight(180);
        zones.setAccessibleText("Areas in selected map");
        zones.setId("world-areas");
        findArea.setId("world-area-find");
        cancelArea.setId("world-area-cancel");
        areaStatus.setId("world-area-status");
        areaStatus.setWrapText(true);
        areaActivity.setAccessibleText("Finding terrain for the selected exact area");
        areaMatches.setId("world-area-matches");
        areaMatches.setAccessibleText("Verified exact-area terrain matches");
        areaMatches.setPrefHeight(130);
        areaMatches.setPlaceholder(new Label("No observed matching tiles"));
        areaMatches.setCellFactory(
                list ->
                        new javafx.scene.control.ListCell<>() {
                            @Override
                            protected void updateItem(
                                    WorldAreaNavigation.Match match, boolean empty) {
                                super.updateItem(match, empty);
                                setText(
                                        empty || match == null
                                                ? null
                                                : "Tile "
                                                        + match.tile().key()
                                                        + " · "
                                                        + match.matchingChunks()
                                                        + " matching chunks"
                                                        + (match.entryChunk() == null
                                                                ? " · no rendered entry (holes)"
                                                                : " · entry chunk "
                                                                        + match.entryChunk().x()
                                                                        + ","
                                                                        + match.entryChunk().y()));
                            }
                        });
        areaMatches
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            if (b == null || stale(selection)) return;
                            selected = b.tile();
                            areaEntry = b;
                            open.setDisable(areaScanning || b.entryChunk() == null);
                            status.setText(
                                    "Selected exact-area tile "
                                            + selected.key()
                                            + " · source and entry will be verified when opened");
                            drawTiles();
                        });
        findArea.setOnAction(e -> findTerrain());
        cancelArea.setOnAction(e -> cancelAreaScan());
        zones.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            stopArea(true);
                            areaEntry = null;
                            if (b != null && (inventory == null || inventory.world() == null
                                    || inventory.world().tiles().isEmpty())) {
                                status.setText(inventoryMessage.getText());
                            } else if (b != null)
                                status.setText(
                                        b
                                                + " · Area #"
                                                + b.id()
                                                + " · Select Find terrain to check exact-area"
                                                + " chunks; full-map inventory remains below");
                            areaButtons();
                        });
        areaButtons();
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
        inventoryMessage.setId("world-inventory-message");
        inventoryMessage.setWrapText(true);
        inventoryMessage.setMaxWidth(400);
        inventoryMessage.setStyle("-fx-background-color: #f3f6f3; -fx-padding: 18; -fx-text-fill: #263c31;");
        tileInventory.setMinWidth(512);
        tileInventory.setMaxWidth(512);
        tiles.setId("world-tiles");
        tiles.setAccessibleText("Full-map terrain tile inventory");
        tiles.setOnMouseClicked(
                e -> {
                    if (stale(selection) || inventory == null || inventory.world() == null) return;
                    // Tile.x/y retain ADT filename order; screen columns use the second index.
                    int x = (int) (e.getY() / 8), y = (int) (e.getX() / 8);
                    selected =
                            inventory.world().tiles().stream()
                                    .filter(t -> t.x() == x && t.y() == y)
                                    .findFirst()
                                    .orElse(null);
                    areaEntry =
                            areaMatches.getItems().stream()
                                    .filter(match -> match.tile().equals(selected))
                                    .findFirst()
                                    .orElse(null);
                    drawTiles();
                    open.setDisable(
                            areaScanning
                                    || selected == null
                                    || areaEntry != null && areaEntry.entryChunk() == null);
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
        image.setPreserveRatio(true);
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
                        if (gpu != null && !viewportActive()) {
                            minimap.hide();
                            if (gpuVisible) {
                                gpu.hide();
                                gpuVisible = false;
                                show(gpuActivity, false);
                            }
                            keys.clear();
                            lastTick = 0;
                            return;
                        }
                        if (gpu != null && !gpuVisible) {
                            gpuVisible = true;
                            requestFrame();
                        }
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
                                        ((keys.contains(KeyCode.E) || keys.contains(KeyCode.SPACE))
                                                        ? 1
                                                        : 0)
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
                            long cadence = gpu != null && gpu.ready() ? 16_000_000 : 100_000_000;
                            if (now - lastRender > cadence) {
                                lastRender = now;
                                requestFrame();
                            }
                            if (now - lastNearby > 100_000_000) {
                                lastNearby = now;
                                loadNext();
                            }
                        }
                    }
                };
        showInventory();
    }

    void ensureLoaded() {
        if (closed) return;
        long currentRevision = revisions.getAsLong();
        var currentHandle = handles.get();
        if (revision != currentRevision || handle != currentHandle) {
            invalidate();
            revision = currentRevision;
            handle = currentHandle;
        }
        if (catalog != null || catalogLoading || handle == null) return;
        catalogLoading = true;
        long request = selection;
        status.setText("Reading build map records…");
        show(catalogActivity, true);
        maps.setDisable(true);
        maps.setPlaceholder(new VBox(8, new Label("Loading map records…")));
        var selectedHandle = handle;
        var cancel = token;
        invoke(() -> selectedHandle.catalog(cancel))
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            catalogLoading = false;
                                            show(catalogActivity, false);
                                            if (error != null || data == null) {
                                                status.setText(
                                                        "Map records unavailable; use Reload after"
                                                                + " preparation");
                                                return;
                                            }
                                            catalog = data;
                                            maps.setDisable(false);
                                            maps.setPlaceholder(new Label("No matching maps"));
                                            filter();
                                            status.setText(
                                                    data.maps().size()
                                                            + " map records · "
                                                            + String.join(" · ", data.issues()));
                                        }));
    }

    void invalidate() {
        retireGpu();
        gpuScene = null;
        presentedScene = null;
        stopArea(true);
        areaEntry = null;
        selection++;
        token.cancel();
        token = new Cancellation();
        catalog = null;
        catalogLoading = false;
        loadingTile = false;
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        inventory = null;
        loaded.clear();
        attempted.clear();
        explorer = false;
        timer.stop();
        camera = null;
        terrainScene = null;
        latest.set(null);
        frameGeneration.incrementAndGet();
        cancelPreparation();
        image.setImage(null);
        maps.setDisable(true);
        heading.setText("World");
        inventoryMessage.setText("Choose a map to see its terrain inventory.");
        showInventory();
    }

    private boolean stale(long request) {
        if (closed || request != selection) return true;
        if (revision != revisions.getAsLong() || handle != handles.get()) {
            cancelRequests();
            maps.setDisable(true);
            open.setDisable(true);
            status.setText("World data changed. Reload maps to update; previous content retained.");
            return true;
        }
        return false;
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
        minimap.hide();
        retireGpu();
        stopArea(false);
        selection++;
        token.cancel();
        token = new Cancellation();
        catalogLoading = false;
        loadingTile = false;
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        keys.clear();
        timer.stop();
        frameGeneration.incrementAndGet();
        latest.set(null);
        cancelPreparation();
        attempted.retainAll(loaded.keySet());
    }

    void deactivate() {
        active = false;
        cancelRequests();
    }

    void activate() {
        active = true;
        ensureLoaded();
        areaButtons();
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
        stopArea(true);
        areaEntry = null;
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
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        frameGeneration.incrementAndGet();
        latest.set(null);
        inventoryMessage.setText("Loading terrain tile inventory…");
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
        busy("Loading terrain inventory…");
        var selectedHandle = handle;
        var cancel = token;
        invoke(() -> selectedHandle.inventory(map, cancel))
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) return;
                                            busy(null);
                                            if (error != null
                                                    || data == null
                                                    || data.world() == null) {
                                                String message = data != null && data.issue() != null
                                                        && !data.issue().isBlank() ? data.issue()
                                                        : "Terrain inventory unavailable for this map. Select another map or try again.";
                                                inventoryMessage.setText(message);
                                                status.setText(message);
                                                open.setDisable(true);
                                                drawTiles();
                                                areaButtons();
                                                return;
                                            }
                                            inventory = data;
                                            if (zones.getSelectionModel().getSelectedItem() != null)
                                                areaStatus.setText(areaHint());
                                            areaButtons();
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
                                            status.setText(data.world().tiles().isEmpty()
                                                    ? inventoryMessage.getText()
                                                    : data.world().tiles().size()
                                                            + " terrain tiles · Select a tile and"
                                                            + " open the explorer"
                                                            + (data.world().globalModel()
                                                                    ? " · Global world-model map"
                                                                    : ""));
                                        }));
    }

    private String areaHint() {
        return inventory == null || inventory.world() == null
                ? "Select an area after the terrain inventory loads. Missing files may be"
                        + " downloaded."
                : inventory.world().tiles().isEmpty()
                        ? emptyInventoryMessage()
                        : "Find terrain checks "
                        + inventory.world().tiles().size()
                        + " files for this exact area. Missing files may be downloaded; you can"
                        + " cancel.";
    }

    private void areaButtons() {
        boolean usable =
                !closed
                        && active
                        && !explorer
                        && inventory != null
                        && inventory.world() != null
                        && !inventory.world().tiles().isEmpty()
                        && zones.getSelectionModel().getSelectedItem() != null
                        && zones.getSelectionModel().getSelectedItem().id() > 0
                        && revision == revisions.getAsLong()
                        && handle == handles.get();
        if (!areaScanning && !areaDrain.isDone()) {
            areaDrainMessage = true;
            areaStatus.setText(
                    "Previous area scan is stopping; waiting for its current file before another"
                            + " scan.");
        } else if (!areaScanning && areaDrainMessage) {
            areaDrainMessage = false;
            areaStatus.setText(areaHint());
        }
        show(areaActivity, !closed && active && !explorer && (areaScanning || !areaDrain.isDone()));
        findArea.setDisable(!usable || areaScanning || !areaDrain.isDone());
        cancelArea.setDisable(!areaScanning || areaCancelRequested);
        areaMatches.setDisable(!usable || areaScanning);
    }

    private void cancelAreaScan() {
        var cancel = areaToken;
        var pending = areaFuture;
        if (cancel == null || pending == null || areaCancelRequested) return;
        areaToken = null;
        areaCancelRequested = true;
        areaStatus.setText(
                "Stopping scan · waiting for the current file; observed matches will be retained as"
                        + " incomplete");
        areaButtons();
        areaDrain =
                CompletableFuture.runAsync(cancel::cancel, areaCancellation)
                        .thenCompose(ignored -> pending.handle((result, error) -> (Void) null));
        areaDrain.whenComplete((result, error) -> Platform.runLater(this::areaButtons));
    }

    private void stopArea(boolean clear) {
        ++areaGeneration;
        var cancel = areaToken;
        var pending = areaFuture;
        areaToken = null;
        areaFuture = null;
        areaScanning = false;
        areaCancelRequested = false;
        show(areaActivity, false);
        if (cancel != null) {
            try {
                areaDrain =
                        CompletableFuture.runAsync(cancel::cancel, areaCancellation)
                                .thenCompose(
                                        ignored ->
                                                pending == null
                                                        ? CompletableFuture.completedFuture(null)
                                                        : pending.handle(
                                                                (value, error) -> (Void) null));
                areaDrain.whenComplete((value, error) -> Platform.runLater(this::areaButtons));
            } catch (RuntimeException rejected) {
                areaStatus.setText("Area scan is stopping; reload the workspace before retrying.");
            }
            if (!clear)
                areaStatus.setText(
                        "Scan canceled · observed matches retained only after readable checked"
                                + " files finish; coverage is incomplete.");
        }
        if (clear) {
            areaMatches.getItems().clear();
            areaStatus.setText(areaHint());
            drawTiles();
        }
        areaButtons();
    }

    private void findTerrain() {
        if (stale(selection)) return;
        var area = zones.getSelectionModel().getSelectedItem();
        if (area == null
                || area.id() <= 0
                || inventory == null
                || inventory.world() == null
                || areaScanning
                || !areaDrain.isDone()) return;
        long generation = ++areaGeneration, request = selection;
        var selectedHandle = handle;
        var source =
                new WorldAreaNavigation.Request(
                        inventory.map(), area.id(), inventory.world().tiles());
        areaToken = new Cancellation();
        var cancel = areaToken;
        areaScanning = true;
        areaCancelRequested = false;
        areaEntry = null;
        areaMatches.getItems().clear();
        open.setDisable(true);
        show(areaActivity, true);
        areaStatus.setText(
                "Checking 0 / "
                        + source.tiles().size()
                        + " terrain files · missing files may be downloaded");
        areaButtons();
        var latestProgress = new AtomicReference<WorldAreaNavigation.Progress>();
        var progressQueued = new AtomicBoolean();
        try {
            areaFuture =
                    CompletableFuture.supplyAsync(
                                    () ->
                                            selectedHandle.findArea(
                                                    source,
                                                    cancel,
                                                    progress -> {
                                                        latestProgress.set(progress);
                                                        if (progressQueued.compareAndSet(
                                                                false, true))
                                                            Platform.runLater(
                                                                    () -> {
                                                                        progressQueued.set(false);
                                                                        var current =
                                                                                latestProgress
                                                                                        .getAndSet(
                                                                                                null);
                                                                        if (generation
                                                                                        != areaGeneration
                                                                                || stale(request)
                                                                                || current == null
                                                                                || !areaScanning)
                                                                            return;
                                                                        areaStatus.setText(
                                                                                "Checked "
                                                                                        + current
                                                                                                .checked()
                                                                                        + " / "
                                                                                        + current
                                                                                                .total()
                                                                                        + " · "
                                                                                        + current
                                                                                                .matches()
                                                                                        + " matching"
                                                                                        + " tiles ·"
                                                                                        + " "
                                                                                        + current
                                                                                                .unavailable()
                                                                                        + " unavailable");
                                                                    });
                                                    }),
                                    areaRequests)
                            .thenCompose(value -> value);
        } catch (RuntimeException failure) {
            areaFuture = CompletableFuture.failedFuture(failure);
        }
        areaFuture.whenComplete(
                (result, error) ->
                        Platform.runLater(
                                () -> {
                                    if (generation != areaGeneration || stale(request)) return;
                                    areaScanning = false;
                                    areaCancelRequested = false;
                                    areaToken = null;
                                    areaFuture = null;
                                    show(areaActivity, false);
                                    if (error != null || result == null) {
                                        areaStatus.setText(
                                                "Area terrain discovery unavailable · full-map"
                                                        + " inventory remains usable");
                                    } else {
                                        areaMatches.getItems().setAll(result.matches());
                                        String coverage =
                                                result.state() == WorldAreaNavigation.State.COMPLETE
                                                        ? "Checked complete inventory for this"
                                                                + " exact area"
                                                        : "Incomplete checked subset ("
                                                                + result.state()
                                                                        .name()
                                                                        .toLowerCase(
                                                                                java.util.Locale
                                                                                        .ROOT)
                                                                + ")";
                                        areaStatus.setText(
                                                coverage
                                                        + " · "
                                                        + result.progress().checked()
                                                        + " / "
                                                        + result.progress().total()
                                                        + " files · "
                                                        + result.matches().size()
                                                        + " matching tiles · "
                                                        + result.progress().unavailable()
                                                        + " unavailable"
                                                        + (result.matches().isEmpty()
                                                                ? " · no match observed; no claim"
                                                                        + " about unchecked or"
                                                                        + " unreadable files"
                                                                : " · choose a match to open its"
                                                                        + " verified chunk entry"));
                                    }
                                    open.setDisable(selected == null);
                                    drawTiles();
                                    areaButtons();
                                }));
    }

    private void showInventory() {
        minimap.hide();
        retireGpu();
        explorer = false;
        timer.stop();
        keys.clear();
        content.setTop(new VBox(10, heading, status, localLoading));
        content.setCenter(
                new javafx.scene.control.ScrollPane(
                        new VBox(
                                12,
                                new Label("AREAS / ZONES · exact-area discovery"),
                                zones,
                                new HBox(8, findArea, cancelArea, areaActivity),
                                areaStatus,
                                new Label("Exact area IDs only; child areas are not included."),
                                areaMatches,
                                new Label("Terrain tiles below cover the entire selected map."),
                                new Label("TERRAIN TILES · click to choose a starting location"),
                                tileInventory)));
        content.setBottom(new HBox(10, open));
        drawTiles();
        areaButtons();
    }

    private String emptyInventoryMessage() {
        return inventory.world().globalModel()
                ? "This map records a global world model instead of terrain tiles. World-model previews are not available yet."
                : "No terrain tiles are recorded for this map. Select another map to explore terrain.";
    }

    private void drawTiles() {
        boolean empty = inventory == null || inventory.world() == null || inventory.world().tiles().isEmpty();
        if (inventory != null && inventory.world() != null && inventory.world().tiles().isEmpty()) {
            inventoryMessage.setText(emptyInventoryMessage());
        }
        double height = empty ? 224 : 512;
        tiles.setHeight(height);
        tileInventory.setMinHeight(height);
        tileInventory.setMaxHeight(height);
        inventoryMessage.setVisible(empty);
        inventoryMessage.setManaged(empty);
        // WDT/ADT filename x is the north/south row, y the east/west column.
        // Keep file and explorer coordinates intact; transpose only the 2D presentation.
        var g = tiles.getGraphicsContext2D();
        g.setFill(javafx.scene.paint.Color.web("#e5ebe6"));
        g.fillRect(0, 0, 512, 512);
        if (inventory == null || inventory.world() == null) return;
        g.setFill(javafx.scene.paint.Color.web("#6c9a7c"));
        for (var t : inventory.world().tiles()) g.fillRect(t.y() * 8, t.x() * 8, 7, 7);
        g.setStroke(javafx.scene.paint.Color.web("#286cb5"));
        g.setLineWidth(2);
        for (var match : areaMatches.getItems())
            g.strokeRect(match.tile().y() * 8 + 1, match.tile().x() * 8 + 1, 6, 6);
        if (selected != null) {
            g.setStroke(javafx.scene.paint.Color.web("#e0ae3d"));
            g.setLineWidth(2);
            g.strokeRect(selected.y() * 8, selected.x() * 8, 8, 8);
        }
    }

    private void openExplorer() {
        if (stale(selection) || selected == null || inventory == null) return;
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
                                        "WASD move · Space/E up · Q down · drag or arrows to look · Shift"
                                                + " faster · scroll changes speed")),
                        position,
                        new HBox(8, gpuEnabled, gpuActivity, retryGpu, gpuStatus)));
        status.setText("Loading terrain tile " + selected.key() + "…");
        show(firstFrameActivity, true);
        timer.start();
        loadTile(selected, true);
        viewport.requestFocus();
    }

    private void loadTile(WorldFormats.Tile tile, boolean initial) {
        loadingTile = true;
        busy(
                initial && areaEntry != null
                        ? "Verifying discovered source and area entry…"
                        : initial ? "Loading first terrain tile…" : "Loading nearby terrain…");
        attempted.add(tile.key());
        long request = selection;
        var selectedHandle = handle;
        var map = inventory.map();
        var cancel = token;
        var match = initial ? areaEntry : null;
        invoke(
                        () ->
                                match == null
                                        ? selectedHandle.tile(map, tile, cancel)
                                        : selectedHandle.areaTile(map, match, cancel))
                .whenComplete(
                        (data, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request) || !explorer) return;
                                            if (error != null
                                                    || data == null
                                                    || data.terrain() == null) {
                                                loadingTile = false;
                                                busy(null);
                                                if (initial) show(firstFrameActivity, false);
                                                status.setText(
                                                        data == null
                                                                ? "Terrain tile unavailable"
                                                                : data.issue());
                                                if (!initial) loadNext();
                                                return;
                                            }
                                            if (!initial) {
                                                int x = tileX(), y = tileY();
                                                if (Math.abs(tile.x() - x) <= 1 && Math.abs(tile.y() - y) <= 1) {
                                                    if (!loaded.containsKey(tile.key()) && loaded.size() == 9) {
                                                        var outgoing = loaded.values().stream()
                                                                .max(Comparator.comparingInt(t -> Math.abs(t.tile().x() - x) + Math.abs(t.tile().y() - y)))
                                                                .orElseThrow();
                                                        loaded.remove(outgoing.tile().key());
                                                        attempted.remove(outgoing.tile().key());
                                                    }
                                                    loaded.put(tile.key(), data);
                                                    waveDirty = true;
                                                } else {
                                                    attempted.remove(tile.key());
                                                }
                                                loadingTile = false;
                                                loadNext();
                                                return;
                                            }
                                            loaded.put(tile.key(), data);
                                            prepareScene(data.terrain(), () -> {
                                                loadingTile = false;
                                                busy(null);
                                                requestFrame();
                                                loadNext();
                                            });
                                        }));
    }

    private void cancelPreparation() {
        ++sceneGeneration;
        pendingPrepared = null;
        scenePreparing = false;
        waveActive = false;
        waveDirty = false;
        if (sceneTask != null) sceneTask.cancel(false);
        preparation.getQueue().clear();
        if (invocation != null) invocation.cancel(false);
        requests.getQueue().clear();
    }

    private static Prepared prepare(
            java.util.List<WorldService.LoadedTile> tiles, WorldFormats.Terrain initial,
            boolean prepareGpu, WorldFormats.AreaPoint areaPoint) {
        TerrainRenderer.Camera camera = null;
        double originX = 0, originY = 0;
        if (initial != null) {
            float[] v = initial.vertices();
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
            double ground = maxZ, nearest = Double.POSITIVE_INFINITY;
            for (int at = 0; at < v.length; at += 3) {
                double distance =
                        Math.hypot(v[at] - (minX + maxX) / 2, v[at + 1] - (minY + maxY) / 2);
                if (distance < nearest) {
                    nearest = distance;
                    ground = v[at + 2];
                }
            }
            originX = maxX;
            originY = maxY;
            camera = areaPoint == null
                    ? new TerrainRenderer.Camera(
                            (minX + maxX) / 2, (minY + maxY) / 2, ground + 100, Math.PI, -.8)
                    : new TerrainRenderer.Camera(areaPoint.x(), areaPoint.y(),
                            areaPoint.z() + 100, Math.PI, -.8);
        }
        var cpu = new TerrainRenderer.Scene(tiles.stream().map(WorldService.LoadedTile::terrain).toList());
        var nativeScene = prepareGpu && !tiles.isEmpty() ? new TerrainScene(tiles.stream()
                .map(tile -> new TerrainScene.Tile(tile.tile().x(), tile.tile().y(), tile.terrain()))
                .toList()) : null;
        return new Prepared(cpu, nativeScene, camera, originX, originY);
    }

    private void prepareScene(WorldFormats.Terrain initial, Runnable ready) {
        long scene = ++sceneGeneration, request = selection;
        scenePreparing = true;
        pendingPrepared = null;
        if (sceneTask != null) sceneTask.cancel(false);
        preparation.getQueue().clear();
        var snapshot = java.util.List.copyOf(loaded.values());
        var areaPoint = initial != null && areaEntry != null && areaEntry.entryChunk() != null
                ? areaEntry.entryChunk().entryReference() : null;
        CompletableFuture<Prepared> result;
        try {
            result = CompletableFuture.supplyAsync(
                    () -> prepare(snapshot, initial, gpu != null, areaPoint), preparation);
        } catch (RuntimeException error) {
            result = CompletableFuture.failedFuture(error);
        }
        sceneTask = result;
        result.whenComplete(
                (prepared, error) ->
                        Platform.runLater(
                                () -> {
                                    if (stale(request) || scene != sceneGeneration || !explorer)
                                        return;
                                    if (error != null) {
                                        scenePreparing = false;
                                        loadingTile = false;
                                        busy(null);
                                        show(firstFrameActivity, false);
                                        status.setText(
                                                "Terrain preview preparation unavailable; readable"
                                                        + " terrain retained.");
                                        return;
                                    }
                                    pendingPrepared = new PendingPrepared(prepared, scene, request, ready);
                                    publishPrepared();
                                }));
    }

    private boolean publishPrepared() {
        var pending = pendingPrepared;
        if (pending == null) return false;
        if (stale(pending.request()) || pending.scene() != sceneGeneration || !explorer) return false;
        if (gpu != null && gpuEnabled.isSelected() && !gpu.canReplaceScene()) return false;
        pendingPrepared = null;
        scenePreparing = false;
        var prepared = pending.value();
        terrainScene = prepared.scene();
        gpuScene = prepared.gpuScene();
        if (gpuScene == null && gpu != null) {
            retireGpu();
            gpuStatus.setText("Software terrain · no loaded nearby tiles");
        }
        if (prepared.camera() != null) {
            camera = prepared.camera();
            originX = prepared.x();
            originY = prepared.y();
        }
        pending.ready().run();
        return true;
    }

    private int tileX() {
        return origin.x() + (int) Math.floor((originX - camera.x()) / WorldFormats.TILE_SIZE);
    }

    private int tileY() {
        return origin.y() + (int) Math.floor((originY - camera.y()) / WorldFormats.TILE_SIZE);
    }

    private void loadNext() {
        if (!active || !explorer || loadingTile || scenePreparing || camera == null || inventory == null) return;
        int x = tileX(), y = tileY();
        attempted.retainAll(inventory.world().tiles().stream()
                .filter(t -> Math.abs(t.x() - x) <= 1 && Math.abs(t.y() - y) <= 1)
                .map(WorldFormats.Tile::key).collect(java.util.stream.Collectors.toSet()));
        if (!waveActive) {
            waveActive = true;
            waveDirty = false;
        }
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
        if (next != null) {
            loadTile(next, false);
        } else {
            waveActive = false;
            boolean useful = loaded.values().stream().anyMatch(t ->
                    Math.abs(t.tile().x() - x) <= 1 && Math.abs(t.tile().y() - y) <= 1);
            if (!useful && !loaded.isEmpty()) {
                loaded.clear();
                waveDirty = true;
            }
            if (waveDirty) {
                waveDirty = false;
                busy("Preparing nearby terrain…");
                prepareScene(null, () -> { busy(null); requestFrame(); loadNext(); });
            } else {
                busy(null);
            }
        }
    }

    private void requestFrame() {
        if (!active || !explorer || camera == null || terrainScene == null || closed) return;
        if (publishPrepared()) return;
        if (stale(selection) || !viewportActive()) {
            minimap.hide();
            return;
        }
        minimap.update(handle, inventory, origin, originX, originY,
                camera.x(), camera.y(), camera.yaw());
        double targetWidth = Math.max(1, viewport.getWidth()),
                targetHeight = Math.max(1, viewport.getHeight());
        double scale = Math.min(1, Math.min(800 / targetWidth, 500 / targetHeight));
        int w = Math.max(1, (int) (targetWidth * scale)),
                h = Math.max(1, (int) (targetHeight * scale));
        long frame = frameGeneration.incrementAndGet();
        show(firstFrameActivity, image.getImage() == null || presentedScene != terrainScene);
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
        if (gpu != null && gpuEnabled.isSelected() && gpuScene != null) {
            if (!viewportActive()) {
                retireGpu();
                return;
            }
            gpuVisible = true;
            try {
                if (gpu.request(gpuScene, TerrainCamera.from(camera), w, h)) {
                    latest.set(null);
                    return;
                }
            } catch (RuntimeException invalid) {
                gpu.hide();
                show(gpuActivity, false);
                gpuStatus.setText("GPU terrain unavailable; using software rendering.");
            }
        }
        latest.set(new Render(frame, selection, terrainScene, camera, w, h));
        kick();
    }

    private void retireGpu() {
        if (gpu != null) {
            gpu.hide();
            gpuVisible = false;
            show(gpuActivity, false);
        }
    }

    private boolean viewportActive() {
        if (closed || !active || !explorer || getScene() == null
                || getScene().getWindow() == null || !getScene().getWindow().isShowing()) return false;
        if (getScene().getWindow() instanceof javafx.stage.Stage stage && stage.isIconified()) return false;
        for (javafx.scene.Node node = this; node != null; node = node.getParent()) {
            if (!node.isVisible()) return false;
        }
        return true;
    }

    private boolean presentGpuFrame(GpuPreviewService.Frame frame) {
        if (stale(selection) || !viewportActive() || !gpuEnabled.isSelected()) return false;
        var bitmap = image.getImage() instanceof WritableImage existing
                && existing.getWidth() == frame.width() && existing.getHeight() == frame.height()
                ? existing : new WritableImage(frame.width(), frame.height());
        bitmap.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                PixelFormat.getIntArgbPreInstance(), frame.pixels(), frame.width());
        image.setImage(bitmap);
        presentedScene = terrainScene;
        show(firstFrameActivity, false);
        status.setText("Terrain explorer · height shading · buildings, water and collision are not rendered");
        return true;
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
                                            if (stale(current.selection())
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
                                            presentedScene = current.scene();
                                            if (gpu != null && !gpu.ready()) {
                                                show(gpuActivity, false);
                                            }
                                            show(firstFrameActivity, false);
                                            status.setText(
                                                    "Terrain explorer · height shading · buildings,"
                                                        + " water and collision are not rendered"
                                                            + (frame.budgetReached()
                                                                    ? " · rendering budget reached"
                                                                    : ""));
                                        });
                            } catch (java.util.concurrent.CancellationException ignored) {
                            } catch (RuntimeException error) {
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || frameGeneration.get()
                                                            != current.generation()) return;
                                            show(firstFrameActivity, false);
                                            status.setText(
                                                    "Terrain frame unavailable; previous image"
                                                            + " retained.");
                                        });
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
        minimap.close();
        if (gpu != null) gpu.close();
        invalidate();
        requests.shutdownNow();
        areaRequests.shutdown();
        areaCancellation.shutdown();
        preparation.shutdownNow();
    }
}
