package com.vimi.gui;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The World HUD's coordinates pill, bottom-right: native x, y and altitude on the first line, the
 * terrain tile on the second. A primary click copies "x y z", which the travel panel's coordinate
 * field reads back. The tooltip carries the view's full position line. FX thread only.
 */
final class WorldCoordinatesPill {
    private final Label pill = new Label();
    private final Tooltip tooltip = new Tooltip();
    private final Consumer<String> copy;
    private final Runnable refocus;
    private String coordinates;

    WorldCoordinatesPill(Runnable refocus) {
        this(text -> {
            var content = new ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        }, refocus);
    }

    /** {@code copy} receives the copied text (the system clipboard outside tests). */
    WorldCoordinatesPill(Consumer<String> copy, Runnable refocus) {
        this.copy = Objects.requireNonNull(copy);
        this.refocus = Objects.requireNonNull(refocus);
        pill.setId("world-coordinates");
        pill.getStyleClass().addAll("world-hud-chip", "world-hud-coordinates");
        pill.setFocusTraversable(false);
        pill.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        pill.setTooltip(tooltip);
        pill.setAccessibleText("Camera coordinates; click to copy");
        // Handle the click here, then stop every pointer event so it never looks or inspects.
        pill.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY) copy();
        });
        WorldHud.stopInput(pill);
        clear();
    }

    Label node() {
        return pill;
    }

    Tooltip tooltip() {
        return tooltip;
    }

    /** Shows the camera position and its terrain tile. */
    void show(double x, double y, double altitude, int tileX, int tileY) {
        coordinates = String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, altitude);
        // "\n", not "%n": the pill's two lines are the same on every platform.
        pill.setText(String.format(Locale.ROOT, "x %.1f  y %.1f  alt %.1f", x, y, altitude) + "\n"
                + String.format(Locale.ROOT, "tile %d,%d", tileX, tileY));
        pill.setVisible(true);
    }

    /** No camera: the pill hides. */
    void clear() {
        coordinates = null;
        pill.setText("");
        pill.setVisible(false);
    }

    /** Copies "x y z" (native, one decimal); nothing without a camera. */
    void copy() {
        if (coordinates != null) copy.accept(coordinates);
        refocus.run();
    }

    /** The text a click copies, or null; for tests. */
    String coordinates() {
        return coordinates;
    }
}
