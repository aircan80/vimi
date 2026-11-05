package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.talents.BuildTalents;
import com.vibeminer.concepts.talents.TalentTypes;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reload with unchanged source data and a class switch while trees load. Authored fixture only;
 * requires a desktop display.
 */
public final class TalentsReloadUiTest {
    private TalentsReloadUiTest() {}

    private static final TalentTypes.PlayerClass WARRIOR =
            new TalentTypes.PlayerClass(1, "Warrior", null, null);
    private static final TalentTypes.PlayerClass DRUID =
            new TalentTypes.PlayerClass(11, "Druid", null, null);

    private static final class Fixture implements BuildTalents {
        final AtomicInteger catalogs = new AtomicInteger();
        final AtomicInteger trees = new AtomicInteger();
        final Map<Long, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();

        public CompletableFuture<TalentTypes.Catalog> catalog(Cancellation token) {
            catalogs.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new TalentTypes.Catalog(
                            TalentTypes.State.READY, List.of(WARRIOR, DRUID), null, null));
        }

        public CompletableFuture<TalentTypes.ClassTrees> trees(long classId, Cancellation token) {
            trees.incrementAndGet();
            var player = classId == 1 ? WARRIOR : DRUID;
            var tree =
                    new TalentTypes.Tree(
                            classId,
                            player.name() + " tree",
                            0,
                            "",
                            List.of(
                                    new TalentTypes.Talent(
                                            classId * 100,
                                            0,
                                            0,
                                            List.of(1000L + classId),
                                            List.of(),
                                            player.name() + " talent",
                                            "Plain text.",
                                            null)));
            var result =
                    new TalentTypes.ClassTrees(
                            TalentTypes.State.READY, player, List.of(tree), List.of(), null, null);
            return gates.getOrDefault(classId, CompletableFuture.completedFuture(null))
                    .thenApply(ignored -> result);
        }

        public CompletableFuture<Void> closeAsync() {
            throw new AssertionError("Borrowed handle");
        }
    }

    private static List<String> talentNames(Scene scene) {
        return scene.getRoot().lookupAll(".talent-node").stream()
                .map(node -> ((Button) node).getAccessibleText())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static ListView<TalentTypes.PlayerClass> classes(TalentsView view) {
        return (ListView<TalentTypes.PlayerClass>)
                ((VBox) view.getLeft()).getChildren().getLast();
    }

    private static void await(
            String phase, java.util.function.BooleanSupplier condition, CompletableFuture<?> ready)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            var done = new CompletableFuture<Boolean>();
            Platform.runLater(
                    () -> {
                        try {
                            done.complete(condition.getAsBoolean());
                        } catch (Throwable failure) {
                            done.completeExceptionally(failure);
                        }
                    });
            if (done.get(10, TimeUnit.SECONDS)) {
                return;
            }
            if (System.nanoTime() >= deadline || ready.isDone()) {
                var labels = new CompletableFuture<String>();
                Platform.runLater(
                        () ->
                                labels.complete(
                                        String.join(
                                                " | ",
                                                javafx.stage.Window.getWindows().stream()
                                                        .flatMap(
                                                                w ->
                                                                        w.getScene()
                                                                                .getRoot()
                                                                                .lookupAll(".label")
                                                                                .stream())
                                                        .map(
                                                                l ->
                                                                        ((javafx.scene.control
                                                                                                .Label)
                                                                                        l)
                                                                                .getText())
                                                        .toList())));
                throw new AssertionError(
                        "Timed out waiting for " + phase + ": " + labels.get(5, TimeUnit.SECONDS));
            }
            Thread.sleep(20);
        }
    }

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        done.complete(null);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        done.get(10, TimeUnit.SECONDS);
    }

    public static void main(String[] args) {
        int status = 1;
        try {
            run();
            status = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            Platform.exit();
            System.exit(status);
        }
    }

    private static void run() throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        var failed = new CompletableFuture<Void>();
        var fixture = new Fixture();
        var holder = new Object() {
            TalentsView view;
            Scene scene;
            javafx.stage.Stage stage;
        };
        fx(
                () -> {
                    holder.view =
                            new TalentsView(
                                    () -> fixture,
                                    () -> 1,
                                    (id, token) ->
                                            CompletableFuture.failedFuture(
                                                    new AssertionError("No icons")));
                    holder.scene = new Scene(holder.view, 1200, 800);
                    holder.stage = new javafx.stage.Stage();
                    holder.stage.setScene(holder.scene);
                    holder.stage.show();
                    holder.view.ensureLoaded();
                });
        await(
                "first trees",
                () -> talentNames(holder.scene).stream().anyMatch(t -> t.startsWith("Warrior")),
                failed);

        // Reload with unchanged source data must show the selected class's trees again.
        fx(
                () -> {
                    holder.view.invalidate();
                    holder.view.ensureLoaded();
                });
        await(
                "trees after Reload",
                () -> talentNames(holder.scene).stream().anyMatch(t -> t.startsWith("Warrior")),
                failed);
        if (fixture.catalogs.get() != 2 || fixture.trees.get() != 2)
            throw new AssertionError(
                    "Reload must query the catalog and the selected class again: catalogs="
                            + fixture.catalogs.get()
                            + " trees="
                            + fixture.trees.get());

        // Switching class while the previous class still loads shows only the latest class.
        var druidGate = new CompletableFuture<Void>();
        fixture.gates.put(11L, druidGate);
        fx(() -> classes(holder.view).getSelectionModel().select(DRUID));
        fx(() -> classes(holder.view).getSelectionModel().select(WARRIOR));
        await(
                "warrior after switching back",
                () -> talentNames(holder.scene).stream().anyMatch(t -> t.startsWith("Warrior")),
                failed);
        druidGate.complete(null);
        Thread.sleep(200);
        fx(
                () -> {
                    var names = talentNames(holder.scene);
                    if (names.stream().anyMatch(t -> t.startsWith("Druid"))
                            || names.stream().noneMatch(t -> t.startsWith("Warrior")))
                        throw new AssertionError("A superseded class load replaced the trees");
                    holder.view.close();
                    holder.stage.close();
                });
        System.out.println("Talents reload UI PASS: Reload with unchanged data and class switch");
    }
}
