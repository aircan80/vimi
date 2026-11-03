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
        var done = new CompletableFuture<Void>();
        Platform.startup(() -> Platform.runLater(
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
                }));
        done.get(30, TimeUnit.SECONDS);
        System.out.println(
                "Provider browsers: paging, ID/text search, field precedence and source details"
                    + " passed");
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
