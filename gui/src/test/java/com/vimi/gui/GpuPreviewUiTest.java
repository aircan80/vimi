package com.vimi.gui;

import com.vibeminer.rendering.models.PreviewMesh;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Authored UI only: pending GPU window and failure fallback; starts no native renderer. */
public final class GpuPreviewUiTest {
    private GpuPreviewUiTest() {}

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("vimi-gpu-ui-test-");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var selectedParts = new java.util.concurrent.atomic.AtomicReference<Set<Integer>>();
        var service =
                new GpuPreviewService(
                        temporary,
                        (file, mesh, appearance, parts) -> {
                            selectedParts.set(parts);
                            Files.writeString(file, "authored");
                        },
                        scene -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new IOException("Fixture gate timed out");
                            } catch (InterruptedException error) {
                                throw new IOException(error);
                            }
                            throw new IOException("Authored unavailable GPU");
                        },
                        Duration.ofSeconds(2),
                        Duration.ofMillis(10));
        var ready = new CompletableFuture<ModelPreviewView>();
        Platform.startup(
                () -> {
                    try {
                        Platform.setImplicitExit(false);
                        var view = new ModelPreviewView(service);
                        var stage = new Stage();
                        stage.setScene(new Scene(view, 640, 560));
                        stage.show();
                        view.applyCss();
                        view.layout();
                        var action = (Button) view.lookup("#model-gpu-preview");
                        require(action.isDisabled(), "No GPU launch before a scene exists");
                        view.setMesh(
                                new PreviewMesh(
                                        new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                                        new int[] {0, 1, 2},
                                        List.of(new PreviewMesh.Part(1, 0, 3))),
                                Set.of(1));
                        require(
                                !action.isDisabled(),
                                "Recorded mesh enables separate-window action");
                        ((javafx.scene.control.CheckBox) view.lookup("#model-all-surfaces"))
                                .setSelected(true);
                        action.fire();
                        ready.complete(view);
                    } catch (Throwable failure) {
                        ready.completeExceptionally(failure);
                    }
                });
        ModelPreviewView view = null;
        try {
            view = ready.get(5, TimeUnit.SECONDS);
            require(entered.await(3, TimeUnit.SECONDS), "GPU launch remains pending in worker");
            require(
                    Set.of(1).equals(selectedParts.get()),
                    "All-surfaces snapshot carries actual part IDs, not an empty hide mask");
            final var selected = view;
            fx(
                    () -> {
                        var activity = (ProgressIndicator) selected.lookup("#model-gpu-activity");
                        require(
                                activity.isVisible() && activity.isManaged(),
                                "Pending native window has visible activity");
                        require(
                                activity.getAccessibleText().contains("activity"),
                                "Busy state is accessible");
                        require(
                                ((Button) selected.lookup("#model-gpu-preview"))
                                        .getText()
                                        .contains("Close"),
                                "Pending window can be canceled");
                    });
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
            fx(
                    () -> {
                        require(
                                !((ProgressIndicator) selected.lookup("#model-gpu-activity"))
                                        .isVisible(),
                                "Failure clears busy state");
                        require(
                                ((Label) selected.lookup("#model-gpu-status"))
                                        .getText()
                                        .contains("inline software"),
                                "Failure explicitly preserves inline preview");
                        require(
                                ((Button) selected.lookup("#model-gpu-preview"))
                                        .getText()
                                        .contains("Open"),
                                "Failed launch permits retry");
                        selected.close();
                    });
        } finally {
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
            final var selected = view;
            fx(
                    () -> {
                        if (selected != null) selected.close();
                    });
            Platform.exit();
            try (var files = Files.walk(temporary)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        System.out.println(
                "GPU preview authored UI: visible activity, explicit separate window and software"
                        + " fallback passed");
    }

    private static void fx(Runnable action) throws Exception {
        var complete = new CompletableFuture<Void>();
        Platform.runLater(
                () -> {
                    try {
                        action.run();
                        complete.complete(null);
                    } catch (Throwable failure) {
                        complete.completeExceptionally(failure);
                    }
                });
        complete.get(3, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
