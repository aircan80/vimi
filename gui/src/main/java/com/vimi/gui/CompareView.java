package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.comparison.ComparisonTypes;

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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Session-only local inventory comparison. Backend handles belong to the composition owner. */
final class CompareView extends BorderPane implements AutoCloseable {
    record Request(
            BuildInfo before,
            String beforeLocale,
            BuildInfo after,
            String afterLocale,
            String area,
            Long tableId) {
        Request(
                BuildInfo before,
                String beforeLocale,
                BuildInfo after,
                String afterLocale,
                String area) {
            this(before, beforeLocale, after, afterLocale, area, null);
        }
    }

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
                    "Item effects",
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
    private final TextField rawTableId = new TextField();
    private final Button rawCompare = new Button("Compare readable rows locally");
    private final Button selectedTable = new Button("Use selected table ID");
    private final javafx.scene.layout.FlowPane rawControls = new javafx.scene.layout.FlowPane(8, 8);
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
    private final Label coverageText = text("Not assessed", "muted");
    private final javafx.scene.control.TitledPane coverageDetails =
            new javafx.scene.control.TitledPane();
    private final ScrollPane contentScroll = new ScrollPane(content);
    private final Label capturedPair = text("No captured comparison pair", "muted");
    private final Label pendingSelection = text("", "muted");
    private final ActivityIndicator comparisonActivity = new ActivityIndicator("Comparing local data…");
    private final ActivityIndicator pageActivity = new ActivityIndicator("Loading findings…");
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
            // Historical preparation is a useful default hint, not a local-readiness guarantee.
            var initial =
                    choices.stream()
                            .filter(BuildInfo::prepared)
                            .findFirst()
                            .orElse(choices.getFirst());
            before.setValue(initial);
            var alternatives =
                    choices.stream()
                            .filter(
                                    candidate ->
                                            candidate.product().equals(initial.product())
                                                    && !candidate
                                                            .identity()
                                                            .equals(initial.identity()))
                            .toList();
            after.setValue(
                    alternatives.stream()
                            .filter(BuildInfo::prepared)
                            .findFirst()
                            .orElseGet(
                                    () ->
                                            alternatives.isEmpty()
                                                    ? initial
                                                    : alternatives.getFirst()));
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
                        comparisonActivity.setBusy(false);
                        cancel.setDisable(true);
                        status.setText("Canceled · inputs not assessed");
                        selectArea(selectedArea);
                    }
                });
        compare.setOnAction(event -> start());
        rawTableId.setPromptText("FileDataID");
        rawTableId.setAccessibleText("Raw table FileDataID");
        rawTableId.setPrefWidth(150);
        rawTableId.setMinWidth(100);
        rawTableId.setMaxWidth(200);
        rawTableId.textProperty().addListener((o, a, b) -> selectionChanged());
        rawCompare.setOnAction(
                event -> {
                    var id = parseTableId(rawTableId.getText());
                    if (id == null) {
                        status.setText(
                                "Enter a table FileDataID from 1 to 4294967295. No comparison was"
                                        + " started.");
                        return;
                    }
                    start(id);
                });
        selectedTable.setOnAction(
                event -> {
                    var row = results.getSelectionModel().getSelectedItem();
                    if (inventoryTableSelection(row)) {
                        rawTableId.setText(Long.toString(Long.parseLong(row.key())));
                    }
                });
        selectedTable.setDisable(true);
        rawControls
                .getChildren()
                .setAll(text("Table FileDataID", "muted"), rawTableId, selectedTable, rawCompare);
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
                        comparisonActivity,
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
                                                : resultLabel(value)
                                                        + " · "
                                                        + displayIdentity(value.key()));
                                setTooltip(
                                        empty || value == null
                                                ? null
                                                : new javafx.scene.control.Tooltip(
                                                        "Canonical identity: " + value.key()));
                                setAccessibleText(
                                        empty || value == null
                                                ? null
                                                : resultLabel(value)
                                                        + " · "
                                                        + identityTitle(value.key())
                                                        + " · Canonical identity "
                                                        + value.key());
                            }
                        });
        results.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            inspect(b);
                            selectedTable.setDisable(!inventoryTableSelection(b));
                        });
        VBox.setVgrow(results, Priority.ALWAYS);
        results.setMinHeight(180);
        var coverageScroll = new ScrollPane(coverageText);
        coverageScroll.setFitToWidth(true);
        coverageScroll.setPrefHeight(160);
        coverageScroll.setMinHeight(80);
        coverageScroll.setMaxHeight(180);
        coverageDetails.setText("Coverage details");
        coverageDetails.setAccessibleText("Coverage details, exact scopes and every reason");
        coverageDetails.setContent(coverageScroll);
        coverageDetails.setExpanded(false);
        contentScroll.setMinHeight(0);
        contentScroll.setFitToWidth(true);
        contentScroll.setFitToHeight(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
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
        var raw =
                area.equals("Tables")
                        && area.equals(runArea)
                        && capturedRequest != null
                        && capturedRequest.tableId() != null;
        heading.setText(
                raw
                        ? "Raw table rows · FileDataID " + capturedRequest.tableId()
                        : area.equals("Summary") ? "Comparison summary" : area);
        content.getChildren().setAll(compactArea, new HBox(12, heading, details));
        if (area.equals("Summary")) {
            content.getChildren()
                    .add(
                            text(
                                    session == null
                                            ? "Not compared. Choose Files, Tables, Items or Item effects, then"
                                                    + " Compare local data."
                                            : "Session-only "
                                                    + runArea
                                                    + (capturedRequest != null
                                                                    && capturedRequest.tableId()
                                                                            != null
                                                            ? " raw table findings. Known-equal"
                                                                    + " rows are"
                                                            : " findings. Known-equal"
                                                                    + " records are")
                                                    + " omitted; other areas are not assessed.",
                                    "muted"));
            content.getChildren().addAll(pageStatus, coverageDetails);
        } else if (area.equals("Files") || area.equals("Tables") || area.equals("Items") || area.equals("Item effects")) {
            content.getChildren()
                    .add(
                            text(
                                    raw
                                            ? "Recorded columns from one prepared table. Membership"
                                                    + " is uncertified; gameplay meaning is"
                                                    + " unassessed."
                                            : area.equals("Item effects")
                                                    ? "Recorded links and verified numeric inputs. Relation membership is uncertified; "
                                                            + "spell names, descriptions and gameplay calculations are unassessed."
                                            : area.equals("Items")
                                                    ? "Recorded Item / ItemSparse facts with"
                                                            + " source-qualified conflicts."
                                                            + " Membership is uncertified; effects"
                                                            + " and assets are unassessed."
                                                    : "Local "
                                                            + area.toLowerCase(
                                                                    java.util.Locale.ROOT)
                                                            + " inventory only. Readability and"
                                                            + " source coverage remain separate"
                                                            + " from content changes.",
                                    "muted"));
            if (area.equals("Tables")) {
                content.getChildren().add(rawControls);
            }
            if (area.equals(runArea))
                content.getChildren()
                        .addAll(
                                text(
                                        "Search and status filter apply only to the current"
                                                + " findings page.",
                                        "muted"),
                                new HBox(8, search, filter),
                                results,
                                new HBox(8, previous, next, retryPage),
                                pageStatus,
                                pageActivity,
                                coverageDetails,
                                pageError);
            else
                content.getChildren()
                        .add(
                                text(
                                        "Not compared for this area. Compare local data starts a"
                                                + " new session-only run.",
                                        "muted"));
        } else
            content.getChildren()
                    .add(
                            text(
                                    "Not implemented. This area has no comparison adapter in the"
                                            + " inventory preview; no zero-change conclusion is"
                                            + " available.",
                                    "muted"));
        compare.setDisable(
                pending
                        || running()
                        || !(area.equals("Files")
                                || area.equals("Tables")
                                || area.equals("Items") || area.equals("Item effects")));
        rawCompare.setDisable(pending || running() || !area.equals("Tables"));
        selectedTable.setDisable(
                !inventoryTableSelection(results.getSelectionModel().getSelectedItem()));
        if (!area.equals(runArea)) inspect(null);
        layoutForWidth();
    }

    private void start() {
        start(null);
    }

    private void start(Long tableId) {
        if (tableId != null && !selectedArea.equals("Tables")) return;
        if (closed || pending || running() || before.getValue() == null || after.getValue() == null)
            return;
        if (!Objects.equals(before.getValue().product(), after.getValue().product())
                || !Objects.equals(beforeLocale.getValue(), afterLocale.getValue())
                || beforeLocale.getValue() == null) {
            status.setText(
                    "Choose the same product and locale on both sides. Cross-product and"
                            + " cross-locale comparison is not supported.");
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
        comparisonActivity.setBusy(true);
        pageActivity.setBusy(false);
        openingToken = new Cancellation();
        cancel.setDisable(false);
        compare.setDisable(true);
        rawCompare.setDisable(true);
        selectedTable.setDisable(true);
        runArea = selectedArea;
        pageRows = List.of();
        pageHistory.clear();
        results.getItems().clear();
        pageStatus.setText("Not assessed · comparison starting");
        coverageDetails.setExpanded(false);
        coverageText.setText("Capture has not completed.");
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
                            runArea,
                            tableId);
            capturedRequest = request;
            capturedPair.setText(
                    "Captured Before: "
                            + buildLabel(request.before())
                            + " · "
                            + request.beforeLocale()
                            + " → After: "
                            + buildLabel(request.after())
                            + " · "
                            + request.afterLocale()
                            + " · "
                            + requestMode(request));
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
            selectArea(selectedArea);
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
                                    comparisonActivity.setBusy(false);
                                    if (failure != null || value == null) {
                                        status.setText(
                                                "Local comparison could not open: "
                                                        + (failure == null
                                                                ? "no session returned"
                                                                : openingProblem(failure))
                                                        + ". Missing inputs do not trigger"
                                                        + " downloads.");
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
        comparisonActivity.setBusy(running);
        cancel.setDisable(!running);
        rawCompare.setDisable(running || pending || !selectedArea.equals("Tables"));
        compare.setDisable(
                running
                        || pending
                        || !(selectedArea.equals("Files")
                                || selectedArea.equals("Tables")
                                || selectedArea.equals("Items") || selectedArea.equals("Item effects")));
        var message =
                progress.state() == ComparisonTypes.RunState.COMPLETED
                        ? conciseProgressMessage(progress.message())
                        : safe(progress.message());
        status.setText(
                words(progress.state().toString())
                        + " · "
                        + progress.observed()
                        + " identities scanned · Session only"
                        + (message.isBlank() ? "" : " · " + message));
        status.setTooltip(new javafx.scene.control.Tooltip(safe(progress.message())));
        pageStatus.setText(
                "Before: " + coverage(progress.a()) + "\nAfter: " + coverage(progress.b()));
        coverageText.setText(
                "Before\n"
                        + fullCoverage(progress.a())
                        + "\n\nAfter\n"
                        + fullCoverage(progress.b())
                        + "\n\nCaptured inputs\n"
                        + capturedInputs());
        pageStatus.setTooltip(
                new javafx.scene.control.Tooltip(
                        "Before full scope: "
                                + fullCoverage(progress.a())
                                + "\nAfter full scope: "
                                + fullCoverage(progress.b())));
        if (!running && renderedState != progress.state()) {
            renderedState = progress.state();
            loadPage(0);
        }
    }

    private static Long parseTableId(String text) {
        var value = safe(text).trim();
        if (!value.matches("[0-9]{1,10}")) return null;
        long parsed = Long.parseLong(value);
        return parsed >= 1 && parsed <= 4294967295L ? parsed : null;
    }

    private boolean inventoryTableSelection(ComparisonTypes.Result row) {
        if (pending
                || running()
                || !selectedArea.equals("Tables")
                || !"Tables".equals(runArea)
                || capturedRequest == null
                || capturedRequest.tableId() != null
                || row == null) return false;
        try {
            long id = Long.parseLong(row.key());
            return id >= 1 && id <= 4294967295L;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String requestMode(Request request) {
        return request.tableId() == null
                ? request.area().equals("Item effects") ? "Recorded item effects"
                        : request.area().equals("Items")
                        ? "Recorded Items facts"
                        : request.area() + " inventory"
                : "Raw table rows · FileDataID " + request.tableId();
    }

    private String coverage(ComparisonTypes.Coverage value) {
        if (value == null) return "Not assessed";
        var scope =
                capturedRequest != null && capturedRequest.tableId() != null
                        ? "Prepared table rows"
                        : "Tables".equals(runArea)
                                ? "Table inventory"
                                : "Item effects".equals(runArea) ? "Recorded item effects"
                                : "Items".equals(runArea)
                                        ? "Recorded Items facts"
                                        : "Selected-locale file inventory";
        return words(value.completeness().toString())
                + " · "
                + scope
                + (value.issues().isEmpty() ? "" : " · " + value.issues().size() + " notes");
    }

    private String capturedInputs() {
        if (session == null) return "No captured inputs";
        var text = new StringBuilder();
        for (var endpoint : java.util.List.of(session.before(), session.after())) {
            text.append(text.isEmpty() ? "Before" : "\nAfter")
                    .append(" identity: ")
                    .append(endpoint.identity())
                    .append("\nScope: ")
                    .append(endpoint.scope());
            new java.util.TreeMap<>(endpoint.dependencies())
                    .forEach(
                            (key, value) ->
                                    text.append('\n').append(key).append(": ").append(value));
            text.append('\n');
        }
        return text.toString();
    }

    private static String fullCoverage(ComparisonTypes.Coverage value) {
        return value == null
                ? "Not assessed"
                : value.completeness()
                        + " · "
                        + value.scope()
                        + (value.issues().isEmpty()
                                ? ""
                                : " · " + String.join("; ", value.issues()));
    }

    private static String conciseProgressMessage(String message) {
        return java.util.Arrays.stream(safe(message).split(";"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .filter(
                        part -> {
                            var lower = part.toLowerCase(java.util.Locale.ROOT);
                            return !lower.contains("session-only")
                                    && !lower.contains("known-equal")
                                    && !lower.contains("findings-only")
                                    && !lower.equals("findings only")
                                    && !lower.contains("no persistent report")
                                    && !lower.contains("no saved report");
                        })
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String displayIdentity(String key) {
        if (key.matches("[BD][0-9]{40}")) {
            return "Item " + new java.math.BigInteger(key.substring(1, 21))
                    + (key.charAt(0) == 'B' ? " · bridge link " : " · effect ")
                    + new java.math.BigInteger(key.substring(21));
        }
        return key.matches("[0-9]{20}") ? new java.math.BigInteger(key).toString() : key;
    }

    private static String identityTitle(String key) {
        return key.matches("[BD][0-9]{40}") ? displayIdentity(key) : "ID " + displayIdentity(key);
    }

    private static String fieldLabel(String key) {
        if (key.equals("effect.target.availability")) return "Effect row availability";
        if (key.startsWith("effect.")) {
            var parts = key.split("\\.", 3);
            if (parts.length == 3) return (parts[1].equals("BRIDGE") ? "ItemXItemEffect" : "ItemEffect")
                    + " · " + parts[2];
        }
        if (key.startsWith("item.")) {
            var parts = key.split("\\.", 5);
            if (parts.length == 5) {
                String source =
                        parts[1].equals("ITEM_SPARSE")
                                ? "ItemSparse"
                                : parts[1].equals("ITEM") ? "Item" : parts[1];
                return source
                        + " · "
                        + words(parts[2])
                        + (parts[4].equals("scalar") ? "" : " [" + parts[4] + "]");
            }
        }
        return switch (key) {
            case "record.isCopy" -> "Copy record";
            case "record.copySource" -> "Copy source ID";
            default -> key.startsWith("cell.") ? key.substring(5) : key;
        };
    }

    private static int fieldPriority(ComparisonTypes.FieldDelta field) {
        return switch (field.difference()) {
            case CHANGED -> 0;
            case UNKNOWN -> 1;
            case INCOMPARABLE -> 2;
            case EQUAL -> 3;
        };
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
        pageActivity.setBusy(true);
        CompletableFuture<ComparisonTypes.ResultPage> loading;
        try {
            loading = Objects.requireNonNull(handle.page(offset, 100));
        } catch (Throwable failure) {
            loading = CompletableFuture.failedFuture(failure);
        }
        loading
                .whenComplete(
                        (page, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || current != generation
                                                    || session != handle) return;
                                            pageActivity.setBusy(false);
                                            if (failure != null || page == null) {
                                                pageError.setText(
                                                        "Findings page unavailable. Existing"
                                                            + " findings remain; retry this page.");
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
                                                            || (row.key() + " " + row.fields() + ("Item effects".equals(runArea) ? " " + displayIdentity(row.key()) : ""))
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
        var identity = text(identityTitle(row.key()), "h3");
        identity.setTooltip(new javafx.scene.control.Tooltip("Canonical identity: " + row.key()));
        identity.setAccessibleText(
                identityTitle(row.key()) + " · Canonical identity " + row.key());
        inspector
                .getChildren()
                .addAll(
                        identity,
                        text(resultLabel(row) + " · " + membership(row.membership()), "muted"));
        if (session != null) {
            inspector
                    .getChildren()
                    .add(
                            text(
                                    "Before: "
                                            + endpointCaption(true)
                                            + " · "
                                            + session.before().locale()
                                            + "\nAfter: "
                                            + endpointCaption(false)
                                            + " · "
                                            + session.after().locale(),
                                    "muted"));
            var sources =
                    new javafx.scene.control.TitledPane(
                            "Captured inputs", text(capturedInputs(), "muted"));
            sources.setExpanded(false);
            inspector.getChildren().add(sources);
        }
        for (var field :
                row.fields().stream()
                        .sorted(java.util.Comparator.comparingInt(CompareView::fieldPriority))
                        .toList()) {
            var fieldHeading =
                    text(
                            fieldLabel(field.key()) + " · " + words(field.difference().toString()),
                            "h3");
            fieldHeading.setTooltip(
                    new javafx.scene.control.Tooltip("Canonical field: " + field.key()));
            fieldHeading.setAccessibleText(
                    fieldLabel(field.key())
                            + " · Canonical field "
                            + field.key()
                            + " · "
                            + field.difference());
            var box =
                    new VBox(
                            5,
                            fieldHeading,
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

    private String endpointCaption(boolean first) {
        if (capturedRequest == null)
            return first ? session.before().identity() : session.after().identity();
        var build = first ? capturedRequest.before() : capturedRequest.after();
        return build.version()
                + " · config "
                + configLabels.getOrDefault(safe(build.config()), safe(build.config()));
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
                                        capturedRequest.afterLocale(), afterLocale.getValue())
                                || (capturedRequest.tableId() != null
                                        && !capturedRequest
                                                .tableId()
                                                .toString()
                                                .equals(rawTableId.getText().trim())));
        pendingSelection.setText(
                changed
                        ? "Selection changed. Findings and progress still belong to the captured"
                              + " pair above. Compare local data again after this run finishes, or"
                              + " cancel it."
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
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.getStyleClass().add(style);
        return label;
    }

    private static String openingProblem(Throwable failure) {
        while ((failure instanceof java.util.concurrent.CompletionException
                        || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() <= 600 ? message : message.substring(0, 600) + "…";
    }

    private static VBox endpoint(String name, ComboBox<BuildInfo> build, ComboBox<String> locale) {
        build.setMaxWidth(Double.MAX_VALUE);
        build.setMinWidth(100);
        build.setAccessibleText(name + " build, exact product and version");
        locale.setAccessibleText(name + " locale");
        locale.setMinWidth(100);
        locale.setPrefWidth(110);
        locale.setMaxWidth(110);
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
            body.setCenter(contentScroll);
            compactArea.setVisible(false);
            compactArea.setManaged(false);
        } else {
            compactArea.setVisible(true);
            compactArea.setManaged(true);
            body.setCenter(drawer ? new VBox(10, back, inspectorScroll) : contentScroll);
        }
        details.setVisible(narrow);
        details.setManaged(narrow);
    }

    @Override
    public void close() {
        comparisonActivity.setBusy(false);
        pageActivity.setBusy(false);
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
