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
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * World toasts: 4 s life with a fade, three at most (oldest leaves first), repeats restart
 * instead of stacking, action buttons run, dismiss and give focus back without steering the
 * world, and the backing lets clicks through; then over the real World view: the first-run key
 * help and the cursor-capture fallback toast. Authored data and a fake clock; requires a display.
 */
public final class WorldToastsUiTest {
    private WorldToastsUiTest() {}

    private static final long S = 1_000_000_000L;
    private static long now;
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
            fx(WorldToastsUiTest::timingStackAndActions);
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
            await(() -> get("camera") != null && !(boolean) get("scenePreparing"));
            fx(WorldToastsUiTest::viewShowsHelpAndCaptureToasts);
            fx(WorldToastsUiTest::toastsFollowFullScreenAndSurviveTheInteriorSwap);
            System.out.println("World toasts PASS: 4 s life and fade, stack of 3 oldest-first, repeat"
                    + " restart, action run/dismiss/refocus, event isolation, click-through backing,"
                    + " first-run help and capture-fallback toasts in the World view");
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static void timingStackAndActions() {
        now = 100 * S;
        var toasts = new WorldToasts(() -> now);
        var world = new StackPane(toasts);
        world.setPrefSize(800, 400);
        var refocused = new int[1];
        toasts.refocus(() -> refocused[0]++);
        var window = new Stage();
        window.setScene(new Scene(world));
        window.show();
        try {
            require(!toasts.isVisible() && toasts.toasts().isEmpty(), "No toasts at first");
            toasts.show("one");
            now += S;
            toasts.show("two");
            now += S;
            toasts.show("three");
            require(texts(toasts).equals(List.of("one", "two", "three")) && toasts.isVisible(), "Three stack");
            now += S / 2;
            toasts.show("two");
            require(texts(toasts).equals(List.of("one", "three", "two")),
                    "A repeat restarts and moves to the newest place, no copy: " + texts(toasts));
            now += S / 2;
            toasts.show("four");
            require(texts(toasts).equals(List.of("three", "two", "four")),
                    "A fourth pushes the oldest out: " + texts(toasts));
            // "three" was shown at 102 s: it fades over its last 300 ms and leaves at 106 s.
            now = 106 * S - WorldToasts.FADE_NANOS / 2;
            toasts.pulse();
            require(Math.abs(toasts.opacities().getFirst() - .5) < 1e-9 && toasts.opacities().get(1) == 1,
                    "The oldest fades in its last 300 ms: " + toasts.opacities());
            now = 106 * S;
            toasts.pulse();
            require(texts(toasts).equals(List.of("two", "four")), "Gone after 4 s: " + texts(toasts));
            now = 200 * S;
            toasts.pulse();
            require(toasts.toasts().isEmpty() && !toasts.isVisible(), "All expire");

            var runs = new ArrayList<String>();
            toasts.show("Texture failed", "Retry", () -> runs.add("retry"));
            toasts.show("Plain");
            world.applyCss();
            world.layout();
            var button = (Button) toasts.lookup(".world-toast-action");
            require(button != null && !button.isFocusTraversable() && "Retry".equals(button.getText()),
                    "The action is a non-traversable button");
            boolean[] leaked = {false};
            world.addEventHandler(Event.ANY, event -> {
                if (event instanceof KeyEvent || event instanceof MouseEvent || event instanceof ScrollEvent)
                    leaked[0] = true;
            });
            Event.fireEvent(button, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, false, false, false));
            Event.fireEvent(button, new ScrollEvent(ScrollEvent.SCROLL, 5, 5, 5, 5, false, false, false, false,
                    false, false, 0, 40, 0, 40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, new PickResult(button, 5, 5)));
            mouse(button, MouseEvent.MOUSE_PRESSED);
            mouse(button, MouseEvent.MOUSE_RELEASED);
            require(!leaked[0], "Button events never reach the world behind");
            require(runs.equals(List.of("retry")) && refocused[0] == 1 && texts(toasts).equals(List.of("Plain")),
                    "A click runs the action, dismisses its toast and returns focus: " + runs + " " + texts(toasts));
            var plain = toasts.lookup(".world-toast");
            var bounds = plain.localToScene(plain.getLayoutBounds());
            var picked = pick(world, bounds.getMinX() + 8, bounds.getMinY() + 8);
            require(picked == world, "A click on a toast's backing reaches the world: " + picked);
            boolean threw = false;
            try {
                toasts.show("bad", "Retry", null);
            } catch (IllegalArgumentException expected) {
                threw = true;
            }
            require(threw, "An action needs its callback");
            toasts.show(" ");
            require(texts(toasts).equals(List.of("Plain")), "Blank texts are ignored");
            toasts.show("Plain  ");
            require(texts(toasts).equals(List.of("Plain")), "Repeats match after stripping");
            toasts.show("Again", "Retry", () -> toasts.show("Again", "Retry", () -> {}));
            world.applyCss();
            world.layout();
            ((Button) toasts.lookup(".world-toast-action")).fire();
            require(texts(toasts).equals(List.of("Plain", "Again")),
                    "An action that raises the same notice keeps the new one: " + texts(toasts));
            toasts.clear();
            toasts.show("Long", null, null, 10 * S);
            now += 5 * S;
            toasts.pulse();
            require(texts(toasts).equals(List.of("Long")), "A longer life outlasts 4 s");
            now += 5 * S;
            toasts.pulse();
            require(toasts.toasts().isEmpty(), "And still ends");
            toasts.clear();
            require(toasts.toasts().isEmpty() && !toasts.isVisible(), "Clear removes every toast");
        } finally {
            window.close();
        }
    }

    private static void viewShowsHelpAndCaptureToasts() {
        var toasts = view.toasts();
        var texts = texts(toasts);
        require(texts.size() == 1 && texts.getFirst().startsWith("W A S D move")
                        && texts.getFirst().contains("to inspect it"),
                "Opening the explorer shows the key help and inspect hint once: " + texts);
        ((AnimationTimer) get("timer")).stop();
        // The authored terrain lies outside the bookmark grid, so the capture fallback stands in.
        try {
            var host = WorldExplorerInput.class.getDeclaredField("host");
            host.setAccessible(true);
            ((WorldExplorerInput.Host) host.get(get("input"))).captureUnavailable();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        texts = texts(toasts);
        require(texts.size() == 2 && texts.getLast().startsWith("Mouse look: cursor capture unavailable"),
                "The cursor-capture fallback shows a toast: " + texts);
        require(toasts.getParent() == view.lookup("#world-toast-layer"),
                "Toasts sit on the layer above the explorer content");
        view.applyCss();
        view.layout();
        var viewport = view.lookup("#world-viewport");
        var port = viewport.localToScene(viewport.getLayoutBounds());
        var stack = toasts.localToScene(toasts.getLayoutBounds());
        require(stack.getMaxY() <= port.getMaxY() + 1 && stack.getMinY() >= port.getMinY()
                        && Math.abs(stack.getCenterX() - port.getCenterX()) < 2,
                "The stack sits bottom-centre over the viewport, above the control rows: " + stack + " in " + port);
        // Stream failures toast once per failure episode, not once per snapshot.
        toasts.clear();
        streamStatus(WorldStreamSourceController.Phase.FAILED, "World streaming failed: authored");
        streamStatus(WorldStreamSourceController.Phase.FAILED, "World streaming failed: authored");
        require(texts(toasts).equals(List.of("World streaming failed: authored")),
                "One toast per failure, without a doubled prefix: " + texts(toasts));
        streamStatus(WorldStreamSourceController.Phase.STARTING, "Starting");
        streamStatus(WorldStreamSourceController.Phase.FAILED, "World viewer failed: again");
        require(texts(toasts).equals(List.of("World streaming failed: authored", "World viewer failed: again")),
                "A new failure episode toasts again: " + texts(toasts));
        require(toasts.toasts().stream().allMatch(t -> "Retry".equals(t.action())), "Failures offer Retry");
        toasts.clear();
    }

    /**
     * In full screen the toasts move onto the viewport (in its own stage) and back after; an
     * instance interior replacing the viewport keeps them, so its "Entered" toast is seen.
     */
    private static void toastsFollowFullScreenAndSurviveTheInteriorSwap() {
        var toasts = view.toasts();
        var viewport = view.lookup("#world-viewport");
        try {
            var fullscreen = (WorldFullscreen) get("fullscreen");
            fullscreen.windowed(true);
            var toggle = WorldView.class.getDeclaredMethod("toggleFullscreen");
            toggle.setAccessible(true);
            toggle.invoke(view);
            require(fullscreen.active() && toasts.getParent() == viewport,
                    "Full screen carries the toasts on the viewport");
            toggle.invoke(view);
            require(!fullscreen.active() && toasts.getParent() == view.lookup("#world-toast-layer"),
                    "Leaving full screen puts them back on the content layer");

            Class<?> type = Class.forName("com.vimi.gui.WorldView$InstanceTarget");
            var make = type.getDeclaredConstructors()[0];
            make.setAccessible(true);
            var raid = new WorldService.MapInfo(9, "Authored raid", null, 2, null);
            Object target = make.newInstance(raid, null, null, -1L, "authored");
            var hooksField = WorldInstanceSwitch.class.getDeclaredField("hooks");
            hooksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var hooks = (WorldInstanceSwitch.Hooks<Object>) hooksField.get(get("instances"));
            var interiorNode = new javafx.scene.layout.Pane();
            hooks.showInterior(target, new WorldInstanceSwitch.Interior() {
                @Override public javafx.scene.Node node() { return interiorNode; }
                @Override public CompletableFuture<Void> closeAsync() { return CompletableFuture.completedFuture(null); }
            });
            var content = (javafx.scene.layout.BorderPane) get("content");
            require(content.getCenter() == interiorNode, "The interior replaces the viewport");
            require(toasts.getScene() != null && toasts.isVisible()
                            && texts(toasts).contains("Entered Authored raid"),
                    "The global-interior entry toast is showing: " + texts(toasts));
            content.setCenter(viewport);
            toasts.clear();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void streamStatus(WorldStreamSourceController.Phase phase, String detail) {
        try {
            var method = WorldView.class.getDeclaredMethod("streamStatus", WorldStreamSourceController.Snapshot.class);
            method.setAccessible(true);
            method.invoke(view, new WorldStreamSourceController.Snapshot(phase, detail, 0, 0, null, null));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static List<String> texts(WorldToasts toasts) {
        return toasts.toasts().stream().map(WorldToasts.Toast::text).toList();
    }

    private static void mouse(Node node, javafx.event.EventType<MouseEvent> type) {
        boolean down = type == MouseEvent.MOUSE_PRESSED;
        Event.fireEvent(node, new MouseEvent(type, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                false, false, false, false, down, false, false, false, false, true,
                new PickResult(node, 5, 5)));
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
        return local != null && node.contains(local) ? node : null;
    }

    private static Object get(String name) {
        try {
            Field field = WorldView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(view);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var ready = new CompletableFuture<Boolean>();
            Platform.runLater(() -> {
                try { ready.complete(condition.getAsBoolean()); }
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
