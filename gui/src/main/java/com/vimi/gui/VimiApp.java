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
    private BuildService service;
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
    private final Map<String, com.vibeminer.builds.BuildTypes.BuildOpenResult> realOpened = new java.util.LinkedHashMap<>();
    private final Map<String, String> closedPreparations = new HashMap<>();
    private final Map<String, String> openingProblems = new HashMap<>();
    private long catalogGeneration;
    private final Button issuesButton = new Button("Refresh issues");
    private final javafx.scene.control.ProgressIndicator catalogActivity = new javafx.scene.control.ProgressIndicator(-1);
    private final Button refreshButton = new Button("↻  Refresh builds");
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
        column("Downloaded", b -> mockMode() ? effectiveBuild(b).downloaded() : "Not measured", 110);
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
        stage.setTitle(getParameters().getRaw().contains("--mock") ? "VibeMiner · Design preview" : "VibeMiner · Builds");
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
        stage.setOnCloseRequest(event -> {
            event.consume();
            shutdown();
        });
        showBuilds();
        status.setText("Opening local build history…");
        startup = getParameters().getRaw().contains("--mock")
                ? java.util.concurrent.CompletableFuture.completedFuture(new MockBuildService())
                : BuildApplication.open();
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
        }));
        stage.show();
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
        if (service == null || loading || closing) { return; }
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
            builds = List.copyOf(result);
            selected = builds.stream().filter(build -> build.identity().equals(selectedIdentity)).findFirst()
                    .orElse(builds.isEmpty() ? null : builds.getFirst());
            String priorProduct = product.getValue();
            product.getItems().setAll("All products");
            builds.stream().map(BuildInfo::product).distinct().sorted().forEach(product.getItems()::add);
            product.setValue(product.getItems().contains(priorProduct) ? priorProduct : "All products");
            filter();
            status.setText(service.status() + " · " + builds.size() + " builds");
            issuesButton.setVisible(!service.problems().isEmpty());
            issuesButton.setManaged(!service.problems().isEmpty());
            table.setPlaceholder(new Label(builds.isEmpty()
                    ? "No saved builds yet. Refresh builds to check published versions."
                    : "No matching builds. Adjust or clear the filters."));
        }));
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<BuildInfo> visible = builds.stream()
                .filter(b -> (b.version() + " " + b.product() + " " + b.edition()).toLowerCase(Locale.ROOT).contains(query))
                .filter(b -> "All products".equals(product.getValue()) || java.util.Objects.equals(product.getValue(), b.product()))
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
        viewedPreparation = null;
        if (preparationPane != null) { preparationPane.close(); preparationPane = null; }
        navigationGeneration++;
        status.setText(service == null ? "Opening local build history…" : service.status() + " · " + builds.size() + " builds");
        root.setTop(header(null, null));
        Button refresh = refreshButton;
        refresh.setAccessibleText("Refresh builds");
        refresh.getStyleClass().add("refresh-button");
        HBox titleRow = new HBox(12, label("Builds", "nav"), spacer(), catalogActivity, refresh);
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
        preparationOpenLabelUpdater = null;
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
                label("OBSERVATION", "eyebrow"), fact(mockMode() ? "Advertised regions" : "Observed regions", b.regions().isEmpty() ? "Never observed by VibeMiner" : b.regions()),
                fact("Downloaded", mockMode() ? effectiveBuild(b).downloaded() : "Not measured"));
        if (!mockMode()) {
            inspector.getChildren().addAll(
                    fact("Catalog identity", b.identity()),
                    fact("First observed", b.firstSeen() == null ? "Unknown" : b.firstSeen().toString()),
                    fact("Last observed", b.lastSeen() == null ? "Unknown" : b.lastSeen().toString()));
            inspector.getChildren().add(label("REGIONAL OBSERVATIONS", "eyebrow"));
            for (var observation : b.observations()) {
                inspector.getChildren().add(notice(observation.region().toUpperCase(Locale.ROOT)
                        + (observation.current() ? " · Current" : " · Saved"),
                        "CDN config: " + observation.cdnConfig() + "\nLast observed: " + observation.lastSeen()));
            }
            var regions = new ComboBox<BuildInfo.RegionEvidence>();
            regions.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(BuildInfo.RegionEvidence value) {
                    return value == null ? "" : value.region().toUpperCase(Locale.ROOT) + " · "
                            + (value.current() ? "Current" : "Saved") + " · " + value.cdnConfig();
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
            regions.setMinWidth(0);
            regions.setMaxWidth(Double.MAX_VALUE);
            var locale = new ComboBox<String>();
            com.vibeminer.formats.definitions.WowLocale.ALL.forEach(value -> locale.getItems().add(value.code()));
            locale.setValue(preparationLocales.getOrDefault(b.identity(),"enUS"));
            locale.setAccessibleText("Build locale");
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
            inspector.getChildren().addAll(label("SOURCE REGION", "eyebrow"), regions, label("LOCALE", "eyebrow"), locale, open,
                    label("Overview opens after metadata. Files and known table data prepare in the background; community names and media do not block readiness.", "muted"));
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
                label("Files and Tables continue in the background after metadata is ready. Hide keeps work running.", "muted")));
        var hide = new javafx.scene.control.ButtonType("Hide",javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        var cancel = new javafx.scene.control.ButtonType("Cancel preparation",javafx.scene.control.ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(hide,cancel);
        var cancelButton = (Button)dialog.getDialogPane().lookupButton(cancel);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION,event -> {
            event.consume(); cancelButton.setDisable(true); job.cancel(); dialog.setHeaderText("Stopping…");
        });
        long navigation = navigationGeneration;
        job.metadataReady().whenComplete((value,error) -> Platform.runLater(() -> {
            if (closing) return;
            boolean attached = realOpeningDialog == dialog && dialog.isShowing();
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

    private void showPreparedWorkspace(BuildPreparation.Job job) {
        if (closing) return;
        navigationGeneration++;
        if (preparationPane != null) preparationPane.close();
        preparationPane = new PreparationPane(job,this::showBuilds,() -> {
            var pane = preparationPane;
            if (pane != null) pane.close();
            root.setDisable(true);
            service.preparation().release(job).whenComplete((value,error) -> Platform.runLater(() -> {
                if (closing) return;
                root.setDisable(false); preparationPane = null;
                if (error == null) closedPreparations.put(job.build().identity(),
                        PreparationStatus.closed(job.snapshot().ready(), job.locale()));
                if (preparationFocus == job) preparationFocus = null;
                showBuilds();
                if (error != null) status.setText("Close failed: " + problem(error));
            }));
        },() -> {
            try {
                var enriched = service.preparation().enrich(job.build(),job.source(),job.locale());
                preparationFocus = enriched; showPreparedWorkspace(enriched);
            } catch (RuntimeException error) { status.setText(problem(error)); }
        });
        viewedPreparation = job;
        root.setTop(preparedOverviewHeader(job)); root.setCenter(preparationPane);
        status.setText("Prepared data is scoped to this build and locale. Unknown and protected data remain explicit.");
    }

    private void updatePreparationActivity() {
        if (closing || service == null || service.preparation() == null) return;
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
                realOpened.put(build.identity(), result);
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
        var hide = new javafx.scene.control.ButtonType("Hide", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
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

    private void showOpenedBuild(BuildInfo build, com.vibeminer.builds.BuildTypes.BuildOpenResult result) {
        navigationGeneration++;
        var metadata = result.opened().metadata();
        var content = new VBox(16, label("BUILD OPENED", "eyebrow"), label(build.version(), "h1"),
                fact("Product", build.product()), fact("Source region", result.build().region()),
                fact("Build config", result.build().buildConfig()), fact("CDN config", result.build().cdnConfig()),
                fact("Metadata build name", metadata.buildName()), fact("Files in ROOT", Integer.toString(metadata.fileCount())),
                fact("Archives", Integer.toString(metadata.archiveCount())),
                fact("Available locales", String.join(", ", metadata.locales())),
                notice("Authenticated metadata is ready", "This is the opened build's metadata. Overview measurements and research screens will be connected separately."),
                button("Choose build", this::showBuilds));
        content.setPadding(new Insets(28));
        root.setCenter(scroll(content));
    }

    @Override
    public void stop() {
        preparationClock.stop();
        if (preparationPane != null) preparationPane.close();
        openingClock.stop();
        preparations.close();
        if (service != null) { service.closeAsync(); }
    }

    private void shutdown() {
        if (closing) { return; }
        closing = true;
        preparationClock.stop();
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
        cleanup.whenComplete((unused, error) -> Platform.runLater(Platform::exit));
    }

    private BuildInfo effectiveBuild(BuildInfo build) {
        return preparedResults.getOrDefault(build.config(), build);
    }

    private String preparationSummary(BuildInfo build) {
        if (!mockMode() && service != null && service.preparation() != null) {
            var jobs = service.preparation().jobs();
            return jobs.stream().filter(job -> job.build().identity().equals(build.identity())).reduce((before,after) -> after)
                    .map(job -> PreparationPane.describe(job.snapshot()) + " · " + job.locale()).orElseGet(() -> priorPreparationSummary(build));
        }
        if (!mockMode()) { return realOpeningActive && realOpeningBuild.identity().equals(build.identity()) ? "Opening…" : realOpened.containsKey(build.identity()) ? "Opened" : "Not opened"; }
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
        Button choose = button("← Choose build",this::showBuilds);
        choose.getStyleClass().add("header-menu");
        choose.setMinWidth(Region.USE_PREF_SIZE);
        bar.setSpacing(16);bar.getChildren().addAll(fields,choose);
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
