package com.vimi.gui;

import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The World explorer's inspect card: what the last click picked, as a small panel over the
 * viewport (type chip, title, subtitle, short lines, game file, Pin / Copy / close). An unpinned
 * card closes once the camera has travelled {@link #FOLLOW_YARDS} (in 3D) from where it opened; a
 * pinned one stays until it is closed. Whether the card is open is its own state: hiding the HUD
 * only hides it from view ({@link #hudHidden}), so travel, Escape and the pin key keep working. The card keeps its own clicks (they are meant for it, not the
 * world behind), is never focus-traversable, and gives focus back to the viewport after each
 * action. FX-owned.
 */
final class WorldInspectCard extends VBox {
    static final double WIDTH = 320, FOLLOW_YARDS = 40;

    private final Label chip = new Label(), title = new Label(), subtitle = new Label(), file = new Label();
    private final VBox lines = new VBox(1);
    private final ToggleButton pin = new ToggleButton("Pin");
    private final Button copy = new Button("Copy"), close = new Button("×");
    private Runnable refocus = () -> {};
    private Consumer<String> clipboard = WorldInspectCard::systemClipboard;
    private WorldInspection.Content content, last;
    private double openedX = Double.NaN, openedY = Double.NaN, openedZ = Double.NaN;
    private boolean open, hudHidden;

    WorldInspectCard() {
        setId("world-inspect-card");
        setSpacing(4);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);
        setMaxHeight(USE_PREF_SIZE);
        setFocusTraversable(false);
        setStyle("-fx-background-color: rgba(10,16,20,0.9); -fx-background-radius: 8;"
                + " -fx-padding: 8 10 8 10;");
        String text = "-fx-text-fill: #e6eef0;";
        chip.setId("world-inspect-chip");
        chip.setStyle("-fx-background-color: #2f4a56; -fx-background-radius: 4; -fx-padding: 1 6 1 6;"
                + " -fx-text-fill: #cfe6ee; -fx-font-size: 10px; -fx-font-weight: bold;");
        title.setId("world-inspect-title");
        title.setStyle(text + " -fx-font-size: 13px; -fx-font-weight: bold;");
        title.setWrapText(true);
        title.setMinWidth(0);
        HBox.setHgrow(title, Priority.ALWAYS);
        close.setId("world-inspect-close");
        close.setAccessibleText("Close the inspect card");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        var header = new HBox(6, chip, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        subtitle.setId("world-inspect-subtitle");
        subtitle.setStyle("-fx-text-fill: #a9bcc2; -fx-font-size: 11px;");
        subtitle.setWrapText(true);
        lines.setId("world-inspect-lines");
        file.setId("world-inspect-file");
        file.setStyle("-fx-text-fill: #9fd0e6; -fx-font-size: 11px; -fx-underline: true;");
        file.setAccessibleText("Game file; click copies the full path");
        // A click on the file line copies the full path; the tooltip shows it.
        file.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && content != null && content.file() != null) {
                clipboard.accept(content.file());
                refocus.run();
            }
        });
        pin.setId("world-inspect-pin");
        pin.setAccessibleText("Pin the inspect card while flying");
        pin.selectedProperty().addListener((o, was, on) -> pin.setText(on ? "Unpin" : "Pin"));
        copy.setId("world-inspect-copy");
        copy.setAccessibleText("Copy the full inspection text");
        button(pin, () -> {});
        button(copy, () -> {
            if (content != null) clipboard.accept(content.detail());
        });
        button(close, this::close);
        var actions = new HBox(6, pin, copy);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(header, subtitle, lines, file, actions);
        // The card is a panel: its clicks, scrolls and keys stay here, never inspect or steer
        // the world behind it.
        addEventHandler(MouseEvent.ANY, MouseEvent::consume);
        addEventHandler(ScrollEvent.ANY, ScrollEvent::consume);
        addEventHandler(KeyEvent.ANY, KeyEvent::consume);
        setVisible(false);
    }

    private void button(ButtonBase button, Runnable action) {
        button.setFocusTraversable(false);
        button.setStyle("-fx-font-size: 11px;");
        button.setOnAction(event -> {
            action.run();
            refocus.run();
        });
    }

    /** Gives keyboard focus back to the viewport after an action. */
    void refocus(Runnable next) {
        refocus = Objects.requireNonNull(next);
    }

    /** Replaces the system clipboard; for tests. */
    void clipboard(Consumer<String> next) {
        clipboard = Objects.requireNonNull(next);
    }

    /** Shows a new pick, opened at the camera position (NaN: never closes by travel). */
    void show(WorldInspection.Content next, double cameraX, double cameraY, double cameraZ) {
        pin.setSelected(false);
        openedX = cameraX;
        openedY = cameraY;
        openedZ = cameraZ;
        present(next);
    }

    /** Refreshes the open card when the same pick gains facts (e.g. a resolved model). */
    void update(WorldInspection.Content next) {
        if (content != null && open) present(next);
        else if (next.kind() != WorldInspection.Kind.NOTE) last = Objects.requireNonNull(next);
    }

    /** The HUD is hidden (or shown again): the card hides from view but stays open. */
    void hudHidden(boolean hidden) {
        hudHidden = hidden;
        setVisible(open && !hudHidden);
    }

    /** True while a card is open, whether or not the HUD shows it. */
    boolean isOpen() {
        return open;
    }

    private void present(WorldInspection.Content next) {
        content = Objects.requireNonNull(next);
        // A note ("nothing picked") never replaces the card the pin key re-opens.
        if (next.kind() != WorldInspection.Kind.NOTE) last = next;
        chip.setText(next.kind().chip);
        title.setText(next.title());
        subtitle.setText(next.subtitle());
        subtitle.setVisible(!next.subtitle().isEmpty());
        subtitle.setManaged(!next.subtitle().isEmpty());
        lines.getChildren().clear();
        for (var line : next.lines()) {
            var label = new Label(line);
            label.setWrapText(true);
            label.setStyle("-fx-text-fill: #d5e0e3; -fx-font-size: 11px;");
            lines.getChildren().add(label);
        }
        boolean hasFile = next.file() != null;
        file.setText(hasFile ? "file: " + WorldInspection.base(next.file(), next.file()) : "");
        file.setTooltip(hasFile ? new Tooltip(next.file()) : null);
        file.setVisible(hasFile);
        file.setManaged(hasFile);
        pin.setDisable(next.kind() == WorldInspection.Kind.NOTE);
        setAccessibleText("Inspect card: " + next.kind().chip + ", " + next.title());
        open = true;
        setVisible(!hudHidden);
    }

    /** Closes the card; true when it was open (so Escape stops here). */
    boolean close() {
        if (!open) return false;
        content = null;
        pin.setSelected(false);
        open = false;
        setVisible(false);
        return true;
    }

    /** Closes an unpinned card only, e.g. when its selection goes away. */
    void release() {
        if (!pinned()) close();
    }

    /** The camera moved: an unpinned card closes once it is {@link #FOLLOW_YARDS} away in 3D. */
    void follow(double cameraX, double cameraY, double cameraZ) {
        if (!open || pinned() || Double.isNaN(openedX)) return;
        double dx = cameraX - openedX, dy = cameraY - openedY, dz = cameraZ - openedZ;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) >= FOLLOW_YARDS) close();
    }

    /**
     * The pin key: pins or unpins the open card; with none open, re-opens the last one pinned.
     */
    void togglePin(double cameraX, double cameraY, double cameraZ) {
        if (open) {
            if (!pin.isDisabled()) pin.setSelected(!pin.isSelected());
            return;
        }
        if (last == null) return;
        show(last, cameraX, cameraY, cameraZ);
        pin.setSelected(true);
    }

    boolean pinned() {
        return open && pin.isSelected();
    }

    /** The open content, null when closed; for tests. */
    WorldInspection.Content content() {
        return open ? content : null;
    }

    private static void systemClipboard(String text) {
        var value = new ClipboardContent();
        value.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(value);
    }
}
