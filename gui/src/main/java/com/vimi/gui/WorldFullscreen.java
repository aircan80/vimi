package com.vimi.gui;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.Scene;
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
    private Stage stage;
    private boolean windowed;

    /** {@code restore} puts the viewport back into the explorer layout. */
    WorldFullscreen(Region viewport, Consumer<Region> restore) {
        this.viewport = Objects.requireNonNull(viewport);
        this.restore = Objects.requireNonNull(restore);
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
        next.setScene(new Scene(root));
        next.setFullScreenExitHint(EXIT_HINT);
        stage = next;
        root.getChildren().add(viewport);
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
        if (current.getScene().getRoot() instanceof StackPane root) root.getChildren().remove(viewport);
        restore.accept(viewport);
        current.close();
        viewport.requestFocus();
    }
}
