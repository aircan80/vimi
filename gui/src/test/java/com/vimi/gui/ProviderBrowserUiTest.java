package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.npcs.NpcCatalog;
import com.vibeminer.concepts.quests.QuestCatalog;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Authored provider values only; verifies real list controls, paging, search and evidence. */
public final class ProviderBrowserUiTest {
    private ProviderBrowserUiTest() {}

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.complete(null);
        });
        started.get(15, TimeUnit.SECONDS);
        try {
            loadingFeedback();
        } catch (Exception | Error failure) {
            Platform.exit();
            throw failure;
        }
        var done = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    var stage = new Stage();
                    try {
                        var view = new NpcsView(null, null);
                        var observations = new ArrayList<NpcCatalog.Observation>();
                        var build =
                                new NpcCatalog.Source(
                                        NpcCatalog.Provider.BLIZZARD,
                                        "authored table",
                                        "authored",
                                        "1.60.1.70707",
                                        "enUS");
                        var questie =
                                new NpcCatalog.Source(
                                        NpcCatalog.Provider.QUESTIE,
                                        "authored snapshot",
                                        "Forever",
                                        null,
                                        "enUS");
                        for (int i = 1; i <= 205; i++)
                            observations.add(
                                    new NpcCatalog.Observation(
                                            i, "Authored NPC " + i, null, null, build));
                        observations.add(
                                new NpcCatalog.Observation(
                                        1,
                                        "Alternative name",
                                        "Authored title",
                                        new NpcCatalog.Levels(10, 12),
                                        questie));
                        var catalog =
                                NpcCatalog.merge(
                                        observations,
                                        List.of("Authored fixtures"),
                                        Cancellation.NONE);
                        view.present(
                                new ProviderBrowser.Result<>(
                                        catalog.npcs(), catalog.diagnostics()));
                        var scene = new Scene(view, 1220, 760);
                        scene.getStylesheets().add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        stage.setScene(scene);
                        stage.show();
                        view.applyCss();
                        view.layout();
                        var table = (TableView<?>) view.lookup(".table-view");
                        require(table.getItems().size() == 100, "First page bounded");
                        table.getSelectionModel().selectFirst();
                        require(
                                text(view, "Alternative name · alternative"),
                                "Conflicting source shown");
                        require(
                                text(view, "Authored title · selected"),
                                "Fallback title selected independently");
                        snapshot(scene, "npcs-authored.png");
                        button(view, "Next").fire();
                        require(table.getItems().size() == 100, "Second page bounded");
                        button(view, "Next").fire();
                        require(table.getItems().size() == 5, "Final page preserves tail");
                        ((TextField) view.lookup(".text-field")).setText("Authored title");
                        require(table.getItems().size() == 1, "Search includes merged title");
                        ((TextField) view.lookup(".text-field")).setText("205");
                        require(table.getItems().size() == 1, "Exact ID search spans all pages");
                        ((TextField)view.lookup(".text-field")).setText("1");
                require(table.getItems().size()==1,"Numeric IDs do not become substring name searches");
                view.close();
                        var quests = new QuestsView(null, null);
                        var source =
                                new QuestCatalog.Source(
                                        QuestCatalog.Provider.QUESTIE,
                                        "authored snapshot",
                                        "Forever",
                                        null,
                                        "enUS");
                        var data =
                                QuestCatalog.merge(
                                        List.of(
                                                new QuestCatalog.Observation(
                                                        9, "Authored quest", 8, 15, source)),
                                        List.of("Authored fixtures"),
                                        Cancellation.NONE);
                        quests.present(
                                new ProviderBrowser.Result<>(data.quests(), data.diagnostics()));
                        scene = new Scene(quests, 1220, 760);
                        scene.getStylesheets().add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        stage.setScene(scene);
                        quests.applyCss();
                        quests.layout();
                        ((TableView<?>) quests.lookup(".table-view"))
                                .getSelectionModel()
                                .selectFirst();
                        require(
                                text(quests, "Required level: 8 · selected"),
                                "Required level distinct from quest level");
                        require(text(quests, "Level: 15 · selected"), "Quest level retained");
                        snapshot(scene, "quests-authored.png");
                        quests.close();
                        done.complete(null);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    } finally {
                        stage.close();
                        Platform.exit();
                    }
                });
        done.get(30, TimeUnit.SECONDS);
        System.out.println(
                "Provider browsers: paging, ID/text search, field precedence and source details"
                    + " passed");
    }

    private static void fx(Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15, TimeUnit.SECONDS);
    }

    private static void awaitFx(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (System.nanoTime() < deadline) {
            fx(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) { return; }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for authored provider reply");
    }

    private static final class PendingBrowser extends ProviderBrowser<String> {
        CompletableFuture<Void> searchGate;
        final java.util.concurrent.CountDownLatch searching = new java.util.concurrent.CountDownLatch(1);
        PendingBrowser() {
            super(null, null, "NPCs", List.of(new ProviderBrowser.Column<>("Name", value -> value, 200)));
        }
        java.util.function.Supplier<Result<String>> request(NativePreparationWork work, Cancellation token) {
            throw new UnsupportedOperationException();
        }
        long id(String row) { return Long.parseLong(row.split(" ")[0]); }
        String searchable(String row) {
            if (searchGate != null) {
                require(!Platform.isFxApplicationThread(), "Large provider search must run off FX");
                searching.countDown();
                searchGate.join();
            }
            return row;
        }
        List<Fact> inspection(String row) { return List.of(new Fact(row, "muted")); }
    }

    private static void loadingFeedback() throws Exception {
        var browser = new java.util.concurrent.atomic.AtomicReference<PendingBrowser>();
        var first = new CompletableFuture<ProviderBrowser.Result<String>>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        fx(() -> {
            var view = new PendingBrowser(); browser.set(view);
            var scene = new Scene(view, 1200, 760);
            scene.getStylesheets().add(VimiApp.class.getResource("/vimi.css").toExternalForm());
            view.applyCss(); view.layout();
            view.beginLoad(token -> () -> {
                require(!Platform.isFxApplicationThread(), "Provider preparation must run off FX");
                entered.countDown();
                return first.join();
            }, () -> true);
            var table = (TableView<?>) view.lookup(".table-view");
            require("provider-grid-loading".equals(table.getPlaceholder().getId()),
                    "Initial empty provider area has centered loading");
            require(!table.getPlaceholder().lookupAll(".progress-indicator").isEmpty(),
                    "Loading area has an indeterminate spinner");
            try { snapshot(scene, "npcs-loading.png"); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        require(entered.await(5, TimeUnit.SECONDS), "Bounded provider worker accepted request");
        fx(() -> {
            ((TextField) browser.get().lookup(".text-field")).setText("First");
            require("provider-grid-loading".equals(((TableView<?>) browser.get()
                            .lookup(".table-view")).getPlaceholder().getId()),
                    "Searching before first catalogue completion keeps loading visible and FX responsive");
        });
        first.complete(new ProviderBrowser.Result<>(List.of("1 First"), List.of("Authored ready catalogue")));
        awaitFx(() -> ((TableView<?>) browser.get().lookup(".table-view")).getItems().size() == 1);
        fx(() -> ((TextField) browser.get().lookup(".text-field")).clear());
        var refresh = new CompletableFuture<ProviderBrowser.Result<String>>();
        fx(() -> {
            browser.get().beginLoad(token -> refresh::join, () -> true);
            require(((TableView<?>) browser.get().lookup(".table-view")).getItems().size() == 1,
                    "Refreshing a provider keeps previous rows available");
            require(text(browser.get(), "Updating catalogue"), "Refresh is visibly marked Updating");
        });
        refresh.completeExceptionally(new IllegalStateException("Authored refresh failure"));
        awaitFx(() -> !browser.get().lookup("#provider-activity").isVisible());
        fx(() -> {
            require(((TableView<?>) browser.get().lookup(".table-view")).getItems().size() == 1,
                    "Provider error does not turn existing rows into empty results");
            require(text(browser.get(), "Previous results are still shown"), "Failed update is explicit");
        });
        var stale = new CompletableFuture<ProviderBrowser.Result<String>>();
        var current = new CompletableFuture<ProviderBrowser.Result<String>>();
        fx(() -> browser.get().beginLoad(token -> stale::join, () -> true));
        fx(() -> browser.get().beginLoad(token -> current::join, () -> true));
        stale.complete(new ProviderBrowser.Result<>(List.of("2 Stale"), List.of()));
        current.complete(new ProviderBrowser.Result<>(List.of("3 Current"), List.of()));
        awaitFx(() -> ((TableView<?>) browser.get().lookup(".table-view")).getItems().equals(List.of("3 Current")));
        var pending = new CompletableFuture<ProviderBrowser.Result<String>>();
        var cancellation = new java.util.concurrent.atomic.AtomicReference<Cancellation>();
        fx(() -> {
            browser.get().beginLoad(token -> { cancellation.set(token); return pending::join; }, () -> true);
            browser.get().close();
            require(!browser.get().lookup("#provider-activity").isVisible(), "Closing stops provider activity feedback");
        });
        require(cancellation.get().isCanceled(), "Close cancels provider work");
        pending.complete(new ProviderBrowser.Result<>(List.of("4 Late"), List.of()));
        fx(() -> require(((TableView<?>) browser.get().lookup(".table-view")).getItems().equals(List.of("3 Current")),
                "Closed provider rejects late completion"));

        largeSearchFeedback();
    }

    private static void largeSearchFeedback() throws Exception {
        var browser = new java.util.concurrent.atomic.AtomicReference<PendingBrowser>();
        var values = java.util.stream.IntStream.rangeClosed(1, 2001)
                .mapToObj(id -> id + (id == 2001 ? " Target" : " Candidate")).toList();
        fx(() -> {
            var view = new PendingBrowser(); browser.set(view);
            new Scene(view, 1200, 760); view.applyCss(); view.layout();
            view.present(new ProviderBrowser.Result<>(values, List.of("Authored large snapshot")));
            view.searchGate = new CompletableFuture<>();
            ((TextField) view.lookup(".text-field")).setText("Target");
        });
        require(browser.get().searching.await(5, TimeUnit.SECONDS), "Large query started on its worker");
        fx(() -> {
            require(((TableView<?>) browser.get().lookup(".table-view")).getItems().size() == 100,
                    "Large pending search retains the previous bounded page");
            require(text(browser.get(), "Updating matches"), "Large pending search is visibly marked Updating");
            ((TextField) browser.get().lookup(".text-field")).setText("No such candidate");
        });
        browser.get().searchGate.complete(null);
        awaitFx(() -> ((TableView<?>) browser.get().lookup(".table-view")).getItems().isEmpty());
        fx(() -> {
            require(!browser.get().lookup("#provider-activity").isVisible(), "Latest query retires activity");
            require(((Label) ((TableView<?>) browser.get().lookup(".table-view")).getPlaceholder())
                    .getText().contains("No matching"), "Latest empty query resets loading placeholder");
            browser.get().close();
        });
    }

    private static boolean text(javafx.scene.Parent root, String part) {
        return root.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(l -> l.getText().contains(part));
    }

    private static Button button(javafx.scene.Parent root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(b -> b.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void snapshot(Scene scene, String filename) throws Exception {
        String target = System.getProperty("vimi.provider.screenshots");
        if (target == null) return;
        var image = scene.snapshot(null);
        var pixels = new int[(int) image.getWidth() * (int) image.getHeight()];
        image.getPixelReader()
                .getPixels(
                        0,
                        0,
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        javafx.scene.image.PixelFormat.getIntArgbInstance(),
                        pixels,
                        0,
                        (int) image.getWidth());
        var bitmap =
                new java.awt.image.BufferedImage(
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        bitmap.setRGB(0, 0, bitmap.getWidth(), bitmap.getHeight(), pixels, 0, bitmap.getWidth());
        Files.createDirectories(Path.of(target));
        javax.imageio.ImageIO.write(bitmap, "png", Path.of(target, filename).toFile());
    }
}
