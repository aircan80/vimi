package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaNavigation;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Authored world only; verifies map selection, stale inventory suppression and actual frame
 * delivery.
 */
public final class WorldUiTest {
    private WorldUiTest() {}

    private static final CompletableFuture<Void> emptyChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> areaChecks = new CompletableFuture<>();
    private static final CompletableFuture<Void> orientationChecks = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        areaChecks.whenComplete(
                (value, error) -> {
                    if (error != null) done.completeExceptionally(error);
                });
        emptyChecks.whenComplete((value,error) -> { if(error!=null) done.completeExceptionally(error); });
        var closes = new AtomicInteger();
        Platform.startup(
                () ->
                        Platform.runLater(
                                () -> {
                                    Platform.setImplicitExit(false);
                                    emptyInventoryStates();
                                    gridOrientation();
                                    areaNavigation();
                                    var stage = new Stage();
                                    try {
                                        var continent =
                                                new WorldService.MapInfo(
                                                        0, "Authored continent", "Authored", 0, 1L);
                                        var second =
                                                new WorldService.MapInfo(
                                                        1, "Other continent", "Other", 0, 2L);
                                        var tile = new WorldFormats.Tile(32, 32, 10, 0, 0);
                                        var delayed =
                                                new CompletableFuture<WorldService.Inventory>();
                                        var oldTile =
                                                new CompletableFuture<WorldService.LoadedTile>();
                                        var neighbor =
                                                new CompletableFuture<WorldService.LoadedTile>();
                                        var tileCalls = new AtomicInteger();
                                        var catalogGate = new CompletableFuture<Void>();
                                        var neighborToken =
                                                new java.util.concurrent.atomic.AtomicReference<
                                                        Cancellation>();
                                        BuildWorld handle =
                                                new BuildWorld() {
                                                    public CompletableFuture<WorldService.Catalog>
                                                            catalog(Cancellation t) {
                                                        require(
                                                                !Platform.isFxApplicationThread(),
                                                                "Catalog acquisition is off FX");
                                                        return catalogGate.thenApply(
                                                                ignored ->
                                                                        new WorldService.Catalog(
                                                                                List.of(
                                                                                        continent,
                                                                                        second),
                                                                                List.of(
                                                                                        new WorldService
                                                                                                .Area(
                                                                                                100,
                                                                                                "Authored"
                                                                                                    + " zone",
                                                                                                0,
                                                                                                0)),
                                                                                List.of(
                                                                                        "Authored"
                                                                                            + " partial"
                                                                                            + " fixture")));
                                                    }

                                                    public CompletableFuture<WorldService.Inventory>
                                                            inventory(
                                                                    WorldService.MapInfo map,
                                                                    Cancellation t) {
                                                        require(
                                                                !Platform.isFxApplicationThread(),
                                                                "Inventory acquisition is off FX");
                                                        return map.id() == 1
                                                                ? delayed
                                                                : CompletableFuture.completedFuture(
                                                                        new WorldService.Inventory(
                                                                                map,
                                                                                new WorldFormats
                                                                                        .World(
                                                                                        List.of(
                                                                                                tile,
                                                                                                new WorldFormats
                                                                                                        .Tile(
                                                                                                        33,
                                                                                                        32,
                                                                                                        11,
                                                                                                        0,
                                                                                                        0)),
                                                                                        false),
                                                                                null));
                                                    }

                                                    public CompletableFuture<
                                                                    WorldService.LoadedTile>
                                                            tile(
                                                                    WorldService.MapInfo map,
                                                                    WorldFormats.Tile location,
                                                                    Cancellation t) {
                                                        require(
                                                                !Platform.isFxApplicationThread(),
                                                                "Tile acquisition is off FX");
                                                        int call = tileCalls.getAndIncrement();
                                                        if (call == 0) return oldTile;
                                                        if (call == 2) {
                                                            neighborToken.set(t);
                                                            return neighbor;
                                                        }
                                                        return CompletableFuture.completedFuture(
                                                                new WorldService.LoadedTile(
                                                                        location, terrain(), null));
                                                    }

                                                    public CompletableFuture<Void> closeAsync() {
                                                        closes.incrementAndGet();
                                                        return CompletableFuture.completedFuture(
                                                                null);
                                                    }
                                                };
                                        var view = new WorldView(() -> handle, () -> 1);
                                        var scene = new Scene(view, 1320, 850);
                                        scene.getStylesheets()
                                                .add(
                                                        VimiApp.class
                                                                .getResource("/vimi.css")
                                                                .toExternalForm());
                                        stage.setScene(scene);
                                        stage.show();
                                        view.ensureLoaded();
                                        require(
                                                view.lookup("#world-catalog-activity").isVisible(),
                                                "Delayed catalog exposes activity");
                                        CompletableFuture.delayedExecutor(80, TimeUnit.MILLISECONDS)
                                                .execute(() -> catalogGate.complete(null));
                                        await(
                                                () ->
                                                        ((ListView<?>) view.lookup("#world-maps"))
                                                                        .getItems()
                                                                        .size()
                                                                == 2,
                                                () -> {
                                                    var maps =
                                                            (ListView<?>)
                                                                    view.lookup("#world-maps");
                                                    require(
                                                            !view.lookup("#world-catalog-activity")
                                                                    .isVisible(),
                                                            "Catalog activity retires");
                                                    maps.getSelectionModel().select(1);
                                                    require(
                                                            view.lookup("#world-local-activity")
                                                                    .isVisible(),
                                                            "Delayed inventory exposes activity");
                                                    maps.getSelectionModel().select(0);
                                                    delayed.complete(
                                                            new WorldService.Inventory(
                                                                    second,
                                                                    new WorldFormats.World(
                                                                            List.of(), false),
                                                                    null));
                                                    await(
                                                            () ->
                                                                    !((Button)
                                                                                    view.lookup(
                                                                                            "#world-open"))
                                                                            .isDisabled(),
                                                            () -> {
                                                                ((Button)
                                                                                view.lookup(
                                                                                        "#world-open"))
                                                                        .fire();
                                                                require(
                                                                        view.lookup(
                                                                                        "#world-first-frame-activity")
                                                                                .isVisible(),
                                                                        "Initial terrain has"
                                                                            + " central activity");
                                                                restartAfterOldTileIssued(
                                                                        view, tile, tileCalls, oldTile,
                                                                        () -> neighborToken.get() != null,
                                                                        () -> {
                                                                            require(
                                                                                    scene.lookup(
                                                                                                    "#world-viewport")
                                                                                            != null,
                                                                                    "Explorer"
                                                                                        + " viewport"
                                                                                        + " shown");
                                                                            require(
                                                                                    view
                                                                                            .lookupAll(
                                                                                                    ".label")
                                                                                            .stream()
                                                                                            .filter(
                                                                                                    javafx
                                                                                                                    .scene
                                                                                                                    .control
                                                                                                                    .Label
                                                                                                                    .class
                                                                                                            ::isInstance)
                                                                                            .map(
                                                                                                    javafx
                                                                                                                    .scene
                                                                                                                    .control
                                                                                                                    .Label
                                                                                                                    .class
                                                                                                            ::cast)
                                                                                            .noneMatch(
                                                                                                    l ->
                                                                                                            l.getText()
                                                                                                                    .contains(
                                                                                                                            "altitude"
                                                                                                                                + " 1000")),
                                                                                    "Old explorer"
                                                                                        + " session"
                                                                                        + " cannot"
                                                                                        + " reset"
                                                                                        + " the new"
                                                                                        + " camera");
                                                                            require(
                                                                                    neighborToken
                                                                                                    .get()
                                                                                            != null,
                                                                                    "Neighbor"
                                                                                        + " request"
                                                                                        + " has started");
                                                                            require(
                                                                                    view.lookup(
                                                                                                    "#world-local-activity")
                                                                                            .isVisible(),
                                                                                    "Nearby"
                                                                                        + " acquisition"
                                                                                        + " retains"
                                                                                        + " local"
                                                                                        + " activity");
                                                                            require(
                                                                                    !view.lookup(
                                                                                                    "#world-first-frame-activity")
                                                                                            .isVisible(),
                                                                                    "Readable frame"
                                                                                        + " retires"
                                                                                        + " initial"
                                                                                        + " activity");
                                                                            assertMountedMinimap(view);
                                                                            view.deactivate();
                                                                            require(!view.lookup("#world-minimap").isVisible(),
                                                                                    "Leaving World hides minimap and retires artwork work");
                                                                            require(
                                                                                    !view.lookup(
                                                                                                    "#world-local-activity")
                                                                                            .isVisible(),
                                                                                    "Deactivation"
                                                                                        + " retires"
                                                                                        + " activity");
                                                                            finishAfterNeighborStops(
                                                                                    view, stage, scene, neighborToken,
                                                                                    neighbor, closes, done);
                                                                        },
                                                                        done);
                                                            },
                                                            done);
                                                },
                                                done);
                                    } catch (Throwable failure) {
                                        stage.close();
                                        done.completeExceptionally(failure);
                                        Platform.exit();
                                    }
                                }));
        try {
            done.get(30, TimeUnit.SECONDS);
            orientationChecks.get(10, TimeUnit.SECONDS);
            emptyChecks.get(10, TimeUnit.SECONDS);
            areaChecks.get(15, TimeUnit.SECONDS);
        } finally {
            Platform.exit();
        }
        System.out.println(
                "World UI: continent drill-down, stale inventory, terrain frame and borrowed"
                        + " lifetime passed");
    }

    private static void areaNavigation() {
        var map = new WorldService.MapInfo(0, "Area fixture", "Authored", 0, 1L);
        var tile = new WorldFormats.Tile(17, 21, 12345, 0, 0);
        var other = new WorldFormats.Tile(18, 21, 12346, 0, 0);
        var entry =
                new WorldFormats.AreaChunk(
                        3, 7, 42, 100, 500, 700, new WorldFormats.AreaPoint(123, 456, 789));
        var match = new WorldAreaNavigation.Match(tile, "a".repeat(32), 2, entry);
        var revision = new java.util.concurrent.atomic.AtomicLong();
        class Handle implements BuildWorld {
            final java.util.List<CompletableFuture<WorldAreaNavigation.Result>> scans =
                    new java.util.concurrent.CopyOnWriteArrayList<>();
            final java.util.List<java.util.function.Consumer<WorldAreaNavigation.Progress>>
                    progress = new java.util.concurrent.CopyOnWriteArrayList<>();
            volatile Cancellation scanToken;
            final AtomicInteger areaOpens = new AtomicInteger(),
                    borrowedCloses = new AtomicInteger();

            public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
                return CompletableFuture.completedFuture(
                        new WorldService.Catalog(
                                List.of(map),
                                List.of(
                                        new WorldService.Area(42, "Exact area", 0, 0),
                                        new WorldService.Area(43, "Other area", 0, 0)),
                                List.of()));
            }

            public CompletableFuture<WorldService.Inventory> inventory(
                    WorldService.MapInfo chosen, Cancellation token) {
                return CompletableFuture.completedFuture(
                        new WorldService.Inventory(
                                map, new WorldFormats.World(List.of(tile, other), false), null));
            }

            @SuppressWarnings("try")
            public CompletableFuture<WorldAreaNavigation.Result> findArea(
                    WorldAreaNavigation.Request request,
                    Cancellation token,
                    java.util.function.Consumer<WorldAreaNavigation.Progress> observer) {
                require(!Platform.isFxApplicationThread(), "Area scan is invoked off FX");
                require(
                        request.tiles().equals(List.of(tile, other)),
                        "Exact full inventory references retained");
                scanToken = token;
                progress.add(observer);
                var future = new CompletableFuture<WorldAreaNavigation.Result>();
                scans.add(future);
                if (scans.size() == 1) {
                    var canceled = new java.util.concurrent.CountDownLatch(1);
                    try (var registration = token.onCancel(canceled::countDown)) {
                        require(
                                canceled.await(5, TimeUnit.SECONDS),
                                "Synchronous provider receives cancellation while invocation is"
                                    + " still blocked");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return future;
            }

            public CompletableFuture<WorldService.LoadedTile> areaTile(
                    WorldService.MapInfo chosen,
                    WorldAreaNavigation.Match selected,
                    Cancellation token) {
                require(!Platform.isFxApplicationThread(), "Verified area open is off FX");
                require(selected.equals(match), "Opening carries exact tile/CKey/chunk evidence");
                areaOpens.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new WorldService.LoadedTile(tile, terrain(), null));
            }

            public CompletableFuture<WorldService.LoadedTile> tile(
                    WorldService.MapInfo chosen, WorldFormats.Tile selected, Cancellation token) {
                return CompletableFuture.completedFuture(
                        new WorldService.LoadedTile(selected, null, "Authored nearby unavailable"));
            }

            public CompletableFuture<Void> closeAsync() {
                borrowedCloses.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        }
        var handle = new Handle();
        var view = new WorldView(() -> handle, revision::get);
        var stage = new Stage();
        stage.setScene(new Scene(view, 1100, 850));
        stage.show();
        view.ensureLoaded();
        await(
                () -> !((ListView<?>) view.lookup("#world-maps")).getItems().isEmpty(),
                () -> {
                    ((ListView<?>) view.lookup("#world-maps")).getSelectionModel().selectFirst();
                    await(
                            () ->
                                    ((ListView<?>) view.lookup("#world-areas")).getItems().size()
                                            == 2,
                            () -> {
                                var areas = (ListView<?>) view.lookup("#world-areas");
                                areas.getSelectionModel().selectFirst();
                                await(
                                        () ->
                                                !((Button) view.lookup("#world-area-find"))
                                                        .isDisabled(),
                                        () -> {
                                            require(
                                                    handle.scans.isEmpty(),
                                                    "Selecting an area never acquires files");
                                            ((Button) view.lookup("#world-area-find")).fire();
                                            await(
                                                    () -> handle.scans.size() == 1,
                                                    () -> {
                                                        require(
                                                                view.lookup("#world-area-activity")
                                                                        .isVisible(),
                                                                "Delayed scan has local animated"
                                                                        + " activity");
                                                        handle.progress
                                                                .getFirst()
                                                                .accept(
                                                                        new WorldAreaNavigation
                                                                                .Progress(
                                                                                1, 2, 1, 0));
                                                        ((Button) view.lookup("#world-area-cancel"))
                                                                .fire();
                                                        await(
                                                                () -> handle.scanToken.isCanceled(),
                                                                () -> {
                                                                    require(
                                                                            view.lookup(
                                                                                            "#world-area-activity")
                                                                                    .isVisible(),
                                                                            "Cancellation remains"
                                                                                + " pending until"
                                                                                + " current file"
                                                                                + " drains");
                                                                    handle.scans
                                                                            .getFirst()
                                                                            .complete(
                                                                                    new WorldAreaNavigation
                                                                                            .Result(
                                                                                            WorldAreaNavigation
                                                                                                    .State
                                                                                                    .CANCELED,
                                                                                            new WorldAreaNavigation
                                                                                                    .Progress(
                                                                                                    1,
                                                                                                    2,
                                                                                                    1,
                                                                                                    0),
                                                                                            List.of(
                                                                                                    match),
                                                                                            List
                                                                                                    .of()));
                                                                    await(
                                                                            () ->
                                                                                    ((ListView<?>)
                                                                                                            view
                                                                                                                    .lookup(
                                                                                                                            "#world-area-matches"))
                                                                                                    .getItems()
                                                                                                    .size()
                                                                                            == 1,
                                                                            () -> {
                                                                                require(
                                                                                        !view.lookup(
                                                                                                        "#world-area-activity")
                                                                                                .isVisible(),
                                                                                        "Terminal"
                                                                                            + " cancellation"
                                                                                            + " clears"
                                                                                            + " activity");
                                                                                require(
                                                                                        ((javafx
                                                                                                                .scene
                                                                                                                .control
                                                                                                                .Label)
                                                                                                        view
                                                                                                                .lookup(
                                                                                                                        "#world-area-status"))
                                                                                                .getText()
                                                                                                .contains(
                                                                                                        "Incomplete"),
                                                                                        "Canceled"
                                                                                            + " coverage"
                                                                                            + " is explicit");
                                                                                if (System
                                                                                                .getProperty(
                                                                                                        "vimi.world.screenshot")
                                                                                        != null) {
                                                                                    view.applyCss();
                                                                                    view.layout();
                                                                                    snapshot(
                                                                                            stage
                                                                                                    .getScene(),
                                                                                            System
                                                                                                            .getProperty(
                                                                                                                    "vimi.world.screenshot")
                                                                                                    + ".area.png");
                                                                                }

                                                                                ((ListView<?>)
                                                                                                view
                                                                                                        .lookup(
                                                                                                                "#world-area-matches"))
                                                                                        .getSelectionModel()
                                                                                        .selectFirst();
                                                                                ((Button)
                                                                                                view
                                                                                                        .lookup(
                                                                                                                "#world-open"))
                                                                                        .fire();
                                                                                await(
                                                                                        () ->
                                                                                                ((ImageView)
                                                                                                                        view
                                                                                                                                .lookup(
                                                                                                                                        "#world-frame"))
                                                                                                                .getImage()
                                                                                                        != null,
                                                                                        () -> {
                                                                                            require(
                                                                                                    handle
                                                                                                                    .areaOpens
                                                                                                                    .get()
                                                                                                            == 1,
                                                                                                    "Discovered"
                                                                                                        + " tile"
                                                                                                        + " uses"
                                                                                                        + " CKey-verifying"
                                                                                                        + " areaTile"
                                                                                                        + " API");
                                                                                            var
                                                                                                    position =
                                                                                                            ((javafx
                                                                                                                                    .scene
                                                                                                                                    .control
                                                                                                                                    .Label)
                                                                                                                            positionLine(view))
                                                                                                                    .getText();
                                                                                            require(
                                                                                                    position
                                                                                                            .contains(
                                                                                                                    "x 123.0"
                                                                                                                        + " / y 456.0"
                                                                                                                        + " / altitude"
                                                                                                                        + " 889.0"),
                                                                                                    "Initial"
                                                                                                        + " camera"
                                                                                                        + " uses"
                                                                                                        + " exact"
                                                                                                        + " matching"
                                                                                                        + " entry"
                                                                                                        + " +100,"
                                                                                                        + " not tile"
                                                                                                        + " center");
                                                                                            ((Button)
                                                                                                            view
                                                                                                                    .lookup(
                                                                                                                            "#world-back"))
                                                                                                    .fire();
                                                                                            view
                                                                                                    .applyCss();
                                                                                            view
                                                                                                    .layout();
                                                                                            areas.getSelectionModel()
                                                                                                    .select(
                                                                                                            1);
                                                                                            require(
                                                                                                    ((ListView<
                                                                                                                            ?>)
                                                                                                                    view
                                                                                                                            .lookup(
                                                                                                                                    "#world-area-matches"))
                                                                                                            .getItems()
                                                                                                            .isEmpty(),
                                                                                                    "Area change"
                                                                                                        + " clears"
                                                                                                        + " old highlights/matches");
                                                                                            ((Button)
                                                                                                            view
                                                                                                                    .lookup(
                                                                                                                            "#world-area-find"))
                                                                                                    .fire();
                                                                                            await(
                                                                                                    () ->
                                                                                                            handle
                                                                                                                            .scans
                                                                                                                            .size()
                                                                                                                    == 2,
                                                                                                    () -> {
                                                                                                        areas.getSelectionModel()
                                                                                                                .selectFirst();
                                                                                                        handle.scans
                                                                                                                .get(
                                                                                                                        1)
                                                                                                                .complete(
                                                                                                                        new WorldAreaNavigation
                                                                                                                                .Result(
                                                                                                                                WorldAreaNavigation
                                                                                                                                        .State
                                                                                                                                        .COMPLETE,
                                                                                                                                new WorldAreaNavigation
                                                                                                                                        .Progress(
                                                                                                                                        2,
                                                                                                                                        2,
                                                                                                                                        1,
                                                                                                                                        0),
                                                                                                                                List
                                                                                                                                        .of(
                                                                                                                                                match),
                                                                                                                                List
                                                                                                                                        .of()));
                                                                                                        await(
                                                                                                                () ->
                                                                                                                        !((Button)
                                                                                                                                        view
                                                                                                                                                .lookup(
                                                                                                                                                        "#world-area-find"))
                                                                                                                                .isDisabled(),
                                                                                                                () -> {
                                                                                                                    require(
                                                                                                                            ((ListView<
                                                                                                                                                    ?>)
                                                                                                                                            view
                                                                                                                                                    .lookup(
                                                                                                                                                            "#world-area-matches"))
                                                                                                                                    .getItems()
                                                                                                                                    .isEmpty(),
                                                                                                                            "Old area"
                                                                                                                                + " completion"
                                                                                                                                + " cannot"
                                                                                                                                + " republish"
                                                                                                                                + " matches");
                                                                                                                    ((Button)
                                                                                                                                    view
                                                                                                                                            .lookup(
                                                                                                                                                    "#world-area-find"))
                                                                                                                            .fire();
                                                                                                                    await(
                                                                                                                            () ->
                                                                                                                                    handle
                                                                                                                                                    .scans
                                                                                                                                                    .size()
                                                                                                                                            == 3,
                                                                                                                            () -> {
                                                                                                                                handle.progress
                                                                                                                                        .get(
                                                                                                                                                1)
                                                                                                                                        .accept(
                                                                                                                                                new WorldAreaNavigation
                                                                                                                                                        .Progress(
                                                                                                                                                        2,
                                                                                                                                                        2,
                                                                                                                                                        1,
                                                                                                                                                        0));
                                                                                                                                Platform
                                                                                                                                        .runLater(
                                                                                                                                                () -> {
                                                                                                                                                    require(
                                                                                                                                                            view.lookup(
                                                                                                                                                                            "#world-area-activity")
                                                                                                                                                                    .isVisible(),
                                                                                                                                                            "Old progress"
                                                                                                                                                                + " cannot"
                                                                                                                                                                + " clear"
                                                                                                                                                                + " newer"
                                                                                                                                                                + " activity");
                                                                                                                                                    revision
                                                                                                                                                            .incrementAndGet();
                                                                                                                                                    handle.scans
                                                                                                                                                            .get(
                                                                                                                                                                    2)
                                                                                                                                                            .complete(
                                                                                                                                                                    new WorldAreaNavigation
                                                                                                                                                                            .Result(
                                                                                                                                                                            WorldAreaNavigation
                                                                                                                                                                                    .State
                                                                                                                                                                                    .COMPLETE,
                                                                                                                                                                            new WorldAreaNavigation
                                                                                                                                                                                    .Progress(
                                                                                                                                                                                    2,
                                                                                                                                                                                    2,
                                                                                                                                                                                    1,
                                                                                                                                                                                    0),
                                                                                                                                                                            List
                                                                                                                                                                                    .of(
                                                                                                                                                                                            match),
                                                                                                                                                                            List
                                                                                                                                                                                    .of()));
                                                                                                                                                    await(
                                                                                                                                                            () ->
                                                                                                                                                                    !view.lookup(
                                                                                                                                                                                    "#world-area-activity")
                                                                                                                                                                            .isVisible(),
                                                                                                                                                            () -> {
                                                                                                                                                                require(
                                                                                                                                                                        ((Button)
                                                                                                                                                                                        view
                                                                                                                                                                                                .lookup(
                                                                                                                                                                                                        "#world-area-find"))
                                                                                                                                                                                .isDisabled(),
                                                                                                                                                                        "External"
                                                                                                                                                                            + " retirement"
                                                                                                                                                                            + " disables"
                                                                                                                                                                            + " old actions");
                                                                                                                                                                require(
                                                                                                                                                                        ((ListView<
                                                                                                                                                                                                ?>)
                                                                                                                                                                                        view
                                                                                                                                                                                                .lookup(
                                                                                                                                                                                                        "#world-area-matches"))
                                                                                                                                                                                .getItems()
                                                                                                                                                                                .isEmpty(),
                                                                                                                                                                        "Retired"
                                                                                                                                                                            + " result"
                                                                                                                                                                            + " never"
                                                                                                                                                                            + " attaches");
                                                                                                                                                                view
                                                                                                                                                                        .close();
                                                                                                                                                                stage
                                                                                                                                                                        .close();
                                                                                                                                                                require(
                                                                                                                                                                        handle
                                                                                                                                                                                        .borrowedCloses
                                                                                                                                                                                        .get()
                                                                                                                                                                                == 0,
                                                                                                                                                                        "Area UI"
                                                                                                                                                                            + " keeps"
                                                                                                                                                                            + " borrowed"
                                                                                                                                                                            + " world"
                                                                                                                                                                            + " handle"
                                                                                                                                                                            + " open");
                                                                                                                                                                areaChecks
                                                                                                                                                                        .complete(
                                                                                                                                                                                null);
                                                                                                                                                            },
                                                                                                                                                            areaChecks);
                                                                                                                                                });
                                                                                                                            },
                                                                                                                            areaChecks);
                                                                                                                },
                                                                                                                areaChecks);
                                                                                                    },
                                                                                                    areaChecks);
                                                                                        },
                                                                                        areaChecks);
                                                                            },
                                                                            areaChecks);
                                                                },
                                                                areaChecks);
                                                    },
                                                    areaChecks);
                                        },
                                        areaChecks);
                            },
                            areaChecks);
                },
                areaChecks);
    }

    private static void gridOrientation() {
        var map = new WorldService.MapInfo(0, "Authored tall continent", "Authored", 0, 1L);
        var inventory = new java.util.ArrayList<WorldFormats.Tile>();
        for (int row = 8; row <= 30; row++)
            for (int column = 20; column <= 22; column++)
                inventory.add(new WorldFormats.Tile(row, column, 100000 + row * 64 + column, 0, 0));
        var chosen = new java.util.concurrent.atomic.AtomicReference<WorldFormats.Tile>();
        BuildWorld handle =
                new BuildWorld() {
                    public CompletableFuture<WorldService.Catalog> catalog(Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new WorldService.Catalog(
                                        List.of(map),
                                        List.of(
                                                new WorldService.Area(
                                                        1, "Authored area reference", 0, 0)),
                                        List.of()));
                    }

                    public CompletableFuture<WorldService.Inventory> inventory(
                            WorldService.MapInfo map, Cancellation token) {
                        return CompletableFuture.completedFuture(
                                new WorldService.Inventory(
                                        map, new WorldFormats.World(inventory, false), null));
                    }

                    public CompletableFuture<WorldService.LoadedTile> tile(
                            WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation token) {
                        chosen.set(tile);
                        return new CompletableFuture<>();
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var view = new WorldView(() -> handle, () -> 1);
        var stage = new Stage();
        var scene = new Scene(view, 1150, 950);
        stage.setScene(scene);
        stage.show();
        view.ensureLoaded();
        await(
                () -> ((ListView<?>) view.lookup("#world-maps")).getItems().size() == 1,
                () -> {
                    ((ListView<?>) view.lookup("#world-maps")).getSelectionModel().selectFirst();
                    await(
                            () ->
                                    view.lookup("#world-tiles") != null
                                            && !((Button) view.lookup("#world-open")).isDisabled(),
                            () -> {
                                try {
                                    var canvas =
                                            (javafx.scene.canvas.Canvas)
                                                    view.lookup("#world-tiles");
                                    var gridImage = canvas.snapshot(null, null);
                                    var pixels = gridImage.getPixelReader();
                                    int[] gridPixels = new int[512 * 512];
                                    pixels.getPixels(
                                            0,
                                            0,
                                            512,
                                            512,
                                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                                            gridPixels,
                                            0,
                                            512);
                                    var gridBitmap =
                                            new java.awt.image.BufferedImage(
                                                    512,
                                                    512,
                                                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                    gridBitmap.setRGB(0, 0, 512, 512, gridPixels, 0, 512);
                                    var proofDirectory =
                                            Path.of(
                                                    System.getProperty(
                                                            "vimi.uiReviewDirectory",
                                                            "build/ui-review"));
                                    java.nio.file.Files.createDirectories(proofDirectory);
                                    javax.imageio.ImageIO.write(
                                            gridBitmap,
                                            "png",
                                            proofDirectory
                                                    .resolve("world-grid-orientation-authored.png")
                                                    .toFile());
                                    int green = 0xff6c9a7c, blank = 0xffe5ebe6;
                                    require(
                                            pixels.getArgb(21 * 8 + 3, 8 * 8 + 3) == green
                                                    && pixels.getArgb(21 * 8 + 3, 17 * 8 + 3)
                                                            == green,
                                            "Filename first index runs vertically in the"
                                                    + " full64-grid");
                                    require(
                                            pixels.getArgb(8 * 8 + 3, 21 * 8 + 3) == blank,
                                            "Tall asymmetric inventory is not drawn transposed");
                                    var zones =
                                            (ListView<?>)
                                                    view.lookupAll(".list-view").stream()
                                                            .filter(
                                                                    node ->
                                                                            "Areas in selected map"
                                                                                    .equals(
                                                                                            node
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    zones.getSelectionModel().selectFirst();
                                    require(
                                            canvas.snapshot(null, null)
                                                            .getPixelReader()
                                                            .getArgb(21 * 8 + 3, 17 * 8 + 3)
                                                    == green,
                                            "Area reference selection preserves the full-map"
                                                    + " inventory");
                                    double x = 21 * 8 + 3, y = 17 * 8 + 3;
                                    canvas.getOnMouseClicked()
                                            .handle(
                                                    new javafx.scene.input.MouseEvent(
                                                            javafx.scene.input.MouseEvent
                                                                    .MOUSE_CLICKED,
                                                            x,
                                                            y,
                                                            x,
                                                            y,
                                                            javafx.scene.input.MouseButton.PRIMARY,
                                                            1,
                                                            false,
                                                            false,
                                                            false,
                                                            false,
                                                            true,
                                                            false,
                                                            false,
                                                            false,
                                                            false,
                                                            true,
                                                            null));
                                    var selectedPixels =
                                            canvas.snapshot(null, null).getPixelReader();
                                    require(
                                            selectedPixels.getArgb(21 * 8, 17 * 8) != green,
                                            "Selection highlight follows the displayed cell");
                                    ((Button) view.lookup("#world-open")).fire();
                                    await(
                                            () -> chosen.get() != null,
                                            () -> {
                                                try {
                                                    require(
                                                            chosen.get().x() == 17
                                                                    && chosen.get().y() == 21
                                                                    && chosen.get().rootId()
                                                                            == 101109,
                                                            "Clicked cell loads the original"
                                                                    + " filename tuple and exact"
                                                                    + " FileDataID");
                                                    view.close();
                                                    stage.close();
                                                    orientationChecks.complete(null);
                                                } catch (Throwable failure) {
                                                    view.close();
                                                    stage.close();
                                                    orientationChecks.completeExceptionally(
                                                            failure);
                                                }
                                            },
                                            orientationChecks);
                                } catch (Throwable failure) {
                                    view.close();
                                    stage.close();
                                    orientationChecks.completeExceptionally(failure);
                                }
                            },
                            orientationChecks);
                },
                orientationChecks);
    }

    private static void emptyInventoryStates() {
        var records = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new WorldService.MapInfo(i, "Empty-state case " + i, "Authored", 0, 1L))
                .toList();
        var delayed = new CompletableFuture<WorldService.Inventory>();
        BuildWorld handle = new BuildWorld() {
            public CompletableFuture<WorldService.Catalog> catalog(Cancellation cancel) {
                return CompletableFuture.completedFuture(new WorldService.Catalog(records,
                        records.stream().map(m -> new WorldService.Area(m.id()+1,"Area",0,m.id())).toList(), List.of()));
            }
            public CompletableFuture<WorldService.Inventory> inventory(WorldService.MapInfo map, Cancellation cancel) {
                if(map.id()==5) return delayed;
                if(map.id()==4) return CompletableFuture.failedFuture(new IllegalStateException("Authored inventory failure"));
                return CompletableFuture.completedFuture(new WorldService.Inventory(map,
                        map.id()==3 ? null : new WorldFormats.World(map.id()==0
                            ? List.of(new WorldFormats.Tile(32,32,1,0,0)) : List.of(),map.id()==1),
                        map.id()==3 ? "Authored WDT unavailable" : null));
            }
            public CompletableFuture<WorldService.LoadedTile> tile(WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation cancel) {
                throw new AssertionError("Empty inventory must never request a terrain tile");
            }
            public CompletableFuture<Void> closeAsync() { throw new AssertionError("Borrowed handle"); }
        };
        var view = new WorldView(() -> handle, () -> 1);
        var stage = new Stage();
        stage.setScene(new Scene(view,1320,850)); stage.show(); view.ensureLoaded();
        await(() -> ((ListView<?>)view.lookup("#world-maps")).getItems().size()==6,
                () -> emptyInventoryCase(view,stage,records,delayed,0),emptyChecks);
    }

    private static void emptyInventoryCase(WorldView view, Stage stage,
            List<WorldService.MapInfo> maps, CompletableFuture<WorldService.Inventory> delayed, int at) {
        @SuppressWarnings("unchecked")
        var choices=(ListView<WorldService.MapInfo>)view.lookup("#world-maps");
        choices.getSelectionModel().select(maps.get(at));
        view.applyCss();
        view.layout();
        var message=(javafx.scene.control.Label)view.lookup("#world-inventory-message");
        require(message.isVisible() && message.getText().contains("Loading"),"Inventory overlay explains pending map");
        if(at==5) {
            require(view.lookup("#world-local-activity").isVisible(),"Delayed map activity");
            view.close(); stage.close();
            delayed.complete(new WorldService.Inventory(maps.get(at),new WorldFormats.World(List.of(),true),null));
            await(() -> true, () -> {
                require(!view.lookup("#world-local-activity").isVisible(),"Close clears inventory activity");
                require(!message.getText().contains("global world model"),"Closed late inventory cannot replace status");
                System.out.println("World inventory: loading, global model, empty terrain, unavailable/error and closed late completion passed");
                emptyChecks.complete(null);
            },emptyChecks);
            return;
        }
        await(() -> !view.lookup("#world-local-activity").isVisible()
                        && (at==0 ? !message.isVisible() : !message.getText().contains("Loading")),
                () -> {
                    var open=(Button)view.lookup("#world-open");
                    if(at==0) require(!open.isDisabled(),"Readable tile enables explorer and removes overlay");
                    else {
                        // A global world-model map is entered inline as a GPU interior (#259).
                        require(open.isDisabled() == (at != 1),
                                "Empty or unavailable map disables explorer; a global model enables it");
                        @SuppressWarnings("unchecked")
                        var areas=(ListView<WorldService.Area>)view.lookup("#world-areas");
                        areas.getSelectionModel().selectFirst();
                        require(view.lookup("#world-area-find").isDisabled(),"No terrain discovery action for empty/unavailable inventory");
                        String expected=at==1 ? "global world model" : at==2 ? "No terrain tiles" : at==3 ? "Authored WDT unavailable" : "Terrain inventory unavailable";
                        require(message.isVisible() && message.getText().contains(expected),"Precise map-local empty explanation "+at);
                        if(at!=1) require(!message.getText().contains("global world model"),"No guessed world-model classification");
                        require(message.localToScene(message.getBoundsInLocal()).getMaxY() < view.getScene().getHeight(),
                                "Empty-map explanation is visible without scrolling");
                        if(at==1) snapshot(stage.getScene(),System.getProperty("vimi.world.screenshot") == null ? null : System.getProperty("vimi.world.screenshot") + ".empty.png");
                    }
                    emptyInventoryCase(view,stage,maps,delayed,at+1);
                },emptyChecks);
    }

    private static void externalRetirementAndFailure(CompletableFuture<Void> done) {
        var map = new WorldService.MapInfo(9, "Delayed authored", "Delayed", 0, 1L);
        var catalog = new WorldService.Catalog(List.of(map), List.of(), List.of());
        class Fixture implements BuildWorld {
            volatile CompletableFuture<WorldService.Catalog> catalogue = new CompletableFuture<>();
            volatile CompletableFuture<WorldService.Inventory> inventory =
                    CompletableFuture.failedFuture(
                            new IllegalStateException("Authored inventory failure"));
            final AtomicInteger calls = new AtomicInteger();

            public CompletableFuture<WorldService.Catalog> catalog(Cancellation cancel) {
                calls.incrementAndGet();
                return catalogue;
            }

            public CompletableFuture<WorldService.Inventory> inventory(
                    WorldService.MapInfo map, Cancellation cancel) {
                return inventory;
            }

            public CompletableFuture<WorldService.LoadedTile> tile(
                    WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation cancel) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Authored tile failure"));
            }

            public CompletableFuture<Void> closeAsync() {
                throw new AssertionError("Borrowed handle");
            }
        }
        var original = new Fixture();
        var replacement = new Fixture();
        var current = new java.util.concurrent.atomic.AtomicReference<BuildWorld>(original);
        var revision = new java.util.concurrent.atomic.AtomicLong(1);
        var view = new WorldView(current::get, revision::get);
        new Scene(view, 1320, 850);
        view.applyCss();
        view.layout();
        view.ensureLoaded();
        await(
                () -> original.calls.get() == 1,
                () -> {
                    revision.incrementAndGet();
                    original.catalogue.complete(catalog);
                    await(
                            () -> !view.lookup("#world-catalog-activity").isVisible(),
                            () -> {
                                require(
                                        ((ListView<?>) view.lookup("#world-maps"))
                                                .getItems()
                                                .isEmpty(),
                                        "Old revision cannot commit catalog");
                                original.catalogue = new CompletableFuture<>();
                                view.ensureLoaded();
                                await(
                                        () -> original.calls.get() == 2,
                                        () -> {
                                            current.set(replacement);
                                            original.catalogue.complete(catalog);
                                            await(
                                                    () ->
                                                            !view.lookup("#world-catalog-activity")
                                                                    .isVisible(),
                                                    () -> {
                                                        require(
                                                                ((ListView<?>)
                                                                                view.lookup(
                                                                                        "#world-maps"))
                                                                        .getItems()
                                                                        .isEmpty(),
                                                                "Changed handle cannot commit"
                                                                        + " catalog");
                                                        replacement.catalogue =
                                                                CompletableFuture.failedFuture(
                                                                        new IllegalStateException(
                                                                                "Authored catalog"
                                                                                    + " failure"));
                                                        view.ensureLoaded();
                                                        await(
                                                                () ->
                                                                        !view.lookup(
                                                                                        "#world-catalog-activity")
                                                                                .isVisible(),
                                                                () -> {
                                                                    var placeholder = ((ListView<?>) view.lookup("#world-maps")).getPlaceholder();
                                                                    require(placeholder instanceof javafx.scene.control.Label label
                                                                                    && label.getText().contains("unavailable")
                                                                                    && !label.getText().contains("Loading"),
                                                                            "Failed catalog replaces its loading placeholder");
                                                                    replacement.catalogue =
                                                                            CompletableFuture
                                                                                    .completedFuture(
                                                                                            catalog);
                                                                    view.ensureLoaded();
                                                                    await(
                                                                            () ->
                                                                                    ((ListView<?>)
                                                                                                            view
                                                                                                                    .lookup(
                                                                                                                            "#world-maps"))
                                                                                                    .getItems()
                                                                                                    .size()
                                                                                            == 1,
                                                                            () -> {
                                                                                var rows =
                                                                                        (ListView<
                                                                                                        ?>)
                                                                                                view
                                                                                                        .lookup(
                                                                                                                "#world-maps");
                                                                                rows.getSelectionModel()
                                                                                        .selectFirst();
                                                                                await(
                                                                                        () ->
                                                                                                !view.lookup(
                                                                                                                "#world-local-activity")
                                                                                                        .isVisible(),
                                                                                        () -> {
                                                                                            require(
                                                                                                    ((Button)
                                                                                                                    view
                                                                                                                            .lookup(
                                                                                                                                    "#world-open"))
                                                                                                            .isDisabled(),
                                                                                                    "Inventory"
                                                                                                        + " failure"
                                                                                                        + " keeps"
                                                                                                        + " explorer"
                                                                                                        + " disabled");
                                                                                            replacement
                                                                                                            .inventory =
                                                                                                    CompletableFuture
                                                                                                            .completedFuture(
                                                                                                                    new WorldService
                                                                                                                            .Inventory(
                                                                                                                            map,
                                                                                                                            new WorldFormats
                                                                                                                                    .World(
                                                                                                                                    List
                                                                                                                                            .of(
                                                                                                                                                    new WorldFormats
                                                                                                                                                            .Tile(
                                                                                                                                                            32,
                                                                                                                                                            32,
                                                                                                                                                            10,
                                                                                                                                                            0,
                                                                                                                                                            0)),
                                                                                                                                    false),
                                                                                                                            null));
                                                                                            rows.getSelectionModel()
                                                                                                    .clearSelection();
                                                                                            rows.getSelectionModel()
                                                                                                    .selectFirst();
                                                                                            await(
                                                                                                    () ->
                                                                                                            !((Button)
                                                                                                                            view
                                                                                                                                    .lookup(
                                                                                                                                            "#world-open"))
                                                                                                                    .isDisabled(),
                                                                                                    () -> {
                                                                                                        ((Button)
                                                                                                                        view
                                                                                                                                .lookup(
                                                                                                                                        "#world-open"))
                                                                                                                .fire();
                                                                                                        await(
                                                                                                                () ->
                                                                                                                        !view.lookup(
                                                                                                                                        "#world-first-frame-activity")
                                                                                                                                .isVisible(),
                                                                                                                () -> {
                                                                                                                    require(
                                                                                                                            !view.lookup(
                                                                                                                                            "#world-local-activity")
                                                                                                                                    .isVisible(),
                                                                                                                            "Failed"
                                                                                                                                + " first"
                                                                                                                                + " tile"
                                                                                                                                + " retires"
                                                                                                                                + " all activity");
                                                                                                                    replacement
                                                                                                                                    .inventory =
                                                                                                                            new CompletableFuture<>();
                                                                                                                    rows.getSelectionModel()
                                                                                                                            .clearSelection();
                                                                                                                    rows.getSelectionModel()
                                                                                                                            .selectFirst();
                                                                                                                    require(
                                                                                                                            view.lookup(
                                                                                                                                            "#world-local-activity")
                                                                                                                                    .isVisible(),
                                                                                                                            "Held inventory"
                                                                                                                                + " exposes"
                                                                                                                                + " activity");
                                                                                                                    view
                                                                                                                            .close();
                                                                                                                    replacement
                                                                                                                            .inventory
                                                                                                                            .complete(
                                                                                                                                    new WorldService
                                                                                                                                            .Inventory(
                                                                                                                                            map,
                                                                                                                                            new WorldFormats
                                                                                                                                                    .World(
                                                                                                                                                    List
                                                                                                                                                            .of(),
                                                                                                                                                    false),
                                                                                                                                            null));
                                                                                                                    Platform
                                                                                                                            .runLater(
                                                                                                                                    () -> {
                                                                                                                                        try {
                                                                                                                                            require(
                                                                                                                                                    !view.lookup(
                                                                                                                                                                    "#world-local-activity")
                                                                                                                                                            .isVisible(),
                                                                                                                                                    "Closed"
                                                                                                                                                        + " late"
                                                                                                                                                        + " inventory"
                                                                                                                                                        + " cannot"
                                                                                                                                                        + " restore"
                                                                                                                                                        + " activity");
                                                                                                                                            done
                                                                                                                                                    .complete(
                                                                                                                                                            null);
                                                                                                                                        } catch (
                                                                                                                                                Throwable
                                                                                                                                                        error) {
                                                                                                                                            done
                                                                                                                                                    .completeExceptionally(
                                                                                                                                                            error);
                                                                                                                                        }
                                                                                                                                        Platform
                                                                                                                                                .exit();
                                                                                                                                    });
                                                                                                                },
                                                                                                                done);
                                                                                                    },
                                                                                                    done);
                                                                                        },
                                                                                        done);
                                                                            },
                                                                            done);
                                                                },
                                                                done);
                                                    },
                                                    done);
                                        },
                                        done);
                            },
                            done);
                },
                done);
    }

    private static void finishAfterNeighborStops(
            WorldView view, Stage stage, Scene scene,
            java.util.concurrent.atomic.AtomicReference<Cancellation> neighborToken,
            CompletableFuture<WorldService.LoadedTile> neighbor, AtomicInteger closes,
            CompletableFuture<Void> done) {
        // Cancellation callbacks run off FX; observe delivery instead of assuming synchronous IO.
        await(() -> neighborToken.get().isCanceled(), () -> {
            neighbor.complete(new WorldService.LoadedTile(
                    new WorldFormats.Tile(33, 32, 11, 0, 0), terrain(), null));
            view.activate();
            snapshot(scene);
            view.close();
            require(!view.lookup("#world-catalog-activity").isVisible(),
                    "Close retires catalog activity");
            require(closes.get() == 0, "View borrows the workspace handle");
            stage.close();
            externalRetirementAndFailure(done);
        }, done);
    }

    private static void restartAfterOldTileIssued(
            WorldView view, WorldFormats.Tile tile, AtomicInteger calls,
            CompletableFuture<WorldService.LoadedTile> oldTile,
            java.util.function.BooleanSupplier neighborStarted, Runnable afterImage,
            CompletableFuture<Void> done) {
        // Opening only schedules acquisition. Exercise a genuinely issued old request, rather
        // than assuming a queued request has started before the immediate back/open actions.
        await(() -> calls.get() > 0, () -> {
            ((Button) view.lookup("#world-back")).fire();
            ((Button) view.lookup("#world-open")).fire();
            var high = terrain().vertices();
            for (int at = 2; at < high.length; at += 3) high[at] = 900;
            oldTile.complete(new WorldService.LoadedTile(tile,
                    new WorldFormats.Terrain(high, terrain().triangles(), 1), null));
            await(() -> ((ImageView) view.lookup("#world-frame")).getImage() != null
                            && neighborStarted.getAsBoolean(), afterImage, done);
        }, done);
    }

    private static void await(
            BooleanSupplier ready, Runnable action, CompletableFuture<Void> done) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var timer = new javafx.animation.Timeline();
        timer.getKeyFrames()
                .add(
                        new javafx.animation.KeyFrame(
                                javafx.util.Duration.millis(20),
                                e -> {
                                    try {
                                        if (ready.getAsBoolean()) {
                                            timer.stop();
                                            action.run();
                                        } else if (System.nanoTime() > deadline) {
                                            throw new AssertionError(
                                                    "World UI did not become ready");
                                        }
                                    } catch (Throwable failure) {
                                        timer.stop();
                                        done.completeExceptionally(failure);
                                        Platform.exit();
                                    }
                                }));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        timer.play();
    }

    private static WorldFormats.Terrain terrain() {
        float[] vertices = new float[9 * 9 * 3];
        int[] indices = new int[8 * 8 * 6];
        for (int y = 0; y < 9; y++)
            for (int x = 0; x < 9; x++) {
                int at = (y * 9 + x) * 3;
                vertices[at] = (float) (533.333 - y * 533.333 / 8);
                vertices[at + 1] = (float) (533.333 - x * 533.333 / 8);
                vertices[at + 2] = (float) (30 + Math.sin(x * .6) * Math.cos(y * .6) * 35);
            }
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                int at = (y * 8 + x) * 6, a = y * 9 + x;
                int[] triangle = {a, a + 1, a + 9, a + 1, a + 10, a + 9};
                System.arraycopy(triangle, 0, indices, at, 6);
            }
        return new WorldFormats.Terrain(vertices, indices, 1);
    }

    private static void snapshot(Scene scene) {
        snapshot(scene, System.getProperty("vimi.world.screenshot"));
    }

    private static void snapshot(Scene scene, String destination) {
        if (destination == null) return;
        try {
            var image = scene.snapshot(null);
            int[] pixels = new int[(int) image.getWidth() * (int) image.getHeight()];
            image.getPixelReader()
                    .getPixels(
                            0,
                            0,
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            javafx.scene.image.PixelFormat.getIntArgbInstance(),
                            pixels,
                            0,
                            (int) image.getWidth());
            var bitmap =
                    new java.awt.image.BufferedImage(
                            (int) image.getWidth(),
                            (int) image.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
            bitmap.setRGB(
                    0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());
            javax.imageio.ImageIO.write(bitmap, "png", Path.of(destination).toFile());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    private static void assertMountedMinimap(WorldView view) {
        var minimap = view.lookup("#world-minimap");
        var viewport = view.lookup("#world-viewport");
        require(minimap != null && minimap.isVisible(), "Explorer shows mounted minimap");
        view.applyCss();
        view.layout();
        var mapBounds = minimap.localToScene(minimap.getBoundsInLocal());
        var viewBounds = viewport.localToScene(viewport.getBoundsInLocal());
        require(Math.abs(viewBounds.getMaxX() - mapBounds.getMaxX() - 12) < 1,
                "Minimap stays in viewport right corner");
        require(Math.abs(mapBounds.getMinY() - viewBounds.getMinY() - 12) < 1,
                "Minimap stays in viewport top corner");
        require(((javafx.scene.control.Label) view.lookup("#world-minimap-message"))
                        .getText().contains("unavailable"),
                "Missing recorded artwork explains fallback in mounted minimap");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** The view's full position line: the model behind the HUD coordinates pill (its tooltip). */
    private static javafx.scene.Node positionLine(WorldView view) {
        try {
            var field = WorldView.class.getDeclaredField("position");
            field.setAccessible(true);
            return (javafx.scene.Node) field.get(view);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
