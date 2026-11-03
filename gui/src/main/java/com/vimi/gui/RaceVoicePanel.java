package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceVoiceTypes;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Preview only selected build-locale recorded voice clips; never autoplays on navigation. */
final class RaceVoicePanel extends VBox implements AutoCloseable {
    private final BuildRaces handle;
    private final BooleanSupplier workspaceStale;
    private final ComboBox<RaceVoiceTypes.Clip> clips = new ComboBox<>();
    private final Button play = new Button("Play"), stop = new Button("Stop");
    private final Label status = new Label("Choose a recorded model voice");
    private final VoicePlayback playback = new VoicePlayback();
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
        getChildren().addAll(new Label("Voice previews"), new HBox(10, clips, play, stop), status);
    }

    void selection(long raceId, long classId, int sex) {
        stop();
        this.raceId = raceId;
        this.classId = classId;
        this.sex = sex;
        clips.getItems().clear();
        play.setDisable(true);
        status.setText("Loading recorded voice clips…");
        long request = ++generation;
        handle.voices(raceId, classId, sex, token)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (stale(request)) {
                                                return;
                                            }
                                            if (error != null
                                                    || result == null
                                                    || result.clips().isEmpty()) {
                                                status.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "No readable voice clips are available.");
                                                return;
                                            }
                                            clips.getItems().setAll(result.clips());
                                            clips.getSelectionModel().selectFirst();
                                            play.setDisable(false);
                                            status.setText(
                                                    result.locale()
                                                            + " · recorded jokes, flirts and action voices · runtime conditions are not evaluated");
                                        }));
    }

    private void play() {
        var selected = clips.getValue();
        if (selected == null) {
            return;
        }
        token.cancel();
        token = new Cancellation();
        long request = ++generation;
        play.setDisable(true);
        stop.setDisable(false);
        status.setText("Preparing voice preview…");
        handle.voice(raceId, classId, sex, selected.entryId(), token)
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
                                                status.setText(
                                                        result != null && result.message() != null
                                                                ? result.message()
                                                                : "Voice preview is unavailable.");
                                                play.setDisable(false);
                                                stop.setDisable(true);
                                                return;
                                            }
                                            status.setText(
                                                    "Playing "
                                                            + selected.action()
                                                            + " · "
                                                            + (result.restored()
                                                                    ? "saved PCM"
                                                                    : "decoded Vorbis"));
                                            playback.play(result.clip())
                                                    .whenComplete(
                                                            (message, failure) ->
                                                                    Platform.runLater(
                                                                            () -> {
                                                                                if (!stale(
                                                                                        request)) {
                                                                                    play.setDisable(
                                                                                            false);
                                                                                    stop.setDisable(
                                                                                            true);
                                                                                    status.setText(
                                                                                            message
                                                                                                            == null
                                                                                                    ? "Ready · select another clip or play again"
                                                                                                    : message);
                                                                                }
                                                                            }));
                                        }));
    }

    private boolean stale(long request) {
        return closed || generation != request || workspaceStale.getAsBoolean();
    }

    private void stop() {
        generation++;
        token.cancel();
        token = new Cancellation();
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
        playback.close();
    }
}
