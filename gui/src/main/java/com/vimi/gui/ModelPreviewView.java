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
        all.setTooltip(new Tooltip("Show every recorded mesh section, including alternate parts and sections without supported materials. Unresolved materials appear neutral."));
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
        HBox controls = new HBox(12, reset, textures, wireframe, all);
        controls.getStyleClass().add("model-preview-controls");
        setTop(controls);
        status.getStyleClass().add("muted");
        status.setWrapText(true);
        setBottom(new VBox(8, status, new HBox(8, gpuButton, gpuActivity), gpuStatus));
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
    }

    void setMesh(PreviewMesh value, Set<Integer> visibleParts) {
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
        request();
    }

    void setAppearance(MeshAppearance value, Set<Integer> visibleParts) {
        setAppearance(value, visibleParts, false);
    }

    void setAppearance(MeshAppearance value, Set<Integer> visibleParts, boolean qualifiedOnly) {
        if (closed) return;
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

    void loading(String message) {
        if (closed) return;
        clear(message);
        renderingActivity.setAccessibleText(message);
        renderingActivity.setVisible(true);
        renderingActivity.setManaged(true);
    }

    void clear(String message) {
        closeGpu();
        gpuButton.setDisable(true);
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        generation.incrementAndGet();
        latest.set(null);
        mesh = null;
        appearance = null;
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
        status.setText("Rendering static preview…");
        boolean initialFrame = image.getImage() == null;
        renderingActivity.setVisible(initialFrame);
        renderingActivity.setManaged(initialFrame);
        latest.set(
                new Work(
                        request,
                        mesh,
                        appearance,
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
                                var frame =
                                        work.appearance() == null
                                                ? StaticMeshRenderer.render(
                                                        work.mesh(),
                                                        work.width(),
                                                        work.height(),
                                                        work.view(),
                                                        canceled)
                                                : StaticMeshRenderer.render(
                                                        work.appearance(),
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
                                                            + " keys rotate · static rest surface");
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
        closeGpu();
        closed = true;
        renderingActivity.setVisible(false);
        renderingActivity.setManaged(false);
        resize.stop();
        visibility.stop();
        generation.incrementAndGet();
        latest.set(null);
        mesh = null;
        appearance = null;
        textures.setDisable(true);
        image.setImage(null);
    }
}
