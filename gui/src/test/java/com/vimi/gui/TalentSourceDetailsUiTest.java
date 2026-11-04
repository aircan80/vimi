package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.BuildTalents;
import com.vibeminer.concepts.talents.TalentTypes;
import com.vibeminer.formats.images.BlpImageTypes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;

/** Authored reported causes, expandable details and late-selection isolation; no real assets. */
public final class TalentSourceDetailsUiTest {
    private TalentSourceDetailsUiTest() {}
    private static TalentsView view;
    private static Stage stage;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        var calls = new AtomicInteger();
        var player = new TalentTypes.PlayerClass(1, "Authored class", null, "#55aaff");
        BuildTalents source = new BuildTalents() {
            public CompletableFuture<TalentTypes.Catalog> catalog(Cancellation token) {
                return CompletableFuture.completedFuture(new TalentTypes.Catalog(
                        TalentTypes.State.READY, List.of(player), null, null));
            }
            public CompletableFuture<TalentTypes.ClassTrees> trees(long id, Cancellation token) {
                return CompletableFuture.completedFuture(new TalentTypes.ClassTrees(
                        TalentTypes.State.PARTIAL, player,
                        List.of(new TalentTypes.Tree(1, "Authored tree", 0, null, List.of(
                                talent(1, null, 201L), talent(2, "Readable text", 202L),
                                talent(3, null, null)))),
                        List.of("Spell: PARTIAL; authored class-level notice"), "PARTIAL", null));
            }
            public CompletableFuture<Void> closeAsync() { throw new AssertionError("Borrowed source"); }
        };
        try {
            fx(() -> {
                view = new TalentsView(() -> source, () -> 1, (id, token) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(new BlpImageTypes.Result(
                            id == 201 ? BlpImageTypes.State.PROTECTED : BlpImageTypes.State.UNSUPPORTED,
                            null, BlpImageTypes.Cache.NONE,
                            id == 201 ? List.of("0011223344556677") : List.of(), null,
                            id == 201 ? null : "BLP_UNSUPPORTED",
                            id == 201 ? "Missing TACT key" : "Unsupported mip flags 2", null));
                });
                stage = new Stage();
                stage.setScene(new Scene(view, 1150, 750));
                stage.show();
                view.ensureLoaded();
            });
            await(() -> view.lookupAll(".talent-node").size() == 3, "talent nodes");
            fx(() -> button(1).fire());
            await(() -> label("talent-source-icon").contains("encrypted"), "protected source cause");
            fx(() -> {
                var details = (TitledPane) view.lookup("#talent-source-details");
                require(!details.isExpanded(), "Source details starts collapsed");
                int before = calls.get();
                details.setExpanded(true);
                require(details.isExpanded(), "Source details expands");
                require(label("talent-source-icon").contains("0011223344556677"), "Reported key identifier retained");
                require(label("talent-source-description").contains("did not report a precise cause"),
                        "Missing description does not invent encryption or absence");
                require(details.getContent().lookupAll(".label").stream()
                        .map(node -> ((Label) node).getText())
                        .anyMatch(text -> text.contains("Class-level source notices")),
                        "Class-wide evidence is labelled separately");
                details.setExpanded(false);
                require(!details.isExpanded() && before == calls.get(), "Collapse/expand does not request source data");
                button(2).fire();
            });
            await(() -> label("talent-source-icon").contains("BLP_UNSUPPORTED"), "unsupported image cause");
            fx(() -> {
                require(!label("talent-source-icon").contains("encrypted"), "Unsupported image is not labelled encrypted");
                require(label("talent-source-description").contains("source text is available"), "Readable description stays readable");
                button(3).fire();
            });
            await(() -> label("talent-source-icon").contains("cause is unconfirmed"), "missing reference evidence after selection layout");
            fx(() -> require(!label("talent-source-icon").contains("BLP_UNSUPPORTED"),
                    "Selection discards prior icon evidence"));
        } finally {
            fx(() -> { if (view != null) view.close(); if (stage != null) stage.close(); });
            Platform.exit();
        }
        System.out.println("Talent source details UI PASS: expand/collapse, protected vs unsupported, missing text and selection isolation");
    }

    private static TalentTypes.Talent talent(int id, String description, Long icon) {
        return new TalentTypes.Talent(id, 0, id - 1, List.of(1000L + id), List.of(),
                "Authored talent " + id, description, icon);
    }
    private static Button button(int id) {
        return view.lookupAll(".talent-node").stream().map(Button.class::cast)
                .filter(node -> node.getAccessibleText().startsWith("Authored talent " + id + ","))
                .findFirst().orElseThrow();
    }
    private static String label(String id) {
        var node = (Label) view.lookup("#" + id);
        return node == null ? "" : node.getText();
    }
    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var ready = new java.util.concurrent.atomic.AtomicBoolean();
            fx(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out: " + description);
    }
    private static void fx(Runnable action) throws Exception {
        var result = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { action.run(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); } });
        result.get(8, TimeUnit.SECONDS);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
