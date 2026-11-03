package com.vimi.gui;

import com.vibeminer.concepts.comparison.ComparisonTypes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.stage.Stage;

/** Authored-only comparison identity, evidence, paging, cancellation and responsive checks. */
public final class CompareUiTest {
    private CompareUiTest() {}

    private static final class Fixture implements CompareView.Session {
        final AtomicInteger cancels = new AtomicInteger(), closes = new AtomicInteger();
        final AtomicReference<ComparisonTypes.RunState> state =
                new AtomicReference<>(ComparisonTypes.RunState.RUNNING);
        final AtomicBoolean failPage = new AtomicBoolean(true);

        public ComparisonTypes.Descriptor before() {
            return descriptor("authored-before");
        }

        public ComparisonTypes.Descriptor after() {
            return descriptor("authored-after");
        }

        public ComparisonTypes.Progress progress() {
            return new ComparisonTypes.Progress(
                    state.get(),
                    1200,
                    0,
                    new ComparisonTypes.Coverage(
                            ComparisonTypes.Completeness.PARTIAL,
                            "Authored files",
                            List.of("Protected rows")),
                    new ComparisonTypes.Coverage(
                            ComparisonTypes.Completeness.COMPLETE, "Authored files", List.of()),
                    "Findings only");
        }

        public CompletableFuture<ComparisonTypes.ResultPage> page(long offset, int limit) {
            if (failPage.getAndSet(false))
                return CompletableFuture.failedFuture(
                        new IllegalStateException("authored transient page failure"));
            var old = field("authored-content-before");
            var current = field("authored-content-after");
            var key = offset == 0 ? "910001" : "910003";
            var row =
                    new ComparisonTypes.Result(
                            key,
                            ComparisonTypes.Membership.BOTH,
                            new ComparisonTypes.Record(
                                    key, Map.of("content-identity", old), List.of()),
                            new ComparisonTypes.Record(
                                    key, Map.of("content-identity", current), List.of()),
                            List.of(
                                    new ComparisonTypes.FieldDelta(
                                            "content-identity",
                                            old,
                                            current,
                                            ComparisonTypes.Difference.CHANGED,
                                            ComparisonTypes.Aspect.CONTENT)));
            return CompletableFuture.completedFuture(
                    new ComparisonTypes.ResultPage(
                            offset, List.of(row), offset == 0 ? 2L : null, progress()));
        }

        public void cancel() {
            cancels.incrementAndGet();
        }

        public CompletableFuture<Void> closeAsync() {
            closes.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    public static void main(String[] args) throws Exception {
        var started = new CompletableFuture<Void>();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.complete(null);
                });
        started.get(5, TimeUnit.SECONDS);
        var starts = new AtomicInteger();
        var ownerCloses = new AtomicInteger();
        var pending = new CompletableFuture<CompareView.Session>();
        var fixture = new Fixture();
        // Equal labels and a shared config prefix must still produce distinguishable choices.
        var before = build("aaaaaaaaaa1" + "a".repeat(21));
        var after = build("aaaaaaaaaa2" + "a".repeat(21));
        var view =
                fx(
                        () ->
                                new CompareView(
                                        () -> List.of(before, after),
                                        List.of("enUS", "deDE"),
                                        (request, token) -> {
                                            starts.incrementAndGet();
                                            return pending;
                                        },
                                        ownerCloses::incrementAndGet));
        var stage =
                fx(
                        () -> {
                            var window = new Stage();
                            var scene = new Scene(view, 1360, 940);
                            var css = CompareUiTest.class.getResource("/vimi.css");
                            if (css != null) scene.getStylesheets().add(css.toExternalForm());
                            window.setScene(scene);
                            window.show();
                            view.applyCss();
                            view.layout();
                            return window;
                        });
        try {
            fx(
                    () -> {
                        require(starts.get() == 0, "Opening must not compare or acquire");
                        require(
                                !view.buildLabel(before).equals(view.buildLabel(after)),
                                "Same-version build configs must be distinguishable");
                        area(view, "Items");
                        require(
                                labels(view).contains("Not implemented"),
                                "Unsupported area honest");
                        area(view, "Files");
                        locale(view, "After locale").setValue("deDE");
                        button(view, "Compare local data").fire();
                        require(
                                starts.get() == 0
                                        && labels(view).contains("same product and locale"),
                                "Locale mismatch rejected locally");
                        locale(view, "After locale").setValue("enUS");
                        button(view, "Compare local data").fire();
                        require(starts.get() == 1, "Explicit start once");
                        return null;
                    });
            var captured = fx(() -> captured(view));
            pending.complete(fixture);
            await(() -> fxUnchecked(() -> !button(view, "Cancel comparison").isDisabled()));
            fx(
                    () -> {
                        button(view, "Swap").fire();
                        require(
                                captured(view).equals(captured)
                                        && labels(view).contains("Selection changed"),
                                "Swap during run retains captured direction");
                        builds(view, "Before build, exact product and version").setValue(before);
                        require(
                                captured(view).equals(captured),
                                "Endpoint edit during run retains captured pair");
                        button(view, "Compare local data").fire();
                        require(starts.get() == 1, "Running edit cannot start replacement job");
                        builds(view, "After build, exact product and version").setValue(after);
                        return null;
                    });
            fixture.state.set(ComparisonTypes.RunState.COMPLETED);
            await(() -> fxUnchecked(() -> button(view, "Retry page").isVisible()));
            fx(
                    () -> {
                        require(
                                labels(view).contains("Findings page unavailable"),
                                "Failed page exposes retry");
                        button(view, "Retry page").fire();
                        return null;
                    });
            await(
                    () ->
                            fxUnchecked(
                                    () ->
                                            labels(view)
                                                    .contains("Before: authored-content-before")));
            fx(
                    () -> {
                        var filter = combo(view, "Comparison status filter");
                        require(
                                filter.getItems().contains("All findings")
                                        && !filter.getItems().contains("Unchanged")
                                        && !filter.getItems().contains("All records"),
                                "Findings-only filters");
                        require(
                                labels(view).contains("Protected rows"),
                                "Coverage reasons visible");
                        require(
                                labels(view)
                                        .contains(
                                                "Search and status filter apply only to the current findings page"),
                                "Both filters explicitly page-scoped");
                        button(view, "Swap").fire();
                        require(
                                captured(view).equals(captured)
                                        && labels(view).contains("Selection changed"),
                                "Completed swap cannot relabel findings");
                        locale(view, "After locale").setValue("deDE");
                        require(
                                captured(view).equals(captured),
                                "Completed locale edit cannot relabel findings");
                        require(starts.get() == 1, "Editing does not acquire");
                        builds(view, "Before build, exact product and version").setValue(before);
                        builds(view, "After build, exact product and version").setValue(after);
                        locale(view, "After locale").setValue("enUS");
                        button(view, "Next").fire();
                        return null;
                    });
            await(() -> fxUnchecked(() -> resultKeys(view).contains("910003")));
            fx(
                    () -> {
                        button(view, "Previous").fire();
                        return null;
                    });
            await(() -> fxUnchecked(() -> resultKeys(view).contains("910001")));
            fx(
                    () -> {
                        for (double width : new double[] {1320, 900}) {
                            view.resize(width, 940);
                            view.applyCss();
                            view.layout();
                            require(
                                    locale(view, "Before locale").getWidth() >= 90
                                            && locale(view, "After locale").getWidth() >= 90,
                                    "Locale values must remain visible at width " + width);
                        }
                        view.resize(1320, 940);
                        view.layout();
                        screenshot(stage.getScene());
                        view.resize(820, 760);
                        view.layout();
                        button(view, "Details").fire();
                        view.layout();
                        require(
                                button(view, "Back to results").isVisible(),
                                "Responsive details drawer");
                        button(view, "Back to results").fire();
                        view.close();
                        view.close();
                        stage.close();
                        require(
                                fixture.cancels.get() == 1
                                        && fixture.closes.get() == 1
                                        && ownerCloses.get() == 1,
                                "Accepted session closes once");
                        return null;
                    });
            var late = new CompletableFuture<CompareView.Session>();
            var stale = new Fixture();
            var tokenRef = new AtomicReference<com.vibeminer.access.Cancellation>();
            var closing =
                    fx(
                            () -> {
                                var second =
                                        new CompareView(
                                                () -> List.of(before, after),
                                                List.of("enUS"),
                                                (request, token) -> {
                                                    tokenRef.set(token);
                                                    return late;
                                                },
                                                () -> {});
                                var window = new Stage();
                                window.setScene(new Scene(second, 1000, 700));
                                window.show();
                                second.applyCss();
                                second.layout();
                                area(second, "Files");
                                button(second, "Compare local data").fire();
                                second.close();
                                window.close();
                                return second;
                            });
            late.complete(stale);
            await(() -> stale.closes.get() == 1);
            require(stale.cancels.get() == 1, "Late session canceled and drained after close");
            require(tokenRef.get().isCanceled(), "Opening canceled on close");
            System.out.println(
                    "Comparison UI: captured pair on running/completed edits, distinguishing configs, coverage reasons, page retry/cursors, responsive drawer and stale close passed");
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

    private static BuildInfo build(String config) {
        return new BuildInfo(
                "wowf",
                "Forever",
                "1.60.1.70001",
                "us",
                config,
                BuildInfo.BuildSource.SAVED,
                true,
                null,
                0);
    }

    private static ComparisonTypes.Field field(String value) {
        return new ComparisonTypes.Field(
                "content-identity",
                ComparisonTypes.ValueKind.BYTES_IDENTITY,
                ComparisonTypes.ValueState.VALUE,
                value,
                ComparisonTypes.Aspect.CONTENT,
                List.of(
                        new ComparisonTypes.Evidence(
                                "Authored", "910001", "content-identity", value)));
    }

    private static ComparisonTypes.Descriptor descriptor(String identity) {
        return new ComparisonTypes.Descriptor(
                identity,
                "wowf",
                "enUS",
                "files",
                "Authored selected-locale files; names/storage unassessed",
                Map.of("fixture", "authored"));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<BuildInfo> builds(CompareView view, String label) {
        return (ComboBox<BuildInfo>) combo(view, label);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> locale(CompareView view, String label) {
        return (ComboBox<String>) combo(view, label);
    }

    private static ComboBox<?> combo(CompareView view, String label) {
        return (ComboBox<?>)
                view.lookupAll(".combo-box").stream()
                        .filter(node -> label.equals(node.getAccessibleText()))
                        .findFirst()
                        .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void area(CompareView view, String name) {
        ((ListView<String>)
                        view.lookupAll(".list-view").stream()
                                .filter(
                                        node ->
                                                node instanceof ListView<?> list
                                                        && list.getItems().contains("Files"))
                                .findFirst()
                                .orElseThrow())
                .getSelectionModel()
                .select(name);
    }

    private static String captured(CompareView view) {
        return view.lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .map(Label::getText)
                .filter(value -> value.startsWith("Captured Before:"))
                .findFirst()
                .orElseThrow();
    }

    private static String resultKeys(CompareView view) {
        return view.lookupAll(".list-view").stream()
                .filter(
                        node ->
                                node instanceof ListView<?> list
                                        && !list.getItems().isEmpty()
                                        && list.getItems().getFirst()
                                                instanceof ComparisonTypes.Result)
                .map(node -> ((ListView<?>) node).getItems().toString())
                .reduce("", String::concat);
    }

    private static Button button(CompareView view, String name) {
        return (Button)
                view.lookupAll(".button").stream()
                        .filter(
                                node ->
                                        node instanceof Button button
                                                && button.getText().equals(name))
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

    private static <T> T fx(java.util.concurrent.Callable<T> work) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(
                () -> {
                    try {
                        result.complete(work.call());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static <T> T fxUnchecked(java.util.concurrent.Callable<T> work) {
        try {
            return fx(work);
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("FX condition timed out");
            Thread.sleep(25);
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
