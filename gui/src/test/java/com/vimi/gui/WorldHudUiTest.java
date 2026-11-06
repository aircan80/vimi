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
import javafx.scene.input.ScrollEvent;
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
                statusIconStates();
                audioSilenceFollowsPlaybackStatus();
                toastsTimeStackAndAct();
                screenshotIsAPngOfTheFrame();
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
                + " coordinates pill copy, every status icon state, click and input isolation, audio silence,"
                + " toasts (4 s, 3 stacked, actions) and PNG screenshots passed");
    }

    /** Toasts: 4 s each, at most 3 (the oldest leaves first), one optional action, no duplicates. */
    private static void toastsTimeStackAndAct() {
        var focus = new ArrayList<String>();
        var toasts = new WorldToasts(() -> focus.add("focus"));
        var column = toasts.node();
        require(WorldToasts.LIFETIME.equals(Duration.seconds(4)) && WorldToasts.LIMIT == 3, "4 s, 3 at most");
        var first = toasts.show("Bookmark saved: Goldshire");
        require(first.timer.getDuration().equals(Duration.seconds(4)) && first.timer.getStatus() == Animation.Status.RUNNING
                        && column.getChildren().equals(List.of(first.node)),
                "A toast shows and its 4 s timer runs");
        first.timer.getOnFinished().handle(null);
        require(toasts.shown().isEmpty() && column.getChildren().isEmpty(), "Its time up, it leaves");
        var a = toasts.show("one");
        var b = toasts.show("two");
        var c = toasts.show("three");
        var d = toasts.show("four");
        require(toasts.shown().equals(List.of(b, c, d)) && column.getChildren().equals(List.of(b.node, c.node, d.node))
                        && a.timer.getStatus() == Animation.Status.STOPPED,
                "A fourth pushes the oldest out");
        require(toasts.show("three") == c && toasts.shown().size() == 3, "The same notice again restarts, it does not stack");
        toasts.clear();
        var ran = new ArrayList<String>();
        var retry = toasts.show("GPU World frame upload failed; retry the viewer.", "Retry", () -> ran.add("retry"));
        var button = (javafx.scene.control.Button) retry.node.getChildren().get(1);
        require(button.getText().equals("Retry") && !button.isFocusTraversable(), "The action button, never focused");
        button.fire();
        require(ran.equals(List.of("retry")) && focus.equals(List.of("focus")) && toasts.shown().isEmpty(),
                "The action runs, the toast leaves and focus goes back to the world");
        try {
            toasts.show("x", "Retry", null);
            throw new AssertionError("An action label needs an action");
        } catch (IllegalArgumentException expected) {
            // both or neither
        }
        var bubbled = new ArrayList<String>();
        var parent = new StackPane(column);
        parent.addEventHandler(MouseEvent.ANY, e -> bubbled.add("mouse"));
        var plain = toasts.show("plain");
        click(plain.node);
        require(bubbled.isEmpty() && !column.isFocusTraversable(), "Clicks on a toast never reach the world");
        toasts.clear();
    }

    /** F12's encoder: a valid PNG of the frame's own pixels, saved under the library's screenshots folder. */
    private static void screenshotIsAPngOfTheFrame() {
        var frame = new javafx.scene.image.WritableImage(3, 2);
        int[] colours = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0x80FFFFFF, 0xFF000000, 0xFF123456};
        for (int i = 0; i < colours.length; i++) frame.getPixelWriter().setArgb(i % 3, i / 3, colours[i]);
        var pixels = WorldScreenshot.read(frame);
        var bytes = WorldScreenshot.png(pixels);
        require(bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G', "PNG signature");
        var decoded = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes));
        require(!decoded.isError() && decoded.getWidth() == 3 && decoded.getHeight() == 2, "JavaFX reads it back: " + decoded.getException());
        for (int i = 0; i < colours.length; i++)
            require(decoded.getPixelReader().getArgb(i % 3, i / 3) == colours[i],
                    "Pixel " + i + ": " + Integer.toHexString(decoded.getPixelReader().getArgb(i % 3, i / 3)));
        try {
            var library = java.nio.file.Files.createTempDirectory("world-screenshot-test");
            var when = java.time.LocalDateTime.of(2026, 11, 6, 15, 4, 5);
            var file = WorldScreenshot.save(pixels, library, "Eastern Kingdoms · Map #0", when);
            require(file.getParent().equals(library.resolve("screenshots"))
                            && file.getFileName().toString().equals("world_Eastern-Kingdoms-Map-0_2026-11-06_15-04-05.png"),
                    "Named by map and time in the library's screenshots folder: " + file);
            var again = WorldScreenshot.save(pixels, library, "Eastern Kingdoms · Map #0", when);
            require(!again.equals(file) && again.getFileName().toString().endsWith("_2.png"), "Never overwrites: " + again);
            require(java.util.Arrays.equals(java.nio.file.Files.readAllBytes(file), bytes), "The file is the PNG");
            for (var each : List.of(again, file, file.getParent(), library)) java.nio.file.Files.delete(each);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /** Every icon state from #507's table: the icon's look, its tooltip, its click and its input rules. */
    private static void statusIconStates() {
        var calls = new ArrayList<String>();
        var icons = new WorldStatusIcons(new WorldStatusIcons.Actions() {
            @Override public void toggleWalk() { calls.add("walk"); }
            @Override public void nextSpeed() { calls.add("speed"); }
            @Override public void toggleSmooth() { calls.add("smooth"); }
            @Override public void toggleMusic() { calls.add("music"); }
            @Override public void toggleAmbience() { calls.add("ambience"); }
            @Override public void volume(boolean music, int steps) { calls.add((music ? "music" : "ambience") + steps); }
            @Override public void nextTime() { calls.add("time"); }
            @Override public void details(String icon) { calls.add("details:" + icon); }
        }, () -> calls.add("focus"));
        var viewport = new StackPane(icons.node());
        var bubbled = new ArrayList<String>();
        viewport.addEventHandler(MouseEvent.ANY, e -> bubbled.add(e.getEventType().getName()));
        viewport.addEventHandler(KeyEvent.ANY, e -> bubbled.add(e.getEventType().getName()));
        viewport.addEventHandler(ScrollEvent.ANY, e -> bubbled.add("scroll"));
        var stage = new Stage();
        stage.setScene(new Scene(viewport, 600, 120));
        stage.show();
        try {
            var row = icons.node();
            // Mode: wings for flight, boots for walking, amber when walking has no terrain.
            var mode = button(row, "mode");
            icons.mode(WorldStatusIcons.Mode.FLY, "Free flight · no collision");
            require(icons.mode() == WorldStatusIcons.Mode.FLY && !amber(mode)
                            && mode.getTooltip().getText().startsWith("Free flight · no collision")
                            && mode.getTooltip().getText().endsWith("to walk"),
                    "Fly: " + mode.getTooltip().getText());
            var wings = glyph(mode);
            icons.mode(WorldStatusIcons.Mode.WALK, "Walking · eye 2 yd above rendered terrain · no collision");
            require(!amber(mode) && !glyph(mode).equals(wings) && mode.getTooltip().getText().endsWith("to fly"),
                    "Walk: boots, white");
            icons.mode(WorldStatusIcons.Mode.WALK_NO_TERRAIN, "Walking · no rendered terrain here; altitude held");
            require(amber(mode) && mode.getTooltip().getText().contains("altitude held"), "Walk with no terrain: amber");
            // Speed chip: the live number, brighter while sprinting.
            var speed = button(row, "speed");
            icons.speed(20, false, "Fly · 20 yd/s preset");
            require(speed.getText().equals("20") && !pseudo(speed, WorldStatusIcons.BRIGHT)
                    && speed.getTooltip().getText().equals("Fly · 20 yd/s preset"), "Speed chip");
            icons.speed(60.4, true, "Fly · 20 yd/s preset");
            require(speed.getText().equals("60") && pseudo(speed, WorldStatusIcons.BRIGHT), "Sprinting brightens the chip");
            // Smooth: only while on.
            var smooth = button(row, "smooth");
            icons.smooth(true);
            require(smooth.isVisible() && smooth.getText().equals("~"), "Smooth icon while on");
            icons.smooth(false);
            require(!smooth.isVisible() && !smooth.isManaged(), "Hidden while off");
            // Music and ambience: on, muted (slashed), nothing recorded (dimmed).
            for (var name : List.of("music", "ambience")) {
                var icon = button(row, name);
                for (var state : WorldStatusIcons.Sound.values()) {
                    if (name.equals("music")) icons.music(state, "Music · Recorded silence before the next track");
                    else icons.ambience(state, "Ambience · Ruins of Lordaeron");
                    var shown = name.equals("music") ? icons.music() : icons.ambience();
                    require(shown == state && slashed(icon) == (state == WorldStatusIcons.Sound.MUTED)
                                    && pseudo(icon, WorldStatusIcons.DIM) == (state != WorldStatusIcons.Sound.ON)
                                    && icon.getTooltip().getText().contains(name.equals("music") ? "Recorded silence" : "Ruins")
                                    && icon.getTooltip().getText().contains(
                                            state == WorldStatusIcons.Sound.MUTED ? "turn it on" : "to mute"),
                            name + " " + state + ": " + icon.getTooltip().getText());
                }
            }
            // Time: sun, moon, clock.
            var time = button(row, "time");
            var shapes = new java.util.HashSet<String>();
            for (var state : WorldStatusIcons.Time.values()) {
                icons.time(state, state + " slot");
                require(icons.time() == state && time.getTooltip().getText().equals(state + " slot"), "Time " + state);
                shapes.add(glyph(time));
            }
            require(shapes.size() == 3, "Clock, sun and moon are different glyphs");
            // Render: GPU white, software amber, failed red, busy spinning.
            var render = button(row, "render");
            icons.render(WorldStatusIcons.Render.GPU, "9 tiles: 9 textured");
            require(render.getText().equals("GPU") && !amber(render) && !pseudo(render, WorldStatusIcons.RED)
                    && !spinning(render) && render.getTooltip().getText().equals("9 tiles: 9 textured"), "Render GPU");
            icons.render(WorldStatusIcons.Render.SOFTWARE, "Software terrain");
            require(render.getText().equals("CPU") && amber(render), "Render software: amber");
            icons.render(WorldStatusIcons.Render.FAILED, "GPU World frame upload failed; retry the viewer.");
            require(pseudo(render, WorldStatusIcons.RED) && !amber(render), "Render failed: red");
            icons.render(WorldStatusIcons.Render.BUSY, "Waiting for previous World resources to retire…");
            require(spinning(render) && !pseudo(render, WorldStatusIcons.RED), "Render busy: spinning ring");
            require(WorldStatusIcons.render(true, true, "") == WorldStatusIcons.Render.BUSY
                            && WorldStatusIcons.render(false, false, "9 tiles") == WorldStatusIcons.Render.SOFTWARE
                            && WorldStatusIcons.render(false, true, "GPU terrain unavailable; using software rendering.")
                                    == WorldStatusIcons.Render.SOFTWARE
                            && WorldStatusIcons.render(false, true, "GPU World frame upload failed; retry the viewer.")
                                    == WorldStatusIcons.Render.FAILED
                            && WorldStatusIcons.render(false, true, WorldView.STALL_HINT) == WorldStatusIcons.Render.FAILED
                            && WorldStatusIcons.render(false, true, "World resources could not be confirmed retired (x)")
                                    == WorldStatusIcons.Render.FAILED
                            && WorldStatusIcons.render(false, true, "9 tiles: 9 textured · 40 surfaces")
                                    == WorldStatusIcons.Render.GPU,
                    "Render state from the GPU controls");
            // Texture: ready, loading (ring), failed (red).
            var texture = button(row, "texture");
            icons.texture(WorldStatusIcons.Texture.READY, "Basic terrain textures · 256/256 regions");
            require(!spinning(texture) && !pseudo(texture, WorldStatusIcons.RED), "Texture ready");
            icons.texture(WorldStatusIcons.Texture.LOADING, "Presenting prepared terrain…");
            require(spinning(texture), "Texture loading: ring");
            icons.texture(WorldStatusIcons.Texture.FAILED, "Terrain texture loading unavailable");
            require(pseudo(texture, WorldStatusIcons.RED)
                            && texture.getTooltip().getText().equals("Terrain texture loading unavailable"),
                    "Texture failed: red");
            require(WorldStatusIcons.texture(true, "") == WorldStatusIcons.Texture.LOADING
                            && WorldStatusIcons.texture(false, "Terrain texture frame unavailable · previous preview retained")
                                    == WorldStatusIcons.Texture.FAILED
                            && WorldStatusIcons.texture(false, "Height shading") == WorldStatusIcons.Texture.READY,
                    "Texture state from the texture line");
            // Mouse: slashed when cursor capture is unavailable.
            var mouse = button(row, "mouse");
            icons.mouse(false, "Mouse look: hold a mouse button and move the mouse");
            require(!slashed(mouse) && !icons.mouseUnavailable(), "Mouse normal");
            icons.mouse(true, "Mouse look: cursor capture unavailable; looking stays relative");
            require(slashed(mouse) && icons.mouseUnavailable() && mouse.getTooltip().getText().contains("unavailable"),
                    "Mouse slashed without capture");
            // Tracking: one dot per active layer, in its colour.
            var tracking = button(row, "tracking");
            icons.tracking(List.of());
            require(!tracking.isVisible(), "No tracking layer, no dots");
            icons.tracking(List.of(javafx.scene.paint.Color.GOLD, javafx.scene.paint.Color.CORNFLOWERBLUE));
            var dots = ((javafx.scene.layout.HBox) tracking.getGraphic()).getChildren();
            require(tracking.isVisible() && dots.size() == 2
                            && ((javafx.scene.shape.Circle) dots.get(1)).getFill()
                                    .equals(javafx.scene.paint.Color.CORNFLOWERBLUE)
                            && tracking.getTooltip().getText().startsWith("2 tracking layers"),
                    "Two layers, two coloured dots");
            // Clicks: each runs its action, then gives focus back to the viewport.
            icons.smooth(true);
            calls.clear();
            for (var name : List.of("mode", "speed", "smooth", "music", "ambience", "time", "render", "texture",
                    "mouse", "tracking"))
                button(row, name).fire();
            require(calls.equals(List.of("walk", "focus", "speed", "focus", "smooth", "focus", "music", "focus",
                            "ambience", "focus", "time", "focus", "details:render", "focus", "details:texture",
                            "focus", "details:mouse", "focus", "details:tracking", "focus")),
                    "Every icon click runs its action and refocuses: " + calls);
            calls.clear();
            wheel(button(row, "music"), 40);
            wheel(button(row, "ambience"), -40);
            require(calls.equals(List.of("music1", "ambience-1")),
                    "The wheel over a sound icon changes its volume: " + calls);
            for (var node : row.getChildren())
                require(!node.isFocusTraversable(), "Never focus-traversable: " + node.getId());
            click(mode);
            Event.fireEvent(mode, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, false, false, false));
            wheel(button(row, "music"), 40);
            require(bubbled.isEmpty(), "No pointer, key or wheel event bubbles to the viewport: " + bubbled);
        } finally {
            stage.close();
        }
    }

    /** "Nothing recorded" (the dimmed sound icon) comes from the playback's own status per channel. */
    private static void audioSilenceFollowsPlaybackStatus() {
        var audio = new WorldAudioControls();
        try {
            var music = com.vibeminer.concepts.world.WorldAreaAudio.Kind.MUSIC;
            var ambience = com.vibeminer.concepts.world.WorldAreaAudio.Kind.AMBIENCE;
            require(!audio.silent(music).get() && !audio.silent(ambience).get(), "Not silent before any status");
            audio.silent(new WorldAudioPlayback.Status(music, false, "No recorded music here"));
            require(audio.silent(music).get() && !audio.silent(ambience).get(), "No recorded music: music is silent");
            audio.silent(new WorldAudioPlayback.Status(ambience, false, "No recorded ambience here"));
            require(audio.silent(ambience).get(), "No recorded ambience: ambience is silent");
            audio.silent(new WorldAudioPlayback.Status(music, true, "Playing recorded music"));
            audio.silent(new WorldAudioPlayback.Status(ambience, false, "Ambience off"));
            require(!audio.silent(music).get() && !audio.silent(ambience).get(), "Playing or off is not silence");
        } finally {
            audio.close();
        }
    }

    private static javafx.scene.control.Button button(javafx.scene.Parent row, String name) {
        return (javafx.scene.control.Button) row.lookup("#world-icon-" + name);
    }

    private static boolean pseudo(javafx.scene.Node node, javafx.css.PseudoClass state) {
        return node.getPseudoClassStates().contains(state);
    }

    private static boolean amber(javafx.scene.Node node) {
        return pseudo(node, WorldStatusIcons.AMBER);
    }

    private static String glyph(javafx.scene.control.Button icon) {
        var graphic = (StackPane) icon.getGraphic();
        return ((javafx.scene.shape.SVGPath) graphic.getChildren().getFirst()).getContent();
    }

    private static boolean slashed(javafx.scene.control.Button icon) {
        return ((StackPane) icon.getGraphic()).getChildren().stream()
                .anyMatch(node -> node instanceof javafx.scene.shape.Line && node.isVisible());
    }

    private static boolean spinning(javafx.scene.control.Button icon) {
        var spinner = ((StackPane) icon.getGraphic()).getChildren().getFirst();
        return spinner instanceof javafx.scene.control.ProgressIndicator && spinner.isVisible()
                && icon.getContentDisplay() != javafx.scene.control.ContentDisplay.TEXT_ONLY;
    }

    private static void wheel(javafx.scene.Node node, double delta) {
        Event.fireEvent(node, new ScrollEvent(ScrollEvent.SCROLL, 4, 4, 4, 4, false, false, false, false, false,
                false, 0, delta, 0, delta, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(node, 4, 4)));
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
