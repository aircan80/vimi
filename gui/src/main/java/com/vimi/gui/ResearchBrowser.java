package com.vimi.gui;

import static com.vimi.gui.ResearchPresentation.bytes;
import static com.vimi.gui.ResearchPresentation.columns;
import static com.vimi.gui.ResearchPresentation.qualityInk;
import static com.vimi.gui.ResearchPresentation.state;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureTypes;
import com.vibeminer.concepts.items.ItemAttributes;
import com.vibeminer.concepts.items.ItemFactState;
import com.vibeminer.concepts.items.ItemResult;
import com.vibeminer.concepts.items.ItemSummary;
import com.vibeminer.files.FileTypes;
import com.vibeminer.formats.tables.TableTypes;
import com.vimi.gui.ResearchPresentation.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Live browser using approved compact grids and inspectors. All acquisition is asynchronous. */
public final class ResearchBrowser extends BorderPane implements AutoCloseable {
    private final AsyncIconRequests iconRequests;
    private record IconPixels(int width, int height, int[] argb) {}
    private final java.util.concurrent.ThreadPoolExecutor iconPixelsWorker =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(256),
                    Thread.ofPlatform().daemon().name("browser-icon-pixels").factory(),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final Map<Long, CompletableFuture<IconPixels>> preparedIcons = new LinkedHashMap<>();
    private final java.util.Set<Long> failedIcons = new java.util.HashSet<>();
    private final String area;
    private final ResearchDataSource source;
    private final Consumer<String> navigateTable;
    private final TableView<Row> grid = new TableView<>();
    private final VBox inspector = new VBox(12);
    private final VBox mediaPanel = new VBox(8);
    private final Label hidden = text("Selected record is outside these results.", "muted");
    private final TextField search = new TextField();
    private final Label status = text("", "muted");
    private final Label count = text("", "muted");
    private final ProgressIndicator activity = new ProgressIndicator(-1);
    private final Button refresh = new Button("Refresh");
    private final Button next = new Button("Next page");
    private final Button previous = new Button("Previous page");
    private final Button media = new Button("Load media details");
    private final Map<Integer, ComboBox<com.vibeminer.concepts.items.ItemGridPage.Choice>>
            itemFilters = new LinkedHashMap<>();
    private long totalMatches = -1;
    private boolean updatingRows;
    private final Map<Integer, ComboBox<String>> filters = new LinkedHashMap<>();
    private final javafx.collections.ObservableList<Row> rows = FXCollections.observableArrayList();
    private final FilteredList<Row> filtered = new FilteredList<>(rows);
    private final List<String> history = new ArrayList<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
    private final PauseTransition iconPulse = new PauseTransition(Duration.millis(16));
    private final Map<Long, CompletableFuture<IconPixels>> iconWaiters = new LinkedHashMap<>();
    private Cancellation pageToken = new Cancellation(),
            detailToken = new Cancellation(),
            mediaToken = new Cancellation();
    private Cancellation iconToken = new Cancellation();
    private final Map<Long, javafx.scene.image.Image> icons = new LinkedHashMap<>();
    private long pageGeneration, detailGeneration, mediaGeneration;
    private boolean disposed, loaded, invalidated, filtering;
    private String currentCursor, nextCursor;
    private Row selected;
    private Long tableAfter;
    private String tableIdentity;
    private final VBox tableContent = new VBox(10);
    private Cancellation tableToken = new Cancellation();
    private long tableGeneration;

    public ResearchBrowser(String area, BuildPreparation.Job job, Consumer<String> navigateTable) {
        this(area, new ResearchSource(job), navigateTable);
    }

    ResearchBrowser(String area, ResearchDataSource source, Consumer<String> navigateTable) {
        this.area = area;
        this.source = source;
        iconRequests = new AsyncIconRequests(4, 256, source::icon);
        iconPulse.setOnFinished(event -> loadVisibleIcons());
        this.navigateTable = navigateTable;
        getStyleClass().add("research-browser");
        grid.setId("research-results");
        search.setId("research-search");
        grid.setAccessibleText(area + " · prepared build data");
        grid.getStyleClass().add(area.equals("Items") ? "items-grid" : "files-grid");
        grid.setFixedCellSize(area.equals("Items") ? 36 : 28);
        grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        var headings = columns(area);
        TableColumn<Row, Long> id = new TableColumn<>(headings.getFirst());
        id.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().id()));
        id.setPrefWidth(100);
        id.setUserData(com.vibeminer.concepts.items.ItemGridQuery.Sort.ID);
        grid.getColumns().add(id);
        if (area.equals("Items")) {
            var icon = new TableColumn<Row, Row>("");
            icon.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
            icon.setMinWidth(40);
            icon.setMaxWidth(40);
            icon.setSortable(false);
            icon.setCellFactory(
                    ignored ->
                            new TableCell<>() {
                                @Override
                                protected void updateItem(Row row, boolean empty) {
                                    super.updateItem(row, empty);
                                    setText(null);
                                    setGraphic(null);
                                    if (!empty && row != null) {
                                        iconPulse.playFromStart();
                                        var image = icons.get(row.id());
                                        if (image == null) {
                                            if (failedIcons.contains(row.id())) setText("—");
                                            else if (rowIconId(row) != null) {
                                                var indicator = new ProgressIndicator(-1);
                                                indicator.setPrefSize(16, 16);
                                                indicator.setMaxSize(16, 16);
                                                setGraphic(indicator);
                                            } else setText("—");
                                        } else {
                                            var view = new javafx.scene.image.ImageView(image);
                                            view.setFitWidth(28);
                                            view.setFitHeight(28);
                                            setGraphic(view);
                                        }
                                    }
                                }
                            });
            grid.getColumns().add(icon);
        }
        for (int i = 1; i < headings.size(); i++) {
            final int index = i;
            var column = new TableColumn<Row, String>(headings.get(i));
            column.setCellValueFactory(
                    cell -> new ReadOnlyStringWrapper(cell.getValue().cell(index)));
            column.setPrefWidth(i == 1 ? 260 : 105);
            if (area.equals("Items"))
                column.setUserData(
                        switch (i) {
                            case 1 -> com.vibeminer.concepts.items.ItemGridQuery.Sort.NAME;
                            case 2 -> com.vibeminer.concepts.items.ItemGridQuery.Sort.CLASS;
                            case 3 -> com.vibeminer.concepts.items.ItemGridQuery.Sort.SUBCLASS;
                            case 4 -> com.vibeminer.concepts.items.ItemGridQuery.Sort.QUALITY;
                            case 5 -> com.vibeminer.concepts.items.ItemGridQuery.Sort.SLOT;
                            default -> com.vibeminer.concepts.items.ItemGridQuery.Sort.LEVEL;
                        });
            if (area.equals("Files") && i == 3) column.setSortable(false);
            if (area.equals("Items") && i == 1)
                column.setCellFactory(
                        ignored ->
                                new TableCell<>() {
                                    @Override
                                    protected void updateItem(String value, boolean empty) {
                                        super.updateItem(value, empty);
                                        setText(empty ? null : value);
                                        var row =
                                                getTableRow() == null
                                                        ? null
                                                        : getTableRow().getItem();
                                        setStyle(
                                                empty || row == null
                                                        ? ""
                                                        : "-fx-text-fill: "
                                                                + qualityInk(row.cell(4))
                                                                + ";");
                                    }
                                });
            grid.getColumns().add(column);
        }
        if (area.equals("Items")) {
            grid.setItems(rows);
            grid.setSortPolicy(
                    view -> {
                        if (updatingRows) return true;
                        if (view.getSortOrder().size() > 1) {
                            updatingRows = true;
                            var last = view.getSortOrder().getLast();
                            view.getSortOrder().setAll(List.of(last));
                            updatingRows = false;
                        }
                        if (loaded) {
                            history.clear();
                            load(null);
                        }
                        return true;
                    });
        } else {
            var sorted = new SortedList<>(filtered);
            sorted.comparatorProperty().bind(grid.comparatorProperty());
            grid.setItems(sorted);
        }
        grid.setPlaceholder(
                text(
                        area.equals("Items")
                                ? "No items match these filters."
                                : "No results on this page. Clear filters or continue to the next page.",
                        "muted"));
        grid.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, before, row) -> {
                            if (!filtering && row != null) select(row);
                        });
        inspector.setPadding(new Insets(20));
        inspector.getStyleClass().add("inspector");
        inspector.getChildren().add(text("Select a record to inspect it.", "muted"));
        hidden.setVisible(false);
        hidden.setManaged(false);
        var detailScroll = new ScrollPane(inspector);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setPrefWidth(area.equals("Items") ? 365 : 340);
        detailScroll.setMinWidth(260);
        setRight(detailScroll);
        search.setPromptText(
                area.equals("Files")
                        ? "Search FileDataID, name or path"
                        : "Search name or exact ID");
        search.setAccessibleText("Search " + area);
        search.textProperty().addListener((o, b, a) -> debounce.playFromStart());
        debounce.setOnFinished(
                event -> {
                    history.clear();
                    load(null);
                });
        search.setOnAction(
                event -> {
                    debounce.stop();
                    history.clear();
                    load(null);
                });
        refresh.setOnAction(
                event -> {
                    debounce.stop();
                    history.clear();
                    load(null);
                });
        next.setOnAction(
                event -> {
                    if (nextCursor != null) {
                        history.add(currentCursor);
                        load(nextCursor);
                    }
                });
        previous.setOnAction(
                event -> {
                    if (!history.isEmpty()) load(history.removeLast());
                });
        var clear = new Button("Clear filters");
        clear.setOnAction(
                event -> {
                    filters.values().forEach(box -> box.setValue("All"));
                    updatingRows = true;
                    itemFilters
                            .values()
                            .forEach(
                                    box ->
                                            box.setValue(
                                                    new com.vibeminer.concepts.items.ItemGridPage
                                                            .Choice(null, "All")));
                    updatingRows = false;
                    search.clear();
                    history.clear();
                    load(null);
                });
        HBox title = new HBox(10, text(area, "h1"), spacer(), clear);
        title.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        String scope =
                switch (area) {
                    case "Files" ->
                            "Selected locale · community names and extension-based types · sizes are decoded metadata";
                    case "Tables" -> "Known table catalog · readiness and coverage remain explicit";
                    case "Items" ->
                            "Prepared item data · base item facts · media loads independently on demand";
                    default ->
                            "Prepared creature records · display and model references · media on demand";
                };
        VBox heading = new VBox(6, title, text(scope, "muted"));
        heading.setPadding(new Insets(18, 16, 8, 16));
        HBox toolbar = new HBox(8, search, refresh);
        toolbar.getStyleClass().addAll("toolbar", "research-toolbar");
        HBox.setHgrow(search, Priority.ALWAYS);
        if (area.equals("Files")) {
            filter(toolbar, 2, "Type");
            filter(toolbar, 4, "Downloaded");
        }
        if (area.equals("Tables")) filter(toolbar, 2, "Readiness");
        if (area.equals("Items")) {
            filter(toolbar, 2, "Class");
            filter(toolbar, 3, "Subclass");
            filter(toolbar, 4, "Quality");
            filter(toolbar, 5, "Slot");
        }
        if (area.equals("Creatures")) filter(toolbar, 3, "Name status");
        activity.setAccessibleText("Updating " + area);
        activity.setId("research-page-activity");
        activity.setPrefSize(16, 16);
        activity.setMaxSize(16, 16);
        var paging = new HBox(10, previous, next, activity, count);
        paging.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        paging.setPadding(new Insets(8, 16, 6, 16));
        status.setPadding(new Insets(0, 16, 10, 16));
        var scopeLabel =
                text(
                        area.equals("Items")
                                ? "Search, filters and sorting cover all prepared items; only this page is displayed."
                                : "Search spans the catalog; dropdown filters and sorting apply to this page.",
                        "muted");
        scopeLabel.setPadding(new Insets(0, 16, 6, 16));
        VBox content = new VBox(heading, toolbar, scopeLabel, grid, paging, status);
        if (area.equals("Tables")) {
            tableContent.setPadding(new Insets(10, 16, 10, 16));
            tableContent.setVisible(false);
            tableContent.setManaged(false);
            content.getChildren().add(tableContent);
        }
        VBox.setVgrow(grid, Priority.ALWAYS);
        setCenter(content);
        previous.setDisable(true);
        next.setDisable(true);
        activity.setVisible(false);
        activity.setManaged(false);
    }

    private void filter(HBox toolbar, int column, String name) {
        if (area.equals("Items")) {
            itemFilter(toolbar, column, name);
            return;
        }
        ComboBox<String> box = new ComboBox<>();
        box.getItems().add("All");
        box.setValue("All");
        box.setAccessibleText(
                name + (area.equals("Items") ? " · all prepared items" : " · current page"));
        box.setPrefWidth(area.equals("Items") ? 105 : 145);
        filters.put(column, box);
        box.setOnAction(
                event -> {
                    if (filtering) return;
                    if (area.equals("Items") && column == 2) {
                        filtering = true;
                        var subclass = filters.get(3);
                        if (subclass != null) {
                            subclass.getItems().setAll("All");
                            rows.stream()
                                    .filter(
                                            row ->
                                                    box.getValue().equals("All")
                                                            || row.cell(2).equals(box.getValue()))
                                    .map(row -> row.cell(3))
                                    .distinct()
                                    .sorted()
                                    .forEach(subclass.getItems()::add);
                            subclass.setValue("All");
                        }
                        filtering = false;
                    }
                    applyFilters();
                });
        var field = new VBox(3, text(name, "eyebrow"), box);
        toolbar.getChildren().add(field);
    }

    private void itemFilter(HBox toolbar, int column, String name) {
        var box = new ComboBox<com.vibeminer.concepts.items.ItemGridPage.Choice>();
        var all = new com.vibeminer.concepts.items.ItemGridPage.Choice(null, "All");
        box.getItems().add(all);
        box.setValue(all);
        box.setPrefWidth(105);
        box.setAccessibleText(name + " · complete item catalog");
        itemFilters.put(column, box);
        box.setOnAction(
                event -> {
                    if (updatingRows) return;
                    if (column == 2) {
                        updatingRows = true;
                        var subclass = itemFilters.get(3);
                        if (subclass != null) subclass.setValue(all);
                        updatingRows = false;
                    }
                    history.clear();
                    load(null);
                });
        toolbar.getChildren().add(new VBox(3, text(name, "eyebrow"), box));
    }

    private void updateItemOptions(com.vibeminer.concepts.items.ItemGridPage.Options options) {
        if (options == null) return;
        var lists =
                Map.of(
                        2,
                        options.classes(),
                        3,
                        options.subclasses(),
                        4,
                        options.qualities(),
                        5,
                        options.slots());
        updatingRows = true;
        try {
            itemFilters.forEach(
                    (column, box) -> {
                        var prior = box.getValue();
                        var choices =
                                new ArrayList<com.vibeminer.concepts.items.ItemGridPage.Choice>();
                        choices.add(
                                new com.vibeminer.concepts.items.ItemGridPage.Choice(null, "All"));
                        choices.addAll(lists.get(column));
                        if (prior != null
                                && prior.value() != null
                                && choices.stream()
                                        .noneMatch(
                                                choice ->
                                                        java.util.Objects.equals(
                                                                choice.value(), prior.value()))) {
                            choices.add(prior);
                        }
                        int selectedIndex = 0;
                        if (prior != null) {
                            for (int i = 0; i < choices.size(); i++) {
                                if (java.util.Objects.equals(
                                        choices.get(i).value(), prior.value())) {
                                    selectedIndex = i;
                                    break;
                                }
                            }
                        }
                        if (!box.getItems().equals(choices)) {
                            // Replacing choices while retaining an equal value can leave the
                            // selected index at -1 and the ComboBox button cell blank.
                            box.getSelectionModel().clearSelection();
                            box.setValue(null);
                            box.getItems().setAll(choices);
                        }
                        box.getSelectionModel().select(selectedIndex);
                    });
        } finally {
            updatingRows = false;
        }
    }

    private String itemFilterValue(int column) {
        var box = itemFilters.get(column);
        return box == null || box.getValue() == null ? null : box.getValue().value();
    }

    private com.vibeminer.concepts.items.ItemGridQuery itemQuery(String cursor) {
        var sort = com.vibeminer.concepts.items.ItemGridQuery.Sort.ID;
        boolean descending = false;
        if (!grid.getSortOrder().isEmpty()) {
            var column = grid.getSortOrder().getFirst();
            if (column.getUserData()
                    instanceof com.vibeminer.concepts.items.ItemGridQuery.Sort value) sort = value;
            descending = column.getSortType() == TableColumn.SortType.DESCENDING;
        }
        return new com.vibeminer.concepts.items.ItemGridQuery(
                search.getText().strip(),
                itemFilterValue(2),
                itemFilterValue(3),
                itemFilterValue(4),
                itemFilterValue(5),
                sort,
                descending,
                cursor == null ? 0 : Integer.parseInt(cursor),
                100);
    }

    private void applyFilters() {
        filtering = true;
        filtered.setPredicate(
                row ->
                        filters.entrySet().stream()
                                .allMatch(
                                        e ->
                                                e.getValue().getValue() == null
                                                        || e.getValue().getValue().equals("All")
                                                        || row.cell(e.getKey())
                                                                .equals(e.getValue().getValue())));
        if (selected != null && filtered.contains(selected))
            grid.getSelectionModel().select(selected);
        else grid.getSelectionModel().clearSelection();
        filtering = false;
        hidden.setVisible(selected != null && !filtered.contains(selected));
        hidden.setManaged(hidden.isVisible());
        count.setText(
                area.equals("Items")
                        ? rows.size()
                                + " shown · "
                                + (totalMatches < 0 ? rows.size() : totalMatches)
                                + " matching items"
                        : filtered.size()
                                + " of "
                                + rows.size()
                                + " records on page"
                                + (nextCursor == null ? " · end of results" : " · more available"));
        iconPulse.playFromStart();
    }

    public void ensureLoaded() {
        if (!loaded || invalidated) {
            history.clear();
            load(null);
        }
    }

    public void invalidate() {
        invalidated = true;
        selected = null;
        detailGeneration++;
        tableGeneration++;
        detailToken.cancel();
        tableToken.cancel();
        iconToken.cancel();
        cancelMedia();
        icons.clear();
        iconRequests.clear();
        preparedIcons.values().forEach(future -> future.cancel(false));
        preparedIcons.clear();
        iconPixelsWorker.getQueue().clear();
        tableContent.getChildren().clear();
        tableContent.setManaged(false);
        tableContent.setVisible(false);
        inspector
                .getChildren()
                .setAll(text("Data enriched. Select a record to inspect updated facts.", "muted"));
    }

    public void searchTable(String name) {
        search.setText(name);
        debounce.stop();
        history.clear();
        load(null);
    }

    private void load(String cursor) {
        if (disposed) return;
        loaded = true;
        failedIcons.clear();
        invalidated = false;
        pageToken.cancel();
        pageToken = new Cancellation();
        long request = ++pageGeneration;
        next.setDisable(true);
        previous.setDisable(true);
        activity.setVisible(!rows.isEmpty());
        activity.setManaged(!rows.isEmpty());
        if (rows.isEmpty()) {
            grid.setPlaceholder(loading("Loading " + area.toLowerCase(java.util.Locale.ROOT) + "…",
                    true, "research-grid-loading"));
            status.setText("Loading prepared data…");
        } else {
            status.setText("Updating " + area.toLowerCase(java.util.Locale.ROOT)
                    + "… Previous results remain available.");
        }
        CompletableFuture<ResearchPresentation.Page> result;
        try {
            result =
                    area.equals("Items")
                            ? source.items(itemQuery(cursor), pageToken)
                            : source.page(area, search.getText().strip(), cursor, pageToken);
            java.util.Objects.requireNonNull(result, "No page request returned");
        } catch (RuntimeException invalid) {
            activity.setVisible(false);
            activity.setManaged(false);
            pageFailed(invalid);
            return;
        }
        result.whenComplete(
                (page, error) ->
                        javafx.application.Platform.runLater(
                                () -> {
                                    if (disposed || request != pageGeneration) return;
                                    activity.setVisible(false);
                                    activity.setManaged(false);
                                    if (error != null || page == null) {
                                        pageFailed(error == null ? new IllegalStateException("No result returned") : error);
                                        return;
                                    }
                                    currentCursor = cursor;
                                    nextCursor = page.next();
                                    filtering = true;
                                    updatingRows = true;
                                    rows.setAll(page.rows());
                                    updatingRows = false;
                                    totalMatches = page.total();
                                    updateItemOptions(page.itemOptions());
                                    filters.forEach(
                                            (index, box) -> {
                                                String value = box.getValue();
                                                box.getItems().setAll("All");
                                                rows.stream()
                                                        .map(row -> row.cell(index))
                                                        .distinct()
                                                        .sorted()
                                                        .forEach(box.getItems()::add);
                                                box.setValue(
                                                        box.getItems().contains(value)
                                                                ? value
                                                                : "All");
                                            });
                                    filtering = false;
                                    status.setText(page.status());
                                    grid.setPlaceholder(
                                            text(
                                                    page.rows().isEmpty()
                                                            ? "No records returned\n"
                                                                    + page.status()
                                                            : "No matching records on this page. Clear filters to show them.",
                                                    "muted"));
                                    next.setDisable(nextCursor == null);
                                    previous.setDisable(history.isEmpty());
                                    applyFilters();
                                    iconPulse.playFromStart();
                                    if (selected == null && !filtered.isEmpty())
                                        grid.getSelectionModel().selectFirst();
                                    if (invalidated) ensureLoaded();
                                }));
    }

    private void pageFailed(Throwable failure) {
        activity.setVisible(false);
        activity.setManaged(false);
        if (rows.isEmpty()) {
            loaded = false;
            nextCursor = null;
            grid.setPlaceholder(text("Unable to load " + area + "\n" + errorMessage(failure), "muted"));
            count.setText("Request failed");
        }
        status.setText("Unavailable: " + errorMessage(failure)
                + (rows.isEmpty() ? "" : " · Previous results are still shown. Refresh to retry."));
        next.setDisable(nextCursor == null);
        previous.setDisable(history.isEmpty());
    }

    private CompletableFuture<IconPixels> icon(long fileDataId) {
        var existing = preparedIcons.get(fileDataId);
        if (existing != null) {
            if (!existing.isDone() || (!existing.isCompletedExceptionally() && existing.getNow(null) != null)) {
                return existing;
            }
            preparedIcons.remove(fileDataId);
        }
        if (preparedIcons.size() >= 256) {
            var iterator = preparedIcons.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue().isDone()) { iterator.remove(); break; }
            }
            if (preparedIcons.size() >= 256) {
                return CompletableFuture.failedFuture(new IllegalStateException("Icon queue full"));
            }
        }
        var future = iconRequests.request(fileDataId).thenApplyAsync(result -> {
            if (result == null || result.image() == null
                    || result.state() != com.vibeminer.formats.images.BlpImageTypes.State.READY) {
                return null;
            }
            var value = result.image();
            int width = value.width(), height = value.height(), stride = value.rowStride();
            if (width < 1 || height < 1 || width > 64 || height > 64
                    || stride < width * 4 || stride > 256) {
                throw new IllegalArgumentException("Icon pixels exceed preview bounds");
            }
            var rgba = value.rgba();
            if (rgba.length != stride * height) {
                throw new IllegalArgumentException("Icon pixel length disagrees");
            }
            var argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = y * stride + x * 4;
                    argb[y * width + x] = (rgba[at + 3] & 255) << 24
                            | (rgba[at] & 255) << 16 | (rgba[at + 1] & 255) << 8
                            | (rgba[at + 2] & 255);
                }
            }
            return new IconPixels(width, height, argb);
        }, iconPixelsWorker);
        preparedIcons.put(fileDataId, future);
        return future;
    }

    private static javafx.scene.image.WritableImage image(IconPixels value) {
        var image = new javafx.scene.image.WritableImage(value.width(), value.height());
        image.getPixelWriter().setPixels(0, 0, value.width(), value.height(),
                javafx.scene.image.PixelFormat.getIntArgbInstance(), value.argb(), 0, value.width());
        return image;
    }

    private static javafx.scene.Node iconMessage(String message, boolean pending) {
        javafx.scene.Node icon;
        if (pending) {
            var spinner = new ProgressIndicator(-1);
            spinner.setPrefSize(22, 22);
            spinner.setMaxSize(22, 22);
            spinner.setAccessibleText(message);
            icon = spinner;
        } else {
            var label = text("—", "muted");
            label.setAccessibleText(message);
            icon = label;
        }
        icon.setId("research-inspector-icon");
        javafx.scene.control.Tooltip.install(icon, new javafx.scene.control.Tooltip(message));
        return icon;
    }

    private static javafx.scene.Node loading(String message, boolean centered, String id) {
        var spinner = new ProgressIndicator(-1);
        spinner.setPrefSize(centered ? 30 : 18, centered ? 30 : 18);
        spinner.setMaxSize(centered ? 30 : 18, centered ? 30 : 18);
        spinner.setAccessibleText(message);
        var label = text(message, "muted");
        javafx.scene.layout.Pane result = centered
                ? new VBox(12, spinner, label) : new HBox(8, spinner, label);
        if (result instanceof VBox box) { box.setAlignment(javafx.geometry.Pos.CENTER); }
        if (result instanceof HBox box) { box.setAlignment(javafx.geometry.Pos.CENTER_LEFT); }
        result.setId(id);
        result.setAccessibleText(message);
        return result;
    }

    private static Long rowIconId(Row row) {
        if (row.source() instanceof com.vibeminer.concepts.items.ItemGridRow item)
            return item.iconFileDataId();
        return row.source() instanceof ItemSummary item ? item.iconFileDataId() : null;
    }

    private void loadVisibleIcons() {
        if (disposed || !area.equals("Items")) return;
        grid.applyCss();
        grid.layout();
        var visible =
                grid.lookupAll(".table-row-cell").stream()
                        .filter(
                                node ->
                                        node.isVisible()
                                                && node instanceof javafx.scene.control.TableRow<?>)
                        .map(node -> ((javafx.scene.control.TableRow<?>) node).getItem())
                        .filter(Row.class::isInstance)
                        .map(Row.class::cast)
                        .distinct()
                        .toList();
        var ids =
                visible.stream()
                        .map(ResearchBrowser::rowIconId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
        if (selected != null && rowIconId(selected) != null) ids.add(rowIconId(selected));
        iconRequests.retain(ids);
        long generation = pageGeneration;
        for (var row : visible) {
            if (rowIconId(row) == null
                    || icons.containsKey(row.id())
                    || failedIcons.contains(row.id())
                    || iconWaiters.containsKey(row.id())) continue;
            var future = icon(rowIconId(row));
            iconWaiters.put(row.id(), future);
            future.whenComplete(
                    (result, error) ->
                            javafx.application.Platform.runLater(
                                    () -> {
                                        iconWaiters.remove(row.id(), future);
                                        if (disposed || generation != pageGeneration) return;
                                        Throwable cause = error;
                                        while (cause
                                                        instanceof
                                                        java.util.concurrent.CompletionException
                                                && cause.getCause() != null)
                                            cause = cause.getCause();
                                        if (cause
                                                instanceof
                                                java.util.concurrent.CancellationException) return;
                                        if (error != null || result == null) {
                                            failedIcons.add(row.id());
                                            grid.refresh();
                                            return;
                                        }
                                        var image = image(result);
                                        if (icons.size() >= 100)
                                            icons.remove(icons.keySet().iterator().next());
                                        icons.put(row.id(), image);
                                        grid.refresh();
                                    }));
        }
    }

    private void select(Row row) {
        selected = row;
        hidden.setVisible(false);
        hidden.setManaged(false);
        detailToken.cancel();
        detailToken = new Cancellation();
        long request = ++detailGeneration;
        cancelMedia();
        iconToken.cancel();
        tableToken.cancel();
        tableGeneration++;
        tableContent.getChildren().clear();
        tableContent.setVisible(false);
        tableContent.setManaged(false);
        inspector
                .getChildren()
                .setAll(
                        text(area.equals("Items") ? "ITEM DETAILS" : "SELECTED RECORD", "eyebrow"),
                        hidden,
                        text(row.name(), "h2"),
                        loading("Loading local data…", false, "research-detail-loading"));
        guarded(() -> source.detail(area, row, detailToken))
                .whenComplete(
                        (value, error) ->
                                javafx.application.Platform.runLater(
                                        () -> {
                                            if (disposed || request != detailGeneration) return;
                                            if (error != null) {
                                                inspector
                                                        .getChildren()
                                                        .setAll(
                                                                hidden,
                                                                text(row.name(), "h2"),
                                                                text(
                                                                        "Unavailable: "
                                                                                + errorMessage(
                                                                                        error),
                                                                        "muted"));
                                                return;
                                            }
                                            showDetail(row, value);
                                        }));
    }

    private void showDetail(Row row, Object value) {
        inspector
                .getChildren()
                .setAll(
                        text(area.equals("Items") ? "ITEM DETAILS" : "SELECTED RECORD", "eyebrow"),
                        hidden);
        if (value instanceof FileTypes.BuildFile file) {
            inspector
                    .getChildren()
                    .addAll(
                            text(row.name(), "h2"),
                            fact("FileDataID", Long.toString(row.id())),
                            fact("Full path", file.path() == null ? "Not known" : file.path()),
                            fact("Name source", state(file.pathSource())),
                            fact("Type", row.cell(2)),
                            text(
                                    "Type uses the attributed extension; content has not been inspected.",
                                    "muted"),
                            fact("Decoded size", bytes(file.size())),
                            fact("Encoded download size", "Not exposed by this file listing"),
                            fact("Selected locale", source.locale()),
                            fact("Downloaded", row.cell(4)),
                            fact(
                                    "Content key",
                                    file.contentKey() == null ? "Unknown" : file.contentKey()),
                            text(
                                    "Presence applies to the selected encoded variant at assessment time. It is not an all-variants or byte-coverage measurement.",
                                    "muted"));
        } else if (value instanceof TableTypes.Entry table) {
            inspector
                    .getChildren()
                    .addAll(
                            text(row.name(), "h2"),
                            fact("FileDataID", Long.toString(row.id())),
                            fact("Readiness", state(table.state())),
                            fact("Readable / declared rows", row.cell(3) + " / " + row.cell(4)));
            if (table.counts() != null)
                inspector
                        .getChildren()
                        .addAll(
                                fact(
                                        "Format / layout",
                                        table.counts().format()
                                                + " / "
                                                + table.counts().layoutHash()),
                                fact(
                                        "Excluded records",
                                        Long.toString(table.counts().excludedRecords())));
            if (table.reason() != null)
                inspector
                        .getChildren()
                        .add(
                                text(
                                        table.reason().code() + " · " + table.reason().message(),
                                        "muted"));
            Button browse = new Button("Browse table rows");
            browse.setDisable(
                    table.state() != TableTypes.State.READY
                            && table.state() != TableTypes.State.PARTIAL);
            browse.setOnAction(
                    event -> {
                        tableAfter = null;
                        tableIdentity = null;
                        showTableRows(row);
                    });
            inspector.getChildren().add(browse);
            tableContent.getChildren().clear();
        } else if (value instanceof ResearchSource.ItemDetail selected) {
            var inspection = selected.inspection();
            showItem(row, inspection.item(), inspection.attributes(), inspection.scope(), selected.effects());
            if (selected.effectsIssue() != null)
                inspector.getChildren().add(text(selected.effectsIssue(), "muted"));
        } else if (value instanceof com.vibeminer.concepts.items.ItemInspection inspection) {
            showItem(row, inspection.item(), inspection.attributes(), inspection.scope());
        } else if (value instanceof ItemResult item) {
            showItem(
                    row,
                    item,
                    item.detail() == null ? null : item.detail().attributes(),
                    "Direct base source values");
        } else if (value instanceof CreatureTypes.CreatureResult creature) {
            showCreature(row, creature);
        } else {
            inspector.getChildren().addAll(text(row.name(), "h2"),
                    text("Local details are unavailable for this record.", "muted"));
        }
        if (area.equals("Items") || area.equals("Creatures")) {
            media.setDisable(false);
            media.setText("Load media details");
            media.setOnAction(event -> loadMedia(row));
            inspector
                    .getChildren()
                    .addAll(
                            media,
                            text(
                                    "Media is independent of data readiness. 3D rendering is not yet available.",
                                    "muted"),
                            mediaPanel);
        }
    }

    private void showItem(
            Row row, ItemResult result, ItemAttributes resolvedAttributes, String scope) {
        showItem(row, result, resolvedAttributes, scope, null);
    }

    private void showItem(Row row, ItemResult result, ItemAttributes resolvedAttributes, String scope,
            com.vibeminer.concepts.effects.EffectsTypes.Tooltip effects) {
        var detail = result.detail();
        var tooltip = new VBox(7);
        tooltip.getStyleClass().add("wow-item-tooltip");
        Label name = text(row.name(), "wow-item-name");
        name.setStyle(
                "-fx-text-fill: "
                        + switch (row.cell(4)) {
                            case "Uncommon" -> "#1eff00";
                            case "Rare" -> "#0070dd";
                            case "Epic" -> "#a335ee";
                            case "Legendary" -> "#ff8000";
                            default -> "#ffffff";
                        }
                        + ";");
        var art = new javafx.scene.image.ImageView();
        art.setFitWidth(44);
        art.setFitHeight(44);
        art.setAccessibleText("Item icon for " + row.name());
        art.setVisible(false);
        art.setManaged(false);
        var iconRegion = new javafx.scene.layout.StackPane();
        iconRegion.setMinSize(44, 44);
        iconRegion.setPrefSize(44, 44);
        iconRegion.setMaxSize(44, 44);
        iconRegion.getChildren().setAll(iconMessage("No icon recorded", false));
        var top = new HBox(12, iconRegion, name);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        tooltip.getChildren().add(top);
        if (detail != null && detail.summary().iconFileDataId() != null) {
            iconToken.cancel();
            iconToken = new Cancellation();
            long request = detailGeneration;
            var cached = icons.get(row.id());
            if (cached != null) {
                art.setImage(cached);
                art.setVisible(true);
                art.setManaged(true);
                iconRegion.getChildren().setAll(art);
            } else {
                iconRegion.getChildren().setAll(iconMessage("Loading item icon", true));
                icon(detail.summary().iconFileDataId())
                        .whenComplete(
                                (image, error) ->
                                        javafx.application.Platform.runLater(
                                                () -> {
                                                    if (disposed || request != detailGeneration)
                                                        return;
                                                    if (error != null || image == null) {
                                                        iconRegion.getChildren().setAll(iconMessage("Icon unavailable", false));
                                                        return;
                                                    }
                                                    var output = image(image);
                                                    if (icons.size() >= 100)
                                                        icons.remove(
                                                                icons.keySet().iterator().next());
                                                    icons.put(row.id(), output);
                                                    art.setImage(output);
                                                    art.setVisible(true);
                                                    art.setManaged(true);
                                                    iconRegion.getChildren().setAll(art);
                                                    grid.refresh();
                                                }));
            }
        }
        if (!row.cell(6).equals("Unknown"))
            tooltip.getChildren().add(text("Base item level " + row.cell(6), "wow-level"));
        tooltip.getChildren().add(text(row.cell(5), "wow-line"));
        inspector
                .getChildren()
                .addAll(
                        tooltip,
                        fact("Item ID / availability", row.id() + " / " + state(result.state())));
        if (detail == null) {
            inspector
                    .getChildren()
                    .add(
                            text(
                                    result.message() == null
                                            ? "Item data unavailable"
                                            : result.message(),
                                    "muted"));
            return;
        }
        if (detail.labels() != null) {
            var labels = detail.labels();
            inspector
                    .getChildren()
                    .add(
                            fact(
                                    "Class / subclass",
                                    (labels.itemClass() == null
                                                    ? row.cell(2)
                                                    : safe(labels.itemClass().text()))
                                            + " / "
                                            + (labels.subclass() == null
                                                    ? row.cell(3)
                                                    : safe(labels.subclass().text()))));
        }
        var attributes = resolvedAttributes == null ? detail.attributes() : resolvedAttributes;
        for (var line : attributes.attributes())
            tooltip.getChildren().add(text(line.text(), "wow-stat"));
        if (attributes.attributes().isEmpty())
            tooltip.getChildren()
                    .add(
                            text(
                                    attributes.state() == ItemAttributes.State.READY
                                            ? "No nonzero base attributes"
                                            : "Attributes not resolved",
                                    "wow-line"));
        for (String issue : attributes.issues()) inspector.getChildren().add(text(issue, "muted"));
        inspector.getChildren().add(text(scope, "muted"));
        if (effects != null) {
            for (var effect : effects.lines()) {
                String content = effect.text();
                if (content == null || content.isBlank())
                    content = safe(effect.name()) + " (" + state(effect.state()) + ")";
                tooltip.getChildren().add(text(effect.trigger() + ": " + content, "wow-stat"));
                if (effect.state() == com.vibeminer.concepts.effects.EffectsTypes.TextState.UNRESOLVED_TOKENS)
                    inspector.getChildren().add(text("Client spell expressions are unresolved; source text shown.", "muted"));
            }
            if (effects.state() != com.vibeminer.concepts.effects.EffectsTypes.PageState.OK)
                inspector.getChildren().add(text("Item effects: " + state(effects.state()), "muted"));
            if (effects.truncated())
                inspector.getChildren().add(text("Additional item effects omitted by the query limit", "muted"));
        }
        if (detail.facts() != null) {
            var factRows = new ArrayList<List<String>>();
            for (var fact : detail.facts().facts()) {
                if (fact.state() == ItemFactState.VALUE) {
                    for (var cell : fact.values()) {
                        if (fact.key().equals("bonding") && cell.label() != null)
                            tooltip.getChildren().add(text(cell.label(), "wow-line"));
                        if (fact.key().equals("description") && !cell.value().isBlank())
                            tooltip.getChildren().add(text(cell.value(), "wow-flavor"));
                        factRows.add(
                                List.of(
                                        fact.key(),
                                        cell.label() == null
                                                ? cell.value()
                                                : cell.label() + " · " + cell.value(),
                                        cell.table() + "." + cell.column()));
                    }
                } else factRows.add(List.of(fact.key(), state(fact.state()), "—"));
            }
            var raw =
                    new TitledPane(
                            "Source facts and provenance",
                            dataGrid(List.of("Fact", "Value / state", "Source"), factRows));
            raw.setExpanded(false);
            inspector.getChildren().add(raw);
        }
        sourceButton("Item");
        sourceButton("ItemSparse");
    }

    private void showCreature(Row row, CreatureTypes.CreatureResult result) {
        inspector
                .getChildren()
                .addAll(
                        text(row.name(), "h2"),
                        fact("Creature ID", Long.toString(row.id())),
                        fact("Availability", state(result.state())));
        if (result.detail() == null) {
            inspector.getChildren().add(text(safe(result.message()), "muted"));
            return;
        }
        var detail = result.detail();
        inspector.getChildren().add(fact("Classification", safe(detail.classificationLabel())));
        if (detail.displays() != null)
            for (var display : detail.displays()) {
                VBox card =
                        new VBox(
                                6,
                                text("Display " + display.slot().displayId(), "value"),
                                fact("State", state(display.state())),
                                fact("Model record", safe(display.modelId())),
                                fact("Model FileDataID", safe(display.modelFileDataId())));
                card.getStyleClass().add("notice");
                inspector.getChildren().add(card);
            }
        if (detail.summary().facts() != null) {
            var values =
                    detail.summary().facts().stream()
                            .map(f -> List.of(f.column(), safe(f.value())))
                            .toList();
            var raw = new TitledPane("Source facts", dataGrid(List.of("Column", "Value"), values));
            raw.setExpanded(false);
            inspector.getChildren().add(raw);
        }
        sourceButton("Creature");
        sourceButton("CreatureDisplayInfo");
        sourceButton("CreatureModelData");
    }

    private void sourceButton(String table) {
        Button button = new Button("Open " + table + " table");
        button.setOnAction(event -> navigateTable.accept(table));
        inspector.getChildren().add(button);
    }

    private void showTableRows(Row row) {
        tableContent.setVisible(true);
        tableContent.setManaged(true);
        tableToken.cancel();
        tableToken = new Cancellation();
        long request = ++tableGeneration;
        tableContent.getChildren().setAll(loading("Loading table rows…", false,
                "research-table-loading"));
        guarded(() -> source.tableRows(row.id(), tableAfter, tableIdentity, tableToken))
                .whenComplete(
                        (result, error) ->
                                javafx.application.Platform.runLater(
                                        () -> {
                                            if (disposed || request != tableGeneration) return;
                                            if (error != null || result == null) {
                                                tableContent
                                                        .getChildren()
                                                        .setAll(
                                                                text(
                                                                        "Unavailable: "
                                                                                + errorMessage(
                                                                                        error == null
                                                                                                ? new IllegalStateException("No table rows returned")
                                                                                                : error),
                                                                        "muted"));
                                                return;
                                            }
                                            var page = result.page();
                                            var columns = new ArrayList<String>();
                                            columns.add("Record ID");
                                            if (result.schema().columns() != null)
                                                result.schema()
                                                        .columns()
                                                        .forEach(c -> columns.add(c.name()));
                                            var values =
                                                    page.rows().stream()
                                                            .map(
                                                                    value -> {
                                                                        var cells =
                                                                                new ArrayList<
                                                                                        String>();
                                                                        cells.add(
                                                                                Long.toString(
                                                                                        value.row()
                                                                                                .id()));
                                                                        cells.addAll(
                                                                                value.row()
                                                                                        .values());
                                                                        return List.copyOf(cells);
                                                                    })
                                                            .toList();
                                            Button more = new Button("Next 100 rows");
                                            more.setDisable(page.nextAfter() == null);
                                            more.setOnAction(
                                                    event -> {
                                                        tableAfter = page.nextAfter();
                                                        tableIdentity = page.identity();
                                                        showTableRows(row);
                                                    });
                                            tableContent
                                                    .getChildren()
                                                    .setAll(
                                                            text(
                                                                    state(page.state())
                                                                            + " · "
                                                                            + page.rows().size()
                                                                            + " rows"
                                                                            + (page.message()
                                                                                            == null
                                                                                    ? ""
                                                                                    : " · "
                                                                                            + page
                                                                                                    .message()),
                                                                    "muted"),
                                                            dataGrid(columns, values),
                                                            more);
                                        }));
    }

    private void cancelMedia() {
        mediaToken.cancel();
        mediaGeneration++;
        mediaPanel.getChildren().clear();
    }

    private void loadMedia(Row row) {
        cancelMedia();
        mediaToken = new Cancellation();
        long request = ++mediaGeneration;
        media.setDisable(true);
        mediaPanel.getChildren().setAll(loading("Loading media independently…", false,
                "research-media-loading"));
        guarded(() -> source.media(area, row, mediaToken))
                .whenComplete(
                        (value, error) ->
                                javafx.application.Platform.runLater(
                                        () -> {
                                            if (disposed || request != mediaGeneration) return;
                                            media.setDisable(false);
                                            mediaPanel.getChildren().clear();
                                            if (error != null) {
                                                mediaPanel
                                                        .getChildren()
                                                        .add(
                                                                text(
                                                                        "Media unavailable: "
                                                                                + errorMessage(
                                                                                        error),
                                                                        "muted"));
                                                return;
                                            }
                                            if (value instanceof ItemResult item
                                                    && item.detail() != null) {
                                                var detail = item.detail();
                                                if (detail.icon() != null)
                                                    mediaPanel
                                                            .getChildren()
                                                            .add(
                                                                    fact(
                                                                            "Icon",
                                                                            safe(
                                                                                            detail.icon()
                                                                                                    .fileDataId())
                                                                                    + " · "
                                                                                    + state(
                                                                                            detail.icon()
                                                                                                    .state())));
                                                if (detail.appearances() != null)
                                                    mediaPanel
                                                            .getChildren()
                                                            .add(
                                                                    fact(
                                                                            "Appearances",
                                                                            state(
                                                                                    detail.appearances()
                                                                                            .state())));
                                            } else if (value
                                                            instanceof
                                                            CreatureTypes.CreatureResult creature
                                                    && creature.detail() != null
                                                    && creature.detail().displays() != null) {
                                                for (var display : creature.detail().displays())
                                                    if (display.model() != null)
                                                        mediaPanel
                                                                .getChildren()
                                                                .add(
                                                                        fact(
                                                                                "Model "
                                                                                        + display
                                                                                                .modelFileDataId(),
                                                                                state(
                                                                                                display.model()
                                                                                                        .state())
                                                                                        + " · "
                                                                                        + safe(
                                                                                                display.model()
                                                                                                        .message())));
                                            } else
                                                mediaPanel
                                                        .getChildren()
                                                        .add(
                                                                text(
                                                                        "Media facts unavailable for this record.",
                                                                        "muted"));
                                        }));
    }

    private static TableView<List<String>> dataGrid(
            List<String> headings, List<List<String>> values) {
        TableView<List<String>> grid = new TableView<>();
        grid.setFixedCellSize(28);
        for (int i = 0; i < headings.size(); i++) {
            final int index = i;
            var column = new TableColumn<List<String>, String>(headings.get(i));
            column.setPrefWidth(155);
            column.setCellValueFactory(
                    cell ->
                            new ReadOnlyStringWrapper(
                                    index < cell.getValue().size()
                                            ? cell.getValue().get(index)
                                            : "—"));
            grid.getColumns().add(column);
        }
        grid.setItems(FXCollections.observableArrayList(values));
        grid.setPrefHeight(320);
        grid.setMinHeight(180);
        return grid;
    }

    private static <T> CompletableFuture<T> guarded(
            java.util.function.Supplier<CompletableFuture<T>> operation) {
        try {
            return java.util.Objects.requireNonNull(operation.get(), "No request returned");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static String safe(Object value) {
        return value == null ? "Unknown" : value.toString();
    }

    private static String errorMessage(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException
                && error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Region spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static VBox fact(String name, String value) {
        return new VBox(4, text(name, "muted"), text(value, "value"));
    }

    private static Label text(String value, String style) {
        var label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    @Override
    public void close() {
        disposed = true;
        iconRequests.close();
        preparedIcons.values().forEach(future -> future.cancel(false));
        preparedIcons.clear();
        iconPixelsWorker.shutdownNow();
        activity.setVisible(false);
        activity.setManaged(false);
        if (rows.isEmpty()) { grid.setPlaceholder(text("Browser closed", "muted")); }
        inspector.getChildren().setAll(text("Browser closed", "muted"));
        tableContent.getChildren().clear();
        tableContent.setVisible(false);
        tableContent.setManaged(false);
        debounce.stop();
        iconPulse.stop();
        iconWaiters.clear();
        pageGeneration++;
        detailGeneration++;
        tableGeneration++;
        pageToken.cancel();
        detailToken.cancel();
        tableToken.cancel();
        iconToken.cancel();
        icons.clear();
        cancelMedia();
    }
}
