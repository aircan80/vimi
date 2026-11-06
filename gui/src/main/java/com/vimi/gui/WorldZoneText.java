package com.vimi.gui;

import java.util.List;
import java.util.Objects;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Zone text for the World HUD. The top-centre line shows the recorded area and its sub-area and
 * updates at once. When the most specific area changes, a large banner fades in over the upper
 * third of the view, holds, and fades out, as game clients do on entering a zone. A "Leave" chip
 * sits next to the zone while an instance can be left. FX thread only.
 */
final class WorldZoneText {
    static final Duration FADE_IN = Duration.millis(400), HOLD = Duration.millis(1500),
            FADE_OUT = Duration.millis(600);

    private final Label zone = new Label(), subzone = new Label(), banner = new Label();
    private final Button leave = new Button("Leave");
    private final VBox line;
    private final SequentialTransition bannerAnimation;
    private final Tooltip tooltip = new Tooltip();
    private String shown = "";

    /** {@code onLeave} leaves the current instance (the view's Leave instance action). */
    WorldZoneText(Runnable onLeave, Runnable refocus) {
        Objects.requireNonNull(onLeave);
        Objects.requireNonNull(refocus);
        zone.setId("world-zone-name");
        zone.getStyleClass().add("world-hud-zone");
        subzone.setId("world-zone-subzone");
        subzone.getStyleClass().add("world-hud-subzone");
        leave.setId("world-zone-leave");
        leave.getStyleClass().add("world-hud-map-button");
        leave.setFocusTraversable(false);
        leave.setVisible(false);
        leave.managedProperty().bind(leave.visibleProperty());
        leave.setOnAction(e -> {
            onLeave.run();
            refocus.run();
        });
        WorldHud.stopInput(leave);
        var top = new HBox(8, zone, leave);
        top.setAlignment(Pos.CENTER);
        top.setPickOnBounds(false);
        line = new VBox(0, top, subzone);
        line.setId("world-zone-text");
        line.setAlignment(Pos.TOP_CENTER);
        line.setPickOnBounds(false);
        line.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        subzone.managedProperty().bind(subzone.visibleProperty());
        line.visibleProperty().bind(zone.textProperty().isNotEmpty().or(leave.visibleProperty()));
        Tooltip.install(line, tooltip);
        WorldHud.stopInput(line);
        banner.setId("world-zone-banner");
        banner.getStyleClass().add("world-hud-banner");
        banner.setMouseTransparent(true);
        banner.setOpacity(0);
        banner.setVisible(false);
        var in = new FadeTransition(FADE_IN, banner);
        in.setFromValue(0);
        in.setToValue(1);
        var out = new FadeTransition(FADE_OUT, banner);
        out.setFromValue(1);
        out.setToValue(0);
        bannerAnimation = new SequentialTransition(in, new PauseTransition(HOLD), out);
        bannerAnimation.setOnFinished(e -> banner.setVisible(false));
        clearLine();
    }

    /** The top-centre zone line (zone, leave chip and sub-area). */
    VBox line() {
        return line;
    }

    /** The large zone-change banner; the HUD centres it in the upper third. */
    Label banner() {
        return banner;
    }

    Animation bannerAnimation() {
        return bannerAnimation;
    }

    /** Whether the Leave chip shows; follows the view's "Leave instance" button. */
    Button leave() {
        return leave;
    }

    /**
     * The recorded area chain, outermost first (e.g. zone, then sub-area), or empty when the area
     * is unknown. The line updates now; a new most specific area also plays the banner. An unknown
     * area keeps the last known one, on the line and as the banner's memory: tiles not loaded yet
     * or a tab switch report "unknown" for a moment, and coming back to the same area must not
     * replay the banner. Only {@link #reset()} forgets it.
     */
    void area(List<String> chain) {
        var names = chain == null ? List.<String>of()
                : chain.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (names.isEmpty()) return;
        zone.setText(names.getFirst());
        subzone.setText(names.size() < 2 ? "" : names.getLast());
        subzone.setVisible(names.size() >= 2);
        String specific = names.getLast();
        if (specific.equals(shown)) return;
        shown = specific;
        banner.setText(specific);
        banner.setVisible(true);
        bannerAnimation.playFromStart();
    }

    /** The zone line's tooltip; the view binds it to its full "Terrain area: ..." text. */
    Tooltip tooltip() {
        return tooltip;
    }

    /**
     * Stops the banner and forgets the area, e.g. when the explorer closes or the map changes; the
     * next area plays the banner again.
     */
    void reset() {
        bannerAnimation.stop();
        banner.setOpacity(0);
        banner.setVisible(false);
        shown = "";
        clearLine();
    }

    private void clearLine() {
        zone.setText("");
        subzone.setText("");
        subzone.setVisible(false);
    }
}
