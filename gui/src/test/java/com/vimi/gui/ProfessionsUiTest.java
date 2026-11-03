package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.professions.BuildProfessions;
import com.vibeminer.concepts.professions.ProfessionTypes;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

/** Authored recipe facts only: visible partial rows, stale selection and borrowed lifetime. */
public final class ProfessionsUiTest {
    private ProfessionsUiTest() {}

    private static final AtomicInteger CLOSES = new AtomicInteger();
    private static final AtomicLong REVISION = new AtomicLong(1);
    private static final CompletableFuture<ProfessionTypes.Detail> DELAYED =
            new CompletableFuture<>();
    private static final ProfessionTypes.Ability POTION =
            new ProfessionTypes.Ability(1, 171, 100, "Authored restorative tonic", null, 25L, 40L);
    private static final ProfessionTypes.Ability ENCHANT =
            new ProfessionTypes.Ability(2, 171, 101, "Authored enchantment", null, 40L, 40L);
    private static final BuildProfessions HANDLE =
            new BuildProfessions() {
                @Override
                public CompletableFuture<ProfessionTypes.Catalog> catalog(Cancellation token) {
                    return CompletableFuture.completedFuture(
                            new ProfessionTypes.Catalog(
                                    ProfessionTypes.State.PARTIAL,
                                    List.of(
                                            new ProfessionTypes.Profession(
                                                    171,
                                                    "Authored Alchemy",
                                                    "Invented research fixture",
                                                    11,
                                                    "Primary",
                                                    null,
                                                    0,
                                                    2,
                                                    true),
                                            new ProfessionTypes.Profession(
                                                    182,
                                                    "Authored Herbalism",
                                                    "Gathering fixture",
                                                    11,
                                                    "Primary",
                                                    null,
                                                    0,
                                                    0,
                                                    true)),
                                    List.of(),
                                    List.of("Authored protected source section"),
                                    null));
                }

                @Override
                public CompletableFuture<ProfessionTypes.Page> abilities(
                        long professionId,
                        String query,
                        long after,
                        int limit,
                        Cancellation token) {
                    return CompletableFuture.completedFuture(
                            new ProfessionTypes.Page(
                                    ProfessionTypes.State.PARTIAL,
                                    professionId == 171 ? List.of(POTION, ENCHANT) : List.of(),
                                    null,
                                    List.of("Authored partial source"),
                                    null));
                }

                @Override
                public CompletableFuture<ProfessionTypes.Detail> detail(
                        long id, Cancellation token) {
                    return id == 2 ? DELAYED : CompletableFuture.completedFuture(potion());
                }

                @Override
                public CompletableFuture<Void> closeAsync() {
                    CLOSES.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };

    private static ProfessionTypes.Detail potion() {
        return new ProfessionTypes.Detail(
                ProfessionTypes.State.PARTIAL,
                POTION,
                new ProfessionTypes.SkillFacts(25L, 50L, 100L, 1L, 0L, 0L, List.of(), List.of()),
                List.of(
                        new ProfessionTypes.Ingredient(0, 2447, 2L, List.of()),
                        new ProfessionTypes.Ingredient(1, 765, 1L, List.of())),
                ProfessionTypes.State.PARTIAL,
                List.of(new ProfessionTypes.Tool(0, 6217L, 1L, List.of())),
                ProfessionTypes.State.READY,
                List.of(
                        new ProfessionTypes.Result(
                                ProfessionTypes.ResultKind.ITEM,
                                118L,
                                null,
                                null,
                                new java.math.BigDecimal("2"),
                                List.of(),
                                "Authored unknown yield")),
                ProfessionTypes.State.PARTIAL,
                List.of("Authored source section is protected."),
                null);
    }

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        ProfessionsView view =
                fx(
                        () ->
                                new ProfessionsView(
                                        () -> HANDLE,
                                        REVISION::get,
                                        (id, token) ->
                                                CompletableFuture.failedFuture(
                                                        new IllegalStateException(
                                                                "Authored fixture has no"
                                                                        + " assets"))));
        Stage stage =
                fx(
                        () -> {
                            var root = new BorderPane(view);
                            root.setTop(
                                    new Label(
                                            "AUTHORED PROFESSIONS FIXTURE · no game assets or"
                                                    + " local-library data"));
                            var scene = new Scene(root, 1320, 850);
                            scene.getStylesheets()
                                    .add(
                                            ProfessionsUiTest.class
                                                    .getResource("/vimi.css")
                                                    .toExternalForm());
                            var result = new Stage();
                            result.setScene(scene);
                            result.show();
                            view.ensureLoaded();
                            return result;
                        });
        try {
            await(() -> labels(view).contains("Quantity not resolved"));
            fx(
                    () -> {
                        require(
                                labels(view).contains("No verified source relationship"),
                                "Unknown acquisition must be explicit");
                        require(
                                labels(view).contains("2 × Item #2447"),
                                "Readable partial reagent is visible");
                        require(
                                labels(view).contains("Trivial thresholds 50 / 100"),
                                "Recorded thresholds are visible");
                        var image = view.getScene().snapshot(null);
                        var buffered =
                                new BufferedImage(
                                        (int) image.getWidth(),
                                        (int) image.getHeight(),
                                        BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < buffered.getHeight(); y++)
                            for (int x = 0; x < buffered.getWidth(); x++)
                                buffered.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                        Path output =
                                Path.of(
                                        System.getProperty(
                                                "vimi.uiReviewDirectory", "gui/build/ui-review"));
                        Files.createDirectories(output);
                        ImageIO.write(
                                buffered,
                                "png",
                                output.resolve("professions-authored.png").toFile());
                        table(view).getSelectionModel().select(1);
                        table(view).getSelectionModel().select(0);
                        return null;
                    });
            await(() -> labels(view).contains("Quantity not resolved"));
            DELAYED.complete(
                    new ProfessionTypes.Detail(
                            ProfessionTypes.State.READY,
                            ENCHANT,
                            null,
                            List.of(),
                            ProfessionTypes.State.READY,
                            List.of(),
                            ProfessionTypes.State.READY,
                            List.of(
                                    new ProfessionTypes.Result(
                                            ProfessionTypes.ResultKind.ENCHANTMENT,
                                            null,
                                            15L,
                                            null,
                                            null,
                                            List.of(),
                                            null)),
                            ProfessionTypes.State.READY,
                            List.of(),
                            null));
            fx(
                    () -> {
                        require(
                                !labels(view).contains("Enchantment #15"),
                                "Stale detail must not replace current selection");
                        stage.setWidth(980);
                        return null;
                    });
            await(
                    () ->
                            buttons(view).stream()
                                    .anyMatch(b -> b.getText().equals("Details") && b.isVisible()));
            fx(
                    () -> {
                        stage.setWidth(1320);
                        REVISION.incrementAndGet();
                        view.ensureLoaded();
                        return null;
                    });
            await(() -> labels(view).contains("Quantity not resolved"));
            fx(
                    () -> {
                        list(view).getSelectionModel().select(1);
                        return null;
                    });
            await(() -> table(view).getItems().isEmpty());
            fx(
                    () -> {
                        require(CLOSES.get() == 0, "View must borrow component");
                        view.close();
                        view.close();
                        require(CLOSES.get() == 0, "Closing view must not close component");
                        return null;
                    });
            System.out.println(
                    "Professions UI: partial facts, unknown yield/acquisition, stale detail,"
                            + " revision, resize and borrowed lifetime passed.");
        } finally {
            fx(
                    () -> {
                        view.close();
                        stage.close();
                        return null;
                    });
            Platform.exit();
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(action.call());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (fx(condition)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for authored UI state");
    }

    private static List<Node> nodes(Node root) {
        var result = new ArrayList<Node>();
        result.add(root);
        if (root instanceof Parent parent)
            for (var child : parent.getChildrenUnmodifiable()) result.addAll(nodes(child));
        return result;
    }

    private static List<String> labels(Node root) {
        return nodes(root).stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .toList();
    }

    private static List<Button> buttons(Node root) {
        return nodes(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static TableView<ProfessionTypes.Ability> table(Node root) {
        return (TableView<ProfessionTypes.Ability>)
                nodes(root).stream().filter(TableView.class::isInstance).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ListView<ProfessionTypes.Profession> list(Node root) {
        return (ListView<ProfessionTypes.Profession>)
                nodes(root).stream().filter(ListView.class::isInstance).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
