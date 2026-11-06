package com.vimi.gui;

import java.util.Objects;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * The minimap's tracking popup: one place for the map layers (NPC spawns, minimap roles with their
 * faction filter, instance entrances). The spawn and role controls are the overlays' own nodes,
 * moved here unchanged.
 *
 * <p>A popup is never natively focused: keys go to the owner scene's focus owner (the viewport),
 * and the owner window redirects each one to the popup's content first. The content therefore
 * consumes key presses, so nothing typed while it is open starts flight, but lets releases
 * through, so a key held when it opened is released normally. The full-screen and panel keys
 * (F11, Alt+Enter, Alt+Z) also pass, so they work while it is open. Escape closes it. Opening it
 * calls {@link #onShowing} (the view drops held flight input); focus goes back to the viewport
 * when it closes. FX-owned.
 */
final class WorldMinimapTracking {
    private final Popup popup = new Popup();
    private final VBox spawnSlot = new VBox(), roleSlot = new VBox();
    private final CheckBox entrances = new CheckBox("Instance entrances");
    private Runnable refocus = () -> {}, showing = () -> {};
    // Keys bound to HUD commands pass through to the viewport like the layout keys.
    private java.util.function.Predicate<KeyEvent> passes = event -> false;
    private java.util.function.Consumer<KeyEvent> forward;

    WorldMinimapTracking() {
        var title = new Label("TRACKING");
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: white;");
        entrances.setId("world-minimap-entrances");
        entrances.setFocusTraversable(false);
        entrances.setTextFill(WorldMinimap.ENTRANCE);
        entrances.setAccessibleText("Show recorded instance entrances on the minimap");
        var content = new VBox(6, title, spawnSlot, new Separator(), roleSlot, new Separator(), entrances);
        content.setId("world-minimap-tracking-popup");
        content.setMaxWidth(380);
        content.setStyle("-fx-background-color: rgba(14,24,31,0.95); -fx-background-radius: 8;"
                + " -fx-padding: 10; -fx-base: #24343d; -fx-text-fill: white;");
        // HUD command keys go straight to the viewport's key handling, before any popup control
        // sees them: a command bound to Space or Enter must not also toggle a focused checkbox.
        javafx.event.EventHandler<KeyEvent> commands = event -> {
            if (forward == null || event.getEventType() == KeyEvent.KEY_TYPED || !passes.test(event)) return;
            event.consume();
            forward.accept(event);
        };
        // Handle first, then stop presses (but the layout keys) from reaching the viewport.
        javafx.event.EventHandler<KeyEvent> keep = event -> {
            if (event.getEventType() == KeyEvent.KEY_RELEASED || layoutKey(event) || passes.test(event)) return;
            if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == KeyCode.ESCAPE) popup.hide();
            event.consume();
        };
        content.addEventFilter(KeyEvent.ANY, commands);
        content.addEventHandler(KeyEvent.ANY, keep);
        // Also on the popup's scene: with no popup node focused, keys target the scene itself and
        // never pass through the content.
        popup.getScene().addEventFilter(KeyEvent.ANY, commands);
        popup.getScene().addEventHandler(KeyEvent.ANY, keep);
        content.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        content.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
        popup.getContent().add(content);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setConsumeAutoHidingEvents(true);
        popup.setOnShowing(event -> showing.run());
        popup.setOnHidden(event -> refocus.run());
    }

    /** F11, Alt+Enter and Alt+Z: the view's full-screen and panel keys, honoured while open. */
    static boolean layoutKey(KeyEvent event) {
        var code = event.getCode();
        return code == KeyCode.F11 || event.isAltDown() && (code == KeyCode.ENTER || code == KeyCode.Z);
    }

    /** Keys that pass through to the viewport while open (the view's HUD command keys). */
    void passThrough(java.util.function.Predicate<KeyEvent> next) {
        passes = Objects.requireNonNull(next);
    }

    /**
     * Where passing keys go: the view's own key handling, called directly. Without it they
     * still reach the viewport through the owner window, after the popup's controls.
     */
    void forward(java.util.function.Consumer<KeyEvent> next) {
        forward = Objects.requireNonNull(next);
    }

    /** Runs when the popup opens, e.g. to drop held flight input. */
    void onShowing(Runnable next) {
        showing = Objects.requireNonNull(next);
    }

    /** Gives focus back to the viewport when the popup closes. */
    void refocus(Runnable next) {
        refocus = Objects.requireNonNull(next);
    }

    /** Mounts the NPC spawn controls (the first row). */
    void spawns(Node controls) {
        spawnSlot.getChildren().setAll(controls);
    }

    /** Mounts the minimap role controls, faction filter included. */
    void roles(Node controls) {
        roleSlot.getChildren().setAll(controls);
    }

    CheckBox entrances() {
        return entrances;
    }

    /** Opens the popup under {@code anchor}, or closes it when it is open. */
    void toggle(Node anchor) {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (anchor.getScene() == null || anchor.getScene().getWindow() == null) return;
        Bounds at = anchor.localToScreen(anchor.getBoundsInLocal());
        if (at == null) return;
        popup.show(anchor, at.getMaxX() - 380, at.getMaxY() + 4);
    }

    boolean showing() {
        return popup.isShowing();
    }

    void hide() {
        popup.hide();
    }

    /** The popup window; for tests. */
    Popup popup() {
        return popup;
    }
}
