package com.vimi.gui;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Full-screen World viewport. The viewport node itself moves into an owned full-screen stage, so
 * its key, mouse and overlay wiring keep working, and moves back when full screen ends: by F11
 * or Alt+Enter, by Escape (the platform leaves full screen), by closing the stage, or when the
 * view leaves the explorer. The application window and its layout are not touched. FX thread.
 */
final class WorldFullscreen {
    static final String EXIT_HINT = "F11, Alt+Enter or Esc leaves full screen";

    private final Region viewport;
    private final Consumer<Region> restore;
    private final EventHandler<KeyEvent> keys;
    private final Node overlay;
    private Stage stage;
    private boolean windowed;

    /**
     * {@code restore} puts the viewport back into the explorer layout; {@code keys} is the view's
     * full-screen key filter, installed on the full-screen scene too; {@code overlay} is a small
     * status line shown over the viewport while full screen.
     */
    WorldFullscreen(Region viewport, Consumer<Region> restore, EventHandler<KeyEvent> keys, Node overlay) {
        this.viewport = Objects.requireNonNull(viewport);
        this.restore = Objects.requireNonNull(restore);
        this.keys = Objects.requireNonNull(keys);
        this.overlay = Objects.requireNonNull(overlay);
    }

    /** Tests: show the stage as an ordinary window instead of taking over the screen. */
    void windowed(boolean value) {
        windowed = value;
    }

    boolean active() {
        return stage != null;
    }

    /** The stage showing the viewport while full screen; for tests. */
    Stage stage() {
        return stage;
    }

    void toggle(Window owner, String title) {
        if (active()) exit();
        else enter(owner, title);
    }

    void enter(Window owner, String title) {
        if (active()) return;
        var root = new StackPane();
        root.setStyle("-fx-background-color: black;");
        var next = new Stage();
        if (owner != null) next.initOwner(owner);
        next.setTitle(title);
        var scene = new Scene(root);
        if (owner != null && owner.getScene() != null)
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        scene.addEventFilter(KeyEvent.ANY, keys);
        next.setScene(scene);
        next.setFullScreenExitHint(EXIT_HINT);
        stage = next;
        root.getChildren().addAll(viewport, overlay);
        StackPane.setAlignment(overlay, javafx.geometry.Pos.BOTTOM_LEFT);
        StackPane.setMargin(overlay, new javafx.geometry.Insets(8));
        overlay.setVisible(true);
        next.fullScreenProperty().addListener((o, was, full) -> {
            if (!full && stage == next) exit();
        });
        next.setOnCloseRequest(e -> {
            if (stage == next) exit();
        });
        if (windowed) {
            next.setWidth(640);
            next.setHeight(400);
        } else {
            next.setFullScreen(true);
        }
        next.show();
        viewport.requestFocus();
    }

    /** Returns the viewport to the explorer and closes the full-screen stage; idempotent. */
    void exit() {
        var current = stage;
        if (current == null) return;
        stage = null;
        if (current.getScene().getRoot() instanceof StackPane root) root.getChildren().removeAll(viewport, overlay);
        overlay.setVisible(false);
        restore.accept(viewport);
        current.close();
        viewport.requestFocus();
    }
}
