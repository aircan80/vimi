package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.concepts.world.WorldCurrentArea;

import java.util.Objects;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * World explorer music/ambience controls. Area changes are debounced, resolved off the FX thread
 * and published to owned playback channels; both channels start off.
 */
final class WorldAudioControls implements AutoCloseable {
    private record Key(BuildWorld handle, long mapId, WorldCurrentArea.State state, long areaId,
            WorldAreaAudio.Variant variant) {}

    private final CheckBox music = new CheckBox("Music"), ambience = new CheckBox("Ambience");
    private final Slider musicVolume = slider("world-music-volume"),
            ambienceVolume = slider("world-ambience-volume");
    private final ChoiceBox<WorldAreaAudio.Variant> variant = new ChoiceBox<>();
    private final Label status = new Label("Recorded area audio off");
    private final HBox node = new HBox(8, music, musicVolume, ambience, ambienceVolume, variant,
            status);
    private final PauseTransition settle = new PauseTransition(Duration.millis(750));
    private final WorldAudioPlayback playback;
    private String musicStatus = "Music off", ambienceStatus = "Ambience off";
    private Key observed, resolved;
    private WorldCurrentArea.Result area;
    private Cancellation request;
    private long generation;
    private BuildWorld sourceHandle;
    private WorldAudioPlayback.Source source;
    private boolean closed;

    WorldAudioControls() {
        this(null);
    }

    WorldAudioControls(WorldAudioPlayback playback) {
        this.playback = playback != null ? playback
                : new WorldAudioPlayback(value -> Platform.runLater(() -> show(value)));
        node.setId("world-audio");
        music.setId("world-music");
        ambience.setId("world-ambience");
        variant.setId("world-audio-variant");
        status.setId("world-audio-status");
        status.setMinWidth(0);
        variant.getItems().setAll(WorldAreaAudio.Variant.values());
        variant.setValue(WorldAreaAudio.Variant.DAY);
        variant.setTooltip(new Tooltip(
                "Recorded day or night sound slot; game time is not simulated."));
        status.setTooltip(new Tooltip(
                "Recorded terrain-area audio only: WMO interiors, underwater, intro music, player"
                        + " conditions and SoundKit mixing are not evaluated."));
        music.selectedProperty().addListener((o, a, on) -> toggle(this.playback.music, on));
        ambience.selectedProperty().addListener((o, a, on) -> toggle(this.playback.ambience, on));
        musicVolume.valueProperty().addListener(
                (o, a, value) -> this.playback.music.volume(value.doubleValue() / 100));
        ambienceVolume.valueProperty().addListener(
                (o, a, value) -> this.playback.ambience.volume(value.doubleValue() / 100));
        variant.valueProperty().addListener((o, a, value) -> {
            if (observed != null) observed = new Key(observed.handle(), observed.mapId(),
                    observed.state(), observed.areaId(), value);
            schedule();
        });
        settle.setOnFinished(event -> resolve());
    }

    private static Slider slider(String id) {
        var slider = new Slider(0, 100, 50);
        slider.setId(id);
        slider.setPrefWidth(80);
        return slider;
    }

    HBox node() {
        return node;
    }

    /** FX thread; cheap when the area is unchanged, so callers may report it every frame. */
    void area(BuildWorld handle, long mapId, WorldCurrentArea.Result current) {
        if (closed) return;
        var key = current == null || handle == null
                ? new Key(null, -1, WorldCurrentArea.State.UNAVAILABLE, 0, variant.getValue())
                : new Key(handle, mapId, current.state(), current.areaId(), variant.getValue());
        if (key.equals(observed)) return;
        observed = key;
        area = current;
        schedule();
    }

    private void toggle(WorldAudioPlayback.Channel channel, boolean on) {
        // A previously resolved area may be stale; enabling waits for the current area.
        if (on) channel.track(null, null);
        channel.enabled(on);
        if (on) {
            resolved = null;
            schedule();
        }
        refresh();
    }

    private void schedule() {
        if (closed) return;
        settle.playFromStart();
    }

    private void resolve() {
        if (closed || observed == null || observed.equals(resolved)) return;
        if (!music.isSelected() && !ambience.isSelected()) return;
        var key = observed;
        resolved = key;
        long current = ++generation;
        if (request != null) request.cancel();
        if (key.handle() == null || area == null || area.state() != WorldCurrentArea.State.KNOWN) {
            publish(null, null);
            musicStatus = ambienceStatus = "Terrain area unavailable";
            refresh();
            return;
        }
        var token = new Cancellation();
        request = token;
        var handle = key.handle();
        handle.areaAudio(key.mapId(), area, key.variant(), token).whenComplete(
                (plan, failure) -> Platform.runLater(() -> {
                    if (closed || current != generation) return;
                    if (failure != null || plan == null) {
                        publish(null, null);
                        musicStatus = ambienceStatus = "Recorded area audio unavailable";
                        resolved = null;
                    } else {
                        publish(plan, source(handle));
                        musicStatus = plan.music().message();
                        ambienceStatus = plan.ambience().message();
                    }
                    refresh();
                }));
    }

    /** One stable source per handle, so an unchanged sound kit keeps playing. */
    private WorldAudioPlayback.Source source(BuildWorld handle) {
        if (handle != sourceHandle) {
            sourceHandle = handle;
            source = handle::audioFile;
        }
        return source;
    }

    private void publish(WorldAreaAudio.Plan plan, WorldAudioPlayback.Source source) {
        playback.music.track(plan == null ? null : plan.music().track(), source);
        playback.ambience.track(plan == null ? null : plan.ambience().track(), source);
    }

    private void show(WorldAudioPlayback.Status value) {
        if (closed) return;
        if (value.kind() == WorldAreaAudio.Kind.MUSIC) musicStatus = value.message();
        else ambienceStatus = value.message();
        refresh();
    }

    private void refresh() {
        String text;
        if (!music.isSelected() && !ambience.isSelected()) text = "Recorded area audio off";
        else if (!ambience.isSelected()) text = musicStatus;
        else if (!music.isSelected()) text = ambienceStatus;
        else text = musicStatus + " · " + ambienceStatus;
        status.setText(Objects.requireNonNullElse(text, ""));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        settle.stop();
        if (request != null) request.cancel();
        playback.closeAsync();
    }
}
