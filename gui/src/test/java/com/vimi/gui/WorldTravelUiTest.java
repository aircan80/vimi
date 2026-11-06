package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldBookmarks;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;
import com.vibeminer.rendering.world.ExplorerMotion;
import com.vibeminer.rendering.world.TerrainGround;
import com.vibeminer.rendering.world.TerrainRenderer;
import com.vibeminer.rendering.world.TerrainScene;
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
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Authored maps/tiles only: validated coordinate travel, bookmark save, cross-map jump, refusals. */
public final class WorldTravelUiTest {
    private static final double SIZE = WorldFormats.TILE_SIZE;
    private static final CompletableFuture<Void> DONE = new CompletableFuture<>();
    private static final WorldService.MapInfo HOME = new WorldService.MapInfo(0, "Authored continent", "Authored", 0, 1L);
    private static final WorldService.MapInfo OTHER = new WorldService.MapInfo(1, "Second continent", "Second", 0, 2L);

    private WorldTravelUiTest() {}

    /** Absolute filename/native convention: a tile's maxima are (32 - index) * TILE_SIZE. */
    static byte[] root(int tileX, int tileY) {
        float maxX = (float) ((32 - tileX) * SIZE), maxY = (float) ((32 - tileY) * SIZE);
        int chunkBytes = 8 + 128 + 8 + 580;
        var bytes = java.nio.ByteBuffer.allocate(12 + 256 * chunkBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x4d564552).putInt(4).putInt(18);
        for (int i = 0; i < 256; i++) {
            bytes.putInt(0x4d434e4b).putInt(chunkBytes - 8);
            int start = bytes.position(), x = i % 16, y = i / 16;
            bytes.putInt(start + 4, x).putInt(start + 8, y);
            bytes.putFloat(start + 104, (float) (maxX - y * SIZE / 16));
            bytes.putFloat(start + 108, (float) (maxY - x * SIZE / 16));
            bytes.putFloat(start + 112, 40);
            bytes.position(start + 128);
            bytes.putInt(0x4d435654).putInt(580);
            for (int sample = 0; sample < 145; sample++) bytes.putFloat(sample % 9);
        }
        return bytes.array();
    }

    private static final class Fixture implements BuildWorld {
        final List<WorldFormats.Tile> tiles = new ArrayList<>();
        Fixture() {
            for (int x = 29; x <= 31; x++) for (int y = 39; y <= 41; y++)
                tiles.add(new WorldFormats.Tile(x, y, 1000 + x * 64 + y, 0, 0));
        }
        public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
            return CompletableFuture.completedFuture(new WorldService.Catalog(List.of(HOME, OTHER), List.of(), List.of()));
        }
        public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo map, Cancellation token) {
            var recorded = map.id() == HOME.id() ? tiles : List.of(new WorldFormats.Tile(32, 32, 9, 0, 0));
            return CompletableFuture.completedFuture(new WorldService.Inventory(map, new WorldFormats.World(recorded, false), null));
        }
        public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
            var parsed = WorldFormats.adtTile(root(tile.x(), tile.y()), () -> false);
            return CompletableFuture.completedFuture(new WorldService.LoadedTile(tile, parsed.terrain(), null, parsed.areas()));
        }
        public CompletableFuture<WorldService.Minimap> minimap(WorldFormats.Tile tile, Cancellation token) {
            return CompletableFuture.completedFuture(WorldService.Minimap.unavailable("Authored missing artwork"));
        }
        public CompletableFuture<Void> closeAsync() { throw new AssertionError("View borrows fixture"); }
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            var fixture = new Fixture();
            var view = new WorldView(() -> fixture, () -> 1);
            var stage = new Stage();
            stage.setScene(new Scene(view, 1100, 800));
            stage.show();
            DONE.whenComplete((value, error) -> Platform.runLater(() -> { view.close(); stage.close(); }));
            view.ensureLoaded();
            var maps = (ListView<?>) view.lookup("#world-maps");
            await(() -> maps.getItems().size() == 2, () -> {
                select(view, HOME);
                await(() -> get(view, "inventory") != null, () -> coordinates(view));
            });
        });
        try { DONE.get(40, TimeUnit.SECONDS); }
        finally { Platform.exit(); }
        System.out.println("World travel: validated coordinates, refusals, saved bookmark and cross-map jump passed");
    }

    private static void coordinates(WorldView view) {
        var field = (TextField) view.lookup("#world-travel-coordinates");
        var go = (Button) view.lookup("#world-travel-go");
        var status = (Label) view.lookup("#world-travel-status");
        field.setText("not coordinates");
        go.fire();
        require(status.getText().startsWith("Enter native coordinates"), "Malformed input is explained");
        field.setText("0 0");
        go.fire();
        require(status.getText().contains("no tile at that position") && !(boolean) get(view, "explorer"),
                "Unrecorded tile is refused without opening: " + status.getText());
        double x = (32 - 30.5) * SIZE, y = (32 - 40.25) * SIZE;
        field.setText(x + ", " + y);
        go.fire();
        await(() -> get(view, "camera") != null && !(boolean) get(view, "scenePreparing"), () -> {
            var camera = (TerrainRenderer.Camera) get(view, "camera");
            var origin = (WorldFormats.Tile) get(view, "origin");
            require(origin.x() == 30 && origin.y() == 40, "Coordinates open their recorded tile");
            var surface = TerrainGround.of(List.of(TerrainGround.Tile.of(
                    new TerrainScene.Tile(30, 40, WorldFormats.adt(root(30, 40), () -> false)))));
            double height = surface.heightAt(x, y);
            require(camera.x() == x && camera.y() == y && Math.abs(camera.z() - (height + 30)) < 1e-6,
                    "Arrives at the typed position above the rendered surface: " + camera);
            require(status.getText().startsWith("Arrived at"), "Arrival is reported: " + status.getText());
            inspectTerrain(view, x, y, height, () -> {
                var low = new TerrainRenderer.Camera(x - 3, y - 4, height - 50, 1.25, -.3);
                set(view, "camera", low);
                ((Button) view.lookup("#world-save-bookmark")).fire();
                var list = bookmarks(view);
                await(() -> list.getItems().size() == 1, () -> crossMap(view, low, height));
            });
        });
    }

    /** A still primary click on the frame center casts a CPU ray onto the rendered surface. */
    private static void inspectTerrain(WorldView view, double x, double y, double height, Runnable next) {
        var image = (javafx.scene.image.ImageView) get(view, "image");
        var viewport = (javafx.scene.layout.StackPane) get(view, "viewport");
        var inspection = (Label) view.lookup("#world-inspection");
        require(inspection != null && inspection.getText().startsWith("Click terrain"), "Inspection hint is shown");
        await(() -> image.getImage() != null, () -> {
            set(view, "camera", new TerrainRenderer.Camera(x, y, height + 30, 0, -1.2));
            var center = viewport.localToScene(viewport.getWidth() / 2, viewport.getHeight() / 2);
            var drag = new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    center.getX(), center.getY(), 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, false, null);
            javafx.event.Event.fireEvent(viewport, drag);
            require(inspection.getText().startsWith("Click terrain"), "A dragged release does not inspect");
            var click = new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    center.getX(), center.getY(), 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true, null);
            javafx.event.Event.fireEvent(viewport, click);
            await(() -> inspection.getText().startsWith("Terrain ·"), () -> {
                String text = inspection.getText();
                require(text.startsWith("Terrain · map 0 Authored continent · tile 30,40 · x "), "Picked tile: " + text);
                require(text.contains("Area unknown") && text.contains("water is not picked"),
                        "Uncatalogued area stays unknown: " + text);
                inspectCard(view, viewport, inspection);
                next.run();
            });
        });
    }

    /**
     * The terrain pick also opens the inspect card: its fields, Copy, click isolation, travel
     * closing an unpinned card, Pin keeping it, Escape closing it first, and the pin key
     * re-opening the last card.
     */
    private static void inspectCard(WorldView view, javafx.scene.layout.StackPane viewport, Label inspection) {
        var card = view.inspectCard();
        var content = card.content();
        require(card.isVisible() && content != null && content.kind() == WorldInspection.Kind.TERRAIN
                        && content.title().equals("Terrain") && content.subtitle().startsWith("Area unknown")
                        && content.lines().getFirst().startsWith("tile 30,40 · x ")
                        && content.detail().equals(inspection.getText()),
                "The terrain pick opens its card: " + content);
        require(((Label) card.lookup("#world-inspect-chip")).getText().equals("Terrain")
                        && !card.lookup("#world-inspect-file").isVisible(),
                "Chip shown, no file line for terrain");
        for (var id : List.of("#world-inspect-pin", "#world-inspect-copy", "#world-inspect-close"))
            require(!card.lookup(id).isFocusTraversable(), "Not focus traversable: " + id);
        var copied = new java.util.ArrayList<String>();
        card.clipboard(copied::add);
        ((javafx.scene.control.ButtonBase) card.lookup("#world-inspect-copy")).fire();
        require(copied.equals(List.of(inspection.getText())) && viewport.isFocused(),
                "Copy puts the full text on the clipboard and focus returns");
        String before = inspection.getText();
        var onCard = card.localToScene(20, 20);
        javafx.event.Event.fireEvent(card, new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, onCard.getX(), onCard.getY(), 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false, false, false, false,
                false, false, true, null));
        require(inspection.getText().equals(before), "A click on the card never inspects the world behind it");
        var camera = (TerrainRenderer.Camera) get(view, "camera");
        double x = camera.x(), y = camera.y(), z = camera.z();
        var input = (WorldExplorerInput) get(view, "input");
        java.util.function.Consumer<javafx.scene.input.KeyCode> press = code -> {
            javafx.event.Event.fireEvent(viewport, new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            javafx.event.Event.fireEvent(viewport, new javafx.scene.input.KeyEvent(
                    javafx.scene.input.KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false));
        };
        // While the card is open, the toasts keep clear of its column.
        var toasts = view.toasts();
        toasts.show("Authored notice");
        view.applyCss();
        view.layout();
        var cardBox = card.localToScene(card.getLayoutBounds());
        var toastBox = toasts.localToScene(toasts.getLayoutBounds());
        require(toastBox.getMinX() >= cardBox.getMaxX(),
                "Toasts never cover the card column: toasts " + toastBox + " card " + cardBox);
        toasts.clear();
        card.follow(x + WorldInspectCard.FOLLOW_YARDS / 2, y, z);
        require(card.isOpen(), "A short move keeps the card");
        card.follow(x, y, z + WorldInspectCard.FOLLOW_YARDS);
        require(!card.isOpen(), "Distance is 3D: climbing 40 yd closes an unpinned card");
        press.accept(javafx.scene.input.KeyCode.I);
        require(card.isOpen() && card.pinned() && card.content().kind() == WorldInspection.Kind.TERRAIN
                        && !input.keysHeld(),
                "With nothing open, I re-opens the last card pinned, holding no key");
        card.update(WorldInspection.note("Nothing picked · sky"));
        card.close();
        press.accept(javafx.scene.input.KeyCode.I);
        require(card.isOpen() && card.content().kind() == WorldInspection.Kind.TERRAIN,
                "A note never replaces the card I re-opens");
        card.follow(x + 500, y, z);
        require(card.isOpen(), "A pinned card stays while flying");
        card.hudHidden(true);
        require(card.isOpen() && !card.isVisible(), "Hiding the HUD hides the card, which stays open");
        javafx.event.Event.fireEvent(viewport, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                false, false, false, false));
        require(!card.isOpen() && !input.keysHeld(), "Escape closes the card first, even hidden, and holds no key");
        press.accept(javafx.scene.input.KeyCode.I);
        require(card.isOpen() && !card.isVisible(), "I works while the HUD is hidden");
        card.hudHidden(false);
        require(card.isVisible(), "Showing the HUD shows the open card again");
        press.accept(javafx.scene.input.KeyCode.I);
        require(!card.pinned(), "I unpins it");
        // The view hooks the card to the HUD's hidden state: H hides it and keeps it open.
        require(card.isOpen() && card.isVisible(), "The card is open before H");
        press.accept(javafx.scene.input.KeyCode.H);
        require(card.isOpen() && !card.isVisible(), "H hides the card with the HUD; it stays open");
        press.accept(javafx.scene.input.KeyCode.H);
        require(card.isOpen() && card.isVisible(), "H again shows the card");
        card.follow(x + WorldInspectCard.FOLLOW_YARDS, y, z);
        require(!card.isOpen() && !card.isVisible(), "An unpinned card closes after travelling away");
        javafx.event.Event.fireEvent(viewport, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                false, false, false, false));
        javafx.event.Event.fireEvent(viewport, new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_RELEASED, "", "", javafx.scene.input.KeyCode.ESCAPE,
                false, false, false, false));
        require(!input.keysHeld(), "Escape with no card passes through and releases");
    }

    private static void crossMap(WorldView view, TerrainRenderer.Camera saved, double height) {
        var list = bookmarks(view);
        var entry = (WorldBookmarkStore.Entry) list.getItems().getFirst();
        require(entry.bookmark().mapId() == HOME.id() && entry.bookmark().tileX() == 30
                && entry.bookmark().tileY() == 40, "Bookmark records map identity and tile");
        ((Button) view.lookup("#world-back")).fire();
        select(view, OTHER);
        await(() -> get(view, "inventory") != null
                && ((WorldService.Inventory) get(view, "inventory")).map().id() == OTHER.id(), () -> {
            list.getSelectionModel().select(0);
            ((Button) view.lookup("#world-bookmark-go")).fire();
            await(() -> (boolean) get(view, "explorer") && get(view, "camera") != null
                    && !(boolean) get(view, "scenePreparing"), () -> {
                var camera = (TerrainRenderer.Camera) get(view, "camera");
                var inventory = (WorldService.Inventory) get(view, "inventory");
                require(inventory.map().id() == HOME.id(), "Bookmark reselects its recorded map");
                require(camera.x() == saved.x() && camera.y() == saved.y(), "Bookmark position restored");
                double surface = TerrainGround.of(List.of(TerrainGround.Tile.of(new TerrainScene.Tile(30, 40,
                        WorldFormats.adt(root(30, 40), () -> false))))).heightAt(saved.x(), saved.y());
                require(Math.abs(camera.z() - (surface + ExplorerMotion.EYE_HEIGHT)) < 1e-6,
                        "A saved camera below the surface is lifted to eye height: " + camera.z());
                refusals(view);
            });
        });
    }

    private static void refusals(WorldView view) {
        var travel = (WorldTravelPanel) get(view, "travel");
        // Same map ID, different recorded directory: identity mismatch, no selection change.
        var changed = new WorldBookmarks.Bookmark("Changed", 0, "Elsewhere", "Authored continent", 30, 40,
                (32 - 30.5) * SIZE, (32 - 40.5) * SIZE, 50, 0, 0, "authored");
        invoke(view, "goToBookmark", changed);
        require(travel.status().contains("different map directory"), "Map identity mismatch refused: " + travel.status());
        var missing = new WorldBookmarks.Bookmark("Missing", 77, "", "", 30, 40, 0, 0, 0, 0, 0, "");
        invoke(view, "goToBookmark", missing);
        require(travel.status().contains("no map with the saved ID"), "Missing map refused");
        // Recorded tile, but coordinates in a different tile: opens the tile entry, never the point.
        var outside = new WorldBookmarks.Bookmark("Outside", 0, "Authored", "Authored continent", 30, 40,
                (32 - 31.5) * SIZE, (32 - 40.5) * SIZE, 50, 0, 0, "authored");
        set(view, "camera", null);
        ((Button) view.lookup("#world-back")).fire();
        invoke(view, "goToBookmark", outside);
        await(() -> get(view, "camera") != null && !(boolean) get(view, "scenePreparing"), () -> {
            var camera = (TerrainRenderer.Camera) get(view, "camera");
            require(WorldBookmarks.within((32 - 30) * SIZE, (32 - 40) * SIZE, camera.x(), camera.y()),
                    "Out-of-tile bookmark keeps the verified tile entry: " + camera);
            require(travel.status().contains("outside the verified terrain tile"), "Outside reported: " + travel.status());
            require(travel != null, "Travel panel retained");
            DONE.complete(null);
        });
    }

    /** The panel is on the map page, outside the explorer's scene graph while exploring. */
    private static ListView<?> bookmarks(WorldView view) {
        try {
            Field field = WorldTravelPanel.class.getDeclaredField("list");
            field.setAccessible(true);
            return (ListView<?>) field.get(get(view, "travel"));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void select(WorldView view, WorldService.MapInfo map) {
        @SuppressWarnings("unchecked")
        var maps = (ListView<WorldService.MapInfo>) view.lookup("#world-maps");
        maps.getSelectionModel().select(map);
    }

    private static void await(BooleanSupplier ready, Runnable action) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(10), event -> {
            try {
                if (ready.getAsBoolean()) { timer.stop(); action.run(); }
                else if (System.nanoTime() > deadline) throw new AssertionError("Timed out waiting for travel state");
            } catch (Throwable error) { timer.stop(); DONE.completeExceptionally(error); }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private static Object get(Object target, String name) {
        try { Field field = WorldView.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void set(Object target, String name, Object value) {
        try { Field field = WorldView.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object target, String name, WorldBookmarks.Bookmark value) {
        try {
            var method = WorldView.class.getDeclaredMethod(name, WorldBookmarks.Bookmark.class);
            method.setAccessible(true);
            method.invoke(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
