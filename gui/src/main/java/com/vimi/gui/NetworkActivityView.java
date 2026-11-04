package com.vimi.gui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Disposable header node. One shared model survives replacement; this view owns only its animation.
 */
final class NetworkActivityView extends Button implements AutoCloseable {
    private static final Image BRAND =
            new Image(
                    NetworkActivityView.class
                            .getResource("/branding/vibeminer.png")
                            .toExternalForm());
    private final NetworkActivityModel model;
    private final Label status = new Label();
    private final Label amount = new Label();
    private final Label details = new Label();
    private final ContextMenu popup = new ContextMenu();
    private final ImageView brand = new ImageView(BRAND);
    private final Group miner;
    private final Group sparks;
    private final Group overlay;
    private final Timeline swing;
    private final InvalidationListener refresh = ignored -> update();
    private boolean closed;

    NetworkActivityView(NetworkActivityModel model) {
        this.model = model;
        setId("network-activity");
        getStyleClass().add("network-activity");
        status.setId("network-activity-status");
        amount.setId("network-activity-amount");
        status.getStyleClass().add("network-activity-status");
        amount.getStyleClass().add("network-activity-amount");
        brand.setFitWidth(34);
        brand.setFitHeight(34);
        var handle = new Line(10, 27, 24, 11);
        handle.setStroke(Color.web("#e7b85f"));
        handle.setStrokeWidth(4);
        var head = new Line(13, 9, 29, 19);
        head.setStroke(Color.web("#b7efce"));
        head.setStrokeWidth(5);
        miner = new Group(handle, head);
        var ore = new Rectangle(23, 27, 9, 6);
        ore.setFill(Color.web("#84d7ae"));
        ore.setArcWidth(3);
        ore.setArcHeight(3);
        sparks =
                new Group(
                        new Circle(28, 22, 1.5, Color.web("#f5d37a")),
                        new Circle(33, 26, 1, Color.web("#b7efce")));
        overlay = new Group(ore, miner, sparks);
        var icon = new StackPane(brand, overlay);
        icon.setMinSize(36, 36);
        icon.setPrefSize(36, 36);
        icon.setMaxSize(36, 36);
        var text = new VBox(1, status, amount);
        var content = new HBox(7, icon, text);
        content.setAlignment(Pos.CENTER_LEFT);
        setGraphic(content);
        setTooltip(new Tooltip("HTTP response-body activity · click for details"));
        details.setWrapText(true);
        details.setMaxWidth(390);
        details.setId("network-activity-details");
        popup.getItems().add(new CustomMenuItem(details, false));
        setOnAction(
                event -> {
                    if (popup.isShowing()) popup.hide();
                    else popup.show(this, Side.BOTTOM, 0, 3);
                });
        swing =
                new Timeline(
                        new KeyFrame(
                                Duration.ZERO,
                                new KeyValue(miner.rotateProperty(), -14),
                                new KeyValue(sparks.opacityProperty(), 0.15)),
                        new KeyFrame(
                                Duration.millis(300),
                                new KeyValue(miner.rotateProperty(), 16),
                                new KeyValue(sparks.opacityProperty(), 1)),
                        new KeyFrame(
                                Duration.millis(600),
                                new KeyValue(miner.rotateProperty(), -14),
                                new KeyValue(sparks.opacityProperty(), 0.15)));
        swing.setCycleCount(Timeline.INDEFINITE);
        model.displayProperty().addListener(refresh);
        update();
    }

    private void update() {
        if (closed || model.display() == null) return;
        var value = model.display();
        if (!value.visible()) popup.hide();
        if (!status.getText().equals(value.status())) {
            status.setText(value.status());
            setAccessibleText(
                    "VibeMiner network activity: "
                            + value.status()
                            + ". Open for received-byte details.");
        }
        amount.setText(
                value.rate().isEmpty() ? value.total() : value.rate() + " · " + value.total());
        boolean active = value.snapshot().active() > 0;
        brand.setOpacity(active ? 0.25 : 1);
        overlay.setVisible(active);
        if (value.animate()) {
            if (swing.getStatus() != javafx.animation.Animation.Status.RUNNING) swing.play();
        } else {
            swing.stop();
            miner.setRotate(0);
            sparks.setOpacity(0.4);
        }
        var info =
                new StringBuilder("HTTP activity this application session\n")
                        .append(value.total())
                        .append("\n")
                        .append(value.snapshot().active())
                        .append(" active requests")
                        .append(" · ")
                        .append(value.snapshot().waitingResponse())
                        .append(" waiting for headers")
                        .append(" · ")
                        .append(value.snapshot().waitingBody())
                        .append(" reading/waiting for data")
                        .append(" · ")
                        .append(value.snapshot().handoff())
                        .append(" handed to local code\n");
        for (var category : value.snapshot().categories()) {
            if (category.receivedBytes() > 0 || category.active() > 0)
                info.append("\n")
                        .append(category.kind().name().replace('_', ' '))
                        .append(": ")
                        .append(NetworkActivityModel.bytes(category.receivedBytes()))
                        .append(" · ")
                        .append(category.active())
                        .append(" active");
        }
        info.append(
                "\n\nMeasured response-body bytes, including metadata and partial/repeated attempts. "
                        + "Excludes cache hits, decoded sizes, headers and transport overhead. "
                        + "An open transfer may wait for local code; local processing and rendering use their own indicators.");
        details.setText(info.toString());
    }

    boolean animating() {
        return swing.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        model.displayProperty().removeListener(refresh);
        swing.stop();
        popup.hide();
        setOnAction(null);
        setTooltip(null);
    }
}
