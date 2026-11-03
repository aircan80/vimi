package com.vimi.gui;

import com.vibeminer.rendering.models.MeshAppearance;
import com.vibeminer.rendering.models.PreviewMesh;
import com.vibeminer.rendering.models.StaticMeshRenderer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** Reusable actual static-mesh view, independent of JavaFX3D hardware support. */
final class ModelPreviewView extends BorderPane implements AutoCloseable {
    private record Work(
            long generation,
            PreviewMesh mesh,
            MeshAppearance appearance,
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
    private PreviewMesh mesh;
    private MeshAppearance appearance;
    private Set<Integer> visible = Set.of();
    private double yaw = -0.3, pitch, zoom = 1.15, mouseX, mouseY;
    private volatile boolean closed;
    private final javafx.animation.PauseTransition resize =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(35));

    ModelPreviewView() {
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
        setBottom(status);
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
    }

    void setMesh(PreviewMesh value, Set<Integer> visibleParts) {
        mesh = value;
        appearance = null;
        textures.setDisable(true);
        visible = Set.copyOf(visibleParts);
        yaw = -0.3;
        pitch = 0;
        zoom = 1.15;
        all.setSelected(false);
        request();
    }

    void setAppearance(MeshAppearance value, Set<Integer> visibleParts) {
        appearance = value;
        mesh = value.mesh();
        visible = Set.copyOf(visibleParts);
        textures.setDisable(false);
        request();
    }

    void clear(String message) {
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
        int width = Math.max(1, Math.min(1280, (int) viewport.getWidth())),
                height = Math.max(1, Math.min(900, (int) viewport.getHeight()));
        if (width < 10 || height < 10) {
            return;
        }
        long request = generation.incrementAndGet();
        latest.set(
                new Work(
                        request,
                        mesh,
                        textures.isSelected() ? appearance : null,
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
        kick();
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
                                                        canceled);
                                Platform.runLater(
                                        () -> {
                                            if (closed
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
                                            status.setText(
                                                    "Drag to rotate · scroll or +/− to zoom · arrow keys rotate · static rest surface");
                                        });
                            } catch (java.util.concurrent.CancellationException superseded) {
                                /* Latest camera request replaces this frame. */
                            } catch (RuntimeException failure) {
                                Platform.runLater(
                                        () -> {
                                            if (!closed
                                                    && generation.get() == current.generation()) {
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

    @Override
    public void close() {
        closed = true;
        resize.stop();
        generation.incrementAndGet();
        latest.set(null);
        mesh = null;
        appearance = null;
        textures.setDisable(true);
        image.setImage(null);
    }
}
