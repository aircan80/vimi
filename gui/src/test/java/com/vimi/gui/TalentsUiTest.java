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
            "Some spell values are unavailable; raw text is in Source details.";

    private TalentsUiTest() {}

    private static String fixtureDescription(long id) {
        if (id == 2) return null;
        if (id == 4) return "Authored chance rises by $s1%.";
        if (id == 5) return "Authored $d text";
        return "Authored spell text";
    }

    private static Long fixtureIconId(long id) {
        if (id == 4) return 900L;
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

    private static javafx.scene.control.Button talent(Scene scene, long id) {
        return scene.getRoot().lookupAll(".talent-node").stream()
                .map(value -> (javafx.scene.control.Button) value)
                .filter(value -> value.getAccessibleText().startsWith("Fixture talent " + id + ","))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> tooltipText(Scene scene) {
        return scene.getRoot().lookup(".talent-tooltip").lookupAll(".label").stream()
                .map(value -> ((Label) value).getText())
                .toList();
    }

    public static void main(String[] args) throws Exception {
        var ready = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        var catalogGate = new CompletableFuture<Void>();
                        var treesGate = new CompletableFuture<Void>();
                        var descriptionGate = new CompletableFuture<Void>();
                        var iconGate =
                                new CompletableFuture<
                                        com.vibeminer.formats.images.BlpImageTypes.Result>();
                        var player = new TalentTypes.PlayerClass(1, "Warrior", null, null);
                        BuildTalents fixture =
                                new BuildTalents() {
                                    public CompletableFuture<TalentTypes.Catalog> catalog(
                                            Cancellation token) {
                                        return catalogGate.thenApply(
                                                ignored ->
                                                        new TalentTypes.Catalog(
                                                                TalentTypes.State.PARTIAL,
                                                                List.of(player),
                                                                "TALENTS_PARTIAL",
                                                                "Authored layout fixture · not a"
                                                                    + " real build"));
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
                                                                        fixtureDescription(id),
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
                                        return treesGate.thenApply(
                                                ignored ->
                                                        new TalentTypes.ClassTrees(
                                                                TalentTypes.State.PARTIAL,
                                                                player,
                                                                trees,
                                                                List.of(),
                                                                null,
                                                                null));
                                    }

                                    public CompletableFuture<
                                                    com.vibeminer.concepts.effects.SpellTexts
                                                            .Description>
                                            description(long spell, Cancellation token) {
                                        boolean resolved = spell == 1004;
                                        return (resolved
                                                        ? descriptionGate
                                                        : CompletableFuture.<Void>completedFuture(
                                                                null))
                                                .thenApply(
                                                        ignored ->
                                                                new com.vibeminer.concepts.effects
                                                                        .SpellTexts.Description(
                                                                        spell,
                                                                        resolved
                                                                                ? "Authored chance"
                                                                                      + " rises by"
                                                                                      + " $s1%."
                                                                                : "Authored $d"
                                                                                      + " text",
                                                                        resolved
                                                                                ? "Authored chance"
                                                                                      + " rises by"
                                                                                      + " 5%."
                                                                                : "Authored ? text",
                                                                        resolved
                                                                                ? com.vibeminer
                                                                                        .concepts
                                                                                        .effects
                                                                                        .SpellTexts
                                                                                        .State
                                                                                        .RESOLVED
                                                                                : com.vibeminer
                                                                                        .concepts
                                                                                        .effects
                                                                                        .SpellTexts
                                                                                        .State
                                                                                        .UNRESOLVED,
                                                                        List.of(),
                                                                        List.of()));
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
                                            if (id == 900) return iconGate;
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
                        if (!scene.getRoot().lookup("#talents-activity").isVisible())
                            throw new AssertionError("Delayed catalog has animated activity");
                        catalogGate.complete(null);
                        Platform.runLater(
                                () -> {
                                    try {
                                        if (!scene.getRoot()
                                                .lookup("#talents-activity")
                                                .isVisible())
                                            throw new AssertionError(
                                                    "Delayed trees have animated activity");
                                        treesGate.complete(null);
                                    } catch (Throwable error) {
                                        ready.completeExceptionally(error);
                                        Platform.exit();
                                    }
                                });
                        var selectPause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(300));
                        selectPause.setOnFinished(
                                event -> {
                                    try {
                                        talent(scene, 2).fire();
                                        if (!tooltipText(scene)
                                                        .contains(
                                                                "Description unavailable in the"
                                                                        + " readable data.")
                                                || !tooltipText(scene)
                                                        .contains(
                                                                "Requires unavailable talent #999 ·"
                                                                        + " rank 1"))
                                            throw new AssertionError(
                                                    "Missing description/prerequisite must remain"
                                                            + " explicit");
                                        talent(scene, 5).fire();
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        if (!tooltipText(scene)
                                                                        .contains("Authored ? text")
                                                                || scene
                                                                        .getRoot()
                                                                        .lookupAll(".label")
                                                                        .stream()
                                                                        .map(
                                                                                value ->
                                                                                        ((Label)
                                                                                                        value)
                                                                                                .getText())
                                                                        .noneMatch(
                                                                                TOKEN_WARNING
                                                                                        ::equals))
                                                            throw new AssertionError(
                                                                    "Unknown values need safe"
                                                                            + " placeholders and a"
                                                                            + " warning outside the"
                                                                            + " tooltip");
                                                        talent(scene, 4).fire();
                                                        if (!scene.getRoot()
                                                                .lookup(
                                                                        "#talent-description-activity")
                                                                .isVisible())
                                                            throw new AssertionError(
                                                                    "Delayed description has"
                                                                        + " activity");
                                                        if (scene
                                                                .getRoot()
                                                                .lookupAll(".progress-indicator")
                                                                .stream()
                                                                .noneMatch(
                                                                        n ->
                                                                                "Loading talent icon"
                                                                                                .equals(
                                                                                                        n
                                                                                                                .getAccessibleText())
                                                                                        && n
                                                                                                .isVisible()))
                                                            throw new AssertionError(
                                                                    "Delayed icon has pending"
                                                                        + " state");
                                                        descriptionGate.complete(null);
                                                        iconGate.complete(authoredIcon());
                                                    } catch (Throwable failure) {
                                                        ready.completeExceptionally(failure);
                                                        Platform.exit();
                                                    }
                                                });
                                    } catch (Throwable failure) {
                                        ready.completeExceptionally(failure);
                                        Platform.exit();
                                    }
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
                                        if (!tooltipText.contains("Fixture talent 4")
                                                || !tooltipText.contains("Rank 1/3")
                                                || !tooltipText.contains(
                                                        "Authored chance rises by 5%."))
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
                                                        "vimi-talents-spell-text"
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
