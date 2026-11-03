package com.vimi.gui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/**
 * Source management controls over cached service snapshots; all I/O belongs to the application
 * service.
 */
final class DataSourcesView extends BorderPane {
    private record Source(String name, String category, String description, String origin) {}

    private final DataSourcesService service;
    private Source selectedSource;
    private String selectedDatabase;
    private String selectedPolestar;
    private final Label activityLabel = text("", "muted");
    private final javafx.scene.control.ProgressBar progress =
            new javafx.scene.control.ProgressBar(-1);
    private boolean wasBusy;
    private final javafx.animation.Timeline clock =
            new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(
                            javafx.util.Duration.millis(500), event -> tick()));
    private final VBox information = new VBox(20);
    private final Label feedback =
            text("Source management is available in local-library mode", "muted");

    DataSourcesView() {
        this(null);
    }

    DataSourcesView(DataSourcesService service) {
        this.service = service;
        if (service != null)
            feedback.setText("Local source data · last checks are shown on each page");
        clock.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty()
                .addListener(
                        (observable, before, after) -> {
                            if (after == null) clock.stop();
                            else if (service != null) clock.play();
                        });
        ListView<Source> sources = new ListView<>();
        sources.getItems()
                .addAll(
                        new Source(
                                "QuestieDB",
                                "Quests & world data",
                                "Manage downloaded releases and searchable game databases.",
                                "github.com/Questie/QuestieDB"),
                        new Source(
                                "Community listfile",
                                "File names",
                                "Manage the community names used when browsing WoW files.",
                                "github.com/wowdev/wow-listfile"),
                        new Source(
                                "WoWDBDefs",
                                "Table definitions",
                                "Manage the definitions used to interpret WoW database tables.",
                                "github.com/wowdev/WoWDBDefs"),
                        new Source("TACTKeys", "Decryption keys",
                                "Public WoW keys used to read encrypted files and table sections.",
                                "github.com/wowdev/TACTKeys"),
                        new Source("EG Link", "Observed game data", "Download GitHub snapshots and prepare searchable local data.",
                                "github.com/JIVESCORP/eg-link-output-wowf"),
                        new Source("Polestar", "Forever database",
                                "Download and prepare Polestar's published quests, NPCs, spawns and item relationships.",
                                "github.com/fitzforge/polestar/tree/next/forever-db"));
        sources.setAccessibleText("External data sources");
        sources.getStyleClass().add("product-menu");
        sources.setCellFactory(
                ignored ->
                        new ListCell<>() {
                            @Override
                            protected void updateItem(Source source, boolean empty) {
                                super.updateItem(source, empty);
                                setText(null);
                                setGraphic(null);
                                if (empty || source == null) return;
                                VBox entry =
                                        new VBox(
                                                5,
                                                text(source.name(), "product-name"),
                                                text(source.category(), "product-code"));
                                entry.prefWidthProperty().bind(widthProperty().subtract(28));
                                setPrefWidth(0);
                                setGraphic(entry);
                            }
                        });
        sources.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (observable, before, after) -> {
                            if (after != null) {
                                selectedSource = after;
                                render();
                            }
                        });
        VBox sidebar = new VBox(16, text("DATA SOURCES", "eyebrow"), sources);
        sidebar.getStyleClass().add("product-sidebar");
        sidebar.setPrefWidth(230);
        sidebar.setMinWidth(210);
        VBox.setVgrow(sources, Priority.ALWAYS);
        setLeft(sidebar);
        information.setPadding(new Insets(24, 28, 28, 28));
        information.setMaxWidth(1200);
        ScrollPane content = new ScrollPane(information);
        content.setFitToWidth(true);
        content.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox preview = new VBox(feedback);
        preview.setPadding(new Insets(12, 28, 0, 28));
        BorderPane main = new BorderPane(content);
        main.setTop(preview);
        setCenter(main);
        sources.getSelectionModel().selectFirst();
    }

    private DataSourcesService.Id id() {
        return selectedSource.name().equals("QuestieDB")
                ? DataSourcesService.Id.QUESTIE
                : selectedSource.name().equals("Community listfile")
                        ? DataSourcesService.Id.LISTFILE
                        : selectedSource.name().equals("TACTKeys") ? DataSourcesService.Id.TACT_KEYS
                        : selectedSource.name().equals("EG Link") ? DataSourcesService.Id.EG_LINK
                        : selectedSource.name().equals("Polestar") ? DataSourcesService.Id.POLESTAR
                        : DataSourcesService.Id.DEFINITIONS;
    }

    private void render() {
        if (selectedSource == null) return;
        information
                .getChildren()
                .setAll(
                        service == null
                                ? text(
                                        "Source management is available in the local-library"
                                                + " application.",
                                        "muted")
                                : livePage(selectedSource));
        tick();
    }

    private void tick() {
        if (service == null || selectedSource == null) return;
        var current = service.activity(id());
        activityLabel.setText(
                current.problem() != null
                        ? current.problem()
                        : current.message() == null ? "" : current.message());
        progress.setProgress(current.fraction());
        progress.setVisible(current.busy());
        progress.setManaged(current.busy());
        boolean completed = wasBusy != current.busy();
        wasBusy = current.busy();
        if (completed) render();
    }

    private void command(java.util.concurrent.CompletableFuture<Void> operation) {
        operation.whenComplete(
                (value, error) ->
                        javafx.application.Platform.runLater(
                                () -> {
                                    if (error != null) {
                                        Throwable cause = error;
                                        while (cause.getCause() != null
                                                && cause
                                                        instanceof
                                                        java.util.concurrent.CompletionException)
                                            cause = cause.getCause();
                                        feedback.setText(
                                                "Could not complete action: " + cause.getMessage());
                                    } else feedback.setText("Source data and settings updated");
                                    render();
                                }));
    }

    private Node livePage(Source source) {
        var id = id();
        var policy = service.policy(id);
        CheckBox enabled = new CheckBox("Use this source");
        enabled.setSelected(policy.enabled());
        enabled.getStyleClass().add("source-enabled");
        enabled.setOnAction(event -> command(service.setEnabled(id, enabled.isSelected())));
        var title = new VBox(7, text(source.name(), "h1"), text(source.description(), "subtitle"));
        title.setMinWidth(0);
        HBox heading = new HBox(20, title, space(), enabled);
        heading.setAlignment(Pos.CENTER_LEFT);
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox page =
                new VBox(
                        18,
                        heading,
                        text(
                                "Downloaded data is kept when disabled. Close and reopen builds to"
                                        + " apply source changes.",
                                "muted"),
                        activityLabel,
                        progress);
        if (id == DataSourcesService.Id.QUESTIE) liveQuestie(page);
        else if (id == DataSourcesService.Id.LISTFILE) liveListfile(page);
        else if (id == DataSourcesService.Id.TACT_KEYS) liveTactKeys(page);
        else if (id == DataSourcesService.Id.EG_LINK) liveEgLink(page);
        else if (id == DataSourcesService.Id.POLESTAR) livePolestar(page);
        else {
            var definitions = service.definitions();
            if (definitions != null)
                page.getChildren()
                        .addAll(
                                metrics(
                                        metric(
                                                "DEFINITIONS",
                                                Long.toString(definitions.tables()),
                                                "In the bundled provider"),
                                        metric("SOURCE", "Bundled", "Included with this app"),
                                        metric(
                                                "REVISION",
                                                definitions.revision().substring(0, 12),
                                                definitions.license())),
                                panel(
                                        "Definition settings",
                                        text(
                                                "The app uses its pinned bundled definitions."
                                                        + " Updating the app can provide a new"
                                                        + " revision.",
                                                "muted")),
                                text(
                                        "Disabling definitions affects new schema-backed tables and"
                                            + " research. Build metadata and file browsing remain"
                                            + " available.",
                                        "muted"));
        }
        if (id != DataSourcesService.Id.DEFINITIONS) {
            var checks = new ComboBox<DataSourcesService.Checks>();
            checks.getItems().setAll(DataSourcesService.Checks.values());
            checks.setValue(policy.checks());
            checks.getStyleClass().add("build-choice");
            checks.setConverter(
                    new javafx.util.StringConverter<>() {
                        public String toString(DataSourcesService.Checks value) {
                            return value == null
                                    ? ""
                                    : switch (value) {
                                        case MANUAL -> "Manually";
                                        case STARTUP -> "When the app opens";
                                        case DAILY -> "Daily";
                                    };
                        }

                        public DataSourcesService.Checks fromString(String value) {
                            throw new UnsupportedOperationException();
                        }
                    });
            checks.setOnAction(event -> command(service.setChecks(id, checks.getValue())));
            page.getChildren()
                    .add(
                            settings(
                                    id == DataSourcesService.Id.LISTFILE
                                            ? "Automatic listfile updates"
                                            : id == DataSourcesService.Id.TACT_KEYS ? "Automatic key updates" : id == DataSourcesService.Id.POLESTAR ? "Published data checks" : id == DataSourcesService.Id.EG_LINK ? "GitHub snapshot checks" : "Release checks",
                                    checks,
                                    "Last successful check: " + checkedDate(id)));
        }
        var provenance =
                new TitledPane(
                        "Source & provenance",
                        new VBox(
                                10,
                                text(source.origin(), "value"),
                                text(
                                        "Retained data keeps its original provenance and notices.",
                                        "muted")));
        provenance.setExpanded(false);
        page.getChildren().add(provenance);
        return page;
    }

    private void liveEgLink(VBox page) {
        var state = service.egLink();
        boolean enabled = service.policy(DataSourcesService.Id.EG_LINK).enabled();
        boolean busy = service.activity(DataSourcesService.Id.EG_LINK).busy();
        long originals = state.snapshots().stream().mapToLong(com.vibeminer.eglink.EgLinkTypes.Snapshot::originalBytes).sum();
        long prepared = state.snapshots().stream().filter(value -> value.databaseBytes() != null).mapToLong(value -> value.databaseBytes()).sum();
        page.getChildren().addAll(metrics(
                metric("GITHUB BUILDS", Integer.toString(state.catalog().builds().size()), "In the checked commit"),
                metric("LOCAL SNAPSHOTS", Integer.toString(state.snapshots().size()), "Retained product / build versions"),
                metric("LOCAL DATA", bytes(originals + prepared), bytes(originals) + " original · " + bytes(prepared) + " prepared")),
                text(com.vibeminer.eglink.EgLinkTypes.ATTRIBUTION + " Prepared data is transformed into local indexed records.", "muted"),
                text("License: https://creativecommons.org/licenses/by/4.0/", "muted"),
                text("Snapshots cover exactly the recorded product and build. Dataset coverage varies; newer builds may contain only hotfix data. No older/beta snapshot is substituted automatically.", "muted"));
        if (state.issue() != null) page.getChildren().add(text(state.issue(), "muted"));
        Button check = new Button("Check GitHub for snapshots");
        check.setDisable(!enabled || busy);
        check.setOnAction(event -> command(service.checkEgLink()));
        page.getChildren().add(check);
        var available = new TableView<com.vibeminer.eglink.EgLinkTypes.Build>();
        available.setAccessibleText("EG Link GitHub build snapshots");
        available.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        column(available, "Product", value -> value.product(), 160);
        column(available, "Build", value -> value.version(), 170);
        column(available, "Datasets", value -> Integer.toString(value.datasets().size()), 90);
        column(available, "Reported records", value -> Long.toString(value.declaredRecords()), 130);
        available.getItems().setAll(state.catalog().builds().reversed());
        available.setPrefHeight(230);
        available.setPlaceholder(text("Check GitHub to discover the available build snapshots.", "muted"));
        Label details = text("Select a build to review its datasets before downloading.", "muted");
        Button download = new Button("Download selected snapshot");
        download.setDisable(true);
        available.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            selectedEgBuild = after == null ? null : after.product() + "/" + after.version();
            download.setDisable(after == null || !enabled || busy);
            details.setText(after == null ? "Select a build." : "Datasets: " + after.datasets().stream().map(com.vibeminer.eglink.EgLinkTypes.Dataset::name).collect(java.util.stream.Collectors.joining(", ")));
        });
        for (var build : available.getItems()) if ((build.product() + "/" + build.version()).equals(selectedEgBuild)) available.getSelectionModel().select(build);
        download.setOnAction(event -> {
            var selected = available.getSelectionModel().getSelectedItem();
            if (selected != null) command(service.downloadEgLink(selected));
        });
        page.getChildren().addAll(text("Available on GitHub", "h2"), available, details, download);
        var local = new TableView<com.vibeminer.eglink.EgLinkTypes.Snapshot>();
        local.setAccessibleText("Downloaded EG Link snapshots");
        local.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        column(local, "Product / build", value -> value.build().product() + " · " + value.build().version(), 260);
        column(local, "Commit", value -> value.build().revision().substring(0, 12), 120);
        column(local, "State", value -> value.problem() != null ? "Unavailable" : value.indexedRecords() == null ? "Downloaded" : "Prepared", 115);
        column(local, "Indexed records", value -> value.indexedRecords() == null ? "—" : Long.toString(value.indexedRecords()), 130);
        column(local, "Locales", value -> value.build().datasets().stream().map(com.vibeminer.eglink.EgLinkTypes.Dataset::locale).filter(locale -> !locale.isEmpty()).distinct().collect(java.util.stream.Collectors.joining(", ")), 100);
        local.getItems().setAll(state.snapshots().reversed()); local.setPrefHeight(Math.max(120, Math.min(240, 35 + state.snapshots().size() * 34)));
        local.setPlaceholder(text("No EG Link snapshots downloaded.", "muted"));
        Label localDetails = text("Preparation builds local query indexes and preserves each original dataset.", "muted");
        Button prepare = new Button("Prepare selected snapshot"); prepare.setDisable(true);
        local.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            selectedEgSource = after == null ? null : after.id();
            prepare.setDisable(after == null || !enabled || busy);
            localDetails.setText(after == null ? "Select a retained snapshot." : bytes(after.originalBytes()) + " original · "
                    + (after.databaseBytes() == null ? "not prepared" : bytes(after.databaseBytes()) + " indexed database")
                    + " · " + after.build().datasets().size() + " datasets" + (after.problem() == null ? "" : " · " + after.problem()));
        });
        for (var snapshot : local.getItems()) if (snapshot.id().equals(selectedEgSource)) local.getSelectionModel().select(snapshot);
        prepare.setOnAction(event -> {
            var selected = local.getSelectionModel().getSelectedItem();
            if (selected != null) command(service.prepareEgLink(selected.id()));
        });
        page.getChildren().addAll(text("Downloaded snapshots", "h2"), local, localDetails, prepare);
    }

    private void livePolestar(VBox page) {
        var state = service.polestar();
        var catalog = state.catalog().orElse(null);
        boolean available = service.policy(DataSourcesService.Id.POLESTAR).enabled()
                && !service.activity(DataSourcesService.Id.POLESTAR).busy();
        Button check = new Button("Check GitHub versions");
        check.setDisable(!available); check.setOnAction(event -> command(service.checkPolestar()));
        Button download = new Button("Download published data");
        download.setDisable(!available || catalog == null); download.setOnAction(event -> command(service.downloadPolestar()));
        Button cancel = new Button("Cancel"); cancel.setDisable(!service.activity(DataSourcesService.Id.POLESTAR).busy());
        cancel.setOnAction(event -> service.cancel(DataSourcesService.Id.POLESTAR));
        page.getChildren().add(new HBox(10, check, download, cancel));
        if (catalog != null) {
            page.getChildren().add(metrics(
                    metric("PUBLISHED VERSION", catalog.version(), "Commit " + catalog.revision().substring(0,12)),
                    metric("CLIENT BUILD", catalog.clientBuild(), "Published source provenance"),
                    metric("DATASETS", Integer.toString(catalog.resources().size()), bytes(catalog.bytes()) + " of data")));
            page.getChildren().add(text("This package identifies its own client build. It is not silently substituted for another build or treated as a complete world database.", "muted"));
        } else page.getChildren().add(text("No checked GitHub version. Checking downloads only the package metadata; download and preparation are separate actions.", "muted"));
        if (state.issue() != null) page.getChildren().add(text("Inventory unavailable: " + state.issue(), "muted"));
        TableView<com.vibeminer.polestar.PolestarTypes.Snapshot> table = new TableView<>();
        column(table,"Version",value -> value.catalog().version(),115);
        column(table,"Client build",value -> value.catalog().clientBuild(),155);
        column(table,"State",value -> value.preparedAt() == null ? "Downloaded" : "Prepared",110);
        column(table,"Records",value -> value.preparedAt() == null ? "—" : String.format(java.util.Locale.ROOT, "%,d", value.records()),100);
        column(table,"Originals",value -> bytes(value.originalBytes()),100);
        column(table,"Database",value -> value.preparedAt() == null ? "—" : bytes(value.databaseBytes()),110);
        table.getItems().setAll(state.snapshots());
        table.setPlaceholder(text("No downloaded Polestar versions", "muted"));
        table.setPrefHeight(Math.min(300, Math.max(135, state.snapshots().size()*34+50)));
        VBox selection = new VBox(12);
        table.getSelectionModel().selectedItemProperty().addListener((observable,before,value) -> {
            selection.getChildren().clear(); if (value == null) return;
            selectedPolestar = value.id();
            Button prepare = new Button(value.preparedAt() == null ? "Prepare local database" : "Database prepared");
            prepare.setDisable(!available || value.preparedAt() != null);
            prepare.setOnAction(event -> command(service.preparePolestar(value.id())));
            String resources = value.catalog().resources().stream()
                    .map(resource -> resource.table().wire() + " · " + resource.rows() + " records · " + resource.license())
                    .collect(java.util.stream.Collectors.joining("\n"));
            TitledPane datasets = new TitledPane("Datasets & licenses",text(resources,"muted")); datasets.setExpanded(false);
            String coverage = value.catalog().coverage().stream().map(entry -> entry.what() + ": " + entry.with() + " / " + entry.of())
                    .collect(java.util.stream.Collectors.joining("\n"));
            TitledPane coveragePanel = new TitledPane("Published coverage",text(coverage,"muted")); coveragePanel.setExpanded(false);
            if (!value.issues().isEmpty()) selection.getChildren().add(text(String.join("\n", value.issues()), "muted"));
            selection.getChildren().addAll(prepare,
                    text("Snapshot " + value.id().substring(0,12) + " · downloaded " + date(value.downloadedAt()),"muted"), datasets, coveragePanel);
        });
        page.getChildren().addAll(text("DOWNLOADED VERSIONS","eyebrow"),table,selection,
                text("Data from Polestar / forever-db and its credited upstream sources. Database tables are GPL-3.0; authored mechanics, steps and notes are CC BY 4.0. Preparation indexes the downloaded data for fast offline queries.", "muted"),
                text("Spawn coordinates use world yards on client Map IDs, not zone-map percentages. Coverage mixes client data and credited community sources; missing facts remain unavailable.", "muted"));
        var selected = state.snapshots().stream().filter(value -> value.id().equals(selectedPolestar)).findFirst();
        if (selected.isPresent()) table.getSelectionModel().select(selected.get());
        else if (!state.snapshots().isEmpty()) table.getSelectionModel().selectFirst();
    }

    private void liveTactKeys(VBox page) {
        var state = service.tactState();
        page.getChildren().add(metrics(
                metric("KEYS AVAILABLE", Integer.toString(state.keys()), "Validated public key entries"),
                metric("CACHED LIST", bytes(state.bytes()), "Available offline"),
                metric("UPDATE POLICY", service.policy(DataSourcesService.Id.TACT_KEYS).checks().name(), "Daily by default")));
        Button update = new Button("Update keys now");
        update.setDisable(!service.policy(DataSourcesService.Id.TACT_KEYS).enabled() || service.activity(DataSourcesService.Id.TACT_KEYS).busy());
        update.setOnAction(event -> command(service.updateTactKeys()));
        page.getChildren().addAll(update,
                text("Public keys supplement the keys included in each build. Build-provided keys take precedence if values conflict.", "muted"),
                text("Daily updates run while the app is open; overdue updates run when it opens again. Reopen a build to use newly available keys.", "muted"));
        if (state.problem() != null) page.getChildren().add(text(state.problem(), "muted"));
        if (state.fingerprint() != null) page.getChildren().add(text("Cached revision " + state.fingerprint().substring(0, 12), "muted"));
    }

    private void liveQuestie(VBox page) {
        var state = service.questie();
        var databases = state.databases();
        long archives =
                databases.stream()
                        .filter(v -> v.archiveBytes() >= 0)
                        .map(DataSourcesService.Database::sourceId)
                        .distinct()
                        .count();
        long ready = databases.stream().filter(v -> v.versionId() != null).count();
        long archiveBytes =
                databases.stream()
                        .filter(v -> v.archiveBytes() >= 0)
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        DataSourcesService.Database::sourceId,
                                        DataSourcesService.Database::archiveBytes,
                                        (a, b) -> a))
                        .values()
                        .stream()
                        .mapToLong(Long::longValue)
                        .sum();
        long databaseBytes =
                databases.stream().mapToLong(v -> Math.max(0, v.databaseBytes())).sum();
        page.getChildren()
                .add(
                        metrics(
                                metric(
                                        "DOWNLOADED ARCHIVES",
                                        state.issue() != null && databases.isEmpty()
                                                ? "Unavailable"
                                                : Long.toString(archives),
                                        "Retained original packages"),
                                metric(
                                        "READY DATABASES",
                                        state.issue() != null && databases.isEmpty()
                                                ? "Unavailable"
                                                : Long.toString(ready),
                                        "Prepared for local queries"),
                                metric(
                                        "LOCAL STORAGE",
                                        state.issue() != null && databases.isEmpty()
                                                ? "Unavailable"
                                                : bytes(archiveBytes + databaseBytes),
                                        "Archives "
                                                + bytes(archiveBytes)
                                                + " · Databases "
                                                + bytes(databaseBytes))));
        if (state.issue() != null) page.getChildren().add(text(state.issue(), "value"));
        page.getChildren()
                .add(
                        text(
                                "Latest observed release: "
                                        + (state.latest() == null ? "Not checked" : state.latest()),
                                "muted"));
        Button check = liveAction("Check releases", false, service::checkQuestie);
        Button download = new Button("Download release…");
        download.getStyleClass().addAll("primary", "source-action");
        download.setDisable(
                !service.policy(DataSourcesService.Id.QUESTIE).enabled()
                        || service.activity(DataSourcesService.Id.QUESTIE).busy());
        download.setOnAction(event -> downloadDialog());
        HBox actions = new HBox(10, text("Local databases", "h2"), space(), check, download);
        actions.setAlignment(Pos.CENTER_LEFT);
        TableView<DataSourcesService.Database> table = new TableView<>();
        table.getStyleClass().add("builds-table");
        table.setFixedCellSize(38);
        table.setPrefHeight(200);
        table.setMinHeight(160);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        column(table, "Release", DataSourcesService.Database::release, 100);
        column(table, "Flavor", DataSourcesService.Database::flavor, 90);
        column(
                table,
                "Archive",
                value -> value.archiveBytes() < 0 ? "Unavailable" : "Downloaded",
                110);
        column(
                table,
                "Database",
                value -> value.versionId() == null ? "Not prepared" : "Ready",
                130);
        column(
                table,
                "Records",
                value ->
                        value.records() == null
                                ? "—"
                                : String.format(java.util.Locale.ROOT, "%,d", value.records()),
                90);
        column(
                table,
                "Languages",
                value -> value.versionId() == null ? "—" : Integer.toString(value.locales().size()),
                90);
        column(table, "Use", value -> value.preferred() ? "Default" : "—", 80);
        table.setPlaceholder(
                text(
                        state.issue() == null
                                ? "No downloaded Questie packages. Check releases or download a"
                                        + " named release."
                                : "Inventory could not be read. Retained data has not been"
                                        + " removed.",
                        "muted"));
        table.getItems().setAll(databases);
        VBox selection = new VBox(12);
        table.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (observable, before, version) -> {
                            selection.getChildren().clear();
                            if (version == null) return;
                            selectedDatabase =
                                    version.versionId() == null
                                            ? version.sourceId()
                                            : version.versionId();
                            boolean prepared = version.versionId() != null;
                            selection
                                    .getChildren()
                                    .addAll(
                                            text(
                                                    version.release() + " · " + version.flavor(),
                                                    "h2"),
                                            text(
                                                    "Archive "
                                                            + bytes(version.archiveBytes())
                                                            + " · Database "
                                                            + (prepared
                                                                    ? bytes(version.databaseBytes())
                                                                    : "Not prepared"),
                                                    "value"));
                            if (prepared)
                                selection
                                        .getChildren()
                                        .addAll(
                                                text(
                                                        "Languages: "
                                                                + String.join(
                                                                        ", ", version.locales()),
                                                        "muted"),
                                                text(
                                                        "Prepared: " + date(version.preparedAt()),
                                                        "muted"));
                            if (!version.diagnostics().isEmpty()) {
                                var notes =
                                        new TitledPane(
                                                "Preparation notes",
                                                text(
                                                        String.join("\n\n", version.diagnostics()),
                                                        "muted"));
                                notes.setExpanded(false);
                                selection.getChildren().add(notes);
                            }
                            HBox controls = new HBox(10);
                            if (prepared)
                                controls.getChildren()
                                        .add(
                                                liveAction(
                                                        "Use for " + version.flavor(),
                                                        true,
                                                        () ->
                                                                service.setDefault(
                                                                        version.flavor(),
                                                                        version.versionId())));
                            controls.getChildren()
                                    .add(
                                            liveAction(
                                                    prepared
                                                            ? "Verify / prepare"
                                                            : "Prepare database",
                                                    !prepared,
                                                    () -> service.prepare(version.sourceId())));
                            selection.getChildren().add(controls);
                        });
        var selected =
                databases.stream()
                        .filter(
                                v ->
                                        java.util.Objects.equals(
                                                selectedDatabase,
                                                v.versionId() == null
                                                        ? v.sourceId()
                                                        : v.versionId()))
                        .findFirst();
        if (selected.isPresent()) table.getSelectionModel().select(selected.get());
        else table.getSelectionModel().selectFirst();
        selection.getStyleClass().add("source-panel");
        selection.setVisible(!databases.isEmpty());
        selection.setManaged(!databases.isEmpty());
        page.getChildren()
                .addAll(
                        actions,
                        table,
                        selection,
                        text(
                                "Defaults are selected per flavor. Provider storage is measured"
                                        + " separately from build object sizes.",
                                "muted"));
        if (service.activity(DataSourcesService.Id.QUESTIE).busy())
            page.getChildren()
                    .add(
                            liveAction(
                                    "Cancel",
                                    false,
                                    () -> {
                                        service.cancel(DataSourcesService.Id.QUESTIE);
                                        return java.util.concurrent.CompletableFuture
                                                .completedFuture(null);
                                    }));
    }

    private void downloadDialog() {
        var dialog = new javafx.scene.control.Dialog<javafx.util.Pair<String, String>>();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Download QuestieDB release");
        dialog.setHeaderText("Choose a release and game flavor");
        var release = new ComboBox<String>();
        release.setEditable(true);
        release.getItems().setAll(service.questie().releases());
        if (service.questie().latest() != null) release.setValue(service.questie().latest());
        release.setPromptText("Release tag, for example v1.0.10");
        release.setMaxWidth(Double.MAX_VALUE);
        var flavor = new ComboBox<String>();
        for (var value : com.vibeminer.questie.QuestieTypes.Flavor.values())
            flavor.getItems().add(value.upstream());
        flavor.getSelectionModel().selectFirst();
        flavor.setMaxWidth(Double.MAX_VALUE);
        dialog.getDialogPane()
                .setContent(
                        new VBox(
                                10,
                                text("Release", "muted"),
                                release,
                                text("Flavor", "muted"),
                                flavor));
        var confirm =
                new javafx.scene.control.ButtonType(
                        "Download", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane()
                .getButtonTypes()
                .addAll(confirm, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(
                button ->
                        button == confirm
                                ? new javafx.util.Pair<>(
                                        release.getEditor().getText().strip(), flavor.getValue())
                                : null);
        dialog.showAndWait()
                .ifPresent(
                        value -> {
                            command(service.download(value.getKey(), value.getValue()));
                            render();
                        });
    }

    private void liveListfile(VBox page) {
        var status = service.listfile();
        var totals = service.listfileBytes();
        page.getChildren()
                .add(
                        metrics(
                                metric(
                                        "KNOWN FILE NAMES",
                                        status == null || status.tag() == null
                                                ? "Not indexed"
                                                : String.format(
                                                        java.util.Locale.ROOT,
                                                        "%,d",
                                                        status.count()),
                                        "Across the community listfile"),
                                metric(
                                        "PREPARED INDEX",
                                        status == null ? "Unavailable" : status.state().name(),
                                        "Local filename lookup"),
                                metric(
                                        "STORED PAYLOADS",
                                        totals == null
                                                ? "Not measured"
                                                : bytes(
                                                        totals.original().storedBytes()
                                                                + totals.derived().storedBytes()),
                                        "Original listfile and prepared index")));
        if (status != null)
            page.getChildren()
                    .add(
                            panel(
                                    "Installed listfile",
                                    text(
                                            status.tag() == null
                                                    ? "No listfile installed"
                                                    : "Release " + status.tag(),
                                            "h2"),
                                    text(
                                            "Published: "
                                                    + (status.publishedAt() == null
                                                            ? "Unknown"
                                                            : status.publishedAt())
                                                    + " · Downloaded: "
                                                    + (status.fetchedAt() == null
                                                            ? "Never"
                                                            : status.fetchedAt()),
                                            "muted")));
        page.getChildren()
                .addAll(
                        liveAction("Update listfile", true, service::updateListfile),
                        text(
                                "An update checks the upstream release and prepares its filename"
                                        + " index. Build-specific coverage is shown in the build"
                                        + " Overview.",
                                "muted"));
        if (service.activity(DataSourcesService.Id.LISTFILE).busy())
            page.getChildren()
                    .add(
                            liveAction(
                                    "Cancel",
                                    false,
                                    () -> {
                                        service.cancel(DataSourcesService.Id.LISTFILE);
                                        return java.util.concurrent.CompletableFuture
                                                .completedFuture(null);
                                    }));
    }

    private Button liveAction(
            String label,
            boolean primary,
            java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> action) {
        Button button = new Button(label);
        button.getStyleClass().add("source-action");
        if (primary) button.getStyleClass().add("primary");
        button.setDisable(
                !label.equals("Cancel")
                        && (!service.policy(id()).enabled() || service.activity(id()).busy()));
        button.setOnAction(
                event -> {
                    command(action.get());
                    render();
                });
        return button;
    }

    private String checkedDate(DataSourcesService.Id source) {
        String value = service.checkedAt(source);
        try {
            return date(java.time.Instant.parse(value));
        } catch (java.time.format.DateTimeParseException ignored) {
            return value;
        }
    }

    private static String date(java.time.Instant value) {
        if (value == null) return "Unknown";
        return java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                        java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
                .withZone(java.time.ZoneId.systemDefault())
                .format(value);
    }

    private static String bytes(long value) {
        if (value < 0) return "Unavailable";
        if (value < 1024) return value + " B";
        if (value < 1048576)
            return String.format(java.util.Locale.ROOT, "%.1f KiB", value / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    private static VBox settings(String title, Node control, String description) {
        HBox row = new HBox(20, text(title, "value"), space(), control);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(10, row, text(description, "muted"));
    }

    private static HBox metrics(VBox... cards) {
        HBox row = new HBox(14, cards);
        row.setFillHeight(false);
        for (VBox card : cards) {
            card.setMaxWidth(Double.MAX_VALUE);
            card.setMinWidth(0);
            card.setPrefWidth(260);
            card.setMaxHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(card, Priority.ALWAYS);
        }
        return row;
    }

    private static VBox metric(String title, String value, String scope) {
        VBox card =
                new VBox(
                        8,
                        text(title, "eyebrow"),
                        text(value, "source-metric"),
                        text(scope, "muted"));
        card.getStyleClass().add("source-panel");
        return card;
    }

    private static VBox panel(String title, Node... content) {
        VBox panel = new VBox(14, text(title, "h2"));
        panel.getChildren().addAll(content);
        panel.getStyleClass().add("source-panel");
        return panel;
    }

    private static <T> void column(
            TableView<T> table, String name, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(name);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private static Region space() {
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        return space;
    }

    private static Label text(String value, String style) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }
}
