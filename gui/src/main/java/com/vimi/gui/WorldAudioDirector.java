package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.concepts.world.WorldCurrentArea;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The World audio controls' selection logic, without JavaFX nodes: area changes are debounced,
 * resolved off the caller's thread and published to owned playback channels. All methods run on
 * one thread (the FX thread in the app); {@code ui} hops plan completions back onto it.
 */
final class WorldAudioDirector {
    /** The day/night slot: by local clock, or a fixed recorded slot. */
    enum Slot {
        CLOCK("Day/night by clock"),
        DAY("Day"),
        NIGHT("Night");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Local clock hours that select the recorded day slot; the rest select night. */
    static final LocalTime DAY_STARTS = LocalTime.of(6, 0), NIGHT_STARTS = LocalTime.of(18, 0);
    /** Retries of a failed plan for an unchanged area before the tracks stop. */
    static final int PLAN_RETRIES = 3;

    private record Key(BuildWorld handle, long mapId, WorldCurrentArea.State state, long areaId,
            WorldAreaAudio.Variant variant) {}

    private final WorldAudioPlayback playback;
    private final Clock clock;
    private final Runnable debounce;
    private final Executor ui;
    private final Consumer<String> statusText;
    private String musicStatus = "Music off", ambienceStatus = "Ambience off";
    private boolean musicOn, ambienceOn;
    private Slot slot = Slot.CLOCK;
    private WorldAreaAudio.Variant variant;
    private Key observed, resolved;
    private WorldCurrentArea.Result area;
    private Cancellation request;
    private long generation;
    private int failures;
    private BuildWorld sourceHandle;
    private WorldAudioPlayback.Source source;
    private boolean closed;

    /**
     * {@code debounce} (re)starts the settle delay, after which the caller invokes
     * {@link #settled()}; restarting an already pending delay postpones it.
     */
    WorldAudioDirector(WorldAudioPlayback playback, Clock clock, Runnable debounce, Executor ui,
            Consumer<String> statusText) {
        this.playback = Objects.requireNonNull(playback);
        this.clock = Objects.requireNonNull(clock);
        this.debounce = Objects.requireNonNull(debounce);
        this.ui = Objects.requireNonNull(ui);
        this.statusText = Objects.requireNonNull(statusText);
        variant = variantNow();
    }

    /** Day from {@link #DAY_STARTS} until {@link #NIGHT_STARTS}, otherwise night. */
    static WorldAreaAudio.Variant variantAt(LocalTime time) {
        return !time.isBefore(DAY_STARTS) && time.isBefore(NIGHT_STARTS)
                ? WorldAreaAudio.Variant.DAY : WorldAreaAudio.Variant.NIGHT;
    }

    private WorldAreaAudio.Variant variantNow() {
        return switch (slot) {
            case DAY -> WorldAreaAudio.Variant.DAY;
            case NIGHT -> WorldAreaAudio.Variant.NIGHT;
            case CLOCK -> variantAt(LocalTime.now(clock));
        };
    }

    WorldAreaAudio.Variant variant() {
        return variant;
    }

    /** Cheap when the area is unchanged, so callers may report it every frame. */
    void area(BuildWorld handle, long mapId, WorldCurrentArea.Result current) {
        if (closed) return;
        var key = current == null || handle == null
                ? new Key(null, -1, WorldCurrentArea.State.UNAVAILABLE, 0, variant)
                : new Key(handle, mapId, current.state(), current.areaId(), variant);
        if (key.equals(observed)) return;
        observed = key;
        area = current;
        failures = 0;
        schedule();
    }

    /** A manual day or night slot overrides the clock until the clock slot is chosen again. */
    void slot(Slot value) {
        if (closed) return;
        slot = Objects.requireNonNull(value);
        tick();
    }

    /** Re-reads the clock; call it periodically. A slot change resolves the area again. */
    void tick() {
        if (closed) return;
        var now = variantNow();
        if (now == variant) return;
        variant = now;
        if (observed != null) observed = new Key(observed.handle(), observed.mapId(),
                observed.state(), observed.areaId(), now);
        failures = 0;
        schedule();
    }

    void toggle(WorldAreaAudio.Kind kind, boolean on) {
        if (closed) return;
        var channel = playback.channel(kind);
        if (kind == WorldAreaAudio.Kind.MUSIC) musicOn = on;
        else ambienceOn = on;
        // A previously resolved area may be stale; enabling waits for the current area.
        if (on) channel.track(null, null);
        channel.enabled(on);
        if (on) {
            // The current area resolves at once; only area changes wait for the settle delay.
            resolved = null;
            failures = 0;
            settled();
        }
        refresh();
    }

    void volume(WorldAreaAudio.Kind kind, double value) {
        playback.channel(kind).volume(value);
    }

    private void schedule() {
        if (closed) return;
        debounce.run();
    }

    /** The settle delay elapsed: resolve the observed area if it changed. */
    void settled() {
        if (closed || observed == null || observed.equals(resolved)) return;
        if (!musicOn && !ambienceOn) return;
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
                (plan, failure) -> ui.execute(() -> {
                    if (closed || current != generation) return;
                    if (failure != null || plan == null) failed(key);
                    else {
                        failures = 0;
                        publish(plan, source(handle));
                        musicStatus = plan.music().message();
                        ambienceStatus = plan.ambience().message();
                    }
                    refresh();
                }));
    }

    /**
     * A failed plan (a full source queue, a briefly unavailable source) says nothing about the
     * recorded sounds: what plays keeps playing while the plan is retried. Only repeated failures
     * stop the tracks; the next area or toggle tries again.
     */
    private void failed(Key key) {
        if (++failures <= PLAN_RETRIES) {
            resolved = null;
            musicStatus = ambienceStatus = "Recorded area audio unavailable; retrying";
            schedule();
        } else {
            publish(null, null);
            musicStatus = ambienceStatus = "Recorded area audio unavailable";
            resolved = key;
        }
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

    /** A playback status, delivered on this director's thread. */
    void show(WorldAudioPlayback.Status value) {
        if (closed) return;
        if (value.kind() == WorldAreaAudio.Kind.MUSIC) musicStatus = value.message();
        else ambienceStatus = value.message();
        refresh();
    }

    private void refresh() {
        String text;
        if (!musicOn && !ambienceOn) text = "Recorded area audio off";
        else if (!ambienceOn) text = musicStatus;
        else if (!musicOn) text = ambienceStatus;
        else text = musicStatus + " · " + ambienceStatus;
        statusText.accept(Objects.requireNonNullElse(text, ""));
    }

    void close() {
        if (closed) return;
        closed = true;
        if (request != null) request.cancel();
        playback.closeAsync();
    }
}
