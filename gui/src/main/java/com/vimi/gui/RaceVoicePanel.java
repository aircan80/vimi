package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceVoiceTypes;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.BooleanSupplier;

/** Preview only selected build-locale recorded voice clips; never autoplays on navigation. */
final class RaceVoicePanel extends VBox implements AutoCloseable {
    private final BuildRaces handle;
    private final BooleanSupplier workspaceStale;
    private final ComboBox<RaceVoiceTypes.Clip> clips = new ComboBox<>();
    private final Button play = new Button("Play"), stop = new Button("Stop");
    private final Label status = new Label("Choose a recorded model voice");
    private final Label source = new Label();
    private final Label refused = new Label();
    private final Label notes = new Label();
    private final VoicePlayback playback = new VoicePlayback();
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private Cancellation token = new Cancellation();
    private long generation, raceId, classId;
    private int sex;
    private boolean closed;

    RaceVoicePanel(BuildRaces handle, BooleanSupplier workspaceStale) {
        super(8);
        this.handle = handle;
        this.workspaceStale = workspaceStale;
        clips.setConverter(
                new javafx.util.StringConverter<>() {
                    public String toString(RaceVoiceTypes.Clip value) {
                        return value == null
                                ? "Select voice clip"
                                : value.action() + " · clip #" + value.entryId();
                    }

                    public RaceVoiceTypes.Clip fromString(String value) {
                        throw new UnsupportedOperationException();
                    }
                });
        clips.setAccessibleText("Recorded race voice clips");
        clips.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(clips, Priority.ALWAYS);
        play.setDisable(true);
        stop.setDisable(true);
        play.setOnAction(event -> play());
        stop.setOnAction(event -> stop());
        status.getStyleClass().add("muted");
        status.setWrapText(true);
        for (var label : new Label[] {source, refused, notes}) {
            label.getStyleClass().add("muted");
            label.setWrapText(true);
            label.setVisible(false);
            label.managedProperty().bind(label.visibleProperty());
        }
        source.setAccessibleText("Recorded voice source chain");
        refused.setAccessibleText("Refused voice links");
        notes.setAccessibleText("Voice record notes");
        clips.valueProperty().addListener((observable, previous, value) -> showSource(value));
        activity.setPrefSize(18, 18);
        activity.setMaxSize(18, 18);
        activity.setVisible(false);
        activity.managedProperty().bind(activity.visibleProperty());
        getChildren()
                .addAll(
                        new Label("Voice previews"),
                        new HBox(10, clips, play, stop),
                        new HBox(9, activity, status),
                        source,
                        refused,
                        notes);
    }

    static final int SHOWN_REFUSALS = 6;

    private void showSource(RaceVoiceTypes.Clip clip) {
        source.setText(clip == null ? "" : "Source: " + clip.chain());
        source.setVisible(clip != null);
    }

    static final int SHOWN_NOTES = 4;

    private void showRecords(RaceVoiceTypes.Catalog catalog) {
        showRefusals(catalog == null ? null : catalog.refusals());
        var shown =
                catalog == null
                        ? java.util.List.<String>of()
                        : catalog.issues().stream().limit(SHOWN_NOTES).toList();
        notes.setText(String.join("\n", shown));
        notes.setVisible(!shown.isEmpty());
    }

    private void showRefusals(java.util.List<RaceVoiceTypes.Refusal> refusals) {
        if (refusals == null || refusals.isEmpty()) {
            refused.setText("");
            refused.setVisible(false);
            return;
        }
        var text = new StringBuilder("Not playable (" + refusals.size() + " listed):");
        for (int i = 0; i < refusals.size() && i < SHOWN_REFUSALS; i++) {
            var refusal = refusals.get(i);
            text.append("\n").append(refusal.link()).append(" — ").append(refusal.reason());
        }
        if (refusals.size() > SHOWN_REFUSALS) {
            text.append("\n…and ").append(refusals.size() - SHOWN_REFUSALS).append(" more");
        }
        refused.setText(text.toString());
        refused.setVisible(true);
    }

    void selection(long raceId, long classId, int sex) {
        if (stale(generation)) return;
        stop();
        this.raceId = raceId;
        this.classId = classId;
        this.sex = sex;
        clips.getItems().clear();
        clips.setDisable(false);
        play.setDisable(true);
        clips.setValue(null);
        showRecords(null);
        busy("Loading recorded voice clips…");
        long request = ++generation;
        request(() -> handle.voices(raceId, classId, sex, token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) {
                                                return;
                                            }
                                            activity.setVisible(false);
                                            showRecords(error == null ? result : null);
                                            if (error != null
                                                    || result == null
                                                    || result.clips().isEmpty()) {
                                                status.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "No readable voice clips are"
                                                                        + " available.");
                                                return;
                                            }
                                            clips.getItems().setAll(result.clips());
                                            clips.getSelectionModel().selectFirst();
                                            play.setDisable(false);
                                            status.setText(
                                                    result.locale()
                                                            + " · recorded jokes, flirts and action"
                                                            + " voices · runtime conditions are not"
                                                            + " evaluated");
                                        }));
    }

    private void play() {
        var selected = clips.getValue();
        if (stale(generation) || selected == null) {
            return;
        }
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        play.setDisable(true);
        stop.setDisable(false);
        busy("Decoding voice preview…");
        request(() -> handle.voice(raceId, classId, sex, selected.entryId(), token))
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.clip() == null) {
                                                activity.setVisible(false);
                                                status.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Voice preview is unavailable.");
                                                play.setDisable(false);
                                                stop.setDisable(true);
                                                return;
                                            }
                                            busy("Preparing audio output…");
                                            playback.play(
                                                            result.clip(),
                                                            () ->
                                                                    Platform.runLater(
                                                                            () -> {
                                                                                if (stale(request))
                                                                                    return;
                                                                                activity.setVisible(
                                                                                        false);
                                                                                status.setText(
                                                                                        "Playing "
                                                                                                + selected
                                                                                                        .action()
                                                                                                + " · "
                                                                                                + (result
                                                                                                                .restored()
                                                                                                        ? "saved"
                                                                                                              + " PCM"
                                                                                                        : "decoded"
                                                                                                              + " Vorbis"));
                                                                            }))
                                                    .whenComplete(
                                                            (message, failure) ->
                                                                    Platform.runLater(
                                                                            () -> {
                                                                                if (!stale(
                                                                                        request)) {
                                                                                    activity
                                                                                            .setVisible(
                                                                                                    false);
                                                                                    play.setDisable(
                                                                                            false);
                                                                                    stop.setDisable(
                                                                                            true);
                                                                                    status.setText(
                                                                                            message
                                                                                                            == null
                                                                                                    ? "Ready"
                                                                                                          + " · select"
                                                                                                          + " another"
                                                                                                          + " clip"
                                                                                                          + " or play"
                                                                                                          + " again"
                                                                                                    : message);
                                                                                }
                                                                            }));
                                        }));
    }

    private void busy(String message) {
        activity.setAccessibleText(message);
        activity.setVisible(true);
        status.setText(message);
    }

    private static <T> java.util.concurrent.CompletableFuture<T> request(
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> operation) {
        try {
            return java.util.Objects.requireNonNull(operation.get());
        } catch (RuntimeException failure) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
    }

    private boolean stale(long request) {
        if (closed || generation != request) return true;
        if (!workspaceStale.getAsBoolean()) return false;
        stop();
        clips.getItems().clear();
        clips.setValue(null);
        showRecords(null);
        clips.setDisable(true);
        play.setDisable(true);
        stop.setDisable(true);
        status.setText("Build changed. Reload voice records.");
        return true;
    }

    private void stop() {
        generation++;
        token.cancel();
        token = new Cancellation();
        activity.setVisible(false);
        playback.stop();
        play.setDisable(clips.getItems().isEmpty());
        stop.setDisable(true);
        status.setText("Voice stopped");
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        token.cancel();
        activity.setVisible(false);
        playback.close();
    }
}
