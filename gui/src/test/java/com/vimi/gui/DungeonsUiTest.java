package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.dungeons.BuildDungeons;
import com.vibeminer.concepts.dungeons.DungeonTypes;
import com.vibeminer.formats.tables.TableTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/** Authored-only dungeon layout, filtering, partial metadata and stale/borrowed-lifetime checks. */
public final class DungeonsUiTest {
    private DungeonsUiTest() {}

    private static final CompletableFuture<Void> loadingChecks = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        loadingFeedback();
                        var dungeon =
                                new DungeonTypes.Dungeon(
                                        33,
                                        "Authored Keep",
                                        DungeonTypes.Kind.DUNGEON,
                                        5L,
                                        100L,
                                        0L);
                        var raid =
                                new DungeonTypes.Dungeon(
                                        44,
                                        "Authored Citadel",
                                        DungeonTypes.Kind.RAID,
                                        40L,
                                        null,
                                        0L);
                        var pending = new CompletableFuture<DungeonTypes.Overview>();
                        var closes = new AtomicInteger();
                        var loads = new AtomicInteger();
                        var sources =
                                List.of(
                                        new TableTypes.Entry(
                                                1,
                                                "Map",
                                                TableTypes.State.PARTIAL,
                                                true,
                                                null,
                                                new TableTypes.Counts(
                                                        3, 2, 1, 1, "fixture", "AUTHORED"),
                                                "1".repeat(32)),
                                        new TableTypes.Entry(
                                                2,
                                                "DungeonEncounter",
                                                TableTypes.State.PARTIAL,
                                                true,
                                                null,
                                                new TableTypes.Counts(
                                                        4, 3, 1, 1, "fixture", "AUTHORED"),
                                                "2".repeat(32)));
                        BuildDungeons handle =
                                new BuildDungeons() {
                                    public CompletableFuture<DungeonTypes.Catalog> catalog(
                                            Cancellation token) {
                                        loads.incrementAndGet();
                                        return CompletableFuture.completedFuture(
                                                new DungeonTypes.Catalog(
                                                        DungeonTypes.State.PARTIAL,
                                                        List.of(dungeon, raid),
                                                        sources,
                                                        List.of("Authored protected map row"),
                                                        null));
                                    }

                                    public CompletableFuture<DungeonTypes.Overview> overview(
                                            long id, Cancellation token) {
                                        if (id == 44) {
                                            return pending;
                                        }
                                        return CompletableFuture.completedFuture(
                                                new DungeonTypes.Overview(
                                                        DungeonTypes.State.PARTIAL,
                                                        dungeon,
                                                        List.of(
                                                                new DungeonTypes.Area(
                                                                        200,
                                                                        "Authored Greenwood",
                                                                        0L,
                                                                        0L),
                                                                new DungeonTypes.Area(
                                                                        100,
                                                                        "Keep interior",
                                                                        200L,
                                                                        33L)),
                                                        List.of(),
                                                        List.of(
                                                                new DungeonTypes.Difficulty(
                                                                        10,
                                                                        1,
                                                                        "Normal",
                                                                        5L,
                                                                        "An authored entry"
                                                                            + " requirement message"
                                                                            + " from the client."),
                                                                new DungeonTypes.Difficulty(
                                                                        11, 2, "Heroic", 10L,
                                                                        null)),
                                                        List.of(),
                                                        List.of(
                                                                new DungeonTypes.Encounter(
                                                                        20,
                                                                        "Authored Gatekeeper",
                                                                        1L,
                                                                        "Normal",
                                                                        1L,
                                                                        1000L,
                                                                        null),
                                                                new DungeonTypes.Encounter(
                                                                        21,
                                                                        "Authored Sentinel",
                                                                        1L,
                                                                        "Normal",
                                                                        2L,
                                                                        1001L,
                                                                        null),
                                                                new DungeonTypes.Encounter(
                                                                        22,
                                                                        "Authored Sentinel",
                                                                        2L,
                                                                        "Heroic",
                                                                        1L,
                                                                        1001L,
                                                                        null)),
                                                        sources,
                                                        List.of(
                                                                "Readable encounter rows remain"
                                                                        + " available when another"
                                                                        + " section is protected."),
                                                        null));
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        closes.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        var view = new DungeonsView(() -> handle, () -> 1, "Authored build · enUS");
                        var root = new BorderPane(view);
                        root.setTop(
                                new Label(
                                        "AUTHORED DUNGEONS UI FIXTURE · no game assets or real"
                                                + " build data"));
                        var scene = new Scene(root, 1320, 850);
                        scene.getStylesheets()
                                .add(
                                        DungeonsUiTest.class
                                                .getResource("/vimi.css")
                                                .toExternalForm());
                        var stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        await(
                                () -> labels(view).contains("Recorded encounters"),
                                () -> {
                                    require(
                                            labels(view).stream()
                                                    .anyMatch(
                                                            s -> s.contains("Authored Greenwood")),
                                            "Associated area displayed");
                                    require(
                                            labels(view).contains("5 players"),
                                            "Recorded map capacity displayed");
                                    require(
                                            labels(view).stream()
                                                    .anyMatch(
                                                            s ->
                                                                    s.contains(
                                                                            "entrance coordinates"
                                                                                    + " are not"
                                                                                    + " supplied")),
                                            "Associated area is not misrepresented as entrance");
                                    @SuppressWarnings("unchecked")
                                    var list =
                                            (ListView<DungeonTypes.Dungeon>)
                                                    view.lookupAll(".list-view").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Build dungeon and raid records"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    var query =
                                            (TextField)
                                                    view.lookupAll(".text-field").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Search dungeons and raids"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    query.setText("33");
                                    require(list.getItems().size() == 1, "Map ID search");
                                    query.clear();
                                    @SuppressWarnings("unchecked")
                                    var encounterList =
                                            (ListView<DungeonTypes.Encounter>)
                                                    view.lookupAll(".list-view").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Recorded dungeon encounters"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    require(
                                            encounterList.getItems().size() == 3,
                                            "All readable encounter variants shown");
                                    var filter =
                                            (ComboBox<?>)
                                                    view.lookupAll(".combo-box").stream()
                                                            .filter(
                                                                    n ->
                                                                            "Recorded encounter difficulty"
                                                                                    .equals(
                                                                                            n
                                                                                                    .getAccessibleText()))
                                                            .findFirst()
                                                            .orElseThrow();
                                    filter.getSelectionModel().select(2);
                                    filter.fireEvent(new javafx.event.ActionEvent());
                                    require(
                                            encounterList.getItems().size() == 1,
                                            "Difficulty-specific encounter filtering");
                                    filter.getSelectionModel().selectFirst();
                                    filter.fireEvent(new javafx.event.ActionEvent());
                                    try {
                                        root.applyCss();
                                        root.layout();
                                        snapshot(scene, "dungeons-authored.png");
                                        var center = (BorderPane) view.getCenter();
                                        var scroller = (ScrollPane) center.getCenter();
                                        scroller.setVvalue(1);
                                        root.applyCss();
                                        root.layout();
                                        snapshot(scene, "dungeons-encounters-authored.png");
                                        stage.setWidth(1150);
                                        stage.setHeight(760);
                                        root.applyCss();
                                        root.layout();
                                        require(
                                                view.getWidth() > 900,
                                                "Resize retains useful content");
                                    } catch (Exception failure) {
                                        done.completeExceptionally(failure);
                                        stage.close();
                                        return;
                                    }
                                    list.getSelectionModel().select(raid);
                                    view.close();
                                    pending.complete(
                                            new DungeonTypes.Overview(
                                                    DungeonTypes.State.READY,
                                                    raid,
                                                    List.of(),
                                                    List.of(
                                                            new DungeonTypes.Description(
                                                                    "STALE DATA",
                                                                    "SHOULD NOT APPEAR",
                                                                    "Map",
                                                                    44,
                                                                    "fixture")),
                                                    List.of(),
                                                    List.of(),
                                                    List.of(),
                                                    sources,
                                                    List.of(),
                                                    null));
                                    Platform.runLater(
                                            () -> {
                                                try {
                                                    require(
                                                            labels(view).stream()
                                                                    .noneMatch(
                                                                            s ->
                                                                                    s.contains(
                                                                                            "SHOULD"
                                                                                                + " NOT APPEAR")),
                                                            "Stale selection suppressed");
                                                    require(
                                                            closes.get() == 0,
                                                            "View retains borrowed handle"
                                                                    + " lifetime");
                                                    require(
                                                            loads.get() == 1,
                                                            "Catalogue is reused");
                                                    stage.close();
                                                    done.complete(null);
                                                } catch (Throwable failure) {
                                                    stage.close();
                                                    done.completeExceptionally(failure);
                                                }
                                            });
                                },
                                done,
                                0);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        try {
            done.get(20, TimeUnit.SECONDS);
            loadingChecks.get(5, TimeUnit.SECONDS);
            System.out.println(
                    "Dungeons UI: partial metadata, area hierarchy, map-ID search, difficulty"
                            + " filters, resize and stale/borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            Runnable action,
            CompletableFuture<Void> done,
            int attempts) {
        if (done.isDone()) {
            return;
        }
        try {
            if (condition.getAsBoolean()) {
                action.run();
                return;
            }
            if (attempts > 100) {
                throw new AssertionError("UI readiness timed out");
            }
            var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
            pause.setOnFinished(e -> await(condition, action, done, attempts + 1));
            pause.play();
        } catch (Throwable failure) {
            done.completeExceptionally(failure);
        }
    }

    private static List<String> labels(javafx.scene.Parent parent) {
        return parent.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static void snapshot(Scene scene, String file) throws Exception {
        var image = scene.snapshot(null);
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        int[] pixels = new int[width * height];
        image.getPixelReader()
                .getPixels(
                        0,
                        0,
                        width,
                        height,
                        javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        pixels,
                        0,
                        width);
        var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        output.setRGB(0, 0, width, height, pixels, 0, width);
        var directory = Path.of(System.getProperty("vimi.uiReviewDirectory", "build/ui-review"));
        Files.createDirectories(directory);
        ImageIO.write(output, "png", directory.resolve(file).toFile());
    }

    private static long activityCount(javafx.scene.Node node) {
        if (!node.isVisible()) return 0;
        long count = node instanceof javafx.scene.control.ProgressIndicator ? 1 : 0;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) count += activityCount(child);
        return count;
    }

    private static boolean accessible(javafx.scene.Node node, String text) {
        if (text.equals(node.getAccessibleText())) return true;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable())
                if (accessible(child, text)) return true;
        return false;
    }

    private static boolean hasColor(javafx.scene.Node node) {
        if (node instanceof javafx.scene.image.ImageView image
                && image.getImage() != null
                && image.getImage().getPixelReader().getArgb(0, 0) == 0xff12aabb
                && image.getImage().getHeight() == 2
                && image.getImage().getPixelReader().getArgb(0, 1) == 0xff345678) return true;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) if (hasColor(child)) return true;
        return false;
    }

    private static void awaitColor(DungeonsView view, long deadline) {
        try {
            if (!hasColor(view) && System.nanoTime() < deadline) {
                var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(10));
                pause.setOnFinished(event -> awaitColor(view, deadline));
                pause.play();
                return;
            }
            require(hasColor(view), "Background RGBA conversion preserves uploaded color");
            view.close();
            require(activityCount(view) == 0, "Closing dungeon retires activity");
            loadingChecks.complete(null);
        } catch (Throwable error) {
            view.close();
            loadingChecks.completeExceptionally(error);
        }
    }

    private static void loadingFeedback() {
        var catalog = new CompletableFuture<DungeonTypes.Catalog>();
        var overview = new CompletableFuture<DungeonTypes.Overview>();
        var icon = new CompletableFuture<com.vibeminer.formats.images.BlpImageTypes.Result>();
        var colorIcon = new CompletableFuture<com.vibeminer.formats.images.BlpImageTypes.Result>();
        var dungeon =
                new DungeonTypes.Dungeon(
                        1, "Pending authored dungeon", DungeonTypes.Kind.DUNGEON, 5L, null, null);
        BuildDungeons handle =
                new BuildDungeons() {
                    public CompletableFuture<DungeonTypes.Catalog> catalog(Cancellation token) {
                        return catalog;
                    }

                    public CompletableFuture<DungeonTypes.Overview> overview(
                            long id, Cancellation token) {
                        return overview;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        return CompletableFuture.completedFuture(null);
                    }
                };
        var view =
                new DungeonsView(
                        () -> handle,
                        () -> 1,
                        "Authored pending fixture",
                        (id, token) -> id == 9 ? icon : colorIcon);
        new Scene(view, 1200, 1000);
        view.applyCss();
        view.layout();
        view.ensureLoaded();
        view.applyCss();
        view.layout();
        require(activityCount(view) > 0, "Pending dungeon catalog has local activity");
        catalog.complete(
                new DungeonTypes.Catalog(
                        DungeonTypes.State.READY, List.of(dungeon), List.of(), List.of(), null));
        Platform.runLater(
                () -> {
                    try {
                        require(
                                activityCount(view) > 0,
                                "Pending dungeon overview keeps local activity");
                        overview.complete(
                                new DungeonTypes.Overview(
                                        DungeonTypes.State.PARTIAL,
                                        dungeon,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(
                                                new DungeonTypes.Encounter(
                                                        1,
                                                        "Pending encounter",
                                                        null,
                                                        null,
                                                        1L,
                                                        null,
                                                        9L),
                                                new DungeonTypes.Encounter(
                                                        2,
                                                        "Authored color",
                                                        null,
                                                        null,
                                                        2L,
                                                        null,
                                                        10L)),
                                        List.of(),
                                        List.of(),
                                        null));
                        Platform.runLater(
                                () -> {
                                    try {
                                        view.applyCss();
                                        view.layout();
                                        require(
                                                accessible(view, "Loading encounter icon"),
                                                "Pending encounter icon is explicit and"
                                                        + " accessible");
                                        icon.completeExceptionally(
                                                new IllegalStateException("Authored icon failure"));
                                        var pixels =
                                                new com.vibeminer.formats.images.Blp2Types.Image(
                                                        1,
                                                        2,
                                                        8,
                                                        new byte[] {
                                                            0x12,
                                                            (byte) 0xaa,
                                                            (byte) 0xbb,
                                                            (byte) 0xff,
                                                            0,
                                                            0,
                                                            0,
                                                            0,
                                                            0x34,
                                                            0x56,
                                                            0x78,
                                                            (byte) 0xff,
                                                            0,
                                                            0,
                                                            0,
                                                            0
                                                        },
                                                        null,
                                                        8,
                                                        0,
                                                        0,
                                                        1,
                                                        2,
                                                        List.of(),
                                                        0);
                                        colorIcon.complete(
                                                new com.vibeminer.formats.images.BlpImageTypes
                                                        .Result(
                                                        com.vibeminer.formats.images.BlpImageTypes
                                                                .State.READY,
                                                        pixels,
                                                        com.vibeminer.formats.images.BlpImageTypes
                                                                .Cache.NONE,
                                                        List.of(),
                                                        null,
                                                        null,
                                                        null,
                                                        null));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                accessible(
                                                                        view,
                                                                        "Encounter icon"
                                                                                + " unavailable"),
                                                                "Failed encounter icon is"
                                                                    + " explicitly unavailable");
                                                        awaitColor(
                                                                view,
                                                                System.nanoTime()
                                                                        + TimeUnit.SECONDS.toNanos(
                                                                                3));
                                                    } catch (Throwable error) {
                                                        view.close();
                                                        loadingChecks.completeExceptionally(error);
                                                    }
                                                });
                                    } catch (Throwable error) {
                                        view.close();
                                        loadingChecks.completeExceptionally(error);
                                    }
                                });
                    } catch (Throwable error) {
                        view.close();
                        loadingChecks.completeExceptionally(error);
                    }
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
