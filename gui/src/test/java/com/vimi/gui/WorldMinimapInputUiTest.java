package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Actual control events over the real World viewport; authored source/pixels only. */
public final class WorldMinimapInputUiTest {
    private WorldMinimapInputUiTest() {}

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
                controlEventsNeverSteerWorld();
            });
            popupKeyboardSelectsOrientation();
            fx(WorldMinimapInputUiTest::viewportStillAcceptsFlightInput);
            System.out.println("World minimap input PASS: actual mouse/key/scroll, popup selection, viewport flight isolation");
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static void controlEventsNeverSteerWorld() {
        var choice = (ChoiceBox<?>) view.lookup("#world-minimap-orientation");
        var before = (TerrainRenderer.Camera) get("camera");
        double speed = input().speed();
        choice.requestFocus();
        key(choice, KeyCode.W, true);
        key(choice, KeyCode.RIGHT, true);
        tick();
        require(before.equals(get("camera")), "Orientation keys must not move or turn the camera");
        key(choice, KeyCode.W, false);
        key(choice, KeyCode.RIGHT, false);
        mouse(choice, MouseEvent.MOUSE_PRESSED, 10, 10);
        mouse(choice, MouseEvent.MOUSE_DRAGGED, 35, 30);
        mouse(choice, MouseEvent.MOUSE_RELEASED, 35, 30);
        mouse(choice, MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY);
        mouse(choice, MouseEvent.MOUSE_DRAGGED, 35, 30, MouseButton.SECONDARY);
        tick();
        mouse(choice, MouseEvent.MOUSE_RELEASED, 35, 30, MouseButton.SECONDARY);
        require(before.equals(get("camera")), "Dragging orientation must not look around the world");
        Event.fireEvent(choice, new ScrollEvent(ScrollEvent.SCROLL,
                10, 10, 10, 10, false, false, false, false, false, false,
                0, 40, 0, 40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(choice, 10, 10)));
        require(speed == input().speed(), "Scrolling orientation must not change flight speed");
        choice.hide();
    }

    private static void popupKeyboardSelectsOrientation() throws Exception {
        fx(WorldMinimapInputUiTest::openOrientationPopup);
        // Popup showing/layout and native window focus settle on later FX turns. In particular,
        // Scene.focusOwner alone can refer to a row whose Node.isFocused is false after another
        // GUI/native test window took focus. Dispatch navigation in the SAME turn as this check.
        await(() -> {
            var choice = (ChoiceBox<?>) view.lookup("#world-minimap-orientation");
            if (!choice.isShowing()) {
                openOrientationPopup();
                return false;
            }
            var popup = orientationPopup();
            if (!popup.isFocused()) {
                popup.requestFocus();
                return false;
            }
            var root = popup.getScene().getRoot();
            return root.getLayoutBounds().getWidth() > 0
                    && root.getLayoutBounds().getHeight() > 0
                    && root.lookupAll(".menu-item").size() == 2;
        }, WorldMinimapInputUiTest::selectOrientationWithKeyboard);
    }

    private static void openOrientationPopup() {
        stage.toFront();
        stage.requestFocus();
        var choice = (ChoiceBox<?>) view.lookup("#world-minimap-orientation");
        choice.requestFocus();
        if (!choice.isShowing()) {
            key(choice, KeyCode.SPACE, true);
            key(choice, KeyCode.SPACE, false);
        }
        require(choice.isShowing(), "Space opens the actual orientation popup");
        orientationPopup().requestFocus();
    }

    private static PopupWindow orientationPopup() {
        return (PopupWindow) Window.getWindows().stream()
                .filter(window -> window instanceof PopupWindow && window.isShowing())
                .findFirst().orElseThrow();
    }

    private static void selectOrientationWithKeyboard() {
        var choice = (ChoiceBox<?>) view.lookup("#world-minimap-orientation");
        var popup = orientationPopup();
        require(popup.isFocused(), "Popup is active before keyboard navigation");
        // Native pointer placement can hover either popup row when its screen position changes.
        // Drive an actual hover into the known first row before testing keyboard navigation;
        // neither the selection model nor the value is changed programmatically.
        var north = popup.getScene().getRoot().lookupAll(".menu-item").stream()
                .filter(node -> "North up".equals(menuText(node)))
                .findFirst().orElseThrow();
        var heading = popup.getScene().getRoot().lookupAll(".menu-item").stream()
                .filter(node -> "Heading up".equals(menuText(node)))
                .findFirst().orElseThrow();
        hover(heading);
        require("Heading up".equals(menuText(popup.getScene().getFocusOwner())),
                "Adversarial native-pointer hover may initially focus the second row");
        hover(north);
        var focused = java.util.Objects.requireNonNull(popup.getScene().getFocusOwner(),
                "Orientation popup owns keyboard focus");
        require(focused.isFocused() && "North up".equals(menuText(focused)),
                "Hover establishes an actively focused first popup row");
        key(focused, KeyCode.DOWN, true);
        key(focused, KeyCode.DOWN, false);
        var selected = java.util.Objects.requireNonNull(popup.getScene().getFocusOwner());
        require(selected.isFocused() && "Heading up".equals(menuText(selected)),
                "Down moves active focus to the second row");
        key(selected, KeyCode.ENTER, true);
        key(selected, KeyCode.ENTER, false);
        require("Heading up".equals(choice.getValue()), "Popup keyboard chooses Heading up");
        require(!choice.isShowing(), "Popup selection closes normally");
        require(!input().keysHeld(), "Control input leaves no held flight keys");
    }

    private static void hover(Node node) {
        Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_ENTERED,
                5, 5, 5, 5, MouseButton.NONE, 0, false, false, false, false,
                false, false, false, false, false, true, new PickResult(node, 5, 5)));
    }

    private static String menuText(Node node) {
        var label = node.lookup(".label");
        return label instanceof javafx.scene.control.Labeled value ? value.getText() : null;
    }

    private static void viewportStillAcceptsFlightInput() {
        var viewport = view.lookup("#world-viewport");
        var before = (TerrainRenderer.Camera) get("camera");
        var minimap = view.lookup("#world-minimap");
        mouse(minimap, MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY);
        mouse(minimap, MouseEvent.MOUSE_DRAGGED, 35, 30, MouseButton.SECONDARY);
        tick();
        mouse(minimap, MouseEvent.MOUSE_RELEASED, 35, 30, MouseButton.SECONDARY);
        require(!before.equals(get("camera")), "Right-dragging non-control minimap artwork still looks around");
        require(viewport.isFocused(), "Clicking non-control artwork restores viewport focus");
        var moved = (TerrainRenderer.Camera) get("camera");
        key(viewport, KeyCode.W, true);
        tick();
        key(viewport, KeyCode.W, false);
        require(!moved.equals(get("camera")), "Viewport keyboard still moves");
    }

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
