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
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/**
 * HUD keys over the real World view (step 7): V toggles NPC spawn markers, T cycles day/night
 * (clock, day, night), 1 to 5 select the speed presets and persist them; none moves the camera or
 * stays held. Authored data; requires a display.
 */
public final class WorldHudKeysUiTest {
    private WorldHudKeysUiTest() {}

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
                view.attachSpawns(new WorldSpawnOverlay(token -> {
                    throw new IllegalStateException("authored: no spawn source");
                }, () -> 1L));
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
            fx(WorldHudKeysUiTest::keysAct);
            await(() -> view.lookup("#world-minimap").isVisible());
            fx(WorldHudKeysUiTest::minimapKey);
            fx(() -> {
                stage.toFront();
                stage.requestFocus();
            });
            await(stage::isFocused);
            fx(() -> {
                view.lookup("#world-viewport").requestFocus();
                press(KeyCode.L);
            });
            await(() -> view.minimap().tracking().showing());
            fx(() -> {
                // Keys reach the open popup through the owner window; bound HUD keys pass on.
                var overlay = view.devOverlay();
                var page = overlay.page();
                press(KeyCode.F3);
                require(overlay.page() != page, "F3 still works while the tracking popup is open");
                press(KeyCode.W);
                require(!((WorldExplorerInput) get("input")).keysHeld(), "Movement keys stay in the popup");
                press(KeyCode.L);
            });
            await(() -> !view.minimap().tracking().showing());
            System.out.println("World HUD keys PASS: V spawns toggle, T day/night cycle, 1-5 speed presets"
                    + " persisted, N minimap hide/show, L tracking popup open/close, HUD keys through the"
                    + " popup, no held keys or motion");
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static void keysAct() {
        ((AnimationTimer) get("timer")).stop();
        var viewport = view.lookup("#world-viewport");
        viewport.requestFocus();
        var input = (WorldExplorerInput) get("input");
        var before = (TerrainRenderer.Camera) get("camera");
        var spawns = (WorldSpawnOverlay) get("spawns");
        boolean shown = spawns.toggle().isSelected();
        press(KeyCode.V);
        require(spawns.toggle().isSelected() != shown, "V toggles the NPC spawn markers");
        press(KeyCode.V);
        require(spawns.toggle().isSelected() == shown, "V again toggles them back");
        var audio = (WorldAudioControls) get("audio");
        require(audio.slot() == WorldAudioDirector.Slot.CLOCK, "Day/night starts by clock");
        press(KeyCode.T);
        require(audio.slot() == WorldAudioDirector.Slot.DAY, "T: day");
        press(KeyCode.T);
        require(audio.slot() == WorldAudioDirector.Slot.NIGHT, "T: night");
        press(KeyCode.T);
        require(audio.slot() == WorldAudioDirector.Slot.CLOCK, "T: back to the clock");
        var speed = (javafx.scene.control.ChoiceBox<?>) view.lookup("#world-flight-speed");
        var codes = List.of(KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5);
        for (int i = 0; i < codes.size(); i++) {
            press(codes.get(i));
            var preset = WorldControls.Speed.values()[i];
            require(speed.getValue() == preset && input.controls().speed() == preset,
                    "Digit " + (i + 1) + " selects " + preset + ": " + speed.getValue());
        }
        var store = (WorldControls.Store) get("controlsStore");
        require(WorldControls.decode(store.load().join()).speed() == WorldControls.Speed.TURBO,
                "The chosen preset is saved with the controls");
        press(KeyCode.DIGIT3);
        require(!input.keysHeld() && before.equals(get("camera")), "HUD keys never move or stay held");
    }

    private static void minimapKey() {
        var minimap = view.minimap();
        require(minimap.isVisible() && !minimap.userHidden(), "The minimap starts shown");
        press(KeyCode.N);
        require(!minimap.isVisible() && minimap.userHidden(), "N hides the minimap");
        var camera = (TerrainRenderer.Camera) get("camera");
        minimap.update((BuildWorld) get("handle"), (WorldService.Inventory) get("inventory"),
                (WorldFormats.Tile) get("origin"), (double) get("originX"), (double) get("originY"),
                camera.x(), camera.y(), camera.yaw());
        require(!minimap.isVisible(), "It stays hidden across updates");
        press(KeyCode.L);
        require(!minimap.tracking().showing(), "L does nothing while the minimap is hidden");
        press(KeyCode.N);
        require(minimap.userHidden() == false, "N shows it again");
    }

    private static void press(KeyCode code) {
        var viewport = view.lookup("#world-viewport");
        Event.fireEvent(viewport, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        Event.fireEvent(viewport, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
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
