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
    private static final String TOKEN_WARNING =
            "Raw client spell text; substitutions are not evaluated.";

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
                                                                        id == 2
                                                                                ? List.of(
                                                                                        new TalentTypes
                                                                                                .Prerequisite(
                                                                                                999,
                                                                                                0))
                                                                                : prerequisites,
                                                                        "Fixture talent " + id,
                                                                        id == 2
                                                                                ? null
                                                                                : id == 4
                                                                                        ? "Authored"
                                                                                              + " $s1 text"
                                                                                        : "Authored"
                                                                                              + " spell"
                                                                                              + " text",
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
                        var scene =
                                new Scene(
                                        page,
                                        args.length == 0 ? 1600 : Double.parseDouble(args[0]),
                                        950);
                        scene.getStylesheets()
                                .add(TalentsUiTest.class.getResource("/vimi.css").toExternalForm());
                        var stage = new Stage();
                        stage.setScene(scene);
                        stage.show();
                        view.ensureLoaded();
                        var selectPause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(300));
                        selectPause.setOnFinished(
                                event -> {
                                    var unavailable =
                                            scene.getRoot().lookupAll(".talent-node").stream()
                                                    .map(
                                                            value ->
                                                                    (javafx.scene.control.Button)
                                                                            value)
                                                    .filter(
                                                            value ->
                                                                    value.getAccessibleText()
                                                                            .startsWith(
                                                                                    "Fixture talent"
                                                                                        + " 2,"))
                                                    .findFirst()
                                                    .orElseThrow();
                                    unavailable.fire();
                                    var missingText =
                                            scene
                                                    .getRoot()
                                                    .lookup(".talent-tooltip")
                                                    .lookupAll(".label")
                                                    .stream()
                                                    .map(
                                                            value ->
                                                                    ((javafx.scene.control.Label)
                                                                                    value)
                                                                            .getText())
                                                    .toList();
                                    if (!missingText.contains(
                                                    "Description unavailable in the readable data.")
                                            || !missingText.contains(
                                                    "Requires unavailable talent #999 · rank 1"))
                                        throw new AssertionError(
                                                "Unreadable talent metadata must remain explicit in"
                                                        + " the tooltip");
                                    var tokenNode =
                                            scene.getRoot().lookupAll(".talent-node").stream()
                                                    .map(
                                                            value ->
                                                                    (javafx.scene.control.Button)
                                                                            value)
                                                    .filter(
                                                            value ->
                                                                    value.getAccessibleText()
                                                                            .startsWith(
                                                                                    "Fixture talent"
                                                                                        + " 4,"))
                                                    .findFirst()
                                                    .orElseThrow();
                                    tokenNode.fire();
                                    if (scene.getRoot().lookupAll(".label").stream()
                                            .map(
                                                    value ->
                                                            ((javafx.scene.control.Label) value)
                                                                    .getText())
                                            .noneMatch(TOKEN_WARNING::equals))
                                        throw new AssertionError(
                                                "Unresolved client substitutions need a warning"
                                                    + " outside the tooltip");
                                    var node =
                                            scene.getRoot().lookupAll(".talent-node").stream()
                                                    .map(
                                                            value ->
                                                                    (javafx.scene.control.Button)
                                                                            value)
                                                    .filter(
                                                            value ->
                                                                    value.getAccessibleText()
                                                                            .startsWith(
                                                                                    "Fixture talent"
                                                                                        + " 1,"))
                                                    .findFirst()
                                                    .orElseThrow();
                                    node.fire();
                                });
                        selectPause.play();
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
                                        var tooltip = scene.getRoot().lookup(".talent-tooltip");
                                        var tooltipText =
                                                tooltip.lookupAll(".label").stream()
                                                        .map(
                                                                value ->
                                                                        ((javafx.scene.control
                                                                                                .Label)
                                                                                        value)
                                                                                .getText())
                                                        .toList();
                                        if (!tooltipText.contains("Fixture talent 1")
                                                || !tooltipText.contains("Rank 1/3")
                                                || !tooltipText.contains("Authored spell text"))
                                            throw new AssertionError(
                                                    "Selected talent details did not reach the"
                                                            + " right tooltip");
                                        if (scene.getRoot().lookupAll(".label").stream()
                                                .map(
                                                        value ->
                                                                ((javafx.scene.control.Label) value)
                                                                        .getText())
                                                .anyMatch(TOKEN_WARNING::equals))
                                            throw new AssertionError(
                                                    "Plain descriptions must not show an"
                                                        + " unresolved-token warning");
                                        var right = view.getRight();
                                        if (right.getBoundsInParent().getMinX()
                                                        <= view.getCenter()
                                                                .getBoundsInParent()
                                                                .getMinX()
                                                || right.getBoundsInParent().getWidth() != 320)
                                            throw new AssertionError(
                                                    "Tooltip must remain in the fixed right detail"
                                                            + " rail");
                                        for (var canvas :
                                                scene.getRoot().lookupAll(".talent-canvas"))
                                            if (canvas.getBoundsInLocal().getWidth() < 280)
                                                throw new AssertionError(
                                                        "Talent trees must retain readable width");
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
                                                        "vimi-talents-tooltip"
                                                                + (args.length == 0
                                                                        ? ""
                                                                        : "-" + args[0])
                                                                + ".png");
                                        ImageIO.write(out, "png", path.toFile());
                                        view.invalidate();
                                        if (scene
                                                .getRoot()
                                                .lookup(".talent-tooltip")
                                                .lookupAll(".label")
                                                .stream()
                                                .map(
                                                        value ->
                                                                ((javafx.scene.control.Label) value)
                                                                        .getText())
                                                .noneMatch("Select a talent"::equals))
                                            throw new AssertionError(
                                                    "Workspace invalidation must clear the old"
                                                            + " selected talent");
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
