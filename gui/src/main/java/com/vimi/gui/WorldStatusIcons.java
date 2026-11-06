package com.vimi.gui;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;

/**
 * The World HUD's status icon row, bottom-left: one small icon per piece of state that used to be
 * a text row. Each icon's tooltip holds the full text; each click does the obvious thing (toggle
 * walk, next speed preset, mute, next day/night slot) or opens where the detail lives.
 *
 * <p>The row only shows state; the view's existing controls stay the models and call the setters
 * here. Icons follow the HUD event rules: never focus-traversable, input stops at the icon, and
 * a click hands focus back to the viewport. FX thread only.
 */
final class WorldStatusIcons {
    enum Mode { FLY, WALK, WALK_NO_TERRAIN }

    /** A sound channel: playing, switched off, or on with nothing recorded for this area. */
    enum Sound { ON, MUTED, SILENT }

    /**
     * GPU (white); PARTIAL, GPU with some wanted tiles missing (amber); SOFTWARE, a fallback
     * (amber); FAILED, a real failure such as a failed stream, a stalled renderer or a failed upload
     * (red); BUSY (spinner).
     */
    enum Render { GPU, PARTIAL, SOFTWARE, FAILED, BUSY }

    /** READY (white); FALLBACK, height shading instead of textures (amber); FAILED (red). */
    enum Texture { READY, LOADING, FALLBACK, FAILED }

    enum Time { CLOCK, DAY, NIGHT }

    /** What the icons do when clicked or scrolled; the view implements each with its controls. */
    interface Actions {
        void toggleWalk();

        void nextSpeed();

        void toggleSmooth();

        void toggleMusic();

        void toggleAmbience();

        /** {@code steps} wheel notches up (+) or down (-) for the music or ambience volume. */
        void volume(boolean music, int steps);

        void nextTime();

        /** Render, texture, mouse and tracking icons: show where their detail and controls are. */
        void details(String icon);
    }

    static final PseudoClass AMBER = PseudoClass.getPseudoClass("amber"),
            RED = PseudoClass.getPseudoClass("red"),
            DIM = PseudoClass.getPseudoClass("dim"),
            BRIGHT = PseudoClass.getPseudoClass("bright");

    private static final String WINGS = "M1 13 C5 7 9 6 12 9.5 C15 6 19 7 23 13 C19 11 15.5 11.5 12 15"
            + " C8.5 11.5 5 11 1 13 Z";
    private static final String BOOTS = "M7 2 C9 2 10 5 10 8 C10 11 8.5 12 7 12 C5.5 12 4 10.5 4 7.5"
            + " C4 4.5 5 2 7 2 Z M5 14 H9 V17 C9 18.5 8 19 7 19 C6 19 5 18.5 5 17 Z M16 6 C18 6 19 8.5"
            + " 19 11.5 C19 14.5 18 16 16 16 C14 16 13 14.5 13 11.5 C13 8.5 14 6 16 6 Z M14 18 H18 V20"
            + " C18 21.5 17 22 16 22 C15 22 14 21.5 14 20 Z";
    private static final String NOTE = "M9 4 L20 2 V15.5 A3 3 0 1 1 18 12.8 V6 L11 7.3 V18.5 A3 3 0 1 1"
            + " 9 15.8 Z";
    private static final String LEAF = "M3 21 C3 11 9 3 21 3 C21 15 13 21 3 21 Z M5 19 L15 9 L14 8 L4 18 Z";
    private static final String SUN = "M12 7 A5 5 0 1 0 12.01 7 Z M11 1 H13 V4 H11 Z M11 20 H13 V23 H11 Z"
            + " M1 11 H4 V13 H1 Z M20 11 H23 V13 H20 Z";
    private static final String MOON = "M14 2 A10 10 0 1 0 22 15 A8 8 0 1 1 14 2 Z";
    private static final String CLOCK = "M12 2 A10 10 0 1 0 12.01 2 Z M12 4.5 A7.5 7.5 0 1 1 11.99 4.5 Z"
            + " M11 6 H13 V11 H17 V13 H11 Z";
    private static final String MOUSE = "M12 2 C8 2 6 5 6 9 V15 C6 19 8.5 22 12 22 C15.5 22 18 19 18 15"
            + " V9 C18 5 16 2 12 2 Z M12 3.8 C14.6 3.8 16.2 6 16.2 9 V15 C16.2 18 14.4 20.2 12 20.2"
            + " C9.6 20.2 7.8 18 7.8 15 V9 C7.8 6 9.4 3.8 12 3.8 Z M11 5.5 H13 V10 H11 Z";

    private final HBox row = new HBox(6);
    private final Actions actions;
    private final Runnable refocus;
    private final Button mode, speed, smooth, music, ambience, time, render, texture, mouse, tracking;
    private final SVGPath modePath = path(WINGS), timePath = path(CLOCK);
    private final Line musicSlash = slash(), ambienceSlash = slash(), mouseSlash = slash();
    private final ProgressIndicator renderBusy = busy(), textureBusy = busy();
    private final HBox dots = new HBox(3);
    private Mode modeState = Mode.FLY;
    private Sound musicState = Sound.MUTED, ambienceState = Sound.MUTED;
    private Render renderState = Render.GPU;
    // The tracking colours shown, so an unchanged list never rebuilds the dots.
    private List<Color> trackingShown;
    private Texture textureState = Texture.READY;
    private Time timeState = Time.CLOCK;
    private boolean mouseUnavailable;

    WorldStatusIcons(Actions actions, Runnable refocus) {
        this.actions = Objects.requireNonNull(actions);
        this.refocus = Objects.requireNonNull(refocus);
        row.setId("world-status-icons");
        row.getStyleClass().add("world-hud-icons");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPickOnBounds(false);
        row.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        mode = icon("mode", new StackPane(modePath), actions::toggleWalk);
        speed = icon("speed", null, actions::nextSpeed);
        speed.getStyleClass().add("world-hud-text-icon");
        smooth = icon("smooth", null, actions::toggleSmooth);
        smooth.setText("~");
        smooth.getStyleClass().add("world-hud-text-icon");
        smooth.managedProperty().bind(smooth.visibleProperty());
        smooth.setTooltip(new Tooltip("Smooth motion is on; click to turn it off"));
        music = icon("music", new StackPane(path(NOTE), musicSlash), actions::toggleMusic);
        ambience = icon("ambience", new StackPane(path(LEAF), ambienceSlash), actions::toggleAmbience);
        music.addEventHandler(ScrollEvent.SCROLL, e -> wheel(true, e));
        ambience.addEventHandler(ScrollEvent.SCROLL, e -> wheel(false, e));
        time = icon("time", new StackPane(timePath), actions::nextTime);
        render = icon("render", new StackPane(renderBusy), () -> actions.details("render"));
        render.setText("GPU");
        render.getStyleClass().add("world-hud-text-icon");
        texture = icon("texture", new StackPane(textureBusy), () -> actions.details("texture"));
        texture.setText("TEX");
        texture.getStyleClass().add("world-hud-text-icon");
        mouse = icon("mouse", new StackPane(path(MOUSE), mouseSlash), () -> actions.details("mouse"));
        dots.setAlignment(Pos.CENTER);
        dots.setMouseTransparent(true);
        tracking = icon("tracking", dots, () -> actions.details("tracking"));
        tracking.managedProperty().bind(tracking.visibleProperty());
        row.getChildren().addAll(mode, speed, smooth, music, ambience, time, render, texture, mouse, tracking);
        mode(Mode.FLY, "");
        speed(WorldControls.Speed.FLY.yardsPerSecond, false, "");
        smooth(false);
        music(Sound.MUTED, "");
        ambience(Sound.MUTED, "");
        time(Time.CLOCK, "");
        render(Render.GPU, "");
        texture(Texture.READY, "");
        mouse(false, "");
        tracking(List.of());
    }

    HBox node() {
        return row;
    }

    /** Adds a trailing control (the "More" toggle) to the row with the same input rules. */
    void trailing(javafx.scene.control.ButtonBase control) {
        control.setFocusTraversable(false);
        control.getStyleClass().add("world-hud-icon");
        WorldHud.stopInput(control);
        row.getChildren().add(control);
    }

    void mode(Mode state, String tip) {
        modeState = Objects.requireNonNull(state);
        modePath.setContent(state == Mode.FLY ? WINGS : BOOTS);
        mode.pseudoClassStateChanged(AMBER, state == Mode.WALK_NO_TERRAIN);
        tip(mode, tip + "\nClick or press the walk key to " + (state == Mode.FLY ? "walk" : "fly"));
    }

    /** The live speed in yards per second; sprinting brightens the chip. */
    void speed(double yardsPerSecond, boolean sprinting, String tip) {
        speed.setText(String.format(Locale.ROOT, "%.0f", yardsPerSecond));
        speed.pseudoClassStateChanged(BRIGHT, sprinting);
        tip(speed, tip);
    }

    void smooth(boolean on) {
        smooth.setVisible(on);
    }

    void music(Sound state, String tip) {
        musicState = sound(music, musicSlash, state, tip);
    }

    void ambience(Sound state, String tip) {
        ambienceState = sound(ambience, ambienceSlash, state, tip);
    }

    void time(Time state, String tip) {
        timeState = Objects.requireNonNull(state);
        timePath.setContent(switch (state) {
            case CLOCK -> CLOCK;
            case DAY -> SUN;
            case NIGHT -> MOON;
        });
        tip(time, tip);
    }

    void render(Render state, String tip) {
        renderState = Objects.requireNonNull(state);
        render.setText(state == Render.SOFTWARE ? "CPU" : "GPU");
        status(render, renderBusy, state == Render.BUSY,
                state == Render.SOFTWARE || state == Render.PARTIAL, state == Render.FAILED);
        tip(render, tip);
    }

    void texture(Texture state, String tip) {
        textureState = Objects.requireNonNull(state);
        status(texture, textureBusy, state == Texture.LOADING, state == Texture.FALLBACK,
                state == Texture.FAILED);
        tip(texture, tip);
    }

    void mouse(boolean captureUnavailable, String tip) {
        mouseUnavailable = captureUnavailable;
        mouseSlash.setVisible(captureUnavailable);
        mouse.pseudoClassStateChanged(AMBER, captureUnavailable);
        tip(mouse, tip);
    }

    /**
     * One dot per active tracking layer, in the layer's marker colour; hidden when none. The view
     * calls this on every status refresh (each stream snapshot), so the dots are only rebuilt when
     * the colours change.
     */
    void tracking(List<Color> layers) {
        if (layers.equals(trackingShown)) return;
        trackingShown = List.copyOf(layers);
        dots.getChildren().setAll(layers.stream().map(color -> {
            var dot = new Circle(4, color);
            dot.setStroke(Color.rgb(12, 16, 14, .9));
            return (Node) dot;
        }).toList());
        tracking.setVisible(!layers.isEmpty());
        tip(tracking, layers.size() + (layers.size() == 1 ? " tracking layer" : " tracking layers")
                + " shown; click for the layer controls");
    }

    /** The dots shown now; for tests. */
    List<javafx.scene.Node> trackingDots() {
        return dots.getChildren();
    }

    /**
     * Render chip state from typed state, never from the status text: the view's status lines
     * carry stream details that routinely mention unavailable tiles or failed objects while the
     * view renders fine. {@code health} is what the view last recorded (failure, fallback, partial
     * or fine); a failure wins over everything but a running job, then the software choice.
     */
    static Render render(boolean busy, boolean gpuSelected, Render health) {
        if (busy) return Render.BUSY;
        if (health == Render.FAILED) return Render.FAILED;
        if (!gpuSelected || health == Render.SOFTWARE) return Render.SOFTWARE;
        return health == Render.PARTIAL ? Render.PARTIAL : Render.GPU;
    }

    /** Texture chip state from the view's typed texture outcome, never from the text. */
    static Texture texture(boolean busy, Texture health) {
        if (busy) return Texture.LOADING;
        return health == null || health == Texture.LOADING ? Texture.READY : health;
    }

    /** Stream render health from the typed snapshot: phase, stall alert and tile counts. */
    static Render streamHealth(WorldStreamSourceController.Phase phase, boolean stalled, int prepared, int desired) {
        if (phase == WorldStreamSourceController.Phase.FAILED || stalled) return Render.FAILED;
        if (phase == WorldStreamSourceController.Phase.UNAVAILABLE) return Render.SOFTWARE;
        // Settled with some wanted tiles not resident (for example missing tiles at a map edge).
        if (phase == WorldStreamSourceController.Phase.READY && desired > 0 && prepared < desired)
            return Render.PARTIAL;
        return Render.GPU;
    }

    Mode mode() {
        return modeState;
    }

    Sound music() {
        return musicState;
    }

    Sound ambience() {
        return ambienceState;
    }

    Time time() {
        return timeState;
    }

    Render render() {
        return renderState;
    }

    Texture texture() {
        return textureState;
    }

    boolean mouseUnavailable() {
        return mouseUnavailable;
    }

    private Sound sound(Button icon, Line slash, Sound state, String tip) {
        Objects.requireNonNull(state);
        slash.setVisible(state == Sound.MUTED);
        icon.pseudoClassStateChanged(DIM, state != Sound.ON);
        tip(icon, tip + "\nClick to " + (state == Sound.MUTED ? "turn it on" : "mute") + "; wheel changes the volume");
        return state;
    }

    private static void status(Button icon, ProgressIndicator spinner, boolean busy, boolean amber, boolean red) {
        spinner.setVisible(busy);
        spinner.setManaged(busy);
        icon.setContentDisplay(busy ? javafx.scene.control.ContentDisplay.LEFT
                : javafx.scene.control.ContentDisplay.TEXT_ONLY);
        icon.pseudoClassStateChanged(AMBER, amber);
        icon.pseudoClassStateChanged(RED, red);
    }

    private void wheel(boolean musicChannel, ScrollEvent e) {
        int steps = (int) Math.signum(e.getDeltaY());
        if (steps != 0) actions.volume(musicChannel, steps);
    }

    private Button icon(String name, Node graphic, Runnable click) {
        var button = new Button();
        button.setId("world-icon-" + name);
        button.getStyleClass().add("world-hud-icon");
        button.setFocusTraversable(false);
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip());
        button.setOnAction(e -> {
            click.run();
            refocus.run();
        });
        WorldHud.stopInput(button);
        return button;
    }

    private static void tip(Labeled icon, String text) {
        icon.getTooltip().setText(text == null ? "" : text.strip());
        icon.setAccessibleText(icon.getTooltip().getText());
    }

    private static SVGPath path(String content) {
        var path = new SVGPath();
        path.setContent(content);
        path.setFillRule(FillRule.EVEN_ODD);
        path.getStyleClass().add("world-hud-glyph");
        path.setScaleX(.75);
        path.setScaleY(.75);
        return path;
    }

    private static Line slash() {
        var line = new Line(0, 18, 18, 0);
        line.getStyleClass().add("world-hud-slash");
        line.setVisible(false);
        line.setMouseTransparent(true);
        return line;
    }

    private static ProgressIndicator busy() {
        var spinner = new ProgressIndicator();
        spinner.setPrefSize(12, 12);
        spinner.setMaxSize(12, 12);
        spinner.setVisible(false);
        spinner.setManaged(false);
        spinner.setMouseTransparent(true);
        return spinner;
    }
}
