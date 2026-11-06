package com.vimi.gui;

import java.util.Objects;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
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
 * <p>Hook points for other HUD pieces:
 *
 * <ul>
 *   <li>{@link #addTopLeft} adds a row under the status line, above the map panel, in the same
 *       left column, so the panel never covers it. The row hides with the HUD.
 *   <li>{@link #devOverlay} puts the developer overlay in its own slot in that column, under the
 *       rows and above the map panel. The slot does not follow the hidden state: the overlay
 *       stays visible, at the top of the column, while the HUD is hidden. Toasts have their own
 *       layer outside the HUD.
 *   <li>{@link #hiddenProperty()} is the single, read-only "HUD hidden" state. Followers that
 *       manage their own visibility (the minimap shows and hides itself) must not bind
 *       {@code visible} to it: AND it with their own state in a listener, or bind {@code opacity}
 *       and {@code mouseTransparent} instead.
 * </ul>
 *
 * <p>FX thread only.
 */
final class WorldHud extends StackPane {
    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("selected-panel");

    private final Button map = new Button("Map");
    private final Label mapName = new Label();
    private final VBox status = new VBox(4);
    private final VBox topLeft;
    private final VBox column;
    private final StackPane devSlot = new StackPane();
    private final VBox panel = new VBox(10);
    private final Runnable refocus;
    private final ReadOnlyBooleanWrapper hidden = new ReadOnlyBooleanWrapper(this, "hidden");

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

        // The rows that hide with the HUD: the Map row, the status rows and any added rows.
        topLeft = new VBox(6, identity, status);
        topLeft.setId("world-hud-top-left");
        topLeft.setPickOnBounds(false);
        topLeft.setFillWidth(false);
        topLeft.visibleProperty().bind(hidden.not());
        topLeft.managedProperty().bind(topLeft.visibleProperty());
        // The developer overlay's slot: it stays when the HUD is hidden, and takes no room while
        // it holds nothing shown.
        devSlot.setId("world-hud-dev-slot");
        devSlot.setPickOnBounds(false);
        devSlot.setFocusTraversable(false);
        devSlot.setAlignment(Pos.TOP_LEFT);
        devSlot.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        devSlot.setVisible(false);
        devSlot.managedProperty().bind(devSlot.visibleProperty());
        // One left column: the rows, the developer overlay, then the map panel, which takes the
        // height that is left, so it never covers what sits above it. Hiding the HUD closes the
        // panel (the view does that), so only the slot can show while hidden.
        column = new VBox(6, topLeft, devSlot, panel);
        column.setId("world-hud-left");
        column.setPickOnBounds(false);
        column.setFillWidth(false);
        column.setMaxWidth(Region.USE_PREF_SIZE);
        VBox.setVgrow(panel, javafx.scene.layout.Priority.ALWAYS);
        StackPane.setAlignment(column, Pos.TOP_LEFT);
        StackPane.setMargin(column, new Insets(10));

        panel.setId("world-map-panel");
        panel.getStyleClass().add("world-map-panel");
        panel.setFocusTraversable(false);
        panel.setPrefWidth(340);
        panel.setMaxWidth(380);
        panel.setMinWidth(240);
        panel.setVisible(false);
        panel.managedProperty().bind(panel.visibleProperty());
        panel.setMaxHeight(Double.MAX_VALUE);
        stopInput(panel);

        getChildren().add(column);
    }

    /**
     * Puts {@code overlay} in the developer-overlay slot, under the left column's rows and above
     * the map panel, replacing whatever was there. The slot ignores the hidden state, so the
     * overlay shows whenever it is visible itself; the slot takes no room while it is not. The
     * overlay keeps its own input handling (its buttons stop their events and refocus).
     */
    void devOverlay(Node overlay) {
        Objects.requireNonNull(overlay);
        devSlot.visibleProperty().unbind();
        devSlot.getChildren().setAll(overlay);
        devSlot.visibleProperty().bind(overlay.visibleProperty());
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

    /**
     * Adds a row to the left column, under the status rows and above the map panel. The row gets
     * the HUD input rules ({@link #stopInput}); buttons in it should still hand focus back with
     * {@link #refocus()} after a click.
     */
    void addTopLeft(Node row) {
        Objects.requireNonNull(row);
        if (row.getParent() == topLeft) return;
        stopInput(row);
        topLeft.getChildren().add(row);
    }

    /** Removes a row added with {@link #addTopLeft}; the Map row and status stay. */
    void removeTopLeft(Node row) {
        if (row == status || row == topLeft.getChildren().getFirst()) return;
        topLeft.getChildren().remove(row);
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

    /**
     * The single "HUD hidden" state (Alt+Z or the hide HUD key, H by default), read-only: only the view sets it, through
     * {@link #hidden(boolean)}. See the class notes for how followers should use it.
     */
    ReadOnlyBooleanProperty hiddenProperty() {
        return hidden.getReadOnlyProperty();
    }

    boolean hidden() {
        return hidden.get();
    }

    void hidden(boolean value) {
        // The panel is not under the hidden binding (it shares the column with the developer
        // slot), so hiding closes it; the view closes it first and resets what it held.
        if (value) hidePanel();
        hidden.set(value);
    }

    /** Gives focus back to the viewport, e.g. after a HUD click. */
    void refocus() {
        refocus.run();
    }
}
