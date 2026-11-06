package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Transient World explorer notices, bottom-centre over the viewport: each shows for {@link
 * #SHOW_NANOS} and fades over its last {@link #FADE_NANOS}; at most {@link #LIMIT} are stacked
 * and the oldest leaves first. Showing a text that is already up restarts it instead of stacking
 * a copy. A toast may carry one action button ("Retry", …); it is the only clickable part, never
 * takes focus, keeps its events from the viewport's flight handlers and hands focus back to the
 * viewport after its action. Time comes from a clock, so tests drive it; a running timer prunes
 * only while toasts are up. FX-owned.
 */
final class WorldToasts extends VBox {
    static final long SHOW_NANOS = 4_000_000_000L, FADE_NANOS = 300_000_000L;
    static final int LIMIT = 3;

    /** One shown notice; {@code action} is null when it has no button. */
    record Toast(String text, String action, Runnable run) {}

    private final class Shown {
        final Toast toast;
        final javafx.scene.layout.StackPane node;
        long since;

        Shown(Toast toast, javafx.scene.layout.StackPane node, long since) {
            this.toast = toast;
            this.node = node;
            this.since = since;
        }
    }

    private final LongSupplier clock;
    private final List<Shown> shown = new ArrayList<>();
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long ignored) {
            pulse();
        }
    };
    private Runnable refocus = () -> {};

    WorldToasts() {
        this(System::nanoTime);
    }

    WorldToasts(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        setId("world-toasts");
        setSpacing(6);
        setAlignment(Pos.BOTTOM_CENTER);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        // Only action buttons take the mouse; clicks elsewhere reach the world.
        setPickOnBounds(false);
        setFocusTraversable(false);
        setVisible(false);
    }

    /** Gives keyboard focus back to the viewport after an action. */
    void refocus(Runnable next) {
        refocus = Objects.requireNonNull(next);
    }

    /** Shows a notice without an action. */
    void show(String text) {
        show(text, null, null);
    }

    /** Shows a notice; {@code action} labels a button that runs {@code run} (both or neither). */
    void show(String text, String action, Runnable run) {
        if (text == null || text.isBlank()) return;
        if ((action == null) != (run == null)) throw new IllegalArgumentException("Action and run go together");
        long now = clock.getAsLong();
        for (var each : shown) {
            if (each.toast.text().equals(text) && Objects.equals(each.toast.action(), action)) {
                each.since = now;
                // A repeat moves to the newest place, so the oldest still leaves first.
                shown.remove(each);
                shown.add(each);
                layoutToasts();
                pulse();
                return;
            }
        }
        var toast = new Toast(text.strip(), action, run);
        shown.add(new Shown(toast, node(toast), now));
        while (shown.size() > LIMIT) shown.removeFirst();
        layoutToasts();
        timer.start();
        pulse();
    }

    private javafx.scene.layout.StackPane node(Toast toast) {
        var label = new Label(toast.text());
        label.setWrapText(true);
        label.setMaxWidth(520);
        label.setStyle("-fx-text-fill: #eef3f4; -fx-font-size: 12px;");
        label.setMouseTransparent(true);
        var content = new HBox(10, label);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPickOnBounds(false);
        content.setStyle("-fx-padding: 6 12 6 12;");
        // The dark backing is its own mouse-transparent layer: only the button picks.
        var backing = new javafx.scene.layout.Region();
        backing.setMouseTransparent(true);
        backing.setStyle("-fx-background-color: rgba(10,16,20,0.86); -fx-background-radius: 6;");
        var toastNode = new javafx.scene.layout.StackPane(backing, content);
        toastNode.getStyleClass().add("world-toast");
        toastNode.setPickOnBounds(false);
        toastNode.setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        if (toast.action() != null) {
            var button = new Button(toast.action());
            button.getStyleClass().add("world-toast-action");
            button.setFocusTraversable(false);
            button.setStyle("-fx-font-size: 11px;");
            button.setOnAction(event -> {
                toast.run().run();
                dismiss(toast);
                refocus.run();
            });
            button.addEventHandler(KeyEvent.ANY, KeyEvent::consume);
            button.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
            button.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
            content.getChildren().add(button);
        }
        return toastNode;
    }

    private void dismiss(Toast toast) {
        shown.removeIf(each -> each.toast == toast);
        layoutToasts();
        if (shown.isEmpty()) timer.stop();
    }

    /** Drops expired toasts and fades the ones in their last moments; the timer calls it. */
    void pulse() {
        long now = clock.getAsLong();
        boolean removed = shown.removeIf(each -> now - each.since >= SHOW_NANOS);
        for (var each : shown) {
            long left = SHOW_NANOS - (now - each.since);
            each.node.setOpacity(left >= FADE_NANOS ? 1 : Math.max(0, (double) left / FADE_NANOS));
        }
        if (removed) layoutToasts();
        if (shown.isEmpty()) timer.stop();
    }

    private void layoutToasts() {
        var nodes = new ArrayList<javafx.scene.Node>(shown.size());
        for (var each : shown) nodes.add(each.node);
        getChildren().setAll(nodes);
        setVisible(!shown.isEmpty());
    }

    /** The shown toasts, oldest first; for tests and the HUD. */
    List<Toast> toasts() {
        return shown.stream().map(each -> each.toast).toList();
    }

    /** Opacity of each shown toast, oldest first; for tests. */
    List<Double> opacities() {
        return shown.stream().map(each -> each.node.getOpacity()).toList();
    }

    /** Removes every toast, e.g. when the explorer closes. */
    void clear() {
        shown.clear();
        layoutToasts();
        timer.stop();
    }
}
