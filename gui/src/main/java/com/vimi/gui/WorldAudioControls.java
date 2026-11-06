package com.vimi.gui;

import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.concepts.world.WorldCurrentArea;

import java.time.Clock;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * World explorer music/ambience controls; the selection logic is {@link WorldAudioDirector}.
 * Both channels start off. The day/night slot follows the local clock unless one is chosen.
 */
final class WorldAudioControls implements AutoCloseable {
    private final CheckBox music = new CheckBox("Music"), ambience = new CheckBox("Ambience");
    private final Slider musicVolume = slider("world-music-volume"),
            ambienceVolume = slider("world-ambience-volume");
    private final ChoiceBox<WorldAudioDirector.Slot> variant = new ChoiceBox<>();
    private final Label status = new Label("Recorded area audio off");
    private final HBox node = new HBox(8, music, musicVolume, ambience, ambienceVolume, variant,
            status);
    private final PauseTransition settle = new PauseTransition(Duration.millis(750));
    private final Timeline clock = new Timeline();
    private final WorldAudioDirector director;
    private boolean closed;

    WorldAudioControls() {
        this(null);
    }

    WorldAudioControls(WorldAudioPlayback playback) {
        var owned = playback != null ? playback
                : new WorldAudioPlayback(value -> Platform.runLater(() -> show(value)));
        director = new WorldAudioDirector(owned, Clock.systemDefaultZone(),
                settle::playFromStart, Platform::runLater, status::setText);
        node.setId("world-audio");
        music.setId("world-music");
        ambience.setId("world-ambience");
        variant.setId("world-audio-variant");
        status.setId("world-audio-status");
        status.setMinWidth(0);
        variant.getItems().setAll(WorldAudioDirector.Slot.values());
        variant.setValue(WorldAudioDirector.Slot.CLOCK);
        variant.setTooltip(new Tooltip("Recorded day or night sound slot. By default it follows"
                + " this computer's clock (day " + WorldAudioDirector.DAY_STARTS + " to "
                + WorldAudioDirector.NIGHT_STARTS + "); game time is not simulated."));
        status.setTooltip(new Tooltip(
                "Recorded terrain-area audio only: WMO interiors, underwater, intro music, player"
                        + " conditions and SoundKit variation are not evaluated."));
        music.selectedProperty().addListener(
                (o, a, on) -> director.toggle(WorldAreaAudio.Kind.MUSIC, on));
        ambience.selectedProperty().addListener(
                (o, a, on) -> director.toggle(WorldAreaAudio.Kind.AMBIENCE, on));
        musicVolume.valueProperty().addListener((o, a, value) ->
                director.volume(WorldAreaAudio.Kind.MUSIC, value.doubleValue() / 100));
        ambienceVolume.valueProperty().addListener((o, a, value) ->
                director.volume(WorldAreaAudio.Kind.AMBIENCE, value.doubleValue() / 100));
        variant.valueProperty().addListener((o, a, value) -> {
            if (value != null) director.slot(value);
        });
        settle.setOnFinished(event -> director.settled());
        clock.getKeyFrames().setAll(new KeyFrame(Duration.seconds(30), e -> director.tick()));
        clock.setCycleCount(Animation.INDEFINITE);
        clock.play();
    }

    private static Slider slider(String id) {
        var slider = new Slider(0, 100, 50);
        slider.setId(id);
        slider.setPrefWidth(80);
        return slider;
    }

    /** The day/night key: by clock, then day, then night, then by clock again. */
    void cycleSlot() {
        var all = WorldAudioDirector.Slot.values();
        var now = variant.getValue() == null ? WorldAudioDirector.Slot.CLOCK : variant.getValue();
        variant.setValue(all[(now.ordinal() + 1) % all.length]);
    }

    /** The selected day/night slot; for tests. */
    WorldAudioDirector.Slot slot() {
        return variant.getValue();
    }

    /** The audio status text, e.g. for the developer overlay. */
    javafx.beans.property.ReadOnlyStringProperty statusText() {
        return status.textProperty();
    }

    HBox node() {
        return node;
    }

    // The controls themselves, for the HUD's music, ambience and day/night icons.
    CheckBox music() {
        return music;
    }

    CheckBox ambience() {
        return ambience;
    }

    Slider volume(WorldAreaAudio.Kind kind) {
        return kind == WorldAreaAudio.Kind.MUSIC ? musicVolume : ambienceVolume;
    }

    ChoiceBox<WorldAudioDirector.Slot> variant() {
        return variant;
    }

    Label status() {
        return status;
    }

    /** True while the channel is on but this area records nothing for it (the icon dims). */
    javafx.beans.property.ReadOnlyBooleanProperty silent(WorldAreaAudio.Kind kind) {
        return kind == WorldAreaAudio.Kind.MUSIC ? musicSilent : ambienceSilent;
    }

    private final javafx.beans.property.SimpleBooleanProperty musicSilent =
                    new javafx.beans.property.SimpleBooleanProperty(),
            ambienceSilent = new javafx.beans.property.SimpleBooleanProperty();

    /** FX thread; cheap when the area is unchanged, so callers may report it every frame. */
    void area(BuildWorld handle, long mapId, WorldCurrentArea.Result current) {
        director.area(handle, mapId, current);
    }

    private void show(WorldAudioPlayback.Status value) {
        if (closed) return;
        silent(value);
        director.show(value);
    }

    /** Records whether a playback status means "nothing recorded here" for its channel. */
    void silent(WorldAudioPlayback.Status value) {
        boolean none = !value.playing() && value.message() != null && value.message().startsWith("No recorded");
        (value.kind() == WorldAreaAudio.Kind.MUSIC ? musicSilent : ambienceSilent).set(none);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        settle.stop();
        clock.stop();
        director.close();
    }
}
