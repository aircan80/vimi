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
        CompletableFuture<Void> closeGate = CompletableFuture.completedFuture(null);
        boolean rawFacts;

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
                            rawFacts
                                    ? ComparisonTypes.Completeness.UNKNOWN
                                    : ComparisonTypes.Completeness.PARTIAL,
                            rawFacts ? "raw-verified-cells-v1:" + "a".repeat(64) : "Authored files",
                            rawFacts
                                    ? List.of("Membership is uncertified", "Protected rows")
                                    : List.of("Protected rows")),
                    new ComparisonTypes.Coverage(
                            rawFacts
                                    ? ComparisonTypes.Completeness.PARTIAL
                                    : ComparisonTypes.Completeness.COMPLETE,
                            rawFacts ? "raw-verified-cells-v1:" + "b".repeat(64) : "Authored files",
                            List.of()),
                    rawFacts
                            ? "Session-only findings; known-equal rows omitted; no persistent report; findings-only, known-equal rows omitted"
                            : "Findings only");
        }

        public CompletableFuture<ComparisonTypes.ResultPage> page(long offset, int limit) {
            if (failPage.getAndSet(false))
                return CompletableFuture.failedFuture(
                        new IllegalStateException("authored transient page failure"));
            if (rawFacts) {
                var equal = field("0");
                var unknown =
                        new ComparisonTypes.Field(
                                "raw-unreadable",
                                ComparisonTypes.ValueKind.TEXT,
                                ComparisonTypes.ValueState.PROTECTED,
                                null,
                                ComparisonTypes.Aspect.CONTENT,
                                List.of());
                var incomparable =
                        new ComparisonTypes.Field(
                                "raw-copy-source",
                                ComparisonTypes.ValueKind.REFERENCE,
                                ComparisonTypes.ValueState.UNKNOWN,
                                null,
                                ComparisonTypes.Aspect.INTERPRETATION,
                                List.of());
                var deltas =
                        List.of(
                                new ComparisonTypes.FieldDelta(
                                        "cell.Alliance",
                                        equal,
                                        equal,
                                        ComparisonTypes.Difference.EQUAL,
                                        ComparisonTypes.Aspect.CONTENT),
                                new ComparisonTypes.FieldDelta(
                                        "record.isCopy",
                                        equal,
                                        equal,
                                        ComparisonTypes.Difference.EQUAL,
                                        ComparisonTypes.Aspect.INTERPRETATION),
                                new ComparisonTypes.FieldDelta(
                                        "record.copySource",
                                        incomparable,
                                        incomparable,
                                        ComparisonTypes.Difference.INCOMPARABLE,
                                        ComparisonTypes.Aspect.INTERPRETATION),
                                new ComparisonTypes.FieldDelta(
                                        "cell.Unreadable",
                                        unknown,
                                        unknown,
                                        ComparisonTypes.Difference.UNKNOWN,
                                        ComparisonTypes.Aspect.CONTENT));
                var record =
                        new ComparisonTypes.Record("00000000000000000001", Map.of(), List.of());
                return CompletableFuture.completedFuture(
                        new ComparisonTypes.ResultPage(
                                offset,
                                List.of(
                                        new ComparisonTypes.Result(
                                                record.key(),
                                                ComparisonTypes.Membership.BOTH,
                                                record,
                                                record,
                                                deltas)),
                                null,
                                progress()));
            }
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
            return closeGate;
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
        var unprepared =
                new BuildInfo(
                        "wowf",
                        "Forever",
                        "1.60.1.70003",
                        "us",
                        "c".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        var retail =
                new BuildInfo(
                        "wow",
                        "Retail",
                        "12.0.1.80001",
                        "us",
                        "d".repeat(32),
                        BuildInfo.BuildSource.CURRENT,
                        false,
                        null,
                        0);
        fx(
                () -> {
                    assertDefaults(List.of(retail, unprepared, before, after), before, after);
                    assertDefaults(List.of(unprepared, before), before, unprepared);
                    var preparedRetail =
                            new BuildInfo(
                                    "wow",
                                    "Retail",
                                    "12.0.1.80001",
                                    "us",
                                    "e".repeat(32),
                                    BuildInfo.BuildSource.SAVED,
                                    true,
                                    null,
                                    0);
                    assertDefaults(List.of(before, preparedRetail, unprepared), before, unprepared);
                    var otherUnprepared =
                            new BuildInfo(
                                    "wowf",
                                    "Forever",
                                    "1.60.1.70004",
                                    "us",
                                    "f".repeat(32),
                                    BuildInfo.BuildSource.CURRENT,
                                    false,
                                    null,
                                    0);
                    assertDefaults(
                            List.of(unprepared, otherUnprepared), unprepared, otherUnprepared);
                    assertDefaults(List.of(retail, unprepared), retail, retail);
                    assertDefaults(List.of(unprepared), unprepared, unprepared);
                    return null;
                });
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
            rawRowsControls(before, after);
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

    private static void rawRowsControls(BuildInfo before, BuildInfo after) throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<CompareView.Request>();
        var inventory = new Fixture();
        inventory.state.set(ComparisonTypes.RunState.COMPLETED);
        inventory.failPage.set(false);
        var raw = new Fixture();
        raw.failPage.set(false);
        raw.rawFacts = true;
        var reset = new Fixture();
        reset.state.set(ComparisonTypes.RunState.COMPLETED);
        reset.failPage.set(false);
        var sessions = new java.util.ArrayDeque<Fixture>(List.of(inventory, raw, reset));
        var view =
                fx(
                        () ->
                                new CompareView(
                                        () -> List.of(before, after),
                                        List.of("enUS"),
                                        (request, token) -> {
                                            requests.add(request);
                                            return CompletableFuture.completedFuture(
                                                    sessions.remove());
                                        },
                                        () -> {}));
        var window =
                fx(
                        () -> {
                            var stage = new Stage();
                            var scene = new Scene(view, 1320, 940);
                            var css = CompareUiTest.class.getResource("/vimi.css");
                            if (css != null) scene.getStylesheets().add(css.toExternalForm());
                            stage.setScene(scene);
                            stage.show();
                            view.applyCss();
                            view.layout();
                            area(view, "Tables");
                            return stage;
                        });
        try {
            fx(
                    () -> {
                        for (var value : List.of("", "0", "-1", "4294967296", "abc")) {
                            rawId(view).setText(value);
                            button(view, "Compare readable rows locally").fire();
                            require(requests.isEmpty(), "Invalid FileDataID cannot start raw work");
                        }
                        require(
                                labels(view).contains("1 to 4294967295"),
                                "Numeric range explained");
                        button(view, "Compare local data").fire();
                        return null;
                    });
            await(() -> fxUnchecked(() -> !button(view, "Use selected table ID").isDisabled()));
            fx(
                    () -> {
                        require(
                                requests.size() == 1 && requests.getFirst().tableId() == null,
                                "Normal Tables compare remains inventory");
                        button(view, "Use selected table ID").fire();
                        require(
                                rawId(view).getText().equals("910001"),
                                "Selected inventory finding prefills its FileDataID");
                        require(
                                requests.size() == 1,
                                "Selecting/prefilling does not start decoding or sorting");
                        inventory.closeGate = new CompletableFuture<>();
                        rawId(view).setText("4294967295");
                        button(view, "Compare readable rows locally").fire();
                        require(
                                requests.size() == 1
                                        && button(view, "Compare readable rows locally")
                                                .isDisabled(),
                                "Raw start waits for old session drain");
                        require(
                                captured(view).contains("Raw table rows · FileDataID 4294967295"),
                                "Captured mode identifies exact raw table");
                        return null;
                    });
            inventory.closeGate.complete(null);
            await(() -> requests.size() == 2);
            fx(
                    () -> {
                        require(
                                requests.get(1).area().equals("Tables")
                                        && requests.get(1).tableId() == 4294967295L,
                                "Raw request preserves Tables area and unsigned FileDataID");
                        require(
                                labels(view).contains("gameplay meaning is unassessed"),
                                "Raw findings have scoped semantics warning");
                        button(view, "Compare local data").fire();
                        button(view, "Compare readable rows locally").fire();
                        require(requests.size() == 2, "Running raw session rejects replacement");
                        var captured = captured(view);
                        button(view, "Swap").fire();
                        rawId(view).setText("123");
                        require(
                                captured(view).equals(captured)
                                        && labels(view).contains("Selection changed"),
                                "Raw mode/table stays captured through selector/input edits");
                        view.resize(900, 940);
                        view.layout();
                        require(
                                rawId(view).getWidth() >= 100
                                        && button(view, "Compare readable rows locally").getWidth()
                                                > 100,
                                "Narrow raw controls remain usable");
                        return null;
                    });
            raw.state.set(ComparisonTypes.RunState.COMPLETED);
            await(() -> fxUnchecked(() -> !button(view, "Compare local data").isDisabled()));
            fx(
                    () -> {
                        view.resize(1320, 940);
                        view.layout();
                        return null;
                    });
            await(() -> fxUnchecked(() -> labels(view).contains("Unreadable · Unknown")));
            fx(
                    () -> {
                        assertRawReadability(view);
                        return null;
                    });
            fx(
                    () -> {
                        button(view, "Compare local data").fire();
                        return null;
                    });
            await(() -> requests.size() == 3);
            fx(
                    () -> {
                        require(
                                requests.get(2).tableId() == null
                                        && requests.get(2).area().equals("Tables"),
                                "Normal action resets raw mode to inventory");
                        require(
                                captured(view).contains("Tables inventory")
                                        && !captured(view).contains("Raw table rows"),
                                "Captured result header resets mode honestly");
                        require(
                                inventory.closes.get() == 1 && raw.closes.get() == 1,
                                "Each replacement drains its previous session exactly once");
                        return null;
                    });
        } finally {
            fx(
                    () -> {
                        view.close();
                        window.close();
                        return null;
                    });
        }
    }

    private static void assertRawReadability(CompareView view) throws Exception {
        view.applyCss();
        view.layout();
        var identity =
                view.lookupAll(".label").stream()
                        .filter(
                                node ->
                                        node instanceof Label label
                                                && label.getText().equals("ID 1"))
                        .map(Label.class::cast)
                        .findFirst()
                        .orElseThrow();
        require(
                identity.getTooltip().getText().contains("00000000000000000001")
                        && identity.getAccessibleText().contains("00000000000000000001"),
                "Human identity retains canonical tooltip/accessibility");
        var cell =
                view.lookupAll(".list-cell").stream()
                        .filter(
                                node ->
                                        node instanceof javafx.scene.control.ListCell<?> item
                                                && item.getItem() instanceof ComparisonTypes.Result)
                        .map(node -> (javafx.scene.control.ListCell<?>) node)
                        .findFirst()
                        .orElseThrow();
        require(
                cell.getText().equals("Cannot determine · 1")
                        && cell.getTooltip().getText().contains("00000000000000000001"),
                "List IDs are human-readable with canonical evidence");
        var unreadable =
                view.lookupAll(".label").stream()
                        .filter(
                                node ->
                                        node instanceof Label label
                                                && label.getText().equals("Unreadable · Unknown"))
                        .map(Label.class::cast)
                        .findFirst()
                        .orElseThrow();
        var inspector = (javafx.scene.layout.VBox) unreadable.getParent().getParent();
        var fields =
                inspector.getChildren().stream()
                        .filter(javafx.scene.layout.VBox.class::isInstance)
                        .map(javafx.scene.layout.VBox.class::cast)
                        .map(box -> ((Label) box.getChildren().getFirst()).getText())
                        .toList();
        require(
                fields.getFirst().equals("Unreadable · Unknown")
                        && fields.indexOf("Copy source ID · Incomparable")
                                < fields.indexOf("Alliance · Equal"),
                "Uncertainty and incomparable reasons precede equal fields");
        require(
                fields.contains("Copy record · Equal")
                        && unreadable
                                .getTooltip()
                                .getText()
                                .equals("Canonical field: cell.Unreadable"),
                "Friendly column/copy labels preserve canonical keys");
        var coverage =
                view.lookupAll(".label").stream()
                        .filter(
                                node ->
                                        node instanceof Label label
                                                && label.getText()
                                                        .startsWith(
                                                                "Before: Unknown · Prepared table rows"))
                        .map(Label.class::cast)
                        .findFirst()
                        .orElseThrow();
        require(
                coverage.getText().contains("Membership is uncertified")
                        && coverage.getText().contains("Protected rows")
                        && coverage.getText().contains("After: Partial"),
                "Human coverage retains every issue and differing states");
        require(
                !coverage.getText().contains("a".repeat(64))
                        && coverage.getTooltip().getText().contains("a".repeat(64)),
                "Main coverage omits fingerprint while full scope remains inspectable");
        require(
                labels(view).contains("Completed · 1200 identities scanned · Session only")
                        && !labels(view).contains("known-equal rows omitted; no persistent report"),
                "Progress avoids repeated boilerplate");
        require(
                unreadable.isWrapText()
                        && coverage.isWrapText()
                        && coverage.getMinHeight() == javafx.scene.layout.Region.USE_PREF_SIZE,
                "Narrow labels wrap instead of hiding uncertainty");
        screenshot(view.getScene(), "comparison-raw-authored");
    }

    private static javafx.scene.control.TextField rawId(CompareView view) {
        return (javafx.scene.control.TextField)
                view.lookupAll(".text-field").stream()
                        .filter(node -> "Raw table FileDataID".equals(node.getAccessibleText()))
                        .findFirst()
                        .orElseThrow();
    }

    private static void assertDefaults(
            List<BuildInfo> choices, BuildInfo expectedBefore, BuildInfo expectedAfter) {
        var view =
                new CompareView(
                        () -> choices,
                        List.of("enUS"),
                        (request, token) -> {
                            throw new AssertionError(
                                    "Choosing defaults must not acquire or assess readiness");
                        },
                        () -> {});
        new Scene(view, 1320, 850);
        view.applyCss();
        view.layout();
        require(
                builds(view, "Before build, exact product and version").getValue()
                        == expectedBefore,
                "Before prefers prepared history hint, else first catalogue choice");
        require(
                builds(view, "After build, exact product and version").getValue() == expectedAfter,
                "After prefers different same-product prepared hint, then same-product alternative, then Before");
        require(
                builds(view, "Before build, exact product and version").getItems().equals(choices)
                        && builds(view, "After build, exact product and version")
                                .getItems()
                                .equals(choices),
                "Default preference preserves every choice and catalogue order");
        view.close();
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
        screenshot(scene, "comparison-authored");
    }

    private static void screenshot(Scene scene, String name) throws Exception {
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
        javax.imageio.ImageIO.write(pixels, "png", directory.resolve(name + ".png").toFile());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
