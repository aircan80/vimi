package com.vimi.gui;

import com.vibeminer.access.Cancellation;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
        setTop(new VBox(10, new HBox(10, search, reload), status));
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
        invalidate();
        loading = true;
        long requestId = generation;
        var cancel = token;
        workRevision = wr;
        sourceRevision = sr;
        status.setText("Loading Blizzard and prepared external data…");
        // Freeze borrowed handles and provider choices before dispatch.
        Supplier<Result<T>> request = request(work, cancel);
        CompletableFuture.supplyAsync(request, command -> Thread.startVirtualThread(command))
                .whenComplete(
                        (snapshot, error) ->
                                Platform.runLater(
                                        () -> {
                                            if (closed
                                                    || requestId != generation
                                                    || wr != work.revision()) return;
                                            loading = false;
                                            if (error != null) {
                                                status.setText(
                                                        "Catalogue unavailable. Reload to retry.");
                                                return;
                                            }
                                            loaded = true;
                                            present(snapshot);
                                        }));
    }

    void present(Result<T> snapshot) {
        all = snapshot.rows();
        filter();
        status.setText(
                all.size()
                        + " "
                        + area.toLowerCase(Locale.ROOT)
                        + " · "
                        + String.join(" · ", snapshot.diagnostics())
                        + " · Coverage may be partial");
    }

    void invalidate() {
        generation++;
        token.cancel();
        token = new Cancellation();
        loaded = false;
        loading = false;
        all = List.of();
        filter();
    }

    private void filter() {
        String term = search.getText().strip().toLowerCase(Locale.ROOT);
        Long exact = com.vibeminer.concepts.core.ConceptQueries.tryParseId(term);
        filtered = all.stream().filter(n -> exact != null ? id(n) == exact
                : term.isEmpty() || searchable(n).toLowerCase(Locale.ROOT).contains(term)).toList();
        offset = 0;
        page();
    }

    private void page() {
        int end = Math.min(filtered.size(), offset + 100);
        offset = Math.min(offset, end);
        table.getItems().setAll(filtered.subList(offset, end));
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
    }
}
