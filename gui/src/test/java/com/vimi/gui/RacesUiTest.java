package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.races.BuildRaces;
import com.vibeminer.concepts.races.RaceTypes;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javax.imageio.ImageIO;

/**
 * Authored data only: layout, stale selection, readable partial cards and borrowed handle lifetime.
 */
public final class RacesUiTest {
    private RacesUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        var human = new RaceTypes.Race(1, "Authored Human", 1, 2);
                        var internal = new RaceTypes.Race(99, "Internal record", 99, 0);
                        var pending = new CompletableFuture<RaceTypes.Overview>();
                        var closes = new java.util.concurrent.atomic.AtomicInteger();
                        BuildRaces handle =
                                new BuildRaces() {
                                    public CompletableFuture<RaceTypes.Catalog> catalog(
                                            Cancellation token) {
                                        return CompletableFuture.completedFuture(
                                                new RaceTypes.Catalog(
                                                        RaceTypes.State.PARTIAL,
                                                        List.of(human, internal),
                                                        List.of("Authored partial fixture"),
                                                        null));
                                    }

                                    public CompletableFuture<RaceTypes.Overview> overview(
                                            long id, Cancellation token) {
                                        if (id == 99) return pending;
                                        return CompletableFuture.completedFuture(
                                                new RaceTypes.Overview(
                                                        RaceTypes.State.PARTIAL,
                                                        human,
                                                        "An authored lore paragraph used to review layout. This screen does not contain real game build data.",
                                                        10L,
                                                        "Authored faction",
                                                        List.of(
                                                                new RaceTypes.PlayerClass(
                                                                        1, "Warrior"),
                                                                new RaceTypes.PlayerClass(
                                                                        2, "Mage")),
                                                        List.of(
                                                                new RaceTypes.Ability(
                                                                        101,
                                                                        "Authored Diplomacy",
                                                                        "Recorded racial description remains usable when another section is protected.",
                                                                        900L,
                                                                        0),
                                                                new RaceTypes.Ability(
                                                                        102,
                                                                        "Authored racial",
                                                                        "A second recorded description.",
                                                                        null,
                                                                        1)),
                                                        List.of("Some source rows are protected."),
                                                        null));
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        closes.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        var decoded =
                                new com.vibeminer.formats.images.Blp2Types.Image(
                                        1,
                                        1,
                                        4,
                                        new byte[] {0x12, (byte) 0xaa, (byte) 0xbb, (byte) 0xff},
                                        null,
                                        8,
                                        0,
                                        0,
                                        1,
                                        1,
                                        List.of(),
                                        0);
                        var image =
                                new com.vibeminer.formats.images.BlpImageTypes.Result(
                                        com.vibeminer.formats.images.BlpImageTypes.State.READY,
                                        decoded,
                                        com.vibeminer.formats.images.BlpImageTypes.Cache.NONE,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null);
                        var revision = new java.util.concurrent.atomic.AtomicLong(1);
                        var view =
                                new RacesView(
                                        () -> handle,
                                        revision::get,
                                        (id, token) -> CompletableFuture.completedFuture(image));
                        BorderPane root = new BorderPane(view);
                        root.setTop(
                                new Label(
                                        "AUTHORED RACES LAYOUT FIXTURE · no character or real build data"));
                        double width =
                                Double.parseDouble(
                                        System.getProperty("vimi.racesReviewWidth", "1320"));
                        Scene scene = new Scene(root, width, 900);
                        scene.getStylesheets()
                                .add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        Stage stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        var pause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(500));
                        pause.setOnFinished(
                                event -> {
                                    try {
                                        var list = (ListView<?>) view.lookup(".list-view");
                                        require(
                                                list.getItems().size() == 1,
                                                "Default sidebar only shows recorded class links");
                                        require(
                                                labels(view).contains("Authored Diplomacy"),
                                                "Readable partial racial card displayed");
                                        require(
                                                !view.lookupAll(".image-view").isEmpty()
                                                        || descendantsImage(view),
                                                "Racial image rendered");
                                        var imageSnapshot = scene.snapshot(null);
                                        var out =
                                                new BufferedImage(
                                                        (int) imageSnapshot.getWidth(),
                                                        (int) imageSnapshot.getHeight(),
                                                        BufferedImage.TYPE_INT_ARGB);
                                        for (int y = 0; y < out.getHeight(); y++)
                                            for (int x = 0; x < out.getWidth(); x++)
                                                out.setRGB(
                                                        x,
                                                        y,
                                                        imageSnapshot
                                                                .getPixelReader()
                                                                .getArgb(x, y));
                                        Path directory =
                                                Path.of(
                                                        System.getProperty(
                                                                "vimi.uiReviewDirectory",
                                                                "gui/build/ui-review"));
                                        Files.createDirectories(directory);
                                        ImageIO.write(
                                                out,
                                                "png",
                                                directory.resolve("races-authored.png").toFile());
                                        var check = (CheckBox) view.lookup(".check-box");
                                        check.fire();
                                        require(
                                                list.getItems().size() == 2,
                                                "Other internal records remain inspectable");
                                        list.getSelectionModel().select(1);
                                        view.invalidate();
                                        pending.complete(
                                                new RaceTypes.Overview(
                                                        RaceTypes.State.READY,
                                                        internal,
                                                        "STALE OVERVIEW",
                                                        null,
                                                        null,
                                                        List.of(),
                                                        List.of(),
                                                        List.of(),
                                                        null));
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                !labels(view)
                                                                        .contains("STALE OVERVIEW"),
                                                                "Stale overview cannot repopulate invalidated view");
                                                        view.close();
                                                        require(
                                                                closes.get() == 0,
                                                                "GUI borrows domain handle");
                                                        stage.close();
                                                        done.complete(null);
                                                    } catch (Throwable failure) {
                                                        done.completeExceptionally(failure);
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        done.completeExceptionally(failure);
                                    }
                                });
                        pause.play();
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        try {
            done.get(20, TimeUnit.SECONDS);
            System.out.println(
                    "Races UI: partial data, icons, internal-record filter, stale selection and borrowed lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static boolean descendantsImage(javafx.scene.Node node) {
        if (node instanceof ImageView) return true;
        if (node instanceof javafx.scene.Parent parent)
            for (var child : parent.getChildrenUnmodifiable())
                if (descendantsImage(child)) return true;
        return false;
    }

    private static List<String> labels(javafx.scene.Parent parent) {
        return parent.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
