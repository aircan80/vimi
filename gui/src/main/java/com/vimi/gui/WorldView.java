package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaNavigation;
import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldInstanceLinks;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.TerrainGround;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
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
    private final Label currentArea = new Label("Terrain area unavailable");
    private static final String INSPECT_HINT =
            "Click terrain, a drawn building, doodad or creature model, or a shown NPC spawn marker to inspect it";
    private final Label inspection = new Label(INSPECT_HINT);
    // Latest click wins: one CPU pick at a time over immutable height snapshots, never on FX.
    private final ThreadPoolExecutor picks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("world-pick").factory(),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private long pickGeneration;
    private final WorldAudioControls audio = new WorldAudioControls();
    private final Canvas tiles = new Canvas(512, 512);
    private final Label inventoryMessage = new Label("Choose a map to see its terrain inventory.");
    private final StackPane tileInventory = new StackPane(tiles, inventoryMessage);
    private final BorderPane content = new BorderPane();
    private final ImageView image = new ImageView();
    private final StackPane viewport = new StackPane(image);
    private final WorldMinimap minimap = new WorldMinimap();
    private WorldSpawnOverlay spawns;
    private WorldRoleOverlay roles;
    // Highlight of the picked object; FX overlay only, cleared by terrain, sky and marker clicks.
    private final WorldSelectionOverlay objectSelection = new WorldSelectionOverlay();
    // Metadata-only listfile names for picked objects; read on the pick worker, null when unknown.
    private java.util.function.LongFunction<String> fileNames = id -> null;
    private WorldTileCreatures creatures;
    private WorldSpawnOverlay.Picked inspectedSpawn;
    private String inspectedMap;
    private long inspectedRequest;
    private final TerrainGpuPreview gpu;
    private final GpuPreviewService gpuService;
    private WorldStreamSourceController streaming;
    private final WorldStreamSourceController.Events streamingEvents = this::streamChanged;
    private boolean streamingMode;
    private boolean streamingPaused, streamingStartWaiting, renderingTransitionPending;
    static final String STALL_HINT = "Renderer not responding; Retry";
    // Detection only: logs and hints once per stall episode, never kills or restarts.
    private final WorldStallWatchdog stallWatchdog = new WorldStallWatchdog(
            WorldStallWatchdog.configured(System.getProperty("vimi.world.stallMillis")));
    private final WorldStreamImagePresentation streamingImages = new WorldStreamImagePresentation(image);
    private TerrainRenderer.Camera resumeCamera;
    private long streamingControl, streamingPolicy = 1;
    private int streamingWidth, streamingHeight;
    private CompletableFuture<Void> streamingRetirement = CompletableFuture.completedFuture(null);
    private final CheckBox gpuEnabled = new CheckBox("GPU terrain");
    // Object texture budget tier (#267); a change re-prepares the streamed tiles' objects.
    private final javafx.scene.control.ChoiceBox<
                    com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail>
            objectTextures = new javafx.scene.control.ChoiceBox<>();
    private final CheckBox walkMode = new CheckBox("Walk on terrain (F)");
    private final CheckBox smoothMotion = new CheckBox("Smooth motion");
    private final javafx.scene.control.Slider lookSensitivity = new javafx.scene.control.Slider(
            WorldControls.MIN_SENSITIVITY, WorldControls.MAX_SENSITIVITY, WorldControls.DEFAULT_SENSITIVITY);
    private final CheckBox invertLook = new CheckBox("Invert mouse Y");
    private final Label lookStatus = new Label();
    // Session-only until the application's library store is installed (bookmarks(...)).
    private WorldControls.Store controlsStore = WorldControls.memory();
    private boolean showingControls, controlsEdited, captureFallbackLogged;
    private final Label motionStatus = new Label("Free flight · no collision");
    private final WorldExplorerInput input = new WorldExplorerInput(new WorldExplorerInput.Host() {
        @Override public TerrainRenderer.Camera camera() { return camera; }
        @Override public void camera(TerrainRenderer.Camera next) { camera = next; }
        @Override public TerrainGround ground() { return currentGround(); }
        @Override public boolean wheelReady() {
            return WorldExplorerInput.wheelReady(terrainScene != null, streamingMode, streaming != null);
        }
        @Override public boolean fastCadence() { return streamingMode || gpu != null && gpu.ready(); }
        @Override public void groundStatus(ExplorerMotion.Ground ground) { WorldView.this.groundStatus(ground); }
        @Override public void toggleWalk() {
            walkMode.setSelected(!walkMode.isSelected());
            motionChanged();
        }
        @Override public void requestFrame() { WorldView.this.requestFrame(); }
        @Override public void abandonUnusableCandidate() { WorldView.this.abandonUnusableCandidate(); }
        @Override public void loadNext() { WorldView.this.loadNext(); }
        @Override public void cursorHidden(boolean hidden) {
            viewport.setCursor(hidden ? javafx.scene.Cursor.NONE : null);
        }
        @Override public void captureUnavailable() {
            lookStatus.setText("Mouse look: cursor capture unavailable; looking stays relative");
        }
        @Override public void captureFallback(String reason) {
            if (captureFallbackLogged) return;
            captureFallbackLogged = true;
            WorldDiagnostics.warn("Mouse-look cursor capture", new IllegalStateException(reason));
        }
    });
    private final WorldTravelPanel travel = new WorldTravelPanel(this::goToBookmark, this::goToCoordinates);
    private final Button saveBookmark = new Button("Bookmark this view");
    private String buildLabel = "";
    // A validated target waiting for its tile's first verified seed; never applied elsewhere.
    private WorldBookmarks.Target pendingJump;
    private WorldBookmarks.Bookmark pendingBookmark;
    // Immutable rendered-surface heights of the visible CPU scene; streaming reads its controller.
    private TerrainGround ground = TerrainGround.empty();
    private final Button retryGpu = new Button("Retry GPU");
    private final Label gpuStatus = new Label();
    private final ProgressIndicator gpuActivity = activity("world-gpu-activity", 18);
    private TerrainScene gpuScene;
    private final ProgressIndicator textureActivity = activity("world-texture-activity", 18);
    private final Label textureStatus = new Label("Terrain textures not loaded");
    private final Button retryTextures = new Button("Retry textures");
    private final WorldTextureLoading textureLoading;
    private WorldTextureSnapshot textureSnapshot;
    private TerrainScene textureRequestedScene;
    private java.util.List<WorldFormats.Tile> sceneTiles = java.util.List.of();
    private long textureGeneration;
    private boolean textureSourceBusy, texturePendingFrame;
    private boolean currentMaterialsReady;
    private String currentMaterialIssue;
    private long loadedRevision, publishedLoadedRevision = -1, presentedTextureGeneration = -1;
    private final AtomicBoolean materialWakeNeeded = new AtomicBoolean();
    private boolean retirementWakePending, geometryPreparationWaiting;
    private int preparingAnchorX, preparingAnchorY;
    private TerrainRenderer.Scene presentedScene;
    private boolean gpuVisible;
    private final Button open = new Button("Open explorer"), back = new Button("Back to map");

    /**
     * A dungeon or raid to zone into: its map, its recorded link (null when the map has none) and
     * its recorded instance area resolved on the area's own map (null when none is recorded).
     */
    private record InstanceTarget(
            WorldService.MapInfo map,
            WorldInstanceLinks.Link link,
            WorldCurrentArea.Result area,
            long areaMap,
            String areaIssue) {}

    private java.util.function.Supplier<com.vibeminer.concepts.locations.BuildLocationMaps>
            locationMaps = () -> null;
    private WorldInstanceLinks instanceLinks;
    private Cancellation instanceLookup = new Cancellation();
    private final ComboBox<WorldInstanceLinks.Link> entrances = new ComboBox<>();
    private final Button enterInstance = new Button("Enter instance"),
            leaveInstance = new Button("Leave instance");
    private final Label instanceStatus = new Label("Reading recorded instance entrances…"),
            instanceArea = new Label();
    private double entranceX, entranceY;
    private final WorldInstanceSwitch<InstanceTarget> instances =
            new WorldInstanceSwitch<>(new InstanceHooks(), Platform::runLater);
    // An ADT-based instance streams like any map; Leave returns to this saved open-world view.
    private WorldBookmarks.Bookmark instanceReturn;
    private long instanceMap = -1;
    private boolean instanceOpenPending;
    private final ProgressIndicator catalogActivity = activity("world-catalog-activity", 18),
            localActivity = activity("world-local-activity", 18),
            firstFrameActivity = activity("world-first-frame-activity", 32);
    private final Label localStage = new Label();
    private final HBox localLoading = new HBox(8, localActivity, localStage);
    private final LegacyWorldActivity requests = new LegacyWorldActivity("world-requests", true),
            preparation = new LegacyWorldActivity("world-preparation", false);
    private CompletableFuture<Void> legacyRetirement = CompletableFuture.completedFuture(null);
    private volatile CompletableFuture<Void> cpuRetirement = CompletableFuture.completedFuture(null);
    private volatile java.io.IOException cpuRetirementFailure;
    private boolean legacyPaused, legacyResumeWaiting;
    private PendingLegacy<?> pendingLegacy;

    private static final class PendingLegacy<T> {
        final long selection;
        final Cancellation token;
        final java.util.function.Supplier<CompletableFuture<T>> action;
        final CompletableFuture<T> result = new CompletableFuture<>();
        PendingLegacy(long selection, Cancellation token,
                java.util.function.Supplier<CompletableFuture<T>> action) {
            this.selection = selection;
            this.token = token;
            this.action = action;
        }
    }
    private CompletableFuture<?> invocation, sceneTask;
    private long sceneGeneration;
    private boolean scenePreparing, waveActive, waveDirty;
    private PendingPrepared pendingPrepared;
    private GeometryJob geometryJob;
    private static final class GeometryJob {
        final long scene, request, loadedRevision;
        final int anchorX, anchorY;
        final boolean initialSeed;
        final Runnable ready;
        final AtomicReference<Prepared> result = new AtomicReference<>();
        volatile boolean canceled;
        volatile Throwable failure;
        GeometryJob(long scene, long request, long loadedRevision, int anchorX, int anchorY,
                boolean initialSeed, Runnable ready) {
            this.scene = scene; this.request = request; this.loadedRevision = loadedRevision;
            this.anchorX = anchorX; this.anchorY = anchorY;
            this.initialSeed = initialSeed; this.ready = ready;
        }
        void cancel() { canceled = true; result.set(null); }
    }
    private static final class PendingPrepared implements AutoCloseable {
        final Prepared value;
        final long scene, request, loadedRevision;
        final int anchorX, anchorY;
        final Runnable ready;
        boolean materialsStarted, materialsReady;
        WorldTextureSnapshot textures;
        String issue;

        PendingPrepared(Prepared value, long scene, long request, long loadedRevision,
                int anchorX, int anchorY, Runnable ready) {
            this.value = value;
            this.scene = scene;
            this.request = request;
            this.loadedRevision = loadedRevision;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.ready = ready;
        }

        WorldTextureSnapshot takeTextures() {
            var result = textures;
            textures = null;
            return result;
        }

        @Override public void close() {
            if (textures != null) textures.close();
            textures = null;
        }
    }

    private final LinkedHashMap<String, WorldService.LoadedTile> loaded = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private Cancellation token = new Cancellation();
    private boolean tokenUsed;
    private BuildWorld handle;
    private WorldService.Catalog catalog;
    private WorldService.Inventory inventory;
    private WorldFormats.Tile selected, origin;
    private TerrainRenderer.Camera camera;
    private TerrainRenderer.Scene terrainScene;
    private double originX, originY;
    private boolean explorer, loadingTile, catalogLoading;
    private volatile boolean closed;
    private boolean active = true;
    private long revision = -1, selection;
    private final AnimationTimer timer;
    private final AtomicLong frameGeneration = new AtomicLong();
    private final AtomicReference<Render> latest = new AtomicReference<>();
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final Object cpuWorkLock = new Object();
    private CpuWork inFlightCpu;
    private static final class CpuWork implements AutoCloseable {
        final Render render;
        boolean retired;
        CpuWork(Render render) { this.render = render; }
        @Override public void close() {
            render.close();
            retired = true;
        }
    }
    @FunctionalInterface
    interface CpuRenderer {
        TerrainRenderer.Frame render(TerrainRenderer.Scene scene, TerrainRenderer.Camera camera,
                int width, int height, java.util.List<com.vibeminer.rendering.world.TerrainTextureBinding> textures,
                java.util.function.BooleanSupplier canceled);
    }
    private final CpuRenderer cpuRenderer;
    private final java.util.function.Consumer<Runnable> cpuDelivery;
    private record CpuDelivery(long generation, long selection, long textureGeneration,
            boolean textured, TerrainRenderer.Frame frame) {}

    private record Prepared(
            TerrainRenderer.Scene scene, TerrainScene gpuScene,
            TerrainRenderer.Camera camera, double x, double y,
            java.util.List<WorldFormats.Tile> tiles, TerrainGround ground) {}

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
        var previousInvocation = invocation;
        invocation = null;
        if (previousInvocation != null) previousInvocation.cancel(false);
        requests.discardQueued();
        if (legacyPaused) {
            var pending = new PendingLegacy<T>(selection, token, action);
            if (pendingLegacy != null) pendingLegacy.result.cancel(false);
            pendingLegacy = pending;
            invocation = pending.result;
            resumePendingLegacy();
            return logged(pending.result.copy());
        }
        try {
            var cancel = token;
            tokenUsed = true;
            var call = requests.invoke(cancel, cancel::cancel, action);
            invocation = call;
            return logged(call);
        } catch (RuntimeException error) {
            return logged(CompletableFuture.failedFuture(error));
        }
    }

    /** Request failures stay visible in the application log even when the UI shows a summary. */
    private static <T> CompletableFuture<T> logged(CompletableFuture<T> call) {
        call.whenComplete((ignored, failure) -> {
            if (failure != null) WorldDiagnostics.request("World request failed", failure);
        });
        return call;
    }

    private void resumePendingLegacy() {
        if (legacyResumeWaiting) return;
        legacyResumeWaiting = true;
        CompletableFuture.allOf(legacyRetirement, streamingRetirement)
                .whenComplete((ignored, failure) -> Platform.runLater(() -> {
                    legacyResumeWaiting = false;
                    var pending = pendingLegacy;
                    pendingLegacy = null;
                    if (pending == null) return;
                    if (stale(pending.selection) || streamingMode || !active) {
                        pending.result.cancel(false);
                        return;
                    }
                    if (failure != null) {
                        pending.result.completeExceptionally(new WorldDiagnostics.RetirementBlocked(failure));
                        return;
                    }
                    try {
                        resumeLegacy();
                        runPendingLegacy(pending);
                    } catch (RuntimeException error) {
                        pending.result.completeExceptionally(error);
                    }
                }));
    }

    private <T> void runPendingLegacy(PendingLegacy<T> pending) {
        tokenUsed = true;
        var actual = requests.invoke(pending.token, pending.token::cancel, pending.action);
        actual.whenComplete((value, error) -> {
            if (error == null) pending.result.complete(value);
            else pending.result.completeExceptionally(error);
        });
    }

    private void cancelLegacyToken() {
        var previous = token;
        boolean used = tokenUsed;
        token = new Cancellation();
        tokenUsed = false;
        try {
            if (used) requests.stop(previous, previous::cancel);
        } catch (RuntimeException error) {
            // Retire every already-issued key; an unadmitted token grants no source credit.
            requests.pauseAndDrain();
            legacyPaused = true;
            legacyRetirement = CompletableFuture.failedFuture(error);
        }
        if (pendingLegacy != null) pendingLegacy.result.cancel(false);
        pendingLegacy = null;
    }

    private void pauseLegacy() {
        var source = handle;
        legacyPaused = true;
        var cpuFence = cpuRetirementFailure == null ? cpuRetirement
                : CompletableFuture.<Void>failedFuture(cpuRetirementFailure);
        legacyRetirement = CompletableFuture.allOf(requests.pauseAndDrain(),
                preparation.pauseAndDrain(), textureLoading.whenIdle(), cpuFence, areaDrain,
                gpu == null ? CompletableFuture.completedFuture(null) : gpu.retirement())
                .thenCompose(ignored -> source instanceof WorldService nativeWorld
                        ? nativeWorld.terrainActivityDrained()
                        : CompletableFuture.failedFuture(new IllegalStateException(
                                "Custom world provider has no native retirement fence")));
    }

    private void resumeLegacy() {
        if (!legacyPaused) return;
        requests.resume();
        preparation.resume();
        legacyPaused = false;
    }

    private record Render(
            long generation,
            long selection,
            TerrainRenderer.Scene scene,
            TerrainRenderer.Camera camera,
            int width,
            int height,
            WorldTextureSnapshot textures,
            long textureGeneration) implements AutoCloseable {
        @Override public void close() { if (textures != null) textures.close(); }
    }

    WorldView(BuildPreparation.Job job) {
        this(
                () -> job.work() instanceof NativePreparationWork w ? w.worldHandle() : null,
                () -> job.work() instanceof NativePreparationWork w ? w.revision() : 0,
                GpuPreviewService.application());
        buildLabel = job.build().version() + " · " + job.locale();
        locationMaps =
                () -> job.work() instanceof NativePreparationWork w ? w.locationMapsHandle() : null;
        input.pointer(robotPointer());
    }

    /**
     * Places the system cursor with the JavaFX robot for mouse-look capture. The position is never
     * read back: the input recognises the warp from the next mouse events. False when the robot is
     * unavailable; the input then keeps looks relative.
     */
    private static WorldExplorerInput.Pointer robotPointer() {
        return new WorldExplorerInput.Pointer() {
            private javafx.scene.robot.Robot robot;
            private boolean unavailable;

            @Override public boolean warp(double screenX, double screenY) {
                if (unavailable) return false;
                try {
                    if (robot == null) robot = new javafx.scene.robot.Robot();
                    robot.mouseMove(screenX, screenY);
                    return true;
                } catch (RuntimeException failed) {
                    unavailable = true;
                    WorldDiagnostics.warn("Mouse-look cursor capture is unavailable", failed);
                    return false;
                }
            }
        };
    }

    /** Shows and applies control preferences without saving them again. */
    void showControls(WorldControls controls) {
        showingControls = true;
        try {
            input.controls(controls);
            invertLook.setSelected(controls.invertY());
            lookSensitivity.setValue(controls.sensitivity());
        } finally {
            showingControls = false;
        }
    }

    private void controlsChanged() {
        if (showingControls) return;
        var next = input.controls().withSensitivity(lookSensitivity.getValue())
                .withInvertY(invertLook.isSelected());
        if (next.equals(input.controls())) return;
        controlsEdited = true;
        input.controls(next);
        controlsStore.save(next.encode());
    }

    /**
     * Uses the application's World store for bookmarks and controls; the view's own session
     * stores are replaced. Stored controls load off the FX thread and apply unless the user has
     * already changed a control in this view.
     */
    void bookmarks(WorldBookmarkStore store) {
        travel.store(store);
        if (store == null) return;
        controls(WorldControls.Store.of(store));
    }

    void controls(WorldControls.Store store) {
        controlsStore = java.util.Objects.requireNonNull(store);
        controlsEdited = false;
        store.load().whenComplete((text, failure) -> Platform.runLater(() -> {
            if (closed || controlsStore != store || controlsEdited) return;
            if (failure != null) WorldDiagnostics.warn("World controls could not be loaded", failure);
            else showControls(WorldControls.decode(text));
        }));
    }

    WorldView(
            java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions) {
        this(handles, revisions, null);
    }

    WorldView(java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions, GpuPreviewService gpuService) {
        this(handles, revisions, gpuService, TerrainRenderer::render, Platform::runLater);
    }

    WorldView(java.util.function.Supplier<BuildWorld> handles,
            java.util.function.LongSupplier revisions, GpuPreviewService gpuService,
            CpuRenderer cpuRenderer, java.util.function.Consumer<Runnable> cpuDelivery) {
        this.gpuService = gpuService;
        this.cpuRenderer = java.util.Objects.requireNonNull(cpuRenderer);
        this.cpuDelivery = java.util.Objects.requireNonNull(cpuDelivery);
        gpu = gpuService == null ? null : new TerrainGpuPreview(gpuService, Platform::runLater,
                update -> {
                    show(gpuActivity, update.busy());
                    gpuStatus.setText(update.message());
                    retryGpu.setDisable(update.busy() || update.ready());
                    acceptedGpuStatus(update);
                }, this::presentGpuFrame, this::requestFrame);
        textureLoading = new WorldTextureLoading(Platform::runLater, update -> {
            textureSourceBusy = update.busy();
            show(textureActivity, update.busy() || texturePendingFrame);
            textureStatus.setText(update.message());
            retryTextures.setDisable(update.busy() || texturePendingFrame || gpuScene == null);
        }, this::completedTextures, this::advanceMaterials);
        textureStatus.setId("world-texture-status");
        textureStatus.setWrapText(true);
        textureStatus.setTooltip(new javafx.scene.control.Tooltip(
                "Basic diffuse coverage preview with terrain tint; client height blending and layer sharpening are not evaluated."));
        retryTextures.setId("world-texture-retry");
        retryTextures.setDisable(true);
        retryTextures.setOnAction(event -> {
            textureLoading.hide();
            textureRequestedScene = null;
            maybeLoadTextures();
        });
        objectTextures.setId("world-object-textures");
        objectTextures.setFocusTraversable(false);
        objectTextures.getItems().setAll(
                com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.values());
        objectTextures.setValue(WorldStreamGpuOwner.IMAGE_DETAIL);
        objectTextures.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(
                    com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail detail) {
                return detail == null ? ""
                        : detail == com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail.HIGH
                                ? "Object textures: High" : "Object textures: Standard";
            }

            @Override
            public com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail fromString(
                    String text) {
                return null;
            }
        });
        objectTextures.setTooltip(new javafx.scene.control.Tooltip(
                "Per-tile texture budget for buildings and doodads. Standard keeps 1 Mi building"
                        + " pixels per tile; High doubles every pool, for GPUs with memory to spare."
                        + " Larger buildings get full resolution first; when a tile is over budget,"
                        + " textures use smaller mip levels instead of going missing. This session"
                        + " only: start with -Dvimi.world.objectTextures=high to open on High."));
        // Only the GPU stream prepares object textures.
        objectTextures.setDisable(gpu == null || !gpuEnabled.isSelected());
        gpuEnabled.selectedProperty().addListener(
                (o, was, on) -> objectTextures.setDisable(gpu == null || !on));
        objectTextures.valueProperty().addListener((o, previous, next) -> {
            if (next == null || next == previous || !streamingMode || streaming == null) return;
            // New budgets apply to newly prepared tiles: restart the stream to re-prepare them.
            retireStreaming();
            startStreaming();
        });
        gpuEnabled.setSelected(gpu != null);
        gpuEnabled.setDisable(gpu == null);
        gpuEnabled.setId("world-gpu-enabled");
        walkMode.setId("world-walk-mode");
        walkMode.setFocusTraversable(false);
        walkMode.setOnAction(e -> motionChanged());
        smoothMotion.setId("world-smooth-motion");
        smoothMotion.setFocusTraversable(false);
        smoothMotion.setSelected(true);
        smoothMotion.setOnAction(e -> motionChanged());
        lookSensitivity.setId("world-look-sensitivity");
        lookSensitivity.setFocusTraversable(false);
        lookSensitivity.setPrefWidth(110);
        lookSensitivity.setBlockIncrement(.001);
        lookSensitivity.setAccessibleText("Mouse-look sensitivity");
        lookSensitivity.valueChangingProperty().addListener((o, was, changing) -> {
            if (!changing) controlsChanged();
        });
        lookSensitivity.valueProperty().addListener((o, a, b) -> {
            if (!lookSensitivity.isValueChanging()) controlsChanged();
        });
        lookStatus.setId("world-look-status");
        invertLook.setId("world-invert-look");
        invertLook.setFocusTraversable(false);
        invertLook.setOnAction(e -> controlsChanged());
        motionStatus.setId("world-motion-status");
        saveBookmark.setId("world-save-bookmark");
        saveBookmark.setFocusTraversable(false);
        saveBookmark.setOnAction(e -> saveBookmark());
        gpuStatus.setId("world-gpu-status");
        retryGpu.setId("world-gpu-retry");
        gpuStatus.setWrapText(true);
        gpuStatus.setTooltip(new javafx.scene.control.Tooltip(
                "BASIC exterior objects: outdoor WMO exterior groups, their doodad sets and ADT doodads in "
                        + "a static bind pose, texture1 only with a fixed light. Opaque, alpha-key and "
                        + "alpha-blended materials are drawn; other materials and doodads over the "
                        + "per-tile caps are counted as not drawn. Interior groups are not drawn, but the doodads in "
                        + "them are, because group membership is not evaluated. Not client "
                        + "lighting or animation."));
        retryGpu.setDisable(true);
        gpuEnabled.setOnAction(e -> {
            if (instances.engaged()) return;
            if (streamingMode) {
                switchStreamingRendering();
                return;
            }
            if (explorer && gpuEnabled.isSelected() && gpuService != null
                    && handle instanceof WorldService && handle.terrainStreaming().isPresent()) {
                var previous = image.getImage();
                var retainedCamera = camera;
                openExplorer();
                camera = retainedCamera;
                image.setImage(previous);
                streamCamera();
                streamNeighborhood();
                return;
            }
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
            if (instances.engaged()) return;
            if (streamingMode) {
                retireStreaming();
                startStreaming();
                return;
            }
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
        entrances.setId("world-instance-entrances");
        entrances.setPromptText("Recorded instance entrances");
        entrances.setFocusTraversable(false);
        entrances.setOnShowing(e -> refreshEntrances());
        entrances.setCellFactory(list -> entranceCell());
        entrances.setButtonCell(entranceCell());
        entrances.valueProperty().addListener((o, a, b) -> enterInstance.setDisable(b == null));
        enterInstance.setId("world-instance-enter");
        enterInstance.setFocusTraversable(false);
        enterInstance.setDisable(true);
        enterInstance.setOnAction(e -> enterInstance(entrances.getValue()));
        leaveInstance.setId("world-instance-leave");
        leaveInstance.setFocusTraversable(false);
        leaveInstance.setOnAction(e -> leaveInstance());
        instanceStatus.setId("world-instance-status");
        instanceStatus.setWrapText(true);
        instanceStatus.setMinWidth(0);
        instanceArea.setId("world-instance-area");
        instanceArea.setWrapText(true);
        instanceStatus.setTooltip(new javafx.scene.control.Tooltip(
                "Each dungeon or raid map records a corpse position on its parent map (Map.Corpse"
                        + " and Map.CorpseMapID), which the client places at the instance entrance."
                        + " Entering switches this view to the instance map; Leave returns here."));
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
        viewport.getChildren().addAll(selectionLayer(), firstFrameActivity, minimap);
        StackPane.setAlignment(minimap, Pos.TOP_RIGHT);
        StackPane.setMargin(minimap, new Insets(12));
        VBox.setVgrow(maps, Priority.ALWAYS);
        heading.getStyleClass().add("h2");
        currentArea.setId("world-current-area");
        inspection.setId("world-inspection");
        inspection.setWrapText(true);
        inspection.setMinWidth(0);
        inspection.setMaxWidth(Double.MAX_VALUE);
        inspection.setTooltip(new javafx.scene.control.Tooltip("Rendered ADT terrain under the pointer,"
                + " the nearest drawn BASIC building, doodad or static creature model (exact triangles of"
                + " the uploaded objects, with their recorded transforms and source records), or a declared"
                + " NPC spawn marker. Water and live creatures are not picked; unknown facts are reported"
                + " as unknown."));
        currentArea.setMinWidth(0);
        currentArea.setMaxWidth(Double.MAX_VALUE);
        currentArea.setTooltip(new javafx.scene.control.Tooltip("Recorded ADT terrain area; WMO interior volumes are not evaluated."));
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
                    clearInstanceReturn();
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
                    input.keyPressed(e.getCode());
                    e.consume();
                });
        viewport.setOnKeyReleased(
                e -> {
                    input.keyReleased(e.getCode());
                    e.consume();
                });
        viewport.focusedProperty()
                .addListener(
                        (o, a, b) -> {
                            if (!b) clearKeys();
                        });
        viewport.setOnMousePressed(
                e -> {
                    viewport.requestFocus();
                    input.lookPressed(e.getButton(), e.getScreenX(), e.getScreenY());
                });
        viewport.setOnMouseReleased(e -> input.lookReleased(e.getButton()));
        viewport.setOnMouseClicked(
                e -> {
                    if (WorldExplorerInput.inspects(e.getButton(), e.isStillSincePress()))
                        inspect(e.getSceneX(), e.getSceneY());
                });
        // The view box is taken per move, so a window moved or resized mid-look warps to its centre.
        viewport.setOnMouseDragged(e -> input.lookMoved(e.getScreenX(), e.getScreenY(), viewBox()));
        viewport.setOnScroll(e -> {
            input.scroll(e.getDeltaY(), e.isShiftDown());
            e.consume();
        });
        viewport.widthProperty().addListener((o, a, b) -> requestFrame());
        viewport.heightProperty().addListener((o, a, b) -> requestFrame());
        timer =
                new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (!active || !explorer || instances.engaged()) {
                            // Inactive or switching instances: nothing is owed meanwhile.
                            input.idle();
                            stallWatchdog.poll(now, false, false);
                            return;
                        }
                        if (streamingMode) {
                            if (!viewportActive()) {
                                streamingPaused = true;
                                retireStreaming();
                                minimap.hide();
                                clearKeys();
                                input.idle();
                                return;
                            }
                            if (streamingPaused && streaming == null) {
                                if (gpuEnabled.isSelected()) startStreaming();
                                else switchStreamingRendering();
                            }
                            if (streaming != null) streaming.pulse();
                            pollStall(now);
                        }
                        if (camera == null) {
                            input.idle();
                            return;
                        }
                        if (gpu != null && !viewportActive()) {
                            minimap.hide();
                            retireTextures();
                            if (gpuVisible) {
                                retireStreaming();
                                gpu.hide();
                                gpuVisible = false;
                                show(gpuActivity, false);
                            }
                            clearKeys();
                            input.idle();
                            return;
                        }
                        if (gpu != null && !gpuVisible) {
                            gpuVisible = true;
                            if (streamingMode && streaming == null) startStreaming();
                            requestFrame();
                            loadNext();
                        }
                        if (terrainScene == null && !streamingMode) {
                            input.idle();
                            return;
                        }
                        input.pulse(now);
                    }
                };
        showInventory();
    }

    private void goToBookmark(WorldBookmarks.Bookmark bookmark) {
        if (catalog == null) {
            travel.status("Map records are not loaded yet.");
            return;
        }
        var refusal = new WorldBookmarks.Refusal[1];
        var map = WorldBookmarks.map(bookmark, catalog.maps(), refusal);
        if (map.isEmpty()) {
            travel.status(new WorldBookmarks.Resolution(refusal[0], null).explanation() + ".");
            return;
        }
        if (inventory != null && inventory.map().id() == map.get().id()) {
            travelTo(WorldBookmarks.resolve(bookmark, map.get(), inventory));
            return;
        }
        pendingBookmark = bookmark;
        if (!maps.getItems().contains(map.get())) {
            allMaps.setSelected(true);
            search.clear();
            filter();
        }
        travel.status("Loading the terrain inventory of " + map.get() + "…");
        if (maps.getSelectionModel().getSelectedItem() == map.get()) selectMap(map.get());
        else maps.getSelectionModel().select(map.get());
    }

    private void goToCoordinates(double[] position) {
        if (inventory == null || inventory.world() == null) {
            travel.status("Select a map and wait for its terrain inventory first.");
            return;
        }
        travelTo(WorldBookmarks.coordinates(inventory.map(), inventory, position));
    }

    private void travelTo(WorldBookmarks.Resolution resolution) {
        if (closed || stale(selection)) return;
        if (!resolution.usable()) {
            travel.status(resolution.explanation() + "; no jump was made.");
            return;
        }
        var target = resolution.target();
        if (inventory == null || inventory.map().id() != target.map().id()) {
            travel.status("The selected map changed; no jump was made.");
            return;
        }
        areaEntry = null;
        selected = target.tile();
        pendingJump = target;
        travel.status("Opening tile " + target.tile().key() + "; the position is checked against its loaded terrain…");
        openExplorer();
    }

    /**
     * Applies a pending jump to a freshly seeded camera only when the target lies inside the
     * verified tile maxima. Never lands below the rendered surface when its height is known.
     */
    private TerrainRenderer.Camera applyJump(TerrainRenderer.Camera seed, double maxX, double maxY,
            TerrainGround surface) {
        var jump = pendingJump;
        pendingJump = null;
        if (jump == null || seed == null) return seed;
        if (origin == null || !origin.equals(jump.tile())
                || !WorldBookmarks.within(maxX, maxY, jump.x(), jump.y())) {
            travel.status("Position lies outside the verified terrain tile; opened the tile's"
                    + " terrain entry instead.");
            return seed;
        }
        double height = surface.heightAt(jump.x(), jump.y());
        double z = jump.hasZ() ? jump.z() : Double.isNaN(height) ? seed.z() : height + 30;
        if (!Double.isNaN(height)) z = Math.max(z, height + ExplorerMotion.EYE_HEIGHT);
        travel.status(String.format(java.util.Locale.ROOT, "Arrived at x %.1f / y %.1f / altitude %.1f%s",
                jump.x(), jump.y(), z, Double.isNaN(height) ? " · terrain height not loaded yet" : ""));
        return new TerrainRenderer.Camera(jump.x(), jump.y(), Math.clamp(z, -1_000_000, 1_000_000),
                jump.yaw(), Math.clamp(jump.pitch(), -1.5, 1.5));
    }

    private void saveBookmark() {
        if (camera == null || inventory == null || origin == null) return;
        String area = currentArea.getText();
        String name = area.startsWith("Terrain area: ")
                ? area.substring("Terrain area: ".length()) : inventory.map() + " tile " + tileX() + "," + tileY();
        if (name.length() > WorldBookmarks.MAX_NAME) name = name.substring(0, WorldBookmarks.MAX_NAME);
        int tx = WorldBookmarks.tileIndex(camera.x()), ty = WorldBookmarks.tileIndex(camera.y());
        if (tx < 0 || ty < 0) {
            travel.status("This position is outside the 64×64 tile grid and cannot be bookmarked.");
            return;
        }
        var map = inventory.map();
        double yaw = Math.IEEEremainder(camera.yaw(), 2 * Math.PI);
        travel.save(new WorldBookmarks.Bookmark(name, map.id(),
                map.directory() == null ? "" : map.directory(), map.name() == null ? "" : map.name(),
                tx, ty, camera.x(), camera.y(), camera.z(), yaw, camera.pitch(), buildLabel));
        status.setText("Saved bookmark " + name + " · see Bookmarks & travel on the map page");
        viewport.requestFocus();
    }

    /** The viewport on screen, clipped to the visible area of the screen holding its centre. */
    private WorldExplorerInput.ViewBox viewBox() {
        var box = viewport.localToScreen(viewport.getLayoutBounds());
        if (box == null) return null;
        var view = new WorldExplorerInput.ViewBox(box.getMinX(), box.getMinY(), box.getWidth(), box.getHeight());
        var screens = javafx.stage.Screen.getScreensForRectangle(view.centerX(), view.centerY(), 1, 1);
        if (screens.isEmpty()) screens = javafx.stage.Screen.getScreensForRectangle(box.getMinX(), box.getMinY(), box.getWidth(), box.getHeight());
        if (screens.isEmpty()) return view;
        var visible = screens.getFirst().getVisualBounds();
        return view.clippedTo(visible.getMinX(), visible.getMinY(), visible.getMaxX(), visible.getMaxY());
    }

    private void clearKeys() {
        input.clear();
    }

    private TerrainGround currentGround() {
        if (!streamingMode) return ground;
        var controller = streaming;
        return controller == null ? TerrainGround.empty() : controller.ground();
    }

    private void motionChanged() {
        input.motion(walkMode.isSelected(), smoothMotion.isSelected());
        if (!walkMode.isSelected() || camera == null) groundStatus(ExplorerMotion.Ground.FREE);
        else groundStatus(Double.isNaN(currentGround().heightAt(camera.x(), camera.y()))
                ? ExplorerMotion.Ground.UNAVAILABLE : ExplorerMotion.Ground.FOLLOWING);
        if (explorer) viewport.requestFocus();
    }

    private void groundStatus(ExplorerMotion.Ground next) {
        motionStatus.setText(switch (next) {
            case FREE -> "Free flight · no collision";
            case FOLLOWING -> "Walking · eye "
                    + (int) ExplorerMotion.EYE_HEIGHT + " yd above rendered terrain · no collision";
            case UNAVAILABLE -> "Walking · no rendered terrain here; altitude held";
        });
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
                                                maps.setPlaceholder(new Label("Map records unavailable; use Reload to retry."));
                                                String blocked = WorldDiagnostics.retirementMessage(error);
                                                status.setText(blocked != null ? blocked
                                                        : "Map records unavailable; use Reload after"
                                                                + " preparation");
                                                return;
                                            }
                                            catalog = data;
                                            loadInstanceLinks();
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
        instances.abandon();
        clearInstanceReturn();
        cancelInstanceLookups();
        instanceLookup = new Cancellation();
        instanceLinks = null;
        resumeCamera = null;
        pendingJump = null;
        pendingBookmark = null;
        retireTextures();
        retireGpu();
        gpuScene = null;
        presentedScene = null;
        stopArea(true);
        areaEntry = null;
        selection++;
        cancelLegacyToken();
        catalog = null;
        catalogLoading = false;
        loadingTile = false;
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        inventory = null;
        loaded.clear();
        loadedRevision++;
        attempted.clear();
        explorer = false;
        streamingMode = false;
        timer.stop();
        camera = null;
        terrainScene = null;
        ground = TerrainGround.empty();
        replaceRender(null);
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
        if (spawns != null) spawns.suspend();
        resetInspection();
        retireTextures();
        currentArea.setText("Terrain area unavailable");
        audio.area(null, -1, null);
        minimap.hide();
        retireGpu();
        stopArea(false);
        selection++;
        cancelLegacyToken();
        catalogLoading = false;
        loadingTile = false;
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        clearKeys();
        timer.stop();
        frameGeneration.incrementAndGet();
        replaceRender(null);
        cancelPreparation();
        attempted.retainAll(loaded.keySet());
    }

    void deactivate() {
        active = false;
        // An entry still waiting for the open world to drain must not open the interior, take
        // the GPU or play instance audio in a hidden tab: cancel it. An open interior stays.
        // Before cancelRequests: a canceled entry resumes the world, and cancelRequests then
        // suspends what that resume restarted (spawns, timer) for the hidden tab.
        instances.cancelEntry();
        cancelRequests();
    }

    void activate() {
        active = true;
        if (spawns != null && explorer) spawns.resume();
        ensureLoaded();
        areaButtons();
        refreshEntrances();
        if (instances.engaged()) {
            // The interior keeps the view; deactivation stopped its audio, so restore it.
            if (instances.phase() == WorldInstanceSwitch.Phase.INSIDE) {
                instanceAudio(instances.target());
            }
            return;
        }
        if (explorer && inventory != null) {
            if (streamingMode && streaming == null) {
                if (gpuEnabled.isSelected()) {
                    startStreaming();
                    input.idle(); // owed look movement never lands as one jump on restart
                    timer.start();
                } else switchStreamingRendering();
            } else if (camera == null) openExplorer();
            else {
                input.idle(); // also drops owed look movement
                timer.start();
                requestFrame();
                loadNext();
            }
        }
    }

    private void selectMap(WorldService.MapInfo map) {
        // Choosing a map leaves any inline interior; it does not return to the open world.
        instances.abandon();
        if (map.id() != instanceMap) clearInstanceReturn();
        resumeCamera = null;
        pendingJump = null;
        if (pendingBookmark != null && pendingBookmark.mapId() != map.id()) pendingBookmark = null;
        retireTextures();
        stopArea(true);
        areaEntry = null;
        selection++;
        cancelLegacyToken();
        long request = selection;
        explorer = false;
        streamingMode = false;
        timer.stop();
        inventory = null;
        selected = null;
        camera = null;
        terrainScene = null;
        ground = TerrainGround.empty();
        loaded.clear();
        loadedRevision++;
        attempted.clear();
        loadingTile = false;
        busy(null);
        show(catalogActivity, false);
        show(firstFrameActivity, false);
        frameGeneration.incrementAndGet();
        replaceRender(null);
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
                                                String blocked = WorldDiagnostics.retirementMessage(error);
                                                String message = blocked != null ? blocked
                                                        : data != null && data.issue() != null
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
                                            var waiting = pendingBookmark;
                                            pendingBookmark = null;
                                            if (waiting != null && waiting.mapId() == data.map().id())
                                                Platform.runLater(() -> travelTo(
                                                        WorldBookmarks.resolve(waiting, data.map(), data)));
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
                                            open.setDisable(selected == null && !globalOnly(data));
                                            drawTiles();
                                            if (instanceOpenPending && data.map().id() == instanceMap) {
                                                instanceOpenPending = false;
                                                if (selected != null) {
                                                    Platform.runLater(() -> {
                                                        if (!stale(request)) openExplorer();
                                                    });
                                                }
                                            }
                                            status.setText((globalOnly(data)
                                                    ? "Global world-model map · Open explorer enters it inline on the GPU"
                                                    : data.world().tiles().isEmpty()
                                                    ? inventoryMessage.getText()
                                                    : data.world().tiles().size()
                                                            + " terrain tiles · Select a tile and"
                                                            + " open the explorer"
                                                            + (data.world().globalModel()
                                                                    ? " · Global world-model map"
                                                                    : ""))
                                                    + entranceNote(data.map()));
                                        }));
    }

    /** For a dungeon or raid map: where its recorded entrance is, or why there is none. */
    private String entranceNote(WorldService.MapInfo map) {
        var link = instanceLinks == null ? null : instanceLinks.link(map.id()).orElse(null);
        if (link == null) return "";
        if (link.entrance() == null) return " · Entrance: none, " + link.entranceIssue();
        var parent = catalog == null ? null : catalog.maps().stream()
                .filter(m -> m.id() == link.entrance().mapId()).findFirst().orElse(null);
        return String.format(java.util.Locale.ROOT, " · Entrance: %s at %.0f %.0f",
                parent == null ? "Map #" + link.entrance().mapId() : parent.toString(),
                link.entrance().x(), link.entrance().y());
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
        if (spawns != null) spawns.suspend();
        resumeCamera = null;
        minimap.hide();
        retireGpu();
        explorer = false;
        streamingMode = false;
        timer.stop();
        clearKeys();
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
                                tileInventory,
                                travel)));
        content.setBottom(new HBox(10, open));
        drawTiles();
        areaButtons();
    }

    private String emptyInventoryMessage() {
        return inventory.world().globalModel()
                ? "This map records a global world model instead of terrain tiles. Select Open explorer to enter it in this view (GPU interior)."
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
        if (stale(selection) || inventory == null || instances.engaged()) return;
        if (globalOnly(inventory)) {
            enterGlobal(inventory.map(), false);
            return;
        }
        if (selected == null) return;
        cancelRequests();
        explorer = true;
        origin = selected;
        loaded.clear();
        loadedRevision++;
        attempted.clear();
        camera = null;
        terrainScene = null;
        ground = TerrainGround.empty();
        gpuScene = null;
        sceneTiles = java.util.List.of();
        presentedScene = null;
        image.setImage(null);
        showExplorerLayout();
        resetInspection();
        status.setText("Loading terrain tile " + selected.key() + "…");
        if (spawns != null) spawns.resume();
        show(firstFrameActivity, true);
        input.idle(); // owed look movement never lands as one jump on restart
        timer.start();
        streamingMode = gpuEnabled.isSelected() && gpuService != null
                && handle instanceof WorldService && handle.terrainStreaming().isPresent();
        if (streamingMode) {
            pauseLegacy();
            startStreaming();
        }
        else loadTile(selected, true);
        viewport.requestFocus();
    }

    /** The explorer's viewport and controls; also restored when an inline instance is left. */
    private void showExplorerLayout() {
        content.setCenter(viewport);
        boolean adtInstance = instanceReturn != null && inventory != null
                && inventory.map().id() == instanceMap;
        // An entry abandoned while it showed "Cancel" (selectMap, invalidate, close) resets here.
        leaveInstance.setText("Leave instance");
        leaveInstance.setVisible(adtInstance);
        leaveInstance.setManaged(adtInstance);
        refreshEntrances();
        content.setBottom(
                new VBox(
                        8,
                        new HBox(
                                10,
                                back,
                                new Label(
                                        "WASD move · Space/E up · Q/X down · hold the right mouse button or"
                                                + " arrows to look · Shift faster · wheel zooms · Shift+wheel"
                                                + " changes speed · F walks · click inspects")),
                        new HBox(10, walkMode, smoothMotion, motionStatus, saveBookmark),
                        new HBox(8, new Label("Look sensitivity"), lookSensitivity, invertLook, lookStatus),
                        currentArea,
                        inspection,
                        audio.node(),
                        position,
                        new HBox(8, gpuEnabled, objectTextures, gpuActivity, retryGpu, gpuStatus),
                        new HBox(8, textureActivity, textureStatus, retryTextures),
                        new HBox(8, new Label("Instances"), entrances, enterInstance, leaveInstance,
                                instanceStatus)));
        if (spawns != null) ((VBox) content.getBottom()).getChildren().add(spawns.controls());
        if (roles != null) ((VBox) content.getBottom()).getChildren().add(roles.controls());
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
                                                String blocked = WorldDiagnostics.retirementMessage(error);
                                                status.setText(
                                                        blocked != null ? blocked
                                                                : data == null
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
                                                                .filter(t -> Math.abs(t.tile().x() - x) > 1 || Math.abs(t.tile().y() - y) > 1)
                                                                .max(Comparator.comparingInt(t -> Math.abs(t.tile().x() - x) + Math.abs(t.tile().y() - y)))
                                                                .orElseThrow();
                                                        loaded.remove(outgoing.tile().key());
                                                        attempted.remove(outgoing.tile().key());
                                                    }
                                                    loaded.put(tile.key(), data);
                                                    loadedRevision++;
                                                    updateCurrentArea();
                                                    waveDirty = true;
                                                } else {
                                                    attempted.remove(tile.key());
                                                }
                                                loadingTile = false;
                                                loadNext();
                                                return;
                                            }
                                            loaded.put(tile.key(), data);
                                            loadedRevision++;
                                            prepareScene(data.terrain(), () -> {
                                                loadingTile = false;
                                                busy(null);
                                                requestFrame();
                                                loadNext();
                                            });
                                        }));
    }

    private void cancelCandidate() {
        ++sceneGeneration;
        if (pendingPrepared != null) pendingPrepared.close();
        pendingPrepared = null;
        scenePreparing = false;
        if (geometryJob != null) geometryJob.cancel();
        geometryJob = null;
        var task = sceneTask;
        sceneTask = null;
        if (task != null) task.cancel(false);
        preparation.discardQueued();
        textureLoading.hide();
        textureSourceBusy = false;
    }

    private void cancelPreparation() {
        geometryPreparationWaiting = false;
        cancelCandidate();
        waveActive = false;
        waveDirty = false;
        var previousInvocation = invocation;
        invocation = null;
        if (previousInvocation != null) previousInvocation.cancel(false);
        requests.discardQueued();
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
        var heights = nativeScene != null
                ? nativeScene.tiles().stream().map(TerrainGround.Tile::of).toList()
                : tiles.stream().map(tile -> TerrainGround.Tile.of(
                        new TerrainScene.Tile(tile.tile().x(), tile.tile().y(), tile.terrain()))).toList();
        return new Prepared(cpu, nativeScene, camera, originX, originY,
                tiles.stream().map(WorldService.LoadedTile::tile).toList(), TerrainGround.of(heights));
    }

    private void prepareScene(WorldFormats.Terrain initial, Runnable ready) {
        cancelCandidate();
        scenePreparing = true;
        preparingAnchorX = camera == null ? origin.x() : tileX();
        preparingAnchorY = camera == null ? origin.y() : tileY();
        var job = new GeometryJob(sceneGeneration, selection, loadedRevision,
                preparingAnchorX, preparingAnchorY, initial != null && gpu != null, ready);
        geometryJob = job;
        var snapshot = java.util.List.copyOf(loaded.values());
        var areaPoint = initial != null && areaEntry != null && areaEntry.entryChunk() != null
                ? areaEntry.entryChunk().entryReference() : null;
        try {
            // Completed futures carry no full Prepared object. Retiring the mailbox also releases
            // geometry held by a queued FX delivery, without waiting for that callback or GC.
            sceneTask = preparation.invoke(() -> {
                try {
                    job.result.set(prepare(snapshot, initial, gpu != null, areaPoint));
                    if (closed || job.canceled) job.result.set(null);
                } catch (Throwable failure) {
                    job.failure = failure;
                } finally {
                    if (closed || job.canceled) job.result.set(null);
                    try { Platform.runLater(() -> completedGeometry(job)); }
                    catch (IllegalStateException stopped) { job.result.set(null); }
                }
                return CompletableFuture.completedFuture(null);
            });
        } catch (RuntimeException rejected) {
            job.failure = rejected;
            completedGeometry(job);
        }
    }

    private void completedGeometry(GeometryJob job) {
        if (closed || job.canceled || geometryJob != job || stale(job.request)
                || job.scene != sceneGeneration || !explorer) {
            job.cancel();
            return;
        }
        if (!candidateUseful(job.anchorX, job.anchorY)) {
            cancelCandidate();
            loadNext();
            return;
        }
        geometryJob = null;
        sceneTask = null;
        var prepared = job.result.getAndSet(null);
        if (job.failure != null || prepared == null) {
            scenePreparing = false;
            loadingTile = false;
            busy(null);
            show(firstFrameActivity, false);
            status.setText("Terrain preview preparation unavailable; readable terrain retained.");
            return;
        }
        if (job.initialSeed) {
            // Seed only navigation, then gather the full available first neighborhood. No
            // height-only child is launched while its material bundle is still incomplete.
            camera = resumeCamera == null ? prepared.camera() : resumeCamera;
            resumeCamera = null;
            originX = prepared.x();
            originY = prepared.y();
            camera = applyJump(camera, originX, originY, prepared.ground());
            scenePreparing = false;
            loadingTile = false;
            waveActive = true;
            waveDirty = true;
            job.ready.run();
            return;
        }
        pendingPrepared = new PendingPrepared(prepared, job.scene, job.request,
                job.loadedRevision, job.anchorX, job.anchorY, job.ready);
        if (prepared.gpuScene() == null) pendingPrepared.materialsReady = true;
        startCandidateMaterials();
        publishPrepared();
    }

    private boolean publishPrepared() {
        var pending = pendingPrepared;
        if (pending == null || !pending.materialsReady) return false;
        if (stale(pending.request) || pending.scene != sceneGeneration || !explorer) return false;
        if (!candidateUseful(pending.anchorX, pending.anchorY)) {
            cancelCandidate();
            loadNext();
            return false;
        }
        if (gpu != null && gpuEnabled.isSelected() && !gpu.canReplaceScene()) return false;
        pendingPrepared = null;
        scenePreparing = false;
        var prepared = pending.value;
        var preparedTextures = pending.takeTextures();
        releaseCurrentTextures();
        terrainScene = prepared.scene();
        ground = prepared.ground();
        gpuScene = prepared.gpuScene();
        sceneTiles = prepared.tiles();
        textureSnapshot = preparedTextures;
        textureRequestedScene = gpuScene;
        currentMaterialsReady = true;
        currentMaterialIssue = pending.issue;
        publishedLoadedRevision = pending.loadedRevision;
        geometryPreparationWaiting = loadedRevision != publishedLoadedRevision;
        texturePendingFrame = gpuScene != null;
        show(textureActivity, texturePendingFrame);
        textureStatus.setText(texturePendingFrame ? "Presenting prepared terrain…"
                : "Terrain texture loading unavailable");
        retryTextures.setDisable(true);
        if (gpuScene == null && gpu != null) {
            retireGpu();
            gpuStatus.setText("Software terrain · no loaded nearby tiles");
        }
        if (prepared.camera() != null) {
            camera = prepared.camera();
            originX = prepared.x();
            originY = prepared.y();
            camera = applyJump(camera, originX, originY, prepared.ground());
        }
        pending.close();
        pending.ready.run();
        return true;
    }

    private int tileX() {
        return origin.x() + (int) Math.floor((originX - camera.x()) / WorldFormats.TILE_SIZE);
    }

    private int tileY() {
        return origin.y() + (int) Math.floor((originY - camera.y()) / WorldFormats.TILE_SIZE);
    }

    private void loadNext() {
        if (streamingMode) {
            streamNeighborhood();
            return;
        }
        if (!active || !explorer || loadingTile || camera == null || inventory == null || !viewportActive()) return;
        int x = tileX(), y = tileY();
        attempted.retainAll(inventory.world().tiles().stream()
                .filter(t -> Math.abs(t.x() - x) <= 1 && Math.abs(t.y() - y) <= 1)
                .map(WorldFormats.Tile::key).collect(java.util.stream.Collectors.toSet()));
        if (!waveActive) {
            waveActive = true;
            waveDirty = loadedRevision != publishedLoadedRevision;
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
                loadedRevision++;
                updateCurrentArea();
                waveDirty = true;
            }
            if (waveDirty) {
                waveDirty = false;
                busy("Preparing nearby terrain and textures…");
                boolean represented = pendingPrepared != null && pendingPrepared.loadedRevision == loadedRevision
                        || geometryJob != null && geometryJob.loadedRevision == loadedRevision;
                geometryPreparationWaiting = !represented;
                scenePreparing = true;
                if (geometryJob == null && pendingPrepared == null) {
                    preparingAnchorX = tileX();
                    preparingAnchorY = tileY();
                }
                prepareStableCandidate();
            } else {
                busy(null);
                maybeLoadTextures();
            }
        }
    }

    private void replaceRender(Render next) {
        Render previous;
        synchronized (cpuWorkLock) { previous = latest.getAndSet(next); }
        if (previous != null) previous.close();
    }

    private CpuWork takeCpuWork() {
        synchronized (cpuWorkLock) {
            var next = latest.get();
            if (next == null) return null;
            // Allocate metadata before removing the queued owner, even if allocation fails.
            var work = new CpuWork(next);
            latest.set(null);
            inFlightCpu = work;
            return work;
        }
    }

    private boolean differentCpuOwner(Render work) {
        return work != null && (work.scene() != terrainScene
                || work.textureGeneration() != textureGeneration);
    }

    private void releaseCurrentTextures() {
        textureRequestedScene = null;
        textureSourceBusy = false;
        texturePendingFrame = false;
        currentMaterialsReady = false;
        currentMaterialIssue = null;
        ++textureGeneration;
        if (textureSnapshot != null) textureSnapshot.close();
        textureSnapshot = null;
        show(textureActivity, false);
        textureStatus.setText("Terrain textures not loaded");
        retryTextures.setDisable(true);
    }

    private void retireTextures() {
        cancelCandidate();
        releaseCurrentTextures();
    }

    private boolean candidateUseful(int anchorX, int anchorY) {
        return camera == null || terrainScene == null
                || Math.abs(tileX() - anchorX) <= 1 && Math.abs(tileY() - anchorY) <= 1;
    }

    private void abandonUnusableCandidate() {
        if (streamingMode) return;
        if (!scenePreparing || candidateUseful(preparingAnchorX, preparingAnchorY)) return;
        cancelCandidate();
        waveActive = false;
        loadNext();
    }

    private boolean materialAdmission() {
        synchronized (cpuWorkLock) {
            if (cpuRetirementFailure != null
                    || inFlightCpu != null && differentCpuOwner(inFlightCpu.render)
                    || differentCpuOwner(latest.get())) {
                materialWakeNeeded.set(true);
                return false;
            }
        }
        boolean accepted = presentedScene == terrainScene
                && presentedTextureGeneration == textureGeneration;
        if (gpu != null && gpuEnabled.isSelected() && gpu.ready()) {
            if (accepted) return true;
        } else if (gpu != null && !gpu.retirement().isDone()) {
            materialWakeNeeded.set(true);
            if (!retirementWakePending) {
                retirementWakePending = true;
                gpu.retirement().whenComplete((ignored, error) -> {
                    try {
                        Platform.runLater(() -> {
                            retirementWakePending = false;
                            advanceMaterials();
                        });
                    } catch (IllegalStateException stopped) {
                        // Toolkit teardown: candidate/current ownership has already been retired.
                    }
                });
            }
            return false;
        } else if (accepted) {
            return true;
        }
        if (!currentMaterialsReady && textureSnapshot == null && !rendering.get()) return true;
        materialWakeNeeded.set(true);
        return false;
    }

    private void prepareStableCandidate() {
        if (!geometryPreparationWaiting || geometryJob != null || pendingPrepared != null || loadingTile || waveActive
                || closed || !active || !explorer || !viewportActive()) return;
        if (textureLoading.hasActiveWork() || !materialAdmission()) return;
        geometryPreparationWaiting = false;
        prepareScene(null, () -> { busy(null); requestFrame(); loadNext(); });
    }

    private void advanceMaterials() {
        if (closed || !active || !explorer || !viewportActive()) return;
        prepareStableCandidate();
        startCandidateMaterials();
        if (!publishPrepared()) maybeLoadTextures();
    }

    private void startCandidateMaterials() {
        var pending = pendingPrepared;
        if (pending == null || pending.materialsReady || pending.materialsStarted) return;
        if (!materialAdmission()) return;
        pending.materialsStarted = true;
        textureLoading.request(handle, inventory.map(), pending.value.tiles(), pending.value.gpuScene());
    }

    private void maybeLoadTextures() {
        if (closed || !active || !explorer || scenePreparing || loadingTile || waveActive
                || handle == null || inventory == null || !viewportActive()) return;
        if (gpuScene == null) {
            currentMaterialsReady = true;
            return;
        }
        if (sceneTiles.isEmpty() || textureRequestedScene == gpuScene) return;
        if (loadedRevision != publishedLoadedRevision || !materialAdmission()) return;
        textureRequestedScene = gpuScene;
        texturePendingFrame = false;
        textureLoading.request(handle, inventory.map(), sceneTiles, gpuScene);
    }

    private void completedTextures(WorldTextureLoading.Result result) {
        var prepared = result.snapshot();
        if (closed || !active || !explorer || stale(selection)) {
            if (prepared != null) prepared.close();
            return;
        }
        if (!viewportActive()) {
            if (prepared != null) prepared.close();
            retireTextures();
            retireGpu();
            return;
        }
        var candidate = pendingPrepared;
        if (candidate != null && candidate.value.gpuScene() == result.scene()) {
            textureSourceBusy = false;
            candidate.materialsReady = true;
            candidate.issue = result.issue() == null ? null : "Terrain textures unavailable · height shading";
            if (prepared != null && prepared.atlasCount() > 0) {
                candidate.textures = prepared;
            } else {
                if (prepared != null) prepared.close();
                if (candidate.issue == null) candidate.issue = "Terrain textures unavailable · height shading";
            }
            publishPrepared();
            return;
        }
        if (result.scene() != gpuScene) {
            if (prepared != null) prepared.close();
            return;
        }
        textureSourceBusy = false;
        if (prepared == null && currentMaterialsReady) {
            // A retry failure does not discard an already qualified current appearance.
            show(textureActivity, texturePendingFrame);
            textureStatus.setText(result.issue());
            retryTextures.setDisable(texturePendingFrame);
            return;
        }
        ++textureGeneration;
        if (textureSnapshot != null) textureSnapshot.close();
        textureSnapshot = null;
        currentMaterialsReady = true;
        currentMaterialIssue = result.issue() == null ? null : "Terrain textures unavailable · height shading";
        if (prepared != null && prepared.atlasCount() > 0) {
            textureSnapshot = prepared;
        } else {
            if (prepared != null) prepared.close();
            if (currentMaterialIssue == null) currentMaterialIssue = "Terrain textures unavailable · height shading";
        }
        texturePendingFrame = true;
        show(textureActivity, true);
        textureStatus.setText("Presenting prepared terrain…");
        retryTextures.setDisable(true);
        requestFrame();
    }

    private void acceptedGpuStatus(TerrainGpuPreview.Status update) {
        if (update.ready() && gpu.usesTextures(textureSnapshot)) {
            presentedTextureGeneration = textureGeneration;
        }
        prepareStableCandidate();
        startCandidateMaterials();
        if (publishPrepared() || !update.ready()) return;
        if (!gpu.usesTextures(textureSnapshot)) {
            if (currentMaterialsReady) requestFrame();
        } else if (!scenePreparing && !textureSourceBusy) {
            finishGpuTextures(update.textures());
        }
    }

    private void finishGpuTextures(com.vibeminer.rendering.bgfx.TerrainTextureStatus textures) {
        texturePendingFrame = false;
        show(textureActivity, false);
        retryTextures.setDisable(gpuScene == null);
        textureStatus.setText(textures == null
                ? (currentMaterialIssue == null ? "Height shading" : currentMaterialIssue)
                : textures.coveredRects() == 0
                        ? "Terrain textures unavailable on GPU · height shading retained"
                        : "Basic terrain textures · " + textures.coveredRects() + "/"
                                + textures.totalRects() + " regions");
    }

    private void finishCpuTextures() {
        texturePendingFrame = false;
        show(textureActivity, false);
        retryTextures.setDisable(gpuScene == null);
        textureStatus.setText(textureSnapshot == null
                ? (currentMaterialIssue == null ? "Height shading" : currentMaterialIssue)
                : "Basic terrain textures · " + textureSnapshot.coveredChunks() + "/"
                        + textureSnapshot.totalChunks() + " regions");
    }

    private void updateCurrentArea() {
        // The instance owns the area and its audio while it is entered.
        if (instances.engaged()) return;
        if (camera==null || catalog==null || inventory==null) {
            currentArea.setText("Terrain area unavailable");
            audio.area(null, -1, null);
            return;
        }
        var area = com.vibeminer.concepts.world.WorldCurrentArea.locate(
                inventory.map().id(),camera.x(),camera.y(),loaded.values(),catalog.areaIndex());
        currentArea.setText(area.label());
        audio.area(handle, inventory.map().id(), area);
    }

    private void requestFrame() {
        if (streamingMode) {
            streamCamera();
            return;
        }
        if (!active || !explorer || camera == null || terrainScene == null || closed) return;
        if (publishPrepared()) return;
        if (stale(selection) || !viewportActive()) {
            minimap.hide();
            return;
        }
        prepareStableCandidate();
        startCandidateMaterials();
        maybeLoadTextures();
        updateCurrentArea();
        minimap.update(handle, inventory, origin, originX, originY,
                camera.x(), camera.y(), camera.yaw());
        double targetWidth = Math.max(1, viewport.getWidth()),
                targetHeight = Math.max(1, viewport.getHeight());
        double scale = Math.min(1, Math.min(800 / targetWidth, 500 / targetHeight));
        int w = Math.max(1, (int) (targetWidth * scale)),
                h = Math.max(1, (int) (targetHeight * scale));
        if (!currentMaterialsReady) return;
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
                        input.effectiveSpeed(),
                        loaded.size()));
        if (gpu != null && gpuEnabled.isSelected() && gpuScene != null) {
            if (!viewportActive()) {
                retireGpu();
                return;
            }
            gpuVisible = true;
            try {
                if (gpu.request(gpuScene, textureSnapshot, TerrainCamera.from(camera), w, h)) {
                    replaceRender(null);
                    return;
                }
            } catch (RuntimeException invalid) {
                WorldDiagnostics.warn("GPU terrain request failed; using software rendering", invalid);
                gpu.hide();
                show(gpuActivity, false);
                gpuStatus.setText("GPU terrain unavailable; using software rendering.");
            }
        }
        replaceRender(new Render(frame, selection, terrainScene, camera, w, h,
                textureSnapshot == null ? null : textureSnapshot.retain(), textureGeneration));
        kick();
    }

    private void switchStreamingRendering() {
        if (instances.engaged()) return;
        timer.stop();
        retireStreaming();
        show(gpuActivity, true);
        gpuStatus.setText("Stopping previous renderer before changing rendering mode…");
        if (renderingTransitionPending) return;
        renderingTransitionPending = true;
        streamingRetirement.whenComplete((ignored, failure) -> Platform.runLater(() -> {
            renderingTransitionPending = false;
            if (closed || !active || !explorer || !streamingMode) return;
            if (failure != null) {
                streamingUnavailable(failure);
                return;
            }
            if (gpuEnabled.isSelected()) {
                startStreaming();
                input.idle(); // owed look movement never lands as one jump on restart
                timer.start();
            } else {
                var previous = image.getImage();
                resumeCamera = camera;
                streamingMode = false;
                openExplorer();
                image.setImage(previous);
            }
        }));
    }

    private void startStreaming() {
        if (closed || !active || !explorer || !streamingMode || streaming != null
                || !gpuEnabled.isSelected() || stale(selection) || instances.engaged()) return;
        if (!viewportActive()) {
            streamingPaused = true;
            return;
        }
        // A left or abandoned interior must release its GPU request first (#259); its own
        // failure is reported by the switch and never blocks the open world.
        var interiorDrained = instances.drained().exceptionally(ignored -> null);
        if (!streamingRetirement.isDone() || !legacyRetirement.isDone() || !interiorDrained.isDone()) {
            if (streamingStartWaiting) return;
            streamingStartWaiting = true;
            show(gpuActivity, true);
            gpuStatus.setText("Waiting for previous World resources to retire…");
            CompletableFuture.allOf(streamingRetirement, legacyRetirement, interiorDrained)
                    .whenComplete((ignored, failure) -> Platform.runLater(() -> {
                streamingStartWaiting = false;
                if (closed || !active || !streamingMode || !explorer || streaming != null) return;
                if (failure == null) startStreaming();
                else streamingUnavailable(failure);
            }));
            return;
        }
        if (streamingRetirement.isCompletedExceptionally() || legacyRetirement.isCompletedExceptionally()) {
            streamingUnavailable(WorldDiagnostics.failureOf(
                    streamingRetirement.isCompletedExceptionally() ? streamingRetirement : legacyRetirement));
            return;
        }
        streamingPaused = false;
        streamingControl = 0;
        streamingPolicy = 1;
        streamingWidth = streamingHeight = 0;
        var request = new WorldStreamSourceController.Request(selection, handle, inventory.map(),
                inventory, origin, areaEntry == null || areaEntry.entryChunk() == null
                        ? null : areaEntry.entryChunk().entryReference(), catalog.areaIndex());
        var creatureSource = WorldStreamGpuOwner.CREATURES ? creatures : null;
        long creatureMap = inventory.map().id();
        var detail = objectTextures.getValue();
        WorldStreamSourceBridge.ExteriorPreparer preparer = creatureSource == null
                ? (source, scope, tile, cancellation) -> WorldStreamSourceBridge.prepareExterior(
                        source, scope, tile, cancellation, null, detail)
                : (source, scope, tile, cancellation) -> WorldStreamSourceBridge.prepareExterior(
                        source, scope, tile, cancellation,
                        (builder, owned, token) -> creatureSource.append(builder, creatureMap, owned, token),
                        detail);
        stallWatchdog.reset();
        streaming = new WorldStreamSourceController(request, scope -> {
            var owner = new WorldStreamGpuOwner(gpuService, scope);
            return new WorldStreamSourceController.GpuOwner() {
                public CompletableFuture<com.vibeminer.rendering.bgfx.WorldStreamTransport.Session> session() {
                    return owner.session();
                }
                public CompletableFuture<Void> closeAsync() { return owner.closeAsync(); }
            };
        }, streamingEvents, Platform::runLater, System::nanoTime, preparer);
        if (camera != null) {
            streamCamera();
            streamNeighborhood();
        }
        streamChanged();
    }

    private void streamingUnavailable(Throwable failure) {
        WorldDiagnostics.warn("World resources could not be confirmed retired", failure);
        show(gpuActivity, false);
        show(firstFrameActivity, false);
        gpuStatus.setText("World resources could not be confirmed retired ("
                + WorldDiagnostics.brief(failure) + "); reload the workspace.");
        retryGpu.setDisable(true);
    }

    private void retireStreaming() {
        var previous = streaming;
        streaming = null;
        stallWatchdog.reset();
        if (previous != null) streamingRetirement = previous.closeAsync();
    }

    private void streamChanged() {
        var controller = streaming;
        if (controller == null || !streamingMode || stale(selection) || !explorer) return;
        var snapshot = controller.snapshot();
        if (snapshot.phase() == WorldStreamSourceController.Phase.UNAVAILABLE
                && snapshot.seed() == null) {
            gpuEnabled.setSelected(false);
            switchStreamingRendering();
            return;
        }
        if (camera == null && snapshot.seed() != null) {
            camera = snapshot.seed().camera().software();
            originX = snapshot.seed().originX();
            originY = snapshot.seed().originY();
            camera = applyJump(camera, originX, originY, controller.ground());
            streamCamera();
            streamNeighborhood();
        }
        boolean busy = switch (snapshot.phase()) {
            case READY, UNAVAILABLE, FAILED, CLOSED -> false;
            default -> true;
        };
        show(gpuActivity, busy);
        show(textureActivity, busy);
        show(firstFrameActivity, busy && image.getImage() == null);
        streamStatus(snapshot);
        textureStatus.setText(snapshot.detail());
        retryTextures.setDisable(true);
        if (snapshot.area() != null) {
            currentArea.setText(snapshot.area().label());
            audio.area(inventory == null ? null : handle,
                    inventory == null ? -1 : inventory.map().id(), snapshot.area());
        }
        if (camera != null) position.setText(String.format(java.util.Locale.ROOT,
                "Tile %d,%d · x %.1f / y %.1f / altitude %.1f · %.0f yards/s · %d / %d tiles",
                tileX(), tileY(), camera.x(), camera.y(), camera.z(),
                input.effectiveSpeed(),
                snapshot.prepared(), snapshot.desired()));
        var pending = controller.takePresentation();
        if (pending.isEmpty()) return;
        var frame = pending.get();
        try {
            if (!viewportActive() || controller != streaming || !controller.mayPresent(frame)) return;
            if (!streamingImages.present(frame.width(), frame.height(), frame.pixels(),
                    () -> !stale(selection) && viewportActive() && controller == streaming,
                    () -> controller.presented(frame))) return;
            if (stallWatchdog.presented(frame.cameraRevision(), frame.sequence(), System.nanoTime())) {
                streamStatus(controller.snapshot());
            }
            show(firstFrameActivity, false);
            status.setText("Terrain explorer · persistent GPU terrain · static diagnostic water where qualified · buildings and collision are not rendered");
        } catch (RuntimeException failedUpload) {
            WorldDiagnostics.warn("GPU World frame upload failed", failedUpload);
            retireStreaming();
            show(firstFrameActivity, false);
            show(textureActivity, false);
            show(gpuActivity, false);
            gpuStatus.setText("GPU World frame upload failed; retry the viewer.");
            retryGpu.setDisable(false);
        } finally {
            controller.discard(frame);
        }
    }

    private void streamCamera() {
        if (streaming == null || camera == null || !active || !explorer || closed) return;
        if (stale(selection) || !viewportActive()) return;
        double targetWidth = Math.max(1, viewport.getWidth());
        double targetHeight = Math.max(1, viewport.getHeight());
        double scale = Math.min(1, Math.min(800 / targetWidth, 500 / targetHeight));
        int width = Math.max(1, (int) (targetWidth * scale));
        int height = Math.max(1, (int) (targetHeight * scale));
        if (streamingWidth != 0 && (width != streamingWidth || height != streamingHeight)) streamingPolicy++;
        streamingWidth = width;
        streamingHeight = height;
        streaming.camera(new com.vibeminer.rendering.bgfx.WorldStreamTransport.Camera(
                ++streamingControl, streamingPolicy, width, height, TerrainCamera.from(camera)));
        stallWatchdog.requested(streamingControl);
        minimap.update(handle, inventory, origin, originX, originY,
                camera.x(), camera.y(), camera.yaw());
    }

    /**
     * Frames are owed only while the stream is READY in a visible, current explorer. A stall logs
     * one bounded, redacted record and shows a non-blocking hint; Retry restarts the stream.
     */
    private void pollStall(long now) {
        var controller = streaming;
        var phase = controller == null ? null : controller.snapshot().phase();
        boolean eligible = controller != null && !streamingStartWaiting && !stale(selection)
                && viewportActive() && stallPhase(phase);
        boolean shown = stallWatchdog.alerted();
        var stall = stallWatchdog.poll(now, eligible,
                phase == WorldStreamSourceController.Phase.PRESENTING);
        if (stall != null) {
            // Redaction may wait up to 250 ms once per process for the host name; one stall
            // record per episode on FX is an accepted cost.
            WorldDiagnostics.stalled(WorldStallWatchdog.describe(stall) + "; "
                    + controller.stallSummary());
        }
        if (controller != null && (stall != null || shown != stallWatchdog.alerted())) {
            streamStatus(controller.snapshot());
        }
    }

    /** READY and PRESENTING owe frames on the helper's own draw cadence. */
    private static boolean stallPhase(WorldStreamSourceController.Phase phase) {
        return phase == WorldStreamSourceController.Phase.READY
                || phase == WorldStreamSourceController.Phase.PRESENTING;
    }

    /**
     * GPU status and Retry for a stream snapshot. The stall hint replaces the detail only while a
     * stall is alerted in a phase that owes frames, so FAILED or CLOSED details always show.
     */
    private void streamStatus(WorldStreamSourceController.Snapshot snapshot) {
        boolean busy = switch (snapshot.phase()) {
            case READY, UNAVAILABLE, FAILED, CLOSED -> false;
            default -> true;
        };
        boolean hint = stallWatchdog.alerted() && stallPhase(snapshot.phase());
        gpuStatus.setText(hint ? STALL_HINT : snapshot.detail());
        retryGpu.setDisable(busy && !hint);
    }

    private void streamNeighborhood() {
        if (streaming == null || camera == null || inventory == null) return;
        int x = tileX(), y = tileY();
        streaming.neighborhood(WorldStreamNeighborhood.select(
                inventory.world().tiles(), x, y, camera.yaw()));
    }

    private void retireGpu() {
        retireStreaming();
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
        var textures = gpu == null ? null : gpu.textureStatus();
        status.setText("Terrain explorer · " + (textures != null && textures.coveredRects() > 0
                ? "basic terrain textures" : "height shading")
                + " · buildings, water and collision are not rendered");
        return true;
    }

    private void queueCpuDelivery(Render work, TerrainRenderer.Frame frame) {
        // Queued delivery owns detached pixels and epoch metadata only. It never captures a Render,
        // geometry scene, atlas snapshot, or callback-scoped texture binding.
        var delivery = new CpuDelivery(work.generation(), work.selection(), work.textureGeneration(),
                work.textures() != null, frame);
        cpuDelivery.accept(() -> presentCpu(delivery));
    }

    private void presentCpu(CpuDelivery delivery) {
        if (stale(delivery.selection()) || !viewportActive()
                || frameGeneration.get() != delivery.generation()
                || textureGeneration != delivery.textureGeneration()) return;
        var frame = delivery.frame();
        if (frame == null) {
            show(firstFrameActivity, false);
            if (!textureSourceBusy) {
                texturePendingFrame = false;
                show(textureActivity, false);
                textureStatus.setText("Terrain texture frame unavailable · previous preview retained");
                retryTextures.setDisable(gpuScene == null);
            }
            status.setText("Terrain frame unavailable; previous image retained.");
            return;
        }
        var bitmap = new WritableImage(frame.width(), frame.height());
        bitmap.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                PixelFormat.getIntArgbInstance(), frame.pixels(), 0, frame.width());
        image.setImage(bitmap);
        // Matching frame and material epochs establish that this is the current scene; no old
        // scene reference needs to survive in the queued callback to establish that identity.
        presentedScene = terrainScene;
        presentedTextureGeneration = textureGeneration;
        if (gpu != null && !gpu.ready()) show(gpuActivity, false);
        show(firstFrameActivity, false);
        if (!textureSourceBusy && !scenePreparing) finishCpuTextures();
        advanceMaterials();
        status.setText("Terrain explorer · "
                + (delivery.textured() ? "basic terrain textures" : "height shading")
                + " · buildings, water and collision are not rendered"
                + (frame.budgetReached() ? " · rendering budget reached" : ""));
    }

    private void cpuFailure(Throwable failure) {
        if (cpuRetirementFailure == null) {
            cpuRetirementFailure = new java.io.IOException(failure.getClass().getName());
        }
    }

    private void reportCpuFailure() {
        try {
            Platform.runLater(() -> {
                if (!closed && !streamingMode) {
                    show(firstFrameActivity, false);
                    status.setText("Software renderer failed; reload the workspace before retrying.");
                }
            });
        } catch (IllegalStateException stopped) { /* Toolkit has already stopped. */ }
    }

    private void kick() {
        if (cpuRetirementFailure != null) {
            try { replaceRender(null); }
            catch (RuntimeException | Error cleanup) { cpuFailure(cleanup); }
            reportCpuFailure();
            return;
        }
        if (!rendering.compareAndSet(false, true)) return;
        var retired = new CompletableFuture<Void>();
        cpuRetirement = retired;
        Runnable render = () -> {
            try {
                CpuWork work;
                while ((work = takeCpuWork()) != null) {
                    var owner = work;
                    var current = owner.render;
                    try (owner) {
                        if (closed) continue;
                        java.util.function.BooleanSupplier canceled = () -> closed
                                || frameGeneration.get() != current.generation();
                        TerrainRenderer.Frame frame;
                        if (current.textures() == null) {
                            frame = cpuRenderer.render(current.scene(), current.camera(),
                                    current.width(), current.height(), java.util.List.of(), canceled);
                        } else {
                            var rendered = new TerrainRenderer.Frame[1];
                            current.textures().withBindings(bindings -> rendered[0] =
                                    cpuRenderer.render(current.scene(), current.camera(),
                                            current.width(), current.height(), bindings, canceled));
                            frame = rendered[0];
                        }
                        queueCpuDelivery(current, frame);
                    } catch (java.util.concurrent.CancellationException stopped) {
                        if (stopped.getClass() != java.util.concurrent.CancellationException.class
                                || stopped.getCause() != null || stopped.getSuppressed().length != 0) cpuFailure(stopped);
                    } catch (RuntimeException error) {
                        cpuFailure(error);
                        queueCpuDelivery(current, null);
                    } catch (Error fatal) {
                        cpuFailure(fatal);
                        throw fatal;
                    } finally {
                        synchronized (cpuWorkLock) {
                            if (owner.retired && inFlightCpu == owner) inFlightCpu = null;
                        }
                    }
                    if (cpuRetirementFailure != null) break;
                }
            } catch (RuntimeException | Error failure) {
                cpuFailure(failure);
            } finally {
                try {
                    if (cpuRetirementFailure != null) replaceRender(null);
                } catch (RuntimeException | Error cleanup) {
                    cpuFailure(cleanup);
                } finally {
                    // Complete the old ownership fence before admitting another worker. Nothing
                    // after this point closes a renderer, source handle or snapshot.
                    if (cpuRetirementFailure == null) retired.complete(null);
                    else retired.completeExceptionally(cpuRetirementFailure);
                    rendering.set(false);
                }
                if (cpuRetirementFailure != null) reportCpuFailure();
                else if (latest.get() != null && !closed) kick();
                if (materialWakeNeeded.getAndSet(false) && !closed) {
                    try { Platform.runLater(this::advanceMaterials); }
                    catch (IllegalStateException stopped) { /* Toolkit already retired. */ }
                }
            }
        };
        try {
            Thread.startVirtualThread(render);
        } catch (RuntimeException | Error failedStart) {
            cpuFailure(failedStart);
            try { replaceRender(null); }
            catch (RuntimeException | Error cleanup) { cpuFailure(cleanup); }
            finally {
                retired.completeExceptionally(cpuRetirementFailure);
                rendering.set(false);
            }
            reportCpuFailure();
        }
    }

    private void resetInspection() {
        pickGeneration++;
        objectSelection.select(null);
        inspection.setText(INSPECT_HINT);
    }

    /** The selection highlight, bound to the presented view and the resident pick tiles. */
    private WorldSelectionOverlay selectionLayer() {
        objectSelection.bind(this::presentedView, this::residentObjects, () -> {
            pickGeneration++;
            inspection.setText("Selection cleared · the selected object's tile is no longer resident");
        });
        return objectSelection;
    }

    private WorldSpawnOverlay.View presentedView() {
        if (closed || !active || !explorer || camera == null || inventory == null
                || !(image.getImage() instanceof javafx.scene.image.Image frame)) return null;
        return new WorldSpawnOverlay.View(camera, inventory.map().id(), frame.getWidth(), frame.getHeight());
    }

    /** Pick tiles of the objects the hot child draws now; none outside streaming. */
    private java.util.List<WorldObjectPicks.Tile> residentObjects() {
        var controller = streamingMode ? streaming : null;
        return controller == null ? java.util.List.of() : controller.objects();
    }

    /** Metadata-only listfile names for picked objects. FX thread, before or after activation. */
    void attachFileNames(java.util.function.LongFunction<String> names) {
        fileNames = java.util.Objects.requireNonNull(names);
    }

    /**
     * Inspects what lies under a scene point of the presented frame. A drawn spawn marker wins and
     * is described from its declared record on FX (no I/O); otherwise a CPU ray is cast against the
     * retained rendered-surface heights on the pick worker, and the latest click publishes.
     */
    private void inspect(double sceneX, double sceneY) {
        long request = ++pickGeneration;
        if (closed || !active || !explorer || camera == null || inventory == null
                || !(image.getImage() instanceof javafx.scene.image.Image frame)) {
            objectSelection.select(null);
            inspection.setText("Nothing to inspect until a terrain frame is shown");
            return;
        }
        var map = inventory.map();
        if (spawns != null) {
            var local = spawns.sceneToLocal(sceneX, sceneY);
            var picked = local == null ? java.util.Optional.<WorldSpawnOverlay.Picked>empty()
                    : spawns.pick(local.getX(), local.getY());
            if (picked.isPresent()) {
                var spawn = picked.get();
                objectSelection.select(null);
                var models = spawns.models();
                boolean resolving = models.isPresent() && spawn.model() == null;
                inspection.setText(WorldPick.describeSpawn(spawn.spawn(), spawn.npc(), map.name(), spawn.model(), resolving));
                if (resolving) {
                    inspectedSpawn = spawn;
                    inspectedMap = map.name();
                    inspectedRequest = request;
                    models.get().prioritize(spawn.spawn().npcId());
                }
                return;
            }
        }
        var local = viewport.sceneToLocal(sceneX, sceneY);
        var ray = local == null ? java.util.Optional.<WorldPick.Ray>empty()
                : WorldPick.ray(camera, frame.getWidth(), frame.getHeight(), viewport.getWidth(),
                        viewport.getHeight(), local.getX(), local.getY());
        if (ray.isEmpty()) {
            objectSelection.select(null);
            inspection.setText("Nothing picked · the pointer is outside the rendered frame");
            return;
        }
        var objects = residentObjects();
        var eye = com.vibeminer.rendering.world.TerrainCamera.from(camera);
        var names = fileNames;
        var surface = currentGround();
        long mapId = map.id(), chosen = selection;
        String mapName = map.name();
        var controller = streamingMode ? streaming : null;
        var tiles = streamingMode ? java.util.List.<WorldService.LoadedTile>of() : java.util.List.copyOf(loaded.values());
        var areaIndex = catalog == null ? null : catalog.areaIndex();
        inspection.setText("Inspecting…");
        try {
            picks.execute(() -> {
                String text;
                WorldObjectPicks.Selected object = null;
                try {
                    var hit = WorldPick.terrain(ray.get(), surface);
                    // The nearer of terrain and drawn objects wins, as under the depth test. With
                    // no terrain hit only the per-hit eye depth clips, as the shader's discard does.
                    object = WorldObjectPicks.pick(objects, eye, ray.get(),
                            hit.map(WorldPick.Hit::distance).orElse(Double.POSITIVE_INFINITY)).orElse(null);
                    if (object != null) {
                        text = WorldObjectPicks.describe(object, mapId, mapName, names);
                    } else if (hit.isEmpty()) {
                        text = WorldPick.describeMiss(surface.tiles() > 0);
                    } else {
                        var at = hit.get();
                        var area = controller != null ? controller.areaAt(at.x(), at.y())
                                : areaIndex == null
                                        ? new com.vibeminer.concepts.world.WorldCurrentArea.Result(
                                                com.vibeminer.concepts.world.WorldCurrentArea.State.UNAVAILABLE,
                                                com.vibeminer.concepts.world.WorldCurrentArea.Reason.NONE,
                                                0, java.util.List.of())
                                        : com.vibeminer.concepts.world.WorldCurrentArea.locate(
                                                mapId, at.x(), at.y(), tiles, areaIndex);
                        text = WorldPick.describeTerrain(at, mapId, mapName, area, objects.size());
                    }
                } catch (Throwable failure) {
                    object = null;
                    text = "Inspection failed: " + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage());
                }
                String result = text;
                var selected = object;
                Platform.runLater(() -> {
                    if (closed || request != pickGeneration || chosen != selection) return;
                    // A tile retired since the click no longer draws its object: never highlight it.
                    boolean resident = selected != null
                            && residentObjects().stream().anyMatch(tile -> tile == selected.tile());
                    inspection.setText(selected != null && !resident
                            ? result + " · its tile is no longer resident" : result);
                    objectSelection.select(resident ? selected : null);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            inspection.setText(INSPECT_HINT);
        }
    }

    /**
     * Supplies static creature models for declared spawns to every later streaming session's tile
     * objects; tiles prepared before this is attached keep their objects. FX thread.
     */
    void attachCreatures(WorldTileCreatures source) {
        if (creatures != null || closed) throw new IllegalStateException("Creature models already attached");
        creatures = java.util.Objects.requireNonNull(source);
    }

    /**
     * Mounts quest/service role markers on the minimap (never the 3D viewport: Questie records no
     * height). FX thread.
     */
    void attachRoles(WorldRoleOverlay overlay) {
        if (roles != null || closed) throw new IllegalStateException("Role overlay already attached");
        roles = java.util.Objects.requireNonNull(overlay);
        minimap.attachRoles(overlay);
    }

    /** The minimap, for tests. */
    WorldMinimap minimap() {
        return minimap;
    }

    /** Mounts declared NPC spawn markers over the presented explorer frame. FX thread. */
    void attachSpawns(WorldSpawnOverlay overlay) {
        if (spawns != null || closed) throw new IllegalStateException("Spawn overlay already attached");
        spawns = java.util.Objects.requireNonNull(overlay);
        viewport.getChildren().add(viewport.getChildren().indexOf(image) + 1, overlay);
        // A clicked marker whose model outcome was still resolving is re-described once it arrives.
        overlay.models().ifPresent(models -> models.listen(npcId -> {
            var pending = inspectedSpawn;
            if (closed || pending == null || pending.spawn().npcId() != npcId) return;
            if (inspectedRequest != pickGeneration) {
                inspectedSpawn = null;
                return;
            }
            models.cached(npcId).ifPresent(model -> {
                inspectedSpawn = null;
                inspection.setText(WorldPick.describeSpawn(pending.spawn(), pending.npc(), inspectedMap, model, false));
            });
        }));
        overlay.bind(() -> {
            if (closed || !active || !explorer || camera == null || inventory == null
                    || !(image.getImage() instanceof javafx.scene.image.Image frame)) return null;
            return new WorldSpawnOverlay.View(camera, inventory.map().id(), frame.getWidth(), frame.getHeight());
        });
    }

    // ---- Instances: recorded entrances and in-place zone-in (#259) ----------------------------

    /** A map that records one global world model and no terrain tiles (an interior map). */
    private static boolean globalOnly(WorldService.Inventory data) {
        return data != null && data.world() != null && data.world().globalModel()
                && data.world().tiles().isEmpty();
    }

    /** Reads the recorded instance links once per catalog, off the FX thread. */
    private void loadInstanceLinks() {
        instanceLinks = null;
        var source = handle;
        var cancel = instanceLookup;
        if (source == null) return;
        Thread.ofVirtual().name("world-instance-links").start(() -> {
            CompletableFuture<WorldInstanceLinks> call;
            try {
                call = source.instanceLinks(cancel);
            } catch (RuntimeException failed) {
                call = CompletableFuture.failedFuture(failed);
            }
            call.whenComplete((links, failure) -> Platform.runLater(() -> {
                if (closed || handle != source || cancel.isCanceled()) return;
                if (failure != null) WorldDiagnostics.request("Instance links failed", failure);
                var maps = catalog == null ? java.util.Set.<Long>of()
                        : catalog.maps().stream().map(WorldService.MapInfo::id)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                instanceLinks = failure != null || links == null
                        ? WorldInstanceLinks.unavailable("Recorded instance links could not be read")
                        : links.within(maps);
                refreshEntrances();
            }));
        });
    }

    /** Lists this map's recorded instance entrances, nearest to the camera first. */
    private void refreshEntrances() {
        var links = instanceLinks;
        var map = inventory == null ? null : inventory.map();
        if (links == null || map == null) {
            entrances.getItems().clear();
            instanceStatus.setText(links == null ? "Reading recorded instance entrances…" : "");
            return;
        }
        if (camera != null) {
            entranceX = camera.x();
            entranceY = camera.y();
        }
        var previous = entrances.getValue();
        var found = links.entrancesOn(map.id(), entranceX, entranceY);
        entrances.getItems().setAll(found);
        if (previous != null) {
            found.stream().filter(l -> l.mapId() == previous.mapId()).findFirst()
                    .ifPresent(entrances::setValue);
        }
        enterInstance.setDisable(entrances.getValue() == null);
        instanceStatus.setText(found.isEmpty()
                ? (links.links().isEmpty() && !links.issues().isEmpty()
                        ? links.issues().getFirst()
                        : "No recorded instance entrance on this map")
                : found.size() + (found.size() == 1 ? " recorded entrance" : " recorded entrances")
                        + " on this map (Map corpse positions)");
    }

    private javafx.scene.control.ListCell<WorldInstanceLinks.Link> entranceCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(WorldInstanceLinks.Link link, boolean empty) {
                super.updateItem(link, empty);
                setText(empty || link == null ? null : link + String.format(java.util.Locale.ROOT,
                        " · %.0f yd", WorldInstanceLinks.distance(link, entranceX, entranceY)));
            }
        };
    }

    /** Zone-in from the open world: reads the instance map's inventory, then picks the path. */
    private void enterInstance(WorldInstanceLinks.Link link) {
        if (link == null || closed || instances.engaged() || catalog == null) return;
        var map = catalog.maps().stream().filter(m -> m.id() == link.mapId()).findFirst()
                .orElse(null);
        if (map == null) {
            instanceStatus.setText(link.name() + " · Map #" + link.mapId()
                    + " is not in the readable World catalog; not entered.");
            return;
        }
        enterInstance.setDisable(true);
        instanceStatus.setText("Reading " + link.name() + "…");
        var source = handle;
        var cancel = instanceLookup;
        long request = selection;
        Thread.ofVirtual().name("world-instance-inventory").start(() -> {
            CompletableFuture<WorldService.Inventory> call;
            try {
                call = source.inventory(map, cancel);
            } catch (RuntimeException failed) {
                call = CompletableFuture.failedFuture(failed);
            }
            call.whenComplete((data, failure) -> Platform.runLater(() -> {
                if (closed) return;
                enterInstance.setDisable(entrances.getValue() == null);
                if (handle != source || cancel.isCanceled() || request != selection
                        || instances.engaged()) {
                    // The view moved on (tab, map or build change): nothing is being read now.
                    refreshEntrances();
                    return;
                }
                enterResolved(link, map, data, failure);
            }));
        });
    }

    private void enterResolved(WorldInstanceLinks.Link link, WorldService.MapInfo map,
            WorldService.Inventory data, Throwable failure) {
        if (failure != null || data == null || data.world() == null) {
            instanceStatus.setText(link.name() + ": its map inventory is unavailable"
                    + (data != null && data.issue() != null ? " (" + data.issue() + ")" : "")
                    + "; not entered.");
            return;
        }
        if (globalOnly(data)) {
            enterGlobal(map, explorer);
            return;
        }
        if (data.world().tiles().isEmpty()) {
            instanceStatus.setText(link.name()
                    + " records neither terrain tiles nor a global world model; not entered.");
            return;
        }
        // An ADT-based instance streams like any map, from its terrain tile nearest the grid
        // centre: no in-instance entry point is recorded.
        var back = currentBookmark();
        if (back == null) {
            instanceStatus.setText("This position cannot be returned to; not entered.");
            return;
        }
        instanceReturn = back;
        instanceMap = map.id();
        instanceOpenPending = true;
        if (!maps.getItems().contains(map)) {
            allMaps.setSelected(true);
            search.clear();
            filter();
        }
        status.setText("Entering " + link.name() + " · it streams like any map; Leave instance"
                + " returns to this view");
        if (maps.getSelectionModel().getSelectedItem() == map) selectMap(map);
        else maps.getSelectionModel().select(map);
    }

    /** Zone-in to a global-WMO map in place; Leave returns to the open world or the map page. */
    private void enterGlobal(WorldService.MapInfo map, boolean worldOpen) {
        if (instances.engaged()) return;
        if (instanceLinks == null) {
            instanceStatus.setText("Recorded instance links are still loading; try again in a"
                    + " moment.");
            status.setText(instanceStatus.getText());
            return;
        }
        if (gpuService == null) {
            instanceStatus.setText("Instance interiors render on the GPU, which is unavailable.");
            status.setText(instanceStatus.getText());
            return;
        }
        if (worldOpen && !streamingMode) {
            instanceStatus.setText("Turn on GPU terrain to enter an instance from the open world.");
            return;
        }
        instances.enter(target(map), worldOpen);
    }

    /** The instance's recorded link and area (Map.AreaTableID), resolved on the area's own map. */
    private InstanceTarget target(WorldService.MapInfo map) {
        var link = instanceLinks == null ? null : instanceLinks.link(map.id()).orElse(null);
        if (link == null) {
            return new InstanceTarget(map, null, null, -1,
                    "this map has no recorded dungeon or raid link (Map.InstanceType)");
        }
        if (link.areaId() == null) {
            return new InstanceTarget(map, link, null, -1, "Map.AreaTableID is unset");
        }
        var area = catalog == null ? null : catalog.areas().stream()
                .filter(a -> a.id() == link.areaId()).findFirst().orElse(null);
        if (area == null) {
            return new InstanceTarget(map, link, null, -1, "Map.AreaTableID " + link.areaId()
                    + " is not in the readable AreaTable records");
        }
        var resolved = WorldCurrentArea.resolve(area.mapId(), area.id(), catalog.areaIndex());
        return new InstanceTarget(map, link, resolved, area.mapId(),
                resolved.state() == WorldCurrentArea.State.KNOWN ? null
                        : "area " + area.id() + " has no readable hierarchy ("
                                + resolved.reason() + ")");
    }

    /** Plays the instance's recorded area audio; a target without one stops the tracks. */
    private void instanceAudio(InstanceTarget target) {
        if (target == null) {
            audio.area(null, -1, null);
            return;
        }
        boolean known = target.area() != null
                && target.area().state() == WorldCurrentArea.State.KNOWN;
        instanceArea.setText(known
                ? "Instance area: " + target.area().chain().getFirst()
                        + " (recorded Map.AreaTableID) · music and ambience from its recorded sound"
                        + " links"
                : "No instance area: " + target.areaIssue()
                        + "; no instance music or ambience is played");
        if (known) audio.area(handle, target.areaMap(), target.area());
        else audio.area(null, -1, null);
    }

    /** Cancellation callbacks may reach lower services; never run them on FX. */
    private void cancelInstanceLookups() {
        var lookups = instanceLookup;
        Thread.ofVirtual().name("world-instance-cancel").start(lookups::cancel);
    }

    private void clearInstanceReturn() {
        instanceReturn = null;
        instanceMap = -1;
        instanceOpenPending = false;
    }

    /** The current explorer camera as a return point, or null outside the tile grid. */
    private WorldBookmarks.Bookmark currentBookmark() {
        if (camera == null || inventory == null) return null;
        int tx = WorldBookmarks.tileIndex(camera.x()), ty = WorldBookmarks.tileIndex(camera.y());
        if (tx < 0 || ty < 0) return null;
        var map = inventory.map();
        try {
            return new WorldBookmarks.Bookmark("Instance return", map.id(),
                    map.directory() == null ? "" : map.directory(),
                    map.name() == null ? "" : map.name(), tx, ty, camera.x(), camera.y(),
                    camera.z(), Math.IEEEremainder(camera.yaw(), 2 * Math.PI),
                    Math.clamp(camera.pitch(), -1.5, 1.5), buildLabel);
        } catch (IllegalArgumentException outside) {
            return null;
        }
    }

    private void leaveInstance() {
        if (instances.engaged()) {
            instances.leave();
            return;
        }
        var back = instanceReturn;
        if (back == null) return;
        clearInstanceReturn();
        goToBookmark(back);
    }

    /** The World view's side of an inline global interior switch. */
    private final class InstanceHooks implements WorldInstanceSwitch.Hooks<InstanceTarget> {
        @Override
        public CompletableFuture<Void> retireWorld() {
            // Only Cancel is live while the open world drains: no control can restart it under
            // the instance or replace the instance audio.
            leaveInstance.setText("Cancel");
            leaveInstance.setVisible(true);
            leaveInstance.setManaged(true);
            content.setBottom(new VBox(8, new HBox(10, leaveInstance, instanceStatus),
                    audio.node()));
            timer.stop();
            clearKeys();
            minimap.hide();
            resetInspection();
            if (spawns != null) spawns.suspend();
            show(firstFrameActivity, false);
            retireStreaming();
            return streamingRetirement;
        }

        @Override
        public WorldInstanceSwitch.Interior openInterior(InstanceTarget target) {
            var view = new GlobalInteriorView(gpuService, () -> handle, locationMaps,
                    target.map().id(), target.map().toString());
            return new WorldInstanceSwitch.Interior() {
                @Override
                public javafx.scene.Node node() {
                    return view;
                }

                @Override
                public CompletableFuture<Void> closeAsync() {
                    return view.closeAsync();
                }
            };
        }

        @Override
        public void showInterior(InstanceTarget target, WorldInstanceSwitch.Interior interior) {
            leaveInstance.setText("Leave instance");
            leaveInstance.setVisible(true);
            leaveInstance.setManaged(true);
            content.setCenter(interior.node());
            content.setBottom(new VBox(8, new HBox(10, leaveInstance, instanceArea),
                    audio.node()));
            status.setText("Inside " + target.map() + " · Map #" + target.map().id()
                    + " · Leave instance returns to "
                    + (explorer && inventory != null ? inventory.map().toString() : "the map page"));
            if (interior.node() instanceof GlobalInteriorView view) view.focusViewport();
        }

        @Override
        public void audio(InstanceTarget target) {
            instanceAudio(target);
        }

        @Override
        public void resumeWorld(boolean worldOpen) {
            if (closed) return;
            leaveInstance.setText("Leave instance");
            leaveInstance.setVisible(false);
            leaveInstance.setManaged(false);
            if (!worldOpen || !explorer || inventory == null || camera == null) {
                showInventory();
                return;
            }
            showExplorerLayout();
            if (spawns != null) spawns.resume();
            status.setText("Back in " + inventory.map() + " · the open world resumes where you entered");
            input.idle();
            if (!active) return;
            input.idle(); // owed look movement never lands as one jump on restart
            timer.start();
            startStreaming();
            viewport.requestFocus();
        }

        @Override
        public void status(String message) {
            status.setText(message);
            instanceStatus.setText(message);
        }
    }


    @Override
    public void close() {
        instances.abandon();
        cancelInstanceLookups();
        closed = true;
        audio.close();
        resumeCamera = null;
        pendingJump = null;
        pendingBookmark = null;
        travel.close();
        if (spawns != null) spawns.close();
        objectSelection.close();
        if (creatures != null) creatures.close();
        picks.shutdownNow();
        streamingImages.clear();
        minimap.close();
        if (gpu != null) gpu.close();
        invalidate();
        textureLoading.close();
        requests.closeAsync();
        areaRequests.shutdown();
        areaCancellation.shutdown();
        preparation.closeAsync();
    }
}
