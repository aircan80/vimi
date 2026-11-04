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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public final class VimiApp extends Application {
    private BuildService service;
    private DataSourcesView dataSourcesView;
    private CompareView comparisonView;
    private SettingsView settingsView;
    private java.util.concurrent.CompletableFuture<? extends BuildService> startup;
    private boolean closing;
    private BuildPreparation.Job preparationFocus;
    private PreparationPane preparationPane;
    private BuildPreparation.Job viewedPreparation;
    private final Label liveHeaderStatus = new Label();
    private Runnable preparationOpenLabelUpdater;
    private final Map<String,String> preparationLocales = new HashMap<>();
    private final javafx.animation.Timeline preparationClock = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(250),event -> updatePreparationActivity()));
    private boolean loading;
    private boolean catalogReloadPending, catalogRefreshPending;
    private BuildInfo realOpeningBuild;
    private String realOpeningRegion;
    private boolean realOpeningActive;
    private boolean realOpeningStopping;
    private long realOpeningStartedNanos;
    private Label openingElapsedLabel;
    private final javafx.scene.control.ProgressIndicator openingSpinner = new javafx.scene.control.ProgressIndicator(-1);
    private final javafx.animation.Timeline openingClock = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> updateOpeningActivity()));
    private javafx.scene.control.Dialog<Void> realOpeningDialog;
    private final Button openingActivity = new Button();
    private record LoadedMetadata(com.vibeminer.builds.BuildTypes.BuildRef build,
            com.vibeminer.builds.BuildTypes.BuildMetadata metadata) {}
    private final Map<String, LoadedMetadata> realOpened = new java.util.LinkedHashMap<>();
    private final Map<String, String> closedPreparations = new HashMap<>();
    private final Map<String, String> openingProblems = new HashMap<>();
    private BuildInfo metadataOverviewBuild;
    private VBox metadataOverviewMetrics;
    private BuildLoadSnapshot displayedLoadSnapshot;
    private String accountingSelection;
    private String accountingPending, accountingFailure;
    private long accountingGeneration;
    private javafx.scene.layout.StackPane drainOverlay;
    private long catalogGeneration;
    private final Button issuesButton = new Button("Refresh issues");
    private final javafx.scene.control.ProgressIndicator catalogActivity = new javafx.scene.control.ProgressIndicator(-1);
    private final Button refreshButton = new Button("↻  Refresh builds");
    private final Button refreshSourcesButton = new Button("↻  Refresh sources");
    private SourceRefresh sourceRefresh;
    private SourceRefreshDialog sourceRefreshDialog;
    private final com.vibeminer.activity.NetworkActivity networkActivity = new com.vibeminer.activity.NetworkActivity();
    private NetworkActivityModel networkActivityModel;
    private NetworkActivityView networkActivityView;
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
    private final ListView<String> product = new ListView<>();
    private final Label buildsHeading = new Label("Builds");
    private final Label buildCount = new Label();
    private boolean updatingProducts;
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
        networkActivityModel = new NetworkActivityModel(networkActivity, stage);
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
        search.setPromptText("Search builds");
        search.setAccessibleText("Search builds by version or product");
        search.textProperty().addListener((o, before, after) -> filter());
        product.setAccessibleText("Products");
        product.getStyleClass().add("product-menu");
        product.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(String code, boolean empty) {
                super.updateItem(code, empty);
                setText(null);
                setGraphic(null);
                if (empty || code == null) return;
                long count = builds.stream().filter(b -> code.equals(b.product())).count();
                var name = label(productName(code), "product-name");
                name.setWrapText(true);
                name.setMinWidth(0);
                name.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(name, Priority.ALWAYS);
                var total = label(Long.toString(count), "product-count");
                var row = new HBox(8, name, total);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                var content = new VBox(5, row);
                content.prefWidthProperty().bind(widthProperty().subtract(28));
                setPrefWidth(0);
                var productCode = label(code, "product-code");
                productCode.setWrapText(false);
                content.getChildren().add(productCode);
                setGraphic(content);
            }
        });
        product.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            if (!updatingProducts && after != null) {
                selected = null;
                filter();
            }
        });
        scope.getItems().addAll("All builds", "Current", "Saved");
        if (getParameters().getRaw().contains("--mock")) {
            scope.getItems().addAll("Prepared", "Community");
        }
        scope.setValue("All builds");
        scope.setAccessibleText("Build status filter");
        scope.setOnAction(e -> filter());
        column("Product", b -> b.edition() + " · " + b.product(), 225);
        column("Version", BuildInfo::version, 170);
        column("Build source", b -> b.source().label(), 110);
        column("Stored", b -> mockMode() ? effectiveBuild(b).downloaded() : loadStored(b), 110);
        column("Status", this::preparationSummary, 230);
        table.getStyleClass().add("builds-table");
        table.setFixedCellSize(36);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching builds. Adjust or clear the filters."));
        table.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> {
            if (!updating && after != null) {
                selected = after;
                inspect();
            }
        });
        status.getStyleClass().add("status");
        issuesButton.setVisible(false);
        issuesButton.setManaged(false);
        issuesButton.setOnAction(event -> {
            var dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING);
            dialog.initOwner(root.getScene().getWindow());
            dialog.setTitle("Build catalog issues");
            dialog.setHeaderText("Some build observations could not be refreshed or read");
            var details = new javafx.scene.control.TextArea(String.join("\n\n", service.problems()));
            details.setEditable(false);
            details.setWrapText(true);
            details.setPrefSize(650, 350);
            dialog.getDialogPane().setContent(details);
            dialog.show();
        });
        openingSpinner.setMinSize(18, 18);
        openingSpinner.setPrefSize(18, 18);
        openingSpinner.setMaxSize(18, 18);
        openingSpinner.setVisible(false);
        openingSpinner.setManaged(false);
        openingSpinner.setAccessibleText("Build opening is active");
        openingClock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        openingActivity.setMinWidth(0);
        openingActivity.setMaxWidth(650);
        openingActivity.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        openingActivity.setVisible(false);
        openingActivity.setManaged(false);
        openingActivity.setOnAction(event -> {
            if (service != null && service.preparation() != null && preparationFocus != null) {
                if (preparationFocus.snapshot().ready() != BuildPreparation.Ready.NOT_PREPARED) showPreparedWorkspace(preparationFocus);
                else showPreparationOpening(preparationFocus);
                return;
            }
            if (realOpeningActive) showRealOpening();
            else if (realOpeningBuild != null) {
                var ready = realOpened.get(realOpeningBuild.identity());
                if (ready != null) showOpenedBuild(realOpeningBuild, ready);
                else {
                    selected = realOpeningBuild;
                    showBuilds();
                }
            }
        });
        var statusBar = new HBox(12, status, spacer(), openingSpinner, openingActivity, issuesButton);
        root.setBottom(statusBar);
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
        String version = System.getProperty("vimi.version", "");
        stage.setTitle((getParameters().getRaw().contains("--mock") ? "VibeMiner · Design preview" : "VibeMiner · Builds")
                + (version.isBlank() ? "" : " · " + version));
        stage.getIcons().add(brandImage());
        stage.setMinWidth(1000);
        stage.setMinHeight(680);
        stage.setScene(scene);
        catalogActivity.setMinSize(20, 20);
        catalogActivity.setPrefSize(20, 20);
        catalogActivity.setMaxSize(20, 20);
        catalogActivity.getStyleClass().add("catalog-activity");
        catalogActivity.setFocusTraversable(false);
        showCatalogActivity("Loading history…");
        refreshButton.setOnAction(event -> loadBuilds(true));
        refreshButton.setDisable(true);
        refreshSourcesButton.setDisable(true);
        refreshSourcesButton.setOnAction(event -> refreshSources());
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutdown();
        });
        showBuilds();
        status.setText("Opening local build history…");
        startup = getParameters().getRaw().contains("--mock")
                ? java.util.concurrent.CompletableFuture.completedFuture(new MockBuildService())
                : BuildApplication.open(BuildApplication.defaultDataDirectory(), null, networkActivity);
        startup.whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing) { return; }
            if (error != null) {
                showCatalogActivity(null);
                status.setText("Could not open build history: " + problem(error));
                table.setPlaceholder(new Label("Build history could not be opened. Close and reopen after checking the data directory."));
                return;
            }
            service = result;
            preparationClock.setCycleCount(javafx.animation.Animation.INDEFINITE);
            preparationClock.play();
            loadBuilds(false);
            refreshSourcesButton.setDisable(false);
            if (!service.mockMode()) refreshSources(true);
        }));
        stage.show();
    }

    private void refreshSources() { refreshSources(false); }

    private void refreshSources(boolean startupRefresh) {
        if (service == null || closing) return;
        if (sourceRefresh == null) {
            var providers = new java.util.ArrayList<SourceRefresh.Provider>();
            providers.add(new SourceRefresh.Provider("BLIZZARD", "Blizzard build catalog", () -> true,
                    () -> service.refreshBuilds().thenAccept(ignored -> Platform.runLater(() -> {
                        if (!closing) loadBuilds(false);
                    })).thenRun(() -> {
                        if (!service.problems().isEmpty()) throw new IllegalStateException(
                                String.join("; ", service.problems().stream().limit(3).toList()));
                    }),
                    () -> new SourceRefresh.Progress("Checking published builds for enabled products…", -1),
                    service::cancelCatalogRefresh));
            var sources = service.dataSources();
            if (sources != null) for (var id : DataSourcesService.Id.values()) {
                String name = switch (id) {
                    case QUESTIE -> "QuestieDB"; case LISTFILE -> "Community listfile";
                    case DEFINITIONS -> "WoWDBDefs (bundled)"; case TACT_KEYS -> "TACTKeys"; case EG_LINK -> "EG Link"; case POLESTAR -> "Polestar";
                };
                providers.add(new SourceRefresh.Provider(id.name(), name, () -> sources.policy(id).enabled(),
                        () -> sources.sync(id), () -> {
                            var value = sources.activity(id);
                            return value == null || !value.busy() ? new SourceRefresh.Progress("Checking for updates…", -1)
                                    : new SourceRefresh.Progress(value.message(), value.fraction());
                        }, () -> sources.cancel(id)));
            }
            sourceRefresh = new SourceRefresh(providers);
        }
        var run = sourceRefresh.start();
        if (sourceRefreshDialog != null && sourceRefreshDialog.isShowing()) {
            if (sourceRefreshDialog.run() == run) {
                sourceRefreshDialog.getDialogPane().getScene().getWindow().requestFocus();
                return;
            }
            sourceRefreshDialog.close();
        }
        sourceRefreshDialog = new SourceRefreshDialog(root.getScene().getWindow(), run, startupRefresh, this::refreshSources);
        sourceRefreshDialog.getDialogPane().getStylesheets().add(getClass().getResource("/vimi.css").toExternalForm());
        sourceRefreshDialog.show();
    }

    private void column(String title, java.util.function.Function<BuildInfo, String> value, int width) {
        TableColumn<BuildInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private boolean mockMode() {
        return service != null ? service.mockMode() : getParameters().getRaw().contains("--mock");
    }

    private static String problem(Throwable error) {
        while (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void showCatalogActivity(String message) {
        boolean busy = message != null;
        catalogActivity.setVisible(busy);
        catalogActivity.setManaged(busy);
        catalogActivity.setAccessibleText(busy ? message : "Build catalog idle");
        refreshButton.setText(busy ? message : "↻  Refresh builds");
    }

    private void loadBuilds(boolean refreshSources) {
        if (service == null || closing) return;
        if (loading) { catalogReloadPending = true; catalogRefreshPending |= refreshSources; return; }
        if (service.settings() != null) {
            String priorProduct = product.getSelectionModel().getSelectedItem();
            updatingProducts = true;
            try {
                product.getItems().setAll(service.settings().enabledProducts());
                product.getSelectionModel().select(product.getItems().contains(priorProduct) ? priorProduct
                        : product.getItems().isEmpty() ? null : product.getItems().getFirst());
                product.refresh();
            } finally { updatingProducts = false; }
            filter();
        }
        loading = true;
        showCatalogActivity(refreshSources ? "Refreshing builds…" : "Loading history…");
        refreshButton.setDisable(true);
        long generation = ++catalogGeneration;
        status.setText(refreshSources ? "Checking published builds… Saved history stays available." : "Reading saved build history…");
        table.setPlaceholder(new Label("Loading build history…"));
        var request = refreshSources ? service.refreshBuilds() : service.listBuilds();
        request.whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing || generation != catalogGeneration) { return; }
            loading = false;
            if (catalogReloadPending) {
                boolean refreshAgain = catalogRefreshPending;
                catalogReloadPending = catalogRefreshPending = false;
                Platform.runLater(() -> loadBuilds(refreshAgain));
            }
            showCatalogActivity(null);
            refreshButton.setDisable(false);
            if (error != null) {
                status.setText("Could not load builds: " + problem(error) + ". Refresh builds to retry.");
                issuesButton.setVisible(!service.problems().isEmpty());
                issuesButton.setManaged(!service.problems().isEmpty());
                table.setPlaceholder(new Label("Build history is unavailable. Refresh builds to retry."));
                return;
            }
            String selectedIdentity = selected == null ? null : selected.identity();
            boolean firstCatalog = builds.isEmpty();
            builds = List.copyOf(result);
            selected = builds.stream().filter(build -> build.identity().equals(selectedIdentity)).findFirst()
                    .orElse(builds.isEmpty() ? null : builds.getFirst());
            String priorProduct = product.getSelectionModel().getSelectedItem();
            updatingProducts = true;
            try {
                product.getItems().clear();
                if (service.settings() != null) product.getItems().addAll(service.settings().enabledProducts());
                else builds.stream().map(BuildInfo::product).distinct().sorted().forEach(product.getItems()::add);
                product.getSelectionModel().select(firstCatalog && selected != null
                        ? selected.product()
                        : product.getItems().contains(priorProduct) ? priorProduct
                                : product.getItems().isEmpty() ? null : product.getItems().getFirst());
                product.refresh();
            } finally {
                updatingProducts = false;
            }
            filter();
            status.setText(service.status() + " · " + builds.size() + " builds");
            issuesButton.setVisible(!service.problems().isEmpty());
            issuesButton.setManaged(!service.problems().isEmpty());
            table.setPlaceholder(new Label(builds.isEmpty()
                    ? service.settings() != null && service.settings().enabledProducts().isEmpty()
                            ? "No products enabled. Choose products in Settings."
                            : "No saved builds yet. Refresh builds to check published versions."
                    : "No matching builds. Adjust or clear the filters."));
        }));
    }

    private String productName(String code) {
        return builds.stream().filter(build -> code.equals(build.product()))
                .map(BuildInfo::edition).filter(name -> name != null && !name.isBlank())
                .findFirst().orElse(com.vibeminer.builds.Products.name(code));
    }

    private void filter() {
        String activeProduct = product.getSelectionModel().getSelectedItem();
        if (selected != null && !java.util.Objects.equals(selected.product(), activeProduct)) selected = null;
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<BuildInfo> visible = builds.stream()
                .filter(b -> (b.version() + " " + b.product() + " " + b.edition()).toLowerCase(Locale.ROOT).contains(query))
                .filter(b -> java.util.Objects.equals(product.getSelectionModel().getSelectedItem(), b.product()))
                .filter(b -> !scope.getValue().equals("Prepared") || effectiveBuild(b).prepared())
                .filter(b -> !scope.getValue().equals("Current") || b.current())
                .filter(b -> !scope.getValue().equals("Saved") || b.source() == BuildInfo.BuildSource.SAVED)
                .filter(b -> !scope.getValue().equals("Community") || b.source() == BuildInfo.BuildSource.COMMUNITY).toList();
        updating = true;
        table.setItems(FXCollections.observableArrayList(visible));
        selected = BuildInfo.visibleSelection(visible, selected);
        table.getSelectionModel().select(selected);
        String code = product.getSelectionModel().getSelectedItem();
        buildsHeading.setText(code == null ? "Choose a product" : productName(code));
        buildCount.setText(visible.size() + (visible.size() == 1 ? " build" : " builds"));
        table.getColumns().getFirst().setVisible(false);
        updating = false;
        inspect();
    }

    private void showBuilds() {
        if (comparisonView != null) { leaveComparison(this::showBuilds); return; }
        if (viewedPreparation != null) { closePreparedBuild(viewedPreparation, this::showBuilds); return; }
        viewedPreparation = null;
        if (preparationPane != null) { preparationPane.close(); preparationPane = null; }
        navigationGeneration++;
        status.setText(service == null ? "Opening local build history…" : service.status() + " · " + builds.size() + " builds");
        root.setTop(header(null, null));
        Button refresh = refreshButton;
        refresh.setAccessibleText("Refresh builds");
        refresh.getStyleClass().add("refresh-button");
        buildsHeading.getStyleClass().setAll("h2");
        buildCount.getStyleClass().setAll("muted");
        buildsHeading.setMinWidth(80);
        buildCount.setMinWidth(Region.USE_PREF_SIZE);
        refreshSourcesButton.setMinWidth(Region.USE_PREF_SIZE);
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        var refreshActions = new javafx.scene.layout.FlowPane(8, 6, refreshSourcesButton, refresh);
        refreshActions.setPrefWrapLength(350);
        refreshActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        HBox.setHgrow(refreshActions, Priority.ALWAYS);
        HBox titleRow = new HBox(12, buildsHeading, buildCount, catalogActivity, refreshActions);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        titleRow.getStyleClass().addAll("toolbar", "builds-product-heading");
        HBox filters = new HBox(12, search, scope, button("Clear filters", () -> {
            search.clear();
            scope.setValue("All builds");
        }));
        filters.getStyleClass().addAll("toolbar", "builds-filters");
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox list = new VBox(titleRow, filters, table);
        list.getStyleClass().add("build-list");
        VBox.setVgrow(table, Priority.ALWAYS);
        inspector.setPadding(new Insets(20));
        inspector.setPrefWidth(320);
        inspector.setMinWidth(280);
        inspector.getStyleClass().setAll("inspector");
        VBox products = new VBox(16, label("PRODUCTS", "eyebrow"), product);
        products.getStyleClass().add("product-sidebar");
        products.setPrefWidth(230);
        products.setMinWidth(210);
        VBox.setVgrow(product, Priority.ALWAYS);
        BorderPane content = new BorderPane(list);
        content.setLeft(products);
        ScrollPane details = scroll(inspector);
        details.setPrefWidth(330);
        content.setRight(details);
        BorderPane page = new BorderPane(content);
        page.setTop(applicationNavigation("Builds"));
        root.setCenter(page);
        inspect();
    }

    private HBox applicationNavigation(String active) {
        Button buildsMenu = button("Builds", this::showBuilds);
        Button compareMenu = button("Compare", this::showComparison);
        Button sourcesMenu = button("Data Sources", this::showDataSources);
        Button settingsMenu = button("Settings", this::showSettings);
        for (Button item : List.of(buildsMenu, compareMenu, sourcesMenu, settingsMenu)) {
            item.getStyleClass().add("area-link");
            if (item.getText().equals(active)) item.getStyleClass().add("area-active");
        }
        var divider = new javafx.scene.control.Separator(javafx.geometry.Orientation.VERTICAL);
        divider.getStyleClass().add("application-area-divider");
        divider.setMinHeight(20);
        divider.setPrefHeight(20);
        divider.setMaxHeight(20);
        HBox.setMargin(divider, new Insets(0, 12, 0, 12));
        HBox navigation = new HBox(4, buildsMenu, compareMenu, divider, sourcesMenu, settingsMenu);
        navigation.getStyleClass().add("area-navigation");
        navigation.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return navigation;
    }

    private void showComparison() {
        if (viewedPreparation != null) { closePreparedBuild(viewedPreparation, this::showComparison); return; }
        if (comparisonView != null) return;
        if (service == null || service.comparisons() == null) {
            showApplicationArea("Compare", "Local comparison is unavailable", "Open the local library to compare prepared build inventories.");
            return;
        }
        navigationGeneration++;
        root.setTop(header(null, null));
        comparisonView = new CompareView(
                () -> builds.stream().filter(build -> service.settings() == null
                        || service.settings().enabled(build.product())).toList(),
                List.of("enUS", "enGB", "deDE", "frFR", "esES", "esMX", "itIT", "ptBR", "ruRU", "koKR", "zhCN", "zhTW"),
                ComparisonControllers.from(service.comparisons()), this::showBuilds);
        var page = new BorderPane(comparisonView);
        page.setTop(applicationNavigation("Compare"));
        root.setCenter(page);
        status.setText("Local comparison · Files, table inventory, selected raw rows and recorded Items facts.");
    }

    private void leaveComparison(Runnable afterClose) {
        var view = comparisonView;
        comparisonView = null;
        if (view != null) view.close();
        showDrainActivity("Closing comparison…");
        var comparisons = service == null ? null : service.comparisons();
        var drained = comparisons == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                : comparisons.releaseCurrent();
        drained.whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (closing) return;
            hideDrainActivity();
            afterClose.run();
            if (error != null) status.setText("Comparison close failed: " + problem(error));
        }));
    }

    private void showSettings() {
        if (comparisonView != null) { leaveComparison(this::showSettings); return; }
        if (viewedPreparation != null) { closePreparedBuild(viewedPreparation, this::showSettings); return; }
        if (service == null || service.settings() == null) {
            showApplicationArea("Settings", "Settings are unavailable", "Open the local library to manage products."); return;
        }
        viewedPreparation = null;
        if (preparationPane != null) { preparationPane.close(); preparationPane = null; }
        navigationGeneration++;
        root.setTop(header(null, null));
        if (settingsView == null) settingsView = new SettingsView(service.settings(), () -> loadBuilds(false));
        BorderPane page = new BorderPane(settingsView);
        page.setTop(applicationNavigation("Settings"));
        root.setCenter(page);
    }

    private void showDataSources() {
        if (comparisonView != null) { leaveComparison(this::showDataSources); return; }
        if (viewedPreparation != null) { closePreparedBuild(viewedPreparation, this::showDataSources); return; }
        viewedPreparation = null;
        if (preparationPane != null) { preparationPane.close(); preparationPane = null; }
        navigationGeneration++;
        root.setTop(header(null, null));
        if (service == null) { showApplicationArea("Data Sources", "Opening the local library…", "Source data will be available after startup."); return; }
        if (dataSourcesView == null) dataSourcesView = new DataSourcesView(service.dataSources());
        BorderPane page = new BorderPane(dataSourcesView);
        page.setTop(applicationNavigation("Data Sources"));
        root.setCenter(page);
    }

    private void showApplicationArea(String title, String subtitle, String emptyMessage) {
        viewedPreparation = null;
        if (preparationPane != null) { preparationPane.close(); preparationPane = null; }
        navigationGeneration++;
        root.setTop(header(null, null));
        VBox content = new VBox(12, label(title, "h1"), label(subtitle, "subtitle"),
                label(emptyMessage, "muted"));
        content.setPadding(new Insets(28));
        BorderPane page = new BorderPane(content);
        page.setTop(applicationNavigation(title));
        root.setCenter(page);
    }

    private void inspect() {
        preparationOpenLabelUpdater = null;
        inspector.getChildren().clear();
        if (selected == null) {
            inspector.getChildren().add(label("Select a build to inspect its identity.", "muted"));
            return;
        }
        BuildInfo b = selected;
        if (!mockMode() && service != null && !b.identity().equals(accountingSelection)) {
            accountingSelection = b.identity();
            accountingPending = b.identity();
            accountingFailure = null;
            long accountingRequest = ++accountingGeneration;
            service.refreshLoadSnapshot(b).whenComplete((snapshot, failure) -> Platform.runLater(() -> {
                if (closing || accountingRequest != accountingGeneration) return;
                accountingPending = null;
                accountingFailure = failure == null ? null : b.identity();
                if (selected != null && selected.identity().equals(b.identity())) {
                    table.refresh();
                    inspect();
                }
            }));
        }
        inspector.getChildren().addAll(label("SELECTED BUILD", "eyebrow"), label(b.version(), "h2"),
                label("World of Warcraft · " + b.edition(), "muted"));
        if (!table.getItems().contains(b)) {
            inspector.getChildren().add(notice("Selection hidden by filters", "Clear filters to see this build in the list."));
        }
        if (b.identity().equals(accountingPending)) {
            var activity = new ActivityIndicator("Refreshing size accounting…");
            activity.setBusy(true);
            inspector.getChildren().add(activity);
        } else if (b.identity().equals(accountingFailure)) {
            inspector.getChildren().add(label("Size accounting could not be refreshed.", "muted"));
        }
        var sourceBadge = label(b.source().label(), "build-source-badge");
        sourceBadge.setTooltip(new javafx.scene.control.Tooltip(b.source().description()));
        inspector.getChildren().add(sourceBadge);
        VBox technical = new VBox(12);
        technical.getChildren().addAll(label("IDENTITY", "eyebrow"), fact("Product", b.product()), fact("Build config", b.config()),
                label("OBSERVATION", "eyebrow"), fact(mockMode() ? "Advertised regions" : "Observed regions", b.regions().isEmpty() ? "Never observed by VibeMiner" : b.regions()),
                fact("Stored", mockMode() ? effectiveBuild(b).downloaded() : loadStored(b)));
        if (!mockMode()) {
            var load = service.loadSnapshot(b);
            if (load != null) {

                if (load.entry() != null && load.entry().errorMessage() != null)
                    inspector.getChildren().add(notice("Last load", load.entry().errorMessage()));
            }
            technical.getChildren().addAll(
                    fact("Catalog identity", b.identity()),
                    fact("First observed", b.firstSeen() == null ? "Unknown" : b.firstSeen().toString()),
                    fact("Last observed", b.lastSeen() == null ? "Unknown" : b.lastSeen().toString()));
            var observations = new VBox(10);
            for (var observation : b.observations()) {
                observations.getChildren().add(notice(observation.region().toUpperCase(Locale.ROOT)
                        + (observation.current() ? " · Current" : " · Saved"),
                        "CDN config: " + observation.cdnConfig() + "\nLast observed: " + observation.lastSeen()));
            }
            var regions = new ComboBox<BuildInfo.RegionEvidence>();
            regions.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(BuildInfo.RegionEvidence value) {
                    return value == null ? "" : value.region().toUpperCase(Locale.ROOT) + " · "
                            + (value.current() ? "Current" : "Saved");
                }
                @Override public BuildInfo.RegionEvidence fromString(String value) { throw new UnsupportedOperationException(); }
            });
            b.observations().stream().sorted(java.util.Comparator.comparing(observation -> !observation.current())).forEach(regions.getItems()::add);
            if (!regions.getItems().isEmpty()) {
                regions.setValue(regions.getItems().stream()
                        .filter(observation -> observation.region().equalsIgnoreCase("us"))
                        .findFirst().orElse(regions.getItems().getFirst()));
            }
            regions.setAccessibleText("Build source region");
            regions.getStyleClass().add("build-choice");
            regions.setMinWidth(0);
            regions.setMaxWidth(Double.MAX_VALUE);
            var locale = new ComboBox<String>();
            com.vibeminer.formats.definitions.WowLocale.ALL.forEach(value -> locale.getItems().add(value.code()));
            locale.setValue(preparationLocales.getOrDefault(b.identity(),"enUS"));
            locale.setAccessibleText("Build locale");
            locale.getStyleClass().add("build-choice");
            locale.valueProperty().addListener((observable,before,after) -> preparationLocales.put(b.identity(),after));
            Button open = new Button(realOpened.containsKey(b.identity()) ? "View opened build" : "Open build");
            open.getStyleClass().addAll("primary", "open-build");
            open.setMaxWidth(Double.MAX_VALUE);
            open.setDisable(service.opening() == null || regions.getValue() == null || realOpeningActive);
            Runnable updateOpenLabel = () -> {
                var ready = realOpened.get(b.identity());
                var choice = regions.getValue();
                if (service.preparation() != null && choice != null) {
                    var job = service.preparation().find(b,choice,locale.getValue());
                    open.setText(job != null && job.snapshot().ready() != BuildPreparation.Ready.NOT_PREPARED ? "View build" : "Open build");
                    return;
                }
                open.setText(ready != null && choice != null && ready.build().region().equals(choice.region())
                        && ready.build().cdnConfig().equals(choice.cdnConfig()) ? "View opened build" : "Open build");
            };
            regions.valueProperty().addListener((observable, before, after) -> updateOpenLabel.run());
            locale.valueProperty().addListener((observable,before,after) -> updateOpenLabel.run());
            preparationOpenLabelUpdater = updateOpenLabel;
            updateOpenLabel.run();
            open.setOnAction(event -> {
                if (service.preparation() != null) {
                    startPreparation(b,regions.getValue(),locale.getValue());
                    return;
                }
                var ready = realOpened.get(b.identity());
                if (ready != null && ready.build().region().equals(regions.getValue().region()) && ready.build().cdnConfig().equals(regions.getValue().cdnConfig())) showOpenedBuild(b, ready);
                else startRealOpening(b, regions.getValue());
            });
            var routeChoice = new VBox(6, label("Source region", "muted"), regions);
            var choices = new HBox(12, routeChoice);
            HBox.setHgrow(routeChoice, Priority.ALWAYS);
            routeChoice.setMinWidth(0);
            if (service.preparation() != null) {
                locale.setMaxWidth(Double.MAX_VALUE);
                var localeChoice = new VBox(6, label("Locale", "muted"), locale);
                HBox.setHgrow(localeChoice, Priority.ALWAYS);
                choices.getChildren().add(localeChoice);
            }
            inspector.getChildren().add(choices);
            inspector.getChildren().addAll(open,
                    label(service.preparation() == null
                            ? "Loading verifies build metadata and indexes object ownership. Tables, items and other research data are not prepared."
                            : "Overview opens after metadata. Files and known table data prepare in the background; community names and media do not block readiness.", "muted"));
            inspector.getChildren().add(fact("Stored locally", loadStored(b)));
            if (load != null) inspector.getChildren().add(fact("Load status", load.status()));
            var technicalPane = new TitledPane("Technical details", technical);
            technicalPane.setExpanded(false);
            var observationsPane = new TitledPane("Regional observations", observations);
            observationsPane.setExpanded(false);
            inspector.getChildren().addAll(technicalPane, observationsPane);
            if (openingProblems.containsKey(b.identity())) inspector.getChildren().add(notice("Opening problem", openingProblems.get(b.identity())));
            return;
        }
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
        var technicalPane = new TitledPane("Technical details", technical);
        technicalPane.setExpanded(false);
        inspector.getChildren().add(technicalPane);
        updatePreparationControls();
    }

    private void startPreparation(BuildInfo build, BuildInfo.RegionEvidence source, String locale) {
        try {
            var existing = service.preparation().find(build,source,locale);
            if (existing != null && existing.snapshot().ready() != BuildPreparation.Ready.NOT_PREPARED) {
                preparationFocus = existing; showPreparedWorkspace(existing); return;
            }
            var job = service.preparation().start(build,source,locale);
            preparationFocus = job;
            showPreparationOpening(job);
        } catch (RuntimeException error) { status.setText(problem(error)); }
    }

    private void showPreparationOpening(BuildPreparation.Job job) {
        if (job.snapshot().ready() != BuildPreparation.Ready.NOT_PREPARED) { showPreparedWorkspace(job); return; }
        if (!job.snapshot().busy()) {
            status.setText("Metadata unavailable: " + job.snapshot().problem()); return;
        }
        if (realOpeningDialog != null && realOpeningDialog.isShowing()) return;
        var dialog = new javafx.scene.control.Dialog<Void>();
        realOpeningDialog = dialog;
        dialog.initOwner(root.getScene().getWindow());
        dialog.setTitle("Preparing build metadata");
        dialog.setHeaderText(job.build().version() + " · " + job.source().region().toUpperCase(Locale.ROOT) + " · " + job.locale());
        var spinner = new javafx.scene.control.ProgressIndicator(-1); spinner.setPrefSize(28,28);
        dialog.getDialogPane().setContent(new VBox(12,spinner,label("Verifying configurations, ENCODING and ROOT…", "value"),
                label("Files and Tables continue after metadata is ready. Closing stops this build workspace.", "muted")));
        var hide = new javafx.scene.control.ButtonType("Close build",javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        var cancel = new javafx.scene.control.ButtonType("Cancel preparation",javafx.scene.control.ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(hide,cancel);
        var cancelButton = (Button)dialog.getDialogPane().lookupButton(cancel);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION,event -> {
            event.consume(); cancelButton.setDisable(true); job.cancel(); dialog.setHeaderText("Stopping…");
        });
        var userClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var completed = new java.util.concurrent.atomic.AtomicBoolean();
        dialog.setOnHidden(event -> {
            if (completed.get() || closing) return;
            userClosed.set(true);
            if (realOpeningDialog == dialog) realOpeningDialog = null;
            if (preparationFocus == job) preparationFocus = null;
            service.preparation().release(job).whenComplete((ignored, error) -> Platform.runLater(() -> {
                if (!closing && error != null) status.setText("Close failed: " + problem(error));
            }));
        });
        long navigation = navigationGeneration;
        job.metadataReady().whenComplete((value,error) -> Platform.runLater(() -> {
            if (closing) return;
            if (userClosed.get()) return;
            boolean attached = realOpeningDialog == dialog && dialog.isShowing();
            completed.set(true);
            dialog.close(); if (realOpeningDialog == dialog) realOpeningDialog = null;
            if (error != null) {
                status.setText("Metadata unavailable: " + job.snapshot().problem());
                openingProblems.put(job.build().identity(),job.snapshot().problem());
                service.preparation().release(job).whenComplete((unused,releaseError) -> Platform.runLater(() -> {
                    if (preparationFocus == job) preparationFocus = null;
                }));
                inspect();
            } else if (attached && navigation == navigationGeneration) showPreparedWorkspace(job);
        }));
        dialog.show(); updatePreparationActivity();
    }

    private void showDrainActivity(String message) {
        hideDrainActivity();
        var activity = new ActivityIndicator(message);
        activity.setBusy(true);
        var card = new VBox(12, activity);
        card.setPadding(new Insets(24));
        card.setMaxSize(420, Region.USE_PREF_SIZE);
        card.getStyleClass().add("source-panel");
        drainOverlay = new javafx.scene.layout.StackPane(card);
        drainOverlay.getStyleClass().add("preparation-overlay");
        drainOverlay.setPadding(new Insets(18));
        surface.getChildren().add(drainOverlay);
        root.setDisable(true);
    }

    private void hideDrainActivity() {
        if (drainOverlay != null) surface.getChildren().remove(drainOverlay);
        drainOverlay = null;
        root.setDisable(false);
    }

    private void closePreparedBuild(BuildPreparation.Job job, Runnable afterClose) {
        viewedPreparation = null;
        var pane = preparationPane;
        preparationPane = null;
        if (pane != null) pane.close();
        showDrainActivity("Closing build…");
        service.preparation().release(job).whenComplete((value, error) -> Platform.runLater(() -> {
            if (closing) return;
            hideDrainActivity();
            if (error == null) closedPreparations.put(job.build().identity(),
                    PreparationStatus.closed(job.snapshot().ready(), job.locale()));
            if (preparationFocus == job) preparationFocus = null;
            afterClose.run();
            if (error != null) status.setText("Close failed: " + problem(error));
        }));
    }

    private void showPreparedWorkspace(BuildPreparation.Job job) {
        if (closing) return;
        navigationGeneration++;
        if (preparationPane != null) preparationPane.close();
        preparationPane = new PreparationPane(job,this::showBuilds,() -> closePreparedBuild(job, this::showBuilds),() -> {
            try {
                var enriched = service.preparation().enrich(job.build(),job.source(),job.locale());
                preparationFocus = enriched; showPreparedWorkspace(enriched);
            } catch (RuntimeException error) { status.setText(problem(error)); }
        }, service.downloads(), service.dataSources());
        preparationPane.worldBookmarks(service.worldBookmarks());
        viewedPreparation = job;
        root.setTop(preparedOverviewHeader(job)); root.setCenter(preparationPane);
        status.setText("Prepared data is scoped to this build and locale. Unknown and protected data remain explicit.");
    }

    private void updatePreparationActivity() {
        if (closing || service == null) return;
        if (service.preparation() == null) {
            if (table.getScene() != null) table.refresh();
            if (metadataOverviewMetrics != null && metadataOverviewMetrics.getScene() != null) {
                var snapshot = service.loadSnapshot(metadataOverviewBuild);
                if (snapshot != displayedLoadSnapshot) {
                    displayedLoadSnapshot = snapshot;
                    updateBuildMetrics(metadataOverviewMetrics, snapshot);
                }
            }
            return;
        }
        if (viewedPreparation != null) liveHeaderStatus.setText(readyLabel(viewedPreparation.snapshot().ready()));
        var jobs = service.preparation().jobs();
        var focus = PreparationStatus.activityFocus(jobs,preparationFocus);
        preparationFocus = focus;
        if (table.getScene() != null) {
            table.refresh();
            if (preparationOpenLabelUpdater != null) preparationOpenLabelUpdater.run();
        }
        boolean visible = focus != null;
        openingActivity.setVisible(visible); openingActivity.setManaged(visible);
        boolean busy = visible && (focus.snapshot().busy() || focus.enrichingNames());
        openingSpinner.setVisible(busy); openingSpinner.setManaged(busy);
        if (visible) openingActivity.setText(focus.build().version() + " · " + focus.locale() + " · " + PreparationPane.describe(focus.snapshot())
                + (focus.enrichingNames() ? " · Enriching names" : "")
                + (busy ? " · " + (focus.elapsedSeconds()/60) + "m " + (focus.elapsedSeconds()%60) + "s" : "") + " · View");
    }

    private void startRealOpening(BuildInfo build, BuildInfo.RegionEvidence region) {
        if (realOpeningActive || closing) return;
        realOpeningBuild = build;
        realOpeningRegion = region.region();
        realOpeningActive = true;
        realOpeningStopping = false;
        openingProblems.remove(build.identity());
        realOpened.remove(build.identity());
        realOpeningStartedNanos = System.nanoTime();
        openingSpinner.setVisible(true);
        openingSpinner.setManaged(true);
        openingClock.playFromStart();
        updateOpeningActivity();
        openingActivity.setVisible(true);
        openingActivity.setManaged(true);
        inspect();
        showRealOpening();
        accountingSelection = null;
        service.opening().open(build, region).whenComplete((result, error) -> Platform.runLater(() -> {
            if (closing) return;
            realOpeningActive = false;
            openingClock.stop();
            openingSpinner.setVisible(false);
            openingSpinner.setManaged(false);
            boolean attached = realOpeningDialog != null && realOpeningDialog.isShowing();
            if (realOpeningDialog != null) realOpeningDialog.close();
            realOpeningDialog = null;
            if (error != null) {
                openingProblems.put(build.identity(), error instanceof java.util.concurrent.CancellationException
                        || error.getCause() instanceof java.util.concurrent.CancellationException ? "Opening canceled" : problem(error));
            } else if (result.state() == com.vibeminer.builds.BuildTypes.BuildOpenState.OPEN) {
                realOpened.put(build.identity(), new LoadedMetadata(result.build(), result.opened().metadata()));
                if (realOpened.size() > 4) realOpened.remove(realOpened.keySet().iterator().next());
            } else {
                openingProblems.put(build.identity(), result.state() + " · " + result.part() + " · "
                        + result.code() + " · " + result.message());
            }
            openingActivity.setText(realOpened.containsKey(build.identity()) ? "Ready to view: " + build.version()
                    : "Opening stopped: " + build.version());
            inspect();
            if (attached && realOpened.containsKey(build.identity())) showOpenedBuild(build, realOpened.get(build.identity()));
            else if (attached) {
                var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                        openingProblems.get(build.identity()));
                alert.initOwner(root.getScene().getWindow());
                alert.setHeaderText("Build was not opened");
                alert.show();
            }
        }));
    }

    private void updateOpeningActivity() {
        if (!realOpeningActive || realOpeningBuild == null) return;
        long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - realOpeningStartedNanos);
        String elapsed = (seconds / 60) + "m " + (seconds % 60) + "s";
        openingActivity.setText((realOpeningStopping ? "Stopping: " : "Opening: ")
                + realOpeningBuild.version() + " · " + elapsed + " · View");
        if (openingElapsedLabel != null) openingElapsedLabel.setText("Elapsed " + elapsed
                + " · The metadata loader does not report work-unit progress.");
    }

    private void showRealOpening() {
        if (!realOpeningActive) return;
        if (realOpeningDialog != null && realOpeningDialog.isShowing()) return;
        var dialog = new javafx.scene.control.Dialog<Void>();
        realOpeningDialog = dialog;
        dialog.initOwner(root.getScene().getWindow());
        dialog.setTitle("Opening build");
        dialog.setHeaderText(realOpeningBuild.version() + " · " + realOpeningRegion.toUpperCase(Locale.ROOT));
        var spinner = new javafx.scene.control.ProgressIndicator(-1);
        spinner.setPrefSize(28, 28);
        var message = label(realOpeningStopping ? "Stopping…" : "Reading and verifying build metadata…", "value");
        openingElapsedLabel = label("", "muted");
        updateOpeningActivity();
        dialog.getDialogPane().setContent(new VBox(12, spinner, message, openingElapsedLabel,
                label("Hide keeps this operation running. Progress is indeterminate.", "muted")));
        var hide = new javafx.scene.control.ButtonType("Close build", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        var cancel = new javafx.scene.control.ButtonType("Cancel opening", javafx.scene.control.ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(hide, cancel);
        var cancelButton = (Button) dialog.getDialogPane().lookupButton(cancel);
        cancelButton.setDisable(realOpeningStopping);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            realOpeningStopping = true;
            cancelButton.setDisable(true);
            updateOpeningActivity();
            message.setText("Stopping…");
            service.opening().cancel();
        });
        dialog.show();
    }

    private void showOpenedBuild(BuildInfo build, LoadedMetadata result) {
        navigationGeneration++;
        var metadata = result.metadata();
        var metrics = new VBox(8);
        metadataOverviewBuild = build;
        metadataOverviewMetrics = metrics;
        displayedLoadSnapshot = service.loadSnapshot(build);
        updateBuildMetrics(metrics, service.loadSnapshot(build));
        var accountingActivity = new ActivityIndicator("Refreshing size accounting…");
        accountingActivity.setBusy(true);
        var accounting = new VBox(8, metrics, accountingActivity);
        service.refreshLoadSnapshot(build).whenComplete((snapshot, failure) -> Platform.runLater(() -> {
            if (closing || metrics.getScene() == null) return;
            accountingActivity.setBusy(false);
            updateBuildMetrics(metrics, service.loadSnapshot(build));
            if (failure != null) accounting.getChildren().add(label("Size accounting could not be refreshed.", "muted"));
        }));
        var content = new VBox(16, label("BUILD LOADED", "eyebrow"), label(build.version(), "h1"),
                fact("Product", build.product()), fact("Source region", result.build().region()),
                fact("Build config", result.build().buildConfig()), fact("CDN config", result.build().cdnConfig()),
                fact("Metadata build name", metadata.buildName()), fact("Files in ROOT", Integer.toString(metadata.fileCount())),
                fact("Archives", Integer.toString(metadata.archiveCount())),
                fact("Available locales", String.join(", ", metadata.locales())),
                accounting,
                notice("Authenticated metadata is ready", "Build loading is complete. Size accounting refreshes independently; no tables, items or other research data are prepared."),
                button("Close build", this::showBuilds));
        content.setPadding(new Insets(28));
        root.setCenter(scroll(content));
    }

    private void updateBuildMetrics(VBox target, BuildLoadSnapshot snapshot) {
        target.getChildren().setAll(fact("Stored object data", snapshot == null ? "Not measured" : snapshot.stored()),
                fact("Published CDN data", snapshot == null ? "Unknown" : snapshot.expected()));
        if (snapshot != null && snapshot.usage() != null) {
            var usage = snapshot.usage();
            target.getChildren().addAll(fact("Unique original data", BuildLoadSnapshot.size(usage.original().uniqueToBuildBytes())),
                    fact("Shared original data", BuildLoadSnapshot.size(usage.original().sharedWithBuildBytes())),
                    fact("Derived object data", BuildLoadSnapshot.size(usage.derived().storedBytes())));
            if (!usage.original().sharingComplete()) target.getChildren().add(label("Sharing is provisional while retained build inventories are incomplete.", "muted"));
        }
        if (snapshot != null && snapshot.problem() != null) target.getChildren().add(notice("Accounting unavailable", snapshot.problem()));
    }

    @Override
    public void stop() {
        closeNetworkActivity();
        GpuPreviewService.application().closeAsync();
        if (sourceRefresh != null) sourceRefresh.close();
        if (sourceRefreshDialog != null) sourceRefreshDialog.close();
        preparationClock.stop();
        if (comparisonView != null) comparisonView.close();
        if (preparationPane != null) preparationPane.close();
        openingClock.stop();
        preparations.close();
        if (service != null) { service.closeAsync(); }
    }

    private void shutdown() {
        if (closing) { return; }
        closing = true;
        closeNetworkActivity();
        if (sourceRefresh != null) sourceRefresh.close();
        if (sourceRefreshDialog != null) sourceRefreshDialog.close();
        preparationClock.stop();
        if (comparisonView != null) comparisonView.close();
        if (preparationPane != null) preparationPane.close();
        openingClock.stop();
        catalogGeneration++;
        preparations.close();
        root.setDisable(true);
        showCatalogActivity("Closing history…");
        status.setText("Closing build history…");
        var cleanup = startup == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
                : startup.handle((ready, error) -> ready)
                        .thenCompose(ready -> ready == null ? java.util.concurrent.CompletableFuture.completedFuture(null) : ready.closeAsync());
        java.util.concurrent.CompletableFuture.allOf(cleanup, GpuPreviewService.application().closeAsync())
                .whenComplete((unused, error) -> Platform.runLater(Platform::exit));
    }

    private BuildInfo effectiveBuild(BuildInfo build) {
        return preparedResults.getOrDefault(build.config(), build);
    }

    private String loadStored(BuildInfo build) {
        BuildLoadSnapshot snapshot = service == null ? null : service.loadSnapshot(build);
        return snapshot == null ? "Not measured" : snapshot.stored();
    }

    private String preparationSummary(BuildInfo build) {
        if (!mockMode() && service != null && service.preparation() != null) {
            var jobs = service.preparation().jobs();
            return jobs.stream().filter(job -> job.build().identity().equals(build.identity())).reduce((before,after) -> after)
                    .map(job -> PreparationPane.describe(job.snapshot()) + " · " + job.locale()).orElseGet(() -> priorPreparationSummary(build));
        }
        if (!mockMode()) {
            BuildLoadSnapshot snapshot = service == null ? null : service.loadSnapshot(build);
            if (snapshot != null) return snapshot.status();
            return realOpeningActive && realOpeningBuild.identity().equals(build.identity()) ? "Loading…" : "Not loaded";
        }
        var job = preparations.latest(build);
        return job == null ? (build.prepared() ? "Prepared" : "Not prepared") : job.summary();
    }

    private String priorPreparationSummary(BuildInfo build) {
        String closed = closedPreparations.get(build.identity());
        if (closed != null) return closed;
        String historical = service.historicalPreparation(build);
        return historical == null ? "Cache not assessed" : historical;
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

    private static String readyLabel(BuildPreparation.Ready ready) {
        return switch (ready) {
            case NOT_PREPARED -> "Not prepared";
            case METADATA_READY -> "Metadata ready";
            case FILES_READY -> "Files ready";
            case TABLES_READY -> "Tables ready";
        };
    }

    private HBox preparedOverviewHeader(BuildPreparation.Job job) {
        var build = job.build();
        HBox bar = header(null,null);
        bar.getChildren().remove(1,bar.getChildren().size());
        liveHeaderStatus.getStyleClass().setAll("header-value");
        liveHeaderStatus.setText(readyLabel(job.snapshot().ready()));
        HBox fields = new HBox(16,
                headerField("GAME / EDITION",label("World of Warcraft · " + build.edition(),"header-value")),
                headerField("PRODUCT ID",label(build.product(),"header-value")),
                headerField("BUILD",label(build.version(),"header-value")),
                headerField("LOCALE",label(job.locale(),"header-value")),
                headerField("STATUS",liveHeaderStatus),
                headerField("RETAINED",label("Not measured","retained-value")));
        fields.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(fields,Priority.ALWAYS);
        Button choose = button("Close build",this::showBuilds);
        choose.getStyleClass().add("header-menu");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        bar.setSpacing(16);bar.getChildren().addAll(headerFields(fields),choose,networkActivityView);
        return bar;
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
        Button choose = button("Close build", this::showBuilds);
        choose.getStyleClass().add("header-menu");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        bar.setSpacing(16);
        bar.getChildren().addAll(headerFields(fields), preparationActivity, choose, networkActivityView);
        return bar;
    }

    private static ScrollPane headerFields(HBox fields) {
        var viewport = new ScrollPane(fields);
        viewport.setFitToHeight(true);
        viewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        viewport.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        viewport.setPannable(true);
        viewport.setMinWidth(0);
        viewport.setAccessibleText("Build identity; scroll horizontally for additional fields");
        viewport.getStyleClass().add("header-fields");
        HBox.setHgrow(viewport, Priority.ALWAYS);
        return viewport;
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

    private void closeNetworkActivity() {
        if (networkActivityView != null) networkActivityView.close();
        if (networkActivityModel != null) networkActivityModel.close();
    }

    private HBox header(String area, Node action) {
        if (networkActivityView != null) networkActivityView.close();
        networkActivityView = new NetworkActivityView(networkActivityModel);
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
        header.getChildren().addAll(spacer(), preparationActivity, label(mockMode() ? "MOCK DATA" : "Local library", "badge"));
        if (action != null) {
            header.getChildren().add(action);
        }
        header.getChildren().add(networkActivityView);
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
