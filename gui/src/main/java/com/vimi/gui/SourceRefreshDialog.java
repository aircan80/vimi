package com.vimi.gui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cached progress presentation; hiding never cancels the application's source refresh. */
final class SourceRefreshDialog extends Dialog<Void> {
    private record Controls(Label state, Label message, ProgressBar progress) {}

    private final SourceRefresh.Run run;
    private final Map<String, Controls> rows = new LinkedHashMap<>();
    private final Label summary = new Label();
    private final Timeline pulse =
            new Timeline(new KeyFrame(Duration.millis(150), event -> render()));
    private final Button cancel, hide, retry;
    private final boolean autoClose;
    private boolean interacted;
    private final javafx.animation.PauseTransition autoHide =
            new javafx.animation.PauseTransition(Duration.seconds(1));

    SourceRefreshDialog(Window owner, SourceRefresh.Run run) {
        this(owner, run, false, () -> {});
    }

    SourceRefreshDialog(
            Window owner, SourceRefresh.Run run, boolean autoClose, Runnable retryAction) {
        this.run = run;
        this.autoClose = autoClose;
        initModality(javafx.stage.Modality.NONE);
        if (owner != null) initOwner(owner);
        setTitle("Refresh sources");
        setHeaderText("Keeping your local data up to date");
        setResizable(true);
        var body = new VBox(15);
        body.setPadding(new Insets(8, 4, 8, 4));
        var explanation =
                new Label(
                        "Enabled sources check for changes, download new data and prepare it"
                                + " locally. You can keep using the app.");
        explanation.setWrapText(true);
        explanation.getStyleClass().add("muted");
        body.getChildren().addAll(explanation, summary);
        for (var row : run.rows()) {
            var name = new Label(row.name());
            name.getStyleClass().add("source-refresh-name");
            var state = new Label();
            state.getStyleClass().add("muted");
            var spacer = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var heading = new HBox(12, name, spacer, state);
            var message = new Label();
            message.setWrapText(true);
            message.getStyleClass().add("muted");
            var progress = new ProgressBar();
            progress.setMaxWidth(Double.MAX_VALUE);
            rows.put(row.id(), new Controls(state, message, progress));
            var card = new VBox(6, heading, message, progress);
            card.getStyleClass().add("source-refresh-row");
            body.getChildren().add(card);
        }
        var scroll = new javafx.scene.control.ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPrefViewportWidth(560);
        scroll.setPrefViewportHeight(Math.min(640, 105 + rows.size() * 92));
        getDialogPane().setContent(scroll);
        getDialogPane().setPrefSize(620, Math.min(760, 180 + rows.size() * 100));
        var retryType = new ButtonType("Retry refresh", ButtonBar.ButtonData.OTHER);
        var cancelType = new ButtonType("Cancel updates", ButtonBar.ButtonData.LEFT);
        var hideType = new ButtonType("Hide", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(retryType, cancelType, hideType);
        hide = (Button) getDialogPane().lookupButton(hideType);
        retry = (Button) getDialogPane().lookupButton(retryType);
        retry.addEventFilter(
                javafx.event.ActionEvent.ACTION,
                event -> {
                    event.consume();
                    retryAction.run();
                });
        cancel = (Button) getDialogPane().lookupButton(cancelType);
        cancel.addEventFilter(
                javafx.event.ActionEvent.ACTION,
                event -> {
                    event.consume();
                    run.cancel();
                    cancel.setDisable(true);
                    render();
                });
        getDialogPane()
                .addEventFilter(
                        javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> interacted = true);
        getDialogPane()
                .addEventFilter(
                        javafx.scene.input.KeyEvent.KEY_PRESSED, event -> interacted = true);
        autoHide.setOnFinished(
                event -> {
                    if (!interacted && isShowing()) close();
                });
        setOnShown(
                event -> {
                    setWidth(620);
                    setHeight(Math.min(760, 180 + rows.size() * 100));
                    render();
                    if (run.busy()) pulse.play();
                });
        setOnHidden(
                event -> {
                    pulse.stop();
                    autoHide.stop();
                });
        pulse.setCycleCount(Timeline.INDEFINITE);
        render();
    }

    SourceRefresh.Run run() {
        return run;
    }

    private void render() {
        var values = run.rows();
        long completed =
                values.stream()
                        .filter(
                                row ->
                                        row.state() != SourceRefresh.State.WAITING
                                                && row.state() != SourceRefresh.State.UPDATING)
                        .count();
        long failures =
                values.stream().filter(row -> row.state() == SourceRefresh.State.FAILED).count();
        summary.setText(
                run.busy()
                        ? completed + " of " + values.size() + " sources finished"
                        : failures == 0
                                ? "Source refresh finished"
                                : "Refresh finished · " + failures + " source(s) need attention");
        for (var row : values) {
            var controls = rows.get(row.id());
            controls.state()
                    .setText(
                            switch (row.state()) {
                                case WAITING -> "Waiting";
                                case UPDATING -> "Updating";
                                case COMPLETE -> "Ready";
                                case FAILED -> "Needs attention";
                                case CANCELED -> "Canceled";
                                case DISABLED -> "Disabled";
                            });
            controls.message().setText(row.message());
            controls.progress().setProgress(row.fraction());
            controls.progress().setVisible(row.state() == SourceRefresh.State.UPDATING);
            controls.progress().setManaged(row.state() == SourceRefresh.State.UPDATING);
        }
        cancel.setDisable(!run.busy());
        retry.setVisible(!run.busy() && failures > 0);
        retry.setManaged(!run.busy() && failures > 0);
        hide.setText(run.busy() ? "Hide" : "Close");
        if (!run.busy()) {
            pulse.stop();
            boolean successful =
                    values.stream()
                            .allMatch(
                                    row ->
                                            row.state() == SourceRefresh.State.COMPLETE
                                                    || row.state() == SourceRefresh.State.DISABLED);
            if (autoClose && successful && !interacted && isShowing()) autoHide.playFromStart();
        }
    }
}
