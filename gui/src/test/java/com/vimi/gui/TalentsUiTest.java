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
                                                        TalentTypes.State.READY,
                                                        List.of(player),
                                                        null,
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
                                                                        null));
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
                                                        TalentTypes.State.READY,
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
                        var view =
                                new TalentsView(
                                        () -> fixture,
                                        () -> 1,
                                        (id, token) ->
                                                CompletableFuture.failedFuture(
                                                        new AssertionError(
                                                                "Fixture media must not be"
                                                                    + " requested")));
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
