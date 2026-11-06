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
 * moved here unchanged. Keys typed in the popup stay in it (Escape closes it), so they never
 * become held flight keys; focus goes back to the viewport when it closes. FX-owned.
 */
final class WorldMinimapTracking {
    private final Popup popup = new Popup();
    private final VBox spawnSlot = new VBox(), roleSlot = new VBox();
    private final CheckBox entrances = new CheckBox("Instance entrances");
    private Runnable refocus = () -> {};

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
        // Handle first, then keep every key, click and scroll from being redirected to the World
        // viewport behind it. Escape closes the popup here rather than reaching the viewport.
        content.addEventHandler(KeyEvent.ANY, event -> {
            if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == KeyCode.ESCAPE) popup.hide();
            event.consume();
        });
        content.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        content.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
        popup.getContent().add(content);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setConsumeAutoHidingEvents(true);
        popup.setOnHidden(event -> refocus.run());
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
