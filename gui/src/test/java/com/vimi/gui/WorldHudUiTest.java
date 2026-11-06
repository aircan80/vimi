package com.vimi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** World HUD pieces on their own: zone text and banner timing, coordinates pill. Needs a display. */
public final class WorldHudUiTest {
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            try {
                zoneLineAndBannerTiming();
                coordinatesPillCopiesAndStopsInput();
                placedWidgetsHideWithTheHud();
                DONE.complete(null);
            } catch (Throwable error) {
                DONE.completeExceptionally(error);
            }
        });
        try {
            DONE.get(20, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
        System.out.println("World HUD UI: zone line, banner 400 ms in / 1.5 s hold / fade out, leave chip,"
                + " coordinates pill copy and input isolation, placed widgets hide with the HUD passed");
    }

    private static void zoneLineAndBannerTiming() {
        var left = new ArrayList<String>();
        var zone = new WorldZoneText(() -> left.add("leave"), () -> left.add("focus"));
        var banner = zone.banner();
        var animation = zone.bannerAnimation();
        require(!zone.line().isVisible() && !banner.isVisible(), "Nothing shows before an area is known");
        zone.area(List.of("Elwynn Forest", "Northshire Valley", "Northshire Abbey"));
        require(text(zone, "#world-zone-name").equals("Elwynn Forest")
                        && text(zone, "#world-zone-subzone").equals("Northshire Abbey") && zone.line().isVisible(),
                "The small line updates at once: zone, then the most specific area");
        require(banner.getText().equals("Northshire Abbey") && banner.isVisible()
                        && animation.getStatus() == Animation.Status.RUNNING,
                "A new area plays the banner");
        require(animation.getTotalDuration().equals(WorldZoneText.FADE_IN.add(WorldZoneText.HOLD).add(WorldZoneText.FADE_OUT))
                        && WorldZoneText.FADE_IN.equals(Duration.millis(400)) && WorldZoneText.HOLD.equals(Duration.millis(1500)),
                "Banner timing: 400 ms in, 1.5 s hold, then out: " + animation.getTotalDuration());
        animation.pause();
        animation.jumpTo(Duration.millis(200));
        require(near(banner.getOpacity(), .5), "Half faded in at 200 ms: " + banner.getOpacity());
        animation.jumpTo(Duration.millis(1000));
        require(near(banner.getOpacity(), 1), "Fully shown while it holds: " + banner.getOpacity());
        animation.jumpTo(Duration.millis(1899));
        require(near(banner.getOpacity(), 1), "Still held at 1.9 s: " + banner.getOpacity());
        animation.jumpTo(Duration.millis(2200));
        require(banner.getOpacity() < 1 && banner.getOpacity() > 0, "Fading out at 2.2 s: " + banner.getOpacity());
        animation.jumpTo(animation.getTotalDuration());
        require(near(banner.getOpacity(), 0), "Faded out at the end: " + banner.getOpacity());
        animation.stop();
        animation.getOnFinished().handle(null);
        require(!banner.isVisible(), "Finishing hides the banner");
        zone.area(List.of("Elwynn Forest", "Northshire Valley", "Northshire Abbey"));
        require(animation.getStatus() == Animation.Status.STOPPED, "The same area does not replay the banner");
        zone.area(List.of("Elwynn Forest"));
        require(text(zone, "#world-zone-subzone").isEmpty() && !zone.line().lookup("#world-zone-subzone").isVisible()
                        && banner.getText().equals("Elwynn Forest") && animation.getStatus() == Animation.Status.RUNNING,
                "A zone without a sub-area: one line, and a banner for the zone");
        animation.stop();
        zone.area(List.of());
        require(!zone.line().isVisible() && animation.getStatus() == Animation.Status.STOPPED,
                "An unknown area clears the line and plays nothing");
        zone.leave().setVisible(true);
        require(zone.line().isVisible() && zone.leave().getText().equals("Leave"), "The leave chip keeps the line shown");
        zone.leave().fire();
        require(left.equals(List.of("leave", "focus")), "Leave runs the view's leave, then refocuses the world");
        zone.area(List.of("Elwynn Forest"));
        zone.reset();
        require(!banner.isVisible() && animation.getStatus() == Animation.Status.STOPPED, "Reset stops the banner");
        zone.area(List.of("Elwynn Forest"));
        require(animation.getStatus() == Animation.Status.RUNNING, "After a reset the same zone plays again");
        animation.stop();
    }

    private static void coordinatesPillCopiesAndStopsInput() {
        var copied = new ArrayList<String>();
        var focus = new ArrayList<String>();
        var pill = new WorldCoordinatesPill(copied::add, () -> focus.add("focus"));
        var viewport = new StackPane(pill.node());
        var bubbled = new ArrayList<String>();
        viewport.addEventHandler(MouseEvent.ANY, e -> bubbled.add(e.getEventType().getName()));
        viewport.addEventHandler(KeyEvent.ANY, e -> bubbled.add(e.getEventType().getName()));
        var stage = new Stage();
        stage.setScene(new Scene(viewport, 300, 200));
        stage.show();
        try {
            require(!pill.node().isVisible() && !pill.node().isFocusTraversable(), "Hidden without a camera; never focused");
            click(pill.node());
            require(copied.isEmpty(), "Nothing to copy without a camera");
            pill.show(1611.94, -405.04, 307.1, 28, 31);
            require(pill.node().getText().equals("x 1611.9  y -405.0  alt 307.1\ntile 28,31"),
                    "x, y, alt, then the tile: " + pill.node().getText());
            focus.clear();
            click(pill.node());
            require(copied.equals(List.of("1611.9 -405.0 307.1")), "A click copies x y z: " + copied);
            require(focus.equals(List.of("focus")), "Focus goes back to the viewport");
            require(com.vibeminer.concepts.world.WorldBookmarks.parseCoordinates(copied.getFirst()).isPresent(),
                    "The travel panel parses what was copied");
            Event.fireEvent(pill.node(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, false, false, false));
            require(bubbled.isEmpty(), "No pointer or key event bubbles to the viewport: " + bubbled);
            pill.clear();
            require(!pill.node().isVisible() && pill.coordinates() == null, "Cleared");
        } finally {
            stage.close();
        }
    }

    /**
     * The zone line, icon row, coordinates pill and banner hide with the HUD; the developer slot
     * stays, and the left column stays above the placed widgets.
     */
    private static void placedWidgetsHideWithTheHud() {
        var hud = new WorldHud(() -> {}, () -> {});
        var zone = new javafx.scene.control.Label("zone");
        var icons = new javafx.scene.control.Label("icons");
        var pill = new javafx.scene.control.Label("pill");
        var banner = new javafx.scene.control.Label("banner");
        var dev = new javafx.scene.control.Label("dev");
        hud.topCenter(zone);
        hud.bottomLeft(icons);
        hud.bottomRight(pill);
        hud.banner(banner);
        hud.devOverlay(dev);
        var column = hud.lookup("#world-hud-left");
        var widgets = hud.lookup("#world-hud-widgets");
        require(hud.getChildren().indexOf(widgets) < hud.getChildren().indexOf(column),
                "The left column stays above the placed widgets");
        for (var node : List.of(zone, icons, pill, banner))
            require(node.getParent() == widgets && node.isVisible() && widgets.isVisible(), "Placed and shown: " + node.getText());
        hud.hidden(true);
        for (var node : List.of(zone, icons, pill, banner))
            require(!node.getParent().isVisible(), "Hidden with the HUD: " + node.getText());
        require(dev.getParent().isVisible() && hud.isVisible(), "The developer slot stays while hidden");
        hud.hidden(false);
        require(widgets.isVisible(), "Shown again with the HUD");
    }

    private static void click(javafx.scene.Node node) {
        for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(node, new MouseEvent(type, 4, 4, 4, 4, MouseButton.PRIMARY, 1, false, false, false,
                    false, type == MouseEvent.MOUSE_PRESSED, false, false, false, false, true,
                    new PickResult(node, 4, 4)));
        }
    }

    private static String text(WorldZoneText zone, String id) {
        return ((javafx.scene.control.Label) zone.line().lookup(id)).getText();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < .02;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
