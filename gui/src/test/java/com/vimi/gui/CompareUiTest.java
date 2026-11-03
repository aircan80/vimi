package com.vimi.gui;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

/** Authored local-only comparison interaction and stale/lifetime checks; no repository access. */
public final class CompareUiTest {
    private CompareUiTest() {}

    public static void main(String[] args) throws Exception {
        var done = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    try {
                        var starts = new AtomicInteger();
                        var cancels = new AtomicInteger();
                        var closes = new AtomicInteger();
                        var ownerCloses = new AtomicInteger();
                        var ready = new CompletableFuture<CompareView.Session>();
                        var before =
                                new BuildInfo(
                                        "wowf",
                                        "Forever",
                                        "1.60.1.70001",
                                        "us",
                                        "a".repeat(32),
                                        BuildInfo.BuildSource.SAVED,
                                        true,
                                        null,
                                        0);
                        var after =
                                new BuildInfo(
                                        "wowf",
                                        "Forever",
                                        "1.60.1.70002",
                                        "us",
                                        "b".repeat(32),
                                        BuildInfo.BuildSource.SAVED,
                                        true,
                                        null,
                                        0);
                        var view =
                                new CompareView(
                                        () -> List.of(before, after),
                                        List.of("enUS", "deDE"),
                                        (request, token) -> {
                                            starts.incrementAndGet();
                                            return ready;
                                        },
                                        ownerCloses::incrementAndGet);
                        var stage = new Stage();
                        var scene = new Scene(view, 1360, 850);
                        var css = CompareUiTest.class.getResource("/vimi.css");
                        if (css != null) scene.getStylesheets().add(css.toExternalForm());
                        stage.setScene(scene);
                        stage.show();
                        view.applyCss();
                        view.layout();
                        require(starts.get() == 0, "Opening must not compare/download");
                        @SuppressWarnings("unchecked")
                        var areas =
                                (ListView<String>)
                                        view.lookupAll(".list-view").stream()
                                                .filter(
                                                        node ->
                                                                node instanceof ListView<?> list
                                                                        && list.getItems()
                                                                                .contains("Files"))
                                                .findFirst()
                                                .orElseThrow();
                        areas.getSelectionModel().select("Items");
                        require(
                                labels(view).contains("Not implemented"),
                                "Every unsupported area needs honest label");
                        areas.getSelectionModel().select("Files");
                        require(starts.get() == 0, "Area browse must not start comparison");
                        @SuppressWarnings("unchecked")
                        var locale =
                                (ComboBox<String>)
                                        view.lookupAll(".combo-box").stream()
                                                .filter(
                                                        node ->
                                                                "After locale"
                                                                        .equals(
                                                                                node
                                                                                        .getAccessibleText()))
                                                .findFirst()
                                                .orElseThrow();
                        locale.setValue("deDE");
                        button(view, "Compare local data").fire();
                        require(
                                starts.get() == 0
                                        && labels(view).contains("same product and locale"),
                                "Locale mismatch rejected locally");
                        locale.setValue("enUS");
                        button(view, "Compare local data").fire();
                        require(starts.get() == 1, "Explicit start exactly once");
                        ready.complete(
                                new CompareView.Session() {
                                    public ComparisonTypes.Descriptor before() {
                                        return descriptor("before");
                                    }

                                    public ComparisonTypes.Descriptor after() {
                                        return descriptor("after");
                                    }

                                    public ComparisonTypes.Progress progress() {
                                        return progressValue();
                                    }

                                    public CompletableFuture<ComparisonTypes.ResultPage> page(
                                            long offset, int limit) {
                                        var old =
                                                new ComparisonTypes.Field(
                                                        "content-identity",
                                                        ComparisonTypes.ValueKind.BYTES_IDENTITY,
                                                        ComparisonTypes.ValueState.VALUE,
                                                        "authored-content-before",
                                                        ComparisonTypes.Aspect.CONTENT,
                                                        List.of(
                                                                new ComparisonTypes.Evidence(
                                                                        "Authored",
                                                                        "910001",
                                                                        "content-identity",
                                                                        "authored-content-before")));
                                        var current =
                                                new ComparisonTypes.Field(
                                                        "content-identity",
                                                        ComparisonTypes.ValueKind.BYTES_IDENTITY,
                                                        ComparisonTypes.ValueState.VALUE,
                                                        "authored-content-after",
                                                        ComparisonTypes.Aspect.CONTENT,
                                                        List.of());
                                        var delta =
                                                new ComparisonTypes.FieldDelta(
                                                        "content-identity",
                                                        old,
                                                        current,
                                                        ComparisonTypes.Difference.CHANGED,
                                                        ComparisonTypes.Aspect.CONTENT);
                                        var result =
                                                new ComparisonTypes.Result(
                                                        "910001",
                                                        ComparisonTypes.Membership.BOTH,
                                                        new ComparisonTypes.Record(
                                                                "910001",
                                                                Map.of("content-identity", old),
                                                                List.of()),
                                                        new ComparisonTypes.Record(
                                                                "910001",
                                                                Map.of("content-identity", current),
                                                                List.of()),
                                                        List.of(delta));
                                        return CompletableFuture.completedFuture(
                                                new ComparisonTypes.ResultPage(
                                                        0, List.of(result), null, progressValue()));
                                    }

                                    public void cancel() {
                                        cancels.incrementAndGet();
                                    }

                                    public CompletableFuture<Void> closeAsync() {
                                        closes.incrementAndGet();
                                        return CompletableFuture.completedFuture(null);
                                    }
                                });
                        Platform.runLater(
                                () ->
                                        Platform.runLater(
                                                () -> {
                                                    try {
                                                        require(
                                                                labels(view)
                                                                        .contains(
                                                                                "Before: authored-content-before"),
                                                                "Typed field evidence visible");
                                                        require(
                                                                labels(view).contains("Partial"),
                                                                "Partial coverage retained");
                                                        view.applyCss();
                                                        view.layout();
                                                        screenshot(scene);
                                                        view.resize(820, 760);
                                                        view.layout();
                                                        button(view, "Details").fire();
                                                        view.layout();
                                                        require(
                                                                button(view, "Back to results")
                                                                        .isVisible(),
                                                                "Narrow inspector drawer");
                                                        button(view, "Back to results").fire();
                                                        view.close();
                                                        view.close();
                                                        stage.close();
                                                        require(
                                                                cancels.get() == 1
                                                                        && closes.get() == 1
                                                                        && ownerCloses.get() == 1,
                                                                "Close idempotently cancels/drains owned callback session");
                                                        require(
                                                                starts.get() == 1,
                                                                "Inspection and resizing never start acquisition");
                                                        done.complete(null);
                                                    } catch (Throwable failure) {
                                                        done.completeExceptionally(failure);
                                                    }
                                                }));
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
        try {
            done.get(15, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println(
                    "Comparison UI: explicit local start, unsupported rail, partial evidence, responsive drawer and lifetime passed");
        } finally {
            Platform.exit();
        }
    }

    private static void screenshot(Scene scene) throws Exception {
        var image = scene.snapshot(null);
        var pixels =
                new java.awt.image.BufferedImage(
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < pixels.getHeight(); y++)
            for (int x = 0; x < pixels.getWidth(); x++)
                pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        var directory =
                java.nio.file.Path.of(
                        System.getProperty("vimi.uiReviewDirectory", "gui/build/ui-review"));
        java.nio.file.Files.createDirectories(directory);
        javax.imageio.ImageIO.write(
                pixels, "png", directory.resolve("comparison-authored.png").toFile());
    }

    private static ComparisonTypes.Descriptor descriptor(String id) {
        return new ComparisonTypes.Descriptor(
                id,
                "wowf",
                "enUS",
                "files",
                "Authored selected-locale inventory",
                Map.of("fixture", "authored"));
    }

    private static ComparisonTypes.Progress progressValue() {
        return new ComparisonTypes.Progress(
                ComparisonTypes.RunState.COMPLETED,
                1,
                0,
                new ComparisonTypes.Coverage(
                        ComparisonTypes.Completeness.PARTIAL,
                        "Authored files",
                        List.of("Protected rows")),
                new ComparisonTypes.Coverage(
                        ComparisonTypes.Completeness.COMPLETE, "Authored files", List.of()),
                "Inventory only");
    }

    private static Button button(CompareView view, String text) {
        return (Button)
                view.lookupAll(".button").stream()
                        .filter(
                                node ->
                                        node instanceof Button button
                                                && button.getText().equals(text))
                        .findFirst()
                        .orElseThrow();
    }

    private static String labels(CompareView view) {
        return view.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
