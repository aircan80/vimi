package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;

/**
 * The F3 developer overlay over the real World viewport: the key cycles its pages and turns it
 * off, the text panel takes no mouse, its buttons fire the view's own Retry actions without
 * steering the world and give focus back, Copy report covers every page, and the key follows
 * rebinding. Authored source and pixels only; requires a display.
 */
public final class WorldDevOverlayUiTest {
    private WorldDevOverlayUiTest() {}

    private static WorldView view;
    private static Stage stage;
    private static final WorldFormats.Tile TILE = new WorldFormats.Tile(7, 19, 1, 0, 2);

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(10, TimeUnit.SECONDS);
        try {
            fx(() -> {
                var source = new Source();
                view = new WorldView(() -> source, () -> 1);
                stage = new Stage();
                stage.setScene(new Scene(view, 1000, 750));
                stage.show();
                view.ensureLoaded();
            });
            await(() -> ((ListView<?>) view.lookup("#world-maps")).getItems().size() == 1);
            fx(() -> ((ListView<?>) view.lookup("#world-maps")).getSelectionModel().selectFirst());
            await(() -> !((Button) view.lookup("#world-open")).isDisabled());
            fx(() -> ((Button) view.lookup("#world-open")).fire());
            await(() -> get("camera") != null && !(boolean) get("scenePreparing")
                    && view.lookup("#world-minimap").isVisible());
            fx(() -> {
                ((AnimationTimer) get("timer")).stop();
                input().holdCadence();
                stage.toFront();
                stage.requestFocus();
                view.lookup("#world-viewport").requestFocus();
            });
            fx(WorldDevOverlayUiTest::keyCyclesPagesThenOff);
            fx(WorldDevOverlayUiTest::panelTakesNoMouseAndLabelsStay);
            fx(WorldDevOverlayUiTest::buttonsFireRetryWithoutSteeringTheWorld);
            fx(WorldDevOverlayUiTest::copyReportCoversEveryPage);
            fx(WorldDevOverlayUiTest::keyFollowsRebinding);
            System.out.println("World developer overlay PASS: F3 page cycle and off, mouse-transparent"
                    + " panel, Retry/Copy buttons isolated from flight with focus returned, report,"
                    + " rebinding");
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static WorldDevOverlay overlay() {
        return view.devOverlay();
    }

    private static void press(KeyCode code) {
        var viewport = view.lookup("#world-viewport");
        key(viewport, code, true);
        key(viewport, code, false);
    }

    private static void keyCyclesPagesThenOff() {
        var node = view.lookup("#world-dev-overlay");
        require(node == overlay() && !node.isVisible() && overlay().page() == null,
                "The overlay starts off");
        var before = (TerrainRenderer.Camera) get("camera");
        press(KeyCode.F3);
        require(node.isVisible() && overlay().page() == WorldDevReport.Page.STREAM,
                "F3 opens the Stream page");
        require(overlay().headerText().contains("[1 Stream]") && overlay().headerText().endsWith("F3: next"),
                "Header: " + overlay().headerText());
        var position = ((Label) view.lookup("#world-position")).getText();
        require(overlay().text().startsWith("tile      7,19\ncamera    x "),
                "Stream page starts with the labelled position:\n" + overlay().text());
        require(overlay().text().contains(position.substring(position.indexOf("x "),
                        position.indexOf(" · ", position.indexOf("x ")))),
                "The camera line follows the position label: " + position + "\n" + overlay().text());
        press(KeyCode.F3);
        require(overlay().page() == WorldDevReport.Page.RENDER && overlay().headerText().contains("[2 Render]"),
                "F3 again: Render");
        press(KeyCode.F3);
        require(overlay().page() == WorldDevReport.Page.INPUT
                        && overlay().text().contains("look      cursor capture OK")
                        && overlay().text().contains("motion    Free flight"),
                "Input page:\n" + overlay().text());
        press(KeyCode.F3);
        require(overlay().page() == WorldDevReport.Page.AUDIO
                        && overlay().text().equals("audio     "
                                + ((Label) view.lookup("#world-audio-status")).getText()),
                "Audio page shows the audio status:\n" + overlay().text());
        press(KeyCode.ESCAPE);
        require(overlay().page() == WorldDevReport.Page.AUDIO && node.isVisible(),
                "Escape leaves the overlay open");
        press(KeyCode.F3);
        require(overlay().page() == null && !node.isVisible(), "F3 after Audio turns the overlay off");
        tick();
        require(before.equals(get("camera")) && !input().keysHeld(), "F3 never moves the camera");
        var viewport = view.lookup("#world-viewport");
        key(viewport, KeyCode.W, true);
        press(KeyCode.F3);
        press(KeyCode.F3);
        press(KeyCode.F3);
        require(overlay().page() == WorldDevReport.Page.INPUT && overlay().text().contains("held      W"),
                "The Input page lists held keys:\n" + overlay().text());
        key(viewport, KeyCode.W, false);
        input().clear();
        set("camera", before);
        overlay().show(WorldDevReport.Page.STREAM);
    }

    private static void panelTakesNoMouseAndLabelsStay() {
        var panel = view.lookup("#world-dev-overlay-panel");
        require(panel.isMouseTransparent() && !overlay().isPickOnBounds()
                        && !overlay().isFocusTraversable(),
                "Only the overlay's buttons take the mouse");
        for (var id : List.of("#world-gpu-status", "#world-texture-status", "#world-look-status",
                "#world-position", "#world-gpu-retry", "#world-texture-retry")) {
            require(view.lookup(id) != null, "The existing control stays: " + id);
        }
        var viewport = view.lookup("#world-viewport");
        view.getScene().getRoot().applyCss();
        view.getScene().getRoot().layout();
        var bounds = panel.localToScene(panel.getLayoutBounds());
        require(bounds.getWidth() > 40 && bounds.getHeight() > 40, "The panel is laid out: " + bounds);
        double x = bounds.getMinX() + 20, y = bounds.getMinY() + 20;
        var picked = pick(view.getScene().getRoot(), x, y);
        require(picked != null && !isWithin(picked, panel),
                "A click on the overlay text reaches the world, picked: " + picked);
        require(isWithin(picked, viewport), "The click lands in the viewport: " + picked);
    }

    private static void buttonsFireRetryWithoutSteeringTheWorld() {
        var viewRetryGpu = (Button) get("retryGpu");
        var viewRetryTextures = (Button) get("retryTextures");
        var gpu = (Button) view.lookup("#world-dev-overlay-retry-gpu");
        var textures = (Button) view.lookup("#world-dev-overlay-retry-textures");
        var copy = (Button) view.lookup("#world-dev-overlay-copy");
        require(gpu.isDisabled() == viewRetryGpu.isDisabled()
                        && textures.isDisabled() == viewRetryTextures.isDisabled(),
                "Overlay Retry buttons follow the view's");
        // Only the firing is observed: Retry must not restart anything in this fixture.
        var fired = new ArrayList<String>();
        viewRetryGpu.setOnAction(e -> fired.add("gpu"));
        viewRetryTextures.setOnAction(e -> fired.add("textures"));
        viewRetryGpu.setDisable(false);
        viewRetryTextures.setDisable(false);
        require(!gpu.isDisabled() && !textures.isDisabled(), "Enabled with the view's");
        overlay().clipboard(text -> {});
        var viewport = view.lookup("#world-viewport");
        var before = (TerrainRenderer.Camera) get("camera");
        double speed = input().speed();
        for (var button : List.of(gpu, textures, copy)) {
            require(!button.isFocusTraversable(), "Not focus traversable: " + button.getText());
            key(button, KeyCode.W, true);
            key(button, KeyCode.RIGHT, true);
            tick();
            key(button, KeyCode.W, false);
            key(button, KeyCode.RIGHT, false);
            require(before.equals(get("camera")), "Keys on " + button.getText() + " never fly");
            mouse(button, MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY);
            mouse(button, MouseEvent.MOUSE_DRAGGED, 35, 30, MouseButton.SECONDARY);
            pulse();
            mouse(button, MouseEvent.MOUSE_RELEASED, 35, 30, MouseButton.SECONDARY);
            require(before.equals(get("camera")) && !input().looking(),
                    "Dragging " + button.getText() + " never looks around");
            Event.fireEvent(button, new ScrollEvent(ScrollEvent.SCROLL,
                    10, 10, 10, 10, false, false, false, false, false, false,
                    0, 40, 0, 40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(button, 10, 10)));
            require(speed == input().speed() && before.equals(get("camera")),
                    "Scrolling " + button.getText() + " never dollies or changes speed");
        }
        fired.clear();
        for (var button : List.of(gpu, textures)) {
            copy.requestFocus();
            mouse(button, MouseEvent.MOUSE_PRESSED, 10, 10);
            mouse(button, MouseEvent.MOUSE_RELEASED, 10, 10);
            require(viewport.isFocused(), "Focus returns to the viewport after " + button.getText());
        }
        require(fired.equals(List.of("gpu", "textures")), "Clicks fire the view's Retry buttons: " + fired);
        require(before.equals(get("camera")) && !input().looking(), "Clicking a button never looks");
        key(viewport, KeyCode.W, true);
        tick();
        key(viewport, KeyCode.W, false);
        require(!before.equals(get("camera")), "The viewport still flies after a button click");
        set("camera", before);
    }

    private static void copyReportCoversEveryPage() {
        var copied = new ArrayList<String>();
        overlay().clipboard(copied::add);
        var copy = (Button) view.lookup("#world-dev-overlay-copy");
        copy.fire();
        require(copied.size() == 1, "Copy report copies once");
        var report = copied.getFirst();
        for (var page : WorldDevReport.Page.values()) {
            require(report.contains("== " + page.label + " =="), "Report has " + page + ":\n" + report);
        }
        require(report.contains("tile      7,19") && report.contains("audio     "),
                "Report holds the page lines:\n" + report);
        require(view.lookup("#world-viewport").isFocused(), "Copy returns focus to the viewport");
        var gpuStatus = (Label) view.lookup("#world-gpu-status");
        String previous = gpuStatus.getText();
        String home = System.getProperty("user.home");
        gpuStatus.setText("GPU helper failed reading " + home + java.io.File.separator + "vimi"
                + java.io.File.separator + "frame.bin; retry the viewer.");
        copy.fire();
        var redacted = copied.getLast();
        require(!redacted.contains(home) && !redacted.matches("(?s).*[A-Za-z]:\\\\Users.*")
                        && redacted.contains("retry the viewer."),
                "Copy report carries no absolute local path:\n" + redacted);
        gpuStatus.setText(previous);
    }

    private static void keyFollowsRebinding() {
        overlay().show(null);
        view.showControls(WorldControls.DEFAULTS.unbind(WorldControls.Action.DEV_OVERLAY)
                .bind(KeyCode.F7, WorldControls.Action.DEV_OVERLAY));
        press(KeyCode.F3);
        require(overlay().page() == null, "F3 is free once the overlay is rebound");
        press(KeyCode.F7);
        require(overlay().page() == WorldDevReport.Page.STREAM && overlay().headerText().endsWith("F7: next"),
                "The rebound key opens it and the header names it: " + overlay().headerText());
        view.showControls(WorldControls.DEFAULTS.withKeys(WorldControls.Layout.AZERTY.keys()));
        press(KeyCode.F3);
        require(overlay().page() == WorldDevReport.Page.RENDER && overlay().headerText().endsWith("F3: next"),
                "The AZERTY preset keeps F3");
        view.showControls(WorldControls.DEFAULTS);
    }

    /** The topmost node JavaFX would pick at a scene point, honouring mouse transparency. */
    private static Node pick(Node node, double sceneX, double sceneY) {
        if (!node.isVisible() || node.isMouseTransparent()) return null;
        if (node instanceof javafx.scene.Parent parent) {
            var children = parent.getChildrenUnmodifiable();
            for (int i = children.size() - 1; i >= 0; i--) {
                var hit = pick(children.get(i), sceneX, sceneY);
                if (hit != null) return hit;
            }
        }
        var local = node.sceneToLocal(sceneX, sceneY);
        if (local == null || !node.contains(local)) return null;
        return node.isPickOnBounds() || !(node instanceof javafx.scene.layout.Region region)
                || region.getBackground() != null && !region.getBackground().isEmpty()
                        ? node : null;
    }

    private static boolean isWithin(Node node, Node ancestor) {
        for (var at = node; at != null; at = at.getParent()) if (at == ancestor) return true;
        return false;
    }

    /** One more timer pulse without resetting owed look movement (unlike {@link #tick}). */
    private static void pulse() {
        clock += 16_000_000L;
        ((AnimationTimer) get("timer")).handle(clock);
    }

    private static long clock = 5_000_000_000L;

    private static void tick() {
        input().idle();
        var timer = (AnimationTimer) get("timer");
        timer.handle(1_000_000_000L);
        timer.handle(1_100_000_000L);
    }

    private static void key(Node node, KeyCode code, boolean down) {
        Event.fireEvent(node, new KeyEvent(down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED,
                "", "", code, false, false, false, false));
    }

    private static void mouse(Node node, EventType<MouseEvent> type, double x, double y) {
        mouse(node, type, x, y, MouseButton.PRIMARY);
    }

    private static void mouse(Node node, EventType<MouseEvent> type, double x, double y, MouseButton button) {
        boolean down = type != MouseEvent.MOUSE_RELEASED;
        Event.fireEvent(node, new MouseEvent(type, x, y, x, y, button, 1,
                false, false, false, false, down && button == MouseButton.PRIMARY,
                false, down && button == MouseButton.SECONDARY, false, false, true, new PickResult(node, x, y)));
    }

    private static WorldExplorerInput input() {
        return (WorldExplorerInput) get("input");
    }

    private static Object get(String name) {
        try { return field(name).get(view); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(String name, Object value) {
        try { field(name).set(view, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Field field(String name) throws NoSuchFieldException {
        var field = WorldView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, () -> {});
    }

    private static void await(BooleanSupplier condition, Runnable action) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var ready = new CompletableFuture<Boolean>();
            Platform.runLater(() -> {
                try {
                    boolean satisfied = condition.getAsBoolean();
                    if (satisfied) action.run();
                    ready.complete(satisfied);
                }
                catch (Throwable error) { ready.completeExceptionally(error); }
            });
            if (ready.get(5, TimeUnit.SECONDS)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("World fixture did not settle");
    }

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable error) { done.completeExceptionally(error); }
        });
        done.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Source implements BuildWorld {
        private final WorldService.MapInfo map = new WorldService.MapInfo(0, "Authored", null, 0, null);
        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Catalog(List.of(map), List.of(), List.of()));
        }
        public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo selected, Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Inventory(map,
                    new WorldFormats.World(List.of(TILE), false), null));
        }
        public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo selected, WorldFormats.Tile tile, Cancellation token) {
            float side = (float) WorldFormats.TILE_SIZE;
            var terrain = new WorldFormats.Terrain(new float[] {10000, 20000, 0,
                    10000 - side, 20000, 0, 10000, 20000 - side, 0}, new int[] {0, 1, 2}, 1);
            return CompletableFuture.completedFuture(new WorldService.LoadedTile(tile, terrain, null));
        }
        public CompletableFuture<WorldService.Minimap> minimap(WorldFormats.Tile tile, Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Minimap(2, 2,
                    IntBuffer.wrap(new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff}), null));
        }
        public CompletableFuture<Void> closeAsync() { throw new AssertionError("Borrowed source"); }
    }
}
