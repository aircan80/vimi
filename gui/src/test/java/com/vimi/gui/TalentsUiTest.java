package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.BuildTalents;
import com.vibeminer.concepts.talents.TalentTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/** Optional authored layout review. Does not open a native store or acquire any game data. */
public final class TalentsUiTest {
    private TalentsUiTest() {}

    private static Long fixtureIconId(long id) {
        if (id == 1) return 900L;
        if (id == 2) return 901L;
        return null;
    }

    private static com.vibeminer.formats.images.BlpImageTypes.Result authoredIcon() {
        var image =
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
        return new com.vibeminer.formats.images.BlpImageTypes.Result(
                com.vibeminer.formats.images.BlpImageTypes.State.READY,
                image,
                com.vibeminer.formats.images.BlpImageTypes.Cache.NONE,
                List.of(),
                null,
                null,
                null,
                null);
    }

    public static void main(String[] args) throws Exception {
        var ready = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        var player = new TalentTypes.PlayerClass(1, "Warrior", null, null);
                        BuildTalents fixture =
                                new BuildTalents() {
                                    public CompletableFuture<TalentTypes.Catalog> catalog(
                                            Cancellation token) {
                                        return CompletableFuture.completedFuture(
                                                new TalentTypes.Catalog(
                                                        TalentTypes.State.PARTIAL,
                                                        List.of(player),
                                                        "TALENTS_PARTIAL",
                                                        "Authored layout fixture · not a real"
                                                                + " build"));
                                    }

                                    public CompletableFuture<TalentTypes.ClassTrees> trees(
                                            long classId, Cancellation token) {
                                        var trees = new ArrayList<TalentTypes.Tree>();
                                        for (int tree = 0; tree < 3; tree++) {
                                            var nodes = new ArrayList<TalentTypes.Talent>();
                                            for (int row = 0; row < 7; row++)
                                                for (int column = 0; column < 4; column++)
                                                    if ((row + column) % 3 != 2) {
                                                        long id = 1 + tree * 100 + row * 4 + column;
                                                        var ranks =
                                                                row % 2 == 0
                                                                        ? List.of(
                                                                                1000 + id,
                                                                                2000 + id,
                                                                                3000 + id)
                                                                        : List.of(1000 + id);
                                                        var prerequisites =
                                                                row > 0
                                                                                && column == 0
                                                                                && row % 3 == 0
                                                                        ? List.of(
                                                                                new TalentTypes
                                                                                        .Prerequisite(
                                                                                        id - 8, 0))
                                                                        : List
                                                                                .<TalentTypes
                                                                                                .Prerequisite>
                                                                                        of();
                                                        nodes.add(
                                                                new TalentTypes.Talent(
                                                                        id,
                                                                        row,
                                                                        column,
                                                                        ranks,
                                                                        prerequisites,
                                                                        "Fixture talent " + id,
                                                                        "Authored spell text",
                                                                        fixtureIconId(id)));
                                                    }
                                            trees.add(
                                                    new TalentTypes.Tree(
                                                            tree,
                                                            List.of("Arms", "Fury", "Protection")
                                                                    .get(tree),
                                                            tree,
                                                            "",
                                                            nodes));
                                        }
                                        return CompletableFuture.completedFuture(
                                                new TalentTypes.ClassTrees(
                                                        TalentTypes.State.PARTIAL,
                                                        player,
                                                        trees,
                                                        List.of(),
                                                        null,
                                                        null));
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        // Publish another workspace between reading the handle and its revision.
                        // The first catalog reply must not populate the newer workspace's menu.
                        BuildTalents retired =
                                new BuildTalents() {
                                    public CompletableFuture<TalentTypes.Catalog> catalog(
                                            Cancellation token) {
                                        return fixture.catalog(token);
                                    }

                                    public CompletableFuture<TalentTypes.ClassTrees> trees(
                                            long id, Cancellation token) {
                                        return fixture.trees(id, token);
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                };
                        var swapped = new java.util.concurrent.atomic.AtomicBoolean();
                        var staleView =
                                new TalentsView(
                                        () -> swapped.getAndSet(true) ? fixture : retired,
                                        () -> 2,
                                        (id, token) ->
                                                CompletableFuture.failedFuture(
                                                        new AssertionError("No media")));
                        staleView.ensureLoaded();
                        var requestedIcons =
                                java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
                        var view =
                                new TalentsView(
                                        () -> fixture,
                                        () -> 1,
                                        (id, token) -> {
                                            requestedIcons.add(id);
                                            if (id == 900)
                                                return CompletableFuture.completedFuture(
                                                        authoredIcon());
                                            return CompletableFuture.failedFuture(
                                                    new IllegalStateException(
                                                            "Authored unavailable icon"));
                                        });
                        var page = new BorderPane(view);
                        var heading =
                                new Label(
                                        "AUTHORED TALENT LAYOUT FIXTURE · no character or real"
                                                + " build data");
                        heading.setStyle("-fx-padding: 12; -fx-text-fill: #88611f;");
                        page.setTop(heading);
                        var scene = new Scene(page, 1320, 850);
                        scene.getStylesheets()
                                .add(TalentsUiTest.class.getResource("/vimi.css").toExternalForm());
                        var stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        var pause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(500));
                        pause.setOnFinished(
                                event -> {
                                    try {
                                        var staleMenu =
                                                (javafx.scene.control.ListView<?>)
                                                        ((javafx.scene.layout.VBox)
                                                                        staleView.getLeft())
                                                                .getChildren()
                                                                .getLast();
                                        if (!staleMenu.getItems().isEmpty())
                                            throw new AssertionError(
                                                    "Retired catalog populated the new workspace");
                                        staleView.close();
                                        if (!requestedIcons.equals(java.util.Set.of(900L, 901L)))
                                            throw new AssertionError(
                                                    "Visible talent icon references were not"
                                                            + " requested: "
                                                            + requestedIcons);
                                        long rendered =
                                                scene.getRoot().lookupAll(".talent-node").stream()
                                                        .map(
                                                                node ->
                                                                        ((javafx.scene.control
                                                                                                .Button)
                                                                                        node)
                                                                                .getGraphic())
                                                        .map(
                                                                graphic ->
                                                                        (javafx.scene.layout
                                                                                        .StackPane)
                                                                                graphic)
                                                        .flatMap(
                                                                graphic ->
                                                                        graphic
                                                                                .getChildren()
                                                                                .stream())
                                                        .filter(
                                                                node ->
                                                                        node
                                                                                instanceof
                                                                                javafx.scene.image
                                                                                        .ImageView)
                                                        .map(
                                                                node ->
                                                                        (javafx.scene.image
                                                                                        .ImageView)
                                                                                node)
                                                        .filter(
                                                                icon ->
                                                                        icon.getImage()
                                                                                        .getPixelReader()
                                                                                        .getArgb(
                                                                                                0,
                                                                                                0)
                                                                                == 0xff12aabb)
                                                        .count();
                                        if (rendered != 1)
                                            throw new AssertionError(
                                                    "Decoded RGBA icon did not render; unavailable"
                                                            + " icons must remain placeholders");
                                        var image = scene.snapshot(null);
                                        var out =
                                                new BufferedImage(
                                                        (int) image.getWidth(),
                                                        (int) image.getHeight(),
                                                        BufferedImage.TYPE_INT_ARGB);
                                        var reader = image.getPixelReader();
                                        for (int y = 0; y < out.getHeight(); y++)
                                            for (int x = 0; x < out.getWidth(); x++)
                                                out.setRGB(x, y, reader.getArgb(x, y));
                                        Path path =
                                                Path.of(
                                                        System.getProperty("java.io.tmpdir"),
                                                        "vimi-talents.png");
                                        ImageIO.write(out, "png", path.toFile());
                                        view.close();
                                        stage.close();
                                        ready.complete(null);
                                    } catch (Throwable error) {
                                        ready.completeExceptionally(error);
                                    } finally {
                                        Platform.exit();
                                    }
                                });
                        pause.play();
                    } catch (Throwable error) {
                        ready.completeExceptionally(error);
                        Platform.exit();
                    }
                });
        ready.get(20, TimeUnit.SECONDS);
        System.out.println("Talents layout fixture rendered to system temporary directory");
    }
}
