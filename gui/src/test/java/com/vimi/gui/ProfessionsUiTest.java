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
            loadingStates();
            retirementStates();
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

    private static void loadingStates() throws Exception {
        var catalog = new CompletableFuture<ProfessionTypes.Catalog>();
        var page = new CompletableFuture<ProfessionTypes.Page>();
        var detail = new CompletableFuture<ProfessionTypes.Detail>();
        var icon = new CompletableFuture<com.vibeminer.formats.images.BlpImageTypes.Result>();
        var names = new CompletableFuture<String>();
        var nameRequest =
                new java.util.concurrent.atomic.AtomicReference<CompletableFuture<String>>(names);
        var nameTokens = new java.util.concurrent.CopyOnWriteArrayList<Cancellation>();
        var sourceRevision = new AtomicLong(1);
        var handle =
                new BuildProfessions() {
                    public CompletableFuture<ProfessionTypes.Catalog> catalog(Cancellation token) {
                        return catalog;
                    }

                    public CompletableFuture<ProfessionTypes.Page> abilities(
                            long id, String query, long after, int limit, Cancellation token) {
                        return page;
                    }

                    public CompletableFuture<ProfessionTypes.Detail> detail(
                            long id, Cancellation token) {
                        return detail;
                    }

                    public CompletableFuture<Void> closeAsync() {
                        throw new AssertionError("Borrowed handle");
                    }
                };
        var view =
                fx(
                        () ->
                                new ProfessionsView(
                                        () -> handle,
                                        sourceRevision::get,
                                        (id, token) -> icon,
                                        (id, token) -> {
                                            nameTokens.add(token);
                                            return nameRequest.get();
                                        }));
        var stage =
                fx(
                        () -> {
                            var result = new Stage();
                            result.setScene(new Scene(view, 1320, 850));
                            result.show();
                            view.ensureLoaded();
                            require(
                                    view.lookup("#professions-activity").isVisible(),
                                    "Catalog activity is animated");
                            return result;
                        });
        try {
            catalog.complete(HANDLE.catalog(new Cancellation()).get());
            await(() -> labels(view).contains("Reading Authored Alchemy abilities…"));
            fx(
                    () -> {
                        require(
                                view.lookup("#professions-activity").isVisible(),
                                "Abilities activity is animated");
                        return null;
                    });
            page.complete(
                    new ProfessionTypes.Page(
                            ProfessionTypes.State.READY, List.of(POTION), 1L, List.of(), null));
            await(
                    () ->
                            nodes(view).stream()
                                    .anyMatch(
                                            n ->
                                                    "Loading recipe details"
                                                            .equals(n.getAccessibleText())));
            var original = potion();
            var illustrated =
                    new ProfessionTypes.Ability(
                            1, 171, 100, "Authored illustrated recipe", 900L, 25L, 40L);
            detail.complete(
                    new ProfessionTypes.Detail(
                            original.state(),
                            illustrated,
                            original.skill(),
                            original.reagents(),
                            original.reagentState(),
                            original.tools(),
                            original.toolState(),
                            original.results(),
                            original.resultState(),
                            original.issues(),
                            original.message()));
            await(
                    () ->
                            nodes(view).stream()
                                    .anyMatch(
                                            n ->
                                                    "Loading recipe icon"
                                                            .equals(n.getAccessibleText())));
            fx(
                    () -> {
                        require(
                                nodes(view).stream()
                                        .anyMatch(
                                                n ->
                                                        n.getAccessibleText() != null
                                                                && n.getAccessibleText()
                                                                        .startsWith(
                                                                                "Resolving item"
                                                                                    + " name")),
                                "Optional item-name resolution has local activity");
                        return null;
                    });
            fx(
                    () -> {
                        buttons(view).stream()
                                .filter(b -> b.getText().equals("More recipes"))
                                .findFirst()
                                .orElseThrow()
                                .fire();
                        return null;
                    });
            await(() -> !view.lookup("#professions-activity").isVisible());
            fx(
                    () -> {
                        require(
                                nameTokens.stream().noneMatch(Cancellation::isCanceled),
                                "Append must retain the selected detail's token");
                        return null;
                    });
            icon.completeExceptionally(new IllegalStateException("Authored missing icon"));
            names.complete("Authored resolved item");
            await(
                    () ->
                            nodes(view).stream()
                                    .anyMatch(
                                            n ->
                                                    "Recipe icon unavailable"
                                                            .equals(n.getAccessibleText())));
            fx(
                    () -> {
                        require(
                                nodes(view).stream()
                                        .filter(
                                                javafx.scene.control.ProgressIndicator.class
                                                        ::isInstance)
                                        .noneMatch(Node::isVisible),
                                "Completed and failed work retires all activity");
                        require(
                                labels(view).contains("2 × Authored resolved item (#2447)"),
                                "Append retains pending item-name work");
                        nameRequest.set(new CompletableFuture<>());
                        table(view).getSelectionModel().clearSelection();
                        table(view).getSelectionModel().selectFirst();
                        return null;
                    });
            await(
                    () ->
                            nodes(view).stream()
                                    .anyMatch(
                                            n ->
                                                    n.getAccessibleText() != null
                                                            && n.getAccessibleText()
                                                                    .startsWith(
                                                                            "Resolving item name")
                                                            && n.isVisible()));
            fx(
                    () -> {
                        sourceRevision.incrementAndGet();
                        nameRequest.get().complete("Retired name");
                        return null;
                    });
            await(() -> labels(view).contains("Profession sources changed. Reload to update."));
            fx(
                    () -> {
                        require(
                                table(view).isDisabled(),
                                "Retired detail sources disable old actions");
                        require(
                                nodes(view).stream()
                                        .filter(
                                                javafx.scene.control.ProgressIndicator.class
                                                        ::isInstance)
                                        .noneMatch(Node::isVisible),
                                "Retired attached item-name activity is removed");
                        return null;
                    });
        } finally {
            fx(
                    () -> {
                        view.close();
                        stage.close();
                        return null;
                    });
        }
    }

    private static void retirementStates() throws Exception {
        class Fixture implements BuildProfessions {
            CompletableFuture<ProfessionTypes.Catalog> catalog = new CompletableFuture<>();

            public CompletableFuture<ProfessionTypes.Catalog> catalog(Cancellation token) {
                return catalog;
            }

            public CompletableFuture<ProfessionTypes.Page> abilities(
                    long id, String query, long after, int limit, Cancellation token) {
                throw new AssertionError("No abilities in empty retirement catalog");
            }

            public CompletableFuture<ProfessionTypes.Detail> detail(long id, Cancellation token) {
                throw new AssertionError("No detail");
            }

            public CompletableFuture<Void> closeAsync() {
                throw new AssertionError("Borrowed handle");
            }
        }
        var original = new Fixture();
        var replacement = new Fixture();
        var handle = new java.util.concurrent.atomic.AtomicReference<BuildProfessions>(original);
        var revision = new AtomicLong(1);
        var empty =
                new ProfessionTypes.Catalog(
                        ProfessionTypes.State.READY, List.of(), List.of(), List.of(), null);
        var view =
                fx(
                        () ->
                                new ProfessionsView(
                                        handle::get,
                                        revision::get,
                                        (id, token) ->
                                                CompletableFuture.failedFuture(
                                                        new AssertionError("No icon"))));
        fx(
                () -> {
                    view.ensureLoaded();
                    revision.incrementAndGet();
                    original.catalog.complete(empty);
                    return null;
                });
        await(() -> !view.lookup("#professions-activity").isVisible());
        fx(
                () -> {
                    require(list(view).isDisabled(), "Retired revision disables old actions");
                    original.catalog = new CompletableFuture<>();
                    view.ensureLoaded();
                    handle.set(replacement);
                    original.catalog.complete(empty);
                    return null;
                });
        await(() -> !view.lookup("#professions-activity").isVisible());
        fx(
                () -> {
                    require(
                            list(view).getItems().isEmpty(),
                            "Same-revision handle exchange rejects old catalog");
                    view.ensureLoaded();
                    require(
                            view.lookup("#professions-activity").isVisible(),
                            "New generation retains activity");
                    var old = replacement.catalog;
                    revision.incrementAndGet();
                    replacement.catalog = new CompletableFuture<>();
                    view.ensureLoaded();
                    old.complete(empty);
                    return null;
                });
        fx(
                () -> {
                    require(
                            view.lookup("#professions-activity").isVisible(),
                            "Stale old reply cannot hide new activity");
                    replacement.catalog.complete(empty);
                    return null;
                });
        await(() -> !view.lookup("#professions-activity").isVisible());
        fx(
                () -> {
                    view.close();
                    return null;
                });
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
