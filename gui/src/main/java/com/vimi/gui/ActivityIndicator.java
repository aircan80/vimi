package com.vimi.gui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;

/** Compact local activity feedback, kept outside disabled operation controls. */
final class ActivityIndicator extends HBox {
    private final Label message = new Label();

    ActivityIndicator(String text) {
        super(8);
        getStyleClass().add("local-activity");
        var spinner = new ProgressIndicator(-1);
        spinner.setMinSize(18, 18);
        spinner.setPrefSize(18, 18);
        spinner.setMaxSize(18, 18);
        spinner.setAccessibleText(text);
        message.setText(text);
        message.setWrapText(true);
        message.getStyleClass().add("muted");
        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(spinner, message);
        setBusy(false);
    }

    void setBusy(boolean value) {
        setVisible(value);
        setManaged(value);
    }
}
