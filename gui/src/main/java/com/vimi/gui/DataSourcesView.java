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
                                "github.com/wowdev/WoWDBDefs"));
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
                                            : "Release checks",
                                    checks,
                                    "Last successful check: " + service.checkedAt(id)));
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
                                                text("Prepared: " + version.preparedAt(), "muted"));
                            if (!version.diagnostics().isEmpty())
                                selection
                                        .getChildren()
                                        .add(
                                                text(
                                                        String.join(" · ", version.diagnostics()),
                                                        "value"));
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
                                                            ? "Rebuild database"
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
