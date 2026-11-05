package com.vimi.gui;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.StaticMeshRenderer;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Reusable actual static-mesh view, independent of JavaFX3D hardware support. */
final class ModelPreviewView extends BorderPane implements AutoCloseable {
    private record Work(
            long generation,
            PreviewMesh mesh,
            MeshAppearance appearance,
            com.vibeminer.rendering.models.PreviewAnimation animation,
            long animationMillis,
            boolean textured,
            boolean qualifiedMaterialsOnly,
            int width,
            int height,
            StaticMeshRenderer.View view) {}

    private final AtomicReference<Work> latest = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final ImageView image = new ImageView();
    private final StackPane viewport = new StackPane(image);
    private final CheckBox wireframe = new CheckBox("Edges"),
            all = new CheckBox("All recorded surfaces"),
            textures = new CheckBox("Textures");
    private final Label status = new Label("Select a model variant");
    /** Shown when a prepared idle does not fit the surface on screen; the toggle stays hidden. */
    static final String MISMATCHED_SURFACE =
            "Idle animation unavailable: it was prepared for a different surface than the one"
                    + " shown.";

    private final CheckBox idle = new CheckBox("Idle animation");
    private final Label idleStatus = new Label();
    private com.vibeminer.rendering.models.PreviewAnimation animation;
    private long idleOrigin, idlePaused, idleLastFrame;
    private final javafx.animation.AnimationTimer idleClock =
            new javafx.animation.AnimationTimer() {
                @Override
                public void handle(long now) {
                    if (!playing() || !active || running.get() || now - idleLastFrame < 40_000_000L)
                        return;
                    idleLastFrame = now;
                    request();
                }
            };
    private final GpuPreviewService gpu;
    private final Object gpuOwner = new Object();
    private final CheckBox gpuButton = new CheckBox("GPU rendering");

    private record PendingFrame(long token, GpuPreviewService.Frame frame) {}

    /** One pending UI delivery; completion never extracts and republishes an older value. */
    static final class LatestDelivery<T> {
        private final AtomicReference<T> latest = new AtomicReference<>();
        private final AtomicBoolean queued = new AtomicBoolean();
        private final java.util.function.Consumer<Runnable> scheduler;
        private final java.util.function.Consumer<T> consumer;

        LatestDelivery(
                java.util.function.Consumer<Runnable> scheduler,
                java.util.function.Consumer<T> consumer) {
            this.scheduler = scheduler;
            this.consumer = consumer;
        }

        void submit(T value) {
            latest.set(value);
            schedule();
        }

        void clear() {
            latest.set(null);
        }

        private void schedule() {
            if (latest.get() != null && queued.compareAndSet(false, true))
                scheduler.accept(this::drain);
        }

        private void drain() {
            try {
                var value = latest.getAndSet(null);
                if (value != null) consumer.accept(value);
            } finally {
                queued.set(false);
                schedule();
            }
        }
    }

    private final LatestDelivery<PendingFrame> gpuFrame;
    private final java.util.function.Consumer<Runnable> gpuScheduler;
    private boolean gpuReady, gpuFailed, active;
    private volatile long gpuBarrier;
    private long gpuPresented;
    private int gpuWidth, gpuHeight, gpuFlags = -1;
    private PreviewMesh gpuMesh;
    private MeshAppearance gpuAppearance;
    private Set<Integer> gpuParts = Set.of();
    private final javafx.animation.AnimationTimer visibility =
            new javafx.animation.AnimationTimer() {
                @Override
                public void handle(long now) {
                    boolean value = viewportActive();
                    if (value != active) {
                        active = value;
                        if (active) request();
                        else closeGpu();
                    }
                }
            };
    private final ProgressIndicator gpuActivity = new ProgressIndicator(-1);
    private final Label gpuStatus =
            new Label("GPU rendering stays in this preview; software fallback is available.");
    private final javafx.scene.layout.HBox appearanceActivity = new javafx.scene.layout.HBox(7);
    private final Label appearanceStage = new Label();
    private boolean appearancePending;
    private MeshAppearance appearanceTarget;
    private java.util.function.Consumer<Boolean> appearanceDelivery;
    private final ProgressIndicator renderingActivity = new ProgressIndicator(-1);
    private volatile long gpuGeneration;
    private boolean gpuRequested;
    private PreviewMesh mesh;
    private MeshAppearance appearance;
    private boolean qualifiedMaterials;
    private Set<Integer> visible = Set.of();
    private double yaw = -0.3, pitch, zoom = 1.15, mouseX, mouseY;
    private volatile boolean closed;
    private final javafx.animation.PauseTransition resize =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(35));

    ModelPreviewView() {
        this(GpuPreviewService.application());
    }

    ModelPreviewView(GpuPreviewService gpu) {
        this(gpu, Platform::runLater);
    }

    ModelPreviewView(GpuPreviewService gpu, java.util.function.Consumer<Runnable> gpuScheduler) {
        this.gpu = gpu;
        this.gpuScheduler = gpuScheduler;
        gpuFrame = new LatestDelivery<>(gpuScheduler, this::presentGpu);
        gpuButton.setId("model-gpu-preview");
        gpuButton.setAccessibleText("Use inline GPU model rendering");
        gpuButton.setSelected(true);
        all.setTooltip(
                new Tooltip(
                        "Show every recorded mesh section, including alternate parts and sections"
                                + " without supported materials. Unresolved materials appear"
                                + " neutral."));
        gpuButton.setTooltip(
                new Tooltip(
                        "GPU rasterization with copied frames displayed here. Software is used when"
                                + " GPU rendering is unavailable."));
        gpuButton.setDisable(true);
        gpuButton.setOnAction(
                event -> {
                    gpuFailed = false;
                    closeGpu();
                    request();
                });
        gpuActivity.setId("model-gpu-activity");
        gpuStatus.setId("model-gpu-status");
        gpuActivity.setAccessibleText("Inline GPU rendering activity");
        gpuActivity.setPrefSize(16, 16);
        gpuActivity.setMaxSize(16, 16);
        gpuStatus.setWrapText(true);
        gpuStatus.getStyleClass().add("muted");
        showGpuActivity(false);
        renderingActivity.setPrefSize(30, 30);
        renderingActivity.setMaxSize(30, 30);
        renderingActivity.setMouseTransparent(true);
        renderingActivity.setAccessibleText("Rendering static model preview");
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        viewport.getChildren().add(renderingActivity);
        var composing = new ProgressIndicator(-1);
        composing.setPrefSize(18, 18);
        composing.setMaxSize(18, 18);
        composing.visibleProperty().bind(appearanceActivity.visibleProperty());
        composing.managedProperty().bind(appearanceActivity.visibleProperty());
        appearanceActivity.setId("model-appearance-activity");
        appearanceActivity.setAlignment(javafx.geometry.Pos.CENTER);
        appearanceActivity.setStyle(
                "-fx-background-color: rgba(20,30,38,0.90); -fx-padding: 9; -fx-background-radius:"
                        + " 8;");
        appearanceActivity.setMaxSize(
                javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        appearanceActivity.setMouseTransparent(true);
        appearanceStage.setStyle("-fx-text-fill: white;");
        appearanceActivity.getChildren().addAll(composing, appearanceStage);
        appearanceActivity.setVisible(false);
        appearanceActivity.setManaged(false);
        javafx.scene.layout.StackPane.setAlignment(
                appearanceActivity, javafx.geometry.Pos.TOP_CENTER);
        javafx.scene.layout.StackPane.setMargin(appearanceActivity, new javafx.geometry.Insets(12));
        viewport.getChildren().add(appearanceActivity);
        viewport.setMinWidth(0);
        viewport.setPrefWidth(640);
        viewport.setMinHeight(320);
        viewport.setPrefHeight(420);
        viewport.getStyleClass().add("model-preview-viewport");
        viewport.setFocusTraversable(true);
        viewport.setAccessibleText("Static model preview");
        viewport.setAccessibleHelp("Arrow keys rotate. Plus or minus zoom. Home resets the view.");
        viewport.focusedProperty()
                .addListener(
                        (o, b, a) ->
                                viewport.setStyle(
                                        a
                                                ? "-fx-border-color: #137b70; -fx-border-width: 2;"
                                                : ""));
        viewport.setOnKeyPressed(
                event -> {
                    if (mesh == null) {
                        return;
                    }
                    switch (event.getCode()) {
                        case LEFT -> yaw -= .12;
                        case RIGHT -> yaw += .12;
                        case UP -> pitch = Math.max(-1.1, pitch - .08);
                        case DOWN -> pitch = Math.min(1.1, pitch + .08);
                        case PLUS, ADD, EQUALS, PAGE_UP -> zoom = Math.min(3, zoom * 1.1);
                        case MINUS, SUBTRACT, PAGE_DOWN -> zoom = Math.max(.4, zoom / 1.1);
                        case HOME -> {
                            yaw = -.3;
                            pitch = 0;
                            zoom = 1.15;
                        }
                        default -> {
                            return;
                        }
                    }
                    request();
                    event.consume();
                });
        image.setId("model-preview-image");
        viewport.setId("model-preview-viewport");
        image.setPreserveRatio(true);
        image.fitWidthProperty().bind(viewport.widthProperty());
        image.fitHeightProperty().bind(viewport.heightProperty());
        setCenter(viewport);
        Button reset = new Button("Reset view");
        reset.setOnAction(
                event -> {
                    yaw = -0.3;
                    pitch = 0;
                    zoom = 1.15;
                    request();
                });
        all.setId("model-all-surfaces");
        textures.setSelected(true);
        textures.setDisable(true);
        textures.setOnAction(event -> request());
        wireframe.setOnAction(event -> request());
        all.setOnAction(event -> request());
        idle.setId("model-idle-animation");
        idle.setAccessibleText("Play the qualified idle animation");
        idle.setTooltip(
                new Tooltip(
                        "Loop the model's recorded Stand sequence. Only bones stored in the model"
                                + " with step or linear keys are evaluated; playback uses software"
                                + " rendering."));
        idle.setOnAction(
                event -> {
                    if (idle.isSelected()) {
                        idleOrigin = System.nanoTime() - idlePaused * 1_000_000L;
                    } else {
                        idlePaused = idleMillis();
                        gpuFailed = false;
                    }
                    request();
                });
        idleStatus.setId("model-idle-status");
        idleStatus.getStyleClass().add("muted");
        idleStatus.setWrapText(true);
        resetAnimation(null);
        HBox controls = new HBox(12, reset, textures, wireframe, all, idle);
        controls.getStyleClass().add("model-preview-controls");
        setTop(controls);
        status.getStyleClass().add("muted");
        status.setWrapText(true);
        setBottom(
                new VBox(8, status, idleStatus, new HBox(8, gpuButton, gpuActivity), gpuStatus));
        viewport.setOnMousePressed(
                event -> {
                    viewport.requestFocus();
                    mouseX = event.getX();
                    mouseY = event.getY();
                });
        viewport.setOnMouseDragged(
                event -> {
                    yaw += (event.getX() - mouseX) * 0.012;
                    pitch = Math.max(-1.1, Math.min(1.1, pitch + (event.getY() - mouseY) * 0.008));
                    mouseX = event.getX();
                    mouseY = event.getY();
                    request();
                });
        viewport.setOnScroll(
                event -> {
                    zoom = Math.max(0.4, Math.min(3, zoom * Math.exp(event.getDeltaY() * 0.002)));
                    request();
                    event.consume();
                });
        resize.setOnFinished(event -> request());
        viewport.widthProperty().addListener((o, b, a) -> resize.playFromStart());
        viewport.heightProperty().addListener((o, b, a) -> resize.playFromStart());
        visibility.start();
        idleClock.start();
    }

    /**
     * Offers looping idle playback when the animation was prepared for the exact surface shown.
     * A mismatched or null animation hides the control; the static preview is unaffected.
     */
    void setAnimation(com.vibeminer.rendering.models.PreviewAnimation value, String message) {
        if (closed) return;
        boolean fits =
                value != null
                        && (appearance != null ? value.matches(appearance) : value.matches(mesh));
        if (!fits) {
            animationUnavailable(value == null ? message : MISMATCHED_SURFACE);
            return;
        }
        boolean replaced = animation != value;
        animation = value;
        idle.setVisible(true);
        idle.setManaged(true);
        idle.setDisable(false);
        showIdleStatus(message);
        if (replaced && idle.isSelected()) {
            idleOrigin = System.nanoTime();
            request();
        }
    }

    /** Hides idle playback; a non-null explanation is shown instead of an inert control. */
    void animationUnavailable(String message) {
        if (closed) return;
        boolean wasPlaying = playing();
        resetAnimation(message);
        if (wasPlaying) request();
    }

    private void resetAnimation(String message) {
        animation = null;
        idlePaused = 0;
        idle.setVisible(false);
        idle.setManaged(false);
        showIdleStatus(message);
    }

    private void showIdleStatus(String message) {
        boolean shown = message != null && !message.isBlank();
        idleStatus.setText(shown ? message : "");
        idleStatus.setVisible(shown);
        idleStatus.setManaged(shown);
    }

    private boolean playing() {
        return animation != null && idle.isSelected();
    }

    private long idleMillis() {
        return idle.isSelected()
                ? Math.max(0, (System.nanoTime() - idleOrigin) / 1_000_000L)
                : idlePaused;
    }

    void setMesh(PreviewMesh value, Set<Integer> visibleParts) {
        setMesh(value, visibleParts, null);
    }

    /**
     * Shows a static surface; a non-null message also announces that its textured appearance is
     * coming, so the inline GPU renderer waits for that scene instead of starting for this one.
     */
    void setMesh(PreviewMesh value, Set<Integer> visibleParts, String appearanceMessage) {
        endAppearance();
        if (animation != null && !animation.matches(value)) resetAnimation(null);
        closeGpu();
        mesh = value;
        gpuFailed = false;
        gpuButton.setDisable(false);
        appearance = null;
        qualifiedMaterials = false;
        textures.setDisable(true);
        visible = Set.copyOf(visibleParts);
        yaw = -0.3;
        pitch = 0;
        zoom = 1.15;
        all.setSelected(false);
        if (appearanceMessage != null) appearanceLoading(appearanceMessage);
        request();
    }

    void setAppearance(MeshAppearance value, Set<Integer> visibleParts) {
        setAppearance(value, visibleParts, false);
    }

    void setAppearance(MeshAppearance value, Set<Integer> visibleParts, boolean qualifiedOnly) {
        if (closed) return;
        if (appearanceTarget != null && appearanceTarget != value) endAppearance();
        if (animation != null && !animation.matches(value)) resetAnimation(null);
        var parts = Set.copyOf(visibleParts);
        boolean newScene =
                mesh != value.mesh()
                        || appearance != value
                        || !visible.equals(parts)
                        || qualifiedMaterials != qualifiedOnly;
        if (newScene) gpuFailed = false;
        appearance = value;
        qualifiedMaterials = qualifiedOnly;
        mesh = value.mesh();
        visible = parts;
        gpuButton.setDisable(false);
        textures.setDisable(false);
        request();
    }

    void appearanceLoading(String message) {
        if (closed) return;
        appearancePending = true;
        appearanceTarget = null;
        appearanceDelivery = null;
        appearanceStage.setText(message);
        appearanceActivity.setAccessibleText(message);
        appearanceActivity.setVisible(true);
        appearanceActivity.setManaged(true);
    }

    void presentAppearance(
            MeshAppearance value,
            Set<Integer> parts,
            java.util.function.Consumer<Boolean> delivered) {
        if (closed) return;
        appearancePending = true;
        appearanceStage.setText("Rendering composed textures…");
        appearanceActivity.setAccessibleText("Rendering composed textures");
        appearanceActivity.setVisible(true);
        appearanceActivity.setManaged(true);
        appearanceTarget = value;
        appearanceDelivery = delivered;
        setAppearance(value, parts);
    }

    /** Ends a pending appearance; a GPU renderer deferred for it starts for the static surface. */
    void appearanceUnavailable() {
        boolean deferred = gpuDeferred();
        endAppearance();
        if (deferred && !closed && mesh != null) request();
    }

    /** A static surface whose announced appearance has not arrived yet. */
    private boolean gpuDeferred() {
        return appearancePending && appearance == null;
    }

    private void endAppearance() {
        appearanceStage.setText("");
        appearanceActivity.setAccessibleText("");
        appearancePending = false;
        appearanceTarget = null;
        appearanceDelivery = null;
        appearanceActivity.setVisible(false);
        appearanceActivity.setManaged(false);
    }

    private void appearanceAccepted(MeshAppearance frameAppearance, boolean success) {
        if (!appearancePending || appearanceTarget == null || appearanceTarget != frameAppearance)
            return;
        var delivered = appearanceDelivery;
        endAppearance();
        if (delivered != null) delivered.accept(success);
    }

    void loading(String message) {
        if (closed) return;
        clear(message);
        renderingActivity.setAccessibleText(message);
        renderingActivity.setVisible(true);
        renderingActivity.setManaged(true);
    }

    void clear(String message) {
        endAppearance();
        closeGpu();
        gpuButton.setDisable(true);
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        generation.incrementAndGet();
        latest.set(null);
        mesh = null;
        appearance = null;
        resetAnimation(null);
        textures.setDisable(true);
        image.setImage(null);
        status.setText(message);
    }

    private void request() {
        if (closed || mesh == null) {
            return;
        }
        double scale =
                Math.min(
                        1,
                        Math.min(
                                960 / Math.max(1, viewport.getWidth()),
                                720 / Math.max(1, viewport.getHeight())));
        int width = Math.max(1, (int) (viewport.getWidth() * scale)),
                height = Math.max(1, (int) (viewport.getHeight() * scale));
        if (width < 10 || height < 10) {
            return;
        }
        long request = generation.incrementAndGet();
        status.setText(playing() ? "Rendering idle pose…" : "Rendering static preview…");
        boolean initialFrame = image.getImage() == null;
        renderingActivity.setVisible(initialFrame);
        renderingActivity.setManaged(initialFrame);
        latest.set(
                new Work(
                        request,
                        mesh,
                        appearance,
                        playing() ? animation : null,
                        playing() ? idleMillis() : 0,
                        textures.isSelected(),
                        qualifiedMaterials && !all.isSelected(),
                        width,
                        height,
                        new StaticMeshRenderer.View(
                                yaw,
                                pitch,
                                zoom,
                                wireframe.isSelected(),
                                all.isSelected()
                                        ? Set.of()
                                        : visible.isEmpty()
                                                ? Set.of(Integer.MIN_VALUE)
                                                : visible)));
        if (playing()) {
            if (gpuRequested) closeGpu();
            kick();
            return;
        }
        updateGpu(request, width, height);
        if (!gpuReady) kick();
        else latest.set(null);
    }

    private void kick() {
        if (closed || !running.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(
                () -> {
                    try {
                        Work work;
                        while (!closed && (work = latest.getAndSet(null)) != null) {
                            final Work current = work;
                            try {
                                var canceled =
                                        (java.util.function.BooleanSupplier)
                                                () ->
                                                        closed
                                                                || generation.get()
                                                                        != current.generation();
                                var posedMesh = work.mesh();
                                var posedAppearance = work.appearance();
                                if (work.animation() != null) {
                                    if (posedAppearance != null) {
                                        posedAppearance =
                                                work.animation()
                                                        .pose(posedAppearance, work.animationMillis());
                                        posedMesh = posedAppearance.mesh();
                                    } else {
                                        posedMesh =
                                                work.animation()
                                                        .pose(posedMesh, work.animationMillis())
                                                        .mesh();
                                    }
                                }
                                var frame =
                                        posedAppearance == null
                                                ? StaticMeshRenderer.render(
                                                        posedMesh,
                                                        work.width(),
                                                        work.height(),
                                                        work.view(),
                                                        canceled)
                                                : StaticMeshRenderer.render(
                                                        posedAppearance,
                                                        work.width(),
                                                        work.height(),
                                                        work.view(),
                                                        canceled,
                                                        work.qualifiedMaterialsOnly(),
                                                        work.textured());
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || gpuReady
                                                    || generation.get() != current.generation()) {
                                                return;
                                            }
                                            WritableImage bitmap =
                                                    new WritableImage(
                                                            frame.width(), frame.height());
                                            bitmap.getPixelWriter()
                                                    .setPixels(
                                                            0,
                                                            0,
                                                            frame.width(),
                                                            frame.height(),
                                                            PixelFormat.getIntArgbInstance(),
                                                            frame.argb(),
                                                            0,
                                                            frame.width());
                                            image.setImage(bitmap);
                                            renderingActivity.setVisible(false);
                                            renderingActivity.setManaged(false);
                                            status.setText(
                                                    "Drag to rotate · scroll or +/− to zoom · arrow"
                                                            + " keys rotate · "
                                                            + (current.animation() != null
                                                                    ? current.animation().label()
                                                                            + " idle · software"
                                                                            + " rendering"
                                                                    : "static rest surface"));
                                            appearanceAccepted(current.appearance(), true);
                                        });
                            } catch (java.util.concurrent.CancellationException superseded) {
                                /* Latest camera request replaces this frame. */
                            } catch (RuntimeException failure) {
                                Platform.runLater(
                                        () -> {
                                            if (!closed
                                                    && generation.get() == current.generation()) {
                                                renderingActivity.setVisible(false);
                                                renderingActivity.setManaged(false);
                                                status.setText(
                                                        "Model preview could not be rendered.");
                                                appearanceAccepted(current.appearance(), false);
                                            }
                                        });
                            }
                        }
                    } finally {
                        running.set(false);
                        if (!closed && latest.get() != null) {
                            kick();
                        }
                    }
                });
    }

    private boolean viewportActive() {
        if (closed
                || getScene() == null
                || getScene().getWindow() == null
                || !getScene().getWindow().isShowing()) return false;
        if (getScene().getWindow() instanceof javafx.stage.Stage stage && stage.isIconified())
            return false;
        for (javafx.scene.Node node = this; node != null; node = node.getParent())
            if (!node.isVisible()) return false;
        return true;
    }

    private void updateGpu(long revision, int width, int height) {
        if (!viewportActive() || !gpuButton.isSelected() || gpuFailed || mesh == null) return;
        // One renderer per scene: a static surface awaiting its appearance is drawn in software.
        if (gpuDeferred()) return;
        if (!gpuRequested
                || gpuMesh != mesh
                || gpuAppearance != appearance
                || !gpuParts.equals(visible)) {
            closeGpu();
            gpuRequested = true;
            gpuMesh = mesh;
            gpuAppearance = appearance;
            gpuParts = visible;
            long token = ++gpuGeneration;
            gpu.open(
                            gpuOwner,
                            mesh,
                            appearance,
                            visible,
                            state ->
                                    gpuScheduler.accept(
                                            () -> {
                                                if (closed || token != gpuGeneration) return;
                                                gpuStatus.setText(state.message());
                                                showGpuActivity(
                                                        state.busy()
                                                                || (!gpuReady
                                                                        && state.state()
                                                                                == GpuPreviewService
                                                                                        .State
                                                                                        .READY));
                                                if (state.state() == GpuPreviewService.State.FAILED
                                                        || state.state()
                                                                == GpuPreviewService.State.CLOSED) {
                                                    invalidateGpuDelivery();
                                                    gpuRequested = false;
                                                    gpuReady = false;
                                                    gpuFailed = true;
                                                    showGpuActivity(false);
                                                    request();
                                                }
                                            }),
                            frame -> deliverGpu(token, frame))
                    .whenComplete(
                            (ignored, error) -> {
                                if (error != null)
                                    gpuScheduler.accept(
                                            () -> {
                                                if (closed || token != gpuGeneration || gpuFailed)
                                                    return;
                                                invalidateGpuDelivery();
                                                gpuRequested = false;
                                                gpuReady = false;
                                                gpuFailed = true;
                                                showGpuActivity(false);
                                                gpuStatus.setText(
                                                        "GPU rendering unavailable. Using software"
                                                                + " preview.");
                                                request();
                                            });
                            });
        }
        int flags =
                (wireframe.isSelected() ? 1 : 0)
                        | (textures.isSelected() ? 2 : 0)
                        | (all.isSelected() ? 4 : 0)
                        | (qualifiedMaterials && !all.isSelected() ? 8 : 0);
        if (width != gpuWidth || height != gpuHeight || flags != gpuFlags) {
            gpuBarrier = revision;
            gpuWidth = width;
            gpuHeight = height;
            gpuFlags = flags;
        }
        gpu.update(
                gpuOwner,
                new GpuPreviewService.ViewRequest(
                        revision, width, height, yaw, pitch, zoom, flags));
    }

    private void deliverGpu(long token, GpuPreviewService.Frame frame) {
        if (closed
                || token != gpuGeneration
                || frame.controlRevision() < gpuBarrier
                || frame.controlRevision() > generation.get()) return;
        gpuFrame.submit(new PendingFrame(token, frame));
    }

    private void presentGpu(PendingFrame pendingFrame) {
        if (pendingFrame == null
                || closed
                || playing()
                || !gpuRequested
                || gpuFailed
                || pendingFrame.token() != gpuGeneration
                || !viewportActive()) return;
        var latestFrame = pendingFrame.frame();
        if (latestFrame.controlRevision() < gpuBarrier
                || latestFrame.controlRevision() > generation.get()
                || latestFrame.controlRevision() <= gpuPresented) return;
        long started = System.nanoTime();
        WritableImage bitmap =
                image.getImage() instanceof WritableImage existing
                                && (int) existing.getWidth() == latestFrame.width()
                                && (int) existing.getHeight() == latestFrame.height()
                        ? existing
                        : new WritableImage(latestFrame.width(), latestFrame.height());
        bitmap.getPixelWriter()
                .setPixels(
                        0,
                        0,
                        latestFrame.width(),
                        latestFrame.height(),
                        PixelFormat.getIntArgbPreInstance(),
                        latestFrame.pixels(),
                        latestFrame.width());
        image.setImage(bitmap);
        gpuPresented = latestFrame.controlRevision();
        gpuReady = true;
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        showGpuActivity(false);
        status.setText(
                "Drag to rotate · scroll or +/− to zoom · arrow keys rotate ·"
                        + " static rest surface");
        gpuStatus.setText(
                "Inline GPU · "
                        + latestFrame.width()
                        + " × "
                        + latestFrame.height()
                        + " · copy "
                        + String.format(
                                java.util.Locale.ROOT,
                                "%.1f ms",
                                latestFrame.bridgeNanos() / 1_000_000.0)
                        + " · upload "
                        + String.format(
                                java.util.Locale.ROOT,
                                "%.1f ms",
                                (System.nanoTime() - started) / 1_000_000.0));
        appearanceAccepted(gpuAppearance, true);
    }

    private void invalidateGpuDelivery() {
        ++gpuGeneration;
        gpuFrame.clear();
    }

    private void closeGpu() {
        gpuRequested = false;
        gpuReady = false;
        gpuPresented = 0;
        gpuFlags = -1;
        gpuBarrier = generation.get();
        gpuMesh = null;
        gpuAppearance = null;
        gpuParts = Set.of();
        invalidateGpuDelivery();
        showGpuActivity(false);
        gpu.closeOwner(gpuOwner);
    }

    private void showGpuActivity(boolean active) {
        gpuActivity.setVisible(active);
        gpuActivity.setManaged(active);
    }

    @Override
    public void close() {
        endAppearance();
        closeGpu();
        closed = true;
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        resize.stop();
        visibility.stop();
        idleClock.stop();
        animation = null;
        generation.incrementAndGet();
        latest.set(null);
        mesh = null;
        appearance = null;
        textures.setDisable(true);
        image.setImage(null);
    }
}
