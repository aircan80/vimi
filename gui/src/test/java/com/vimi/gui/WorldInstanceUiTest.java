package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldInstanceLinks;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.TerrainRenderer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Authored maps only: recorded instance entrances listed nearest first, an ADT-based instance
 * entered and left back to the saved open-world camera, and the global-interior refusals without a
 * GPU (from the open world and from the map page). Requires a desktop display.
 */
public final class WorldInstanceUiTest {
    private static final double SIZE = WorldFormats.TILE_SIZE;
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();
    private static final WorldService.MapInfo HOME =
            new WorldService.MapInfo(0, "Authored continent", "Authored", 0, 1L);
    private static final WorldService.MapInfo DUNGEON =
            new WorldService.MapInfo(36, "Authored dungeon", "AuthoredDungeon", 1, 2L);
    private static final WorldService.MapInfo RAID =
            new WorldService.MapInfo(249, "Authored raid", "AuthoredRaid", 2, 3L);
    private static final double DUNGEON_X = (32 - 30.5) * SIZE, DUNGEON_Y = (32 - 40.5) * SIZE;

    private WorldInstanceUiTest() {}

    private static final class Fixture implements BuildWorld {
        final List<WorldFormats.Tile> tiles = new ArrayList<>();

        Fixture() {
            for (int x = 29; x <= 31; x++)
                for (int y = 39; y <= 41; y++)
                    tiles.add(new WorldFormats.Tile(x, y, 1000 + x * 64 + y, 0, 0));
        }

        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Catalog(
                    List.of(HOME, DUNGEON, RAID), List.of(), List.of()));
        }

        public CompletableFuture<WorldService.Inventory> inventory(
                WorldService.MapInfo map, Cancellation token) {
            var world = map.id() == HOME.id() ? new WorldFormats.World(tiles, false)
                    : map.id() == DUNGEON.id()
                            ? new WorldFormats.World(List.of(new WorldFormats.Tile(32, 32, 9, 0, 0)), false)
                            : new WorldFormats.World(List.of(), true);
            return CompletableFuture.completedFuture(new WorldService.Inventory(map, world, null));
        }

        public CompletableFuture<WorldService.LoadedTile> tile(
                WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
            var parsed = WorldFormats.adtTile(WorldTravelUiTest.root(tile.x(), tile.y()), () -> false);
            return CompletableFuture.completedFuture(
                    new WorldService.LoadedTile(tile, parsed.terrain(), null, parsed.areas()));
        }

        public CompletableFuture<WorldService.Minimap> minimap(
                WorldFormats.Tile tile, Cancellation token) {
            return CompletableFuture.completedFuture(
                    WorldService.Minimap.unavailable("Authored missing artwork"));
        }

        @Override
        public CompletableFuture<WorldInstanceLinks> instanceLinks(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldInstanceLinks(List.of(
                    WorldInstanceLinks.qualify(36, "Authored dungeon", 1L, 0L, 0L, DUNGEON_X, DUNGEON_Y),
                    WorldInstanceLinks.qualify(249, "Authored raid", 2L, 0L, 0L,
                            (32 - 29.5) * SIZE, (32 - 39.5) * SIZE),
                    WorldInstanceLinks.qualify(389, "Unlinked", 1L, 0L, -1L, 0.0, 0.0)), List.of()));
        }

        public CompletableFuture<Void> closeAsync() {
            throw new AssertionError("View borrows fixture");
        }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            var fixture = new Fixture();
            var view = new WorldView(() -> fixture, () -> 1);
            var stage = new Stage();
            stage.setScene(new Scene(view, 1300, 900));
            stage.show();
            DONE.whenComplete((value, error) -> Platform.runLater(() -> {
                view.close();
                stage.close();
            }));
            view.ensureLoaded();
            var maps = (ListView<?>) view.lookup("#world-maps");
            await(() -> maps.getItems().size() >= 1 && get(view, "instanceLinks") != null, () -> {
                select(view, HOME);
                await(() -> get(view, "inventory") != null, () -> {
                    set(view, "selected", new WorldFormats.Tile(30, 40, 1000 + 30 * 64 + 40, 0, 0));
                    invoke(view, "openExplorer");
                    await(() -> get(view, "camera") != null && !(boolean) get(view, "scenePreparing"),
                            () -> entrances(view));
                });
            });
        });
        try {
            DONE.get(60, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
        System.out.println("World instances: entrance list, ADT instance round trip and GPU refusals"
                + " passed");
    }

    @SuppressWarnings("unchecked")
    private static void entrances(WorldView view) {
        var combo = (ComboBox<WorldInstanceLinks.Link>) view.lookup("#world-instance-entrances");
        var status = (Label) view.lookup("#world-instance-status");
        var enter = (Button) view.lookup("#world-instance-enter");
        var leave = (Button) view.lookup("#world-instance-leave");
        set(view, "camera", new TerrainRenderer.Camera(DUNGEON_X + 10, DUNGEON_Y, 80, 0, -.3));
        invoke(view, "refreshEntrances");
        require(combo.getItems().stream().map(WorldInstanceLinks.Link::mapId).toList()
                        .equals(List.of(36L, 249L)),
                "Recorded entrances on this map, nearest first, without the unlinked one: "
                        + combo.getItems());
        require(status.getText().contains("2 recorded entrances"), status.getText());
        require(!leave.isVisible(), "No leave in the open world");
        require(enter.isDisabled(), "Nothing chosen yet");
        combo.setValue(combo.getItems().get(1));
        enter.fire();
        await(() -> status.getText().contains("GPU"), () -> {
            require(!(boolean) get(view, "instances", WorldInstanceSwitch.class, "engaged"),
                    "A global interior without a GPU is refused, not entered");
            var saved = (TerrainRenderer.Camera) get(view, "camera");
            combo.setValue(combo.getItems().get(0));
            enter.fire();
            await(() -> inventoryMap(view) == DUNGEON.id() && (boolean) get(view, "explorer")
                    && get(view, "camera") != null && !(boolean) get(view, "scenePreparing"), () -> {
                require(leave.isVisible(), "Leave is offered inside the instance");
                var toasts = view.toasts();
                require(toasts.getScene() != null && toasts.isVisible() && toasts.toasts().stream()
                                .anyMatch(t -> t.text().startsWith("Entering ")),
                        "Entering an instance shows a visible toast: " + toasts.toasts());
                leave.fire();
                await(() -> inventoryMap(view) == HOME.id() && (boolean) get(view, "explorer")
                        && get(view, "camera") != null && !(boolean) get(view, "scenePreparing"), () -> {
                    var camera = (TerrainRenderer.Camera) get(view, "camera");
                    require(camera.x() == saved.x() && camera.y() == saved.y(),
                            "Leave returns to the open-world position of entry: " + camera);
                    require(!leave.isVisible(), "No leave after returning");
                    var back = view.toasts();
                    require(back.getScene() != null && back.isVisible() && back.toasts().stream()
                                    .anyMatch(t -> t.text().startsWith("Left the instance")),
                            "Leaving shows a visible toast: " + back.toasts());
                    enteringBar(view);
                });
            });
        });
    }

    /**
     * Holds an entry while the open world drains (an incomplete retirement): only Cancel is live,
     * explorer controls cannot restart the world or change its area, and hiding the view cancels
     * the entry without opening anything.
     */
    private static void enteringBar(WorldView view) {
        var leave = (Button) view.lookup("#world-instance-leave");
        var content = (javafx.scene.layout.BorderPane) get(view, "content");
        var viewport = get(view, "viewport");
        var pending = new CompletableFuture<Void>();
        set(view, "streamingRetirement", pending);
        enterHeld(view);
        require(phase(view) == WorldInstanceSwitch.Phase.ENTERING, "Entering");
        require(leave.isVisible() && leave.getText().equals("Cancel"), "Cancel is offered");
        require(view.lookup("#world-gpu-enabled") == null && view.lookup("#world-instance-enter") == null,
                "Explorer controls are not reachable while entering");
        var full = (WorldFullscreen) get(view, "fullscreen");
        full.windowed(true);
        javafx.event.Event.fireEvent(leave, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                "", "", javafx.scene.input.KeyCode.F11, false, false, false, false));
        javafx.event.Event.fireEvent(leave, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_RELEASED,
                "", "", javafx.scene.input.KeyCode.F11, false, false, false, false));
        javafx.event.Event.fireEvent(leave, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                "", "", javafx.scene.input.KeyCode.Z, false, false, true, false));
        javafx.event.Event.fireEvent(leave, new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_RELEASED,
                "", "", javafx.scene.input.KeyCode.Z, false, false, false, false));
        require(!full.active() && leave.isVisible() && content.getBottom().isVisible(),
                "F11 and Alt+Z are refused while the open world drains: Cancel stays reachable");
        var area = (Label) get(view, "currentArea");
        area.setText("marker");
        invoke(view, "switchStreamingRendering");
        invoke(view, "openExplorer");
        invoke(view, "updateCurrentArea");
        require(area.getText().equals("marker"), "The open world's area is not re-resolved");
        require(content.getCenter() == viewport && (boolean) get(view, "explorer"),
                "Nothing restarted under the entry");
        leave.fire();
        require(phase(view) == WorldInstanceSwitch.Phase.OPEN_WORLD, "Cancel returns");
        require(view.lookup("#world-gpu-enabled") != null, "Explorer controls are back");
        require(leave.getText().equals("Leave instance") && !leave.isVisible(), "Cancel hidden again");

        // Leaving the tab while the open world drains cancels the entry; nothing opens later.
        enterHeld(view);
        view.deactivate();
        require(phase(view) == WorldInstanceSwitch.Phase.OPEN_WORLD, "Hidden view cancels the entry");
        invoke(view, "showExplorerLayout");
        require(leave.getText().equals("Leave instance"), "An abandoned entry's Cancel label resets");
        pending.complete(null);
        await(() -> true, () -> {
            require(content.getCenter() == viewport, "No interior opened in the hidden view");
            view.activate();
            mapPage(view);
        });
    }

    private static void enterHeld(WorldView view) {
        try {
            Class<?> type = Class.forName("com.vimi.gui.WorldView$InstanceTarget");
            var make = type.getDeclaredConstructors()[0];
            make.setAccessible(true);
            Object target = make.newInstance(RAID, null, null, -1L, "authored");
            var instances = get(view, "instances");
            var enter = WorldInstanceSwitch.class.getDeclaredMethod("enter", Object.class, boolean.class);
            enter.setAccessible(true);
            require((boolean) enter.invoke(instances, target, true), "Entry starts");
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static WorldInstanceSwitch.Phase phase(WorldView view) {
        return (WorldInstanceSwitch.Phase) get(view, "instances", WorldInstanceSwitch.class, "phase");
    }

    private static void mapPage(WorldView view) {
        ((Button) view.lookup("#world-back")).fire();
        select(view, RAID);
        await(() -> inventoryMap(view) == RAID.id(), () -> {
            var open = (Button) view.lookup("#world-open");
            require(!open.isDisabled(), "A global world-model map can be entered from the map page");
            var message = (Label) view.lookup("#world-inventory-message");
            require(message.getText().contains("enter it in this view"), message.getText());
            var page = (Label) get(view, "status");
            require(page.getText().contains("Entrance: Authored continent at"),
                    "The recorded entrance is stated: " + page.getText());
            open.fire();
            var status = (Label) get(view, "status");
            require(status.getText().contains("GPU"), "Refused without a GPU: " + status.getText());
            require(!(boolean) get(view, "explorer"), "Still on the map page");
            DONE.complete(null);
        });
    }

    private static long inventoryMap(WorldView view) {
        var inventory = (WorldService.Inventory) get(view, "inventory");
        return inventory == null ? -1 : inventory.map().id();
    }

    private static void select(WorldView view, WorldService.MapInfo map) {
        @SuppressWarnings("unchecked")
        var maps = (ListView<WorldService.MapInfo>) view.lookup("#world-maps");
        if (!maps.getItems().contains(map)) {
            ((javafx.scene.control.CheckBox) get(view, "allMaps")).fire();
        }
        maps.getSelectionModel().select(map);
    }

    private static void await(BooleanSupplier ready, Runnable action) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(10), event -> {
            try {
                if (ready.getAsBoolean()) {
                    timer.stop();
                    action.run();
                } else if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out waiting for instance state");
                }
            } catch (Throwable error) {
                timer.stop();
                DONE.completeExceptionally(error);
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private static Object get(Object target, String name) {
        try {
            Field field = WorldView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Object get(Object target, String name, Class<?> type, String method) {
        try {
            var call = type.getDeclaredMethod(method);
            call.setAccessible(true);
            return call.invoke(get(target, name));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = WorldView.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void invoke(Object target, String name) {
        try {
            var method = WorldView.class.getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
