package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaNavigation;
import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldInstanceLinks;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.bgfx.WorldStreamTransport.ViewDistance;
import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.CollisionWorld;
import com.vibeminer.rendering.world.MovementParity;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.WalkController;
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
import javafx.scene.input.KeyCode;
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
    static final String INSPECT_HINT =
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
    // #381: native presentation into a child window, opt-in (-Dvimi.world.nativePresent=true,
    // Windows). Readback stays the default and the fallback: any failure turns it off for the view.
    private final boolean nativeRequested = WorldNativePresenter.requested();
    private boolean nativeStream, nativeFailed;
    private long nativeSequence;
    private final WorldNativeSurface nativeSurface = new WorldNativeSurface(viewport, image,
            node -> {
                // As for full screen: during an instance switch the switch owns the layout.
                if (this.explorer && !this.instances.engaged()) this.content.setCenter(node);
            }, new WorldNativePresenter(nativeRequested
                    ? WorldNativePresenter.user32() : WorldNativePresenter.unavailable()));
    // Viewport-only full screen (F11 / Alt+Enter) and the "hide HUD" toggle (Alt+Z).
    private final Label fullscreenStatus = new Label();
    // HUD over the viewport: Map button and map name, status rows, the map panel (M).
    // Its refocus is shared by every HUD widget (the minimap too); only while exploring.
    // The one "HUD hidden" state (Alt+Z or the hide HUD key): only hideHud() sets it.
    private final javafx.beans.property.BooleanProperty hudHidden =
            new javafx.beans.property.SimpleBooleanProperty(this, "hudHidden");
    private final WorldHud hud = new WorldHud(this::toggleMapPanel, () -> {
        if (this.explorer) focusViewport();
    }, hudHidden);
    private final Button panelBack = new Button();
    // HUD step 2: zone text and banner top-centre, coordinates pill bottom-right.
    // Both refocus through the HUD's one refocus, which acts only while exploring.
    private final WorldZoneText zoneText = new WorldZoneText(() -> this.leaveInstance.fire(), hud::refocus);
    private final WorldCoordinatesPill coordinates =
            new WorldCoordinatesPill(text -> this.coordinatesCopy.accept(text), hud::refocus);
    // Where a pill click copies to: the system clipboard, or a test's capture.
    private java.util.function.Consumer<String> coordinatesCopy = WorldCoordinatesPill::systemClipboard;
    // HUD step 5: the Escape menu; its pages hold the controls that used to be rows.
    private final WorldGameMenu menu = new WorldGameMenu(this::menuOpened, this::menuClosed,
            () -> this.leaveInstance.fire());
    private final javafx.scene.control.ToggleGroup layoutChoice = new javafx.scene.control.ToggleGroup();
    private final javafx.scene.control.RadioButton qwerty = new javafx.scene.control.RadioButton(
            WorldControls.Layout.QWERTY.label), azerty = new javafx.scene.control.RadioButton(
            WorldControls.Layout.AZERTY.label);
    private final CheckBox showCoordinates = new CheckBox("Show coordinates");
    private boolean escapeKeyHeld;
    private final WorldFullscreen fullscreen = new WorldFullscreen(viewport, node -> {
        // During an instance switch the switch owns the layout: never put the world back over it.
        if (this.explorer && !this.instances.engaged()) content.setCenter(node);
        applyHudHidden(); // the hint names the keys for the window again
        placeToasts();
    }, this::layoutKeys, fullscreenStatus);
    // Layout toggles fire once per physical press: held keys repeat KEY_PRESSED.
    private boolean fullscreenKeyHeld, hideHudKeyHeld;
    private final Button fullscreenButton = new Button("Full screen (F11)");
    private final Button hudButton = new Button(hideHudButton("H"));
    private final Label hudHint = new Label(hudHint("H", false));
    // The keys bound to HIDE_HUD ("H" by default), for the hint and the button; null when unbound.
    private String hideHudKey = "H";
    private SplitPane split;
    private javafx.scene.Node sidebarPane;
    private double sidebarDivider = .23;
    private final WorldMinimap minimap = new WorldMinimap();
    // The last pick as a card over the viewport (HUD step 6); the inspection label keeps its text.
    private final WorldInspectCard inspectCard = new WorldInspectCard();
    // Transient notices over the viewport (HUD step 6); first-run help is shown once per view.
    private final WorldToasts toasts = new WorldToasts();
    // The toasts' layer sits above the whole explorer content (viewport or interior view), so a
    // swap of the centre never hides them; in full screen they move onto the viewport.
    private final StackPane toastLayer = new StackPane();
    private boolean helpToastShown, streamFailedToast, stallToast;
    // Typed render and texture health for the status chips (never parsed from the status text).
    private WorldStatusIcons.Render renderHealth = WorldStatusIcons.Render.GPU;
    private WorldStatusIcons.Texture textureHealth = WorldStatusIcons.Texture.READY;
    // Where viewport screenshots go: the data directory's screenshots folder; null without one.
    private java.nio.file.Path screenshots;
    // Opens a folder in the desktop's file manager; tests replace it.
    private java.util.function.Consumer<java.nio.file.Path> folderOpener = this::openFolder;
    // Starts the screenshot writer off FX; tests replace it to make the start fail.
    private java.util.function.Consumer<Runnable> screenshotWriter =
            save -> Thread.ofVirtual().name("world-screenshot").start(save);
    // One screenshot in flight at a time (each holds a full-frame copy); FX-owned.
    private boolean screenshotSaving;
    /** The first-run help is long: it stays up longer than an event toast. */
    static final long HELP_TOAST_NANOS = 10_000_000_000L;
    private WorldSpawnOverlay spawns;
    private WorldRoleOverlay roles;
    // Highlight of the picked object; FX overlay only, cleared by terrain, sky and marker clicks.
    private final WorldSelectionOverlay objectSelection = new WorldSelectionOverlay();
    // Metadata-only listfile names for picked objects; read on the pick worker, null when unknown.
    private java.util.function.LongFunction<String> fileNames = id -> null;
    private WorldTileCreatures creatures;
    // The walking body: its display models, its built model once asked for, and the component.
    private java.util.function.Supplier<com.vibeminer.concepts.models.BuildDisplayModels> bodyDisplays;
    private CompletableFuture<WorldBodyModel.Built> bodyModel;
    // Set on the first switch into a body mode: the body is built and sent only from then on.
    private boolean bodyWanted;
    private volatile com.vibeminer.concepts.models.BuildDisplayModels bodyComponent;
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
    private final WorldResizeDebounce streamingResize = new WorldResizeDebounce();
    // Cameras of recent stream requests, and the camera of the streamed frame on screen; overlays
    // and picking use it so they stay on the drawn terrain while the live camera runs ahead.
    private final WorldPresentedCamera presentedCamera = new WorldPresentedCamera(
            reason -> WorldDiagnostics.warn("World overlay camera", new IllegalStateException(reason)));
    private CompletableFuture<Void> streamingRetirement = CompletableFuture.completedFuture(null);
    private final CheckBox gpuEnabled = new CheckBox("GPU terrain");
    // Object texture budget tier (#267); a change re-prepares the streamed tiles' objects.
    private final javafx.scene.control.ChoiceBox<
                    com.vibeminer.concepts.world.WorldExteriorObjects.ImageDetail>
            objectTextures = new javafx.scene.control.ChoiceBox<>();
    // View distance (#173): remembered with the controls; a change restreams at the new size.
    private final javafx.scene.control.ChoiceBox<ViewDistance> viewDistance =
            new javafx.scene.control.ChoiceBox<>();
    // The distance the current stream controller was started with.
    private ViewDistance streamingDistance = WorldStreamGpuOwner.VIEW_DISTANCE;
    // Free camera when neither is ticked; at most one of the two is.
    private final CheckBox walkMode = new CheckBox("Walk with collision (F)");
    private final CheckBox flyMode = new CheckBox("Fly with collision (G)");
    private final CheckBox smoothMotion = new CheckBox("Smooth motion");
    private final javafx.scene.control.Slider lookSensitivity = new javafx.scene.control.Slider(
            WorldControls.MIN_SENSITIVITY, WorldControls.MAX_SENSITIVITY, WorldControls.DEFAULT_SENSITIVITY);
    private final CheckBox invertLook = new CheckBox("Invert mouse Y");
    private final javafx.scene.control.ChoiceBox<WorldControls.Speed> flightSpeed =
            new javafx.scene.control.ChoiceBox<>(javafx.collections.FXCollections.observableArrayList(
                    WorldControls.Speed.values()));
    private final Button keyBindings = new Button("Keys…");
    private final Label keyHelp = new Label(WorldControls.DEFAULTS.help());
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
        @Override public boolean idleFrames() { return viewport.isFocused() && viewportActive(); }
        @Override public void groundStatus(ExplorerMotion.Ground ground) { WorldView.this.groundStatus(ground); }
        @Override public void toggleWalk() {
            walkMode.setSelected(!walkMode.isSelected());
            flyMode.setSelected(false);
            motionChanged();
        }
        @Override public void toggleFly() {
            boolean fly = !flyMode.isSelected();
            flyMode.setSelected(fly);
            walkMode.setSelected(!fly);
            motionChanged();
        }
        @Override public CollisionWorld collision() { return currentCollision(); }
        @Override public void bodyStatus(WorldExplorerInput.Mode mode, WalkController.Status status) {
            WorldView.this.bodyStatus(mode, status);
        }
        @Override public void command(WorldControls.Action action) { WorldView.this.command(action); }
        @Override public void requestFrame() { WorldView.this.requestFrame(); }
        @Override public void abandonUnusableCandidate() { WorldView.this.abandonUnusableCandidate(); }
        @Override public void loadNext() { WorldView.this.loadNext(); }
        @Override public void cameraAtRest() { WorldView.this.cameraAtRest(); }
        @Override public void cursorHidden(boolean hidden) {
            viewport.setCursor(hidden ? javafx.scene.Cursor.NONE : null);
        }
        @Override public void controlsChanged(WorldControls controls) {
            showControls(controls);
            controlsStore.save(controls.encode());
        }
        @Override public void captureUnavailable() {
            lookStatus.setText("Mouse look: cursor capture unavailable; looking stays relative");
            toasts.show(lookStatus.getText());
        }
        @Override public void captureFallback(String reason) {
            if (captureFallbackLogged) return;
            captureFallbackLogged = true;
            WorldDiagnostics.warn("Mouse-look cursor capture", new IllegalStateException(reason));
        }
    });
    private final WorldTravelPanel travel = new WorldTravelPanel(this::goToBookmark, this::goToCoordinates);
    // HUD step 2: status icons bottom-left; the existing controls stay their models.
    private ExplorerMotion.Ground motionGround = ExplorerMotion.Ground.FREE;
    private final WorldStatusIcons icons = new WorldStatusIcons(new WorldStatusIcons.Actions() {
        @Override public void toggleWalk() { walkMode.fire(); }
        @Override public void nextSpeed() {
            var all = WorldControls.Speed.values();
            var now = flightSpeed.getValue();
            flightSpeed.setValue(all[now == null ? 0 : (now.ordinal() + 1) % all.length]);
        }
        @Override public void toggleSmooth() { smoothMotion.fire(); }
        @Override public void toggleMusic() { audio.music().fire(); }
        @Override public void toggleAmbience() { audio.ambience().fire(); }
        @Override public void volume(boolean music, int steps) {
            var slider = audio.volume(music ? com.vibeminer.concepts.world.WorldAreaAudio.Kind.MUSIC : com.vibeminer.concepts.world.WorldAreaAudio.Kind.AMBIENCE);
            slider.setValue(Math.clamp(slider.getValue() + steps * 5.0, slider.getMin(), slider.getMax()));
        }
        @Override public void nextTime() {
            var all = WorldAudioDirector.Slot.values();
            var now = audio.variant().getValue();
            audio.variant().setValue(all[now == null ? 0 : (now.ordinal() + 1) % all.length]);
        }
        // Each detail opens where it lives: render and texture on the developer overlay's Render
        // and Stream pages, tracking in the minimap's tracking popup, mouse on the Controls page.
        @Override public void details(String icon) {
            switch (icon) {
                case "render" -> devOverlay.show(WorldDevReport.Page.RENDER);
                case "texture" -> devOverlay.show(WorldDevReport.Page.STREAM);
                case "tracking" -> {
                    // The minimap key hid the cluster: show it again and open its popup.
                    if (minimap.userHidden()) {
                        minimap.toggleShown();
                        if (minimap.shown() && !minimap.tracking().showing()) minimap.toggleTracking();
                    } else if (minimap.shown()) minimap.toggleTracking();
                    else menu.open(WorldGameMenu.Page.DISPLAY);
                }
                default -> menu.open(WorldGameMenu.Page.CONTROLS);
            }
        }
    }, this::focusViewport);
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
    // F3 developer overlay over the viewport; it reads the status labels above and fires Retry.
    private final WorldDevOverlay devOverlay;
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
    // Volatile: creature preparation reads it off the FX thread to carry idles near it (#384).
    private volatile TerrainRenderer.Camera camera;
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
            if (!viewDistanceForced()) viewDistance.setValue(controls.viewDistance());
            keyHelp.setText(controls.help());
            showLayoutChoice(controls);
            hud.mapKey(mapKey(controls));
            hideHudKey = boundKeys(controls, WorldControls.Action.HIDE_HUD);
            hudButton.setText(hideHudButton(hideHudKey));
            hudHint.setText(hudHint(hideHudKey, fullscreen.active()));
            devOverlay.key(controls.byAction().get(WorldControls.Action.DEV_OVERLAY).stream()
                    .map(javafx.scene.input.KeyCode::getName)
                    .collect(java.util.stream.Collectors.joining("/")));
            invertLook.setSelected(controls.invertY());
            flightSpeed.setValue(controls.speed());
            lookSensitivity.setValue(controls.sensitivity());
        } finally {
            showingControls = false;
        }
    }

    private void controlsChanged() {
        if (showingControls) return;
        var speed = flightSpeed.getValue();
        var next = input.controls().withSensitivity(lookSensitivity.getValue())
                .withInvertY(invertLook.isSelected())
                .withSpeed(speed == null ? input.controls().speed() : speed);
        if (next.equals(input.controls())) return;
        controlsEdited = true;
        input.controls(next);
        controlsStore.save(next.encode());
    }

    /** Whether {@code -Dvimi.world.viewDistance=far} forces FAR for this session. */
    private static boolean viewDistanceForced() {
        return WorldStreamGpuOwner.VIEW_DISTANCE == ViewDistance.FAR;
    }

    private void viewDistanceSetup(boolean gpuAvailable) {
        viewDistance.setId("world-view-distance");
        viewDistance.setFocusTraversable(false);
        viewDistance.getItems().setAll(ViewDistance.values());
        viewDistance.setValue(WorldStreamGpuOwner.VIEW_DISTANCE);
        viewDistance.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ViewDistance distance) {
                return distance == null ? "" : distance == ViewDistance.FAR
                        ? "View distance: Far (5 x 5 tiles)" : "View distance: Near (3 x 3 tiles)";
            }

            @Override
            public ViewDistance fromString(String text) {
                return null;
            }
        });
        viewDistance.setTooltip(new javafx.scene.control.Tooltip(viewDistanceForced()
                ? "Started with -Dvimi.world.viewDistance=far: Far for this session."
                : "Tiles kept loaded around the camera. Near keeps 3 x 3; Far keeps 5 x 5, so the"
                        + " ground reaches about twice as far, for about 76 MiB more GPU memory"
                        + " for terrain plus the extra buildings, and a longer first fill."));
        viewDistance.setDisable(!gpuAvailable || !gpuEnabled.isSelected() || viewDistanceForced());
        gpuEnabled.selectedProperty().addListener((o, was, on) ->
                viewDistance.setDisable(!gpuAvailable || !on || viewDistanceForced()));
        viewDistance.valueProperty().addListener((o, previous, next) -> {
            if (next == null || next == previous) return;
            if (!showingControls) {
                var controls = input.controls().withViewDistance(next);
                if (!controls.equals(input.controls())) {
                    controlsEdited = true;
                    input.controls(controls);
                    controlsStore.save(controls.encode());
                }
            }
            if (!streamingMode || streaming == null || next == streamingDistance) return;
            // Every slot count is fixed per session: restart the stream at the new size.
            retireStreaming();
            startStreaming();
        });
    }

    /** Proof observation: the view distance the current stream was started with. */
    ViewDistance streamingViewDistance() {
        return streamingDistance;
    }

    /**
     * Uses the application's World store for bookmarks and controls; the view's own session
     * stores are replaced. Stored controls load off the FX thread and apply unless the user has
     * already changed a control in this view.
     */
    void bookmarks(WorldBookmarkStore store) {
        travel.store(store);
        screenshots = store == null ? null : store.screenshots();
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
                    // The legacy preview: ready is fine; settled but not ready is a failure.
                    if (!update.busy()) renderHealth = update.ready() ? WorldStatusIcons.Render.GPU
                            : WorldStatusIcons.Render.FAILED;
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
        viewDistanceSetup(gpu != null);
        gpuEnabled.setSelected(gpu != null);
        gpuEnabled.setDisable(gpu == null);
        gpuEnabled.setId("world-gpu-enabled");
        walkMode.setId("world-walk-mode");
        walkMode.setFocusTraversable(false);
        walkMode.setOnAction(e -> {
            if (walkMode.isSelected()) flyMode.setSelected(false);
            motionChanged();
        });
        flyMode.setId("world-fly-mode");
        flyMode.setFocusTraversable(false);
        flyMode.setOnAction(e -> {
            if (flyMode.isSelected()) walkMode.setSelected(false);
            motionChanged();
        });
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
        flightSpeed.setId("world-flight-speed");
        flightSpeed.setFocusTraversable(false);
        flightSpeed.setAccessibleText("Flight speed preset");
        flightSpeed.setValue(WorldControls.DEFAULTS.speed());
        flightSpeed.setOnAction(e -> {
            controlsChanged();
            if (explorer) focusViewport();
        });
        keyBindings.setId("world-key-bindings-open");
        keyBindings.setFocusTraversable(false);
        keyBindings.setOnAction(e -> {
            clearKeys();
            new WorldKeyBindings(input.controls())
                    .edit(keyBindingsOwner())
                    .ifPresent(edited -> {
                        if (!edited.equals(input.controls())) {
                            showControls(edited);
                            controlsStore.save(edited.encode());
                        }
                    });
            if (explorer) focusViewport();
        });
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
        // The long GPU and texture texts (the tile counts among them) show once, on the developer
        // overlay (F3). The labels stay in the More drawer's rows, hidden and taking no room, so
        // their ids keep resolving; the overlay binds to their text. Retry stays in More.
        for (var longText : java.util.List.of(gpuStatus, textureStatus)) {
            longText.setVisible(false);
            longText.setManaged(false);
        }
        // So an enabled Retry always says why: its tooltip is the current status text.
        retryGpu.setTooltip(statusTip(gpuStatus));
        retryTextures.setTooltip(statusTip(textureStatus));
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
                renderHealth = WorldStatusIcons.Render.SOFTWARE;
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
        devOverlay = new WorldDevOverlay(new WorldDevOverlay.Sources(textureStatus.textProperty(),
                gpuStatus.textProperty(), status.textProperty(), position.textProperty(),
                lookStatus.textProperty(), motionStatus.textProperty(), input::heldKeys,
                keyHelp.textProperty(), audio.statusText()), retryGpu, retryTextures,
                hud::refocus);
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
        viewport.getChildren().addAll(selectionLayer(), firstFrameActivity, hud, minimap);
        StackPane.setAlignment(minimap, Pos.TOP_RIGHT);
        StackPane.setMargin(minimap, new Insets(12));
        // The developer overlay sits in the HUD's own slot under the left column; it stays when
        // the HUD is hidden.
        hud.devOverlay(devOverlay);
        // The minimap is a HUD follower: it hides with the HUD (and closes its tracking popup), and
        // its clicks hand focus back through the HUD's one refocus.
        minimap.refocus(hud::refocus);
        minimap.followHud(hud.hiddenProperty());
        // Above the status icon row in the HUD's bottom-left stack; the map panel ends above it.
        hud.inspectCard(inspectCard);
        inspectCard.refocus(hud::refocus);
        inspectCard.visibleProperty().addListener((o, was, now) -> anchorToasts());
        // The toasts keep clear of the status icon row while it shows.
        icons.node().heightProperty().addListener((o, was, now) -> anchorToasts());
        hud.hiddenProperty().addListener((o, was, now) -> anchorToasts());
        // The card follows the HUD's hidden state by listening (it shows and hides itself): hiding
        // the HUD hides it and keeps it open, showing the HUD shows it again.
        hud.hiddenProperty().addListener((o, was, now) -> inspectCard.hudHidden(now));
        inspectCard.hudHidden(hud.hidden());
        toastLayer.setId("world-toast-layer");
        toastLayer.setPickOnBounds(false);
        toastLayer.setMouseTransparent(false);
        StackPane.setAlignment(toasts, Pos.BOTTOM_CENTER);
        // Bottom-centre of whatever the content shows (viewport or interior), above its rows.
        javafx.beans.InvalidationListener anchor = o -> anchorToasts();
        content.heightProperty().addListener(anchor);
        content.centerProperty().addListener((o, was, now) -> {
            if (was != null) was.boundsInParentProperty().removeListener(anchor);
            if (now != null) now.boundsInParentProperty().addListener(anchor);
            anchorToasts();
        });
        anchorToasts();
        toasts.refocus(() -> {
            if (explorer) focusViewport();
        });
        placeToasts();
        // Opening the popup stops flight; a held command key (L) stays held so its repeat is no toggle.
        minimap.tracking().onShowing(() -> {
            input.clearMotion();
            devOverlay.refresh();
        });
        minimap.tracking().passThrough(event -> {
            var action = input.controls().keys().get(event.getCode());
            return action != null && action.command;
        });
        minimap.tracking().forward(this::viewportKey);
        // A clicked minimap marker opens the same inspect card as a pick in the world.
        minimap.onMarkerClicked(clicked -> {
            // A marker card wins over any world pick still in flight, and clears its highlight.
            pickGeneration++;
            objectSelection.select(null);
            inspected(WorldInspection.marker(clicked));
        });
        VBox.setVgrow(maps, Priority.ALWAYS);
        heading.getStyleClass().add("h2");
        hud.mapNameLabel().textProperty().bind(heading.textProperty());
        hud.mapKey(mapKey(WorldControls.DEFAULTS));
        hud.topCenter(zoneText.line());
        hud.banner(zoneText.banner());
        hud.bottomRight(coordinates.node());
        hud.bottomLeft(icons.node());
        // Change listeners, not invalidation ones: refreshIcons does not read every source each time.
        javafx.beans.value.ChangeListener<Object> iconState = (o, before, after) -> refreshIcons();
        for (javafx.beans.value.ObservableValue<?> source : java.util.List.<javafx.beans.value.ObservableValue<?>>of(
                walkMode.selectedProperty(), flyMode.selectedProperty(),
                smoothMotion.selectedProperty(), motionStatus.textProperty(), flightSpeed.valueProperty(),
                audio.music().selectedProperty(), audio.ambience().selectedProperty(), audio.status().textProperty(),
                audio.silent(com.vibeminer.concepts.world.WorldAreaAudio.Kind.MUSIC), audio.silent(com.vibeminer.concepts.world.WorldAreaAudio.Kind.AMBIENCE),
                audio.variant().valueProperty(), gpuActivity.visibleProperty(), gpuEnabled.selectedProperty(),
                gpuStatus.textProperty(), textureActivity.visibleProperty(), textureStatus.textProperty(),
                lookStatus.textProperty()))
            source.addListener(iconState);
        refreshIcons();
        // The full text lines stay the models; the HUD shows the short form and keeps them as tooltips.
        zoneText.tooltip().textProperty().bind(currentArea.textProperty());
        coordinates.tooltip().textProperty().bind(position.textProperty());
        zoneText.leave().visibleProperty().bind(leaveInstance.visibleProperty());
        zoneText.leave().textProperty().bind(javafx.beans.binding.Bindings.when(
                leaveInstance.textProperty().isEqualTo("Cancel")).then("Cancel").otherwise("Leave"));
        panelBack.setId("world-map-panel-back");
        panelBack.setFocusTraversable(false);
        panelBack.textProperty().bind(back.textProperty());
        panelBack.setOnAction(e -> back.fire());
        menuSetup();
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
        position.setWrapText(false);
        position.setMinWidth(0);
        position.setMaxWidth(Double.MAX_VALUE);
        // Under the view only an inspect result shows; the click hint is in the key help.
        inspection.visibleProperty().bind(inspection.textProperty().isNotEqualTo(INSPECT_HINT));
        inspection.managedProperty().bind(inspection.visibleProperty());
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
        sidebarPane = sidebar;
        // Toasts float above the explorer content, whatever its centre shows.
        var contentStack = new StackPane(content, toastLayer);
        split = new SplitPane(sidebar, contentStack);
        split.setDividerPositions(sidebarDivider);
        setCenter(split);
        sceneProperty().addListener((o, was, now) -> {
            if (was != null) was.removeEventFilter(javafx.scene.input.KeyEvent.ANY, layoutKeysFilter);
            if (now != null) now.addEventFilter(javafx.scene.input.KeyEvent.ANY, layoutKeysFilter);
            // A view taken out of its window must not leave the viewport on the full-screen stage.
            if (now == null) fullscreen.exit();
        });
        fullscreenStatus.setId("world-fullscreen-status");
        fullscreenStatus.setMouseTransparent(true);
        fullscreenStatus.setVisible(false);
        fullscreenStatus.setMaxWidth(560);
        fullscreenStatus.setWrapText(true);
        fullscreenStatus.setStyle("-fx-background-color: rgba(16,24,28,.6); -fx-text-fill: #e8eeee;"
                + " -fx-padding: 4 8; -fx-background-radius: 4;");
        // The status line rides along in the viewport's HUD; full screen adds the inspect result,
        // never the click hint (as in the window). WorldFullscreen owns this line's visibility, so
        // "hide HUD" and an empty line fade it instead.
        fullscreenStatus.textProperty().bind(javafx.beans.binding.Bindings
                .when(inspection.textProperty().isEqualTo(INSPECT_HINT)).then("").otherwise(inspection.textProperty()));
        fullscreenStatus.opacityProperty().bind(javafx.beans.binding.Bindings
                .when(hud.hiddenProperty().or(fullscreenStatus.textProperty().isEmpty())).then(0.0).otherwise(1.0));
        fullscreenButton.setId("world-fullscreen");
        fullscreenButton.setFocusTraversable(false);
        fullscreenButton.setOnAction(e -> toggleFullscreen());
        hudButton.setId("world-hide-hud");
        hudButton.setFocusTraversable(false);
        hudButton.setOnAction(e -> hideHud(true));
        hudHint.setId("world-hud-hint");
        hudHint.setMouseTransparent(true);
        hudHint.setVisible(false);
        hudHint.setStyle("-fx-background-color: rgba(16,24,28,.6); -fx-text-fill: #e8eeee;"
                + " -fx-padding: 4 8; -fx-background-radius: 4;");
        // In the HUD's left column, above the developer overlay, so the two never overlap.
        hud.hiddenHint(hudHint);
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
                    viewportKey(e);
                    e.consume();
                });
        viewport.setOnKeyReleased(
                e -> {
                    viewportKey(e);
                    e.consume();
                });
        viewport.focusedProperty()
                .addListener(
                        (o, a, b) -> {
                            if (!b) viewportFocusLost();
                        });
        viewport.setOnMousePressed(
                e -> {
                    focusViewport();
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
            input.scroll(e.getDeltaY(), e.isShiftDown(), e.isControlDown());
            e.consume();
        });
        viewport.widthProperty().addListener((o, a, b) -> requestFrame());
        viewport.heightProperty().addListener((o, a, b) -> requestFrame());
        // #370: a monitor move or display-scale change resizes the native-resolution frame.
        WorldRenderScaleWatch.forNode(this, this::requestFrame);
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
                            streamingSizePulse();
                            // The recorded sky follows the clock even with a still camera.
                            if (streaming != null && streaming.skyStale()) streamCamera();
                            if (nativeSurface.attached() && nativeSurface.pulse(viewportActive(), now)
                                    == WorldNativeSurface.Pulse.FAILED) {
                                nativeFallback("the presentation window could not be placed");
                                return;
                            }
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
        toasts.show("Bookmark saved: " + name);
        focusViewport();
    }

    /**
     * Gives the explorer the keyboard. With native presentation the viewport is on the overlay
     * stage, which is activated as well so keys reach it.
     */
    private void focusViewport() {
        nativeSurface.focus();
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
        devOverlay.refresh();
    }

    private TerrainGround collisionGround;
    private com.vibeminer.rendering.world.ObjectCollision collisionObjects;
    private CollisionWorld collision = CollisionWorld.NONE;

    /**
     * Body collision: the current ground, with the stream's buildings and doodads around the
     * camera; rebuilt only when either changes.
     */
    private CollisionWorld currentCollision() {
        var surface = currentGround();
        var controller = streamingMode ? streaming : null;
        var objects = controller == null ? com.vibeminer.rendering.world.ObjectCollision.empty()
                : controller.objectCollision();
        if (surface != collisionGround || objects != collisionObjects) {
            collisionGround = surface;
            collisionObjects = objects;
            collision = CollisionWorld.combined(surface, objects);
        }
        return collision;
    }

    /** Buildings and doodads under the body: not read off the stream, loading, or colliding. */
    enum ObjectsState { NONE, LOADING, READY }

    private ObjectsState objectsReady() {
        var controller = streamingMode ? streaming : null;
        if (controller == null) return ObjectsState.NONE;
        var walker = input.body().body();
        return controller.objectsReady(walker.x(), walker.y()) ? ObjectsState.READY : ObjectsState.LOADING;
    }

    private TerrainGround currentGround() {
        if (!streamingMode) return ground;
        var controller = streaming;
        return controller == null ? TerrainGround.empty() : controller.ground();
    }

    /** The motion mode the two checkboxes choose. */
    private WorldExplorerInput.Mode motionMode() {
        return walkMode.isSelected() ? WorldExplorerInput.Mode.WALK
                : flyMode.isSelected() ? WorldExplorerInput.Mode.FLY : WorldExplorerInput.Mode.FREE;
    }

    private void motionChanged() {
        var mode = motionMode();
        input.motion(mode, smoothMotion.isSelected());
        bodyMode = null;
        if (mode == WorldExplorerInput.Mode.FREE) groundStatus(ExplorerMotion.Ground.FREE);
        else {
            bodyStatus(mode, input.body().body().status());
            // Built and sent lazily: only once someone walks or flies.
            if (!bodyWanted) {
                bodyWanted = true;
                sendBody();
            }
        }
        if (explorer) focusViewport();
    }

    private void groundStatus(ExplorerMotion.Ground next) {
        if (motionMode() != WorldExplorerInput.Mode.FREE) return;
        motionStatus.setText("Free flight · no collision");
        motionGround = next;
        refreshIcons();
    }

    // The body state the status line shows, so a pulse only relabels on a change.
    private WorldExplorerInput.Mode bodyMode;
    private WalkController.Status bodyShown;
    private double bodySpeedShown = -1;
    private ObjectsState objectsShown = ObjectsState.NONE;

    /** The status line for a walking or flying body; relabels only when something changed. */
    private void bodyStatus(WorldExplorerInput.Mode mode, WalkController.Status status) {
        double speed = mode == WorldExplorerInput.Mode.FLY ? input.flySpeed() : 0;
        var ready = objectsReady();
        if (mode == bodyMode && status == bodyShown && speed == bodySpeedShown && ready == objectsShown) return;
        bodyMode = mode;
        bodyShown = status;
        bodySpeedShown = speed;
        objectsShown = ready;
        motionStatus.setText(bodyText(mode, status, speed, ready));
        motionGround = status == WalkController.Status.LOADING ? ExplorerMotion.Ground.UNAVAILABLE
                : ExplorerMotion.Ground.FOLLOWING;
        refreshIcons();
    }

    /** What a body status says, e.g. "Walking · collides with terrain". */
    static String bodyText(WorldExplorerInput.Mode mode, WalkController.Status status, double flySpeed,
            ObjectsState objects) {
        String collides = switch (objects) {
            case NONE -> " · collides with terrain";
            case LOADING -> " · collides with terrain; buildings and objects here still loading";
            case READY -> " · collides with terrain, buildings and objects";
        };
        if (mode == WorldExplorerInput.Mode.FLY) {
            String speed = String.format(java.util.Locale.ROOT, " · %.0f yd/s", flySpeed)
                    + (flySpeed > MovementParity.TAXI_SPEED ? ", beyond game speed"
                            : " (taxi speed; Classic has no player flight, controls approximated)");
            return status == WalkController.Status.LOADING
                    ? "Flying · terrain collision not loaded here yet; flying on without it" + speed
                    : "Flying" + collides + speed;
        }
        return switch (status) {
            case STANDING, MOVING, FLYING -> "Walking" + collides;
            case FALLING -> "Walking · in the air" + collides;
            case SLIDING -> "Walking · too steep to stand: sliding" + collides;
            case LOADING -> "Walking · waiting for terrain collision here";
            case RETURNED -> "Walking · fell out of the world; put back where you last stood";
        };
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
            // The old world's zone name must not stay on the line (an unknown area keeps it).
            zoneText.reset();
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
        showArea(null);
        coordinates.clear();
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
        nativeSurface.detach();
        fullscreen.exit();
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
        toasts.clearActions();
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
        nativeSurface.detach();
        // A Retry belongs to the explorer session it was raised in; plain notices may stay.
        toasts.clearActions();
        fullscreen.exit();
        hideHud(false);
        if (spawns != null) spawns.suspend();
        resumeCamera = null;
        minimap.hide();
        retireGpu();
        explorer = false;
        streamingMode = false;
        timer.stop();
        clearKeys();
        explorerChrome(false);
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
        if (!helpToastShown) {
            helpToastShown = true;
            toasts.show(input.controls().help() + " · " + INSPECT_HINT, null, null, HELP_TOAST_NANOS);
        }
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
        focusViewport();
    }

    /** The explorer's viewport and controls; also restored when an inline instance is left. */
    private void showExplorerLayout() {
        // In full screen the viewport stays on the full-screen stage until it ends.
        if (!fullscreen.active()) content.setCenter(viewport);
        boolean adtInstance = instanceReturn != null && inventory != null
                && inventory.map().id() == instanceMap;
        // An entry abandoned while it showed "Cancel" (selectMap, invalidate, close) resets here.
        leaveInstance.setText("Leave instance");
        leaveInstance.setVisible(adtInstance);
        leaveInstance.setManaged(adtInstance);
        refreshEntrances();
        explorerChrome(true);
        // HUD step 5: the status icons replaced the slim bar; every other control lives on an
        // Escape menu page. The pages are filled here because an instance switch borrows some of
        // these controls for its own layout.
        fillMenuPages();
        content.setBottom(new VBox(6, inspection));
        applyHudHidden();
    }

    private void fillMenuPages() {
        var speedRow = new HBox(8, new Label("Speed preset"), flightSpeed);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        var sensitivityRow = new HBox(8, new Label("Sensitivity"), lookSensitivity);
        sensitivityRow.setAlignment(Pos.CENTER_LEFT);
        var layoutRow = new HBox(10, new Label("Layout"), qwerty, azerty);
        layoutRow.setAlignment(Pos.CENTER_LEFT);
        menu.page(WorldGameMenu.Page.CONTROLS).getChildren().setAll(
                WorldGameMenu.section("Movement", speedRow, smoothMotion, walkMode, flyMode, motionStatus),
                WorldGameMenu.section("Look", sensitivityRow, invertLook, lookStatus),
                WorldGameMenu.section("Keys", layoutRow, keyBindings),
                keyHelp, inspectHint());
        menu.page(WorldGameMenu.Page.AUDIO).getChildren().setAll(
                WorldGameMenu.section("Music, ambience and day / night", audio.node()));
        var display = menu.page(WorldGameMenu.Page.DISPLAY);
        display.getChildren().setAll(
                WorldGameMenu.section("View", showCoordinates, hudButton, fullscreenButton),
                WorldGameMenu.section("Renderer", new HBox(8, gpuEnabled, objectTextures), viewDistance,
                        new HBox(8, gpuActivity, retryGpu, gpuStatus),
                        new HBox(8, textureActivity, textureStatus, retryTextures)));
        // Map layers stay here until a tracking popup takes them; never steal them from one.
        var layers = new VBox(6);
        for (var overlay : new Object[] {spawns, roles}) {
            javafx.scene.Node controls = overlay instanceof WorldSpawnOverlay s ? s.controls()
                    : overlay instanceof WorldRoleOverlay r ? r.controls() : null;
            if (controls != null && (controls.getParent() == null || isWithinMenu(controls)))
                layers.getChildren().add(controls);
        }
        if (!layers.getChildren().isEmpty()) display.getChildren().add(WorldGameMenu.section("Map layers", layers));
        menu.page(WorldGameMenu.Page.BOOKMARKS).getChildren().setAll(saveBookmark, travel);
        menu.page(WorldGameMenu.Page.INSTANCES).getChildren().setAll(instanceStatus,
                new HBox(8, entrances, enterInstance), leaveInstance);
        menu.lastEntry(back);
    }

    /**
     * The Key bindings dialog's owner: the viewport's window, so the dialog opens on top of the
     * full-screen stage while full screen (the menu page that opens it is on that stage too).
     */
    javafx.stage.Window keyBindingsOwner() {
        var scene = viewport.getScene() != null ? viewport.getScene() : getScene();
        return scene == null ? null : scene.getWindow();
    }

    /** The click-to-inspect hint, under the key help in the Controls page footer. */
    private Label inspectHint() {
        var hint = new Label(INSPECT_HINT);
        hint.setId("world-inspect-hint");
        hint.setWrapText(true);
        hint.setStyle("-fx-opacity: .75;");
        return hint;
    }

    private boolean isWithinMenu(javafx.scene.Node node) {
        for (var at = node; at != null; at = at.getParent()) if (at == menu) return true;
        return false;
    }

    private void menuSetup() {
        viewport.getChildren().add(menu);
        menu.leaveEntry().visibleProperty().bind(leaveInstance.visibleProperty()
                .and(leaveInstance.textProperty().isEqualTo("Leave instance")));
        qwerty.setToggleGroup(layoutChoice);
        azerty.setToggleGroup(layoutChoice);
        for (var choice : new javafx.scene.control.RadioButton[] {qwerty, azerty}) {
            choice.setFocusTraversable(false);
            choice.setOnAction(e -> {
                var layout = choice == azerty ? WorldControls.Layout.AZERTY : WorldControls.Layout.QWERTY;
                var next = input.controls().withKeys(layout.keys());
                showControls(next);
                controlsStore.save(next.encode());
            });
        }
        qwerty.setId("world-layout-qwerty");
        azerty.setId("world-layout-azerty");
        showCoordinates.setId("world-show-coordinates");
        showCoordinates.setFocusTraversable(false);
        showCoordinates.setSelected(true);
        coordinates.node().opacityProperty().bind(javafx.beans.binding.Bindings.when(
                showCoordinates.selectedProperty()).then(1.0).otherwise(0.0));
        coordinates.node().mouseTransparentProperty().bind(showCoordinates.selectedProperty().not());
        keyHelp.setWrapText(true);
        keyHelp.setStyle("-fx-opacity: .75;");
    }

    /** The layout radio that matches the bindings; neither for custom bindings. */
    private void showLayoutChoice(WorldControls controls) {
        layoutChoice.selectToggle(controls.keys().equals(WorldControls.Layout.QWERTY.keys()) ? qwerty
                : controls.keys().equals(WorldControls.Layout.AZERTY.keys()) ? azerty : null);
    }

    /** The menu opened: held keys and mouse look end, so nothing moves behind it. */
    private void menuOpened() {
        clearKeys();
        // Nothing of the HUD stays over the menu: B pressed in the tracking popup opens the menu.
        minimap.tracking().hide();
    }

    private void menuClosed() {
        if (explorer) focusViewport();
    }

    /**
     * Escape, closing the top-most thing first: the inspect card while it shows (a card the hidden
     * HUD hides stays open, and Escape moves on), then the menu (from a page too, the whole menu),
     * then the map panel. With nothing open, a
     * window opens the menu, and full screen leaves full screen: that is the last layer, so Escape
     * never drops out of full screen while something is still open. This is the only Escape
     * handling: it runs as the scene filter (the full-screen stage's own Escape exit is off, see
     * {@link WorldFullscreen}), so the viewport never sees an Escape.
     */
    boolean escape() {
        // A showing card goes first, so Escape never opens the menu over it.
        if (inspectCard.isVisible() && inspectCard.close()) return true;
        if (fullscreen.active() && (!explorer || instances.engaged())) {
            leaveFullscreenByEscape();
            return true;
        }
        if (!explorer || instances.engaged()) return false;
        if (menu.shown()) menu.close();
        else if (hud.panelShown()) {
            closeMapPanel();
            focusViewport();
        } else if (fullscreen.active()) leaveFullscreenByEscape();
        else menu.open();
        return true;
    }

    private void leaveFullscreenByEscape() {
        layoutToggledAt = System.nanoTime();
        fullscreen.exit();
        nativeAfterFullscreen();
        clearKeys();
        applyHudHidden();
    }

    /**
     * HUD step 1, "the viewport is the page". While exploring, the map list leaves the split pane
     * and opens only as the map panel (Map button or M), and the heading and status line move into
     * the HUD over the view. Otherwise (tile picker, instance switch) they go back where they were.
     */
    private void explorerChrome(boolean exploring) {
        closeMapPanel();
        // HUD widgets only act while the explorer owns the layout (not while an instance switch does).
        hud.setDisable(!exploring);
        if (exploring) {
            if (split.getItems().contains(sidebarPane)) {
                if (split.getDividers().size() == 1) sidebarDivider = split.getDividerPositions()[0];
                split.getItems().remove(sidebarPane);
            }
            content.setTop(null);
            hud.status(status, localLoading);
            return;
        }
        menu.close();
        menu.clearPages(back);
        hud.clearStatus();
        zoneText.reset();
        coordinates.clear();
        if (!split.getItems().contains(sidebarPane)) {
            split.getItems().addFirst(sidebarPane);
            split.setDividerPositions(sidebarDivider);
        }
        content.setTop(new VBox(10, heading, status, localLoading));
    }

    /** The Map button and the map key: opens or closes the map panel over the view. */
    private void toggleMapPanel() {
        if (hud.panelShown()) {
            closeMapPanel();
            focusViewport();
            return;
        }
        if (!explorer || instances.engaged()) return;
        hideHud(false);
        // The tile inventory comes along, scaled to the panel: a picked tile opens with "Open".
        tileInventory.setScaleX(MAP_PANEL_TILES);
        tileInventory.setScaleY(MAP_PANEL_TILES);
        var tilePicker = new VBox(6, new javafx.scene.Group(tileInventory), open);
        hud.showPanel(panelBack, tilePicker, sidebarPane);
    }

    /** The tile inventory's scale inside the map panel (512 px becomes 320 px). */
    private static final double MAP_PANEL_TILES = .625;

    /** Closes the map panel; what it held goes back to the tile picker when that shows again. */
    private void closeMapPanel() {
        hud.hidePanel();
        tileInventory.setScaleX(1);
        tileInventory.setScaleY(1);
    }

    /**
     * The viewport's key handling: flight input and HUD commands, from the viewport and from the
     * tracking popup's forwarding. While the menu is open no press acts (nothing flies and no
     * command runs behind it); releases still pass, so nothing stays held.
     */
    private void viewportKey(javafx.scene.input.KeyEvent e) {
        // The bookmarks key's press latch also follows keys forwarded from the tracking popup,
        // which never pass the scene filter, so a held B stays one press there too.
        if (input.controls().keys().get(e.getCode()) == WorldControls.Action.BOOKMARKS) {
            if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED) bookmarksKeyHeld = true;
            else if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) bookmarksKeyHeld = false;
        }
        if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED) {
            if (!menu.shown()) input.keyPressed(e.getCode());
        }
        else if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) input.keyReleased(e.getCode());
        devOverlay.refresh();
    }

    /**
     * Command keys ({@link WorldControls.Action#command}), once per press. Every command action
     * gets a branch here; one without a route is reported instead of being dropped silently, and
     * returns false (a UI test walks every command action and requires true).
     */
    boolean command(WorldControls.Action action) {
        switch (action) {
            case MAP_PANEL -> toggleMapPanel();
            case HIDE_HUD -> hideHud(!hudHidden.get());
            case DEV_OVERLAY -> devOverlay.cycle();
            case SPAWNS -> {
                if (spawns != null && !spawns.toggle().isDisabled()) spawns.toggle().fire();
            }
            case DAY_NIGHT -> audio.cycleSlot();
            case TRACKING -> minimap.toggleTracking();
            case MINIMAP -> minimap.toggleShown();
            // The speed choice box saves the controls from its own action.
            case SPEED_STROLL, SPEED_MOUNT, SPEED_FLY, SPEED_FAST, SPEED_TURBO -> flightSpeed.setValue(action.speed());
            case INSPECT_PIN -> {
                if (camera != null) inspectCard.togglePin(camera.x(), camera.y(), camera.z());
            }
            case SCREENSHOT -> screenshot();
            case BOOKMARKS -> bookmarksKey();
            default -> {
                WorldDiagnostics.warn("World command key", new IllegalStateException(
                        "No route for command action " + action));
                return false;
            }
        }
        return true;
    }

    /** The keys bound to the map panel, for the Map button; null when unbound. */
    private static String mapKey(WorldControls controls) {
        return boundKeys(controls, WorldControls.Action.MAP_PANEL);
    }

    /** The keys bound to {@code action}, joined with "/"; null when unbound. */
    private static String boundKeys(WorldControls controls, WorldControls.Action action) {
        var keys = controls.byAction().get(action);
        return keys.isEmpty() ? null
                : keys.stream().map(KeyCode::getName).collect(java.util.stream.Collectors.joining("/"));
    }

    /**
     * F11 / Alt+Enter (full screen) and Alt+Z (hide HUD), as a key filter on the application scene
     * and the full-screen scene, so they work whatever has focus while the explorer shows. Each
     * fires once per press; the keys never reach flight input.
     */
    void layoutKeys(javafx.scene.input.KeyEvent e) {
        var code = e.getCode();
        if (bookmarksKeyFilter(e)) {
            e.consume();
            return;
        }
        if (code == KeyCode.ESCAPE) {
            if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) escapeKeyHeld = false;
            else if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_PRESSED) {
                // Once per press: a held Escape never opens and closes the menu in turn.
                if (escapeKeyHeld ? explorer || fullscreen.active() : escape()) e.consume();
                escapeKeyHeld = true;
            }
            return;
        }
        boolean full = code == KeyCode.F11 || code == KeyCode.ENTER && e.isAltDown();
        boolean panel = code == KeyCode.Z && e.isAltDown();
        if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) {
            if (code == KeyCode.F11 || code == KeyCode.ENTER) fullscreenKeyHeld = false;
            if (code == KeyCode.Z) hideHudKeyHeld = false;
            return;
        }
        if (e.getEventType() != javafx.scene.input.KeyEvent.KEY_PRESSED || !(full || panel)) return;
        if (!explorer && !fullscreen.active()) return;
        e.consume();
        if (full) {
            if (!fullscreenKeyHeld) toggleFullscreen();
            fullscreenKeyHeld = true;
        } else {
            if (!hideHudKeyHeld) hideHud(!hudHidden.get());
            hideHudKeyHeld = true;
        }
    }

    // One method reference instance, so the filter can be removed again.
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> layoutKeysFilter = this::layoutKeys;

    /** The bookmarks key: opens the menu on its Bookmarks page, or closes it from there. */
    private void bookmarksKey() {
        if (!explorer || instances.engaged()) return;
        if (menu.shown() && menu.current() == WorldGameMenu.Page.BOOKMARKS) menu.close();
        else menu.open(WorldGameMenu.Page.BOOKMARKS);
    }

    /**
     * The bookmarks key while the menu shows: the viewport takes no presses then, so this scene
     * filter handles it, once per press, unless a text field has focus (typing a "b" in a name or
     * coordinates field stays typing). True when the key was handled here.
     */
    private boolean bookmarksKeyFilter(javafx.scene.input.KeyEvent e) {
        if (input.controls().keys().get(e.getCode()) != WorldControls.Action.BOOKMARKS) return false;
        if (e.getEventType() == javafx.scene.input.KeyEvent.KEY_RELEASED) {
            bookmarksKeyHeld = false;
            return false;
        }
        if (e.getEventType() != javafx.scene.input.KeyEvent.KEY_PRESSED) return false;
        boolean repeat = bookmarksKeyHeld;
        bookmarksKeyHeld = true;
        if (!menu.shown()) return false;
        // The key's own target, not this window's focus owner: in full screen the menu lives in
        // the full-screen stage, whose text fields this window's focus owner never is.
        if (e.getTarget() instanceof javafx.scene.control.TextInputControl) return false;
        if (!repeat) bookmarksKey();
        return true;
    }

    private boolean bookmarksKeyHeld;

    private void toggleFullscreen() {
        if (!explorer && !fullscreen.active()) return;
        // An instance switch (drain or interior) owns the layout; only leaving is allowed.
        if (instances.engaged() && !fullscreen.active()) return;
        layoutToggledAt = System.nanoTime();
        boolean entering = !fullscreen.active();
        // One owner of the viewport at a time: native presentation hands it back before full
        // screen takes it, and the stream restarts on readback there (#381, #415).
        boolean restream = entering && nativeStream;
        if (restream) retireStreaming();
        fullscreen.toggle(getScene() == null ? null : getScene().getWindow(),
                "World explorer" + (inventory == null ? "" : " · " + inventory.map()));
        if (restream) startStreaming();
        else if (!entering) nativeAfterFullscreen();
        placeToasts();
        clearKeys();
        applyHudHidden();
    }

    /**
     * Full screen has ended: a stream that runs on readback only because of full screen goes back
     * to native presentation. Only when it was asked for and has not failed in this view.
     */
    private void nativeAfterFullscreen() {
        if (!nativeRequested || nativeFailed || nativeStream || streaming == null || fullscreen.active()
                || closed || !active || !explorer || instances.engaged()
                || WorldNativeSurface.parentHandle(getScene() == null ? null : getScene().getWindow()) == 0) return;
        retireStreaming();
        startStreaming();
    }

    // When F11 or Alt+Enter last moved the viewport between stages; see viewportFocusLost().
    private long layoutToggledAt = Long.MIN_VALUE / 2;
    static final long LAYOUT_FOCUS_SETTLE_NANOS = 1_000_000_000L;

    /**
     * The viewport lost focus: drop held flight keys and, unless the loss is our own move to or
     * from the full-screen stage, the F11 and Alt+Z latches, whose release may now go elsewhere.
     * Keeping them across our own move stops a held F11's key repeat from toggling straight back.
     */
    private void viewportFocusLost() {
        clearKeys();
        if (System.nanoTime() - layoutToggledAt > LAYOUT_FOCUS_SETTLE_NANOS)
            fullscreenKeyHeld = hideHudKeyHeld = escapeKeyHeld = bookmarksKeyHeld = false;
    }

    /**
     * "Hide HUD" (Alt+Z, or the Hide HUD button): one state for every HUD overlay over the view
     * and the slim bar under it. The map list is already out of the way while exploring. Works in
     * full screen too, since the HUD lives in the viewport.
     */
    private void hideHud(boolean hidden) {
        if (hidden && (!explorer || instances.engaged())) return;
        if (hidden == hudHidden.get()) return;
        // A hidden HUD closes the map panel, so the next M opens it again; the view closes it
        // before the HUD follows the state, so what the panel held is reset first.
        if (hidden) closeMapPanel();
        hudHidden.set(hidden);
        applyHudHidden();
        if (explorer) focusViewport();
    }

    private void applyHudHidden() {
        var bottom = content.getBottom();
        if (bottom != null) {
            bottom.setVisible(!hudHidden.get());
            bottom.setManaged(!hudHidden.get());
        }
        hudHint.setVisible(hudHidden.get());
        hudHint.setText(hudHint(hideHudKey, fullscreen.active()));
    }

    /**
     * The hint shown while the HUD is hidden: the hide HUD key (null when unbound) and Alt+Z, which
     * always works, then how to enter or leave full screen.
     */
    static String hudHint(String hideKey, boolean fullscreen) {
        return hideHudKeys(hideKey) + " shows the HUD · "
                + (fullscreen ? "F11 or Esc leaves full screen" : "F11 full screen");
    }

    /** The More drawer's hide HUD button text, naming the same keys. */
    static String hideHudButton(String hideKey) {
        return "Hide HUD (" + hideHudKeys(hideKey) + ")";
    }

    private static String hideHudKeys(String hideKey) {
        return hideKey == null || hideKey.isBlank() ? "Alt+Z" : hideKey + " or Alt+Z";
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
        if (!texturePendingFrame) textureHealth = WorldStatusIcons.Texture.FALLBACK;
        textureStatus.setText(texturePendingFrame ? "Presenting prepared terrain…"
                : "Terrain texture loading unavailable");
        retryTextures.setDisable(true);
        if (gpuScene == null && gpu != null) {
            retireGpu();
            renderHealth = WorldStatusIcons.Render.SOFTWARE;
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
        textureHealth = WorldStatusIcons.Texture.READY;
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
            if (result.issue() != null) textureHealth = WorldStatusIcons.Texture.FAILED;
            textureStatus.setText(result.issue());
            retryTextures.setDisable(texturePendingFrame);
            if (result.issue() != null) toasts.show(result.issue(), "Retry", retryTextures::fire);
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
        textureHealth = textures == null ? (currentMaterialIssue == null ? WorldStatusIcons.Texture.READY
                        : WorldStatusIcons.Texture.FALLBACK)
                : textures.coveredRects() == 0 ? WorldStatusIcons.Texture.FALLBACK : WorldStatusIcons.Texture.READY;
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
        textureHealth = textureSnapshot == null && currentMaterialIssue != null
                ? WorldStatusIcons.Texture.FALLBACK : WorldStatusIcons.Texture.READY;
        textureStatus.setText(textureSnapshot == null
                ? (currentMaterialIssue == null ? "Height shading" : currentMaterialIssue)
                : "Basic terrain textures · " + textureSnapshot.coveredChunks() + "/"
                        + textureSnapshot.totalChunks() + " regions");
    }

    private void updateCurrentArea() {
        // The instance owns the area and its audio while it is entered.
        if (instances.engaged()) return;
        if (camera==null || catalog==null || inventory==null) {
            showArea(null);
            audio.area(null, -1, null);
            return;
        }
        var area = com.vibeminer.concepts.world.WorldCurrentArea.locate(
                inventory.map().id(),camera.x(),camera.y(),loaded.values(),catalog.areaIndex());
        showArea(area);
        audio.area(handle, inventory.map().id(), area);
    }

    /** Recomputes every status icon from the controls and labels it summarises. */
    private void refreshIcons() {
        icons.mode(motionMode() != WorldExplorerInput.Mode.WALK ? WorldStatusIcons.Mode.FLY
                : motionGround == ExplorerMotion.Ground.UNAVAILABLE ? WorldStatusIcons.Mode.WALK_NO_TERRAIN
                : WorldStatusIcons.Mode.WALK, motionStatus.getText());
        icons.smooth(smoothMotion.isSelected());
        icons.music(sound(audio.music(), com.vibeminer.concepts.world.WorldAreaAudio.Kind.MUSIC), "Music · " + audio.status().getText());
        icons.ambience(sound(audio.ambience(), com.vibeminer.concepts.world.WorldAreaAudio.Kind.AMBIENCE),
                "Ambience · " + audio.status().getText());
        var slot = audio.variant().getValue();
        icons.time(slot == WorldAudioDirector.Slot.DAY ? WorldStatusIcons.Time.DAY
                : slot == WorldAudioDirector.Slot.NIGHT ? WorldStatusIcons.Time.NIGHT : WorldStatusIcons.Time.CLOCK,
                (slot == null ? WorldAudioDirector.Slot.CLOCK : slot) + "; click for the next day/night slot");
        icons.render(WorldStatusIcons.render(gpuActivity.isVisible(), gpuEnabled.isSelected(), renderHealth),
                gpuStatus.getText());
        icons.texture(WorldStatusIcons.texture(textureActivity.isVisible(), textureHealth),
                textureStatus.getText());
        boolean capture = lookStatus.getText() != null && !lookStatus.getText().isBlank();
        icons.mouse(capture, capture ? lookStatus.getText() : "Mouse look: hold a mouse button and move the mouse");
        var layers = new java.util.ArrayList<javafx.scene.paint.Color>();
        if (spawns != null && spawns.toggle().isSelected()) layers.add(WorldSpawnOverlay.rankColor(0));
        if (roles != null)
            for (var layer : com.vibeminer.concepts.locations.MapRoles.Layer.values())
                if (roles.toggle(layer).isSelected()) layers.add(MapRoleOverlay.color(layer));
        icons.tracking(layers);
        speedIcon();
    }

    private WorldStatusIcons.Sound sound(CheckBox channel, com.vibeminer.concepts.world.WorldAreaAudio.Kind kind) {
        if (!channel.isSelected()) return WorldStatusIcons.Sound.MUTED;
        return audio.silent(kind).get() ? WorldStatusIcons.Sound.SILENT : WorldStatusIcons.Sound.ON;
    }

    /** The live speed chip; called per frame with the position line. */
    private void speedIcon() {
        var preset = flightSpeed.getValue();
        var modifier = input.controls().sprintModifier();
        icons.speed(input.effectiveSpeed(), input.sprinting(), (preset == null ? "" : preset + " preset")
                + "; click for the next preset" + (modifier == null ? "" : "; " + modifier.getName() + "+wheel steps it"));
    }

    /** The recorded area under the camera (null: unknown), on the area line and the HUD zone text. */
    private void showArea(com.vibeminer.concepts.world.WorldCurrentArea.Result area) {
        currentArea.setText(area == null ? "Terrain area unavailable" : area.label());
        boolean known = area != null && area.state() == com.vibeminer.concepts.world.WorldCurrentArea.State.KNOWN;
        zoneText.area(known ? area.chain().stream().map(Object::toString).toList() : java.util.List.of());
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
        inspectCard.follow(camera.x(), camera.y(), camera.z());
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
        coordinates.show(camera.x(), camera.y(), camera.z(), tileX(), tileY());
        speedIcon();
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
                renderHealth = WorldStatusIcons.Render.SOFTWARE;
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
        resetStreamingSize();
        presentedCamera.restart();
        var request = new WorldStreamSourceController.Request(selection, handle, inventory.map(),
                inventory, origin, areaEntry == null || areaEntry.entryChunk() == null
                        ? null : areaEntry.entryChunk().entryReference(), catalog.areaIndex());
        var creatureSource = WorldStreamGpuOwner.CREATURES ? creatures : null;
        long creatureMap = inventory.map().id();
        var detail = objectTextures.getValue();
        var distance = viewDistance.getValue();
        streamingDistance = distance;
        // FAR keeps 512 masks on any tier, as its terrain budget assumes (#173, #174 step 2b-2).
        WorldStreamGpuOwner.splatMasks(detail, distance);
        // Every map draws its WMO interior groups (#280, #383), within what the exteriors leave;
        // portal sets hide them from outside.
        var interiors = interiorMode(inventory.map());
        WorldStreamSourceBridge.ExteriorPreparer preparer = creatureSource == null
                ? (source, scope, tile, cancellation, formats, creatureBytes) -> WorldStreamSourceBridge.prepareExterior(
                        source, scope, tile, cancellation, null, detail, interiors, formats)
                : (source, scope, tile, cancellation, formats, creatureBytes) -> WorldStreamSourceBridge.prepareExterior(
                        source, scope, tile, cancellation,
                        (builder, owned, token) -> creatureSource.append(builder, creatureMap, owned, creatureBytes, token),
                        detail, interiors, formats);
        stallWatchdog.reset();
        // #381: native presentation when asked for, on Windows, unless it already failed here.
        // Not in full screen: the helper's child belongs to the application window.
        long presentParent = nativeRequested && !nativeFailed && !fullscreen.active()
                ? WorldNativeSurface.parentHandle(getScene() == null ? null : getScene().getWindow()) : 0;
        nativeStream = presentParent != 0;
        nativeSequence = 0;
        streaming = new WorldStreamSourceController(request, scope -> {
            var owner = new WorldStreamGpuOwner(gpuService, scope, detail, presentParent, distance);
            return new WorldStreamSourceController.GpuOwner() {
                public CompletableFuture<com.vibeminer.rendering.bgfx.WorldStreamTransport.Session> session() {
                    return owner.session();
                }
                public CompletableFuture<Void> closeAsync() { return owner.closeAsync(); }
            };
        }, streamingEvents, Platform::runLater, System::nanoTime, preparer, distance.residents());
        if (bodyWanted) sendBody();
        if (nativeStream) {
            var controller = streaming;
            controller.onPresented(revision -> nativePresented(controller, revision));
            // The helper's child starts hidden and presents nothing until it is placed and shown,
            // so the viewport moves onto the overlay now; each pulse then places the child.
            content.setCenter(nativeSurface.placeholder());
            nativeSurface.attach(getScene() == null ? null : getScene().getWindow());
        }
        if (camera != null) {
            streamCamera();
            streamNeighborhood();
        }
        streamChanged();
    }

    /**
     * #381: the helper presented a frame for {@code revision} into its child window; it counts as
     * a presented frame for the stall watchdog, as a streamed frame does on the readback path.
     */
    private void nativePresented(WorldStreamSourceController controller, long revision) {
        if (controller != streaming || !nativeStream || closed || stale(selection) || !explorer) return;
        // The child shows this revision: overlays and picking project with its camera.
        presentedCamera.presented(revision, nativeSurface);
        if (stallWatchdog.presented(revision, ++nativeSequence, System.nanoTime())) {
            streamStatus(controller.snapshot());
        }
        show(firstFrameActivity, false);
    }

    /** #381: native presentation failed; this view goes back to readback for good. */
    private void nativeFallback(String reason) {
        WorldDiagnostics.warn("Native World presentation stopped",
                new IllegalStateException(reason + "; using the compatible path"));
        nativeFailed = true;
        retireStreaming();
        gpuStatus.setText("Native presentation stopped (" + reason + "); using the compatible path.");
        if (active && explorer && streamingMode) startStreaming();
    }

    private void streamingUnavailable(Throwable failure) {
        WorldDiagnostics.warn("World resources could not be confirmed retired", failure);
        show(gpuActivity, false);
        show(firstFrameActivity, false);
        renderHealth = WorldStatusIcons.Render.FAILED;
        gpuStatus.setText("World resources could not be confirmed retired ("
                + WorldDiagnostics.brief(failure) + "); reload the workspace.");
        toasts.show(gpuStatus.getText());
        retryGpu.setDisable(true);
    }

    private void retireStreaming() {
        nativeSurface.detach();
        nativeStream = false;
        var previous = streaming;
        streaming = null;
        stallWatchdog.reset();
        if (previous != null) streamingRetirement = previous.closeAsync();
    }

    private void streamChanged() {
        var controller = streaming;
        if (controller == null || !streamingMode || stale(selection) || !explorer) return;
        var snapshot = controller.snapshot();
        if (nativeStream && snapshot.phase() == WorldStreamSourceController.Phase.FAILED) {
            nativeFallback("the native stream failed");
            return;
        }
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
        // Streamed textures ride with the stream: only a failed stream is a texture failure. Set
        // before streamStatus() refreshes the icons, so the texture chip never waits for the
        // detail text to change.
        textureHealth = snapshot.phase() == WorldStreamSourceController.Phase.FAILED
                ? WorldStatusIcons.Texture.FAILED : WorldStatusIcons.Texture.READY;
        streamStatus(snapshot);
        textureStatus.setText(snapshot.detail());
        retryTextures.setDisable(true);
        if (snapshot.area() != null) {
            showArea(snapshot.area());
            audio.area(inventory == null ? null : handle,
                    inventory == null ? -1 : inventory.map().id(), snapshot.area());
        }
        minimap.progress(snapshot.prepared(), snapshot.desired());
        if (camera != null) position.setText(String.format(java.util.Locale.ROOT,
                "Tile %d,%d · x %.1f / y %.1f / altitude %.1f · %.0f yards/s · %d / %d tiles",
                tileX(), tileY(), camera.x(), camera.y(), camera.z(),
                input.effectiveSpeed(),
                snapshot.prepared(), snapshot.desired()));
        if (camera != null) coordinates.show(camera.x(), camera.y(), camera.z(), tileX(), tileY());
        speedIcon();
        var pending = controller.takePresentation();
        if (pending.isEmpty()) return;
        var frame = pending.get();
        try {
            if (!viewportActive() || controller != streaming || !controller.mayPresent(frame)) return;
            if (!streamingImages.present(frame.width(), frame.height(), frame.pixels(),
                    () -> !stale(selection) && viewportActive() && controller == streaming,
                    () -> controller.presented(frame))) return;
            presentedCamera.presented(frame.cameraRevision(), image.getImage());
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
            renderHealth = WorldStatusIcons.Render.FAILED;
            gpuStatus.setText("GPU World frame upload failed; retry the viewer.");
            retryGpu.setDisable(false);
            toasts.show(gpuStatus.getText(), "Retry", retryGpu::fire);
        } finally {
            controller.discard(frame);
        }
    }

    /**
     * The size and size policy of the next stream camera request. A new viewport size is held for
     * one quiet pulse ({@link WorldResizeDebounce}) so a window drag bumps the policy once.
     */
    void streamingSize() {
        // Native resolution: the viewport times the window's render scale on each axis (HiDPI),
        // within the one shared frame cap; the ImageView fits it back to layout size.
        var window = getScene() == null ? null : getScene().getWindow();
        var frame = com.vibeminer.rendering.world.WorldFrameLimits.frame(
                viewport.getWidth(), viewport.getHeight(),
                window == null ? 1 : window.getRenderScaleX(),
                window == null ? 1 : window.getRenderScaleY(), 1);
        streamingResize.offer(frame.width(), frame.height());
        int width = streamingResize.width(), height = streamingResize.height();
        if (streamingWidth != 0 && (width != streamingWidth || height != streamingHeight)) streamingPolicy++;
        streamingWidth = width;
        streamingHeight = height;
    }

    /** A new stream starts from policy 1 and takes its first size at once. */
    void resetStreamingSize() {
        streamingPolicy = 1;
        streamingWidth = streamingHeight = 0;
        streamingResize.reset();
    }

    /** Once per streaming pulse: a settled size is sent with a fresh camera request. */
    boolean streamingSizePulse() {
        if (!streamingResize.pulse()) return false;
        requestFrame();
        return true;
    }

    private void streamCamera() {
        if (streaming == null || camera == null || !active || !explorer || closed) return;
        if (stale(selection) || !viewportActive()) return;
        streamingSize();
        streaming.camera(new com.vibeminer.rendering.bgfx.WorldStreamTransport.Camera(
                ++streamingControl, streamingPolicy, streamingWidth, streamingHeight, TerrainCamera.from(camera))
                .withBody(bodyPose()));
        presentedCamera.sent(streamingControl, camera);
        stallWatchdog.requested(streamingControl);
        minimap.update(handle, inventory, origin, originX, originY,
                camera.x(), camera.y(), camera.yaw());
        inspectCard.follow(camera.x(), camera.y(), camera.z());
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
        if (stall != null && nativeStream) {
            nativeFallback("no frame was presented in time");
            return;
        }
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
        renderHealth = WorldStatusIcons.streamHealth(snapshot.phase(), hint, snapshot.prepared(), snapshot.desired());
        gpuStatus.setText(hint ? STALL_HINT : snapshot.detail());
        retryGpu.setDisable(busy && !hint);
        // One toast per failure or stall episode, not per snapshot.
        boolean failed = snapshot.phase() == WorldStreamSourceController.Phase.FAILED;
        if (failed && !streamFailedToast) toasts.show(snapshot.detail(), "Retry", retryGpu::fire);
        if (hint && !stallToast) toasts.show(STALL_HINT, "Retry", retryGpu::fire);
        streamFailedToast = failed;
        stallToast = hint;
        // The health can change while the text stays the same (for example the tile counts).
        refreshIcons();
    }

    private final WorldStreamNeighborhood.Motion motion = new WorldStreamNeighborhood.Motion();

    /** Once per stop: the demand order drops its bias ahead of the finished motion (#528). */
    private void cameraAtRest() {
        motion.rest();
        if (streamingMode) streamNeighborhood();
    }

    private void streamNeighborhood() {
        if (streaming == null || camera == null || inventory == null) return;
        int x = tileX(), y = tileY();
        var distance = streamingDistance;
        // Fractional tile position, same axes as tileX/tileY; tiles ahead of a moving camera
        // load first (#366).
        var ahead =
                motion.ahead(
                        origin.x() + (originX - camera.x()) / WorldFormats.TILE_SIZE,
                        origin.y() + (originY - camera.y()) / WorldFormats.TILE_SIZE,
                        System.nanoTime(),
                        distance.radius());
        var tiles = inventory.world().tiles();
        streaming.neighborhood(
                WorldStreamNeighborhood.select(tiles, x, y, camera.yaw(), distance, ahead[0], ahead[1]),
                WorldStreamNeighborhood.keep(tiles, x, y, distance));
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
        // The viewport's own window: the application window, or the full-screen stage. While
        // native presentation holds it on its overlay stage, which each pulse shows only when
        // this is true, the placeholder stands in for it in the application window.
        javafx.scene.Node shown = nativeSurface.attached() ? nativeSurface.placeholder() : viewport;
        var scene = shown.getScene();
        if (closed || !active || !explorer || scene == null
                || scene.getWindow() == null || !scene.getWindow().isShowing()) return false;
        if (scene.getWindow() instanceof javafx.stage.Stage stage && stage.isIconified()) return false;
        if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage main && main.isIconified())
            return false;
        for (javafx.scene.Node node = shown; node != null; node = node.getParent()) {
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
        presentedCamera.shownOther();
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
                textureHealth = WorldStatusIcons.Texture.FAILED;
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
        presentedCamera.shownOther();
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
        inspectCard.close();
    }

    /** Shows a pick on the inspect card; the inspection label keeps the full text. */
    private void inspected(WorldInspection.Content content) {
        inspection.setText(content.detail());
        inspectCard.show(content, camera == null ? Double.NaN : camera.x(),
                camera == null ? Double.NaN : camera.y(), camera == null ? Double.NaN : camera.z());
    }

    /** A declared spawn's card, with its role layers and faction when the roles layer lists it. */
    private WorldInspection.Content spawnContent(com.vibeminer.concepts.locations.WorldSpawns.Spawn spawn,
            com.vibeminer.concepts.locations.WorldSpawns.Npc npc, String mapName,
            WorldSpawnModels.Resolved model, boolean resolving) {
        com.vibeminer.concepts.locations.MapRoles.Npc role = roles == null ? null : roles.npc(spawn.npcId());
        java.util.List<String> layers = role == null ? null : role.layers().stream()
                .sorted().map(MapRoleOverlay::label).toList();
        String faction = role == null ? null : switch (role.friendly()) {
            case ALLIANCE -> "Alliance";
            case HORDE -> "Horde";
            case BOTH -> "Alliance and Horde";
            case UNKNOWN -> null;
        };
        return WorldInspection.spawn(spawn, npc, mapName, model, resolving, layers, faction, -1);
    }

    /** The selection highlight, bound to the presented view and the resident pick tiles. */
    private WorldSelectionOverlay selectionLayer() {
        objectSelection.bind(this::presentedView, this::residentObjects, () -> {
            pickGeneration++;
            inspection.setText("Selection cleared · the selected object's tile is no longer resident");
            inspectCard.release();
        });
        return objectSelection;
    }

    private WorldSpawnOverlay.View presentedView() {
        var frame = presentedFrame();
        if (closed || !active || !explorer || camera == null || inventory == null
                || frame == null) return null;
        return new WorldSpawnOverlay.View(frameCamera(), inventory.map().id(), frame[0], frame[1]);
    }

    /**
     * {width, height} of the frame on screen, or null: the readback image, or with native
     * presentation (once a frame was presented) the viewport, which the child fills.
     */
    private double[] presentedFrame() {
        var shown = image.getImage();
        return WorldNativePresenter.frameSize(nativeShown(), viewport.getWidth(), viewport.getHeight(),
                shown == null ? 0 : shown.getWidth(), shown == null ? 0 : shown.getHeight());
    }

    /** #381: the helper's child shows the frames (a native frame was presented this stream). */
    private boolean nativeShown() {
        return nativeStream && nativeSurface.attached() && nativeSequence > 0;
    }

    /**
     * The camera the frame on screen was drawn with: for the streamed image on screen, or the
     * native child's latest presented frame, the camera sent with its revision
     * ({@link WorldPresentedCamera}); for any other image (CPU and single-frame GPU paths) the
     * live camera.
     */
    private TerrainRenderer.Camera frameCamera() {
        return presentedCamera.frameCamera(camera, nativeShown() ? nativeSurface : image.getImage());
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
        var frame = presentedFrame();
        if (closed || !active || !explorer || camera == null || inventory == null || frame == null) {
            objectSelection.select(null);
            inspected(WorldInspection.note("Nothing to inspect until a terrain frame is shown"));
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
                inspected(spawnContent(spawn.spawn(), spawn.npc(), map.name(), spawn.model(), resolving));
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
                : WorldPick.ray(frameCamera(), frame[0], frame[1], viewport.getWidth(),
                        viewport.getHeight(), local.getX(), local.getY());
        if (ray.isEmpty()) {
            objectSelection.select(null);
            inspected(WorldInspection.note("Nothing picked · the pointer is outside the rendered frame"));
            return;
        }
        var objects = residentObjects();
        var eye = com.vibeminer.rendering.world.TerrainCamera.from(frameCamera());
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
                WorldInspection.Content text;
                WorldObjectPicks.Selected object = null;
                try {
                    var hit = WorldPick.terrain(ray.get(), surface);
                    // The nearer of terrain and drawn objects wins, as under the depth test. With
                    // no terrain hit only the per-hit eye depth clips, as the shader's discard does.
                    object = WorldObjectPicks.pick(objects, eye, ray.get(),
                            hit.map(WorldPick.Hit::distance).orElse(Double.POSITIVE_INFINITY)).orElse(null);
                    if (object != null) {
                        text = WorldInspection.object(object, mapId, mapName, names);
                    } else if (hit.isEmpty()) {
                        text = WorldInspection.note(WorldPick.describeMiss(surface.tiles() > 0));
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
                        text = WorldInspection.terrain(at, mapId, mapName, area, objects.size());
                    }
                } catch (Throwable failure) {
                    object = null;
                    text = WorldInspection.note("Inspection failed: " + WorldDiagnostics.brief(failure));
                }
                var result = text;
                var selected = object;
                Platform.runLater(() -> {
                    if (closed || request != pickGeneration || chosen != selection) return;
                    // A tile retired since the click no longer draws its object: never highlight it.
                    boolean resident = selected != null
                            && residentObjects().stream().anyMatch(tile -> tile == selected.tile());
                    inspected(selected != null && !resident ? new WorldInspection.Content(result.kind(),
                            result.title(), result.subtitle() + " · its tile is no longer resident",
                            result.lines(), result.file(),
                            result.detail() + " · its tile is no longer resident", result.npcId()) : result);
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
        // Only spawns near the camera carry their idle (#384).
        source.camera(() -> {
            var at = camera;
            return at == null ? null : new double[] {at.x(), at.y()};
        });
    }

    /**
     * Supplies the display models the walking body is built from ({@link WorldBodyModel}); every
     * later streaming session draws it in walk and fly modes. FX thread.
     */
    void attachBody(java.util.function.Supplier<com.vibeminer.concepts.models.BuildDisplayModels> displays) {
        if (bodyDisplays != null || closed) throw new IllegalStateException("Body models already attached");
        bodyDisplays = java.util.Objects.requireNonNull(displays);
    }

    /**
     * The walking body, built once on a worker; when it is ready its animation clock and capsule
     * are handed to the input on the FX thread. A failure only means no drawn body.
     */
    private synchronized CompletableFuture<WorldBodyModel.Built> bodyModel() {
        if (bodyModel != null) return bodyModel;
        var displays = bodyDisplays;
        bodyModel = CompletableFuture.supplyAsync(() -> {
            var component = displays.get();
            if (component == null) throw new IllegalStateException("Model files are not open");
            bodyComponent = component;
            try {
                return WorldBodyModel.build(component, WorldBodyModel.MURLOC,
                        com.vibeminer.access.Cancellation.NONE);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, runnable -> Thread.ofVirtual().name("world-body-model").start(runnable));
        bodyModel.whenComplete((built, failure) -> Platform.runLater(() -> {
            if (closed) return;
            if (failure != null) {
                WorldDiagnostics.degraded("body", "The walking body could not be built", failure);
                return;
            }
            input.animator(new com.vibeminer.rendering.world.BodyAnimator(built.durations(), built.moveSpeeds()));
            if (built.capsule() != null) input.body().body().shape(built.capsule());
        }));
        return bodyModel;
    }

    /**
     * Hands the walking body to the current streaming session (once per session; the controller
     * ignores a repeat). The body is built on the first call.
     */
    private void sendBody() {
        var controller = streaming;
        if (bodyDisplays == null || controller == null) return;
        controller.body(() -> bodyModel().thenApply(WorldBodyModel.Built::file));
    }

    /** The drawn body's pose for this frame, or null without a body or in the free camera. */
    private com.vibeminer.rendering.bgfx.WorldStreamTransport.Body bodyPose() {
        var animator = input.animator();
        if (animator == null || input.mode() == WorldExplorerInput.Mode.FREE || !input.body().placed()) return null;
        var walker = input.body().body();
        return new com.vibeminer.rendering.bgfx.WorldStreamTransport.Body(walker.x(), walker.y(), walker.z(),
                input.body().yaw(), animator.sequence(), animator.millis(), input.body().camera().firstPerson());
    }

    /**
     * Mounts quest/service role markers on the minimap (never the 3D viewport: Questie records no
     * height). FX thread.
     */
    void attachRoles(WorldRoleOverlay overlay) {
        if (roles != null || closed) throw new IllegalStateException("Role overlay already attached");
        roles = java.util.Objects.requireNonNull(overlay);
        minimap.attachRoles(overlay);
        for (var layer : com.vibeminer.concepts.locations.MapRoles.Layer.values())
            overlay.toggle(layer).selectedProperty().addListener((o, before, on) -> refreshIcons());
        refreshIcons();
        minimap.tracking().roles(overlay.controls());
    }

    /** A tooltip that always shows {@code status}'s current text. */
    private static javafx.scene.control.Tooltip statusTip(Label status) {
        var tip = new javafx.scene.control.Tooltip();
        tip.setWrapText(true);
        tip.setMaxWidth(520);
        tip.textProperty().bind(status.textProperty());
        return tip;
    }

    /** The inspect card, for tests. */
    WorldInspectCard inspectCard() {
        return inspectCard;
    }

    /** Sets the screenshots folder (the library store sets it; tests use a temporary one). */
    void screenshots(java.nio.file.Path folder) {
        screenshots = folder;
    }

    /** Replaces the file-manager opener; for tests. */
    void folderOpener(java.util.function.Consumer<java.nio.file.Path> opener) {
        folderOpener = java.util.Objects.requireNonNull(opener);
    }

    /** Replaces how the screenshot writer is started; for tests. */
    void screenshotWriter(java.util.function.Consumer<Runnable> writer) {
        screenshotWriter = java.util.Objects.requireNonNull(writer);
    }

    /** Opens a folder with the desktop, off FX; failures are logged, never thrown. */
    private void openFolder(java.nio.file.Path folder) {
        Thread.ofVirtual().name("world-screenshot-folder").start(() -> {
            try {
                if (!java.awt.Desktop.isDesktopSupported()
                        || !java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN))
                    throw new UnsupportedOperationException("opening folders is not supported here");
                java.awt.Desktop.getDesktop().open(folder.toFile());
            } catch (Throwable failed) {
                // Throwable: an AWTError from Desktop must still reach the toast.
                Platform.runLater(() -> folderNotOpened(failed));
            }
        });
    }

    /** The screenshot toast's Open folder: a refused or failed open says so in a toast. */
    private void openScreenshotFolder(java.nio.file.Path folder) {
        try {
            folderOpener.accept(folder);
        } catch (RuntimeException failed) {
            folderNotOpened(failed);
        }
    }

    private void folderNotOpened(Throwable failed) {
        WorldDiagnostics.warn("World screenshot folder could not be opened", failed);
        if (!closed) toasts.show("Could not open the folder");
    }

    /**
     * Saves the presented frame (the World image only, no HUD) as a PNG in the screenshots
     * folder: pixels are copied on FX, written off it, and a toast says where the file went.
     */
    void screenshot() {
        if (closed) return;
        if (screenshotSaving) {
            toasts.show("Screenshot still saving");
            return;
        }
        var folder = screenshots;
        if (!explorer || !(image.getImage() instanceof javafx.scene.image.Image frame)
                || frame.getPixelReader() == null) {
            toasts.show("No World frame to save yet");
            return;
        }
        if (folder == null) {
            toasts.show("Screenshots need a data folder; this session has none");
            return;
        }
        int width = (int) frame.getWidth(), height = (int) frame.getHeight();
        var argb = new int[width * height];
        frame.getPixelReader().getPixels(0, 0, width, height,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, width);
        // The frame is drawn opaque on screen; the file is too.
        for (int i = 0; i < argb.length; i++) argb[i] |= 0xff000000;
        String name = WorldScreenshots.fileName(inventory == null ? null : inventory.map().name(),
                java.time.ZonedDateTime.now());
        Runnable save = () -> {
            java.nio.file.Path saved = null;
            Throwable failure = null;
            try {
                saved = WorldScreenshots.save(folder, name, width, height, argb);
            } catch (Throwable failed) {
                failure = failed;
            }
            var file = saved;
            var problem = failure;
            Platform.runLater(() -> {
                screenshotSaving = false;
                if (closed) return;
                if (problem != null) {
                    WorldDiagnostics.warn("World screenshot could not be saved", problem);
                    toasts.show("Screenshot not saved: " + WorldDiagnostics.brief(problem));
                } else {
                    toasts.show("Screenshot saved: " + file.getFileName(), "Open folder",
                            () -> openScreenshotFolder(file.getParent()));
                }
            });
        };
        try {
            screenshotWriter.accept(save);
        } catch (RuntimeException | Error failed) {
            // Nothing was started: no save is in flight, and the next F12 can try again.
            WorldDiagnostics.warn("World screenshot could not be started", failed);
            toasts.show("Screenshot not saved: " + WorldDiagnostics.brief(failed));
            return;
        }
        // Set on FX after the writer started; its completion clears it in a later FX turn.
        screenshotSaving = true;
    }

    /** Keeps the toasts just above the bottom edge of the content's centre node. */
    private void anchorToasts() {
        var center = content.getCenter();
        double below = fullscreen.active() || center == null ? 0
                : Math.max(0, content.getHeight() - center.getBoundsInParent().getMaxY());
        // While the inspect card is open the toasts keep clear of its bottom-left column.
        double left = inspectCard.isVisible() ? WorldInspectCard.WIDTH + 24 : 12;
        // And they sit above the status icon row, which spans the bottom-left of the view, so a
        // wide toast in a narrow window never covers the icons (the row's 10 px margin, a gap).
        var row = icons.node();
        double iconRow = row.isVisible() && !hud.hidden() ? row.getHeight() + 10 + 8 : 0;
        StackPane.setMargin(toasts, new Insets(12, 12, below + Math.max(24, iconRow), left));
    }

    /** Toasts live on the content layer, or on the viewport while it is full screen. */
    private void placeToasts() {
        var host = fullscreen.active() ? viewport : toastLayer;
        if (toasts.getParent() == host) return;
        if (toasts.getParent() instanceof javafx.scene.layout.Pane old) old.getChildren().remove(toasts);
        host.getChildren().add(toasts);
        anchorToasts();
    }

    /** The toasts, for tests. */
    WorldToasts toasts() {
        return toasts;
    }

    /** Replaces where the coordinates pill copies to; for tests (no system clipboard needed). */
    void coordinatesCopy(java.util.function.Consumer<String> copy) {
        coordinatesCopy = java.util.Objects.requireNonNull(copy);
    }

    /** The developer overlay, for tests. */
    WorldDevOverlay devOverlay() {
        return devOverlay;
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
        minimap.tracking().spawns(overlay.controls());
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
                var resolved = spawnContent(pending.spawn(), pending.npc(), inspectedMap, model, false);
                inspection.setText(resolved.detail());
                inspectCard.update(resolved);
            });
        }));
        overlay.bind(this::presentedView);
        overlay.toggle().selectedProperty().addListener((o, before, on) -> refreshIcons());
        refreshIcons();
    }

    // ---- Instances: recorded entrances and in-place zone-in (#259) ----------------------------

    /** Dungeon (InstanceType 1) and raid (2) maps draw instance interiors (#280). */
    static boolean instanceInteriors(WorldService.MapInfo map) {
        return map != null && map.type() != null && (map.type() == 1 || map.type() == 2);
    }

    /**
     * Streamed tiles draw interior WMO groups on every map: dungeons and raids as instance
     * interiors (#280), every other map (continents, battlegrounds, arenas) with interiors read
     * after every exterior (#383), where caves and buildings are entered from outside.
     */
    static com.vibeminer.concepts.world.WorldExteriorObjects.Interiors interiorMode(
            WorldService.MapInfo map) {
        return instanceInteriors(map)
                ? com.vibeminer.concepts.world.WorldExteriorObjects.Interiors.INSTANCE
                : com.vibeminer.concepts.world.WorldExteriorObjects.Interiors.OPEN_WORLD;
    }

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
            minimap.entrances(java.util.List.of());
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
        minimap.entrances(found);
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
        toasts.show("Entering " + link.name());
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
        toasts.show("Left the instance" + (back.mapName() == null || back.mapName().isBlank()
                ? "" : " · back in " + back.mapName()));
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
            fullscreen.exit();
            hideHud(false);
            explorerChrome(false);
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
            toasts.show("Entered " + target.map());
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
            toasts.show("Left the instance · back in " + inventory.map());
            input.idle();
            if (!active) return;
            input.idle(); // owed look movement never lands as one jump on restart
            timer.start();
            startStreaming();
            focusViewport();
        }

        @Override
        public void status(String message) {
            status.setText(message);
            instanceStatus.setText(message);
        }
    }


    @Override
    public void close() {
        nativeSurface.detach();
        fullscreen.exit();
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
        var component = bodyComponent;
        if (component != null) component.closeAsync();
        picks.shutdownNow();
        streamingImages.clear();
        minimap.close();
        toasts.clear();
        if (gpu != null) gpu.close();
        invalidate();
        textureLoading.close();
        requests.closeAsync();
        areaRequests.shutdown();
        areaCancellation.shutdown();
        preparation.closeAsync();
    }
}
