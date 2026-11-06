package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Short event notices over the World view, bottom-centre: each stays {@link #LIFETIME}, at most
 * {@link #LIMIT} are stacked (the oldest leaves first), and each may carry one action button
 * ("Retry"). They are not part of the HUD, so hiding the HUD keeps them. Nothing here is
 * focus-traversable; an action hands focus back to the viewport. FX thread only.
 */
final class WorldToasts {
    static final Duration LIFETIME = Duration.seconds(4);
    static final int LIMIT = 3;

    /** One notice; {@link #expire()} is what its timer runs. */
    final class Toast {
        final String text;
        final HBox node;
        final PauseTransition timer = new PauseTransition(LIFETIME);

        private Toast(String text, String action, Runnable onAction) {
            this.text = text;
            var label = new Label(text);
            label.setWrapText(true);
            label.setMaxWidth(520);
            node = new HBox(10, label);
            node.getStyleClass().add("world-hud-chip");
            node.setAlignment(Pos.CENTER_LEFT);
            node.setMaxWidth(Region.USE_PREF_SIZE);
            if (action != null) {
                var button = new Button(action);
                button.getStyleClass().add("world-hud-map-button");
                button.setFocusTraversable(false);
                button.setOnAction(e -> {
                    expire();
                    onAction.run();
                    refocus.run();
                });
                node.getChildren().add(button);
            }
            WorldHud.stopInput(node);
            timer.setOnFinished(e -> expire());
        }

        /** Takes the notice away (its time is up, it was pushed out, or its action ran). */
        void expire() {
            timer.stop();
            if (shown.remove(this)) column.getChildren().remove(node);
        }
    }

    private final VBox column = new VBox(6);
    private final List<Toast> shown = new ArrayList<>();
    private final Runnable refocus;

    WorldToasts(Runnable refocus) {
        this.refocus = Objects.requireNonNull(refocus);
        column.setId("world-toasts");
        column.setAlignment(Pos.BOTTOM_CENTER);
        column.setPickOnBounds(false);
        column.setFocusTraversable(false);
        column.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        var css = WorldToasts.class.getResource("world-hud.css");
        if (css != null) column.getStylesheets().add(css.toExternalForm());
    }

    VBox node() {
        return column;
    }

    /** Shows a notice without an action. */
    Toast show(String text) {
        return show(text, null, null);
    }

    /** Shows a notice; {@code action} labels a button that runs {@code onAction} (both or neither). */
    Toast show(String text, String action, Runnable onAction) {
        Objects.requireNonNull(text);
        if ((action == null) != (onAction == null)) throw new IllegalArgumentException("action and onAction");
        // The same notice again only restarts its time; it is not stacked twice.
        for (var toast : shown) {
            if (toast.text.equals(text)) {
                toast.timer.playFromStart();
                return toast;
            }
        }
        while (shown.size() >= LIMIT) shown.getFirst().expire();
        var toast = new Toast(text, action, onAction);
        shown.add(toast);
        column.getChildren().add(toast.node);
        toast.timer.playFromStart();
        return toast;
    }

    /** The notices on show, oldest first; for tests. */
    List<Toast> shown() {
        return List.copyOf(shown);
    }

    void clear() {
        for (var toast : List.copyOf(shown)) toast.expire();
    }
}
