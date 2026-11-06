package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Key binding editor for the World explorer. "Add key" waits for the next key press and binds
 * it to that action (taking it from any other action); Escape cancels the wait. Layout presets
 * reset every binding. The edits are returned only on OK.
 */
final class WorldKeyBindings {
    private final Dialog<WorldControls> dialog = new Dialog<>();
    private final GridPane grid = new GridPane();
    private final Label hint = new Label();
    private final List<Button> addButtons = new ArrayList<>();
    private WorldControls controls;
    private WorldControls.Action capturing;

    WorldKeyBindings(WorldControls initial) {
        controls = initial;
        dialog.setTitle("World explorer keys");
        dialog.setHeaderText("Keys are matched by the key code your keyboard layout reports.");
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setId("world-key-bindings");
        var presets = new HBox(8, new Label("Reset to"));
        for (var layout : WorldControls.Layout.values()) {
            var preset = new Button(layout.label);
            preset.setId("world-keys-" + layout.name().toLowerCase(java.util.Locale.ROOT));
            preset.setOnAction(e -> {
                capturing = null;
                controls = controls.withKeys(layout.keys());
                refresh();
            });
            presets.getChildren().add(preset);
        }
        hint.setId("world-key-bindings-hint");
        dialog.getDialogPane().setContent(new VBox(10, grid, presets, hint));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, this::captured);
        dialog.setResultConverter(button -> button == ButtonType.OK ? controls : null);
        refresh();
    }

    /** Shows the editor modally; the edited controls when the user confirms. */
    Optional<WorldControls> edit(Window owner) {
        if (owner != null) dialog.initOwner(owner);
        return dialog.showAndWait();
    }

    WorldControls controls() {
        return controls;
    }

    /** Starts waiting for the key to add to {@code action}. */
    void capture(WorldControls.Action action) {
        capturing = action;
        refresh();
    }

    /** Key presses while waiting become bindings and never reach the dialog's buttons. */
    void captured(KeyEvent event) {
        if (capturing == null) return;
        event.consume();
        var code = event.getCode();
        if (code != KeyCode.ESCAPE) {
            if (!WorldControls.bindable(code)) {
                hint.setText(code.getName() + " can't be bound; press another key or Escape.");
                return;
            }
            controls = controls.bind(code, capturing);
        }
        capturing = null;
        refresh();
    }

    private void refresh() {
        grid.getChildren().clear();
        addButtons.clear();
        var bound = controls.byAction();
        int row = 0;
        for (var action : WorldControls.Action.values()) {
            var keys = new Label(bound.get(action).stream().map(KeyCode::getName)
                    .collect(Collectors.joining(", ")));
            if (keys.getText().isEmpty()) keys.setText("(none)");
            keys.setId("world-keys-" + action.name().toLowerCase(java.util.Locale.ROOT));
            var add = new Button(capturing == action ? "Press a key…" : "Add key");
            add.setOnAction(e -> capture(action));
            var clear = new Button("Clear");
            clear.setOnAction(e -> {
                capturing = null;
                controls = controls.unbind(action);
                refresh();
            });
            addButtons.add(add);
            grid.addRow(row++, new Label(action.label), keys, add, clear);
        }
        hint.setText(capturing == null
                ? "Escape, F11, Alt and system keys are reserved."
                : "Press the key for " + capturing.label.toLowerCase(java.util.Locale.ROOT)
                        + ", or Escape to cancel.");
    }
}
