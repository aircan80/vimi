package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Session-only local inventory comparison. Backend handles belong to the composition owner. */
final class CompareView extends BorderPane implements AutoCloseable {
    record Request(
            BuildInfo before,
            String beforeLocale,
            BuildInfo after,
            String afterLocale,
            String area) {}

    interface Controller {
        CompletableFuture<Session> start(Request request, Cancellation cancellation);
    }

    interface Session {
        ComparisonTypes.Descriptor before();

        ComparisonTypes.Descriptor after();

        ComparisonTypes.Progress progress();

        CompletableFuture<ComparisonTypes.ResultPage> page(long offset, int limit);

        void cancel();

        CompletableFuture<Void> closeAsync();
    }

    private static final List<String> AREAS =
            List.of(
                    "Summary",
                    "Overview",
                    "Files",
                    "Tables",
                    "Items",
                    "Creatures",
                    "NPCs",
                    "Quests",
                    "Talents",
                    "Races",
                    "Dungeons",
                    "Mounts",
                    "Professions",
                    "World");
    private final ComboBox<BuildInfo> before = new ComboBox<>(), after = new ComboBox<>();
    private final ComboBox<String> beforeLocale = new ComboBox<>(), afterLocale = new ComboBox<>();
    private final ComboBox<String> compactArea = new ComboBox<>();
    private final ListView<String> areas = new ListView<>();
    private final TextField search = new TextField();
    private final ComboBox<String> filter = new ComboBox<>();
    private final Label status =
            text("Choose two builds. Browsing this screen does not download data.", "muted");
    private final Label heading = text("Comparison summary", "h2");
    private final VBox inspector = new VBox(12), content = new VBox(12);
    private final ScrollPane inspectorScroll = new ScrollPane(inspector);
    private final BorderPane body = new BorderPane();
    private final javafx.scene.layout.FlowPane endpoints = new javafx.scene.layout.FlowPane(12, 12);
    private final VBox selectors = new VBox(10);
    private final Button compare = new Button("Compare local data"),
            cancel = new Button("Cancel comparison"),
            details = new Button("Details"),
            back = new Button("Back to results");
    private final Runnable onClose;
    private final Controller controller;
    private final ListView<ComparisonTypes.Result> results = new ListView<>();
    private final Label pageStatus = text("Not compared", "muted");
    private final Label capturedPair = text("No captured comparison pair", "muted");
    private final Label pendingSelection = text("", "muted");
    private final Label pageError = text("", "muted");
    private final Button retryPage = new Button("Retry page");
    private Request capturedRequest;
    private final java.util.Map<String, String> configLabels = new java.util.HashMap<>();
    private long retryOffset;
    private int retryDirection;
    private final Button previous = new Button("Previous"), next = new Button("Next");
    private final Timeline clock;
    private Session session;
    private long generation, pageOffset;
    private Long nextOffset;
    private final java.util.ArrayDeque<Long> pageHistory = new java.util.ArrayDeque<>();
    private List<ComparisonTypes.Result> pageRows = List.of();
    private String selectedArea = "Summary";
    private boolean pending;
    private Cancellation openingToken = new Cancellation();
    private boolean closed, drawer;

    // Composition and typed comparison bindings are added with the reviewed core API.
    CompareView(
            Supplier<List<BuildInfo>> catalog,
            List<String> locales,
            Controller controller,
            Runnable onClose) {
        this.onClose = Objects.requireNonNull(onClose);
        this.controller = Objects.requireNonNull(controller);
        var choices = List.copyOf(Objects.requireNonNull(catalog).get());
        for (var choice : choices) {
            var config = safe(choice.config());
            var length = Math.min(10, config.length());
            while (length < config.length()) {
                var prefix = config.substring(0, length);
                if (choices.stream()
                        .noneMatch(
                                other ->
                                        !Objects.equals(other.config(), config)
                                                && safe(other.config()).startsWith(prefix))) break;
                length++;
            }
            configLabels.put(config, config.substring(0, length));
        }
        before.getItems().setAll(choices);
        after.getItems().setAll(choices);
        for (var box : List.of(before, after)) {
            box.setConverter(
                    new javafx.util.StringConverter<>() {
                        public String toString(BuildInfo value) {
                            return value == null ? "Choose build" : buildLabel(value);
                        }

                        public BuildInfo fromString(String ignored) {
                            throw new UnsupportedOperationException();
                        }
                    });
        }
        beforeLocale.getItems().setAll(locales);
        afterLocale.getItems().setAll(locales);
        if (!locales.isEmpty()) {
            beforeLocale.getSelectionModel().selectFirst();
            afterLocale.getSelectionModel().selectFirst();
        }
        if (!choices.isEmpty()) {
            before.getSelectionModel().selectFirst();
            var initial = choices.getFirst();
            after.setValue(choices.stream().filter(candidate -> candidate.product().equals(initial.product())
                    && !candidate.identity().equals(initial.identity())).findFirst().orElse(initial));
        }
        var swap = new Button("Swap");
        swap.setAccessibleText("Swap before and after builds and locales");
        swap.setOnAction(
                event -> {
                    var first = before.getValue();
                    var locale = beforeLocale.getValue();
                    before.setValue(after.getValue());
                    beforeLocale.setValue(afterLocale.getValue());
                    after.setValue(first);
                    afterLocale.setValue(locale);
                });
        endpoints
                .getChildren()
                .addAll(
                        endpoint("Before", before, beforeLocale),
                        swap,
                        endpoint("After", after, afterLocale));
        var close = new Button("Close comparison");
        close.setOnAction(event -> close());
        cancel.setDisable(true);
        cancel.setOnAction(
                event -> {
                    openingToken.cancel();
                    if (session != null) {
                        session.cancel();
                        refreshProgress();
                    } else if (pending) {
                        generation++;
                        pending = false;
                        cancel.setDisable(true);
                        status.setText("Canceled · inputs not assessed");
                        selectArea(selectedArea);
                    }
                });
        compare.setOnAction(event -> start());
        before.valueProperty().addListener((o, a, b) -> selectionChanged());
        after.valueProperty().addListener((o, a, b) -> selectionChanged());
        beforeLocale.valueProperty().addListener((o, a, b) -> selectionChanged());
        afterLocale.valueProperty().addListener((o, a, b) -> selectionChanged());
        retryPage.setVisible(false);
        retryPage.setManaged(false);
        retryPage.setOnAction(event -> loadPage(retryOffset, retryDirection));
        selectors
                .getChildren()
                .setAll(
                        text("Compare builds", "h2"),
                        endpoints,
                        capturedPair,
                        pendingSelection,
                        new HBox(8, compare, cancel, close),
                        status);
        selectors.setPadding(new Insets(14));
        setTop(selectors);
        areas.getItems().setAll(AREAS);
        areas.setPrefWidth(210);
        areas.setAccessibleText("Comparison area");
        areas.getStyleClass().add("product-menu");
        compactArea.getItems().setAll(AREAS);
        compactArea.setAccessibleText("Comparison area");
        areas.getSelectionModel().selectFirst();
        compactArea.getSelectionModel().selectFirst();
        areas.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> selectArea(b));
        compactArea.valueProperty().addListener((o, a, b) -> selectArea(b));
        search.textProperty().addListener((o, a, b) -> filterPage());
        filter.valueProperty().addListener((o, a, b) -> filterPage());
        search.setPromptText("Search this area's labels or IDs");
        search.setAccessibleText("Search current findings page");
        filter.getItems()
                .setAll(
                        "All findings",
                        "Added",
                        "Removed",
                        "Modified",
                        "Readability changed",
                        "Cannot determine",
                        "Source / interpretation changed");
        filter.getSelectionModel().selectFirst();
        filter.setAccessibleText("Comparison status filter");
        details.setOnAction(
                event -> {
                    drawer = true;
                    layoutForWidth();
                });
        back.setOnAction(
                event -> {
                    drawer = false;
                    layoutForWidth();
                    details.requestFocus();
                });
        content.setPadding(new Insets(14));
        content.getChildren()
                .setAll(
                        compactArea,
                        new HBox(12, heading, details),
                        new HBox(8, search, filter),
                        text("No comparison has run. Counts are not assessed.", "muted"));
        results.setAccessibleText("Comparison findings page");
        results.setCellFactory(
                ignored ->
                        new javafx.scene.control.ListCell<>() {
                            @Override
                            protected void updateItem(ComparisonTypes.Result value, boolean empty) {
                                super.updateItem(value, empty);
                                setText(
                                        empty || value == null
                                                ? null
                                                : resultLabel(value) + " · " + value.key());
                            }
                        });
        results.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> inspect(b));
        VBox.setVgrow(results, Priority.ALWAYS);
        previous.setDisable(true);
        next.setDisable(true);
        previous.setOnAction(
                event -> {
                    if (!pageHistory.isEmpty()) loadPage(pageHistory.peek(), -1);
                });
        next.setOnAction(
                event -> {
                    if (nextOffset != null) {
                        loadPage(nextOffset, 1);
                    }
                });
        inspector.setPadding(new Insets(14));
        inspector
                .getChildren()
                .setAll(
                        text("Evidence", "h2"),
                        text("Select a comparison result to inspect both sides.", "muted"));
        inspectorScroll.setFitToWidth(true);
        inspectorScroll.setPrefWidth(340);
        body.setPadding(new Insets(0, 14, 14, 14));
        setCenter(body);
        widthProperty().addListener((ignored, oldValue, newValue) -> layoutForWidth());
        layoutForWidth();
        clock = new Timeline(new KeyFrame(Duration.millis(350), event -> refreshProgress()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        selectArea("Summary");
    }

    private String runArea;
    private ComparisonTypes.RunState renderedState;

    private void selectArea(String area) {
        if (area == null) return;
        selectedArea = area;
        if (!Objects.equals(areas.getSelectionModel().getSelectedItem(), area))
            areas.getSelectionModel().select(area);
        if (!Objects.equals(compactArea.getValue(), area)) compactArea.setValue(area);
        heading.setText(area.equals("Summary") ? "Comparison summary" : area);
        content.getChildren().setAll(compactArea, new HBox(12, heading, details));
        if (area.equals("Summary")) {
            content.getChildren()
                    .add(
                            text(
                                    session == null
                                            ? "Not compared. Choose Files or Tables, then Compare local data."
                                            : "Session-only "
                                                    + runArea
                                                    + " inventory findings. Known-equal records are omitted; other areas are not assessed.",
                                    "muted"));
            content.getChildren().add(pageStatus);
        } else if (area.equals("Files") || area.equals("Tables")) {
            content.getChildren()
                    .add(
                            text(
                                    "Local "
                                            + area.toLowerCase(java.util.Locale.ROOT)
                                            + " inventory only. Readability and source coverage remain separate from content changes.",
                                    "muted"));
            if (area.equals(runArea))
                content.getChildren()
                        .addAll(
                                text(
                                        "Search and status filter apply only to the current findings page.",
                                        "muted"),
                                new HBox(8, search, filter),
                                results,
                                new HBox(8, previous, next, retryPage),
                                pageStatus,
                                pageError);
            else
                content.getChildren()
                        .add(
                                text(
                                        "Not compared for this area. Compare local data starts a new session-only run.",
                                        "muted"));
        } else
            content.getChildren()
                    .add(
                            text(
                                    "Not implemented. This area has no comparison adapter in the inventory preview; no zero-change conclusion is available.",
                                    "muted"));
        compare.setDisable(
                pending || running() || !(area.equals("Files") || area.equals("Tables")));
        if (!area.equals(runArea)) inspect(null);
        layoutForWidth();
    }

    private void start() {
        if (closed || pending || running() || before.getValue() == null || after.getValue() == null)
            return;
        if (!Objects.equals(before.getValue().product(), after.getValue().product())
                || !Objects.equals(beforeLocale.getValue(), afterLocale.getValue())
                || beforeLocale.getValue() == null) {
            status.setText(
                    "Choose the same product and locale on both sides. Cross-product and cross-locale comparison is not supported.");
            return;
        }
        generation++;
        var current = generation;
        var drained = CompletableFuture.<Void>completedFuture(null);
        if (session != null) {
            session.cancel();
            drained = session.closeAsync();
            session = null;
        }
        pending = true;
        openingToken = new Cancellation();
        cancel.setDisable(false);
        compare.setDisable(true);
        runArea = selectedArea;
        pageRows = List.of();
        pageHistory.clear();
        results.getItems().clear();
        pageStatus.setText("Not assessed · comparison starting");
        status.setText("Opening dedicated local inputs… No acquisition is requested.");
        renderedState = null;
        CompletableFuture<Session> opening;
        try {
            var request =
                    new Request(
                            before.getValue(),
                            beforeLocale.getValue(),
                            after.getValue(),
                            afterLocale.getValue(),
                            runArea);
            capturedRequest = request;
            capturedPair.setText(
                    "Captured Before: "
                            + buildLabel(request.before())
                            + " · "
                            + request.beforeLocale()
                            + " → After: "
                            + buildLabel(request.after())
                            + " · "
                            + request.afterLocale());
            capturedPair.setTooltip(
                    new javafx.scene.control.Tooltip(
                            "Before identity: "
                                    + request.before().identity()
                                    + "\nBefore config: "
                                    + request.before().config()
                                    + "\nAfter identity: "
                                    + request.after().identity()
                                    + "\nAfter config: "
                                    + request.after().config()));
            selectionChanged();
            var token = openingToken;
            opening =
                    drained.thenCompose(
                            ignored -> {
                                token.check();
                                return controller.start(request, token);
                            });
        } catch (Throwable failure) {
            opening = CompletableFuture.failedFuture(failure);
        }
        opening.whenComplete(
                (value, failure) ->
                        Platform.runLater(
                                () -> {
                                    if (closed || current != generation) {
                                        if (value != null) {
                                            value.cancel();
                                            value.closeAsync();
                                        }
                                        return;
                                    }
                                    pending = false;
                                    if (failure != null || value == null) {
                                        status.setText(
                                                "Local comparison could not open: "
                                                        + (failure == null
                                                                ? "no session returned"
                                                                : failure.getClass()
                                                                        .getSimpleName())
                                                        + ". Missing inputs do not trigger downloads.");
                                        pageStatus.setText("Not assessed · opening failed");
                                        selectArea(selectedArea);
                                        return;
                                    }
                                    session = value;
                                    selectArea(selectedArea);
                                    refreshProgress();
                                }));
    }

    private void refreshProgress() {
        if (closed || session == null) return;
        var progress = session.progress();
        if (progress == null) return;
        var running = progress.state() == ComparisonTypes.RunState.RUNNING;
        cancel.setDisable(!running);
        compare.setDisable(
                running
                        || pending
                        || !(selectedArea.equals("Files") || selectedArea.equals("Tables")));
        status.setText(
                words(progress.state().toString())
                        + " · "
                        + progress.observed()
                        + " scanned identities · session-only findings, known-equal records omitted. "
                        + safe(progress.message()));
        pageStatus.setText(
                "Before: " + coverage(progress.a()) + " · After: " + coverage(progress.b()));
        if (!running && renderedState != progress.state()) {
            renderedState = progress.state();
            loadPage(0);
        }
    }

    private static String coverage(ComparisonTypes.Coverage value) {
        return value == null
                ? "not assessed"
                : words(value.completeness().toString())
                        + " · "
                        + value.scope()
                        + (value.issues().isEmpty()
                                ? ""
                                : " · Reasons: " + String.join("; ", value.issues()));
    }

    private void loadPage(long offset) {
        loadPage(offset, 0);
    }

    private void loadPage(long offset, int direction) {
        if (session == null || closed) return;
        var handle = session;
        var current = generation;
        previous.setDisable(true);
        next.setDisable(true);
        retryPage.setVisible(false);
        retryPage.setManaged(false);
        pageError.setText("");
        handle.page(offset, 100)
                .whenComplete(
                        (page, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || current != generation
                                                    || session != handle) return;
                                            if (failure != null || page == null) {
                                                pageError.setText(
                                                        "Findings page unavailable. Existing findings remain; retry this page.");
                                                retryOffset = offset;
                                                retryDirection = direction;
                                                retryPage.setVisible(true);
                                                retryPage.setManaged(true);
                                                previous.setDisable(pageHistory.isEmpty());
                                                next.setDisable(nextOffset == null);
                                                return;
                                            }
                                            if (direction > 0) pageHistory.push(pageOffset);
                                            else if (direction < 0 && !pageHistory.isEmpty())
                                                pageHistory.pop();
                                            pageOffset = page.offset();
                                            nextOffset = page.nextOffset();
                                            pageRows = page.rows();
                                            filterPage();
                                            previous.setDisable(pageHistory.isEmpty());
                                            next.setDisable(nextOffset == null);
                                        }));
    }

    private void filterPage() {
        var query = safe(search.getText()).toLowerCase(java.util.Locale.ROOT).trim();
        var mode = filter.getValue();
        results.getItems()
                .setAll(
                        pageRows.stream()
                                .filter(
                                        row -> {
                                            var label = resultLabel(row);
                                            return (mode == null
                                                            || mode.equals("All findings")
                                                            || label.equals(mode))
                                                    && (query.isEmpty()
                                                            || (row.key() + " " + row.fields())
                                                                    .toLowerCase(
                                                                            java.util.Locale.ROOT)
                                                                    .contains(query));
                                        })
                                .toList());
        search.setPromptText("Search this findings page's IDs and evidence");
        if (!results.getItems().isEmpty()) results.getSelectionModel().selectFirst();
        else inspect(null);
    }

    static String resultLabel(ComparisonTypes.Result row) {
        if (row.membership() == ComparisonTypes.Membership.ADDED) return "Added";
        if (row.membership() == ComparisonTypes.Membership.REMOVED) return "Removed";
        if (row.membership() != ComparisonTypes.Membership.BOTH) return "Cannot determine";
        if (row.fields().stream()
                .anyMatch(
                        field ->
                                field.difference() == ComparisonTypes.Difference.CHANGED
                                        && field.aspect() == ComparisonTypes.Aspect.CONTENT))
            return "Modified";
        if (row.fields().stream()
                .anyMatch(
                        field ->
                                field.difference() == ComparisonTypes.Difference.UNKNOWN
                                        || field.difference()
                                                == ComparisonTypes.Difference.INCOMPARABLE))
            return "Cannot determine";
        if (row.fields().stream()
                .anyMatch(
                        field ->
                                field.difference() == ComparisonTypes.Difference.CHANGED
                                        && field.aspect() == ComparisonTypes.Aspect.AVAILABILITY))
            return "Readability changed";
        if (row.fields().stream()
                .anyMatch(field -> field.difference() == ComparisonTypes.Difference.CHANGED))
            return "Source / interpretation changed";
        return "Unchanged";
    }

    private void inspect(ComparisonTypes.Result row) {
        inspector.getChildren().setAll(text("Evidence", "h2"));
        if (row == null) {
            inspector
                    .getChildren()
                    .add(
                            text(
                                    "Select a result. Unknown values do not establish absence.",
                                    "muted"));
            return;
        }
        inspector
                .getChildren()
                .addAll(
                        text(row.key(), "h3"),
                        text(resultLabel(row) + " · " + membership(row.membership()), "muted"));
        if (session != null) {
            inspector
                    .getChildren()
                    .add(
                            text(
                                    "Before: "
                                            + session.before().identity()
                                            + " · "
                                            + session.before().locale()
                                            + "\nAfter: "
                                            + session.after().identity()
                                            + " · "
                                            + session.after().locale(),
                                    "muted"));
            var sources =
                    new javafx.scene.control.TitledPane(
                            "Captured inputs",
                            text(
                                    "Before: "
                                            + session.before().dependencies()
                                            + "\nAfter: "
                                            + session.after().dependencies(),
                                    "muted"));
            sources.setExpanded(false);
            inspector.getChildren().add(sources);
        }
        for (var field : row.fields()) {
            var box =
                    new VBox(
                            5,
                            text(field.key() + " · " + words(field.difference().toString()), "h3"),
                            text("Before: " + fieldValue(field.a()), "muted"),
                            text("After: " + fieldValue(field.b()), "muted"),
                            text(
                                    "Evidence dimension: " + words(field.aspect().toString()),
                                    "muted"));
            var evidence =
                    new javafx.scene.control.TitledPane(
                            "Why this result?",
                            text(
                                    "Before: "
                                            + (field.a() == null
                                                    ? "No readable field observation"
                                                    : field.a().evidence())
                                            + "\nAfter: "
                                            + (field.b() == null
                                                    ? "No readable field observation"
                                                    : field.b().evidence()),
                                    "muted"));
            evidence.setExpanded(false);
            box.getChildren().add(evidence);
            inspector.getChildren().add(box);
        }
    }

    private static String fieldValue(ComparisonTypes.Field field) {
        if (field == null) return "Not observed · unknown";
        return field.state() == ComparisonTypes.ValueState.VALUE
                ? field.value()
                : field.state() == ComparisonTypes.ValueState.KNOWN_EMPTY
                        ? "Known empty"
                        : words(field.state().toString());
    }

    private static String words(String value) {
        var lower = value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return lower.isEmpty()
                ? lower
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String membership(ComparisonTypes.Membership value) {
        return switch (value) {
            case BOTH -> "Observed on both sides";
            case ADDED -> "Verified absence before, within assessed scope";
            case REMOVED -> "Verified absence after, within assessed scope";
            case OBSERVED_ONLY_A -> "Observed before; after absence not established";
            case OBSERVED_ONLY_B -> "Observed after; before absence not established";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private boolean running() {
        return session != null
                && session.progress() != null
                && session.progress().state() == ComparisonTypes.RunState.RUNNING;
    }

    private void selectionChanged() {
        for (var box : List.of(before, after)) {
            var value = box.getValue();
            box.setTooltip(
                    value == null
                            ? null
                            : new javafx.scene.control.Tooltip(
                                    "Identity: "
                                            + value.identity()
                                            + "\nConfig: "
                                            + value.config()));
        }
        var changed =
                capturedRequest != null
                        && (!Objects.equals(capturedRequest.before(), before.getValue())
                                || !Objects.equals(capturedRequest.after(), after.getValue())
                                || !Objects.equals(
                                        capturedRequest.beforeLocale(), beforeLocale.getValue())
                                || !Objects.equals(
                                        capturedRequest.afterLocale(), afterLocale.getValue()));
        pendingSelection.setText(
                changed
                        ? "Selection changed. Findings and progress still belong to the captured pair above. Compare local data again after this run finishes, or cancel it."
                        : "");
        pendingSelection.setVisible(changed);
        pendingSelection.setManaged(changed);
    }

    String buildLabel(BuildInfo value) {
        var config = safe(value.config());
        return value.edition()
                + " · "
                + value.product()
                + " · "
                + value.version()
                + " · "
                + value.regions()
                + " · config "
                + configLabels.getOrDefault(config, config);
    }

    private static Label text(String value, String style) {
        var label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static VBox endpoint(String name, ComboBox<BuildInfo> build, ComboBox<String> locale) {
        build.setMaxWidth(Double.MAX_VALUE);
        build.setAccessibleText(name + " build, exact product and version");
        locale.setAccessibleText(name + " locale");
        build.getStyleClass().add("build-choice");
        locale.getStyleClass().add("build-choice");
        var line = new HBox(8, build, locale);
        HBox.setHgrow(build, Priority.ALWAYS);
        var box = new VBox(6, text(name, "muted"), line);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPrefWidth(465);
        box.setMinWidth(245);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void layoutForWidth() {
        var narrow = getWidth() > 0 && getWidth() < 1120;
        body.setLeft(null);
        body.setRight(null);
        body.setCenter(null);
        if (inspectorScroll.getParent() instanceof VBox oldDrawer)
            oldDrawer.getChildren().remove(inspectorScroll);
        endpoints.setPrefWrapLength(Math.max(250, getWidth() - 28));
        if (!narrow) {
            body.setLeft(areas);
            body.setRight(inspectorScroll);
            body.setCenter(content);
            compactArea.setVisible(false);
            compactArea.setManaged(false);
        } else {
            compactArea.setVisible(true);
            compactArea.setManaged(true);
            body.setCenter(drawer ? new VBox(10, back, inspectorScroll) : content);
        }
        details.setVisible(narrow);
        details.setManaged(narrow);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        clock.stop();
        openingToken.cancel();
        if (session != null) {
            session.cancel();
            session.closeAsync();
            session = null;
        }
        onClose.run();
    }
}
