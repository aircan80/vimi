package com.vimi.gui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/** Authored progress fixture: no application library, provider data or network. */
public final class SourceRefreshUiTest {
    private SourceRefreshUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        var first = new CompletableFuture<Void>();
                        var second = new CompletableFuture<Void>();
                        var coordinator =
                                new SourceRefresh(
                                        List.of(
                                                provider(
                                                        "blizzard",
                                                        "Blizzard build catalog",
                                                        first,
                                                        "Checking published builds…",
                                                        -1),
                                                provider(
                                                        "questie",
                                                        "QuestieDB",
                                                        second,
                                                        "Forever · Preparing local database",
                                                        .65),
                                                provider(
                                                        "tact",
                                                        "TACTKeys",
                                                        CompletableFuture.completedFuture(null),
                                                        "Ready",
                                                        1),
                                                new SourceRefresh.Provider(
                                                        "disabled",
                                                        "Authored disabled source",
                                                        () -> false,
                                                        () ->
                                                                CompletableFuture.completedFuture(
                                                                        null),
                                                        () -> null,
                                                        () -> {}),
                                                provider(
                                                        "offline",
                                                        "Authored offline source",
                                                        CompletableFuture.failedFuture(
                                                                new IllegalStateException(
                                                                        "Offline; previous local"
                                                                            + " data retained")),
                                                        "",
                                                        -1)));
                        var stage = new Stage();
                        var scene =
                                new Scene(
                                        new VBox(
                                                new Label(
                                                        "Authored source refresh fixture · no game"
                                                                + " data or network")),
                                        900,
                                        730);
                        scene.getStylesheets()
                                .add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        stage.setScene(scene);
                        stage.show();
                        var run = coordinator.start();
                        var dialog = new SourceRefreshDialog(stage, run);
                        dialog.getDialogPane()
                                .getStylesheets()
                                .add(VimiApp.class.getResource("/vimi.css").toExternalForm());
                        dialog.show();
                        var pause =
                                new javafx.animation.PauseTransition(
                                        javafx.util.Duration.millis(350));
                        pause.setOnFinished(
                                event -> {
                                    try {
                                        require(
                                                dialog.getModality() == javafx.stage.Modality.NONE,
                                                "Progress must not block Builds interaction");
                                        require(
                                                run.rows().get(1).message().contains("Preparing"),
                                                "Preparation is visible in provider progress");
                                        var pane = dialog.getDialogPane();
                                        pane.applyCss();
                                        pane.layout();
                                        var image = pane.snapshot(null, null);
                                        var output =
                                                new BufferedImage(
                                                        (int) image.getWidth(),
                                                        (int) image.getHeight(),
                                                        BufferedImage.TYPE_INT_ARGB);
                                        for (int y = 0; y < output.getHeight(); y++)
                                            for (int x = 0; x < output.getWidth(); x++)
                                                output.setRGB(
                                                        x, y, image.getPixelReader().getArgb(x, y));
                                        Path folder =
                                                Path.of(
                                                        System.getProperty(
                                                                "vimi.uiReviewDirectory",
                                                                "build/ui-review"));
                                        Files.createDirectories(folder);
                                        ImageIO.write(
                                                output,
                                                "png",
                                                folder.resolve("source-refresh-authored.png")
                                                        .toFile());
                                        dialog.hide();
                                        require(
                                                run.busy() && !first.isDone(),
                                                "Hide keeps application-owned updates running");
                                        require(
                                                coordinator.start() == run,
                                                "Reopen uses the same active run");
                                        var reopened = new SourceRefreshDialog(stage, run);
                                        reopened.show();
                                        Button cancel =
                                                (Button)
                                                        reopened
                                                                .getDialogPane()
                                                                .lookupAll(".button")
                                                                .stream()
                                                                .filter(
                                                                        node ->
                                                                                node
                                                                                                instanceof
                                                                                                Button
                                                                                                        button
                                                                                        && button.getText()
                                                                                                .equals(
                                                                                                        "Cancel"
                                                                                                            + " updates"))
                                                                .findFirst()
                                                                .orElseThrow();
                                        cancel.fire();
                                        require(
                                                !run.busy(),
                                                "Cancel drains completed authored providers");
                                        require(
                                                run.rows().getFirst().state()
                                                        == SourceRefresh.State.CANCELED,
                                                "Cancel leaves an explicit provider outcome");
                                        reopened.close();
                                        coordinator.close();
                                        var ready =
                                                new SourceRefresh(
                                                        List.of(
                                                                provider(
                                                                        "ready",
                                                                        "Up to date",
                                                                        CompletableFuture
                                                                                .completedFuture(
                                                                                        null),
                                                                        "",
                                                                        1)));
                                        var automatic =
                                                new SourceRefreshDialog(
                                                        stage, ready.start(), true, () -> {});
                                        automatic.show();
                                        var completed =
                                                new javafx.animation.PauseTransition(
                                                        javafx.util.Duration.millis(1300));
                                        completed.setOnFinished(
                                                unused -> {
                                                    try {
                                                        require(
                                                                !automatic.isShowing(),
                                                                "Untouched successful startup"
                                                                        + " refresh closes"
                                                                        + " automatically");
                                                        ready.close();
                                                        var failed =
                                                                new SourceRefresh(
                                                                        List.of(
                                                                                provider(
                                                                                        "failed",
                                                                                        "Offline",
                                                                                        CompletableFuture
                                                                                                .failedFuture(
                                                                                                        new IllegalStateException(
                                                                                                                "Offline")),
                                                                                        "",
                                                                                        -1)));
                                                        var retried =
                                                                new java.util.concurrent.atomic
                                                                        .AtomicInteger();
                                                        var failure =
                                                                new SourceRefreshDialog(
                                                                        stage,
                                                                        failed.start(),
                                                                        true,
                                                                        retried::incrementAndGet);
                                                        failure.show();
                                                        var remains =
                                                                new javafx.animation
                                                                        .PauseTransition(
                                                                        javafx.util.Duration.millis(
                                                                                1300));
                                                        remains.setOnFinished(
                                                                ignored -> {
                                                                    try {
                                                                        require(
                                                                                failure.isShowing(),
                                                                                "Startup failures"
                                                                                    + " remain"
                                                                                    + " visible");
                                                                        var retry =
                                                                                (Button)
                                                                                        failure
                                                                                                .getDialogPane()
                                                                                                .lookupAll(
                                                                                                        ".button")
                                                                                                .stream()
                                                                                                .filter(
                                                                                                        node ->
                                                                                                                node
                                                                                                                                instanceof
                                                                                                                                Button
                                                                                                                                        button
                                                                                                                        && button.getText()
                                                                                                                                .equals(
                                                                                                                                        "Retry"
                                                                                                                                            + " refresh"))
                                                                                                .findFirst()
                                                                                                .orElseThrow();
                                                                        retry.fire();
                                                                        require(
                                                                                retried.get() == 1,
                                                                                "Failure offers a"
                                                                                    + " retry"
                                                                                    + " action");
                                                                        failure.close();
                                                                        failed.close();
                                                                        stage.close();
                                                                        done.complete(null);
                                                                    } catch (Throwable error) {
                                                                        done.completeExceptionally(
                                                                                error);
                                                                    }
                                                                });
                                                        remains.play();
                                                    } catch (Throwable error) {
                                                        done.completeExceptionally(error);
                                                    }
                                                });
                                        completed.play();
                                    } catch (Throwable error) {
                                        done.completeExceptionally(error);
                                    }
                                });
                        pause.play();
                    } catch (Throwable error) {
                        done.completeExceptionally(error);
                    }
                });
        try {
            done.get(20, TimeUnit.SECONDS);
            System.out.println(
                    "Source refresh UI: progress, nonmodal use, hide/reopen and cancellation"
                            + " passed");
        } finally {
            Platform.exit();
        }
    }

    private static SourceRefresh.Provider provider(
            String id,
            String name,
            CompletableFuture<Void> future,
            String message,
            double fraction) {
        return new SourceRefresh.Provider(
                id,
                name,
                () -> true,
                () -> future,
                () -> new SourceRefresh.Progress(message, fraction),
                () ->
                        future.completeExceptionally(
                                new java.util.concurrent.CancellationException()));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
