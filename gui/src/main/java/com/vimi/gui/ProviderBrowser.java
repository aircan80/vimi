package com.vimi.gui;

import com.vibeminer.access.Cancellation;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared local catalogue presentation; provider components own normalization and field precedence.
 */
abstract class ProviderBrowser<T> extends BorderPane implements AutoCloseable {
    record Column<T>(String name, Function<T, String> value, int width) {}

    record Result<T>(List<T> rows, List<String> diagnostics) {
        Result {
            rows = List.copyOf(rows);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record Fact(String text, String style) {}

    final BuildPreparation.Job job;
    final DataSourcesService sources;
    private final String area;
    private LocationMapView locationMap;
    private final TableView<T> table = new TableView<>();
    private final TextField search = new TextField();
    private final Label status = new Label(), pageCount = new Label();
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final java.util.concurrent.ThreadPoolExecutor loader = worker("provider-catalogue");
    private final java.util.concurrent.ThreadPoolExecutor queries = worker("provider-search");
    private CompletableFuture<Result<T>> operation;
    private CompletableFuture<List<T>> query;
    private long queryGeneration;
    private String catalogueStatus = "Catalogue has not been loaded";
    private final VBox details = new VBox(12);
    private final Button previous = new Button("Previous"), next = new Button("Next");
    private Cancellation token = new Cancellation();
    private List<T> all = List.of(), filtered = List.of();
    private long generation, workRevision = -1, sourceRevision = -1;
    private int offset;
    private boolean loaded, loading, closed;

    ProviderBrowser(
            BuildPreparation.Job job,
            DataSourcesService sources,
            String area,
            List<Column<T>> columns) {
        this.job = job;
        this.sources = sources;
        this.area = area;
        search.setPromptText("Search " + area.toLowerCase(Locale.ROOT) + " by ID or text");
        search.setAccessibleText("Search " + area);
        HBox.setHgrow(search, Priority.ALWAYS);
        var reload = new Button("Reload " + area.toLowerCase(Locale.ROOT));
        reload.setOnAction(
                e -> {
                    invalidate();
                    ensureLoaded();
                });
        status.setText("Catalogue has not been loaded");
        status.setWrapText(true);
        activity.setPrefSize(16, 16);
        activity.setMaxSize(16, 16);
        activity.setAccessibleText("Loading " + area);
        activity.setId("provider-activity");
        activity.setVisible(false);
        activity.setManaged(false);
        var feedback = new HBox(8, activity, status);
        feedback.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        setTop(new VBox(10, new HBox(10, search, reload), feedback));
        for (var definition : columns) {
            var column = new TableColumn<T, String>(definition.name());
            column.setCellValueFactory(
                    c -> new ReadOnlyStringWrapper(definition.value().apply(c.getValue())));
            column.setPrefWidth(definition.width());
            column.setSortable(false);
            table.getColumns().add(column);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching " + area.toLowerCase(Locale.ROOT)));
        table.setAccessibleText("Merged " + area + " list");
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> show(b));
        details.setPrefWidth(340);
        details.setMinWidth(260);
        details.setPadding(new javafx.geometry.Insets(16));
        details.getStyleClass().add("inspector");
        var scroll = new ScrollPane(details);
        scroll.setFitToWidth(true);
        var split = new SplitPane(table, scroll);
        split.setDividerPositions(.7);
        setCenter(split);
        previous.setOnAction(
                e -> {
                    offset = Math.max(0, offset - 100);
                    page();
                });
        next.setOnAction(
                e -> {
                    offset += 100;
                    page();
                });
        setBottom(new HBox(10, previous, next, pageCount));
        setPadding(new javafx.geometry.Insets(16));
        search.textProperty().addListener((o, a, b) -> filter());
        page();
    }

    abstract Supplier<Result<T>> request(NativePreparationWork work, Cancellation cancel);

    abstract long id(T row);

    abstract String searchable(T row);

    abstract List<Fact> inspection(T row);

    final void attachLocationMap(LocationMapView view) { locationMap = view; }

    void ensureLoaded() {
        if (closed || job == null || !(job.work() instanceof NativePreparationWork work))
            return;
        long wr = work.revision(), sr = sources == null ? 0 : sources.revision();
        if ((loaded || loading) && workRevision == wr && sourceRevision == sr) return;
        workRevision = wr;
        sourceRevision = sr;
        beginLoad(cancel -> request(work, cancel), () -> wr == work.revision()
                && sr == (sources == null ? 0 : sources.revision()));
    }

    /** Freezes borrowed source choices on FX; catalogue work runs on one bounded worker. */
    final void beginLoad(Function<Cancellation, Supplier<Result<T>>> request,
            java.util.function.BooleanSupplier current) {
        if (closed) { return; }
        invalidate();
        loading = true;
        long requestId = generation;
        var cancel = token;
        setActivity(true, table.getItems().isEmpty()
                ? "Loading Blizzard and prepared external data…"
                : "Updating catalogue… Previous results remain available.");
        if (table.getItems().isEmpty()) { table.setPlaceholder(loadingPlaceholder()); }
        try {
            Supplier<Result<T>> frozen = request.apply(cancel);
            operation = CompletableFuture.supplyAsync(() -> {
                cancel.check();
                return frozen.get();
            }, loader);
        } catch (RuntimeException failure) {
            operation = CompletableFuture.failedFuture(failure);
        }
        operation.whenComplete((snapshot, error) -> Platform.runLater(() -> {
            if (closed || requestId != generation) { return; }
            loading = false;
            if (!current.getAsBoolean()) {
                setActivity(false, "Prepared data changed. Reload to update the catalogue.");
                if (table.getItems().isEmpty()) { table.setPlaceholder(new Label("Reload to update prepared data")); }
                return;
            }
            if (error != null || snapshot == null) {
                setActivity(false, table.getItems().isEmpty()
                        ? "Catalogue unavailable. Reload to retry."
                        : "Update unavailable. Previous results are still shown. Reload to retry.");
                if (table.getItems().isEmpty()) { table.setPlaceholder(new Label("Catalogue unavailable · Reload to retry")); }
                return;
            }
            loaded = true;
            present(snapshot);
        }));
    }

    void present(Result<T> snapshot) {
        if (closed) { return; }
        all = snapshot.rows();
        loaded = true;
        catalogueStatus = (
                all.size()
                        + " "
                        + area.toLowerCase(Locale.ROOT)
                        + " · "
                        + String.join(" · ", snapshot.diagnostics())
                        + " · Coverage may be partial");
        filter();
    }

    void invalidate() {
        generation++;
        token.cancel();
        token = new Cancellation();
        loaded = false;
        loading = false;
        if (operation != null) { operation.cancel(false); }
        loader.getQueue().clear();
        cancelQuery();
        setActivity(false, table.getItems().isEmpty()
                ? "Catalogue has not been loaded" : "Prepared data changed. Reload to update the catalogue.");
    }

    private void filter() {
        if (closed) { return; }
        cancelQuery();
        String term = search.getText().strip().toLowerCase(Locale.ROOT);
        Long exact = com.vibeminer.concepts.core.ConceptQueries.tryParseId(term);
        var values = all;
        if (term.isEmpty()) {
            filtered = values;
            offset = 0;
            page();
            setActivity(loading, loading ? "Updating catalogue… Previous results remain available." : catalogueStatus);
            return;
        }
        java.util.function.Predicate<T> matches = row -> exact != null ? id(row) == exact
                : searchable(row).toLowerCase(Locale.ROOT).contains(term);
        // Only a small bounded catalogue can be filtered synchronously. Larger source snapshots
        // retain the old page while the single bounded search worker computes new matches.
        if (values.size() <= 1000) {
            filtered = values.stream().filter(matches).toList();
            offset = 0;
            page();
            setActivity(loading, loading ? "Updating catalogue… Previous results remain available." : catalogueStatus);
            return;
        }
        long requestId = queryGeneration;
        setActivity(true, "Updating matches… Previous results remain available.");
        if (table.getItems().isEmpty()) { table.setPlaceholder(loadingPlaceholder()); }
        query = CompletableFuture.supplyAsync(() -> values.stream().filter(matches).toList(), queries);
        query.whenComplete((result, error) -> Platform.runLater(() -> {
            if (closed || requestId != queryGeneration) { return; }
            if (error != null) {
                setActivity(loading, "Search unavailable. Previous results are still shown.");
                if (table.getItems().isEmpty()) { table.setPlaceholder(new Label("Search unavailable · Try again")); }
                return;
            }
            filtered = result;
            offset = 0;
            page();
            setActivity(loading, loading ? "Updating catalogue… Previous results remain available." : catalogueStatus);
        }));
    }

    private void cancelQuery() {
        queryGeneration++;
        if (query != null) { query.cancel(false); }
        queries.getQueue().clear();
    }

    private void setActivity(boolean pending, String message) {
        activity.setVisible(pending && !table.getItems().isEmpty());
        activity.setManaged(activity.isVisible());
        activity.setAccessibleText(message);
        status.setText(message);
        if (pending && table.getItems().isEmpty()) { table.setPlaceholder(loadingPlaceholder()); }
    }

    private VBox loadingPlaceholder() {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(30, 30);
        spinner.setMaxSize(30, 30);
        spinner.setAccessibleText("Loading " + area);
        var message = new Label("Loading " + area + "…");
        message.getStyleClass().add("muted");
        var content = new VBox(12, spinner, message);
        content.setAlignment(javafx.geometry.Pos.CENTER);
        content.setId("provider-grid-loading");
        content.setAccessibleText(message.getText());
        return content;
    }

    private static java.util.concurrent.ThreadPoolExecutor worker(String name) {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon().name(name).factory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    private void page() {
        int end = Math.min(filtered.size(), offset + 100);
        offset = Math.min(offset, end);
        table.getItems().setAll(filtered.subList(offset, end));
        table.setPlaceholder(new Label("No matching " + area.toLowerCase(Locale.ROOT)));
        previous.setDisable(offset == 0);
        next.setDisable(end >= filtered.size());
        pageCount.setText(
                (filtered.isEmpty() ? 0 : offset + 1)
                        + "–"
                        + end
                        + " of "
                        + filtered.size()
                        + " matches");
        show(null);
    }

    private void show(T row) {
        details.getChildren().clear();
        if (locationMap != null) locationMap.select(row == null ? null : id(row));
        var facts =
                row == null
                        ? List.of(new Fact("Select a record to inspect its sources.", "muted"))
                        : inspection(row);
        int factIndex = 0;
        for (var fact : facts) {
            var label = new Label(fact.text());
            label.setWrapText(true);
            label.getStyleClass().add(fact.style());
            details.getChildren().add(label);
            if (++factIndex == 2 && row != null && locationMap != null) details.getChildren().add(locationMap);
        }
    }

    static String known(Object value) {
        return value == null ? "Unknown" : value.toString().isEmpty() ? "None" : value.toString();
    }

    static String evidence(Object value, Object selected, boolean winner) {
        return value == null
                ? ""
                : winner
                        ? " · selected"
                        : java.util.Objects.equals(value, selected)
                                ? " · agrees"
                                : " · alternative";
    }

    static String provenance(String product, String build, String locale, String version) {
        return "Product/flavor: "
                + known(product)
                + " · Build: "
                + known(build)
                + " · Locale: "
                + locale
                + "\nSnapshot: "
                + version;
    }

    @Override
    public void close() {
        closed = true;
        if (locationMap != null) locationMap.close();
        token.cancel();
        generation++;
        cancelQuery();
        if (operation != null) { operation.cancel(false); }
        loader.shutdownNow();
        queries.shutdownNow();
        setActivity(false, "Catalogue closed");
        if (table.getItems().isEmpty()) { table.setPlaceholder(new Label("Catalogue closed")); }
    }
}
