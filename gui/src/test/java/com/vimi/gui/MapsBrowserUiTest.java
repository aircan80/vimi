package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.concepts.locations.BuildLocationMaps;
import com.vibeminer.concepts.locations.LocationTypes;
import com.vibeminer.concepts.locations.MapNavigationGraph;
import com.vibeminer.concepts.locations.MapNavigationSourceTypes;
import com.vibeminer.concepts.locations.MapTypes;
import com.vibeminer.concepts.locations.MapNavigationTypes.Assignment;
import com.vibeminer.concepts.locations.MapNavigationTypes.Node;
import com.vibeminer.concepts.locations.MapNavigationTypes.Parent;
import com.vibeminer.concepts.locations.MapNavigationTypes.ReadState;
import com.vibeminer.concepts.locations.MapNavigationTypes.Rect;
import com.vibeminer.concepts.locations.MapNavigationTypes.RowSource;
import com.vibeminer.concepts.locations.MapNavigationTypes.Scope;
import com.vibeminer.concepts.locations.MapNavigationTypes.Table;
import com.vibeminer.concepts.locations.MapNavigationTypes.TableRead;
import com.vibeminer.concepts.locations.MapNavigationTypes.UiMapId;
import com.vibeminer.concepts.locations.MapNavigationTypes.WorldMapId;
import com.vibeminer.concepts.locations.MapNavigationTypes.WorldRect;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;

/** Authored UI fixtures only; validates presentation/controls/lifetime, not source authority. */
public final class MapsBrowserUiTest {
    private static MapsView view;
    private static Stage stage;
    private static final BuildRef BUILD = new BuildRef("wowf", "a".repeat(32), "b".repeat(32), "us");
    private static final TableRead MAPS = new TableRead(Table.UI_MAP, "a".repeat(32), "DB51D55F", ReadState.READY);
    private static final TableRead ASSIGNMENTS = new TableRead(Table.ASSIGNMENT, "b".repeat(32), "C9CC8DFB", ReadState.READY);
    private static final Rect FULL = new Rect(0, 0, 1, 1);
    private static final AtomicInteger roleLoads = new AtomicInteger(), roleKey = new AtomicInteger();
    private MapsBrowserUiTest() {}

    private static Node node(long id, String name, Parent parent) {
        return new Node(new UiMapId(id), name, parent, 0, 0, 0L, 0L, new RowSource(MAPS, id));
    }
    private static Assignment assignment(long row, long id, WorldRect world) {
        return new Assignment(new UiMapId(id), new WorldMapId(0), world, FULL, true,
                new RowSource(ASSIGNMENTS, row));
    }
    private static MapNavigationGraph graph() {
        return new MapNavigationGraph(new Scope(BUILD, "enUS", MAPS, ASSIGNMENTS), List.of(
                node(947, "Azeroth", Parent.root()),
                node(1415, "Eastern Kingdoms", Parent.of(new UiMapId(947))),
                node(1429, "Elwynn Forest", Parent.of(new UiMapId(1415)))), List.of(
                assignment(1, 947, new WorldRect(0, 0, 1000, 1000)),
                assignment(2, 1415, new WorldRect(0, 0, 500, 1000)),
                assignment(3, 1429, new WorldRect(0, 0, 100, 100))), () -> false);
    }
    private static final class Source implements BuildLocationMaps {
        final CompletableFuture<MapNavigationSourceTypes.Result> initial = new CompletableFuture<>();
        final CompletableFuture<MapTypes.TileImage> firstTile = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger(), tiles = new AtomicInteger();
        volatile CompletableFuture<MapNavigationSourceTypes.Result> held;
        volatile CountDownLatch cancelEntered, cancelRelease;
        @Override public CompletableFuture<MapNavigationSourceTypes.Result> navigation(Cancellation token) {
            require(!Platform.isFxApplicationThread(), "Navigation on FX"); calls.incrementAndGet();
            if (held != null) {
                token.onCancel(() -> {
                    require(!Platform.isFxApplicationThread(), "Lower cancellation on FX");
                    cancelEntered.countDown();
                    try { require(cancelRelease.await(5, TimeUnit.SECONDS), "release cancel"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                });
                return held;
            }
            return initial;
        }
        @Override public CompletableFuture<MapTypes.Resolution> resolve(LocationTypes.Location l, Cancellation t) {
            throw new AssertionError("Browser cannot infer spawn locations");
        }
        @Override public CompletableFuture<MapTypes.Artwork> artwork(long id, Cancellation t) {
            require(!Platform.isFxApplicationThread(), "Artwork on FX");
            return CompletableFuture.completedFuture(new MapTypes.Artwork(MapTypes.State.READY,
                    BUILD, "enUS", new MapTypes.MapInfo(id, "Authored", null, 0, 0L), 1L,
                    new MapTypes.Layer(1, 0, 64, 42, 64, 42), List.of(),
                    List.of(new MapTypes.Tile(1, 11, 0, 0, 0, 0, 64, 42)), List.of(), List.of(), "Authored"));
        }
        @Override public CompletableFuture<MapTypes.TileImage> tile(long id, long row, Cancellation t) {
            require(!Platform.isFxApplicationThread(), "Tile on FX");
            return tiles.incrementAndGet() == 1 ? firstTile : CompletableFuture.completedFuture(pixels());
        }
        @Override public CompletableFuture<Void> closeAsync() { throw new AssertionError("Borrowed source closed"); }
    }
    private static MapTypes.TileImage pixels() {
        byte[] rgba = new byte[64 * 42 * 4];
        for (int i = 0; i < rgba.length; i += 4) { rgba[i] = 20; rgba[i + 1] = 80; rgba[i + 2] = (byte) 180; rgba[i + 3] = (byte) 255; }
        var image = new Blp2Types.Image(64, 42, 256, rgba, Blp2Types.Encoding.BGRA8,
                8, 0, 0, 64, 42, List.of(), 0);
        return new MapTypes.TileImage(MapTypes.State.READY, new MapTypes.Tile(1, 11, 0, 0, 0, 0, 64, 42),
                new BlpImageTypes.Result(BlpImageTypes.State.READY, image, BlpImageTypes.Cache.NONE,
                        List.of(), null, null, null, null), List.of(), "Authored");
    }
    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> { Platform.setImplicitExit(false); started.complete(null); });
        started.get(10, TimeUnit.SECONDS);
        var source = new Source();
        var handle = new java.util.concurrent.atomic.AtomicReference<BuildLocationMaps>(source);
        try {
            fx(() -> {
                view = new MapsView(handle::get, () -> 1L);
                view.attachRoles(new MapRoleOverlay(token -> {
                    require(!Platform.isFxApplicationThread(), "Role placement on FX");
                    roleLoads.incrementAndGet();
                    return MapRoleOverlayTest.placement("v1.0.10", 947, List.of(
                            MapRoleOverlayTest.npc(2, 4, null, 25, 25),
                            MapRoleOverlayTest.npc(3, 0, List.of(7L), 75, 25)));
                }, () -> roleKey.get(), view::redraw));
                stage = new Stage(); stage.setScene(new Scene(view, 1100, 760)); stage.show();
                view.ensureLoaded(); require(view.lookup("#maps-activity").isVisible(), "hierarchy activity");
            });
            await(() -> source.calls.get() == 1, "source call");
            source.initial.complete(new MapNavigationSourceTypes.Result(MapNavigationSourceTypes.State.READY,
                    graph(), List.of(), List.of("AUTHORED_UI_FIXTURE")));
            await(() -> source.tiles.get() == 1, "tile call");
            fx(() -> require(view.lookup("#maps-activity").isVisible(), "texture activity remains"));
            source.firstTile.complete(pixels());
            await(() -> !view.lookup("#maps-activity").isVisible(), "art presentation");
            fx(() -> {
                var canvas = (Canvas) view.lookup("#maps-canvas");
                var snapshot = canvas.snapshot(null, null);
                var color = snapshot.getPixelReader().getColor((int) (canvas.getWidth() * .7), (int) (canvas.getHeight() * .7));
                require(color.getBlue() > color.getRed() + .3, "accepted authored pixels");
                require(roleLoads.get() == 0, "overlays stay off until a layer is enabled");
                view.roles().toggle(com.vibeminer.concepts.locations.MapRoles.Layer.VENDOR).setSelected(true);
            });
            await(() -> view.roles().drawn().size() == 1, "vendor marker placed off FX");
            fx(() -> {
                var status = view.roles().status().getText();
                require(status.contains("1 vendors") && status.contains("not evaluated"), status);
                var canvas = (Canvas) view.lookup("#maps-canvas");
                var marker = view.roles().drawn().getFirst();
                var snapshot = canvas.snapshot(null, null);
                var image = (javafx.scene.image.Image) snapshot;
                // Authored map fills the canvas width at fit zoom; locate the marker center.
                double ratio = 64.0 / 42, w = Math.min(canvas.getWidth(), canvas.getHeight() * ratio), h = w / ratio;
                int x = (int) (canvas.getWidth() / 2 - w / 2 + marker.x() * w);
                int y = (int) (canvas.getHeight() / 2 - h / 2 + marker.y() * h);
                var center = image.getPixelReader().getColor(x, y);
                require(center.getGreen() > center.getBlue(), "vendor marker painted over artwork: " + center);
                view.roles().toggle(com.vibeminer.concepts.locations.MapRoles.Layer.QUEST_GIVER).setSelected(true);
                require(view.roles().drawn().size() == 2, "quest giver joins without reloading");
                require(roleLoads.get() == 1, "one placement per source key");
                roleKey.incrementAndGet(); view.redraw();
                require(view.roles().drawn().isEmpty(), "changed source key retires markers");
            });
            await(() -> view.roles().drawn().size() == 2 && roleLoads.get() == 2, "changed key reloads");
            fx(() -> {
                view.roles().toggle(com.vibeminer.concepts.locations.MapRoles.Layer.VENDOR).setSelected(false);
                view.roles().toggle(com.vibeminer.concepts.locations.MapRoles.Layer.QUEST_GIVER).setSelected(false);
                require(view.roles().drawn().isEmpty() && view.roles().status().getText().contains("off"), "overlays off");
                select(1429);
            });
            await(() -> !view.lookup("#maps-activity").isVisible(), "zone presentation");
            fx(() -> { require(picker().getValue().toString().contains("1429"), "direct zone");
                ((Button) view.lookup("#maps-up")).fire(); });
            await(() -> !view.lookup("#maps-activity").isVisible(), "up presentation");
            fx(() -> { require(picker().getValue().toString().contains("1415"), "Up follows parent");
                ((Button) view.lookup("#maps-back")).fire(); });
            await(() -> !view.lookup("#maps-activity").isVisible(), "back presentation");
            fx(() -> require(picker().getValue().toString().contains("1429"), "Back follows visit history"));
            var replacement = new Source();
            handle.set(replacement);
            fx(view::ensureLoaded);
            await(() -> replacement.calls.get() == 1, "same-revision handle replacement reloads");
            fx(() -> require(picker().getItems().isEmpty(), "old graph controls retired during reload"));
            replacement.initial.complete(new MapNavigationSourceTypes.Result(MapNavigationSourceTypes.State.READY,
                    graph(), List.of(), List.of("AUTHORED_REPLACEMENT")));
            await(() -> replacement.tiles.get() == 1, "replacement tile");
            var correct = pixels();
            replacement.firstTile.complete(new MapTypes.TileImage(MapTypes.State.READY,
                    new MapTypes.Tile(999, 11, 0, 0, 0, 0, 64, 42), correct.image(), List.of(), "Wrong row"));
            await(() -> !view.lookup("#maps-activity").isVisible(), "wrong tile terminates activity");
            fx(() -> require(((Label) view.lookup("#maps-status")).getText().contains("0/1 readable"),
                    "wrong tile identity not painted"));
            fx(() -> require(((Label) view.lookup("#maps-source-details")).getText().contains("descriptor differs"),
                    "missing tile source reason retained"));
            var retired = new Source(); handle.set(retired);
            fx(view::ensureLoaded);
            await(() -> retired.calls.get() == 1, "held retired source");
            handle.set(source);
            retired.initial.complete(new MapNavigationSourceTypes.Result(MapNavigationSourceTypes.State.READY,
                    graph(), List.of(), List.of("RETIRED")));
            await(() -> !view.lookup("#maps-activity").isVisible(), "retired result ends local activity");
            fx(() -> { require(picker().getItems().isEmpty(), "retired graph not installed"); view.ensureLoaded(); });
            await(() -> !view.lookup("#maps-activity").isVisible(), "original source reloaded");
            source.held = new CompletableFuture<>(); source.cancelEntered = new CountDownLatch(1); source.cancelRelease = new CountDownLatch(1);
            fx(() -> { view.invalidate(); view.ensureLoaded(); });
            await(() -> source.calls.get() == 3, "held refresh");
            fx(view::close);
            require(source.cancelEntered.await(2, TimeUnit.SECONDS), "off-caller cancellation starts");
            fx(() -> require(!view.lookup("#maps-activity").isVisible(), "close remains responsive"));
            source.held.completeExceptionally(new java.util.concurrent.CancellationException());
            source.cancelRelease.countDown();
            fx(stage::close);
            System.out.println("Maps browser UI: hierarchy/art activity, pixels, direct/Up/Back, role overlays and off-FX held cancellation passed");
        } finally {
            source.firstTile.complete(pixels());
            if (source.cancelRelease != null) source.cancelRelease.countDown();
            if (source.held != null) source.held.completeExceptionally(new java.util.concurrent.CancellationException());
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }
    private static ComboBox<?> picker() { return (ComboBox<?>) view.lookup("#maps-picker"); }
    private static void select(long id) {
        for (int i = 0; i < picker().getItems().size(); i++) if (picker().getItems().get(i).toString().endsWith("· " + id)) {
            picker().getSelectionModel().select(i); return;
        }
        throw new AssertionError("Missing map " + id);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { action.run(); done.complete(null); } catch (Throwable t) { done.completeExceptionally(t); } });
        done.get(2, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier ready, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            var result = new java.util.concurrent.atomic.AtomicBoolean(); fx(() -> result.set(ready.getAsBoolean()));
            if (result.get()) return; Thread.sleep(20);
        }
        throw new AssertionError(message);
    }
}
