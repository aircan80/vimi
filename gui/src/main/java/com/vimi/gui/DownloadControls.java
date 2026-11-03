package com.vimi.gui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Bulk policy controls; reads cached snapshots and submits asynchronous commands only. */
final class DownloadControls extends VBox {
    private final BuildDownloads service;
    private final BuildPreparation.Job job;
    private final RadioButton demand = new RadioButton("On demand"),
            all = new RadioButton("Download all");
    private final Button pause = new Button("Pause");
    private final Label status = new Label(), details = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private boolean updating, pending;
    private String commandProblem;

    DownloadControls(BuildDownloads service, BuildPreparation.Job job) {
        super(10);
        this.service = service;
        this.job = job;
        var choices = new ToggleGroup();
        demand.setToggleGroup(choices);
        all.setToggleGroup(choices);
        choices.selectedToggleProperty()
                .addListener(
                        (observable, old, value) -> {
                            if (updating || pending || value == null) return;
                            command(
                                    value == all
                                            ? service.enable(job.build(), job.source())
                                            : service.onDemand(job.build().identity()));
                        });
        pause.setOnAction(
                event -> {
                    var snapshot = service.snapshot(job.build().identity());
                    command(
                            snapshot.state() == BuildDownloads.State.PAUSED
                                            || snapshot.state() == BuildDownloads.State.FAILED
                                            || snapshot.state() == BuildDownloads.State.INCOMPLETE
                                    ? service.resume(job.build().identity())
                                    : service.pause(job.build().identity()));
                });
        status.setWrapText(true);
        details.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        getChildren().addAll(new HBox(16, demand, all), status, progress, details, pause);
        refresh();
    }

    private void command(CompletableFuture<Void> work) {
        pending = true;
        commandProblem = null;
        refresh();
        work.whenComplete(
                (value, error) ->
                        Platform.runLater(
                                () -> {
                                    pending = false;
                                    if (error != null)
                                        commandProblem =
                                                "Download policy could not be saved; please retry";
                                    refresh();
                                }));
    }

    private static String label(BuildDownloads.State state) {
        String value = state.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    void refresh() {
        var snapshot = service.snapshot(job.build().identity());
        updating = true;
        try {
            all.setSelected(snapshot.enabled());
            demand.setSelected(!snapshot.enabled());
        } finally {
            updating = false;
        }
        all.setDisable(pending);
        demand.setDisable(pending);
        status.setText(
                commandProblem != null
                        ? commandProblem
                        : label(snapshot.state())
                                + ((snapshot.state() == BuildDownloads.State.FAILED
                                                        || snapshot.state()
                                                                == BuildDownloads.State.INCOMPLETE)
                                                && snapshot.message() != null
                                        ? " · " + snapshot.message()
                                        : ""));
        var measurement = snapshot.progress();
        progress.setProgress(
                measurement == null
                        ? (snapshot.state() == BuildDownloads.State.OPENING ? -1 : 0)
                        : measurement.totalBytes() == 0
                                ? 0
                                : (double) measurement.storedBytes() / measurement.totalBytes());
        details.setText(
                measurement == null
                        ? (snapshot.message() == null ? "" : snapshot.message())
                        : String.format(
                                Locale.ROOT,
                                "%,d / %,d objects · %.1f / %.1f MiB stored%s",
                                measurement.storedObjects(),
                                measurement.totalObjects(),
                                measurement.storedBytes() / 1048576.0,
                                measurement.totalBytes() / 1048576.0,
                                measurement.failedObjects() == 0
                                        ? ""
                                        : " · " + measurement.failedObjects() + " unavailable"));
        pause.setText(
                snapshot.state() == BuildDownloads.State.PAUSED
                        ? "Resume"
                        : snapshot.state() == BuildDownloads.State.FAILED
                                        || snapshot.state() == BuildDownloads.State.INCOMPLETE
                                ? "Retry"
                                : "Pause");
        pause.setDisable(
                pending
                        || !snapshot.enabled()
                        || snapshot.state() == BuildDownloads.State.COMPLETED);
    }
}
