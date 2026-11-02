package com.vimi.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class VimiApp extends Application {
    private final BuildService service = new MockBuildService();
    private final BorderPane root = new BorderPane();
    private final javafx.scene.layout.StackPane surface = new javafx.scene.layout.StackPane(root);
    private final MockPreparationService preparations = new MockPreparationService(true);
    private final Map<String, BuildInfo> preparedResults = new HashMap<>();
    private MockPreparationService.Job attachedPreparation;
    private MockPreparationService.Job activityJob;
    private PreparationPopup preparationPopup;
    private Node preparationInvoker;
    private javafx.scene.layout.StackPane preparationOverlay;
    private final Button preparationActivity = new Button();
    private Label selectedPreparation;
    private Button selectedOpen;
    private Button previewOpening;
    private ComboBox<String> selectedSource;
    private Label preparationBusy;
    private long navigationGeneration;
    private final TableView<BuildInfo> table = new TableView<>();
    private final VBox inspector = new VBox(11);
    private final TextField search = new TextField();
    private final ComboBox<String> product = new ComboBox<>();
    private final ComboBox<String> scope = new ComboBox<>();
    private final Label status = new Label("Mock data · No connections or disk changes");
    private final Map<String, Boolean> downloadAll = new HashMap<>();
    private List<BuildInfo> builds = List.of();
    private BuildInfo selected;
    private BuildInfo opened;
    private final Map<String, Node> researchViews = new HashMap<>();
    private String openedRegion = "US";
    private boolean updating;
    private final javafx.beans.property.ObjectProperty<String> overviewMode =
            new javafx.beans.property.SimpleObjectProperty<>("On demand");

    @Override
    public void start(Stage stage) {
        preparations.observe(job -> Platform.runLater(() -> preparationChanged(job)));
        preparationActivity.setOnAction(event -> {
            if (activityJob != null) {
                if (activityJob.state() == MockPreparationService.State.READY) {
                    enterBuild(activityJob.build(), activityJob.region());
                } else {
                    showPreparation(activityJob);
                }
            }
        });
        preparationActivity.setVisible(false);
        preparationActivity.setManaged(false);
        overviewMode.addListener((observable, previous, value) -> {
            if (opened != null) {
                downloadAll.put(opened.config(), "Download all".equals(value));
            }
        });
        search.setPromptText("Search version or product");
        search.setAccessibleText("Search builds by version or product");
        search.textProperty().addListener((o, before, after) -> filter());
        product.getItems().add("All products");
        product.setValue("All products");
        product.setAccessibleText("Product filter");
        product.setOnAction(e -> filter());
        scope.getItems().addAll("All builds", "Prepared", "Current", "Saved", "Community");
        scope.setValue("All builds");
        scope.setAccessibleText("Build status filter");
        scope.setOnAction(e -> filter());
        column("Product", b -> b.edition() + " · " + b.product(), 225);
        column("Version", BuildInfo::version, 170);
        column("Build source", b -> b.source().label(), 110);
        column("Downloaded", b -> effectiveBuild(b).downloaded(), 110);
        column("Preparation", this::preparationSummary, 230);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching builds. Adjust or clear the filters."));
        table.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> {
            if (!updating && after != null) {
                selected = after;
                inspect();
            }
        });
        status.getStyleClass().add("status");
        root.setBottom(status);
        Scene scene = new Scene(surface, 1320, 850);
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (preparationPopup == null) {
                return;
            }
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                hidePreparation();
                event.consume();
            } else if (event.getCode() == javafx.scene.input.KeyCode.TAB) {
                List<Button> controls = preparationPopup.controls().stream().filter(button -> !button.isDisabled()).toList();
                int current = controls.indexOf(scene.getFocusOwner());
                int next = current < 0 ? 0 : Math.floorMod(current + (event.isShiftDown() ? -1 : 1), controls.size());
                controls.get(next).requestFocus();
                event.consume();
            }
        });
        scene.getStylesheets().add(getClass().getResource("/vimi.css").toExternalForm());
        stage.setTitle("VibeMiner · GUI prototype");
        stage.getIcons().add(brandImage());
        stage.setMinWidth(1000);
        stage.setMinHeight(680);
        stage.setScene(scene);
        showBuilds();
        refresh();
        stage.show();
    }

    private void column(String title, java.util.function.Function<BuildInfo, String> value, int width) {
        TableColumn<BuildInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private void refresh() {
        service.listBuilds().whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("Could not load mock builds. Refresh builds to retry.");
                return;
            }
            builds = result;
            if (product.getItems().size() == 1) {
                builds.stream().map(BuildInfo::product).distinct().forEach(product.getItems()::add);
            }
            if (selected == null && !builds.isEmpty()) {
                selected = builds.getFirst();
            }
            filter();
            status.setText("Mock snapshot · " + builds.size() + " builds · No network requests");
        }));
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<BuildInfo> visible = builds.stream()
                .filter(b -> (b.version() + " " + b.product() + " " + b.edition()).toLowerCase(Locale.ROOT).contains(query))
                .filter(b -> product.getValue().equals("All products") || product.getValue().equals(b.product()))
                .filter(b -> !scope.getValue().equals("Prepared") || effectiveBuild(b).prepared())
                .filter(b -> !scope.getValue().equals("Current") || b.current())
                .filter(b -> !scope.getValue().equals("Saved") || b.source() == BuildInfo.BuildSource.SAVED)
                .filter(b -> !scope.getValue().equals("Community") || b.source() == BuildInfo.BuildSource.COMMUNITY).toList();
        updating = true;
        table.setItems(FXCollections.observableArrayList(visible));
        table.getSelectionModel().select(selected);
        updating = false;
        inspect();
    }

    private void showBuilds() {
        navigationGeneration++;
        status.setText("Mock snapshot · " + builds.size() + " builds · No network requests");
        root.setTop(header(null, null));
        Button refresh = button("↻  Refresh builds", this::refresh);
        refresh.setAccessibleText("Refresh builds");
        refresh.getStyleClass().add("refresh-button");
        HBox titleRow = new HBox(12, label("Builds", "nav"), spacer(), refresh);
        titleRow.getStyleClass().addAll("toolbar", "builds-title-row");
        HBox filters = new HBox(12, search, product, scope, button("Clear filters", () -> {
            search.clear();
            product.setValue("All products");
            scope.setValue("All builds");
        }));
        filters.getStyleClass().add("toolbar");
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox list = new VBox(titleRow, filters, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        inspector.setPadding(new Insets(24));
        inspector.setPrefWidth(350);
        inspector.setMinWidth(320);
        inspector.getStyleClass().setAll("inspector");
        BorderPane content = new BorderPane(list);
        ScrollPane details = scroll(inspector);
        details.setPrefWidth(360);
        content.setRight(details);
        root.setCenter(content);
        inspect();
    }

    private void inspect() {
        inspector.getChildren().clear();
        if (selected == null) {
            inspector.getChildren().add(label("Select a build to inspect its identity.", "muted"));
            return;
        }
        BuildInfo b = selected;
        inspector.getChildren().addAll(label("SELECTED BUILD", "eyebrow"), label(b.version(), "h2"),
                label("World of Warcraft · " + b.edition(), "muted"));
        if (!table.getItems().contains(b)) {
            inspector.getChildren().add(notice("Selection hidden by filters", "Clear filters to see this build in the list."));
        }
        inspector.getChildren().addAll(notice(b.source().label(), b.source().description()),
                label("IDENTITY", "eyebrow"), fact("Product", b.product()), fact("Build config", b.config()),
                label("OBSERVATION", "eyebrow"), fact("Advertised regions", b.regions().isEmpty() ? "Never observed by VibeMiner" : b.regions()),
                fact("Downloaded", effectiveBuild(b).downloaded()));
        selectedPreparation = label(preparationSummary(b), "value");
        inspector.getChildren().add(new VBox(5, label("Preparation", "muted"), selectedPreparation));
        boolean community = b.source() == BuildInfo.BuildSource.COMMUNITY;
        if (community) {
            inspector.getChildren().addAll(
                    fact("Provider", "Third-party build history · authored mock provider"),
                    fact("File availability", "Unknown · historical listing is not proof of availability"));
        }
        ComboBox<String> region = new ComboBox<>(FXCollections.observableArrayList(b.regions().split(", ")));
        var existingJob = preparations.latest(b);
        region.setValue(community ? "No verified source route" : existingJob != null ? existingJob.region() : "US");
        selectedSource = region;
        region.setDisable(community);
        region.setAccessibleText("Mock download source");
        region.getStyleClass().add("download-source");
        Button open = button("Open build", () -> openOrPrepare(b, region.getValue(), false));
        selectedOpen = open;
        open.getStyleClass().addAll("primary", "open-build");
        open.setMaxWidth(Double.MAX_VALUE);
        open.setDisable(community);
        previewOpening = button("Preview first-open preparation", () -> openOrPrepare(b, region.getValue(), true));
        previewOpening.setVisible(!community);
        previewOpening.setManaged(!community);
        preparationBusy = label("", "muted");
        inspector.getChildren().addAll(label("DOWNLOAD SOURCE · MOCK", "eyebrow"), region, open, previewOpening, preparationBusy,
                label(community ? "Opening requires verified build metadata and a source route. Community history alone does not provide these." : "First opens simulate metadata preparation. No content is fetched or stored.", "muted"));
        updatePreparationControls();
    }

    @Override
    public void stop() {
        preparations.close();
    }

    private BuildInfo effectiveBuild(BuildInfo build) {
        return preparedResults.getOrDefault(build.config(), build);
    }

    private String preparationSummary(BuildInfo build) {
        var job = preparations.latest(build);
        return job == null ? (build.prepared() ? "Prepared" : "Not prepared") : job.summary();
    }

    private void updatePreparationControls() {
        if (selected == null || selectedOpen == null) {
            return;
        }
        var job = preparations.latest(selected);
        var active = preparations.active();
        boolean sameActive = job != null && job.active();
        boolean busy = active != null && active != job;
        boolean community = selected.source() == BuildInfo.BuildSource.COMMUNITY;
        selectedPreparation.setText(preparationSummary(selected));
        selectedOpen.setText(sameActive ? "View preparation" : job != null
                && (job.state() == MockPreparationService.State.FAILED || job.state() == MockPreparationService.State.CANCELED)
                ? "Review preparation" : "Open build");
        selectedOpen.setDisable(community || (busy && !effectiveBuild(selected).prepared()));
        selectedSource.setDisable(community || sameActive);
        previewOpening.setDisable(active != null);
        preparationBusy.setText(busy ? "Another build is preparing: " + active.build().version() : "");
        preparationBusy.setManaged(busy);
        preparationBusy.setVisible(busy);
    }

    private void openOrPrepare(BuildInfo build, String region, boolean preview) {
        var job = preparations.latest(build);
        if (!preview && job != null && job.state() != MockPreparationService.State.READY) {
            showPreparation(job);
        } else if (!preview && effectiveBuild(build).prepared()) {
            enterBuild(build, region);
        } else if (!preview && job != null && job.state() == MockPreparationService.State.READY) {
            enterBuild(build, job.region());
        } else if (preparations.active() == null) {
            showPreparation(preparations.start(build, region));
        } else {
            showPreparation(preparations.active());
        }
    }

    private void enterBuild(BuildInfo build, String region) {
        long requestGeneration = navigationGeneration;
        service.openBuild(build).whenComplete((result, error) -> Platform.runLater(() -> {
            if (error != null) {
                status.setText("Could not open mock build. Choose Open build to retry.");
                return;
            }
            preparedResults.put(build.config(), result);
            table.refresh();
            if (requestGeneration != navigationGeneration) {
                return;
            }
            opened = result;
            openedRegion = region;
            if (activityJob != null && activityJob.build().config().equals(build.config()) && !activityJob.active()) {
                activityJob = null;
                updateActivity();
            }
            showOverview();
        }));
    }

    private void preparationChanged(MockPreparationService.Job job) {
        if (preparations.latest(job.build()) != job) {
            return;
        }
        activityJob = preparations.active() != null ? preparations.active() : job;
        if (job.state() == MockPreparationService.State.READY) {
            BuildInfo build = job.build();
            preparedResults.put(build.config(), new BuildInfo(build.product(), build.edition(), build.version(),
                    build.regions(), build.config(), build.source(), true, build.totalGiB(), build.storedGiB()));
        }
        table.refresh();
        updatePreparationControls();
        updateActivity();
        if (attachedPreparation == job && preparationPopup != null) {
            preparationPopup.update(preparations.active() != null && preparations.active() != job);
            if (job.state() == MockPreparationService.State.READY) {
                hidePreparation();
                enterBuild(job.build(), job.region());
            }
        } else if (preparationPopup != null) {
            preparationPopup.update(preparations.active() != null && preparations.active() != attachedPreparation);
        }
    }

    private void updateActivity() {
        preparationActivity.setVisible(activityJob != null);
        preparationActivity.setManaged(activityJob != null);
        if (activityJob != null) {
            preparationActivity.setText(activityJob.build().version() + " · " + (activityJob.active()
                    ? "View preparation" : activityJob.summary()));
        }
    }

    private void showPreparation(MockPreparationService.Job job) {
        if (preparationOverlay != null) {
            hidePreparation();
        }
        preparationInvoker = surface.getScene().getFocusOwner();
        attachedPreparation = job;
        preparationPopup = new PreparationPopup(job, this::hidePreparation,
                () -> preparations.cancel(job),
                () -> {
                    if (preparations.active() == null) {
                        showPreparation(preparations.start(job.build(), job.region()));
                    }
                }, () -> preparations.advance(job), () -> preparations.fail(job));
        preparationPopup.update(preparations.active() != null && preparations.active() != job);
        preparationOverlay = new javafx.scene.layout.StackPane(preparationPopup);
        preparationOverlay.getStyleClass().add("preparation-overlay");
        preparationOverlay.setPadding(new Insets(18));
        surface.getChildren().add(preparationOverlay);
        root.setDisable(true);
        preparationPopup.focusHeading();
    }

    private void hidePreparation() {
        attachedPreparation = null;
        if (preparationOverlay != null) {
            surface.getChildren().remove(preparationOverlay);
        }
        preparationOverlay = null;
        preparationPopup = null;
        root.setDisable(false);
        if (preparationInvoker != null && preparationInvoker.getScene() != null && !preparationInvoker.isDisabled()) {
            preparationInvoker.requestFocus();
        } else {
            table.requestFocus();
        }
        preparationInvoker = null;
    }

    private void showOverview() {
        navigationGeneration++;
        BuildInfo b = opened;
        overviewMode.set(downloadAll.getOrDefault(b.config(), false) ? "Download all" : "On demand");
        root.setTop(buildShell("Overview"));
        root.setCenter(scroll(BuildOverviewView.create(b, openedRegion, overviewMode, this::showResearchArea)));
        status.setText("Mock build · " + b.product() + " " + b.version() + " · No backend connected");
    }

    private VBox buildShell(String activeArea) {
        HBox navigation = new HBox(4);
        navigation.getStyleClass().add("area-navigation");
        for (String area : List.of("Overview", "Files", "Tables", "Items", "Creatures")) {
            Button action = button(area, () -> {
                if (area.equals("Overview")) {
                    showOverview();
                } else {
                    showResearchArea(area);
                }
            });
            action.getStyleClass().add("area-link");
            if (area.equals(activeArea)) {
                action.getStyleClass().add("area-active");
                action.setAccessibleHelp("Current build area");
            }
            navigation.getChildren().add(action);
        }
        navigation.setAccessibleText("Selected build areas");
        return new VBox(overviewHeader(opened), navigation);
    }

    private void showResearchArea(String area) {
        navigationGeneration++;
        root.setTop(buildShell(area));
        String key = opened.config() + ":" + area;
        root.setCenter(researchViews.computeIfAbsent(key, ignored -> switch (area) {
            case "Files" -> FilesView.create(opened);
            case "Items" -> new ItemsView(opened, this::showSourceTable);
            default -> researchView(area);
        }));
        status.setText("Mock " + area.toLowerCase(Locale.ROOT) + " · " + opened.product() + " "
                + opened.version() + " · Authored samples · No backend connected");
    }

    private Node researchView(String area) {
        List<MockResearchData.Entry> entries = MockResearchData.entries(area);
        javafx.scene.control.ListView<MockResearchData.Entry> records = new javafx.scene.control.ListView<>();
        records.setAccessibleText(area + " sample results");
        records.setPlaceholder(label("No matching samples. Clear the search to see all entries.", "muted"));
        records.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MockResearchData.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null);
                if (empty || entry == null) {
                    setGraphic(null);
                } else {
                    VBox content = new VBox(4, label(entry.toString(), "value"), label(entry.kind(), "muted"));
                    content.setPadding(new Insets(7, 4, 7, 4));
                    setGraphic(content);
                }
            }
        });
        VBox detail = new VBox(16);
        detail.setPadding(new Insets(24));
        detail.getStyleClass().add("inspector");
        detail.setPrefWidth(360);
        detail.getChildren().add(label("Select a sample to inspect it.", "muted"));
        records.getSelectionModel().selectedItemProperty().addListener((observable, previous, entry) -> {
            if (entry == null) {
                return;
            }
            detail.getChildren().setAll(label("SELECTED " + (area.equals("Tables") ? "TABLE" : "RECORD"), "eyebrow"),
                    label(entry.name(), "h2"), fact("Identity", entry.id()), fact("Kind", entry.kind()),
                    label(entry.details(), "research-details"), fact("Exact mock build", opened.product() + " " + opened.version()),
                    fact("Research locale", "enUS"), notice("Authored sample", "These facts illustrate the interface. They were not read from this build."));
            if (!entry.sourceTable().isEmpty()) {
                detail.getChildren().add(button("View source table · " + entry.sourceTable(), () -> showSourceTable(entry.sourceTable())));
            }
        });
        TextField query = new TextField();
        query.setPromptText("Search " + area.toLowerCase(Locale.ROOT) + " by name or ID");
        query.setAccessibleText("Search " + area + " samples");
        Label count = label(entries.size() + " samples", "muted");
        query.textProperty().addListener((observable, previous, value) -> {
            String term = value.strip().toLowerCase(Locale.ROOT);
            var visible = entries.stream().filter(entry -> (entry.id() + " " + entry.name())
                    .toLowerCase(Locale.ROOT).contains(term)).toList();
            var selection = records.getSelectionModel().getSelectedItem();
            records.setItems(FXCollections.observableArrayList(visible));
            if (visible.contains(selection)) {
                records.getSelectionModel().select(selection);
            } else {
                records.getSelectionModel().clearSelection();
                detail.getChildren().setAll(label("Select a sample to inspect it.", "muted"));
            }
            count.setText(visible.size() + " samples");
        });
        HBox filters = new HBox(12, query, count);
        filters.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(query, Priority.ALWAYS);
        VBox title = new VBox(12, label(area, "h1"),
                label("Interactive design samples · " + opened.edition() + " · " + opened.version(), "muted"), filters);
        title.setPadding(new Insets(24, 20, 16, 20));
        VBox results = new VBox(title, records);
        VBox.setVgrow(records, Priority.ALWAYS);
        BorderPane view = new BorderPane(results);
        ScrollPane inspectorPane = scroll(detail);
        inspectorPane.setPrefWidth(380);
        view.setRight(inspectorPane);
        view.getProperties().put("records", records);
        view.getProperties().put("query", query);
        records.setItems(FXCollections.observableArrayList(entries));
        records.getSelectionModel().selectFirst();
        return view;
    }

    private void showSourceTable(String tableName) {
        showResearchArea("Tables");
        Node view = researchViews.get(opened.config() + ":Tables");
        if (view.getProperties().get("records") instanceof javafx.scene.control.ListView<?> records) {
            // Clear an earlier table filter before following an exact source declaration.
            if (view.getProperties().get("query") instanceof TextField query) {
                query.clear();
            }
            for (int index = 0; index < records.getItems().size(); index++) {
                if (records.getItems().get(index) instanceof MockResearchData.Entry entry
                        && entry.id().equals(tableName)) {
                    records.getSelectionModel().select(index);
                    records.scrollTo(index);
                    break;
                }
            }
        }
    }

    private HBox overviewHeader(BuildInfo b) {
        HBox bar = header(null, null);
        // Replace the spacer and mock badge with the selected build's compact identity.
        bar.getChildren().remove(1, bar.getChildren().size());
        HBox fields = new HBox(16,
                headerField("GAME / EDITION", label("World of Warcraft · " + b.edition(), "header-value")),
                headerField("PRODUCT ID", label(b.product(), "header-value")),
                headerField("BUILD", label(b.version(), "header-value")),
                headerField("LOCALE", label("enUS", "header-value")),
                headerField("STATUS", label("Partial", "header-value")),
                headerField("RETAINED", label(b.downloaded(), "retained-value")));
        fields.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        fields.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(fields, Priority.ALWAYS);
        Button choose = button("← Choose build", this::showBuilds);
        choose.getStyleClass().add("header-menu");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        bar.setSpacing(16);
        bar.getChildren().addAll(fields, preparationActivity, choose);
        return bar;
    }

    private static VBox headerField(String title, Node value) {
        Label heading = label(title, "header-label");
        heading.setWrapText(false);
        if (value instanceof Label text) {
            text.setWrapText(false);
            text.setMinWidth(Region.USE_PREF_SIZE);
            text.setMaxWidth(Double.MAX_VALUE);
        }
        VBox field = new VBox(2, heading, value);
        field.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        field.setMinWidth(Region.USE_PREF_SIZE);
        return field;
    }

    private HBox header(String area, Node action) {
        ImageView mark = new ImageView(brandImage());
        mark.setFitWidth(36);
        mark.setFitHeight(36);
        mark.setPreserveRatio(true);
        mark.setSmooth(true);
        HBox wordmark = new HBox(label("Vibe", "brand-vibe"), label("Miner", "brand-miner"));
        wordmark.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox brand = new HBox(8, mark, wordmark);
        brand.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox header = new HBox(20, brand);
        if (area != null) {
            header.getChildren().add(label(area, "nav"));
        }
        header.getChildren().addAll(spacer(), preparationActivity, label("MOCK DATA", "badge"));
        if (action != null) {
            header.getChildren().add(action);
        }
        header.getStyleClass().add("header");
        return header;
    }

    private static Image brandImage() {
        return new Image(VimiApp.class.getResource("/branding/vibeminer.png").toExternalForm());
    }

    private static VBox fact(String title, String value) {
        return new VBox(5, label(title, "muted"), label(value, "value"));
    }

    private static VBox notice(String title, String message) {
        VBox box = new VBox(7, label(title, "value"), label(message, "muted"));
        box.getStyleClass().add("notice");
        return box;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static Region spacer() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }

    private static ScrollPane scroll(Node node) {
        ScrollPane pane = new ScrollPane(node);
        pane.setFitToWidth(true);
        pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return pane;
    }
}
