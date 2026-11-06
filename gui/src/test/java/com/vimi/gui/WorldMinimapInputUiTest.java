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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;

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
                stage.toFront();
                stage.requestFocus();
                clicksActAndReturnFocus();
            });
            trackingPopupHoldsLayersAndKeepsKeys();
            fx(WorldMinimapInputUiTest::viewportStillAcceptsFlightInput);
            System.out.println("World minimap input PASS: actual mouse/key/scroll on the cluster buttons,"
                    + " toggle and zoom clicks with focus returned, tracking popup layers and key"
                    + " isolation, viewport flight isolation");
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static final List<String> BUTTONS = List.of("#world-minimap-zoom-out",
            "#world-minimap-orientation", "#world-minimap-zoom-in", "#world-minimap-tracking");

    /** Keys, drags and scrolls over every cluster button never fly, look or change speed. */
    private static void controlEventsNeverSteerWorld() {
        var before = (TerrainRenderer.Camera) get("camera");
        double speed = input().speed();
        for (var id : BUTTONS) {
            var button = (ButtonBase) view.lookup(id);
            require(button != null && !button.isFocusTraversable(), "Cluster button, not traversable: " + id);
            key(button, KeyCode.W, true);
            key(button, KeyCode.RIGHT, true);
            tick();
            require(before.equals(get("camera")), "Keys on " + id + " must not move or turn the camera");
            key(button, KeyCode.W, false);
            key(button, KeyCode.RIGHT, false);
            mouse(button, MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY);
            mouse(button, MouseEvent.MOUSE_DRAGGED, 35, 30, MouseButton.SECONDARY);
            pulse();
            mouse(button, MouseEvent.MOUSE_RELEASED, 35, 30, MouseButton.SECONDARY);
            require(before.equals(get("camera")) && !input().looking(),
                    "Dragging " + id + " must not look around the world");
            Event.fireEvent(button, new ScrollEvent(ScrollEvent.SCROLL,
                    10, 10, 10, 10, false, false, false, false, false, false,
                    0, 40, 0, 40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(button, 10, 10)));
            require(speed == input().speed() && before.equals(get("camera")),
                    "Scrolling " + id + " must not dolly or change flight speed");
        }
        require(!input().keysHeld(), "Control input leaves no held flight keys");
    }

    /** Real primary clicks: the toggle flips orientation, zoom steps, focus goes back each time. */
    private static void clicksActAndReturnFocus() {
        var viewport = view.lookup("#world-viewport");
        var minimap = view.minimap();
        var orientation = (ButtonBase) view.lookup("#world-minimap-orientation");
        clickButton(orientation);
        require(minimap.headingUp() && "^".equals(orientation.getText()), "The toggle switches to heading up");
        require(viewport.isFocused(), "Focus returns to the viewport after the toggle");
        clickButton(orientation);
        require(!minimap.headingUp() && "N".equals(orientation.getText()), "And back to north up");
        clickButton((ButtonBase) view.lookup("#world-minimap-zoom-in"));
        require(minimap.zoom() == 1.5 && viewport.isFocused(), "Zoom in, focus back");
        clickButton((ButtonBase) view.lookup("#world-minimap-zoom-out"));
        require(minimap.zoom() == 1 && viewport.isFocused(), "Zoom out, focus back");
    }

    private static void clickButton(ButtonBase button) {
        view.lookup("#world-maps").requestFocus();
        mouse(button, MouseEvent.MOUSE_PRESSED, 10, 10);
        mouse(button, MouseEvent.MOUSE_RELEASED, 10, 10);
    }

    /**
     * The tracking popup holds the role controls and the entrances layer; keys typed in it never
     * reach the viewport (the popup redirects unconsumed keys to its owner); Escape closes it and
     * focus goes back to the viewport.
     */
    private static void trackingPopupHoldsLayersAndKeepsKeys() throws Exception {
        fx(() -> {
            view.attachRoles(new WorldRoleOverlay((worldMapId, token) -> {
                throw new IllegalStateException("authored: no role source");
            }, () -> "authored"));
            stage.toFront();
            stage.requestFocus();
        });
        // Native window focus settles on a later turn; open the popup once the stage has it, so
        // a late focus change cannot auto-hide it.
        await(stage::isFocused);
        // As in use: the viewport owns focus and W is held when the popup opens.
        fx(() -> {
            var viewport = view.lookup("#world-viewport");
            viewport.requestFocus();
            key(viewport, KeyCode.W, true);
            require(input().keysHeld(), "W is held before the popup opens");
            var tracking = (ButtonBase) view.lookup("#world-minimap-tracking");
            mouse(tracking, MouseEvent.MOUSE_PRESSED, 10, 10);
            mouse(tracking, MouseEvent.MOUSE_RELEASED, 10, 10);
        });
        await(() -> view.minimap().tracking().showing());
        fx(() -> {
            var viewport = view.lookup("#world-viewport");
            var content = view.minimap().tracking().popup().getContent().getFirst();
            require(content.lookup("#world-role-overlays") != null && content.lookup("#world-role-faction") != null
                            && content.lookup("#world-minimap-entrances") != null,
                    "The popup holds the role controls, the faction filter and the entrances layer");
            require(view.lookup("#world-role-overlays") == null, "The role row left the bottom block");
            require(!input().keysHeld(), "Opening the popup drops the held W");
            // Keys arrive at the owner's focus owner and reach the popup through the owner
            // window's redirector, exactly as typed keys do.
            var before = (TerrainRenderer.Camera) get("camera");
            key(viewport, KeyCode.W, false);
            for (var code : List.of(KeyCode.W, KeyCode.A, KeyCode.SPACE, KeyCode.F)) key(viewport, code, true);
            tick();
            require(!input().keysHeld() && before.equals(get("camera")),
                    "Keys typed while the popup is open never become held flight keys");
            for (var code : List.of(KeyCode.W, KeyCode.A, KeyCode.SPACE, KeyCode.F)) key(viewport, code, false);
            boolean panels = (boolean) get("panelsHidden");
            altZ(viewport);
            require((boolean) get("panelsHidden") != panels, "Alt+Z still works while the popup is open");
            altZ(viewport);
            require((boolean) get("panelsHidden") == panels, "And toggles back");
            var entrances = (javafx.scene.control.CheckBox) content.lookup("#world-minimap-entrances");
            require(!entrances.isFocusTraversable() && !entrances.isSelected(), "Entrances layer starts off");
            entrances.fire();
            require(entrances.isSelected(), "The entrances layer toggles in the popup");
            entrances.fire();
            key(viewport, KeyCode.ESCAPE, true);
        });
        await(() -> !view.minimap().tracking().showing());
        fx(() -> {
            key(view.lookup("#world-viewport"), KeyCode.ESCAPE, false);
            require(!input().keysHeld(), "Escape in the popup never becomes a held key");
            require(view.lookup("#world-viewport").isFocused(), "Closing the popup gives focus back");
            input().clear();
        });
    }

    private static void altZ(Node node) {
        Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z, false, false, true, false));
        Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.Z, false, false, true, false));
    }

    private static void viewportStillAcceptsFlightInput() {
        var viewport = view.lookup("#world-viewport");
        var before = (TerrainRenderer.Camera) get("camera");
        var minimap = view.lookup("#world-minimap");
        mouse(minimap, MouseEvent.MOUSE_PRESSED, 10, 10, MouseButton.SECONDARY);
        mouse(minimap, MouseEvent.MOUSE_DRAGGED, 35, 30, MouseButton.SECONDARY);
        pulse();
        mouse(minimap, MouseEvent.MOUSE_RELEASED, 35, 30, MouseButton.SECONDARY);
        require(!before.equals(get("camera")), "Right-dragging non-control minimap artwork still looks around");
        require(viewport.isFocused(), "Clicking non-control artwork restores viewport focus");
        var moved = (TerrainRenderer.Camera) get("camera");
        key(viewport, KeyCode.W, true);
        tick();
        key(viewport, KeyCode.W, false);
        require(!moved.equals(get("camera")), "Viewport keyboard still moves");
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
