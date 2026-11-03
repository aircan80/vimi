package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.locations.*;
import com.vibeminer.formats.images.Blp2Types;
import com.vibeminer.formats.images.BlpImageTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Authored map pixels only: partial facts, isolated failures, stale callbacks and borrowed
 * lifetime.
 */
public final class LocationMapUiTest {
    private static final MapTypes.MapInfo MAP =
            new MapTypes.MapInfo(42, "Authored Plains", 0L, 3, 0L);
    private static final LocationTypes.Source SOURCE =
            new LocationTypes.Source(
                    LocationTypes.Provider.QUESTIE, "authored", "Forever", null, "enUS", "fixture");
    private static final AtomicInteger sourceCloses = new AtomicInteger(),
            mapCloses = new AtomicInteger(),
            pickupCalls = new AtomicInteger(),
            delayedRequests = new AtomicInteger();
    private static CompletableFuture<MapTypes.Artwork> heldArt;
    private static final AtomicInteger heldArtRequests = new AtomicInteger(),
            tileCalls = new AtomicInteger();
    private static final CompletableFuture<MapTypes.Resolution> delayed = new CompletableFuture<>();

    private LocationMapUiTest() {}

    private static LocationTypes.Location location(long id, long map) {
        return new LocationTypes.Location(
                new LocationTypes.Entity(LocationTypes.EntityKind.NPC, id),
                LocationTypes.Origin.DECLARED_SPAWN,
                List.of(
                        new LocationTypes.Coordinate(
                                new LocationTypes.MapRef(LocationTypes.MapKind.UI_MAP, map),
                                LocationTypes.Units.NORMALIZED,
                                .25,
                                .5,
                                null)),
                7L,
                new LocationTypes.Evidence(SOURCE, "authored", Long.toString(id), "positions"));
    }

    private static MapTypes.Resolution resolution(LocationTypes.Location location) {
        return new MapTypes.Resolution(
                MapTypes.State.PARTIAL,
                null,
                "enUS",
                location,
                List.of(
                        new MapTypes.Candidate(
                                MAP,
                                .25,
                                .5,
                                123L,
                                MapTypes.Basis.RECORDED_UI_MAP,
                                List.of(),
                                "Authored partial relation")),
                List.of("Readable prefix retained"),
                "Partial source");
    }

    private static BuildLocations sources() {
        return new BuildLocations() {
            public CompletableFuture<LocationTypes.Locations> locations(
                    LocationTypes.Entity entity, Cancellation token) {
                return CompletableFuture.completedFuture(
                        new LocationTypes.Locations(
                                LocationTypes.State.PARTIAL,
                                entity,
                                List.of(location(entity.id(), 42), location(entity.id(), 77)),
                                List.of()));
            }

            public CompletableFuture<LocationTypes.Pickups> pickups(
                    long quest, Cancellation token) {
                pickupCalls.incrementAndGet();
                var point = location(202, 42);
                return CompletableFuture.completedFuture(
                        new LocationTypes.Pickups(
                                LocationTypes.State.PARTIAL,
                                quest,
                                List.of(
                                        new LocationTypes.Pickup(
                                                point.entity(),
                                                List.of(),
                                                new LocationTypes.Locations(
                                                        LocationTypes.State.READY,
                                                        point.entity(),
                                                        List.of(point),
                                                        List.of()))),
                                List.of()));
            }

            public CompletableFuture<LocationTypes.Frames> frames(Cancellation token) {
                return CompletableFuture.completedFuture(
                        new LocationTypes.Frames(LocationTypes.State.READY, List.of(), List.of()));
            }

            public CompletableFuture<Void> closeAsync() {
                sourceCloses.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static BuildLocationMaps maps() {
        return new BuildLocationMaps() {
            public CompletableFuture<MapTypes.Resolution> resolve(
                    LocationTypes.Location point, Cancellation token) {
                if (point.entity().id() == 101) {
                    delayedRequests.incrementAndGet();
                    return delayed;
                }
                if (point.coordinates().getFirst().map().id() == 77)
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Authored isolated failure"));
                return CompletableFuture.completedFuture(resolution(point));
            }

            public CompletableFuture<MapTypes.Artwork> artwork(long id, Cancellation token) {
                if (heldArt != null) {
                    heldArtRequests.incrementAndGet();
                    return heldArt;
                }
                return CompletableFuture.completedFuture(
                        new MapTypes.Artwork(
                                MapTypes.State.PARTIAL,
                                null,
                                "enUS",
                                MAP,
                                10L,
                                new MapTypes.Layer(1, 0, 64, 42, 64, 64),
                                List.of(),
                                List.of(new MapTypes.Tile(1, 11, 0, 0, 0, 0, 64, 42)),
                                List.of(),
                                List.of("Authored partial art"),
                                "Base layer only"));
            }

            public CompletableFuture<MapTypes.TileImage> tile(
                    long id, long row, Cancellation token) {
                tileCalls.incrementAndGet();
                byte[] rgba = new byte[64 * 42 * 4];
                for (int i = 0; i < rgba.length; i += 4) {
                    rgba[i] = (byte) (i / 256 >= 34 ? 220 : 20);
                    rgba[i + 1] = 80;
                    rgba[i + 2] = (byte) (i / 256 >= 34 ? 20 : 120);
                    rgba[i + 3] = (byte) 255;
                }
                var image =
                        new Blp2Types.Image(
                                64,
                                42,
                                256,
                                rgba,
                                Blp2Types.Encoding.BGRA8,
                                8,
                                0,
                                0,
                                64,
                                42,
                                List.of(),
                                0);
                return CompletableFuture.completedFuture(
                        new MapTypes.TileImage(
                                MapTypes.State.READY,
                                new MapTypes.Tile(1, 11, 0, 0, 0, 0, 64, 42),
                                new BlpImageTypes.Result(
                                        BlpImageTypes.State.READY,
                                        image,
                                        BlpImageTypes.Cache.NONE,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null),
                                List.of(),
                                "Authored"));
            }

            public CompletableFuture<Void> closeAsync() {
                mapCloses.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static <T> T fx(Supplier<T> action) throws Exception {
        var done = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        done.complete(action.get());
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
        return done.get(10, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void await(Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(condition)) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("Timed out awaiting authored map");
            Thread.sleep(20);
        }
    }

    public static void main(String[] args) throws Exception {
        var ready = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    ready.complete(null);
                });
        ready.get(10, TimeUnit.SECONDS);
        try {
            var map = maps();
            var view =
                    fx(
                            () ->
                                    new LocationMapView(
                                            () -> map,
                                            LocationMapUiTest::sources,
                                            () -> 1L,
                                            false));
            var stage =
                    fx(
                            () -> {
                                var value = new Stage();
                                view.getStyleClass().add("inspector");
                                var scene = new Scene(view, 340, 600);
                                scene.getStylesheets()
                                        .add(
                                                LocationMapUiTest.class
                                                        .getResource("/vimi.css")
                                                        .toExternalForm());
                                value.setScene(scene);
                                value.show();
                                return value;
                            });
            fx(
                    () -> {
                        view.select(101L);
                        return null;
                    });
            await(() -> delayedRequests.get() == 1);
            fx(
                    () -> {
                        view.select(202L);
                        return null;
                    });
            await(
                    () ->
                            ((ComboBox<?>) view.lookup("#location-map-choice")).getItems().size()
                                            == 1
                                    && ((Label) view.lookup("#location-map-status"))
                                            .getText()
                                            .contains("1 without"));
            delayed.complete(resolution(location(101, 42)));
            await(
                    () -> {
                        var canvas = (Canvas) view.lookup("#location-map-canvas");
                        var image = canvas.snapshot(null, null);
                        var color =
                                image.getPixelReader()
                                        .getColor(
                                                (int) (image.getWidth() * .7),
                                                (int) (image.getHeight() * .7));
                        return color.getBlue() > color.getRed() + .2;
                    });
            await(
                    () -> {
                        var canvas = (Canvas) view.lookup("#location-map-canvas");
                        var image = canvas.snapshot(null, null);
                        var color =
                                image.getPixelReader()
                                        .getColor(
                                                (int) (image.getWidth() * .7),
                                                (int) (image.getHeight() * .95));
                        return color.getRed() > color.getBlue() + .4;
                    });
            fx(
                    () -> {
                        var snapshot = view.snapshot(null, null);
                        var bitmap =
                                new java.awt.image.BufferedImage(
                                        (int) snapshot.getWidth(),
                                        (int) snapshot.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < bitmap.getHeight(); y++)
                            for (int x = 0; x < bitmap.getWidth(); x++)
                                bitmap.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                        try {
                            var directory =
                                    java.nio.file.Path.of(
                                            System.getProperty(
                                                    "vimi.uiReviewDirectory", "build/ui-review"));
                            java.nio.file.Files.createDirectories(directory);
                            javax.imageio.ImageIO.write(
                                    bitmap,
                                    "png",
                                    directory.resolve("locations-authored.png").toFile());
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                        return null;
                    });
            fx(
                    () -> {
                        stage.setWidth(270);
                        view.applyCss();
                        view.layout();
                        require(view.getWidth() <= 340, "Narrow sidebar lays out");
                        view.close();
                        stage.close();
                        return null;
                    });
            var quest =
                    fx(
                            () ->
                                    new LocationMapView(
                                            () -> map, LocationMapUiTest::sources, () -> 1L, true));
            fx(
                    () -> {
                        quest.select(999L);
                        return null;
                    });
            await(
                    () ->
                            pickupCalls.get() == 1
                                    && ((ComboBox<?>) quest.lookup("#location-map-choice"))
                                                    .getItems()
                                                    .size()
                                            == 1);
            fx(
                    () -> {
                        quest.close();
                        return null;
                    });
            require(sourceCloses.get() == 2, "Each owned provider handle closes once");
            var authoredArt = map.artwork(42, new Cancellation()).join();
            heldArt = new CompletableFuture<>();
            var sourceVersion = new java.util.concurrent.atomic.AtomicLong(1);
            var guarded =
                    fx(
                            () ->
                                    new LocationMapView(
                                            () -> map,
                                            LocationMapUiTest::sources,
                                            () -> 1L,
                                            sourceVersion::get,
                                            false));
            fx(
                    () -> {
                        guarded.select(202L);
                        return null;
                    });
            await(() -> heldArtRequests.get() == 1);
            int before = tileCalls.get();
            sourceVersion.incrementAndGet();
            heldArt.complete(authoredArt);
            fx(
                    () -> {
                        require(
                                tileCalls.get() == before,
                                "Retired source revision cannot initiate artwork tile reads");
                        guarded.close();
                        return null;
                    });
            require(sourceCloses.get() == 3, "Source-revision fixture closes its owned handle");
            require(mapCloses.get() == 0, "Selected-build map handle remains borrowed");
            System.out.println(
                    "Location map UI: partial artwork, readable points after failure, stale"
                            + " selection, resize, quest starters and borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }
}
