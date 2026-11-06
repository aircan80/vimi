package com.vimi.gui;

import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The World HUD drawn over the explorer viewport. It holds the top-left Map button and map name,
 * the status rows under them, and the map panel that the Map button or the map key opens on the
 * left.
 *
 * <p>Every widget follows {@link WorldMinimap}'s event pattern. No widget is focus-traversable.
 * Key, mouse and scroll events on a widget stop at the HUD and never reach flight input or
 * inspection. After a button click, focus goes back to the viewport so movement keys keep
 * working. The panel is the one exception: its search field and map list take focus while the
 * user works in them.
 *
 * <p>Hook points for other HUD pieces: {@link #topLeft()} takes more rows under the map name
 * (e.g. a developer overlay), and {@link #hiddenProperty()} is the single "HUD hidden" state that
 * other overlays (e.g. the minimap cluster) can follow. FX thread only.
 */
final class WorldHud extends StackPane {
    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("selected-panel");

    private final Button map = new Button("Map");
    private final Label mapName = new Label();
    private final VBox status = new VBox(4);
    private final VBox topLeft;
    private final VBox panel = new VBox(10);
    private final Runnable refocus;
    private final BooleanProperty hidden = new SimpleBooleanProperty(this, "hidden");

    /**
     * {@code onMap} runs when the Map button is pressed (the view opens or closes the panel);
     * {@code refocus} gives keyboard focus back to the viewport.
     */
    WorldHud(Runnable onMap, Runnable refocus) {
        Objects.requireNonNull(onMap);
        this.refocus = Objects.requireNonNull(refocus);
        setId("world-hud");
        setFocusTraversable(false);
        setPickOnBounds(false);
        var css = WorldHud.class.getResource("world-hud.css");
        if (css != null) getStylesheets().add(css.toExternalForm());
        visibleProperty().bind(hidden.not());

        map.setId("world-hud-map");
        map.getStyleClass().add("world-hud-map-button");
        map.setFocusTraversable(false);
        map.setAccessibleText("Map panel: map list and the way back to the tile picker");
        map.setOnAction(e -> {
            onMap.run();
            refocus.run();
        });
        stopInput(map);
        mapName.setId("world-hud-map-name");
        mapName.getStyleClass().add("world-hud-map-name");
        mapName.setMouseTransparent(true);
        var identity = new HBox(10, map, mapName);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setPickOnBounds(false);
        identity.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        status.setId("world-hud-status");
        status.getStyleClass().add("world-hud-chip");
        status.setMouseTransparent(true);
        status.setMaxWidth(520);
        status.setFillWidth(true);
        status.managedProperty().bind(status.visibleProperty());
        status.setVisible(false);
        status.getChildren().addListener(
                (javafx.collections.ListChangeListener<Node>) change -> status.setVisible(!status.getChildren().isEmpty()));

        topLeft = new VBox(6, identity, status);
        topLeft.setId("world-hud-top-left");
        topLeft.setPickOnBounds(false);
        topLeft.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(topLeft, Pos.TOP_LEFT);
        StackPane.setMargin(topLeft, new Insets(10));

        panel.setId("world-map-panel");
        panel.getStyleClass().add("world-map-panel");
        panel.setFocusTraversable(false);
        panel.setPrefWidth(340);
        panel.setMaxWidth(380);
        panel.setMinWidth(240);
        panel.setVisible(false);
        panel.managedProperty().bind(panel.visibleProperty());
        stopInput(panel);
        StackPane.setAlignment(panel, Pos.TOP_LEFT);
        StackPane.setMargin(panel, new Insets(52, 0, 10, 10));

        getChildren().addAll(topLeft, panel);
    }

    /**
     * Lets a control handle its own input first, then stops the event here so it never bubbles
     * into the viewport's flight keys, mouse look, wheel dolly or click inspection.
     */
    static void stopInput(Node node) {
        node.addEventHandler(KeyEvent.ANY, KeyEvent::consume);
        node.addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        node.addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
    }

    /** The current map's name, top-left next to the Map button. */
    void mapName(String text) {
        mapName.setText(text == null ? "" : text);
    }

    Label mapNameLabel() {
        return mapName;
    }

    /** The key that opens the panel, shown on the Map button ("Map · M"); null when unbound. */
    void mapKey(String key) {
        map.setText(key == null || key.isBlank() ? "Map" : "Map · " + key);
    }

    /** The rows under the map name: the view's status line and loading row while exploring. */
    void status(Node... rows) {
        status.getChildren().setAll(rows);
    }

    /** Takes the status rows out again (the view puts them back above the tile picker). */
    void clearStatus() {
        status.getChildren().clear();
    }


    /** Places {@code node} top-centre (the zone line). */
    void topCenter(Node node) {
        place(node, Pos.TOP_CENTER, new Insets(10, 220, 0, 220));
    }

    /** Places {@code node} bottom-left (the status icon row). */
    void bottomLeft(Node node) {
        place(node, Pos.BOTTOM_LEFT, new Insets(10));
    }

    /** Places {@code node} bottom-right (the coordinates pill). */
    void bottomRight(Node node) {
        place(node, Pos.BOTTOM_RIGHT, new Insets(10));
    }

    /** Places a banner centred a quarter of the way down the view; it never takes input. */
    void banner(Region node) {
        node.setMouseTransparent(true);
        node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        node.translateYProperty().bind(heightProperty().divide(-4));
        place(node, Pos.CENTER, Insets.EMPTY);
    }

    private void place(Node node, Pos where, Insets margin) {
        StackPane.setAlignment(node, where);
        StackPane.setMargin(node, margin);
        // Keep the map panel on top of everything else in the HUD.
        getChildren().add(getChildren().indexOf(panel), node);
    }

    /** Rows under the map name; a hook for other HUD pieces placed top-left. */
    VBox topLeft() {
        return topLeft;
    }

    boolean panelShown() {
        return panel.isVisible();
    }

    /** Opens the map panel holding {@code body} (the view's map list and its way back). */
    void showPanel(Node... body) {
        var title = new Label("MAP");
        title.setStyle("-fx-font-weight: bold;");
        panel.getChildren().setAll(title);
        panel.getChildren().addAll(body);
        if (body.length > 0) VBox.setVgrow(body[body.length - 1], javafx.scene.layout.Priority.ALWAYS);
        panel.setVisible(true);
        map.pseudoClassStateChanged(OPEN, true);
    }

    /** Closes the map panel and detaches what it held; idempotent. */
    void hidePanel() {
        panel.getChildren().clear();
        panel.setVisible(false);
        map.pseudoClassStateChanged(OPEN, false);
    }

    /** The single "HUD hidden" state (Alt+Z); toasts and developer pages do not follow it. */
    BooleanProperty hiddenProperty() {
        return hidden;
    }

    boolean hidden() {
        return hidden.get();
    }

    void hidden(boolean value) {
        hidden.set(value);
    }

    /** Gives focus back to the viewport, e.g. after a HUD click. */
    void refocus() {
        refocus.run();
    }
}
